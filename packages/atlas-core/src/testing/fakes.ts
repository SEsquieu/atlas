import type {
  AgentProviderAdapter,
  CaptureImageOptions,
  DeviceAdapter,
  DeviceCapability,
  NormalizedAgentResult,
  NormalizedSessionTurn,
  Observation
} from '../types.js';

export type FakeDeviceOptions = {
  id?: string;
  name?: string;
  imageSummary?: string;
};

export function createFakeCameraDevice(options: FakeDeviceOptions = {}): DeviceAdapter {
  const capabilities: DeviceCapability[] = ['camera.capture'];

  return {
    id: options.id ?? 'fake-camera',
    name: options.name ?? 'Fake Camera',
    capabilities: async () => capabilities,
    captureImage: async (captureOptions?: CaptureImageOptions): Promise<Observation> => ({
      id: crypto.randomUUID(),
      type: 'image',
      capturedAt: new Date().toISOString(),
      deviceId: options.id ?? 'fake-camera',
      mediaRef: 'fake://current-view.jpg',
      quality: {
        confidence: 0.9,
        motion: false
      },
      summary: options.imageSummary ?? `Fake image captured for: ${captureOptions?.reason ?? 'unspecified reason'}`
    })
  };
}

export type FakeProviderOptions = {
  id?: string;
  name?: string;
  responseText?: string;
  onTurn?: (turn: NormalizedSessionTurn) => NormalizedAgentResult | Promise<NormalizedAgentResult>;
};

export function createFakeProvider(options: FakeProviderOptions = {}): AgentProviderAdapter {
  return {
    id: options.id ?? 'fake-provider',
    name: options.name ?? 'Fake Provider',
    step: async (turn) => {
      if (options.onTurn) return options.onTurn(turn);
      return {
        turnId: turn.turnId,
        responseText:
          options.responseText ??
          `I have ${turn.observations.length} observation(s), visual context available: ${turn.contextStatus.visual?.available === true}.`
      };
    }
  };
}
