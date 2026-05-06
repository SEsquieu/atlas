import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { AtlasRunner } from './atlas-runner.js';
import { createSessionState } from '../session/index.js';
import { FileSessionStore } from '../store/file-session-store.js';
import { createFakeCameraDevice, createFakeProvider } from '../testing/fakes.js';
import { planHeartbeatTick } from '../loops/heartbeat.js';

test('runHeartbeatTick captures context silently when active context is missing or unstable', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-heartbeat-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const session = createSessionState({
      sessionId: 'heartbeat-session',
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' },
      devices: [
        {
          id: 'fake-camera',
          adapter: '@atlas/core/testing',
          capabilities: ['camera.capture']
        }
      ]
    });

    await store.create(session);
    await store.appendEvent(session.sessionId, { type: 'session.started' });

    const runner = new AtlasRunner({
      store,
      devices: [createFakeCameraDevice()],
      provider: createFakeProvider()
    });

    const result = await runner.runHeartbeatTick({ sessionId: session.sessionId });

    assert.equal(result.decision.shouldCapture, true);
    assert.equal(result.decision.cadence.mode, 'active-task');
    assert.equal(result.decision.cadence.nextDelayMs, 30_000);
    assert.ok(result.observation);
    assert.equal(result.significance?.level, 'low');
    assert.equal(result.significance?.shouldCallProvider, false);
    assert.equal(result.session.recentObservations.length, 1);
    assert.equal(Boolean(result.session.perception.latestImageId), true);

    const events = await store.loadEvents(session.sessionId);
    assert.deepEqual(
      events.map((event) => event.type),
      ['session.started', 'heartbeat.tick', 'tool.requested', 'tool.completed', 'observation.captured', 'perception.significance']
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('runHeartbeatTick records meaningful significance when heartbeat capture changes the scene', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-heartbeat-significance-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const nowMs = Date.now();
    const previousObservation = {
      id: 'previous-desk',
      type: 'image' as const,
      capturedAt: new Date(nowMs - 90_000).toISOString(),
      deviceId: 'fake-camera',
      summary: 'A quiet desk with a laptop and coffee mug.',
      quality: { confidence: 0.9, motion: false }
    };
    const session = createSessionState({
      sessionId: 'significance-session',
      now: new Date(nowMs - 90_000).toISOString(),
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
    });

    await store.create({
      ...session,
      status: 'active',
      recentObservations: [previousObservation],
      perception: {
        ...session.perception,
        latestObservationAt: previousObservation.capturedAt,
        latestImageId: previousObservation.id,
        summary: previousObservation.summary,
        confidence: 0.9,
        freshnessMs: 90_000,
        stability: 'stable',
        motionState: 'stationary'
      }
    });

    const runner = new AtlasRunner({
      store,
      devices: [createFakeCameraDevice({ imageSummary: 'A grocery aisle with shelves of cereal and a hanging price sign.' })],
      provider: createFakeProvider()
    });

    const result = await runner.runHeartbeatTick({ sessionId: session.sessionId, now: nowMs });

    assert.equal(result.decision.shouldCapture, true);
    assert.equal(result.significance?.level, 'meaningful');
    assert.equal(result.significance?.shouldCallProvider, true);
    assert.equal(result.significance?.shouldNotifyUser, false);

    const events = await store.loadEvents(session.sessionId);
    assert.equal(events.at(-1)?.type, 'perception.significance');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('runHeartbeatTick defers stale stable context when refresh health is degraded', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-heartbeat-degraded-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const now = new Date().toISOString();
    const session = createSessionState({
      sessionId: 'degraded-session',
      now,
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
    });

    await store.create({
      ...session,
      status: 'active',
      perception: {
        ...session.perception,
        latestObservationAt: new Date(Date.now() - 90_000).toISOString(),
        latestImageId: 'slow-image',
        confidence: 0.9,
        freshnessMs: 90_000,
        stability: 'stable',
        health: {
          visualRefresh: {
            status: 'degraded',
            analysisLatencyMs: 80_000,
            since: now,
            reason: 'visual refresh latency is degraded'
          }
        }
      }
    });

    const runner = new AtlasRunner({
      store,
      devices: [createFakeCameraDevice()],
      provider: createFakeProvider()
    });

    const result = await runner.runHeartbeatTick({ sessionId: session.sessionId });

    assert.equal(result.decision.shouldCapture, false);
    assert.equal(result.decision.cadence.mode, 'stable-scene');
    assert.match(result.decision.reason, /deferring capture/);
    assert.equal(result.observation, undefined);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('runHeartbeatTick refreshes when latest observation age is stale even if stored freshness was fresh at capture time', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-heartbeat-wall-age-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const nowMs = Date.now();
    const session = createSessionState({
      sessionId: 'wall-age-session',
      now: new Date(nowMs - 90_000).toISOString(),
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
    });

    await store.create({
      ...session,
      status: 'active',
      perception: {
        ...session.perception,
        latestObservationAt: new Date(nowMs - 90_000).toISOString(),
        latestImageId: 'old-image',
        confidence: 0.9,
        freshnessMs: 0,
        stability: 'stable'
      }
    });

    const runner = new AtlasRunner({
      store,
      devices: [createFakeCameraDevice()],
      provider: createFakeProvider()
    });

    const result = await runner.runHeartbeatTick({ sessionId: session.sessionId, now: nowMs });

    assert.equal(result.decision.shouldCapture, true);
    assert.equal(result.decision.cadence.mode, 'active-task');
    assert.match(result.decision.reason, /stale/);
    assert.ok(result.observation);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('planHeartbeatTick exposes dynamic cadence modes and clamps configured delays', () => {
  const base = createSessionState({
    sessionId: 'cadence-session',
    provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
  });

  const idle = planHeartbeatTick(base);
  assert.equal(idle.cadence.mode, 'idle');
  assert.equal(idle.cadence.nextDelayMs, 300_000);

  const highRisk = planHeartbeatTick(
    {
      ...base,
      status: 'active',
      perception: {
        ...base.perception,
        latestObservationAt: new Date().toISOString(),
        latestImageId: 'risk-image',
        confidence: 0.95,
        stability: 'stable',
        relevance: { 'high-risk': 0.9 }
      }
    },
    Date.now(),
    { cadence: { 'high-risk': 1_000 }, minDelayMs: 2_000 }
  );
  assert.equal(highRisk.cadence.mode, 'high-risk');
  assert.equal(highRisk.cadence.nextDelayMs, 2_000);

  const moving = planHeartbeatTick({
    ...base,
    status: 'active',
    perception: {
      ...base.perception,
      latestObservationAt: new Date().toISOString(),
      latestImageId: 'moving-image',
      confidence: 0.8,
      stability: 'stable',
      motionState: 'walking'
    }
  });
  assert.equal(moving.cadence.mode, 'unstable-scene');
});

test('runHeartbeatTick stays quiet when context is fresh and stable', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-heartbeat-quiet-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const now = new Date().toISOString();
    const session = createSessionState({
      sessionId: 'quiet-session',
      now,
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
    });

    await store.create({
      ...session,
      status: 'active',
      perception: {
        ...session.perception,
        latestObservationAt: now,
        latestImageId: 'fresh-image',
        confidence: 0.9,
        freshnessMs: 100,
        stability: 'stable'
      }
    });

    const runner = new AtlasRunner({
      store,
      devices: [createFakeCameraDevice()],
      provider: createFakeProvider()
    });

    const result = await runner.runHeartbeatTick({ sessionId: session.sessionId });

    assert.equal(result.decision.shouldCapture, false);
    assert.equal(result.decision.cadence.mode, 'stable-scene');
    assert.equal(result.decision.cadence.nextDelayMs, 60_000);
    assert.equal(result.observation, undefined);

    const events = await store.loadEvents(session.sessionId);
    assert.deepEqual(events.map((event) => event.type), ['heartbeat.tick']);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
