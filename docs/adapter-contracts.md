# Adapter Contracts

This file tracks the stable interface boundary for Atlas adapters.

## Provider Adapter

Provider adapters accept a normalized session turn and return a normalized agent result.

```ts
interface AgentProviderAdapter {
  id: string;
  name: string;
  step(turn: NormalizedSessionTurn): Promise<NormalizedAgentResult>;
}
```

## Device Adapter

Device adapters expose physical capabilities such as image capture, location, speech, display, and haptics.

```ts
interface DeviceAdapter {
  id: string;
  name: string;
  capabilities(): Promise<DeviceCapability[]>;
  captureImage?(options?: CaptureImageOptions): Promise<Observation>;
  getLocation?(options?: LocationOptions): Promise<Observation>;
  speak?(text: string, options?: SpeakOptions): Promise<void>;
}
```

The authoritative definitions should live in `packages/atlas-core/src/types.ts` once implementation begins.
