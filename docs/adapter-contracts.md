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

Future provider results may include sideband suggestions alongside conversational text:

```ts
interface ProviderSidebandResult {
  responseText: string;
  artifacts?: SupportingArtifactCandidate[];
  routeHints?: RouteHint[];
  beliefCandidates?: BeliefCandidate[];
  toolCalls?: ToolCallCandidate[];
}
```

Sideband fields are advisory. Atlas Core validates and admits them independently. Provider adapters must not directly write physical session truth, session memory, or tool/action outcomes.

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

## Inference Sidecar Contract Shape

Future sidecars may run in parallel to enrich context, but they must not block the primary response path.

Every sidecar job should declare:

- reason
- priority
- token budget
- latency budget
- explicit write destination
- permission to be ignored

No sidecar should run merely because it is available.
