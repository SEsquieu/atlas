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
