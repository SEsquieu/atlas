import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { AtlasRunner } from './atlas-runner.js';
import { createSessionState } from '../session/index.js';
import { FileSessionStore } from '../store/file-session-store.js';
import { createFakeCameraDevice, createFakeProvider, createFakeSpeakerDevice, createFakeVisualAnalyzer } from '../testing/fakes.js';

test('runUserTurn falls back to the latest observation when ask-time refresh fails', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-runner-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const session = createSessionState({
      sessionId: 'fallback-session',
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' },
      devices: [
        {
          id: 'failing-camera',
          adapter: '@atlas/core/testing',
          capabilities: ['camera.capture']
        }
      ]
    });

    await store.create(session);
    await store.appendEvent(session.sessionId, { type: 'session.started' });
    const staleCapturedAt = new Date(Date.now() - 10 * 60 * 1000).toISOString();
    await store.appendEvent(session.sessionId, {
      type: 'observation.captured',
      data: {
        observation: {
          id: 'stale-image',
          type: 'image',
          capturedAt: staleCapturedAt,
          deviceId: 'failing-camera',
          telemetry: {
            observedAt: staleCapturedAt,
            availableAt: staleCapturedAt,
            latencyMs: { total: 0, analysis: 0 }
          },
          summary: 'A stale but useful room view.',
          quality: { confidence: 0.9, motion: false }
        }
      }
    });

    let providerSawFallbackInstruction = false;
    let providerSawObservationCount = -1;
    const runner = new AtlasRunner({
      store,
      devices: [
        {
          id: 'failing-camera',
          name: 'Failing Camera',
          capabilities: async () => ['camera.capture'],
          captureImage: async () => {
            throw new Error('camera is asleep');
          }
        }
      ],
      provider: createFakeProvider({
        onTurn: (turn) => {
          providerSawObservationCount = turn.observations.length;
          providerSawFallbackInstruction = turn.instructions.some((instruction) => instruction.includes('refresh failed'));
          return {
            turnId: turn.turnId,
            responseText: 'Using the last observation, with a stale-context caveat.'
          };
        }
      })
    });

    const result = await runner.runUserTurn({
      sessionId: session.sessionId,
      text: 'What am I looking at?'
    });

    assert.equal(result.plan.shouldRefreshVisualContext, true);
    assert.equal(result.refreshedObservation, undefined);
    assert.equal(result.reusedLastObservationAfterRefreshFailure, true);
    assert.equal(result.refreshError, 'camera is asleep');
    assert.equal(providerSawObservationCount, 1);
    assert.equal(providerSawFallbackInstruction, true);
    assert.equal(result.providerResult.responseText, 'Using the last observation, with a stale-context caveat.');

    const events = await store.loadEvents(session.sessionId);
    assert.ok(events.some((event) => event.type === 'visual.refresh_failed'));
    assert.ok(events.some((event) => event.type === 'provider.responded'));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

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

test('runUserTurn delivers response through a bound speaker when available', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-runner-speaker-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const session = createSessionState({
      sessionId: 'speaker-session',
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' },
      devices: [
        { id: 'fake-camera', adapter: '@atlas/core/testing', capabilities: ['camera.capture'] },
        { id: 'fake-speaker', adapter: '@atlas/core/testing', capabilities: ['audio.speak'] }
      ]
    });

    await store.create(session);
    await store.appendEvent(session.sessionId, { type: 'session.started' });

    const spoken: string[] = [];
    const runner = new AtlasRunner({
      store,
      devices: [createFakeCameraDevice(), createFakeSpeakerDevice({ onSpeak: (text) => { spoken.push(text); } })],
      provider: createFakeProvider({ responseText: 'You are looking at the workbench.' })
    });

    const result = await runner.runUserTurn({
      sessionId: session.sessionId,
      text: 'What am I looking at?',
      mode: 'voice'
    });

    assert.equal(result.providerResult.responseText, 'You are looking at the workbench.');
    assert.deepEqual(spoken, ['You are looking at the workbench.']);

    const events = await store.loadEvents(session.sessionId);
    assert.deepEqual(
      events.slice(-3).map((event) => event.type),
      ['agent.speech', 'audio.speech_requested', 'audio.speech_completed']
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('runUserTurn preserves text fallback when bound speaker fails', async () => {
  const root = await mkdtemp(join(tmpdir(), 'atlas-runner-speaker-fallback-'));
  try {
    const store = new FileSessionStore({ rootDir: root });
    const session = createSessionState({
      sessionId: 'speaker-fallback-session',
      provider: { id: 'fake-provider', adapter: '@atlas/core/testing' },
      devices: [
        { id: 'fake-camera', adapter: '@atlas/core/testing', capabilities: ['camera.capture'] },
        { id: 'fake-speaker', adapter: '@atlas/core/testing', capabilities: ['audio.speak'] }
      ]
    });

    await store.create(session);
    await store.appendEvent(session.sessionId, { type: 'session.started' });

    const runner = new AtlasRunner({
      store,
      devices: [
        createFakeCameraDevice(),
        createFakeSpeakerDevice({
          onSpeak: () => {
            throw new Error('speaker offline');
          }
        })
      ],
      provider: createFakeProvider({ responseText: 'Text response is still available.' })
    });

    const result = await runner.runUserTurn({
      sessionId: session.sessionId,
      text: 'What am I looking at?',
      mode: 'voice'
    });

    assert.equal(result.providerResult.responseText, 'Text response is still available.');

    const events = await store.loadEvents(session.sessionId);
    assert.equal(events.some((event) => event.type === 'agent.speech'), true);
    const failed = events.find((event) => event.type === 'audio.speech_failed');
    assert.ok(failed);
    assert.match(JSON.stringify(failed.data), /speaker offline/);
    assert.match(JSON.stringify(failed.data), /textFallbackPreserved/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
