import type { AuditEvent } from '../audit/event-log.js';
import type { AtlasSessionState, VisualRefreshHealth } from '../types.js';
import type { SessionStore } from '../store/types.js';
import { materializeSessionCheckpoint } from '../store/materialize.js';

export type SessionInspection = {
  sessionId: string;
  status: AtlasSessionState['status'];
  name?: string;
  goal?: string;
  provider: string;
  devices: Array<{
    id: string;
    adapter: string;
    name?: string;
    capabilities: string[];
  }>;
  perception: {
    latestObservationAt?: string;
    latestObservationAvailableAt?: string;
    latestImageId?: string;
    latestLocationAt?: string;
    summary?: string;
    confidence: number;
    freshnessMs: number;
    observationLatencyMs?: number;
    analysisLatencyMs?: number;
    visualRefreshHealth?: VisualRefreshHealth;
    stability: string;
    motionState?: string;
    notes?: string[];
  };
  observations: {
    count: number;
    latest?: {
      id: string;
      type: string;
      capturedAt: string;
      deviceId: string;
      mediaRef?: string;
      summary?: string;
      observedAt?: string;
      availableAt?: string;
      analysisCount: number;
    };
  };
  timing?: {
    userTurnMs?: number;
    captureRoundTripMs?: number;
    providerRoundTripMs?: number;
    bridge?: {
      totalMs?: number;
      captureMs?: number;
      stageMs?: number;
      analysisMs?: number;
    };
  };
  events: {
    count: number;
    byType: Record<string, number>;
    checkpoint?: {
      lastEventId?: string;
      lastEventAt?: string;
      materializedThroughLatestEvent: boolean;
    };
    recent: Array<{
      id: string;
      type: string;
      at: string;
      summary?: string;
    }>;
  };
};

export async function inspectSession(store: SessionStore, sessionId: string): Promise<SessionInspection | undefined> {
  const record = await store.load(sessionId);
  if (!record) return undefined;

  return summarizeSession(materializeSessionCheckpoint(record.state, record.events), record.events);
}

export function summarizeSession(state: AtlasSessionState, events: AuditEvent[], recentEventLimit = 12): SessionInspection {
  const latestObservation = state.recentObservations.at(-1);

  return {
    sessionId: state.sessionId,
    status: state.status,
    name: state.name,
    goal: state.goal,
    provider: state.provider.adapter,
    devices: state.devices.map((device) => ({
      id: device.id,
      adapter: device.adapter,
      name: device.name,
      capabilities: device.capabilities
    })),
    perception: {
      latestObservationAt: state.perception.latestObservationAt,
      latestObservationAvailableAt: state.perception.latestObservationAvailableAt,
      latestImageId: state.perception.latestImageId,
      latestLocationAt: state.perception.latestLocationAt,
      summary: state.perception.summary,
      confidence: state.perception.confidence,
      freshnessMs: state.perception.freshnessMs,
      observationLatencyMs: state.perception.observationLatencyMs,
      analysisLatencyMs: state.perception.analysisLatencyMs,
      visualRefreshHealth: state.perception.health?.visualRefresh,
      stability: state.perception.stability,
      motionState: state.perception.motionState,
      notes: state.perception.notes
    },
    observations: {
      count: state.recentObservations.length,
      latest: latestObservation
        ? {
            id: latestObservation.id,
            type: latestObservation.type,
            capturedAt: latestObservation.capturedAt,
            deviceId: latestObservation.deviceId,
            mediaRef: latestObservation.mediaRef,
            summary: latestObservation.summary,
            observedAt: latestObservation.telemetry?.observedAt,
            availableAt: latestObservation.telemetry?.availableAt,
            analysisCount: latestObservation.analyses?.length ?? 0
          }
        : undefined
    },
    timing: summarizeTiming(state, events),
    events: {
      count: events.length,
      byType: countEventsByType(events),
      checkpoint: summarizeCheckpoint(state, events),
      recent: events.slice(-recentEventLimit).map((event) => ({
        id: event.id,
        type: event.type,
        at: event.at,
        summary: summarizeEvent(event)
      }))
    }
  };
}

function summarizeTiming(state: AtlasSessionState, events: AuditEvent[]): SessionInspection['timing'] | undefined {
  const latestObservation = state.recentObservations.at(-1);
  const latestTurnStartIndex = findLastEventIndex(events, 'user.utterance');
  const turnEvents = latestTurnStartIndex >= 0 ? events.slice(latestTurnStartIndex) : events;

  const userTurnMs = durationBetween(findFirstEvent(turnEvents, 'user.utterance'), findFirstEvent(turnEvents, 'agent.speech'));
  const captureRequested = findFirstEvent(turnEvents, 'tool.requested', (event) => eventDataString(event, 'toolName') === 'capture_current_view');
  const captureCompleted = captureRequested
    ? findFirstEventAfter(turnEvents, captureRequested, 'tool.completed', (event) => eventDataString(event, 'toolName') === 'capture_current_view')
    : undefined;
  const providerRequested = findFirstEvent(turnEvents, 'provider.requested');
  const providerResponded = providerRequested ? findFirstEventAfter(turnEvents, providerRequested, 'provider.responded') : undefined;
  const bridge = extractBridgeTimings(latestObservation?.data);

  const timing = {
    userTurnMs,
    captureRoundTripMs: durationBetween(captureRequested, captureCompleted),
    providerRoundTripMs: durationBetween(providerRequested, providerResponded),
    bridge
  };

  return Object.values(timing).some((value) => value !== undefined) ? timing : undefined;
}

function findLastEventIndex(events: AuditEvent[], type: string): number {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    if (events[index]?.type === type) return index;
  }
  return -1;
}

function findFirstEvent(events: AuditEvent[], type: string, predicate?: (event: AuditEvent) => boolean): AuditEvent | undefined {
  return events.find((event) => event.type === type && (!predicate || predicate(event)));
}

function findFirstEventAfter(events: AuditEvent[], after: AuditEvent, type: string, predicate?: (event: AuditEvent) => boolean): AuditEvent | undefined {
  const afterIndex = events.findIndex((event) => event.id === after.id);
  return events.slice(afterIndex + 1).find((event) => event.type === type && (!predicate || predicate(event)));
}

function eventDataString(event: AuditEvent, key: string): string | undefined {
  const data = typeof event.data === 'object' && event.data !== null ? (event.data as Record<string, unknown>) : undefined;
  const value = data?.[key];
  return typeof value === 'string' ? value : undefined;
}

function durationBetween(start: AuditEvent | undefined, end: AuditEvent | undefined): number | undefined {
  if (!start || !end) return undefined;
  const startMs = Date.parse(start.at);
  const endMs = Date.parse(end.at);
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs)) return undefined;
  return Math.max(0, endMs - startMs);
}

function extractBridgeTimings(data: unknown): NonNullable<SessionInspection['timing']>['bridge'] | undefined {
  if (typeof data !== 'object' || data === null) return undefined;
  const bridge = (data as Record<string, unknown>).bridge;
  if (typeof bridge !== 'object' || bridge === null) return undefined;
  const timings = (bridge as Record<string, unknown>).timings;
  if (typeof timings !== 'object' || timings === null) return undefined;
  const source = timings as Record<string, unknown>;
  const result = {
    totalMs: readFiniteNumber(source.totalMs),
    captureMs: readFiniteNumber(source.captureMs),
    stageMs: readFiniteNumber(source.stageMs),
    analysisMs: readFiniteNumber(source.analysisMs)
  };
  return Object.values(result).some((value) => value !== undefined) ? result : undefined;
}

function readFiniteNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function summarizeCheckpoint(state: AtlasSessionState, events: AuditEvent[]): SessionInspection['events']['checkpoint'] {
  const latestEvent = events.at(-1);
  return {
    lastEventId: state.eventCursor?.lastEventId,
    lastEventAt: state.eventCursor?.lastEventAt,
    materializedThroughLatestEvent: !latestEvent || state.eventCursor?.lastEventId === latestEvent.id
  };
}

function countEventsByType(events: AuditEvent[]): Record<string, number> {
  return events.reduce<Record<string, number>>((counts, event) => {
    counts[event.type] = (counts[event.type] ?? 0) + 1;
    return counts;
  }, {});
}

function summarizeEvent(event: AuditEvent): string | undefined {
  const data = typeof event.data === 'object' && event.data !== null ? (event.data as Record<string, unknown>) : undefined;
  if (!data) return undefined;

  if (event.type === 'user.utterance' && typeof data.text === 'string') return data.text;
  if (event.type === 'agent.speech' && typeof data.text === 'string') return data.text;
  if (event.type === 'tool.requested' && typeof data.toolName === 'string') return `${data.toolName}${typeof data.reason === 'string' ? `: ${data.reason}` : ''}`;
  if (event.type === 'tool.completed' && typeof data.toolName === 'string') return `${data.toolName} completed`;
  if (event.type === 'observation.captured') {
    const observation = data.observation as { id?: string; summary?: string } | undefined;
    return observation?.summary ?? observation?.id;
  }
  if (event.type === 'provider.requested' && typeof data.provider === 'string') return `provider=${data.provider}`;
  if (event.type === 'provider.responded' && typeof data.provider === 'string') return `provider=${data.provider}`;
  if (event.type === 'heartbeat.tick') return 'heartbeat tick';

  return undefined;
}
