# Atlas Roadmap

Status: Phase 0 locked; Phase 1 core runner/store underway.

## Current Position

Atlas has a name, build doc, repo skeleton, and initial package boundaries:

- `@atlas/core`
- `@atlas/device-android`
- `@atlas/provider-openclaw`
- `@atlas/test-harness`

The next work is to turn the skeleton into a real, testable MVP using OpenClaw upstream and an Android phone downstream.

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

Status: underway.

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
- Initial `@atlas/cli` control surface for session listing/inspection.

Remaining:

- Cleaner replay/materialization semantics.
- More scenario fixtures.
- CLI create/ask/heartbeat commands.

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

Deliverables:

- Adapter wrapper around existing Android Camera Bridge.
- `captureImage()` returns normalized `Observation`.
- Bridge-provided image analysis maps to `ObservationAnalysis` rather than becoming an OpenClaw-specific assumption.
- Media refs, timestamps, device ID, and summary/quality fields populated where available.
- Error handling for no paired device, capture failure, stale file, or analysis failure.

Pass criteria:

- Atlas can trigger a phone snapshot.
- The captured image becomes an `Observation` in session state.
- The audit log shows the capture request and result.

## Phase 3 — Perception State + Freshness Policy

Goal: make context quality first-class.

Deliverables:

- Latest visual context tracking.
- Freshness age calculation.
- Stability/confidence model.
- Simple stale/unstable/insufficient policy.
- Prompt intent classifier for visual-context-dependent questions.

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

- Adapter maps `NormalizedSessionTurn` to OpenClaw-compatible input.
- Adapter exposes Atlas tool schemas in OpenClaw-compatible form.
- Adapter maps OpenClaw response/tool calls back to `NormalizedAgentResult`.
- Clean failure behavior when OpenClaw is unavailable.

Pass criteria:

- Same Atlas core can run against a fake provider or OpenClaw provider.
- OpenClaw can answer using current Android image context.
- OpenClaw-requested physical tools execute through Atlas Core, not directly in adapter code.

## Phase 6 — Heartbeat / Perception Loop MVP

Goal: give Atlas ambient physical awareness without babbling or provider spam.

Deliverables:

- Configurable heartbeat cadence.
- Capture/update context on heartbeat.
- Cheap significance gate.
- Silent-by-default behavior.
- Optional provider call for meaningful scene changes.
- Hard gate for proactive speech.

Pass criteria:

- Unchanged scenes update state silently.
- Provider calls are avoided when nothing meaningful changed.
- Actionable changes can be escalated when permissions allow.

## Phase 7 — Audit + Scenario Test Harness

Goal: make behavior inspectable and hard to fool ourselves about.

Deliverables:

- Scenario scripts for core MVP cases.
- Event inspection command/output.
- Replay/debug harness.
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

## Immediate Next Step

Implement Phase 1: durable session store and event log.

Recommended first coding target:

```text
atlas/packages/atlas-core/src/store/
  file-session-store.ts
  event-log.ts
  materialize.ts
```

The first satisfying demo should be:

```text
create session → append events → materialize state → inspect session → run fake user-loop preflight decision
```
