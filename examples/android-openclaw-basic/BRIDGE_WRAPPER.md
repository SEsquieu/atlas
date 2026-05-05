# Android Bridge Wrapper Plan

Atlas MVP uses Seth's existing OpenClaw Android Camera Bridge as the first Android transport/capture path.

Important boundary:

```text
OpenClaw Android Camera Bridge
  → transport/capture implementation
  → optional visual analyzer implementation

Atlas Android adapter
  → normalizes bridge output into Atlas Observation / ObservationAnalysis

Atlas Core
  → owns session state, freshness policy, tool execution, and audit
```

The bridge can provide image analysis today, but Atlas models that output as `ObservationAnalysis`. This keeps the architecture open for future paths:

- bridge-provided analysis
- Atlas-configured analyzer such as Ollama/OCR/custom CV
- provider-native vision
- no analysis

## Adapter Shape

`@atlas/device-android` exposes:

- `createAndroidDeviceAdapter(...)` for already-normalized capture functions
- `createAndroidBridgeDeviceAdapter(...)` for injected OpenClaw bridge calls
- `normalizeAndroidBridgeCaptureResult(...)` for tolerant bridge result normalization

Atlas does **not** import OpenClaw tools directly. The OpenClaw-specific caller is injected at the edge.

## Current Fake Harness

A fake Android bridge integration can be exercised with:

```bash
npm --workspace @atlas/test-harness run demo:android-bridge
```

This validates the injected bridge boundary without requiring OpenClaw tool access inside the package.

## Future Integration Harness

A real OpenClaw runtime harness should inject the bridge function roughly like:

```ts
createAndroidBridgeDeviceAdapter({
  captureWithBridge: async (options) => android_camera_bridge(options)
});
```

That keeps Atlas packages reusable outside OpenClaw.
