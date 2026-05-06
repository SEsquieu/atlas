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

## Injected Integration Harness

A real OpenClaw runtime harness should inject the bridge function roughly like:

```ts
createAndroidBridgeDeviceAdapter({
  captureWithBridge: async (options) => android_camera_bridge(options)
});
```

That keeps Atlas packages reusable outside OpenClaw.

## Command-backed CLI Seam

The Atlas CLI can also use a command-backed Android bridge device:

```json
{
  "sessions": {
    "live-android": {
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
  }
}
```

The wrapper command receives capture options as JSON in `ATLAS_ANDROID_BRIDGE_OPTIONS` by default and must print bridge-result JSON to stdout.

This repo includes a concrete wrapper at `examples/android-openclaw-basic/bridge-wrapper.mjs` plus a full session config at `examples/android-openclaw-basic/atlas-live.config.example.json`.

`bridge-wrapper.mjs`:

- reads `ATLAS_ANDROID_BRIDGE_OPTIONS`
- calls `openclaw nodes camera snap`
- parses `MEDIA:` output or falls back to the OpenClaw temp image directory
- stages the selected image into `.atlas-cache/images`
- optionally runs OpenClaw/Codex or Ollama visual analysis
- prints bridge-result JSON to stdout

This is the first live-harness seam: the command can call OpenClaw however the local install supports, while Atlas still sees only normalized device output.
