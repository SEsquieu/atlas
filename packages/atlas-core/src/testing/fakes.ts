import type {
  AgentProviderAdapter,
  CaptureImageOptions,
  DeviceAdapter,
  DeviceCapability,
  NormalizedAgentResult,
  NormalizedSessionTurn,
  Observation,
  ObservationAnalysis,
  PerceptionAnalyzerAdapter
} from '../types.js';

export type FakeDeviceOptions = {
  id?: string;
  name?: string;
  imageSummary?: string;
  includeSummary?: boolean;
};

export type FakeSpeakerOptions = {
  id?: string;
  name?: string;
  onSpeak?: (text: string) => void | Promise<void>;
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
        motion: false
      },
      summary:
        options.includeSummary === false
          ? undefined
          : options.imageSummary ?? `Fake image captured for: ${captureOptions?.reason ?? 'unspecified reason'}`
    })
  };
}

export type FakeAnalyzerOptions = {
  id?: string;
  name?: string;
  summary?: string;
  confidence?: number;
};

export function createFakeVisualAnalyzer(options: FakeAnalyzerOptions = {}): PerceptionAnalyzerAdapter {
  return {
    id: options.id ?? 'fake-visual-analyzer',
    name: options.name ?? 'Fake Visual Analyzer',
    analyze: async (observation): Promise<ObservationAnalysis[]> => [
      {
        id: crypto.randomUUID(),
        observationId: observation.id,
        kind: 'visual-summary',
        producedBy: options.id ?? 'fake-visual-analyzer',
        createdAt: new Date().toISOString(),
        confidence: options.confidence ?? 0.88,
        summary: options.summary ?? 'Fake visual analyzer summary.'
      }
    ]
  };
}

export function createFakeSpeakerDevice(options: FakeSpeakerOptions = {}): DeviceAdapter {
  const capabilities: DeviceCapability[] = ['audio.speak'];

  return {
    id: options.id ?? 'fake-speaker',
    name: options.name ?? 'Fake Speaker',
    capabilities: async () => capabilities,
    speak: async (text) => {
      await options.onSpeak?.(text);
    }
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
