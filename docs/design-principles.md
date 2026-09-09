# Design principles: implementation status

This is a reality check, not a manifesto. Each principle is paired with what the repository actually enforces and where it remains incomplete.

## Deterministic machinery resolves deterministic uncertainty

Supported: freshness classification, scene-difference gating, route compatibility, policy overlays, tool limits, event ordering, scoped memory visibility, and lifecycle transitions are ordinary code with tests. Models interpret scenes, summarize conversation, and propose actions; they do not set permissions or declare execution success.

Gap: native conversation compaction is model-derived and currently has no structured fact-level verifier. Intent classification is regex-based and necessarily incomplete.

## Capability and reliability are different variables

Partially supported: endpoints declare modalities; runtime attempts and telemetry record actual successes/failures. Routing does not yet compute empirical reliability, so user-ordered routes still trust configuration. See [`endpoint-characterization.md`](./endpoint-characterization.md).

## Physical evidence has freshness

Supported: observation time, availability time, media purpose, stability, motion, and confidence are distinct fields. Freshness policy varies by use case and motion. High-risk current-world claims require a new sample and fail closed when it is unavailable.

Gap: classifiers and stale windows are hand-tuned, not yet calibrated across a physical-device evidence set.

## Execution has consequences

Supported: proposed, confirmation-waiting, running, completed, rejected, failed, and unknown tool states do not collapse. Ambiguous provider failures suppress automatic failover. Interruption and process recovery mark running effects unknown rather than silently retrying. See [`runtime-failure-semantics.md`](./runtime-failure-semantics.md).

Gap: the first-party tool set is deliberately small, and external-effect tools need more integration-specific idempotency tests.

## Atlas persists across brains

Supported: sessions, messages, memory, observations, permissions, tool results, and events live in Core-owned storage. Endpoints receive a bounded projection. Provider swaps do not migrate identity into provider conversation state.

Gap: some providers may expose continuation IDs, but Atlas records rather than depends on them. Cross-provider behavioral equivalence is not guaranteed and endpoint characterization is early.

## A model buys automation, not success

Supported: provider output is candidate text or typed proposals. Freshness, permission, policy, confirmation, execution, delivery, timeout, and persistence remain separate Core decisions. The `unsupported_causal_bridge` fixture specifically records a case where memory retrieval succeeded while inference quality failed.

Gap: fixture scoring is currently human/runner-facing; Atlas does not yet use eval results to route automatically.
