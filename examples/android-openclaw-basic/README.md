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

To demonstrate the Phase 6 ambient-perception shape, run a heartbeat refresh first and then ask against the already-fresh session context:

```bash
npm run demo:ambient-android -- "What am I looking at?"
```

This starts the warm image worker, creates/starts the session, runs `atlas session heartbeat` to capture context silently, then runs the summary fast-path ask. The expected UX target is that the visible ask skips capture and returns from cached context in milliseconds while the refresh cost is paid before the user prompt.

For a one-command live smoke pass that builds Atlas, runs the live fast ask, then runs the ambient heartbeat + cached ask demo:

```bash
npm run demo:smoke-android -- "What am I looking at?"
```

Use `--no-build` to skip the build step when iterating quickly. The smoke pass intentionally invokes the Android camera twice: once for the direct live fast ask and once for the ambient heartbeat refresh.

For a live multi-tick ambient loop smoke that runs the Android/OpenClaw heartbeat path over time:

```bash
npm run demo:smoke-ambient-android -- --ticks 3
```

By default this builds Atlas, starts the OpenClaw image worker without blocking on prewarm, runs the live Android/OpenClaw config, waits between ticks using Atlas cadence capped at 30s, and writes `ambient-loop.jsonl` / `ambient-loop.md` under `.atlas-runs/latest-ambient-android/<session>/`. Add `--ask-text "What am I looking at?"` to finish the heartbeat ticks with one explicit user ask; the JSONL/markdown logs then show whether that ask reused ambient context or triggered an ask-time refresh. Set `ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM=1` only when you intentionally want startup to request and wait for `/warm` before the first loop tick; the worker still reports HTTP readiness first, then acknowledges warm status separately. Use `--store <path>` to choose a different log/session location, `--no-build` while iterating, `--max-sleep-ms <ms>` to shorten waits, or `--no-wait` to run ticks back-to-back.

For chat/operator-style loop control over a longer-running ambient loop:

```bash
npm run loop:android -- start
npm run loop:android -- status
npm run loop:android -- summary
npm run loop:android -- summary --markdown --tail 40
npm run loop:android -- ask "What am I looking at?"
npm run loop:android -- worker status
npm run loop:android -- worker start
npm run loop:android -- worker warm
npm run loop:android -- worker clean
npm run loop:android -- worker stop
npm run loop:android -- stop
```

`start` resumes the stable `.atlas-runs/latest-ambient-android` loop location. Use `fresh-start` when you explicitly want a clean loop store. `status` reports process state plus latest session/visual context age, confidence, refresh health, bridge timing, latest summary, and discovered image worker state when available. `summary` parses `ambient-loop.jsonl` into compact stats and a freshness scorecard by default; use `--markdown` to tail the human-readable journal. The scorecard calls out refresh-due captures, preemptive captures before stale, stale captures/reuses, max age versus stale window, whether stale was ever hit, and provider-review counts. If the JSONL includes `cached-ask` entries from `--ask-text`, `summary` separates them from heartbeat ticks and prints a cached-ask scorecard with reuse vs ask-time refresh counts and average ask wall time. `ask` starts the OpenClaw image worker when needed so ask-time refreshes use the same worker path as ambient heartbeats; if `ATLAS_OPENCLAW_IMAGE_WORKER_PREWARM=1` is set, it waits for `/warm` and prints the warm acknowledgement before asking. It then prints user-turn, capture round-trip, bridge total, camera/helper capture, file stage, image-analysis, and provider timings when available. If ask-time refresh fails but Atlas has a previous observation, the core user loop falls back to that observation with an explicit stale-context caveat instead of hard failing. The `worker` subcommands manage the persistent OpenClaw image worker as an intentional workspace service: `status` reads the registry and health endpoint, `start` reuses a healthy/starting worker or starts a detached one, `warm` forces a `/warm` request, `clean` removes stale or invalid registry entries, and `stop` kills the registered worker PID only when it safely looks like the image worker unless `--force` is used. This is still a CLI harness, not a daemon/service, but it gives Vera/OpenClaw a stable command surface for “start the loop” / “stop the loop” / “keep the worker warm” chat control.

For a fake-safe ambient loop journal that does **not** invoke the phone camera unless you pass a live Android config:

```bash
npm run demo:ambient-loop -- --ticks 3
```

The runner writes both `ambient-loop.jsonl` and `ambient-loop.md` under the session store directory. Each tick records cadence, freshness age/stale window, capture/no-capture, latest summary, significance level/score, provider-review/speech-suppression status, and timing so a test loop can be read without digging through raw `events.jsonl`. Add `--ask-text "What am I looking at?"` to append a cached-ask entry after the heartbeat ticks, including user-turn wall time, plan/reason, refresh/no-refresh, latest context summary, and provider response. Add `--wait` to sleep between ticks using the cadence decision, or pass `--config examples/android-openclaw-basic/atlas-live.config.example.json` only when you intentionally want the live Android device path.

To turn ambient-loop logs into a pass/fail regression gate:

```bash
npm run validate:ambient -- .atlas-runs/latest-ambient-android/live-android-openclaw/ambient-loop.jsonl --require-capture --require-cached-ask --max-ask-refreshes 0
```

The validator reads `ambient-loop.jsonl`, separates heartbeat ticks from cached asks, fails on stale-context reuse by default, and can require captures, cached asks, no ask-time refreshes, no refresh-due reuses, or maximum ask wall time. This is the quick “did the ambient run actually prove the thing?” command for demo/harness output.

For a no-camera warm-context behavior harness:

```bash
npm run demo:warm-context
```

This uses fake observations/devices only and verifies three user-loop cases: fresh context reuses the latest observation without capture, stale context refreshes before answering, and refresh failure falls back to the latest observation with a stale-context caveat.

When the live Android/OpenClaw bridge config is used, the ambient loop runner auto-starts and prewarms `openclaw-image-worker.mjs` unless `ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL` / `ATLAS_OPENCLAW_IMAGE_WORKER_URL` is already set or `--image-worker false` is passed. This avoids the cold OpenClaw image-analysis timeout footgun in live loop tests.

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

Override wrapper behavior through the device config and `env` blocks, for example `maxWidth`, `quality`, `ATLAS_ANDROID_BRIDGE_NODE`, `ATLAS_ANDROID_BRIDGE_OPENCLAW_BIN`, `ATLAS_ANDROID_BRIDGE_OLLAMA_MODEL`, `ATLAS_ANDROID_BRIDGE_OPENCLAW_IMAGE_WORKER_URL`, `ATLAS_OPENCLAW_AGENT_ID`, or `ATLAS_OPENCLAW_PROVIDER_MODE=summary`. The live example defaults to `maxWidth=1024` and `quality=0.7`, which keeps enough detail for visual summaries while avoiding the full-resolution capture/analysis tax.
