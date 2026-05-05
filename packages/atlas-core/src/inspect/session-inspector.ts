import type { AuditEvent } from '../audit/event-log.js';
import type { AtlasSessionState } from '../types.js';
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
    latestImageId?: string;
    latestLocationAt?: string;
    summary?: string;
    confidence: number;
    freshnessMs: number;
    stability: string;
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
      analysisCount: number;
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
      latestImageId: state.perception.latestImageId,
      latestLocationAt: state.perception.latestLocationAt,
      summary: state.perception.summary,
      confidence: state.perception.confidence,
      freshnessMs: state.perception.freshnessMs,
      stability: state.perception.stability,
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
            analysisCount: latestObservation.analyses?.length ?? 0
          }
        : undefined
    },
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
