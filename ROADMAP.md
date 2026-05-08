# Atlas Roadmap

Status: Phase 1 core session skeleton closed; Phase 2 Android adapter MVP is live against Seth's Android/OpenClaw path; Phase 6 heartbeat/perception loop is the next major focus.

## Current Position

Atlas has a name, build doc, repo skeleton, and initial package boundaries:

- `@atlas/core`
- `@atlas/config`
- `@atlas/device-android`
- `@atlas/provider-openclaw`
- `@atlas/test-harness`

The next work is to turn the skeleton into a real, testable MVP using OpenClaw upstream and an Android phone downstream.

## Live Latency Finding — 2026-05-05

The Android/OpenClaw path is no longer speculative. A live bridge run on `Galaxy S22 Ultra` using `analysisMode=openclaw` and `openai-codex/gpt-5.5` returned usable perception in roughly seven seconds:

```text
total=7358ms capture=3861ms stage=15ms analysis=3482ms
```

Product implication: Atlas should optimize for *felt latency*, not only raw latency. The user loop can acknowledge immediately by speech while capture/analysis runs in parallel; the heartbeat/perception loop can silently refresh context and only speak when the significance gate finds something worth interrupting for.

Engineering implication: capture latency likely includes OpenClaw CLI/helper/gateway/node overhead, so a native Atlas runtime or persistent device connection should be able to reduce it. Analysis latency is usable now, and should be hidden or amortized through background perception, prewarming, and significance gating.

See `docs/latency-notes.md` for the measurement notes and optimization tracks.

## Live Remote Gateway Checkpoint — 2026-05-07

The dedicated project laptop is now a viable OpenClaw/Vera home base for Atlas development. Android phone, home desktop, and office desktop all reach the gateway cleanly over Tailscale/MagicDNS, and the office desktop Control UI pairing path is confirmed.

Atlas fast Android/OpenClaw demo ran successfully from the laptop environment using the summary fast path:

```text
Total user turn: 11.59s
Atlas capture round trip: 11.44s
Bridge total: 11.30s
camera/helper capture: 6.82s
file stage: 9ms
image analysis: 4.47s
Provider round trip: 138ms
```

The run captured a real office scene from the Galaxy S22 Ultra and returned a useful visual summary. A migrated hardcoded Windows user path in the warm OpenClaw image worker was replaced with `APPDATA` / `USERPROFILE` based resolution so the harness is no longer tied to the old `C:\Users\16096` profile.

Product implication: the basic remote development loop is now stable enough to treat Atlas Phase 6 ambient-perception work as the next primary track, rather than spending more time proving connectivity. The OpenClaw Android app path should remain a proof adapter for validating loop semantics and timing telemetry; deeper mobile latency, lifecycle, and sensor-control work belongs in a native Atlas mobile adapter instead of Android-app-specific optimization.

## Ambient Android Demo Checkpoint — 2026-05-07

Atlas ambient Android/OpenClaw demo confirmed the intended Phase 6 MVP shape: heartbeat pays the capture/analysis cost before the explicit user prompt, then the user ask answers from cached fresh context without another camera refresh.

```text
Ambient heartbeat refresh:
heartbeat wall time: 11.60s
ambient bridge: 11.31s
camera/helper capture: 6.01s
file stage: 9ms
image analysis: 5.29s

User ask using ambient context:
ask wall time: 269ms
refreshed during ask: no
user turn timing: 109ms
provider fast path: 105ms
```

The demo captured a real Android image from the Galaxy S22 Ultra, stored it as the latest observation, and answered the prompt from that ambient observation. This validates the core felt-latency bet even while raw perception wall time is still around eleven seconds: intelligent heartbeating can move that cost out of the explicit user interaction path.

## Visual Freshness Policy — 2026-05-05

Atlas now treats visual context as decaying state. Reuse depends on context age, confidence, stability, inferred motion state, and question use case. The first policy returns `reuse`, `background-refresh`, or `refresh`, with much shorter reuse windows for walking/navigation/confirmation and mandatory refresh for high-risk visual questions.

Design refinement on 2026-05-07: stale should be a degradation/fallback state, not the normal heartbeat recapture trigger. The intended steady-state loop should compute a preemptive refresh deadline before the hard stale deadline using weighted freshness, expected refresh latency, and safety margin. This also becomes a future speed-vs-performance switch: speed favors context correctness and ask latency at the cost of battery/heat; performance favors battery/device health and tolerates more stale fallback.

See `docs/visual-freshness-policy.md` for the initial matrix and examples.

## Future Architecture Shape — 2026-05-06

Captured the next layer of Atlas architecture in `docs/context-memory-and-sidecars.md`.

MVP-shaping decisions:

- heartbeat cadence should become dynamic rather than fixed
- freshness decay should use context-aware multipliers
- physical context, supporting context, and spillover must remain separate
- provider adapters may emit structured sideband suggestions, but Atlas validates/adopts or rejects them
- event/session models should leave room for belief candidates, spillover quarantine, and async sidecar results

Deferred until after a solid MVP:

- full weighted-belief memory store
- truth-state decay engine (`current → stale → historical → archived`)
- external spillover delegation
- budgeted inference sidecar scheduler

## Live Command Adapter Seam — 2026-05-06

Atlas now has a config-selected command seam for live local harnesses while keeping Core provider/device independent:

- `@atlas/device-android/bridge-command` runs a wrapper command and normalizes Android bridge JSON into `Observation`.
- `@atlas/provider-openclaw/command` runs a wrapper command and normalizes JSON/plain-text output into `NormalizedAgentResult`.
- `examples/android-openclaw-basic/bridge-wrapper.mjs` calls `openclaw nodes camera snap`, stages image files, optionally runs Ollama visual analysis, and emits bridge-result JSON.
- `examples/android-openclaw-basic/provider-wrapper.mjs` maps Atlas turns into `openclaw agent --json` calls.
- `examples/android-openclaw-basic/atlas-live.config.example.json` wires the first live Android/OpenClaw CLI harness.

Product implication: the next live test can be a real `atlas session ask ... "What am I looking at?"` run without special in-process OpenClaw APIs. This is still a harness seam, not the final daemon/runtime integration.

## Phase 0 — Design Lock

Status: complete.

Locked decisions:

- Confirmed name: Atlas.
- Confirmed standalone/provider-agnostic architecture.
- Confirmed first upstream: OpenClaw.
- Confirmed first downstream: Android phone.
- Confirmed two-loop design: heartbeat/perception + user interaction.
- Confirmed context freshness/preflight refresh as core behavior.
- Store: JSONL event log + JSON materialized state first; SQLite later if needed.
- Android integration: wrap existing OpenClaw Android Camera Bridge first; direct client later.
- OpenClaw adapter: runtime adapter, not raw LLM adapter.
- Image analysis boundary: Atlas owns objective metadata/policy signals; provider owns semantic interpretation.
- Significance gate: simple heuristics first.
- Memory boundary: Atlas session memory authoritative; provider memory optional/runtime-specific.

## Phase 1 — Core Session Skeleton

Status: complete.

Goal: make Atlas able to create, persist, inspect, and update physical sessions.

Done:

- Session create/load/save.
- JSONL event log.
- Materialized JSON state.
- Device/provider binding model.
- Basic audit events.
- Core `AtlasRunner` for user turns.
- Core `AtlasRunner` heartbeat tick path.
- Fake provider/device adapters for harness/tests.
- Fake perception analyzer adapter and analyzer pipeline.
- Session inspection helper and harness inspect command.
- Initial `@atlas/cli` control surface for session create/list/inspection, lifecycle start/pause/resume/end, heartbeat tick, fake-adapter ask, and config inspection.
- Initial `@atlas/config` package for JSON config loading and configured-session materialization.
- Explicit event cursor/checkpoint semantics for materialized state replay.
- Inspection rematerializes from stored state plus pending events and reports checkpoint coverage.
- Scenario fixtures for stale/transitional place-check refresh and provider swap behavior.
- CLI ask/heartbeat can use config-selected fake provider/device/analyzer bindings via `--config`.

Remaining:

- None for Phase 1. Future replay/storage hardening belongs to post-MVP store work.

Deliverables:

- Session create/load/save.
- Event log.
- Materialized session state.
- Device/provider binding model.
- Basic audit events.
- Minimal test harness using fake provider/device adapters.

Pass criteria:

- A session can be created, written to disk, loaded, updated, and inspected.
- Events and materialized state agree.
- Core tests pass without Android/OpenClaw dependencies.

## Phase 2 — Android Device Adapter MVP

Status: started.

Goal: normalize Android phone captures into Atlas observations.

Done:

- Android bridge result normalizer.
- Injected bridge wrapper adapter shape.
- Fake Android bridge harness scenario.
- Normalized Android bridge capture errors.
- Bridge-provided image analysis maps to `ObservationAnalysis` rather than becoming an OpenClaw-specific assumption.
- Bridge timing telemetry (`totalMs`, `captureMs`, `stageMs`, `analysisMs`) is preserved in observation metadata when present.
- CLI can resolve command-backed Android bridge adapters from config.
- Runnable Android/OpenClaw bridge wrapper and live config example exist under `examples/android-openclaw-basic/`.

In progress / next:

- Add a repeatable Android/OpenClaw latency harness that can compare bridge/plugin path vs future native runtime path.
- Run the command-backed live harness against a paired Android node and record timings.
- Use measurements to decide when to keep using the bridge, when to bypass CLI/helper overhead, and when to move toward a native Atlas Android runtime.

Deliverables:

- Adapter wrapper around existing Android Camera Bridge.
- `captureImage()` returns normalized `Observation`.
- Bridge-provided image analysis maps to `ObservationAnalysis` rather than becoming an OpenClaw-specific assumption.
- Media refs, timestamps, device ID, and summary/quality fields populated where available.
- Error handling for no paired device, capture failure, stale file, or analysis failure.
- Timing telemetry persisted in the observation `data` payload when available.

Pass criteria:

- Atlas can trigger a phone snapshot.
- The captured image becomes an `Observation` in session state.
- The audit log shows the capture request and result.
- Live runs expose capture/stage/analysis/total timings for regression tracking.

## Phase 3 — Perception State + Freshness Policy

Goal: make context quality first-class.

Status: started.

Done:

- Visual context intent classifier.
- Visual use-case classifier (`descriptive`, `confirmation`, `navigation`, `high-risk`).
- Motion-aware visual freshness scoring.
- User turn planning now carries the freshness assessment and distinguishes reuse/background-refresh/refresh.
- Heartbeat planning now emits a dynamic cadence decision (`idle`, `stable-scene`, `active-task`, `unstable-scene`, `high-risk`) with a suggested next delay and reason.
- Heartbeat freshness now uses a weighted stale window and exposes context age/stale window metadata so non-capturing ticks cannot disguise aging context as refreshed context.
- Heartbeat planning now includes an Atlas-side capture budget/device-pressure placeholder derived from recent capture rate and latency.

Deliverables:

- Latest visual context tracking.
- Freshness age calculation.
- Stability/confidence model.
- Simple stale/unstable/insufficient policy.
- Prompt intent classifier for visual-context-dependent questions.
- Motion-aware visual context decay policy.
- Dynamic heartbeat cadence decision hook based on idle/active/stable/unstable/high-risk state. ✅ initial hook landed
- Weighted heartbeat freshness window using motion/stability/confidence/risk/refresh-health multipliers. ✅ v0 landed

Pass criteria:

- Atlas can decide when visual context is reusable vs stale.
- “What am I looking at?” and “Am I in the right place?” trigger refresh when needed.
- Transitional/low-confidence context is rejected instead of trusted.

## Phase 4 — User Loop MVP

Goal: make explicit user prompts work against physical session state.

Deliverables:

- Text prompt intake.
- Preflight context refresh.
- Normalized provider turn construction.
- Provider result handling.
- Tool-call execution path through Atlas Core.
- User-visible response output.

Pass criteria:

- With no visual context, “What am I looking at?” captures a fresh image before answering.
- With fresh stable visual context, Atlas may reuse it according to policy.
- The provider receives context status clearly annotated.

## Phase 5 — OpenClaw Provider Adapter MVP

Goal: route normalized Atlas turns through OpenClaw without making Atlas OpenClaw-native.

Deliverables:

- Adapter maps `NormalizedSessionTurn` to OpenClaw-compatible input. Initial command-backed wrapper exists for CLI harness use.
- Adapter exposes Atlas tool schemas in OpenClaw-compatible form.
- Adapter maps OpenClaw response/tool calls back to `NormalizedAgentResult`.
- Adapter leaves room for structured sideband fields: artifacts, route hints, belief candidates, and tool-call candidates.
- Clean failure behavior when OpenClaw is unavailable.

Pass criteria:

- Same Atlas core can run against a fake provider or OpenClaw provider.
- OpenClaw can answer using current Android image context.
- OpenClaw-requested physical tools execute through Atlas Core, not directly in adapter code.

## Phase 6 — Heartbeat / Perception Loop MVP

Status: started.

Goal: give Atlas ambient physical awareness without babbling or provider spam.

Current slice:

- `npm run demo:ambient-android -- "What am I looking at?"` runs a live heartbeat refresh before the user ask, proving the intended shape: Atlas can pay capture/analysis cost ambiently, then answer from fresh cached visual context without refreshing during the explicit prompt.
- Heartbeat decisions include a dynamic cadence suggestion so a future ambient runner can schedule the next tick without hard-coding one interval.
- Heartbeat captures now run a cheap significance gate that compares the new observation with the previous one and records `none`, `low`, `meaningful`, or `actionable` significance.
- `npm run demo:ambient-loop -- --ticks 3` runs a fake-safe multi-tick ambient loop and writes skim-friendly `ambient-loop.jsonl` / `ambient-loop.md` summaries for test review.
- `npm run demo:ambient-loop -- --ticks 1 --ask-text "What am I looking at?"` appends a cached-ask proof entry after heartbeat ticks, showing user-turn wall time, plan/reason, refresh/no-refresh, latest context summary, and provider response without invoking the phone camera unless a live config is passed.
- `npm run loop:android -- summary` now separates heartbeat tick entries from cached-ask entries so ambient-loop scorecards do not miscount explicit asks as heartbeat reuses.
- `npm run validate:ambient -- <ambient-loop.jsonl>` provides a nonzero-exit regression gate for ambient logs. It requires heartbeat ticks by default, fails on stale-context reuse, and can require captures/cached asks/no ask-time refreshes/max ask wall time.
- Heartbeat captures now optionally call the provider when significance is `meaningful` or `actionable`. Provider review text is recorded in audit/log output, but proactive speech is hard-gated: meaningful changes are suppressed by default, and actionable notifications only become `agent.speech` when session speak permission is `proactive_allowed`.
- The ambient loop runner auto-starts the warm OpenClaw image worker for the live Android/OpenClaw bridge config, preventing cold image-analysis timeouts during live loop tests.

Deliverables:

- Configurable heartbeat cadence.
- Dynamic cadence policy hook: idle/stable slows down; unstable/moving/active/high-risk speeds up within limits. ✅ initial hook landed
- Capture/update context on heartbeat.
- Cheap significance gate. ✅ v0 landed
- Silent-by-default behavior.
- Human-readable ambient loop summary log for test review. ✅ fake-safe runner landed
- Cached-context ask proof in ambient loop logs. ✅ fake-safe runner landed
- Nonzero-exit ambient loop validator for regression/demo gates. ✅ initial fake-safe validator landed
- Capture budget / device health placeholder to avoid runaway camera pressure. ✅ v0 landed
- Optional provider call for meaningful scene changes. ✅ fake-safe gate landed
- Hard gate for proactive speech. ✅ permission gate landed

Pass criteria:

- Unchanged scenes update state silently.
- Provider calls are avoided when nothing meaningful changed.
- Actionable changes can be escalated when permissions allow.

## Phase 7 — Audit + Scenario Test Harness

Goal: make behavior inspectable and hard to fool ourselves about.

Current slice:

- `npm run demo:scenarios` runs a fake-safe scenario harness covering fresh visual capture, cached visual reuse, transitional place-confirmation refresh, unchanged heartbeat silence, meaningful heartbeat provider-review speech suppression, and actionable heartbeat escalation with permission.
- The scenario harness emits `scenario-report.json` / `scenario-report.md` with per-scenario details, event type timelines/counts, latest observation/significance/provider text, and speech/suppression counts.
- `npm run validate:scenarios` validates the fake-safe scenario report contract and fails on missing scenarios, failed report status, or required event-pattern regressions.
- `npm run replay:session -- <sessionId>` rebuilds a saved session from `events.jsonl`, compares replayed materialized state to saved `state.json`, and can emit JSON/Markdown replay reports.

Deliverables:

- Scenario scripts for core MVP cases. ✅ initial fake-safe harness landed
- Event inspection command/output. ✅ scenario report event summaries landed
- Scenario report validator. ✅ initial validator landed
- Replay/debug harness. ✅ initial session replay command landed
- Fake device/provider fixtures.
- Android/OpenClaw integration test path.

Required scenarios:

- “What am I looking at?” fresh capture.
- “Am I in the right place?” stale/transitional rejection.
- Heartbeat unchanged-scene silence.
- Heartbeat actionable-change escalation.
- Provider adapter swap simulation.

## Phase 8 — Voice Layer

Goal: turn the text/image MVP into a natural real-world loop.

Deliverables:

- STT input interface.
- TTS/speak interface.
- Android speaker output path.
- Interrupt/stop behavior.
- Text fallback preserved.

Pass criteria:

- User can ask verbally.
- Atlas can answer via voice.
- Camera/context behavior remains the same as text MVP.

## Phase 9 — Beyond MVP

Possible directions:

- Direct Android client instead of OpenClaw bridge wrapper.
- GPS/location observations.
- OCR-first observations.
- Session inspector UI.
- More provider adapters, e.g. Hermes.
- More device adapters, e.g. webcam/RTSP/smart glasses.
- Native Atlas runtime.
- Richer memory/session replay.
- Weighted belief memory with truth-state decay.
- Spillover routing/delegation for side requests.
- Budgeted inference sidecars for non-blocking context enrichment.
- Supporting-context artifact store for maps, manuals, floorplans, docs, and OCR results.

## Immediate Next Step

Continue Phase 2/5 integration: wire a real OpenClaw/Android config path so CLI `session ask` and `session heartbeat` can use the live bridge/provider path instead of only fake adapters.

Recommended first coding target:

```text
atlas/packages/atlas-provider-openclaw/
atlas/packages/atlas-device-android/
atlas/examples/android-openclaw-basic/
```

The first satisfying demo should be:

```text
create session → start session → live Android heartbeat/ask → materialize state → inspect observation/analysis/timing/audit trail
```
