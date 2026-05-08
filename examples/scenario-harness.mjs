#!/usr/bin/env node
import assert from 'node:assert/strict';
import { rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  AtlasRunner,
  FileSessionStore,
  createSessionState,
  materializeSessionCheckpoint
} from '@atlas/core';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));
const args = parseArgs(process.argv.slice(2));
const storeRoot = path.resolve(repoRoot, args.store ?? path.join('.atlas-runs', 'scenario-harness'));

try {
  if (args.clean !== false) await rm(storeRoot, { recursive: true, force: true });

  console.log('Atlas scenario harness (fake/no-camera)');
  console.log(`Store: ${storeRoot}`);
  console.log('Phone/camera: not used');
  console.log('');

  const results = [];
  results.push(await runFreshCaptureScenario());
  results.push(await runFreshReuseScenario());
  results.push(await runTransitionalPlaceRefreshScenario());
  results.push(await runHeartbeatUnchangedSilenceScenario());
  results.push(await runHeartbeatMeaningfulProviderSuppressionScenario());
  results.push(await runHeartbeatActionablePermissionScenario());

  for (const result of results) printResult(result);

  console.log('All scenario harness checks passed.');
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}

async function runFreshCaptureScenario() {
  const sessionId = 'fresh-capture-required';
  const store = new FileSessionStore({ rootDir: storeRoot });
  await createStartedSession(store, { sessionId });

  let captureCount = 0;
  const runner = new AtlasRunner({
    store,
    devices: [cameraDevice(() => {
      captureCount += 1;
      return fakeImage({ id: 'fresh-workbench', summary: 'Fresh fake observation: a repair bench with a multimeter and power supply.' });
    })],
    provider: providerFor('fresh capture')
  });

  const result = await runner.runUserTurn({ sessionId, text: 'What am I looking at?' });

  assert.equal(result.plan.shouldRefreshVisualContext, true);
  assert.equal(result.refreshedObservation?.id, 'fresh-workbench');
  assert.equal(captureCount, 1);

  return {
    name: 'fresh visual ask captures when context is missing',
    sessionId,
    kind: 'user-loop',
    passed: true,
    details: [`refresh=${result.plan.shouldRefreshVisualContext}`, `captures=${captureCount}`, `response=${result.providerResult.responseText}`]
  };
}

async function runFreshReuseScenario() {
  const sessionId = 'fresh-context-reuse';
  const store = new FileSessionStore({ rootDir: storeRoot });
  await createStartedSession(store, {
    sessionId,
    observation: fakeImage({ id: 'fresh-office', ageMs: 2_000, summary: 'Fresh fake observation: a desk with a laptop and coffee cup.' })
  });

  let captureCount = 0;
  const runner = new AtlasRunner({
    store,
    devices: [cameraDevice(() => {
      captureCount += 1;
      return fakeImage({ id: 'unexpected-refresh', summary: 'This should not be captured.' });
    })],
    provider: providerFor('fresh reuse')
  });

  const result = await runner.runUserTurn({ sessionId, text: 'What am I looking at?' });

  assert.equal(result.plan.shouldRefreshVisualContext, false);
  assert.equal(result.refreshedObservation, undefined);
  assert.equal(captureCount, 0);

  return {
    name: 'fresh stable context reuses cached observation',
    sessionId,
    kind: 'user-loop',
    passed: true,
    details: [`refresh=${result.plan.shouldRefreshVisualContext}`, `captures=${captureCount}`, `response=${result.providerResult.responseText}`]
  };
}

async function runTransitionalPlaceRefreshScenario() {
  const sessionId = 'transitional-place-refresh';
  const store = new FileSessionStore({ rootDir: storeRoot });
  await createStartedSession(store, {
    sessionId,
    observation: fakeImage({
      id: 'turning-hallway',
      ageMs: 2_000,
      summary: 'Unstable fake observation: a blurred hallway while turning.',
      motion: true,
      confidence: 0.45
    })
  });

  let captureCount = 0;
  const runner = new AtlasRunner({
    store,
    devices: [cameraDevice(() => {
      captureCount += 1;
      return fakeImage({ id: 'stable-room-sign', summary: 'Fresh fake observation: a room sign that says Receiving.' });
    })],
    provider: providerFor('transitional place refresh')
  });

  const result = await runner.runUserTurn({ sessionId, text: 'Am I in the right place?' });

  assert.equal(result.plan.shouldRefreshVisualContext, true);
  assert.equal(result.refreshedObservation?.id, 'stable-room-sign');
  assert.equal(captureCount, 1);

  return {
    name: 'place confirmation rejects transitional context',
    sessionId,
    kind: 'user-loop',
    passed: true,
    details: [`refresh=${result.plan.shouldRefreshVisualContext}`, `captures=${captureCount}`, `response=${result.providerResult.responseText}`]
  };
}

async function runHeartbeatUnchangedSilenceScenario() {
  const sessionId = 'heartbeat-unchanged-silence';
  const store = new FileSessionStore({ rootDir: storeRoot });
  await createStartedSession(store, {
    sessionId,
    observation: fakeImage({ id: 'old-desk', ageMs: 90_000, summary: 'A quiet desk with a laptop and coffee mug.' })
  });

  let providerCount = 0;
  const runner = new AtlasRunner({
    store,
    devices: [cameraDevice(() => fakeImage({ id: 'same-desk', summary: 'A quiet desk with a laptop and coffee mug.' }))],
    provider: providerFor('heartbeat unchanged', () => {
      providerCount += 1;
    })
  });

  const result = await runner.runHeartbeatTick({ sessionId });
  const events = await store.loadEvents(sessionId);

  assert.equal(result.decision.shouldCapture, true);
  assert.equal(result.significance?.level, 'none');
  assert.equal(result.providerResult, undefined);
  assert.equal(providerCount, 0);
  assert.equal(events.some((event) => event.type === 'agent.speech'), false);

  return {
    name: 'unchanged heartbeat stays silent and avoids provider',
    sessionId,
    kind: 'heartbeat',
    passed: true,
    details: [`significance=${result.significance?.level}`, `providerCalls=${providerCount}`, 'speech=no']
  };
}

async function runHeartbeatMeaningfulProviderSuppressionScenario() {
  const sessionId = 'heartbeat-meaningful-review';
  const store = new FileSessionStore({ rootDir: storeRoot });
  await createStartedSession(store, {
    sessionId,
    observation: fakeImage({ id: 'old-desk-meaningful', ageMs: 90_000, summary: 'A quiet desk with a laptop and coffee mug.' })
  });

  let providerCount = 0;
  const runner = new AtlasRunner({
    store,
    devices: [cameraDevice(() => fakeImage({ id: 'grocery-aisle', summary: 'A grocery aisle with shelves of cereal and a hanging price sign.' }))],
    provider: providerFor('meaningful heartbeat', (turn) => {
      providerCount += 1;
      assert.equal(turn.trigger.type, 'heartbeat');
    })
  });

  const result = await runner.runHeartbeatTick({ sessionId });
  const events = await store.loadEvents(sessionId);

  assert.equal(result.significance?.level, 'meaningful');
  assert.equal(result.significance?.shouldCallProvider, true);
  assert.equal(providerCount, 1);
  assert.equal(result.proactiveSpeechSuppressed, true);
  assert.equal(events.some((event) => event.type === 'agent.speech'), false);
  assert.equal(events.some((event) => event.type === 'agent.speech_suppressed'), true);

  return {
    name: 'meaningful heartbeat gets provider review but suppresses speech',
    sessionId,
    kind: 'heartbeat',
    passed: true,
    details: [`significance=${result.significance?.level}`, `providerCalls=${providerCount}`, `speechSuppressed=${result.proactiveSpeechSuppressed}`]
  };
}

async function runHeartbeatActionablePermissionScenario() {
  const sessionId = 'heartbeat-actionable-escalation';
  const store = new FileSessionStore({ rootDir: storeRoot });
  await createStartedSession(store, {
    sessionId,
    permissions: { speak: 'proactive_allowed' },
    observation: fakeImage({ id: 'old-bench-safe', ageMs: 90_000, summary: 'A normal electronics bench with tools arranged safely.' })
  });

  let providerCount = 0;
  const runner = new AtlasRunner({
    store,
    devices: [cameraDevice(() => fakeImage({ id: 'bench-smoke', summary: 'Smoke and sparks are coming from a power supply on the bench.' }))],
    provider: providerFor('actionable heartbeat', () => {
      providerCount += 1;
    }, 'Heads up: I see smoke or sparks near the bench power supply.')
  });

  const result = await runner.runHeartbeatTick({ sessionId });
  const events = await store.loadEvents(sessionId);

  assert.equal(result.significance?.level, 'actionable');
  assert.equal(result.significance?.shouldNotifyUser, true);
  assert.equal(providerCount, 1);
  assert.equal(result.proactiveSpeechSuppressed, false);
  assert.equal(events.some((event) => event.type === 'agent.speech'), true);

  return {
    name: 'actionable heartbeat can escalate when speech is permitted',
    sessionId,
    kind: 'heartbeat',
    passed: true,
    details: [`significance=${result.significance?.level}`, `providerCalls=${providerCount}`, 'speech=yes']
  };
}

async function createStartedSession(store, input) {
  const session = createSessionState({
    sessionId: input.sessionId,
    provider: { id: 'fake-provider', adapter: '@atlas/core/testing' },
    devices: [{ id: 'fake-camera', adapter: '@atlas/core/testing', capabilities: ['camera.capture'] }]
  });
  await store.create({
    ...session,
    permissions: {
      ...session.permissions,
      ...(input.permissions ?? {})
    }
  });
  await store.appendEvent(input.sessionId, { type: 'session.started' });
  if (input.observation) {
    await store.appendEvent(input.sessionId, {
      type: 'observation.captured',
      data: { observation: input.observation, reason: 'seed fake visual context' }
    });
  }
  const record = await store.load(input.sessionId);
  assert.ok(record);
  await store.saveState(materializeSessionCheckpoint(record.state, record.events));
}

function cameraDevice(onCapture) {
  return {
    id: 'fake-camera',
    name: 'Fake no-camera device',
    capabilities: async () => ['camera.capture'],
    captureImage: async () => onCapture()
  };
}

function providerFor(label, onTurn, responseText) {
  return {
    id: 'fake-provider',
    name: 'Fake Provider',
    step: async (turn) => {
      onTurn?.(turn);
      const latest = turn.observations.at(-1);
      return {
        turnId: turn.turnId,
        responseText: responseText ?? `${label}: ${latest?.summary ?? 'no observation'}`
      };
    }
  };
}

function fakeImage({ id, ageMs = 0, summary, motion = false, confidence = 0.9 }) {
  const observedAt = new Date(Date.now() - ageMs).toISOString();
  const availableAt = new Date(Date.parse(observedAt) + 150).toISOString();
  return {
    id,
    type: 'image',
    capturedAt: observedAt,
    deviceId: 'fake-camera',
    mediaRef: `fake://${id}.jpg`,
    telemetry: {
      observedAt,
      availableAt,
      latencyMs: {
        total: 150,
        capture: 100,
        stage: 10,
        analysis: 40
      },
      source: 'atlas/fake-scenario-harness'
    },
    quality: {
      confidence,
      motion
    },
    summary,
    analyses: [
      {
        id: `${id}-summary`,
        observationId: id,
        kind: 'visual-summary',
        producedBy: 'atlas/fake-scenario-harness',
        createdAt: availableAt,
        confidence,
        summary
      }
    ]
  };
}

function printResult(result) {
  console.log(`✓ ${result.name}`);
  console.log(`  kind: ${result.kind}`);
  console.log(`  session: ${result.sessionId}`);
  for (const detail of result.details) console.log(`  ${detail}`);
  console.log('');
}

function parseArgs(raw) {
  const parsed = {};
  for (let index = 0; index < raw.length; index += 1) {
    const arg = raw[index];
    if (arg === '--store') parsed.store = raw[++index];
    else if (arg === '--no-clean') parsed.clean = false;
    else if (arg === '--help' || arg === '-h') {
      console.log('Usage: npm run demo:scenarios -- [--store .atlas-runs/scenario-harness] [--no-clean]');
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return parsed;
}
