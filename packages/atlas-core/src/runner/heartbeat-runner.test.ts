import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { AtlasRunner } from './atlas-runner.js';
import { createSessionState } from '../session/index.js';
import { FileSessionStore } from '../store/file-session-store.js';
import { createFakeCameraDevice, createFakeProvider, createFakeSpeakerDevice } from '../testing/fakes.js';
import type { AuditEvent } from '../audit/event-log.js';
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
    assert.equal(result.decision.freshness.hasVisualContext, false);
    assert.equal(result.decision.freshness.stale, true);
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
    let providerReviewCount = 0;
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
      provider: createFakeProvider({
        responseText: 'Scene changed, but no user interruption is needed.',
        onTurn: async (turn) => {
          providerReviewCount += 1;
          assert.equal(turn.trigger.type, 'heartbeat');
          assert.match(turn.instructions.join('\n'), /not automatically spoken/);
          return { turnId: turn.turnId, responseText: 'Scene changed, but no user interruption is needed.' };
        }
      })
    });

    const result = await runner.runHeartbeatTick({ sessionId: session.sessionId, now: nowMs });

    assert.equal(result.decision.shouldCapture, true);
    assert.equal(result.significance?.level, 'meaningful');
    assert.equal(result.significance?.shouldCallProvider, true);
    assert.equal(result.significance?.shouldNotifyUser, false);
    assert.equal(providerReviewCount, 1);
    assert.equal(result.providerResult?.responseText, 'Scene changed, but no user interruption is needed.');
    assert.equal(result.proactiveSpeechSuppressed, true);

    const events = await store.loadEvents(session.sessionId);
    assert.deepEqual(
      events.slice(-4).map((event) => event.type),
      ['perception.significance', 'provider.requested', 'provider.responded', 'agent.speech_suppressed']
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('runHeartbeatTick skips recently reviewed repeated meaningful scenes but permits review after cooldown', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-heartbeat-review-cooldown-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    let providerReviewCount = 0;
    let captureCount = 0;
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
      sessionId: 'review-cooldown-session',
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

    const summaries = [
      'A grocery aisle with shelves of cereal and a hanging price sign.',
      'The same grocery aisle shows cereal boxes, breakfast shelves, and a bright sale sign.',
      'A grocery aisle with cereal shelves and a large yellow clearance placard near breakfast boxes.'
    ];
    const runner = new AtlasRunner({
      store,
      devices: [
        {
          id: 'fake-camera',
          name: 'Fake Camera',
          capabilities: async () => ['camera.capture'],
          captureImage: async () => {
            const summary = summaries[captureCount] ?? summaries.at(-1)!;
            captureCount += 1;
            return {
              id: `grocery-${captureCount}`,
              type: 'image' as const,
              capturedAt: new Date().toISOString(),
              deviceId: 'fake-camera',
              mediaRef: `fake://grocery-${captureCount}.jpg`,
              summary,
              quality: { confidence: 0.9, motion: false }
            };
          }
        }
      ],
      provider: createFakeProvider({
        onTurn: async (turn) => {
          providerReviewCount += 1;
          return { turnId: turn.turnId, responseText: `reviewed ${turn.observations.at(-1)?.id ?? 'none'}` };
        }
      }),
      heartbeatPolicy: { providerReviewCooldownMs: 300_000 }
    });

    const first = await runner.runHeartbeatTick({ sessionId: session.sessionId, now: nowMs });
    const second = await runner.runHeartbeatTick({ sessionId: session.sessionId, now: nowMs + 90_000 });
    const third = await runner.runHeartbeatTick({ sessionId: session.sessionId, now: nowMs + 360_000 });

    assert.equal(first.significance?.level, 'meaningful');
    assert.equal(first.providerResult?.responseText, 'reviewed grocery-1');
    assert.equal(second.significance?.level, 'meaningful');
    assert.equal(second.providerResult, undefined);
    assert.match(second.providerReviewSkipped?.reason ?? '', /already received heartbeat provider review/);
    assert.equal(third.significance?.level, 'meaningful');
    assert.equal(third.providerResult?.responseText, 'reviewed grocery-3');
    assert.equal(providerReviewCount, 2);

    const events = await store.loadEvents(session.sessionId);
    assert.equal(events.filter((event) => event.type === 'provider.requested').length, 2);
    assert.equal(events.filter((event) => event.type === 'provider.responded').length, 2);
    assert.equal(events.filter((event) => event.type === 'provider.review_skipped').length, 1);
    assert.match(
      JSON.stringify(events.find((event) => event.type === 'provider.review_skipped')?.data ?? {}),
      /already received heartbeat provider review/
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('runHeartbeatTick only speaks proactively for actionable significance with permission', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-heartbeat-actionable-'));
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
      sessionId: 'actionable-session',
      now: new Date(nowMs - 90_000).toISOString(),
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
    });

    await store.create({
      ...session,
      status: 'active',
      permissions: {
        ...session.permissions,
        speak: 'proactive_allowed'
      },
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

    const spoken: string[] = [];
    const runner = new AtlasRunner({
      store,
      devices: [
        createFakeCameraDevice({ imageSummary: 'Smoke and sparks are coming from a power supply on the bench.' }),
        createFakeSpeakerDevice({ onSpeak: (text) => { spoken.push(text); } })
      ],
      provider: createFakeProvider({ responseText: 'Heads up: I see smoke or sparks near the bench power supply.' })
    });

    const result = await runner.runHeartbeatTick({ sessionId: session.sessionId, now: nowMs });

    assert.equal(result.significance?.level, 'actionable');
    assert.equal(result.significance?.shouldCallProvider, true);
    assert.equal(result.significance?.shouldNotifyUser, true);
    assert.equal(result.providerResult?.responseText, 'Heads up: I see smoke or sparks near the bench power supply.');
    assert.equal(result.proactiveSpeechSuppressed, false);
    assert.deepEqual(spoken, ['Heads up: I see smoke or sparks near the bench power supply.']);

    const events = await store.loadEvents(session.sessionId);
    assert.deepEqual(
      events.slice(-6).map((event) => event.type),
      ['perception.significance', 'provider.requested', 'provider.responded', 'agent.speech', 'audio.speech_requested', 'audio.speech_completed']
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('runHeartbeatTick treats degraded stale fallback as retryable, not stable', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-heartbeat-degraded-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const nowMs = Date.now();
    const now = new Date(nowMs).toISOString();
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
        latestObservationAt: new Date(nowMs - 130_000).toISOString(),
        latestImageId: 'slow-image',
        confidence: 0.9,
        freshnessMs: 130_000,
        stability: 'stable',
        motionState: 'stationary',
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
      provider: createFakeProvider(),
      heartbeatPolicy: { refreshFailureRetryMs: 120_000 }
    });

    const result = await runner.runHeartbeatTick({ sessionId: session.sessionId, now: nowMs });

    assert.equal(result.decision.shouldCapture, false);
    assert.equal(result.decision.cadence.mode, 'active-task');
    assert.equal(result.decision.cadence.nextDelayMs, 120_000);
    assert.equal(result.decision.fallback?.kind, 'refresh-deferred');
    assert.equal(result.decision.fallback?.retryDue, false);
    assert.equal(result.decision.freshness.staleAfterMs, 112_500);
    assert.match(result.decision.reason, /using degraded fallback temporarily/);
    assert.equal(result.observation, undefined);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('runHeartbeatTick retries capture after degraded fallback retry window elapses', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-heartbeat-degraded-retry-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const nowMs = Date.now();
    const session = createSessionState({
      sessionId: 'degraded-retry-session',
      now: new Date(nowMs - 180_000).toISOString(),
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
    });

    await store.create({
      ...session,
      status: 'active',
      perception: {
        ...session.perception,
        latestObservationAt: new Date(nowMs - 180_000).toISOString(),
        latestImageId: 'old-slow-image',
        confidence: 0.9,
        freshnessMs: 180_000,
        stability: 'stable',
        motionState: 'stationary',
        health: {
          visualRefresh: {
            status: 'unavailable',
            analysisLatencyMs: 130_000,
            since: new Date(nowMs - 130_000).toISOString(),
            reason: 'visual refresh path appears unavailable'
          }
        }
      }
    });

    const runner = new AtlasRunner({
      store,
      devices: [createFakeCameraDevice()],
      provider: createFakeProvider(),
      heartbeatPolicy: { refreshFailureRetryMs: 120_000 }
    });

    const result = await runner.runHeartbeatTick({ sessionId: session.sessionId, now: nowMs });

    assert.equal(result.decision.shouldCapture, true);
    assert.equal(result.decision.fallback, undefined);
    assert.match(result.decision.reason, /fallback retry window elapsed/);
    assert.ok(result.observation);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('runHeartbeatTick captures preemptively when context is refresh-due but not stale', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-heartbeat-refresh-due-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const nowMs = Date.now();
    const session = createSessionState({
      sessionId: 'refresh-due-session',
      now: new Date(nowMs - 50_000).toISOString(),
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
    });

    await store.create({
      ...session,
      status: 'active',
      perception: {
        ...session.perception,
        latestObservationAt: new Date(nowMs - 50_000).toISOString(),
        latestImageId: 'aging-image',
        confidence: 0.9,
        freshnessMs: 0,
        stability: 'stable',
        motionState: 'stationary',
        observationLatencyMs: 8_000
      }
    });

    const runner = new AtlasRunner({
      store,
      devices: [createFakeCameraDevice()],
      provider: createFakeProvider()
    });

    const result = await runner.runHeartbeatTick({ sessionId: session.sessionId, now: nowMs });

    assert.equal(result.decision.freshness.staleAfterMs, 56_250);
    assert.equal(result.decision.freshness.stale, false);
    assert.equal(result.decision.freshness.refreshDue, true);
    assert.equal(result.decision.freshness.expectedRefreshLatencyMs, 8_000);
    assert.equal(result.decision.shouldCapture, true);
    assert.equal(result.decision.cadence.mode, 'active-task');
    assert.match(result.decision.reason, /refresh is due before stale deadline/);
    assert.ok(result.observation);
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
    assert.equal(result.decision.freshness.contextAgeMs, 90_000);
    assert.match(result.decision.reason, /exceeds stale window/);
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

test('planHeartbeatTick ages context against weighted stale windows', () => {
  const nowMs = Date.now();
  const base = createSessionState({
    sessionId: 'weighted-freshness-session',
    provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
  });

  const stationary = planHeartbeatTick(
    {
      ...base,
      status: 'active',
      perception: {
        ...base.perception,
        latestObservationAt: new Date(nowMs - 30_000).toISOString(),
        latestImageId: 'stationary-image',
        confidence: 0.9,
        stability: 'stable',
        motionState: 'stationary'
      }
    },
    nowMs
  );
  assert.equal(stationary.shouldCapture, false);
  assert.equal(stationary.freshness.contextAgeMs, 30_000);
  assert.equal(stationary.freshness.staleAfterMs, 56_250);
  assert.equal(stationary.freshness.refreshDue, false);
  assert.equal(stationary.freshness.expectedRefreshLatencyMs, 10_000);
  assert.equal(stationary.freshness.safetyMarginMs, 2_000);
  assert.equal(stationary.cadence.nextDelayMs, 14_250);
  assert.match(stationary.cadence.reason, /capped to refresh deadline/);
  assert.match(stationary.reason, /within stale window/);

  const preemptive = planHeartbeatTick(
    {
      ...base,
      status: 'active',
      perception: {
        ...base.perception,
        latestObservationAt: new Date(nowMs - 45_000).toISOString(),
        latestImageId: 'stationary-near-stale-image',
        confidence: 0.9,
        stability: 'stable',
        motionState: 'stationary'
      }
    },
    nowMs
  );
  assert.equal(preemptive.shouldCapture, true);
  assert.equal(preemptive.freshness.stale, false);
  assert.equal(preemptive.freshness.refreshDue, true);
  assert.equal(preemptive.freshness.contextAgeMs, 45_000);
  assert.equal(preemptive.freshness.staleAfterMs, 56_250);
  assert.equal(preemptive.cadence.mode, 'active-task');
  assert.match(preemptive.reason, /refresh is due before stale deadline/);

  const walking = planHeartbeatTick(
    {
      ...base,
      status: 'active',
      perception: {
        ...base.perception,
        latestObservationAt: new Date(nowMs - 20_000).toISOString(),
        latestImageId: 'walking-image',
        confidence: 0.9,
        stability: 'stable',
        motionState: 'walking'
      }
    },
    nowMs
  );
  assert.equal(walking.shouldCapture, true);
  assert.equal(walking.freshness.staleAfterMs, 14_850);
  assert.equal(walking.cadence.mode, 'unstable-scene');

  const highRisk = planHeartbeatTick(
    {
      ...base,
      status: 'active',
      perception: {
        ...base.perception,
        latestObservationAt: new Date(nowMs - 16_000).toISOString(),
        latestImageId: 'risk-image',
        confidence: 0.9,
        stability: 'stable',
        motionState: 'stationary',
        relevance: { 'high-risk': 0.9 }
      }
    },
    nowMs
  );
  assert.equal(highRisk.shouldCapture, true);
  assert.equal(highRisk.freshness.staleAfterMs, 14_070);
  assert.equal(highRisk.cadence.mode, 'high-risk');
});

test('planHeartbeatTick defers stale stable context when capture budget is constrained', () => {
  const nowMs = Date.now();
  const base = createSessionState({
    sessionId: 'budget-session',
    provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
  });
  const session = {
    ...base,
    status: 'active' as const,
    perception: {
      ...base.perception,
      latestObservationAt: new Date(nowMs - 90_000).toISOString(),
      latestImageId: 'budget-image',
      confidence: 0.9,
      stability: 'stable' as const,
      motionState: 'stationary' as const
    }
  };

  const result = planHeartbeatTick(session, nowMs, {
    captureBudget: {
      status: 'constrained',
      capturesLastMinute: 5,
      capturesLastFiveMinutes: 5,
      reason: 'capture rate is constrained'
    }
  });

  assert.equal(result.shouldCapture, false);
  assert.equal(result.captureBudget?.status, 'constrained');
  assert.match(result.reason, /capture budget is constrained/);
  assert.equal(result.cadence.mode, 'stable-scene');
});

test('runHeartbeatTick defers stale stable context when recent capture rate is constrained', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-heartbeat-budget-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const nowMs = Date.now();
    const session = createSessionState({
      sessionId: 'budget-runner-session',
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
        stability: 'stable',
        motionState: 'stationary'
      }
    });

    for (let index = 0; index < 5; index += 1) {
      await store.appendEvent(session.sessionId, captureEvent(nowMs - index * 10_000));
    }

    const runner = new AtlasRunner({
      store,
      devices: [createFakeCameraDevice()],
      provider: createFakeProvider()
    });

    const result = await runner.runHeartbeatTick({ sessionId: session.sessionId, now: nowMs });

    assert.equal(result.decision.shouldCapture, false);
    assert.equal(result.decision.captureBudget?.status, 'constrained');
    assert.equal(result.observation, undefined);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

function captureEvent(atMs: number): Omit<AuditEvent, 'id'> {
  return {
    type: 'observation.captured',
    at: new Date(atMs).toISOString(),
    data: {
      observation: {
        id: crypto.randomUUID(),
        type: 'image',
        capturedAt: new Date(atMs).toISOString(),
        deviceId: 'fake-camera',
        telemetry: { latencyMs: { total: 1_000 } }
      }
    }
  };
}

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
    assert.equal(result.decision.cadence.nextDelayMs <= 33_000, true);
    assert.match(result.decision.cadence.reason, /capped to refresh deadline/);
    assert.equal(result.observation, undefined);

    const events = await store.loadEvents(session.sessionId);
    assert.deepEqual(events.map((event) => event.type), ['heartbeat.tick']);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
