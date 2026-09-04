import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createSessionState } from '../session/index.js';
import { materializeSession, materializeSessionCheckpoint, normalizeSessionState, selectPendingEvents } from './materialize.js';
import type { AuditEvent } from '../audit/event-log.js';

test('materializeSession records an explicit event cursor including same-timestamp events', () => {
  const session = createSessionState({
    sessionId: 'replay-session',
    now: '2026-05-05T16:00:00.000Z',
    provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
  });
  const events: AuditEvent[] = [
    { id: 'event-1', type: 'session.started', at: '2026-05-05T16:00:00.000Z' },
    { id: 'event-2', type: 'session.paused', at: '2026-05-05T16:00:00.000Z' }
  ];

  const materialized = materializeSession(session, events);

  assert.equal(materialized.status, 'paused');
  assert.equal(materialized.eventCursor?.lastEventId, 'event-2');
  assert.equal(materialized.eventCursor?.lastEventAt, '2026-05-05T16:00:00.000Z');
});

test('materializeSession derives freshness from observed time, not availability time', () => {
  const session = createSessionState({
    sessionId: 'freshness-session',
    now: '2026-05-05T16:00:00.000Z',
    provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
  });

  const materialized = materializeSession(session, [
    {
      id: 'event-1',
      type: 'observation.captured',
      at: '2026-05-05T16:00:45.000Z',
      data: {
        observation: {
          id: 'obs-1',
          type: 'image',
          capturedAt: '2026-05-05T16:00:03.000Z',
          deviceId: 'android-live',
          summary: 'A workbench.',
          telemetry: {
            observedAt: '2026-05-05T16:00:03.000Z',
            availableAt: '2026-05-05T16:00:44.000Z',
            latencyMs: {
              total: 41000,
              analysis: 38000
            }
          }
        }
      }
    }
  ]);

  assert.equal(materialized.perception.latestObservationAt, '2026-05-05T16:00:03.000Z');
  assert.equal(materialized.perception.latestObservationAvailableAt, '2026-05-05T16:00:44.000Z');
  assert.equal(materialized.perception.freshnessMs, 42000);
  assert.equal(materialized.perception.observationLatencyMs, 41000);
  assert.equal(materialized.perception.analysisLatencyMs, 38000);
  assert.equal(materialized.perception.health?.visualRefresh?.status, 'degraded');
  assert.equal(materialized.perception.health?.visualRefresh?.notifyUser, true);
  assert.equal(materialized.perception.stability, 'stable');
  assert.equal(materialized.perception.motionState, 'handheld-stable');
  assert.equal(materialized.perception.notes, undefined);
});

test('materializeSessionCheckpoint resumes after the explicit event cursor', () => {
  const session = createSessionState({
    sessionId: 'checkpoint-session',
    now: '2026-05-05T16:00:00.000Z',
    provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
  });
  const events: AuditEvent[] = [
    { id: 'event-1', type: 'session.started', at: '2026-05-05T16:00:00.000Z' },
    { id: 'event-2', type: 'session.paused', at: '2026-05-05T16:00:00.000Z' },
    { id: 'event-3', type: 'session.resumed', at: '2026-05-05T16:00:00.000Z' }
  ];

  const checkpoint = materializeSession(session, events.slice(0, 2));
  assert.deepEqual(selectPendingEvents(checkpoint, events).map((event) => event.id), ['event-3']);

  const materialized = materializeSessionCheckpoint(checkpoint, events);
  assert.equal(materialized.status, 'active');
  assert.equal(materialized.eventCursor?.lastEventId, 'event-3');
});

test('legacy checkpoints acquire a personal workspace and scoped-memory bucket', () => {
  const current = createSessionState({ sessionId: 'legacy', provider: { id: 'fake', adapter: 'fake' } });
  const legacy = structuredClone(current) as unknown as Record<string, unknown>;
  delete legacy.workspace;
  const memory = legacy.memory as Record<string, unknown>;
  delete memory.scoped;
  delete legacy.interaction;
  const normalized = normalizeSessionState(legacy as never);
  assert.equal(normalized.workspace.workspaceId, 'workspace:personal:local');
  assert.deepEqual(normalized.memory.scoped, []);
  assert.deepEqual(normalized.interaction, {});
});

test('materializeSession replays the clarification lifecycle deterministically', () => {
  const session = createSessionState({ sessionId: 'clarification-replay', provider: { id: 'fake', adapter: 'fake' } });
  const clarification = {
    clarificationId: 'clarification-1',
    sourceTurnId: 'turn-1',
    createdAt: '2026-09-04T10:00:00.000Z',
    deferredCount: 0,
    question: 'The left one or the right one?',
    reason: 'The referent changes the instruction.',
    ambiguity: 'referent' as const,
    blocking: true
  };
  const requested: AuditEvent = { id: 'event-1', type: 'clarification.requested', at: clarification.createdAt, data: { clarification } };
  const deferred: AuditEvent = { id: 'event-2', type: 'clarification.deferred', at: '2026-09-04T10:00:05.000Z', data: { clarificationId: clarification.clarificationId } };
  const resolved: AuditEvent = { id: 'event-3', type: 'clarification.resolved', at: '2026-09-04T10:00:10.000Z', data: { clarificationId: clarification.clarificationId } };

  const waiting = materializeSession(session, [requested, deferred]);
  assert.equal(waiting.interaction.pendingClarification?.deferredCount, 1);
  assert.equal(waiting.interaction.pendingClarification?.lastDeferredAt, deferred.at);
  assert.equal(materializeSession(session, [requested, deferred, resolved]).interaction.pendingClarification, undefined);
});
