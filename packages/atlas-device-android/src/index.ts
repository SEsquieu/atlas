import type { CaptureImageOptions, DeviceAdapter, DeviceCapability, Observation } from '@atlas/core';

export type AndroidCaptureImage = (options?: CaptureImageOptions) => Promise<Observation>;

export type AndroidDeviceAdapterOptions = {
  id?: string;
  name?: string;
  captureImage: AndroidCaptureImage;
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
