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
- `openclaw-image-worker.mjs` keeps OpenClaw image understanding warm in one Node process, avoiding the ~20–30s cold provider/model-runtime load on every capture.
- `provider-wrapper.mjs` converts a normalized Atlas turn into an `openclaw agent --json` call and prints a normalized provider result.
- `atlas-live.config.example.json` wires both wrappers into the Atlas CLI.

After building from the Atlas repo root:

```bash
npm run build
npm run demo:live-android -- "What am I looking at?"
```

The live demo script creates a temporary session store, starts a warm OpenClaw image worker, runs the Android/OpenClaw loop, prints the response, and emits a timing report for the turn. By default it runs the full two-pass path: image analysis through OpenClaw/Codex, then an OpenClaw agent provider call.

Set `ATLAS_LIVE_USE_IMAGE_WORKER=false` to force the older cold CLI path. Set `ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM=false` to start the worker without doing a prewarm image request.

For latency demos where the second provider pass would only paraphrase the image summary, use the summary fast path:

```bash
npm run demo:live-android -- --fast "What am I looking at?"
```

That still captures a real Android image and runs OpenClaw/Codex image analysis, but the provider wrapper returns the latest normalized visual summary directly instead of making a second `openclaw agent` call.

To benchmark OpenClaw image-analysis latency against a saved image without Android/camera capture in the loop:

```bash
npm run bench:image-openclaw -- .atlas-cache/images/latest-back.jpg openai-codex/gpt-5.5 5
```

The benchmark prints OpenClaw CLI startup timing separately from repeated `openclaw infer image describe` timings. Cold CLI calls include OpenClaw provider/model setup, so they can be much slower than warmed in-process image calls. To run the warm worker directly:

```bash
npm run openclaw:image-worker
```

The worker prints a JSON ready line with its local URL. Point the bridge wrapper at it with `ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL=http://127.0.0.1:<port>`.

To check whether image calls are locally serialized or overlapping upstream:

```bash
npm run bench:image-openclaw:concurrent -- .atlas-cache/images/latest-back.jpg openai-codex/gpt-5.5 2
```

Manual equivalent:

```bash
node packages/atlas-cli/dist/index.js session create live-android-openclaw --config examples/android-openclaw-basic/atlas-live.config.example.json
node packages/atlas-cli/dist/index.js session start live-android-openclaw
node packages/atlas-cli/dist/index.js session ask live-android-openclaw --text "What am I looking at?" --config examples/android-openclaw-basic/atlas-live.config.example.json
node packages/atlas-cli/dist/index.js session inspect live-android-openclaw
```

The example assumes:

- `openclaw` is on PATH.
- A paired Android node is available to OpenClaw.
- OpenClaw image understanding is configured; the live example defaults to `openai-codex/gpt-5.5` via `analysisMode=openclaw`.

Override wrapper behavior through the config `env` blocks, for example `ATLAS_ANDROID_BRIDGE_NODE`, `ATLAS_ANDROID_BRIDGE_OPENCLAW_BIN`, `ATLAS_ANDROID_BRIDGE_OLLAMA_MODEL`, `ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL`, `ATLAS_OPENCLAW_AGENT_ID`, or `ATLAS_OPENCLAW_PROVIDER_MODE=summary`.
