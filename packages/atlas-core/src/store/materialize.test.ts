import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createSessionState } from '../session/index.js';
import { materializeSession, materializeSessionCheckpoint, selectPendingEvents } from './materialize.js';
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
