# Atlas — Physical Agent Loop Build Doc

Project name: **Atlas**  
Status: design draft  
Primary MVP stack: **OpenClaw upstream runtime + Android phone downstream device**

## 1. Vision

Atlas is a provider-agnostic physical agent loop framework.

The goal is to let a user bind real-world devices — phone, camera, microphone, speaker, GPS, sensors — to an upstream agent runtime so the agent can behave like a situated, living helper in the physical world.

Atlas should not be an OpenClaw-native layer. OpenClaw is the first upstream provider adapter. Hermes, future agent runtimes, local runtimes, or a future native Atlas runtime should be hot-swappable behind the same session loop contract.

The central product is not “camera access for an LLM.” The central product is:

> A durable physical session loop that binds devices, perception, memory, and agent reasoning into a living task context.

## 2. Design Principles

1. **Provider-agnostic upstreams**
   - OpenClaw, Hermes, and future runtimes plug in through adapters.
   - The session loop must not depend on any one provider’s message format, memory model, or tool-calling quirks.

2. **Device-agnostic downstreams**
   - Android phone is the first downstream implementation.
   - Core interfaces should support future webcams, body cameras, microphones, speakers, AR glasses, IoT sensors, and desktop capture.

3. **Session loop owns continuity**
   - The upstream agent runtime reasons.
   - Atlas owns physical session state, observation history, context freshness, memory policy, device bindings, tool execution, and loop pacing.

4. **Adapters should be boring**
   - Provider adapters shape normalized Atlas session turns into provider-native requests and map responses back into normalized Atlas results.
   - They should not independently own memory, lifecycle, device policy, or safety policy.

5. **Ambient perception, explicit conversation**
   - Atlas runs a quiet heartbeat/perception loop to maintain physical context.
   - User requests run through an explicit interaction loop.
   - The two loops share session state but remain separate.

6. **Context confidence is first-class**
   - Physical context must carry freshness, stability, confidence, and relevance.
   - The agent should know when visual/location context is stale, transitional, blurry, or insufficient.

7. **No hallucinated navigation**
   - If the answer depends on current physical context and current context is stale or unstable, Atlas should capture fresh context before answering.

8. **Inspectable by default**
   - Atlas should maintain an audit trail of observations, actions, tool calls, speech, context updates, and provider decisions.

## 3. Goals

- Build a modular physical agent session runtime.
- Bind an Android phone as the first physical device/body.
- Use OpenClaw as the first upstream agent provider.
- Support two parallel loops:
  - heartbeat/perception loop
  - user interaction loop
- Maintain session state with current physical context and confidence metadata.
- Allow user prompts to trigger fresh observation/tool calls when needed.
- Keep all provider/device interfaces swappable.
- Preserve enough audit state to debug behavior and replay sessions.

## 4. Non-Goals for MVP

- Full autonomous navigation without user confirmation.
- Multi-user identity/permission system.
- Marketplace or hosted provider network.
- Native Atlas agent runtime.
- Fully general robotics control.
- Always-on surveillance product.
- Complex AR UI.
- Production-grade mobile app polish.

## 5. High-Level Architecture

```text
                ┌──────────────────────────────┐
                │        Upstream Runtime       │
                │ OpenClaw / Hermes / Future X  │
                └──────────────▲───────────────┘
                               │
                               │ provider adapter
                               │
┌──────────────────────────────┴──────────────────────────────┐
│                        Atlas Core                            │
│                                                              │
│  ┌──────────────────┐     ┌──────────────────────────────┐   │
│  │ Heartbeat Loop   │────▶│ Shared Physical Session State │   │
│  └──────────────────┘     └──────────────────────────────┘   │
│           ▲                         ▲                         │
│           │                         │                         │
│  ┌──────────────────┐               │                         │
│  │ User Loop        │───────────────┘                         │
│  └──────────────────┘                                         │
│           │                                                   │
│  ┌────────▼─────────┐                                         │
│  │ Tool Execution   │                                         │
│  │ / Action Policy  │                                         │
│  └────────▲─────────┘                                         │
└───────────┼───────────────────────────────────────────────────┘
            │ device adapter
            ▼
┌─────────────────────────────────────┐
│          Downstream Device           │
│ Android phone / camera / mic / etc.  │
└─────────────────────────────────────┘
```

## 6. Major Components

### 6.1 Atlas Core

Owns:

- session lifecycle
- device bindings
- normalized event model
- heartbeat loop
- user interaction loop
- session memory/state
- context freshness/confidence
- tool execution
- action permissions
- audit log
- provider routing

Atlas Core should be the stable center of the project.

### 6.2 Device Adapters

Device adapters expose physical capabilities in a normalized way.

First implementation: Android phone.

Future implementations may include:

- USB webcam
- RTSP/IP camera
- desktop screen capture
- microphone-only device
- speaker-only device
- smart glasses
- robot or drone
- sensor bundles

### 6.3 Provider Adapters

Provider adapters map normalized Atlas turns to upstream runtime calls.

First implementation: OpenClaw.

Future implementations:

- Hermes
- direct OpenAI-compatible LLM agent
- local model agent
- LangGraph-style runtime
- native Atlas runtime

### 6.4 Session Store

Stores:

- session config
- device bindings
- provider binding
- event log
- current materialized session state
- observation metadata
- media references
- summaries
- memory updates
- audit trail

MVP can use a simple local file or SQLite store.

## 7. Two-Loop Model

Atlas has two primary loops that interact through shared session state.

### 7.1 Heartbeat / Perception Loop

Purpose: keep the agent physically situated.

The heartbeat loop runs procedurally and quietly. It may capture images, fetch location, run lightweight analysis, update scene state, and decide whether anything meaningful changed.

Most heartbeat ticks should not speak to the user.

Example heartbeat flow:

```text
heartbeat tick
  → check session mode and cadence
  → capture/fetch observation if needed
  → run cheap significance gate
  → update perception state
  → maybe call provider if semantic interpretation is needed
  → maybe update memory/context
  → maybe notify user only if actionable/urgent/configured
```

Heartbeat outputs:

```ts
type HeartbeatResult = {
  significance: "none" | "minor" | "context_changed" | "actionable" | "urgent";
  observations?: Observation[];
  stateUpdates?: StateUpdate[];
  providerCalled: boolean;
  userVisible: boolean;
  message?: string;
};
```

Heartbeat should use adaptive cadence:

- idle session: slow
- active task: moderate
- user moving or context unstable: faster
- after user prompt or explicit request: immediate capture if needed
- low battery/network: slower or paused

### 7.2 User Interaction Loop

Purpose: handle explicit user requests.

Triggers:

- wake phrase
- button press
- text prompt
- voice prompt
- “look at this” request
- direct task update request

The user loop should use the latest session context from the heartbeat loop, but it must also verify that context is good enough for the request.

Example user loop flow:

```text
user utterance
  → classify physical context requirements
  → inspect current context freshness/stability/confidence
  → preflight refresh if needed
  → call provider with normalized session turn
  → execute requested tools through Atlas Core
  → update session state
  → speak/respond/show output
```

## 8. Context Freshness and Confidence

Physical context should never be treated as simply “available” or “missing.”

Atlas should track:

```ts
type PerceptionState = {
  latestObservationAt?: string;
  latestImageId?: string;
  latestLocationAt?: string;
  summary?: string;
  confidence: number;
  freshnessMs: number;
  stability: "stable" | "transitioning" | "unknown";
  sceneDeltaFromPrevious?: number;
  blurScore?: number;
  motionDetected?: boolean;
  relevance?: Record<string, number>;
  notes?: string[];
};
```

Important fields:

- **freshness**: how old is the observation?
- **confidence**: how reliable is the interpretation?
- **stability**: was the user stationary, moving, turning, or between scenes?
- **relevance**: is the observation relevant to the current question/task?
- **quality**: blur, occlusion, darkness, bad angle, network failure, etc.

## 9. Preflight Context Refresh Policy

For physical-context-dependent user intents, Atlas should refresh context before provider reasoning when current context is stale, unstable, low-confidence, or irrelevant.

Examples of intents that usually require fresh context:

- “Am I in the right place?”
- “What am I looking at?”
- “Which one is it?”
- “Is this the right part?”
- “Where should I go now?”
- “Read this.”
- “What does this say?”
- “Can you see the problem?”
- “Help me fix this.”
- “Do I have everything?”

Preflight flow:

```text
classify intent
  → determine required context types
  → inspect current context status
  → if insufficient: capture image / location / OCR / audio as needed
  → update session state
  → call provider
```

The provider may also request fresh context using exposed tools, but deterministic preflight should cover common obvious cases so behavior remains consistent across providers.

## 10. Normalized Event Model

```ts
type SessionEvent =
  | { type: "session.started"; at: string }
  | { type: "session.paused"; at: string; reason?: string }
  | { type: "session.resumed"; at: string }
  | { type: "session.ended"; at: string; reason?: string }
  | { type: "heartbeat.tick"; at: string }
  | { type: "observation.captured"; at: string; observation: Observation }
  | { type: "observation.analyzed"; at: string; result: ObservationAnalysis }
  | { type: "user.utterance"; at: string; text: string; mode: "text" | "voice" }
  | { type: "provider.requested"; at: string; provider: string; turnId: string }
  | { type: "provider.responded"; at: string; provider: string; turnId: string }
  | { type: "tool.requested"; at: string; toolName: string; reason?: string }
  | { type: "tool.completed"; at: string; toolName: string; resultRef?: string }
  | { type: "agent.speech"; at: string; text: string }
  | { type: "agent.display"; at: string; text: string }
  | { type: "state.updated"; at: string; patch: unknown }
  | { type: "error"; at: string; source: string; message: string };
```

## 11. Observation Model

```ts
type Observation = {
  id: string;
  type: "image" | "audio" | "location" | "ocr" | "sensor" | "composite";
  capturedAt: string;
  deviceId: string;
  mediaRef?: string;
  data?: unknown;
  quality?: {
    confidence?: number;
    blurScore?: number;
    occluded?: boolean;
    lowLight?: boolean;
    motion?: boolean;
  };
  summary?: string;
  tags?: string[];
};
```

## 12. Session State Model

```ts
type AtlasSessionState = {
  sessionId: string;
  name?: string;
  status: "idle" | "active" | "paused" | "done" | "error";
  createdAt: string;
  updatedAt: string;

  goal?: string;
  mode: "manual" | "assisted" | "ambient";

  provider: ProviderBinding;
  devices: DeviceBinding[];

  perception: PerceptionState;
  recentObservations: Observation[];

  memory: {
    working: string[];
    durable: string[];
    environmentNotes: string[];
    taskProgress: string[];
  };

  permissions: SessionPermissions;
  audit: AuditPointer;
};
```

## 13. Provider Adapter Contract

Provider adapters implement:

```ts
interface AgentProviderAdapter {
  id: string;
  name: string;

  startSession?(session: AtlasSessionState): Promise<void>;
  endSession?(session: AtlasSessionState): Promise<void>;

  step(turn: NormalizedSessionTurn): Promise<NormalizedAgentResult>;
}
```

Normalized turn:

```ts
type NormalizedSessionTurn = {
  turnId: string;
  session: AtlasSessionSnapshot;
  trigger:
    | { type: "user"; text: string; mode: "text" | "voice" }
    | { type: "heartbeat"; reason: string }
    | { type: "tool_result"; toolCallId: string };

  contextStatus: {
    visual?: ContextStatus;
    location?: ContextStatus;
    audio?: ContextStatus;
  };

  observations: Observation[];
  availableTools: AtlasToolSchema[];
  instructions: string[];
};
```

Normalized result:

```ts
type NormalizedAgentResult = {
  turnId: string;
  responseText?: string;
  toolCalls?: AtlasToolCall[];
  memoryUpdates?: StateUpdate[];
  sessionUpdates?: StateUpdate[];
  nextLoopHint?: {
    waitMs?: number;
    requestObservation?: boolean;
    reason?: string;
  };
  done?: boolean;
};
```

Rule: provider adapters expose tool schemas to upstream runtimes, but Atlas Core executes the tools.

## 14. Device Adapter Contract

```ts
interface DeviceAdapter {
  id: string;
  name: string;
  capabilities(): Promise<DeviceCapability[]>;

  captureImage?(options?: CaptureImageOptions): Promise<Observation>;
  getLocation?(options?: LocationOptions): Promise<Observation>;
  listenAudio?(options?: AudioListenOptions): Promise<Observation>;
  speak?(text: string, options?: SpeakOptions): Promise<void>;
  vibrate?(pattern?: VibrationPattern): Promise<void>;
  showPrompt?(prompt: DisplayPrompt): Promise<void>;
}
```

Capabilities:

```ts
type DeviceCapability =
  | "camera.capture"
  | "camera.stream"
  | "audio.listen"
  | "audio.speak"
  | "location.current"
  | "screen.prompt"
  | "haptics.vibrate";
```

## 15. Tool Model

Atlas exposes normalized tools to providers.

Examples:

```ts
const tools = [
  {
    name: "capture_current_view",
    description: "Capture a fresh image from the bound device when current visual context is stale, unstable, or insufficient.",
    parameters: {
      reason: "string",
      quality: "low | medium | high"
    }
  },
  {
    name: "get_current_location",
    description: "Fetch current location from the bound device.",
    parameters: {
      reason: "string"
    }
  },
  {
    name: "speak_to_user",
    description: "Speak a short response through the bound device.",
    parameters: {
      text: "string"
    }
  }
];
```

Providers can request tools, but cannot directly execute physical actions. Atlas Core arbitrates and executes.

## 16. Permissions and Safety

Permissions should be explicit per session.

```ts
type SessionPermissions = {
  observe: boolean;
  captureImage: "never" | "on_user_request" | "during_active_session";
  location: "never" | "on_user_request" | "during_active_session";
  microphone: "never" | "wake_only" | "during_active_session";
  speak: "never" | "respond_only" | "proactive_allowed";
  display: boolean;
  haptics: boolean;
  externalActions: "never" | "confirm_each" | "allowed";
};
```

MVP default:

- active session may capture images from bound phone
- active session may speak/respond if TTS is configured
- proactive speech disabled unless explicitly enabled
- external actions disabled
- audit log always enabled

## 17. Media Retention Policy

Raw physical media can be sensitive.

MVP should support configurable retention:

```ts
type MediaRetentionPolicy = {
  rawImageRetention: "none" | "session" | "hours" | "days" | "forever";
  rawAudioRetention: "none" | "session" | "hours" | "days" | "forever";
  summaryRetention: "session" | "forever";
  defaultRawImageHours?: number;
  defaultRawAudioHours?: number;
};
```

Recommended MVP default:

- raw images retained for the active session or short local cache
- durable summaries/events retained
- raw audio not retained unless explicitly enabled

## 18. MVP Implementation Target

Primary MVP:

```text
Android phone downstream
  → Atlas device adapter
  → Atlas session core
  → OpenClaw provider adapter
  → user text prompt / optional voice later
```

MVP capabilities:

- create/start physical session
- bind Android phone camera
- heartbeat image capture at configurable cadence
- analyze whether visual context changed meaningfully
- maintain latest perception state
- accept user text prompt
- detect whether prompt requires fresh physical context
- snap fresh image when context is stale/unstable/insufficient
- send normalized turn to OpenClaw provider
- receive response/tool calls
- execute capture tool through Atlas Core
- update audit log/session state
- return response to user

Optional MVP+:

- Android TTS output
- STT voice input
- GPS observations
- OCR pass
- simple web UI/session inspector

## 19. First Test Scenarios

### Scenario A: “What am I looking at?”

Expected behavior:

1. User asks: “What am I looking at?”
2. Atlas determines visual context is required.
3. If latest visual context is stale/unstable/missing, Atlas captures a fresh image.
4. Atlas updates perception state.
5. Provider answers using current visual context.

Pass criteria:

- no answer from stale visual context when fresh capture is required
- audit log shows preflight refresh decision
- response references current image accurately

### Scenario B: “Am I in the right place?”

Expected behavior:

1. User asks location/placement question.
2. Atlas checks visual and location context.
3. If insufficient, Atlas captures image and optionally gets GPS.
4. Provider answers with uncertainty if context is still insufficient.

Pass criteria:

- model does not hallucinate certainty
- fresh capture happens when prior image is old or transitional
- response includes useful next action

### Scenario C: Heartbeat context update, no speech

Expected behavior:

1. Heartbeat captures image.
2. Scene is unchanged or unimportant.
3. Atlas updates freshness metadata silently.
4. No user-facing response.

Pass criteria:

- no babbling
- state updates occur
- provider call avoided when significance gate says unnecessary

### Scenario D: Heartbeat detects actionable change

Expected behavior:

1. User is in active task.
2. Heartbeat detects meaningful scene change.
3. Atlas may call provider for interpretation.
4. Agent speaks only if change is actionable and permission allows proactive speech.

Pass criteria:

- proactive speech is rare and useful
- audit log explains why it spoke

## 20. Testing Strategy with Current Tools

Available current setup:

- upstream: OpenClaw
- downstream: Android phone
- existing Android camera bridge capture path
- local image analysis available through OpenClaw/Ollama path

Testing phases:

### Phase 1: Manual capture harness

- Trigger Android camera capture manually.
- Save staged image reference.
- Feed image into Atlas perception update.
- Confirm session state updates.

### Phase 2: User-loop stale context test

- Start session with no image.
- Ask “What am I looking at?”
- Confirm Atlas captures fresh image before provider answer.

### Phase 3: Fresh context reuse test

- Capture image.
- Ask “What am I looking at?” shortly after while stable.
- Confirm Atlas can reuse context or choose refresh based on policy.

### Phase 4: Transitional context test

- Capture while moving/blurred/transitional.
- Mark state as unstable.
- Ask “Am I in the right place?”
- Confirm Atlas refreshes.

### Phase 5: Heartbeat silence test

- Run heartbeat for several ticks in an unchanged scene.
- Confirm no provider spam and no user-facing chatter.

### Phase 6: Provider adapter swap simulation

- Implement a fake provider adapter.
- Feed same normalized turn into OpenClaw adapter and fake adapter.
- Confirm Atlas Core behavior remains provider-independent.

## 21. Suggested Repository Layout

```text
atlas/
  BUILD_DOC.md
  README.md
  packages/
    atlas-core/
      src/
        session/
        loops/
        state/
        tools/
        audit/
    atlas-device-android/
      src/
    atlas-provider-openclaw/
      src/
    atlas-test-harness/
      src/
  docs/
    architecture.md
    adapter-contracts.md
    testing.md
  examples/
    android-openclaw-basic/
```

For the current workspace draft, this document lives at:

```text
atlas/BUILD_DOC.md
```

## 22. Implementation Milestones

### Milestone 0: Design Lock

- Confirm project name.
- Confirm MVP boundaries.
- Confirm Android/OpenClaw first-path assumptions.
- Finalize adapter contracts.

### Milestone 1: Core Session Skeleton

- Session create/load/save.
- Event log.
- Materialized session state.
- Device/provider binding placeholders.

### Milestone 2: Android Device Adapter

- Wrap existing Android camera bridge.
- Normalize image captures into `Observation` objects.
- Track image path/media ref, timestamp, quality metadata where available.

### Milestone 3: Perception State + Freshness Policy

- Maintain latest visual context.
- Track age/freshness/stability/confidence.
- Implement simple stale/unstable decision logic.

### Milestone 4: User Loop MVP

- Accept text prompt.
- Classify whether prompt requires visual context.
- Preflight refresh when needed.
- Send normalized turn to provider.
- Return response.

### Milestone 5: OpenClaw Provider Adapter

- Convert Atlas normalized turn into OpenClaw-compatible request.
- Expose Atlas tools in provider-appropriate shape.
- Convert provider response/tool calls into `NormalizedAgentResult`.

### Milestone 6: Heartbeat Loop MVP

- Configurable timer.
- Capture/update context.
- Simple significance gate.
- Silent-by-default behavior.

### Milestone 7: Audit + Test Harness

- Inspect events.
- Replay/debug basic sessions.
- Run scenario tests.

### Milestone 8: Voice Layer

- Add STT input interface.
- Add TTS/speak interface.
- Keep text in/out as fallback.

## 23. Resolved Decisions

- Project name: **Atlas**.
- Initial repo/runtime shape: TypeScript + Node workspace.
- First upstream provider: OpenClaw.
- First downstream device: Android phone.
- Atlas Core owns session state, context freshness, tool execution, and audit.
- Adapters remain thin and swappable.
- Store: JSONL event log + JSON materialized state first; SQLite later if needed.
- Android integration: wrap existing OpenClaw Android Camera Bridge first; direct client later.
- OpenClaw adapter: runtime adapter, not raw LLM adapter.
- Image analysis boundary: Atlas owns objective metadata/policy signals; provider owns semantic interpretation.
- Significance gate: simple heuristics first.
- Memory boundary: Atlas session memory authoritative; provider memory optional/runtime-specific.

## 24. Open Questions

These can wait until implementation pressure makes them concrete:

1. Exact OpenClaw callable path for the first provider adapter.
2. Exact media cache/retention defaults after first Android integration test.
3. Whether SQLite becomes necessary before MVP or after MVP.

## 25. Strong Recommendations

- Treat **Atlas** as the framework/product name.
- Build Atlas as a standalone framework, not an OpenClaw feature.
- Use OpenClaw only as the first upstream adapter.
- Use Android only as the first downstream adapter.
- Keep Atlas Core responsible for session state, context freshness, tool execution, and audit.
- Keep adapters thin.
- Implement user-loop preflight refresh before complex provider behavior.
- Do not build the native Atlas runtime yet.
- Prove that the physical session loop feels good first.

## 26. One-Sentence MVP

Atlas MVP binds Seth’s Android phone to an OpenClaw-backed physical session so a user can ask real-world questions like “what am I looking at?” or “am I in the right place?”, and Atlas will maintain ambient visual context, refresh stale context when needed, call the upstream agent through a provider shim, and respond accurately without hard-coding itself to OpenClaw.
