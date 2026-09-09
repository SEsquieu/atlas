# Atlas Engineering Documentation

Atlas is easiest to understand by following ownership from the persistent runtime outward. Start with the runtime map, then use the focused documents below when a boundary matters.

## Runtime and ownership

- [Runtime code map](./runtime-code-map.md) — end-to-end path through the shipping Android implementation, with direct source links.
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
- [Testing](./testing.md) — test layers and the role of the legacy scenario harness.
- [Behavioral eval fixtures](../evals/README.md) — small regression cases derived from real sessions.
- [Public-release audit](./public-release-audit.md) — findings, evidence, and remaining publication blockers.
- [Proposed v0.1.0-alpha.5 notes](./releases/v0.1.0-alpha.5.md) — release scope without claiming unfinished gates are complete.

## Principles and direction

- [Design principles](./design-principles.md) — implementation-backed assessment of Atlas's core claims.
- [Product structure](./product-structure.md) — open runtime, optional managed inference, and future enterprise boundary.
- [“The Model Is Not the Agent” essay outline](./the-model-is-not-the-agent-outline.md) — future technical essay structure.
- [Roadmap](../ROADMAP.md) — explicitly deferred work, including packaged embeddings and evolving personality.

Documents describe the code that exists. A future direction is labeled as such; absence of a document is preferable to pretending that a subsystem has already shipped.
