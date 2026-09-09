<div align="center">

# ATLAS

### Physical intelligence, owned by the runtime.

**The model is not the agent.**

Atlas is an Android-native runtime for a persistent physical agent.  
The phone is the body. Models are replaceable reasoning engines.

[Run Atlas](#run-atlas) · [Architecture](./docs/architecture.md) · [Runtime Code Map](./docs/runtime-code-map.md) · [Engineering Docs](./docs/README.md) · [Roadmap](./ROADMAP.md)

![Android](https://img.shields.io/badge/Android-native-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-runtime-7F52FF?logo=kotlin&logoColor=white)
![License](https://img.shields.io/badge/license-Apache--2.0-blue)
![Status](https://img.shields.io/badge/status-pre--v0.1_alpha-yellow)

<img src="./docs/assets/atlas-session-alpha.jpg" alt="Atlas pre-v0.1 alpha session interface on Android" width="420">

<sub><em>Current pre-v0.1 Android alpha. Real app, not a product mockup.</em></sub>

</div>

---

## What Atlas is

Atlas is not a chatbot shell with some tools attached. It is the deterministic runtime around inference.

The runtime owns the things a persistent physical agent cannot safely outsource to whichever model happens to answer the next turn:

| Atlas Core owns | Models provide |
| --- | --- |
| Identity and session continuity | Reasoning |
| Physical observations and freshness | Interpretation |
| Context assembly and memory admission | Language generation |
| Tool authority and confirmation | Tool proposals |
| Failure, timeout, and restart semantics | Probabilistic decisions |
| Routing, budgets, replay, and provenance | Replaceable intelligence |

Changing from an on-device model to a LAN server or cloud API changes a capability. It does **not** change the identity that owns the session.

> **Status:** public pre-v0.1 open-source alpha. Atlas is appropriate for development and invited physical-device testing, not unattended, emergency, safety-critical, or production operation.

## Start here

| If you want to... | Go here |
| --- | --- |
| **Build and run Atlas** | [`apps/atlas-android`](./apps/atlas-android) |
| **Understand the whole system** | [`docs/architecture.md`](./docs/architecture.md) |
| **Trace a turn through the shipping runtime** | [`docs/runtime-code-map.md`](./docs/runtime-code-map.md) |
| **Dig into the Android runtime** | [`docs/android-runtime-architecture.md`](./docs/android-runtime-architecture.md) |
| **Understand context and memory** | [`docs/context-memory-and-sidecars.md`](./docs/context-memory-and-sidecars.md) |
| **Understand failure semantics** | [`docs/failure-semantics.md`](./docs/failure-semantics.md) |
| **Browse all engineering docs** | [`docs/README.md`](./docs/README.md) |
| **See what is intentionally unfinished** | [`ROADMAP.md`](./ROADMAP.md) |

## Why Atlas exists

Most agent frameworks begin with a model conversation and attach tools. Atlas begins with a continuing physical session.

That means the runtime has to answer questions a model conversation usually gets to ignore:

- What device and environment is this agent bound to?
- What did it actually observe, when, and with what confidence?
- Is that context still fresh enough for the current question?
- What did the user actually hear before interrupting?
- Which memory is valid for this session, task, person, or workspace?
- Which physical or external actions are permitted?
- What happened across failure, restart, and provider replacement?

The model can reason about these questions. **It does not get to define their answers.**

## One turn, end to end

```mermaid
flowchart LR
    A[Voice / text] --> B[Atlas Core]
    B --> C[Context + freshness]
    C --> D[Capability route]
    D --> E[Replaceable model]
    E --> F{Answer or tool?}
    F -->|Answer| G[Persist + speak]
    F -->|Tool| H[Policy + confirmation]
    H --> I[Phone capability]
    I --> B
```

The shipping Android path starts at [`AtlasMobileRuntime.startUserTurnLocked`](./apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/runtime/AtlasMobileRuntime.kt), assembles Core-owned state through [`ContextAssembler`](./apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/runtime/ContextAssembler.kt), routes endpoints with [`CapabilityRouter`](./apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/provider/CapabilityRouter.kt), and persists lifecycle evidence through [`AtlasDatabase`](./apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/data/AtlasDatabase.kt).

The [runtime code map](./docs/runtime-code-map.md) traces the complete path and distinguishes the shipping Kotlin runtime from the provider-independent TypeScript reference Core.

## What is real today

The native Android reference app currently provides:

- a foreground, device-owned persistent session service;
- durable SQLite sessions, turns, messages, observations, memory, tool calls, speech delivery, and ordered events;
- CameraX capture with rotation, resize, recompression, hashing, and per-purpose byte budgets;
- motion-aware visual freshness and detail-suitability checks;
- push-to-talk, streaming sentence-aware TTS, and barge-in tracking;
- manual and Live Context modes with scene-change and proactive-inference limits;
- bounded multi-step model/tool turns with confirmation and restart recovery;
- durable conversational clarification that survives tangents and blocks ambiguous physical actions;
- capability routes for `fast`, `vision`, `reasoning`, and `fallback`;
- direct OpenAI-compatible local, LAN, and cloud endpoints;
- optional Atlas Managed account/inference plumbing;
- in-app session, inference, health, context, and event views.

The TypeScript packages contain the provider-independent Core model, deterministic freshness and heartbeat logic, event materialization, scoped memory, runtime-policy resolution, task-run lifecycle, replay, CLI, and scenario harnesses. OpenClaw components remain only as documented legacy proof adapters.

## Design principles

> **The runtime owns identity.**  
> A provider swap should not turn Atlas into a different agent.

> **Physical evidence has freshness.**  
> A stronger model cannot reason its way out of a stale observation.

> **Execution state matters.**  
> Success, failure, timeout, interruption, and unknown outcome are different states.

> **Keep deterministic machinery deterministic.**  
> Models are used where probabilistic intelligence adds value, not simply because a model is available.

> **Capability and reliability are different variables.**  
> An endpoint can know how to do something and still fail to invoke that capability when it matters.

For the deeper rationale, start with the [engineering documentation index](./docs/README.md).

## Open platform

Atlas is designed so the open runtime remains useful on its own:

- **Atlas Open Platform:** Apache-2.0 Core, Android reference app, SDK contracts, tests, and BYOI.
- **Atlas Managed Inference:** optional operated inference, routing, metering, and support.
- **Atlas Enterprise:** future organization, fleet, procedure, policy, integration, audit, and operational control plane built on the same Core.

Open Atlas does not require an Atlas account. Managed inference does not own the physical session. Enterprise must extend versioned open contracts rather than fork a private runtime.

Read [`docs/product-structure.md`](./docs/product-structure.md) for the exact boundary and [`docs/pricing-and-metering.md`](./docs/pricing-and-metering.md) for the alpha budget and later pricing framework.

## Run Atlas

Open [`apps/atlas-android`](./apps/atlas-android) in Android Studio, let Gradle sync, and run it on a physical Android device. Camera and microphone access are required for the complete loop.

Configure an OpenAI-compatible endpoint in the app. For LAN inference, use the computer's LAN address from the phone. `10.0.2.2` is only the Android emulator alias for its host.

See [`apps/atlas-android/README.md`](./apps/atlas-android/README.md) for setup, provider compatibility, media handling, speech behavior, and current limitations.

### Build and test Core

Node.js 22 or later is required.

```bash
npm ci
npm run build
npm run typecheck
npm test
npm run check:public
```

Managed inference is optional. See [`apps/atlas-cloud/README.md`](./apps/atlas-cloud/README.md) for its separate local setup.

## Repository map

| Path | Purpose |
| --- | --- |
| [`apps/atlas-android`](./apps/atlas-android) | Native Android reference app and primary v0.1 experience |
| [`apps/atlas-cloud`](./apps/atlas-cloud) | Optional reference managed-inference/auth/billing gateway |
| [`packages/atlas-core`](./packages/atlas-core) | Provider-independent runtime types, policy, events, replay, and tests |
| [`packages/atlas-config`](./packages/atlas-config) | Configuration contracts |
| [`packages/atlas-cli`](./packages/atlas-cli) | Prototype session inspection and development commands |
| [`packages/atlas-test-harness`](./packages/atlas-test-harness) | Scenario, replay, and adapter test utilities |
| [`packages/atlas-device-android`](./packages/atlas-device-android) | Legacy TypeScript Android bridge adapter |
| [`packages/atlas-provider-openclaw`](./packages/atlas-provider-openclaw) | Legacy OpenClaw proof adapter, not a product dependency |
| [`docs`](./docs) | Architecture, product boundaries, testing, policies, and release gates |

## v0.1 boundary

**One Android phone. One durable personal workspace. One excellent observe → reason → act → respond loop. User-owned inference. Enough evidence to explain every consequential decision.**

The source repository is public. [`OPEN_SOURCE_CHECKLIST.md`](./OPEN_SOURCE_CHECKLIST.md) records release-readiness work and remaining repository hardening. Distribution of a signed closed-alpha APK is a separate gate tracked by [`docs/closed-alpha.md`](./docs/closed-alpha.md).

## Contributing and security

Contributions are welcome under [`CONTRIBUTING.md`](./CONTRIBUTING.md) and require Developer Certificate of Origin sign-off. Report vulnerabilities privately according to [`SECURITY.md`](./SECURITY.md).

## License

Atlas repository code and documentation are licensed under the [Apache License 2.0](./LICENSE) unless a file states otherwise. Dependencies retain their own licenses. The software license does not grant rights to official project identity or imply access to Atlas-operated services.
