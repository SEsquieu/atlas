import { rm } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  AtlasRunner,
  FileSessionStore,
  createFakeCameraDevice,
  createFakeProvider,
  createSessionState
} from '@atlas/core';

const repoRoot = process.env.ATLAS_ROOT ?? process.env.INIT_CWD ?? process.cwd();
const storeRoot = resolve(repoRoot, '.atlas-cache/sessions');
await rm(resolve(repoRoot, '.atlas-cache'), { recursive: true, force: true });

const store = new FileSessionStore({ rootDir: storeRoot });

const session = createSessionState({
  sessionId: 'demo-session',
  name: 'Atlas Demo',
  goal: 'Validate fresh visual context behavior.',
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

await store.create(session);
await store.appendEvent(session.sessionId, { type: 'session.started' });

const runner = new AtlasRunner({
  store,
  provider: createFakeProvider({
    responseText: 'Fresh visual context captured and available.'
  }),
  devices: [createFakeCameraDevice({ imageSummary: 'A fake but current physical view.' })]
});

const heartbeat = await runner.runHeartbeatTick({ sessionId: session.sessionId });

const result = await runner.runUserTurn({
  sessionId: session.sessionId,
  turnId: 'demo-turn-1',
  text: 'What am I looking at?'
});

const events = await store.loadEvents(session.sessionId);

console.log(
  JSON.stringify(
    {
      sessionId: result.session.sessionId,
      status: result.session.status,
      eventCount: events.length,
      heartbeat: {
        shouldCapture: heartbeat.decision.shouldCapture,
        reason: heartbeat.decision.reason,
        observationId: heartbeat.observation?.id
      },
      userTurn: {
        plan: result.plan,
        refreshedObservation: result.refreshedObservation
          ? {
              id: result.refreshedObservation.id,
              mediaRef: result.refreshedObservation.mediaRef,
              summary: result.refreshedObservation.summary
            }
          : undefined,
        responseText: result.providerResult.responseText
      },
      latestImageId: result.session.perception.latestImageId,
      eventTypes: events.map((event) => event.type)
    },
    null,
    2
  )
);
