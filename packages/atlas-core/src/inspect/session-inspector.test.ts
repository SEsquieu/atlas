import assert from 'node:assert/strict';
import { test } from 'node:test';
import { summarizeSession } from './session-inspector.js';
import { createSessionState } from '../session/index.js';

test('summarizeSession returns compact state and event counts', () => {
  const session = createSessionState({
    sessionId: 'inspect-session',
    name: 'Inspect Me',
    goal: 'Make inspection useful.',
    provider: { id: 'fake-provider', adapter: '@atlas/core/testing' },
    devices: [
      {
        id: 'fake-camera',
        adapter: '@atlas/core/testing',
        name: 'Fake camera',
        capabilities: ['camera.capture']
      }
    ]
  });

  const inspection = summarizeSession(session, [
    { id: '1', type: 'session.started', at: '2026-05-05T14:00:00.000Z' },
    { id: '2', type: 'user.utterance', at: '2026-05-05T14:00:01.000Z', data: { text: 'What am I looking at?' } },
    { id: '3', type: 'agent.speech', at: '2026-05-05T14:00:02.000Z', data: { text: 'A fake view.' } }
  ]);

  assert.equal(inspection.sessionId, 'inspect-session');
  assert.equal(inspection.events.count, 3);
  assert.equal(inspection.events.byType['user.utterance'], 1);
  assert.equal(inspection.events.recent.at(-1)?.summary, 'A fake view.');
  assert.equal(inspection.devices[0]?.id, 'fake-camera');
});

test('summarizeSession reports latest turn and bridge timings', () => {
  const session = createSessionState({
    sessionId: 'timed-session',
    provider: { id: 'fake-provider', adapter: '@atlas/core/testing' }
  });
  const timedSession = {
    ...session,
    recentObservations: [
      {
        id: 'obs-1',
        type: 'image' as const,
        capturedAt: '2026-05-05T14:00:06.000Z',
        deviceId: 'fake-camera',
        data: {
          bridge: {
            timings: {
              totalMs: 5000,
              captureMs: 1200,
              stageMs: 10,
              analysisMs: 3790
            }
          }
        }
      }
    ]
  };

  const inspection = summarizeSession(timedSession, [
    { id: '1', type: 'user.utterance', at: '2026-05-05T14:00:00.000Z', data: { text: 'What am I looking at?' } },
    { id: '2', type: 'tool.requested', at: '2026-05-05T14:00:01.000Z', data: { toolName: 'capture_current_view' } },
    { id: '3', type: 'tool.completed', at: '2026-05-05T14:00:06.000Z', data: { toolName: 'capture_current_view' } },
    { id: '4', type: 'provider.requested', at: '2026-05-05T14:00:06.100Z' },
    { id: '5', type: 'provider.responded', at: '2026-05-05T14:00:08.600Z' },
    { id: '6', type: 'agent.speech', at: '2026-05-05T14:00:08.700Z', data: { text: 'A timed view.' } }
  ]);

  assert.equal(inspection.timing?.userTurnMs, 8700);
  assert.equal(inspection.timing?.captureRoundTripMs, 5000);
  assert.equal(inspection.timing?.providerRoundTripMs, 2500);
  assert.equal(inspection.timing?.bridge?.analysisMs, 3790);
});
