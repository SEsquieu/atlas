import { rm } from 'node:fs/promises';
import { resolve } from 'node:path';
import { AtlasRunner, FileSessionStore, createFakeProvider, createSessionState, inspectSession } from '@atlas/core';
import { createAndroidBridgeDeviceAdapter, type AndroidBridgeCapture } from '@atlas/device-android';

const repoRoot = process.env.ATLAS_ROOT ?? process.env.INIT_CWD ?? process.cwd();
const storeRoot = resolve(repoRoot, '.atlas-cache/android-bridge-sessions');
await rm(resolve(repoRoot, '.atlas-cache/android-bridge-sessions'), { recursive: true, force: true });

const bridgeCapture: AndroidBridgeCapture = async (options) => ({
  mediaRef: 'fake://android-bridge/current-view.jpg',
  summary: `Android bridge saw a stable scene for: ${options?.reason ?? 'unspecified reason'}`,
  analysis: {
    confidence: 0.93,
    mode: options?.analysisMode,
    prompt: options?.prompt
  },
  node: 'fake-android-node',
  facing: options?.facing ?? 'back',
  capturedAt: new Date().toISOString()
});

const store = new FileSessionStore({ rootDir: storeRoot });
const session = createSessionState({
  sessionId: 'android-bridge-demo',
  name: 'Android Bridge Demo',
  goal: 'Validate Android bridge wrapper shape without importing OpenClaw into Atlas Core.',
  provider: { id: 'fake-provider', adapter: '@atlas/core/testing' },
  devices: [
    {
      id: 'android-bridge-demo-device',
      adapter: '@atlas/device-android',
      name: 'Injected Android bridge',
      capabilities: ['camera.capture']
    }
  ]
});

await store.create(session);
await store.appendEvent(session.sessionId, { type: 'session.started' });

const runner = new AtlasRunner({
  store,
  provider: createFakeProvider(),
  devices: [
    createAndroidBridgeDeviceAdapter({
      id: 'android-bridge-demo-device',
      name: 'Injected Android bridge',
      captureWithBridge: bridgeCapture,
      analyze: true,
      analysisMode: 'ollama'
    })
  ]
});

const result = await runner.runUserTurn({
  sessionId: session.sessionId,
  turnId: 'android-bridge-demo-turn-1',
  text: 'What am I looking at?'
});

console.log(
  JSON.stringify(
    {
      sessionId: result.session.sessionId,
      refreshedObservation: result.refreshedObservation
        ? {
            id: result.refreshedObservation.id,
            mediaRef: result.refreshedObservation.mediaRef,
            summary: result.refreshedObservation.summary,
            analyses: result.refreshedObservation.analyses?.map((analysis) => ({
              kind: analysis.kind,
              producedBy: analysis.producedBy,
              confidence: analysis.confidence,
              summary: analysis.summary
            }))
          }
        : undefined,
      responseText: result.providerResult.responseText,
      inspection: await inspectSession(store, session.sessionId)
    },
    null,
    2
  )
);
