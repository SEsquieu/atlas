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
const storeRoot = path.resolve(repoRoot, args.store ?? path.join('.atlas-runs', 'warm-context-harness'));

try {
  if (args.clean !== false) await rm(storeRoot, { recursive: true, force: true });

  console.log('Atlas warm-context harness (fake/no-camera)');
  console.log(`Store: ${storeRoot}`);
  console.log('Phone/camera: not used');
  console.log('');

  const results = [];
  results.push(await runFreshReuseScenario());
  results.push(await runStaleRefreshScenario());
  results.push(await runRefreshFailureFallbackScenario());

  for (const result of results) printResult(result);

  console.log('');
  console.log('All warm-context harness checks passed.');
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}

async function runFreshReuseScenario() {
  const sessionId = 'fresh-reuse';
  const store = new FileSessionStore({ rootDir: storeRoot });
  await createStartedSession(store, sessionId, freshImage({
    id: 'fresh-office',
    ageMs: 2_000,
    summary: 'Fresh fake observation: a desk with a laptop and coffee cup.'
  }));

  let captureCount = 0;
  const runner = new AtlasRunner({
    store,
    devices: [cameraDevice({
      onCapture: () => {
        captureCount += 1;
        return freshImage({ id: 'unexpected-refresh', summary: 'This should not be captured.' });
      }
    })],
    provider: providerFor('fresh reuse')
  });

  const result = await runner.runUserTurn({ sessionId, text: 'What am I looking at?' });

  assert.equal(result.plan.shouldRefreshVisualContext, false);
  assert.equal(result.refreshedObservation, undefined);
  assert.equal(captureCount, 0);

  return {
    name: 'fresh context reuses cached observation',
    sessionId,
    plan: result.plan,
    captureCount,
    refreshedObservationId: result.refreshedObservation?.id,
    fallback: result.reusedLastObservationAfterRefreshFailure === true,
    responseText: result.providerResult.responseText
  };
}

async function runStaleRefreshScenario() {
  const sessionId = 'stale-refresh';
  const store = new FileSessionStore({ rootDir: storeRoot });
  await createStartedSession(store, sessionId, freshImage({
    id: 'stale-office',
    ageMs: 5 * 60_000,
    summary: 'Stale fake observation: an old office view.'
  }));

  let captureCount = 0;
  const runner = new AtlasRunner({
    store,
    devices: [cameraDevice({
      onCapture: () => {
        captureCount += 1;
        return freshImage({
          id: 'refreshed-hallway',
          summary: 'Fresh fake observation: a hallway with a blue sign.'
        });
      }
    })],
    provider: providerFor('stale refresh')
  });

  const result = await runner.runUserTurn({ sessionId, text: 'What am I looking at?' });

  assert.equal(result.plan.shouldRefreshVisualContext, true);
  assert.equal(result.refreshedObservation?.id, 'refreshed-hallway');
  assert.equal(captureCount, 1);

  return {
    name: 'stale context refreshes before answer',
    sessionId,
    plan: result.plan,
    captureCount,
    refreshedObservationId: result.refreshedObservation?.id,
    fallback: result.reusedLastObservationAfterRefreshFailure === true,
    responseText: result.providerResult.responseText
  };
}

async function runRefreshFailureFallbackScenario() {
  const sessionId = 'refresh-failure-fallback';
  const store = new FileSessionStore({ rootDir: storeRoot });
  await createStartedSession(store, sessionId, freshImage({
    id: 'stale-storage-room',
    ageMs: 5 * 60_000,
    summary: 'Stale fake observation: a storage room with shelves and boxes.'
  }));

  let captureCount = 0;
  const runner = new AtlasRunner({
    store,
    devices: [cameraDevice({
      onCapture: () => {
        captureCount += 1;
        throw new Error('fake camera unavailable');
      }
    })],
    provider: providerFor('refresh failure fallback')
  });

  const result = await runner.runUserTurn({ sessionId, text: 'What am I looking at?' });

  assert.equal(result.plan.shouldRefreshVisualContext, true);
  assert.equal(result.refreshedObservation, undefined);
  assert.equal(result.refreshError, 'fake camera unavailable');
  assert.equal(result.reusedLastObservationAfterRefreshFailure, true);
  assert.equal(captureCount, 1);
  assert.match(result.providerResult.responseText ?? '', /may be stale/i);

  return {
    name: 'refresh failure falls back to latest observation',
    sessionId,
    plan: result.plan,
    captureCount,
    refreshedObservationId: result.refreshedObservation?.id,
    refreshError: result.refreshError,
    fallback: result.reusedLastObservationAfterRefreshFailure === true,
    responseText: result.providerResult.responseText
  };
}

async function createStartedSession(store, sessionId, observation) {
  const session = createSessionState({
    sessionId,
    provider: { id: 'fake-provider', adapter: '@atlas/core/testing' },
    devices: [{ id: 'fake-camera', adapter: '@atlas/core/testing', capabilities: ['camera.capture'] }]
  });
  await store.create(session);
  await store.appendEvent(sessionId, { type: 'session.started' });
  await store.appendEvent(sessionId, {
    type: 'observation.captured',
    data: { observation, reason: 'seed fake visual context' }
  });
  const record = await store.load(sessionId);
  assert.ok(record);
  await store.saveState(materializeSessionCheckpoint(record.state, record.events));
}

function cameraDevice({ onCapture }) {
  return {
    id: 'fake-camera',
    name: 'Fake no-camera device',
    capabilities: async () => ['camera.capture'],
    captureImage: async () => onCapture()
  };
}

function providerFor(label) {
  return {
    id: 'fake-provider',
    name: 'Fake Provider',
    step: async (turn) => {
      const latest = turn.observations.at(-1);
      const staleInstruction = turn.instructions.some((instruction) => /may be stale|refresh failed/i.test(instruction));
      return {
        turnId: turn.turnId,
        responseText: `${label}: ${latest?.summary ?? 'no observation'}${staleInstruction ? ' (context may be stale)' : ''}`
      };
    }
  };
}

function freshImage({ id, ageMs = 0, summary }) {
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
      source: 'atlas/fake-warm-context-harness'
    },
    quality: {
      confidence: 0.9,
      motion: false
    },
    summary,
    analyses: [
      {
        id: `${id}-summary`,
        observationId: id,
        kind: 'visual-summary',
        producedBy: 'atlas/fake-warm-context-harness',
        createdAt: availableAt,
        confidence: 0.9,
        summary
      }
    ]
  };
}

function printResult(result) {
  console.log(`✓ ${result.name}`);
  console.log(`  session: ${result.sessionId}`);
  console.log(`  plan: refresh=${result.plan.shouldRefreshVisualContext} decision=${result.plan.visualFreshness?.decision ?? 'n/a'} reason=${result.plan.reason}`);
  console.log(`  captures: ${result.captureCount}`);
  console.log(`  refreshed: ${result.refreshedObservationId ?? 'no'}`);
  if (result.refreshError) console.log(`  refresh error: ${result.refreshError}`);
  console.log(`  fallback: ${result.fallback ? 'yes' : 'no'}`);
  console.log(`  response: ${result.responseText}`);
  console.log('');
}

function parseArgs(raw) {
  const parsed = {};
  for (let index = 0; index < raw.length; index += 1) {
    const arg = raw[index];
    if (arg === '--store') parsed.store = raw[++index];
    else if (arg === '--no-clean') parsed.clean = false;
    else if (arg === '--help' || arg === '-h') {
      console.log('Usage: npm run demo:warm-context -- [--store .atlas-runs/warm-context-harness] [--no-clean]');
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return parsed;
}
