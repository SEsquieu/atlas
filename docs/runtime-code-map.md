# Runtime code map

Persistent unresolved work and idle autonomy are documented in [persistent-intent-runtime.md](persistent-intent-runtime.md). The Android implementation lives under `apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/intent/` and extends the existing SQLite database and foreground runtime service.

This document maps Atlas concepts to code. It is deliberately explicit about a current repository boundary: the Android app contains the shipping native alpha runtime, while `packages/atlas-core` is a provider-independent reference Core and testable contract surface. They share principles and event vocabulary, but the Kotlin app does not import or execute the TypeScript package.

## Native Android composition

`AtlasSessionService` is the process-local composition root. It creates the database, secure settings, media repository, device controllers, provider backend/router, and `AtlasMobileRuntime`. `MainActivity` binds to the service and renders `RuntimeSnapshot`; destroying the Activity does not intentionally end an active session.

| Concern | Native implementation | Notes |
| --- | --- | --- |
| App/service lifecycle | [`AtlasApplication.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/AtlasApplication.kt), [`AtlasSessionService.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/runtime/AtlasSessionService.kt) | Foreground service owns the live process composition. Android may still kill the process. |
| Session/turn orchestration | [`AtlasMobileRuntime.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/runtime/AtlasMobileRuntime.kt) | Serializes mutations, bounds agent steps/tools/wall time, and owns late-result disposition. |
| Durable state and events | [`AtlasDatabase.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/data/AtlasDatabase.kt) | SQLite schema for sessions, messages, observations, turns, tools, speech, memory, clarification, summaries, and ordered events. |
| Context projection | [`ContextAssembler.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/runtime/ContextAssembler.kt) | Deterministically selects recent whole turns, admitted memory, checkpoint, delivery notes, and physical context. |
| Freshness and intent | [`FreshnessPolicy.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/runtime/FreshnessPolicy.kt) | Classifies visual use, motion-sensitive stale windows, detail suitability, and high-risk fail-closed refresh. |
| Endpoint routing | [`CapabilityRouter.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/provider/CapabilityRouter.kt) | Ordered `fast`, `vision`, `reasoning`, and fallback routes with modality filtering and conservative failover. |
| Prompt envelopes | [`EndpointPromptCompiler.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/provider/EndpointPromptCompiler.kt) | Preserves full Core context or compiles a smaller envelope for constrained endpoints. |
| Provider protocol | [`OpenAiCompatibleBackend.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/provider/OpenAiCompatibleBackend.kt) | OpenAI-compatible non-streaming/SSE requests, image/tool payloads, telemetry, and bounded network deadlines. |
| Tools and policy | [`ToolHarness.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/runtime/ToolHarness.kt) | Typed definitions, per-tool limits, permission checks, confirmation, execution, and results. |
| Camera/media | [`CameraController.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/device/CameraController.kt), [`MediaRepository.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/media/MediaRepository.kt) | CameraX capture plus orientation, resize, compression, byte budget, hashing, and private storage. |
| Motion/device pressure | [`MotionMonitor.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/device/MotionMonitor.kt), [`DeviceHealthMonitor.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/device/DeviceHealthMonitor.kt) | Accelerometer-derived motion and battery/thermal/network snapshots influence freshness cadence. |
| Voice and delivery | [`SpeechController.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/device/SpeechController.kt), [`SpeechDelivery.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/runtime/SpeechDelivery.kt) | Android STT/TTS, sentence segmentation, delivery status, barge-in, and text fallback. |
| Endpoint secrets | [`SecureSettings.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/data/SecureSettings.kt) | Android Keystore-backed values; secrets are excluded from session export. |
| Export/deletion/retention | [`SessionArchive.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/data/SessionArchive.kt), [`AtlasDatabase.kt`](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/data/AtlasDatabase.kt) | User-scoped share intent, full JSON export, transcript, deletion, and media pruning. |

## User-turn sequence

1. `listenAndAsk` obtains one Android speech-recognition result, or `ask` accepts text.
2. `startUserTurnLocked` persists the user message before inference and evaluates current visual evidence.
3. High-risk, navigation, missing, unsuitable, or stale visual context triggers capture. Some refresh failures fail closed; lower-risk flows may retain a prior usable observation.
4. `runTurnLoopLocked` loads the rolling summary, recent messages, accessible memory, pending clarification, latest observation, and available tools.
5. `ContextAssembler` creates a provider-neutral packet. `EndpointPromptCompiler` may reduce the prompt envelope for a constrained endpoint without changing Core-owned state.
6. `CapabilityRouter` filters ordered candidates by declared vision/tool support and transport validity. Once output may have been accepted, ambiguous failures do not silently fail over.
7. `streamModelStep` creates a durable internal assistant message before collecting output. Text is checkpointed, speech is segmented, and delivery state is independent of generation state.
8. Final text completes the turn, or typed tool proposals are checked against Core policy and budgets. Consequential calls wait for confirmation; results re-enter the same turn as tool messages.
9. At the 60-second interaction deadline, UI delivery detaches but diagnostic generation may continue. Its eventual success or failure receives a distinct durable status and event.
10. Older conversation turns may be compacted into a model-derived checkpoint. The original messages and event history remain the audit source; the summary is bounded context, not canonical truth.

## TypeScript reference Core

The TypeScript side makes several policies independently testable:

- [`AtlasRunner`](../packages/atlas-core/src/runner/atlas-runner.ts) implements normalized user/heartbeat loops over adapters and a `SessionStore`.
- [`materialize.ts`](../packages/atlas-core/src/store/materialize.ts) replays ordered events into session state using an explicit cursor.
- [`context-policy.ts`](../packages/atlas-core/src/state/context-policy.ts) and [`heartbeat.ts`](../packages/atlas-core/src/loops/heartbeat.ts) expose deterministic freshness/cadence decisions.
- [`runtime-policy.ts`](../packages/atlas-core/src/policy/runtime-policy.ts) resolves policy overlays with monotonic denial.
- [`scoped-memory.ts`](../packages/atlas-core/src/memory/scoped-memory.ts) enforces session/task/principal/workspace visibility.

The reference Core is valuable, but parity is not automatic. Changes to a policy in Kotlin do not update TypeScript, or vice versa. Cross-runtime contract fixtures are a roadmap item; documentation must not imply that the Android app currently executes the TypeScript Core.

## What is implemented versus aspirational

Implemented now: durable native sessions; bounded conversation checkpoints; direct local/LAN/cloud endpoints; declared capability routing; compact/full prompt profiles; streaming and non-streaming normalization; visual freshness; motion-aware Live Context; Android STT/TTS; confirmation-gated tools; interruption and late-result states; export/delete/retention; event provenance.

Incomplete or deliberately deferred: empirical endpoint characterization; automatic quality-aware endpoint selection; a packaged embedding model; cross-device memory; a durable evolving personality layer; production managed inference; full Kotlin/TypeScript behavioral parity; comprehensive process-death instrumentation tests on physical devices.
