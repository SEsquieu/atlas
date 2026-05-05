import assert from 'node:assert/strict';
import { test } from 'node:test';
import { AndroidBridgeCaptureError, createAndroidBridgeDeviceAdapter, normalizeAndroidBridgeCaptureResult } from './index.js';

test('normalizeAndroidBridgeCaptureResult maps bridge media and summary into observation analysis', () => {
  const observation = normalizeAndroidBridgeCaptureResult(
    {
      imagePath: 'C:/workspace/images/android-capture.jpg',
      summary: 'A workbench with tools on it.',
      analysis: { confidence: 0.82 },
      node: 'pixel-test',
      facing: 'back',
      capturedAt: '2026-05-05T15:00:00.000Z'
    },
    { deviceId: 'android-test' }
  );

  assert.equal(observation.type, 'image');
  assert.equal(observation.deviceId, 'android-test');
  assert.equal(observation.mediaRef, 'C:/workspace/images/android-capture.jpg');
  assert.equal(observation.summary, 'A workbench with tools on it.');
  assert.equal(observation.quality?.confidence, 0.82);
  assert.equal(observation.analyses?.length, 1);
  assert.equal(observation.analyses?.[0]?.kind, 'visual-summary');
  assert.equal(observation.analyses?.[0]?.producedBy, 'openclaw/android-camera-bridge');
});

test('normalizeAndroidBridgeCaptureResult preserves OpenClaw bridge details and timing telemetry', () => {
  const observation = normalizeAndroidBridgeCaptureResult(
    {
      details: {
        workspaceImagePath: 'C:/workspace/images/back-capture.jpg',
        workspaceLatestPath: 'C:/workspace/images/latest-back.jpg',
        sourceTempPath: 'C:/Temp/openclaw-camera-snap.jpg',
        analysisText: 'A bright office corner with a desk.',
        analysisMode: 'openclaw',
        analysisState: 'ok',
        node: 'Galaxy S22 Ultra',
        facing: 'back',
        timings: {
          totalMs: 7358,
          captureMs: 3861,
          stageMs: 15,
          analysisMs: 3482
        }
      }
    },
    { deviceId: 'android-live', fallbackCapturedAt: '2026-05-05T19:38:14.676Z' }
  );

  assert.equal(observation.mediaRef, 'C:/workspace/images/back-capture.jpg');
  assert.equal(observation.summary, 'A bright office corner with a desk.');

  const bridgeData = (observation.data as { bridge: Record<string, unknown> }).bridge;
  assert.equal(bridgeData.node, 'Galaxy S22 Ultra');
  assert.equal(bridgeData.analysisMode, 'openclaw');
  assert.deepEqual(bridgeData.timings, {
    totalMs: 7358,
    captureMs: 3861,
    stageMs: 15,
    analysisMs: 3482
  });
});

test('normalizeAndroidBridgeCaptureResult rejects invalid bridge results', () => {
  assert.throws(
    () => normalizeAndroidBridgeCaptureResult(undefined as never, { deviceId: 'android-test' }),
    AndroidBridgeCaptureError
  );
});

test('createAndroidBridgeDeviceAdapter delegates capture to injected bridge function', async () => {
  const adapter = createAndroidBridgeDeviceAdapter({
    id: 'android-injected',
    analyze: true,
    analysisMode: 'ollama',
    captureWithBridge: async (options) => {
      assert.equal(options?.facing, 'back');
      assert.equal(options?.analyze, true);
      assert.equal(options?.analysisMode, 'ollama');
      assert.equal(options?.reason, 'verify current view');
      return {
        mediaRef: 'fake://android.jpg',
        visionSummary: 'Injected bridge summary.',
        capturedAt: '2026-05-05T15:01:00.000Z'
      };
    }
  });

  const observation = await adapter.captureImage?.({ reason: 'verify current view', quality: 'medium' });

  assert.equal(observation?.deviceId, 'android-injected');
  assert.equal(observation?.mediaRef, 'fake://android.jpg');
  assert.equal(observation?.summary, 'Injected bridge summary.');
  assert.equal(observation?.analyses?.length, 1);
});

test('createAndroidBridgeDeviceAdapter normalizes injected bridge failures', async () => {
  const adapter = createAndroidBridgeDeviceAdapter({
    captureWithBridge: async () => {
      throw new Error('camera unavailable');
    }
  });

  assert.ok(adapter.captureImage);
  await assert.rejects(() => adapter.captureImage!({ reason: 'test failure' }), AndroidBridgeCaptureError);
});
