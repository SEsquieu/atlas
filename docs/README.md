# Atlas Engineering Documentation

Atlas is easiest to understand by following ownership from the persistent runtime outward. Start with the runtime map, then use the focused documents below when a boundary matters.

## Current implementation

- [Alpha.36 release and phone cycle](./releases/v0.1.0-alpha.36.md) — autonomous visual testing and explicit physical-device acceptance.

- [Autonomous workspace visual testing](./workspace-visual-testing.md) — optional private-display rendering, measured device profiles, interaction evidence and physical-device qualification.

- [Alpha.35 release and test cycle](./releases/v0.1.0-alpha.35.md) — host comparisons, input diagnostics and retained source.
- [Alpha.34 phone assessment](./alpha34-live-assessment.md) — successful delivery and remaining test/source churn.

- [Alpha.34 release and test cycle](./releases/v0.1.0-alpha.34.md) — workspace recovery and native-confirmed local runtime proposals.
- [Alpha.33 phone assessment](./alpha33-live-assessment.md) — revision/approval blockers and separate bridge/provider measurements.

- [Alpha.28 live assessment](./alpha28-live-assessment.md) — observed delivery, persistence, authoring churn, completion failures, and next implementation priorities.

- [Current status](./current-status.md) — current capabilities, verification evidence, limits, and distribution.
- [Durable execution](../apps/atlas-android/DURABLE_EXECUTION.md) — slice continuation, task ownership, receipts, recovery, and allowances.
- [Workspace application host](./workspace-application-host.md) — implementation chronology and qualification evidence.
- [Import and export](./import-export.md) — session/workspace recovery and authority reset.
- [Capability discovery](./capability-discovery.md) — bounded external capability inspection.

## Runtime and ownership

- [Runtime code map](./runtime-code-map.md) — end-to-end path through the shipping Android implementation, with direct source links.
- [Composable workspaces](./composable-workspaces.md) — original design and acceptance goals; use current status and the application-host chronology for implemented behavior.
- [Workspace alpha test plan](./workspace-alpha-test-plan.md) — live Android exercises for creation, revision, context switching, session boundaries, persistence, canvas, export, and failure handling.
- [Persistent intent and idle runtime](./persistent-intent-runtime.md) — durable unresolved intent, autonomy modes, scoring, budgets, preemption, and idle telemetry.
- [Sign in with ChatGPT and plan inference](./chatgpt-plan-inference.md) — local OAuth, protected credentials, Responses translation, and provider boundaries.
- [Architecture](./architecture.md) — repository-level components and ownership boundaries.
- [Android runtime architecture](./android-runtime-architecture.md) — native service, runtime, persistence, provider, camera, and speech composition.
- [Atlas Core](./agent-runtime.md) — turn persistence, context assembly, memory admission, tools, recovery, and loop budgets.
- [Runtime failure semantics](./runtime-failure-semantics.md) — timeout, cancellation, late arrival, restart, and unknown execution state.

## Evidence, context, and action

- [Visual freshness policy](./visual-freshness-policy.md) — why recency, suitability, motion, and confidence are separate variables.
- [Context, memory, and sidecars](./context-memory-and-sidecars.md) — scoped memory and bounded context behavior.
- [Routing and endpoint characterization](./endpoint-characterization.md) — current declared-capability routing and a measured-capability direction that does not invent a giant router.
- [Managed model routing](./model-routing.md) — deterministic managed-inference scorer and its current boundary.
- [Adapter contracts](./adapter-contracts.md) — device and provider interfaces, including historical adapters.

## Testing and release work

- [Build and test](./build-and-test.md) — reproducible checks for Core, Android, cloud, and public readiness.
- [Android app guide](../apps/atlas-android/README.md) — install, first run, ChatGPT-plan sign-in, BYOI endpoints, build variants, and limitations.
- [Testing](./testing.md) — test layers and the role of the legacy scenario harness.
- [Behavioral eval fixtures](../evals/README.md) — small regression cases derived from real sessions.
- [Public-release audit](./public-release-audit.md) — findings, evidence, and remaining publication blockers.
- [Public name collision review](./name-review.md) — preliminary collision evidence and a qualified-name recommendation.
- [Assets and redistribution](./assets-and-redistribution.md) — tracked media/font/audio inventory and website boundary.
- [Proposed v0.1.0-alpha.5 notes](./releases/v0.1.0-alpha.5.md) — release scope without claiming unfinished gates are complete.
- [v0.1 release decisions](./release-decisions.md) — naming, attribution, prototype-history, branch, and distribution choices.
- [Public release runbook](./public-release-runbook.md) — source freeze, GitHub controls, signing, acceptance, and visibility sequence.
- [Android release matrix](./release-device-matrix.md) — concrete signed-build acceptance cases and evidence fields.

## Principles and direction

- [Design principles](./design-principles.md) — implementation-backed assessment of Atlas's core claims.
- [Product structure](./product-structure.md) — open runtime, optional managed inference, and future enterprise boundary.
- [“The Model Is Not the Agent” essay outline](./the-model-is-not-the-agent-outline.md) — future technical essay structure.
- [Roadmap](../ROADMAP.md) — explicitly deferred work, including packaged embeddings and evolving personality.

## Historical prototypes

- [OpenClaw prototype build plan](./history/openclaw-prototype-build-plan.md) — the superseded May 2026 plan preserved as architectural history.
- [OpenClaw latency notes](./latency-notes.md) — measurements from the bridge era that motivated native ownership and warm-path work.
- [Legacy Android/OpenClaw example](../examples/android-openclaw-basic/README.md) — retained adapter harness and contract tests, not current app setup.

Documents describe the code that exists. A future direction is labeled as such; absence of a document is preferable to pretending that a subsystem has already shipped.

Contributor work starts in the [active fix backlog](./active-fixes.md); see the [workflow guide](./contributor-workflow.md) for claims, PR evidence, and independent APK builds.

- [Replaceable runtime milestone](./replaceable-runtime.md): experimental bridge, update trust, race/latency invariants and remaining gates.
