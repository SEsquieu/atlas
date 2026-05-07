# Adapter Contracts

This file tracks the stable interface boundary for Atlas adapters.

See also `docs/native-mobile-adapter.md` for the target contract of a future Atlas-owned phone adapter. The current OpenClaw Android path is a proof adapter, not the architecture ceiling.

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

### Command-backed provider seam

The CLI can resolve a command-backed provider with adapter id:

```text
@atlas/provider-openclaw/command
```

The command receives the normalized Atlas turn as JSON in `ATLAS_PROVIDER_TURN` by default, or on stdin when `config.inputMode = "stdin"`. It may emit either:

- a JSON `NormalizedAgentResult`
- plain text, which Atlas wraps as `responseText`

Example config fragment:

```json
{
  "provider": {
    "id": "openclaw-command",
    "adapter": "@atlas/provider-openclaw/command",
    "config": {
      "command": "node",
      "args": ["examples/provider-wrapper.mjs"],
      "timeoutMs": 60000
    }
  }
}
```

This is an edge seam for live harnesses and OpenClaw wrappers. Atlas Core still only sees a normal `AgentProviderAdapter`.

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

### Command-backed Android bridge seam

The CLI can resolve a command-backed Android bridge device with adapter id:

```text
@atlas/device-android/bridge-command
```

The command receives capture options as JSON in `ATLAS_ANDROID_BRIDGE_OPTIONS` by default, or on stdin when `config.inputMode = "stdin"`. It must emit JSON shaped like the existing OpenClaw Android Camera Bridge result. Atlas normalizes that into `Observation` / `ObservationAnalysis`.

Example config fragment:

```json
{
  "devices": [
    {
      "id": "android-live",
      "adapter": "@atlas/device-android/bridge-command",
      "capabilities": ["camera.capture"],
      "config": {
        "command": "node",
        "args": ["examples/android-openclaw-basic/bridge-wrapper.mjs"],
        "analysisMode": "ollama",
        "timeoutMs": 60000
      }
    }
  ]
}
```

This keeps OpenClaw-specific bridge invocation at the edge while preserving Atlas Core's provider/device independence.

### Observation timing semantics

Device adapters should distinguish physical sample time from context availability time:

- `Observation.capturedAt` / `Observation.telemetry.observedAt`: when the physical world was sampled.
- `Observation.telemetry.availableAt`: when the staged/analyzed observation became available to Atlas.
- `Observation.telemetry.latencyMs`: transport/capture/stage/analysis timing, when known.

Freshness policy should age visual context from observed time, not from analysis completion time. Slow analysis makes the context older when it arrives; it must not reset the freshness vector.

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
