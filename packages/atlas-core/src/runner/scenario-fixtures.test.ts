import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import type { AgentProviderAdapter, DeviceAdapter, Observation } from '../types.js';
import { createSessionState } from '../session/index.js';
import { FileSessionStore } from '../store/index.js';
import { AtlasRunner } from './atlas-runner.js';
import { createFakeProvider, createFakeVisualAnalyzer } from '../testing/index.js';

async function withScenarioStore<T>(name: string, run: (store: FileSessionStore) => Promise<T>): Promise<T> {
  const root = await mkdtemp(join(tmpdir(), name));
  try {
    return await run(new FileSessionStore({ rootDir: join(root, 'sessions') }));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

test('scenario: stale transitional place-check refreshes visual context before provider turn', async () => {
  await withScenarioStore('atlas-scenario-place-', async (store) => {
    const session = createSessionState({
      sessionId: 'place-check',
      now: '2026-05-05T16:00:00.000Z',
      provider: { id: 'place-provider', adapter: '@atlas/core/testing' },
      devices: [{ id: 'scenario-camera', adapter: '@atlas/core/testing', capabilities: ['camera.capture'] }]
    });
    await store.create({
      ...session,
      status: 'active',
      perception: {
        ...session.perception,
        latestImageId: 'old-image',
        latestObservationAt: '2026-05-05T15:00:00.000Z',
        confidence: 0.2,
        freshnessMs: 60 * 60 * 1000,
        stability: 'transitioning'
      }
    });

    const provider: AgentProviderAdapter = createFakeProvider({
      id: 'place-provider',
      onTurn(turn) {
        assert.equal(turn.contextStatus.visual?.available, true);
        assert.equal(turn.contextStatus.visual?.stability, 'stable');
        assert.equal(turn.observations.at(-1)?.deviceId, 'scenario-camera');
        return { turnId: turn.turnId, responseText: 'Fresh context says you are in the right place.' };
      }
    });
    const device: DeviceAdapter = {
      id: 'scenario-camera',
      name: 'Scenario Camera',
      capabilities: async () => ['camera.capture'],
      captureImage: async (): Promise<Observation> => ({
        id: 'fresh-place-image',
        type: 'image',
        capturedAt: '2026-05-05T16:01:00.000Z',
        deviceId: 'scenario-camera',
        mediaRef: 'fake://place-check.jpg',
        quality: { motion: false, confidence: 0.95 }
      })
    };

    const result = await new AtlasRunner({
      store,
      provider,
      devices: [device],
      analyzers: [createFakeVisualAnalyzer({ summary: 'A stable aisle marker is visible.', confidence: 0.95 })]
    }).runUserTurn({ sessionId: 'place-check', text: 'Am I in the right place?', turnId: 'place-turn' });

    assert.equal(result.plan.shouldRefreshVisualContext, true);
    assert.equal(result.refreshedObservation?.id, 'fresh-place-image');
    assert.equal(result.session.perception.summary, 'A stable aisle marker is visible.');
    assert.equal(result.session.eventCursor?.lastEventId !== undefined, true);
  });
});

test('scenario: provider swap receives the same materialized physical session shape', async () => {
  await withScenarioStore('atlas-scenario-provider-', async (store) => {
    const session = createSessionState({
      sessionId: 'provider-swap',
      provider: { id: 'alternate-provider', adapter: 'alternate-provider' },
      devices: [{ id: 'provider-swap-camera', adapter: '@atlas/core/testing', capabilities: ['camera.capture'] }]
    });
    await store.create({ ...session, status: 'active' });

    const observedProviders: string[] = [];
    const provider: AgentProviderAdapter = {
      id: 'alternate-provider',
      name: 'Alternate Provider',
      async step(turn) {
        observedProviders.push(turn.session.provider.id);
        return { turnId: turn.turnId, responseText: `provider=${turn.session.provider.id}` };
      }
    };
    const device: DeviceAdapter = {
      id: 'provider-swap-camera',
      name: 'Provider Swap Camera',
      capabilities: async () => ['camera.capture'],
      captureImage: async () => ({
        id: 'provider-swap-image',
        type: 'image',
        capturedAt: new Date().toISOString(),
        deviceId: 'provider-swap-camera',
        mediaRef: 'fake://provider-swap.jpg',
        quality: { motion: false, confidence: 0.9 }
      })
    };

    const result = await new AtlasRunner({
      store,
      provider,
      devices: [device],
      analyzers: [createFakeVisualAnalyzer()]
    }).runUserTurn({ sessionId: 'provider-swap', text: 'What am I looking at?', turnId: 'provider-turn' });

    assert.deepEqual(observedProviders, ['alternate-provider']);
    assert.equal(result.providerResult.responseText, 'provider=alternate-provider');
  });
});
