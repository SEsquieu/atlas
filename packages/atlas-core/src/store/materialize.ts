import type { AtlasSessionState, Observation, StateUpdate, VisualRefreshHealth } from '../types.js';
import type { AuditEvent } from '../audit/event-log.js';
import { classifyVisualRefreshLatency } from '../state/context-policy.js';

export function applySessionEvent(state: AtlasSessionState, event: AuditEvent): AtlasSessionState {
  switch (event.type) {
    case 'session.started':
      return markEventApplied(touch({ ...state, status: 'active' }, event.at), event);
    case 'session.paused':
      return markEventApplied(touch({ ...state, status: 'paused' }, event.at), event);
    case 'session.resumed':
      return markEventApplied(touch({ ...state, status: 'active' }, event.at), event);
    case 'session.ended':
      return markEventApplied(touch({ ...state, status: 'done' }, event.at), event);
    case 'observation.captured':
      return applyObservationCaptured(state, event);
    case 'state.updated':
      return applyStateUpdated(state, event);
    default:
      return markEventApplied(state, event);
  }
}

export function materializeSession(initialState: AtlasSessionState, events: AuditEvent[]): AtlasSessionState {
  return events.reduce((state, event) => applySessionEvent(state, event), normalizeSessionState(initialState));
}

export function materializeSessionCheckpoint(checkpoint: AtlasSessionState, events: AuditEvent[]): AtlasSessionState {
  return materializeSession(normalizeSessionState(checkpoint), selectPendingEvents(checkpoint, events));
}

/** Reads v0.0/v0.1 checkpoints without forcing a destructive storage migration. */
export function normalizeSessionState(state: AtlasSessionState): AtlasSessionState {
  return {
    ...state,
    workspace: state.workspace ?? { workspaceId: 'workspace:personal:local', kind: 'personal', name: 'Personal' },
    memory: {
      ...state.memory,
      scoped: state.memory.scoped ?? []
    }
  };
}

export function selectPendingEvents(checkpoint: AtlasSessionState, events: AuditEvent[]): AuditEvent[] {
  const lastEventId = checkpoint.eventCursor?.lastEventId;
  if (lastEventId) {
    const index = events.findIndex((event) => event.id === lastEventId);
    if (index >= 0) return events.slice(index + 1);
  }

  const lastEventAt = checkpoint.eventCursor?.lastEventAt ?? checkpoint.updatedAt;
  const lastEventAtMs = Date.parse(lastEventAt);
  if (!Number.isFinite(lastEventAtMs)) return events;
  return events.filter((event) => Date.parse(event.at) > lastEventAtMs);
}

function applyObservationCaptured(state: AtlasSessionState, event: AuditEvent): AtlasSessionState {
  const observation = getDataObject(event)?.observation as Observation | undefined;
  if (!observation) return markEventApplied(state, event);

  const recentObservations = [...state.recentObservations, observation].slice(-20);
  const latestObservationAt = observation.telemetry?.observedAt ?? observation.capturedAt;
  const latestObservationAvailableAt = observation.telemetry?.availableAt ?? event.at;
  const observedAtMs = Date.parse(latestObservationAt);
  const eventAtMs = Date.parse(event.at);
  const freshnessMs = Number.isFinite(observedAtMs) && Number.isFinite(eventAtMs) ? Math.max(0, eventAtMs - observedAtMs) : 0;
  const observationLatencyMs = observation.telemetry?.latencyMs?.total ?? durationMs(latestObservationAt, latestObservationAvailableAt);
  const analysisLatencyMs = observation.telemetry?.latencyMs?.analysis;
  const visualRefreshHealth = classifyVisualRefreshHealth({ observationLatencyMs, analysisLatencyMs, at: event.at });
  const stability = observation.quality?.motion === true ? 'transitioning' : observation.quality?.motion === false ? 'stable' : observation.type === 'image' ? 'stable' : state.perception.stability;
  const motionState = observation.quality?.motion === true ? 'turning' : observation.quality?.motion === false ? 'stationary' : observation.type === 'image' ? 'handheld-stable' : state.perception.motionState;
  const notes = observation.type === 'image'
    ? filterStaleObservationNotes(state.perception.notes)
    : state.perception.notes;

  return markEventApplied(
    touch(
      {
      ...state,
      recentObservations,
      perception: {
        ...state.perception,
        latestObservationAt,
        latestObservationAvailableAt,
        latestImageId: observation.type === 'image' ? observation.id : state.perception.latestImageId,
        latestLocationAt: observation.type === 'location' ? observation.capturedAt : state.perception.latestLocationAt,
        summary: observation.summary ?? observation.analyses?.find((analysis) => analysis.summary)?.summary ?? state.perception.summary,
        confidence: observation.quality?.confidence ?? observation.analyses?.find((analysis) => typeof analysis.confidence === 'number')?.confidence ?? state.perception.confidence,
        freshnessMs,
        observationLatencyMs,
        analysisLatencyMs,
        stability,
        motionState,
        blurScore: observation.quality?.blurScore ?? state.perception.blurScore,
        motionDetected: observation.quality?.motion ?? state.perception.motionDetected,
        health: {
          ...state.perception.health,
          visualRefresh: visualRefreshHealth
        },
        notes
      }
      },
      event.at
    ),
    event
  );
}

function applyStateUpdated(state: AtlasSessionState, event: AuditEvent): AtlasSessionState {
  const updates = getDataObject(event)?.updates as StateUpdate[] | undefined;
  if (!updates?.length) return markEventApplied(state, event);

  let next: AtlasSessionState = structuredClone(state);
  for (const update of updates) {
    next = setByPath(next, update.path, update.value) as AtlasSessionState;
  }
  return markEventApplied(touch(next, event.at), event);
}

function filterStaleObservationNotes(notes: string[] | undefined): string[] | undefined {
  const filtered = notes?.filter((note) => !/no observations captured yet/i.test(note));
  return filtered?.length ? filtered : undefined;
}

function classifyVisualRefreshHealth(input: {
  observationLatencyMs?: number;
  analysisLatencyMs?: number;
  at: string;
}): VisualRefreshHealth {
  const latencyMs = input.analysisLatencyMs ?? input.observationLatencyMs;
  const status = classifyVisualRefreshLatency(latencyMs);
  return {
    status,
    latencyMs: input.observationLatencyMs,
    analysisLatencyMs: input.analysisLatencyMs,
    since: input.at,
    notifyUser: status === 'degraded' || status === 'unavailable',
    reason:
      status === 'healthy'
        ? 'visual refresh latency is within normal range'
        : status === 'slow'
          ? 'visual refresh latency is elevated'
          : status === 'degraded'
            ? 'visual refresh latency is degraded; prefer stable context reuse over churn'
            : 'visual refresh path appears unavailable or too slow for normal cadence'
  };
}

function durationMs(start: string | undefined, end: string | undefined): number | undefined {
  if (!start || !end) return undefined;
  const startMs = Date.parse(start);
  const endMs = Date.parse(end);
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs)) return undefined;
  return Math.max(0, endMs - startMs);
}

function touch(state: AtlasSessionState, updatedAt: string): AtlasSessionState {
  return { ...state, updatedAt };
}

function markEventApplied(state: AtlasSessionState, event: AuditEvent): AtlasSessionState {
  return {
    ...state,
    eventCursor: {
      lastEventId: event.id,
      lastEventAt: event.at
    }
  };
}

function getDataObject(event: AuditEvent): Record<string, unknown> | undefined {
  return typeof event.data === 'object' && event.data !== null ? (event.data as Record<string, unknown>) : undefined;
}

function setByPath(target: unknown, path: string, value: unknown): unknown {
  const parts = path.split('.').filter(Boolean);
  if (parts.length === 0) return target;

  let cursor = target as Record<string, unknown>;
  for (const part of parts.slice(0, -1)) {
    const current = cursor[part];
    if (typeof current !== 'object' || current === null || Array.isArray(current)) {
      cursor[part] = {};
    }
    cursor = cursor[part] as Record<string, unknown>;
  }
  cursor[parts[parts.length - 1]!] = value;
  return target;
}
