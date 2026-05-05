import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createAndroidBridgeDeviceAdapter, normalizeAndroidBridgeCaptureResult } from './index.js';

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
