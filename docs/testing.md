# Atlas Testing Strategy

Initial testing target:

- upstream: OpenClaw
- downstream: Android phone

## Fake-safe scenario harness

Run the current no-camera MVP scenario pass from the repo root:

```bash
npm run demo:scenarios
```

The harness writes temporary session state under `.atlas-runs/scenario-harness` and verifies the major user-loop, heartbeat, and provider-swap cases without touching Android/OpenClaw. It also emits `scenario-report.json` and `scenario-report.md` with per-scenario pass/fail status, details, event type timelines, event counts, latest observation/significance/provider text, and speech/suppression counts. Use `--store <path>` to choose a different output location, `--json <path>` / `--markdown <path>` to override report paths, or `--no-clean` to inspect a previous run alongside new state.

Validate the generated report contract:

```bash
npm run validate:scenarios
```

The validator fails with nonzero exit if the report is missing required scenarios, if any scenario failed, or if required event patterns regress. For example, unchanged heartbeat must not have provider/speech events, meaningful heartbeat must include provider review plus speech suppression, repeated meaningful heartbeat must skip duplicate provider review for the same scene, actionable heartbeat must include proactive speech when permission allows it, and provider swap must show the alternate provider receiving the same materialized physical session shape.

Replay a saved session from its event log and compare replayed materialized state with saved state:

```bash
npm run replay:session -- heartbeat-actionable-escalation --store .atlas-runs/scenario-harness
```

The replay command prints event counts, checkpoint/cursor status, and state comparison results. It exits nonzero if the replayed state diverges from the saved materialized state. Add `--json <path>` and/or `--markdown <path>` for durable replay reports.

Validate an ambient loop journal directly:

```bash
npm run validate:ambient -- --store .atlas-runs/latest-ambient-android --require-capture
```

The ambient validator reads `.atlas-runs/latest-ambient-android/live-android-openclaw/ambient-loop.jsonl` by default. Use `--store <path>` plus optional `--session <id>` for loop stores, or `--jsonl <path>` / positional JSONL when validating a specific log file.

## Fake-safe Android/OpenClaw wrapper contracts

`npm test` includes the Android/OpenClaw wrapper contract checks via:

```bash
npm run test:examples
```

These checks do not touch the phone camera or require OpenClaw to be installed. They verify that heartbeat provider review stays on the summary fast path by default, that `ATLAS_OPENCLAW_HEARTBEAT_PROVIDER_MODE=summary` overrides global agent mode for heartbeat turns, that summary-mode user replies preserve degraded visual-health caveats, and that the Android bridge capture lock can fail before invoking camera/OpenClaw.

## Live Android demo-readiness flow

Before a live Android proof, run the no-camera preflight:

```bash
npm run loop:android -- preflight
```

Preflight checks the live config, runner scripts, built Atlas CLI output, current loop/session state, persistent image-worker registry health, and worker warm path. It does not touch the Android camera. Use `--no-warm` for a structural check that skips `/warm`.

The live proof sequence after preflight is:

```bash
npm run loop:android -- fresh-start --ticks 1 --max-sleep-ms 15000 --wait-complete
npm run loop:android -- ask "What am I looking at?"
npm run validate:ambient -- --store .atlas-runs/latest-ambient-android --require-capture
npm run loop:android -- summary
```

Manual prerequisite: foreground the Android/OpenClaw app before the fresh-start tick. The expected shape is one live heartbeat capture, a cached ask with no ask-time refresh when context is fresh enough, and a passing ambient validator. Use `--wait-complete` in scripts so validation does not race the detached loop process before `ambient-loop.jsonl` exists.

The Android bridge wrapper serializes phone camera access with an inter-process capture lock at `.atlas-runs/android-camera-bridge.lock` by default. This prevents overlapping snaps from a heartbeat, ask-time refresh, or second loop process. If a contention test is needed without touching the phone, pre-create the lock directory and run the wrapper with a tiny `ATLAS_ANDROID_BRIDGE_CAPTURE_LOCK_TIMEOUT_MS`; it should fail before invoking camera capture.

Heartbeat-triggered provider reviews in the live Android config use `ATLAS_OPENCLAW_HEARTBEAT_PROVIDER_MODE=summary` by default. This keeps ambient review on the fast path; full `openclaw agent` provider turns are still available for explicit user asks or deliberate heartbeat-provider tests.

## MVP Test Scenarios

1. **Fresh context required**
   - Ask: “What am I looking at?”
   - Expected: Atlas captures a fresh image if visual context is stale, missing, or unstable.

2. **Fresh context reused**
   - Ask a visual question shortly after a stable capture.
   - Expected: Atlas may reuse current context if policy allows.

3. **Transitional context rejected**
   - Mark visual context as unstable or blurry.
   - Ask: “Am I in the right place?”
   - Expected: Atlas refreshes before answering.

4. **Heartbeat silence**
   - Run heartbeat ticks in an unchanged scene.
   - Expected: state updates silently; significance is `none` or `low`; no babbling; no unnecessary provider calls.
   - Fake-safe journal command: `npm run demo:ambient-loop -- --ticks 3` writes `ambient-loop.jsonl` and `ambient-loop.md` under the session store.
   - `npm run loop:android -- summary --store <store> --session ambient-loop-fake` parses the JSONL without touching the phone and prints the freshness scorecard.
   - The journal should show `freshness.age` increasing relative to `freshness.staleAfter`; check-only ticks must not reset freshness.

5. **Meaningful ambient change**
   - Run a heartbeat after the scene meaningfully changes.
   - Expected: Atlas records `perception.significance` as `meaningful` and marks the observation eligible for provider review without proactive speech.

6. **Repeated meaningful ambient change**
   - Keep seeing the same meaningful scene with small summary wording changes.
   - Expected: Atlas records significance but skips duplicate provider review inside the review cooldown.

7. **Actionable ambient change**
   - Run a heartbeat that detects a safety/action cue.
   - Expected: Atlas records `perception.significance` as `actionable`; this is the only v0 level that may pass the future proactive notification hard gate.

8. **Capture budget / device pressure**
   - Seed recent capture events or run repeated heartbeats.
   - Expected: heartbeat decisions include `captureBudget`; stale stable context can be deferred when budget is `constrained` or `cooldown`.

8. **Adapter independence**
   - Run the same normalized turn through the OpenClaw adapter and a fake adapter.
   - Expected: Atlas Core behavior remains unchanged.
