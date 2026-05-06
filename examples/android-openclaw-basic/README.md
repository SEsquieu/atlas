# Android + OpenClaw Basic Example

First target scenario for Atlas:

```text
Android phone camera
  → Atlas Android device adapter
  → Atlas Core session loop
  → Atlas OpenClaw provider adapter
  → OpenClaw runtime
```

## Initial Scenario

User asks: “What am I looking at?”

Expected behavior:

1. Atlas checks visual context freshness.
2. Atlas captures a fresh Android phone image if needed.
3. Atlas updates perception state.
4. Atlas sends a normalized turn to OpenClaw.
5. OpenClaw responds using current visual context.
6. Atlas records the audit trail and returns the response.

## Live command harness

This example now includes command wrappers for the first local live path:

- `bridge-wrapper.mjs` calls `openclaw nodes camera snap`, stages the image into `.atlas-cache/images`, optionally asks OpenClaw/Codex or Ollama for a visual summary, and prints Android bridge result JSON.
- `provider-wrapper.mjs` converts a normalized Atlas turn into an `openclaw agent --json` call and prints a normalized provider result.
- `atlas-live.config.example.json` wires both wrappers into the Atlas CLI.

After building from the Atlas repo root:

```bash
npm run build
node packages/atlas-cli/dist/index.js session create live-android-openclaw --config examples/android-openclaw-basic/atlas-live.config.example.json
node packages/atlas-cli/dist/index.js session start live-android-openclaw
node packages/atlas-cli/dist/index.js session ask live-android-openclaw --text "What am I looking at?" --config examples/android-openclaw-basic/atlas-live.config.example.json
```

The example assumes:

- `openclaw` is on PATH.
- A paired Android node is available to OpenClaw.
- OpenClaw image understanding is configured; the live example defaults to `openai-codex/gpt-5.5` via `analysisMode=openclaw`.

Override wrapper behavior through the config `env` blocks, for example `ATLAS_ANDROID_BRIDGE_NODE`, `ATLAS_ANDROID_BRIDGE_OPENCLAW_BIN`, `ATLAS_ANDROID_BRIDGE_OLLAMA_MODEL`, or `ATLAS_OPENCLAW_AGENT_ID`.
