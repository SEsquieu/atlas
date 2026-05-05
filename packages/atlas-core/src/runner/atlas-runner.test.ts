import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { AtlasRunner } from './atlas-runner.js';
import { createSessionState } from '../session/index.js';
import { FileSessionStore } from '../store/file-session-store.js';
import { createFakeCameraDevice, createFakeProvider, createFakeVisualAnalyzer } from '../testing/fakes.js';

test('runUserTurn refreshes stale/missing visual context before provider call', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-runner-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const session = createSessionState({
      sessionId: 'test-session',
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

    let providerSawObservationCount = -1;
    const runner = new AtlasRunner({
      store,
      devices: [createFakeCameraDevice({ includeSummary: false })],
      analyzers: [createFakeVisualAnalyzer({ summary: 'Analyzer sees a current image.', confidence: 0.91 })],
      provider: createFakeProvider({
        onTurn: (turn) => {
          providerSawObservationCount = turn.observations.length;
          return {
            turnId: turn.turnId,
            responseText: 'ok'
          };
        }
      })
    });

    const result = await runner.runUserTurn({
      sessionId: session.sessionId,
      text: 'What am I looking at?'
    });

    assert.equal(result.plan.shouldRefreshVisualContext, true);
    assert.ok(result.refreshedObservation);
    assert.equal(providerSawObservationCount, 1);
    assert.equal(result.session.recentObservations.length, 1);
    assert.equal(Boolean(result.session.perception.latestImageId), true);
    assert.equal(result.session.perception.summary, 'Analyzer sees a current image.');
    assert.equal(result.session.recentObservations[0]?.analyses?.length, 1);

    const events = await store.loadEvents(session.sessionId);
    assert.deepEqual(
      events.map((event) => event.type),
      [
        'session.started',
        'user.utterance',
        'tool.requested',
        'tool.completed',
        'observation.captured',
        'provider.requested',
        'provider.responded',
        'agent.speech'
      ]
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
