import type { CaptureImageOptions, DeviceAdapter, DeviceCapability, Observation } from '@atlas/core';
import {
  normalizeAndroidBridgeCaptureResult,
  normalizeAndroidBridgeError,
  type AndroidBridgeCaptureOptions,
  type AndroidBridgeCaptureResult
} from './bridge-result.js';

export type AndroidCaptureImage = (options?: CaptureImageOptions) => Promise<Observation>;

export type AndroidBridgeCapture = (options?: AndroidBridgeCaptureOptions) => Promise<AndroidBridgeCaptureResult>;

export type AndroidDeviceAdapterOptions = {
  id?: string;
  name?: string;
  captureImage: AndroidCaptureImage;
};

export type AndroidBridgeDeviceAdapterOptions = {
  id?: string;
  name?: string;
  facing?: 'back' | 'front';
  analyze?: boolean;
  analysisMode?: 'ollama' | 'openclaw' | 'none' | string;
  captureWithBridge: AndroidBridgeCapture;
};

export function createAndroidDeviceAdapter(options: AndroidDeviceAdapterOptions): DeviceAdapter {
  const capabilities: DeviceCapability[] = ['camera.capture'];

  return {
    id: options.id ?? 'android-default',
    name: options.name ?? 'Android Device',
    capabilities: async () => capabilities,
    captureImage: options.captureImage
  };
}

export function createAndroidBridgeDeviceAdapter(options: AndroidBridgeDeviceAdapterOptions): DeviceAdapter {
  const deviceId = options.id ?? 'android-bridge-default';

  return createAndroidDeviceAdapter({
    id: deviceId,
    name: options.name ?? 'Android Bridge Device',
    captureImage: async (captureOptions) => {
      try {
        const result = await options.captureWithBridge({
          ...captureOptions,
          facing: options.facing ?? 'back',
          analyze: options.analyze ?? true,
          analysisMode: options.analysisMode ?? 'ollama'
        });

        return normalizeAndroidBridgeCaptureResult(result, {
          deviceId,
          producedBy: 'openclaw/android-camera-bridge'
        });
      } catch (error) {
        throw normalizeAndroidBridgeError(error);
      }
    }
  });
}

export { AndroidBridgeCaptureError, normalizeAndroidBridgeCaptureResult, normalizeAndroidBridgeError } from './bridge-result.js';
export type { AndroidBridgeCaptureOptions, AndroidBridgeCaptureResult } from './bridge-result.js';
export { createAndroidBridgeCommandDeviceAdapter, runAndroidBridgeCommand } from './command-bridge.js';
export type { AndroidBridgeCommandDeviceAdapterOptions, AndroidBridgeCommandOptions } from './command-bridge.js';
