# Atlas

Atlas is a device-owned runtime for physical AI agents. It keeps a model situated in a changing physical environment without allowing the inference provider to own the session.

Atlas Core owns durable session state, device permissions, observations, context age and confidence, heartbeat policy, speech, tool execution, lifecycle, and the event log. Inference endpoints are replaceable downstream intelligence.

## Current implementation

There are now two implementation tracks in this repository:

- `apps/atlas-android` is the v0.1 direction: a native Android reference app that runs the physical session loop on the phone and connects directly to user-configured inference.
- `packages/*` is the original TypeScript prototype. It contains useful session, freshness, heartbeat, replay, adapter, CLI, and test-harness work. Its OpenClaw integration is a legacy adapter, not Atlas's product boundary.

The Android POC currently includes:

- a foreground, device-owned session service;
- durable SQLite sessions, observations, and monotonically ordered events;
- CameraX capture plus motion-aware freshness decisions;
- a Core-owned media repository that rotates, downsizes, recompresses, hashes, and enforces per-purpose byte budgets before inference;
- voice input and interruptible spoken responses;
- resource-aware heartbeat and deterministic scene-change gating;
- capability routes (`fast`, `vision`, `reasoning`, `fallback`);
- direct OpenAI-compatible local, LAN, and cloud endpoints;
- Android Keystore-encrypted provider credentials; and
- in-app session, inference, health, context, and event-stream views.

`apps/atlas-cloud` is the optional managed-inference seam: Supabase authentication, a server-held provider key, deterministic risk/latency-aware model selection, atomic credit metering, Stripe subscriptions, and purchasable credit blocks. It is not required for BYOI and does not own physical-session state. It must be deployed and configured before the managed card in the Android app is enabled.

Atlas intentionally does **not** depend on Modulo, a remote session control plane, or a hosted device-state service.

## Run the Android POC

Open [`apps/atlas-android`](./apps/atlas-android) as a project in Android Studio, let Gradle sync, and run it on a physical Android device. Camera and microphone access are required for a full physical session.

Configure an OpenAI-compatible endpoint in the app. For LAN inference, use the computer's LAN address from a phone; `10.0.2.2` is only the Android emulator's alias for its host.

See [`apps/atlas-android/README.md`](./apps/atlas-android/README.md) for setup and compatibility details and [`docs/android-runtime-architecture.md`](./docs/android-runtime-architecture.md) for the architecture and release boundary.

See [`apps/atlas-cloud/README.md`](./apps/atlas-cloud/README.md) for the optional managed-inference gateway and billing setup.
See [`docs/model-routing.md`](./docs/model-routing.md) for the two-layer provider/model routing boundary and calibration rules.

## TypeScript prototype

Node.js 20+ is required.

```bash
npm ci
npm run build
npm run typecheck
```

The original prototype and harness remain useful for contract exploration and replay work. See [`BUILD_DOC.md`](./BUILD_DOC.md), [`docs/adapter-contracts.md`](./docs/adapter-contracts.md), and [`docs/context-memory-and-sidecars.md`](./docs/context-memory-and-sidecars.md). Treat references to OpenClaw as historical implementation detail, not the target architecture.

## v0.1 principle

One Android phone, one durable Atlas session, one excellent observe–reason–respond loop, user-owned inference, and enough evidence to explain every decision. Breadth comes after that loop is trustworthy.
