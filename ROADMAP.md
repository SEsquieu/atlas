# Atlas roadmap

Status: working roadmap toward a closed Android alpha and public v0.1 developer release.

## Current position

Atlas has crossed the line from adapter experiment to a native physical-agent runtime baseline:

- durable multi-turn Android sessions;
- bounded context assembly and checkpoint memory;
- scoped ownership, memory, task-run, and policy primitives;
- resized and budgeted image derivatives;
- freshness and suitability decisions;
- push-to-talk, streaming sentence TTS, and interruption tracking;
- bounded tool loops with confirmation and recovery;
- manual and Live Context modes;
- capability-oriented provider routing;
- optional managed-inference authentication, metering, and billing plumbing; and
- deterministic Core, cloud, and Android CI.

The primary risk is now product reliability and comprehensibility, not missing architectural concepts.

## Milestone A: closed-alpha release candidate

Goal: invited users can run real physical loops without uncontrolled privacy, cost, or lifecycle failure.

Must complete:

- signed release build and controlled distribution;
- first-run permission, privacy, inference-path, and Live Context disclosures;
- session delete and export across state, memory, events, and media;
- release-build network security with explicit LAN endpoint handling;
- editable session permissions and visible provider/media policy;
- per-user daily/monthly and global managed-inference budgets;
- operational rate limits, alerts, and stuck-reservation recovery;
- fresh-install and database-upgrade tests;
- physical-device test matrix including Bluetooth earbuds and process death;
- deterministic golden-loop suite for conversation, perception, tools, interruption, and failure;
- support, severity, rollback, and known-issues process; and
- version, commit, checksum, signing fingerprint, and release-note provenance.

The complete gate is [`docs/closed-alpha.md`](./docs/closed-alpha.md).

## Milestone B: public v0.1 developer release

Goal: another developer can understand Atlas, install it, configure inference, reproduce the reference loop, and build an adapter or tool without private context from the maintainer.

Must complete:

- every blocking item in [`OPEN_SOURCE_CHECKLIST.md`](./OPEN_SOURCE_CHECKLIST.md);
- project-name and redistribution-rights review;
- repository history secret scan and credential rotation;
- coherent API/versioning policy for Core, provider, device, tool, event, and storage contracts;
- one supported local/LAN inference setup guide and one cloud BYOI guide;
- connection diagnostics and per-capability route editing;
- replay fixture and troubleshooting guide based on a real alpha failure;
- third-party notices and reproducible release artifacts;
- replacement or clear archival treatment of OpenClaw-era setup paths; and
- a tagged `v0.1.0` release with migration notes and known limitations.

## Milestone C: open beta and paid managed inference

Goal: users can choose BYOI or purchase a reliable Atlas-operated inference experience.

Must complete:

- separate recorded provider cost and customer charge;
- pricing validated from alpha route/cost distributions;
- payment, tax, refund, cancellation, and allowance policy;
- abuse prevention and provider-failure reconciliation;
- operator dashboards, alerts, support expectations, and incident process;
- production privacy policy, terms, subprocessors, retention, and diagnostic consent;
- external review of auth, tenant isolation, secrets, billing, and tool authorization; and
- staged beta rollout with explicit aggregate spend ceiling.

## Milestone D: first Atlas Enterprise pilot

Goal: prove that the consumer runtime can support one repeatable physical workflow without becoming a second runtime.

Choose one narrow process with a stationary or wearable Android device, explicit procedure, measurable completion criteria, and a real operational owner.

Likely additions:

- organization provisioning and roles;
- device/station enrollment;
- procedure revision and task assignment UI;
- organization policy distribution;
- centralized event ingestion and supervisor review;
- one knowledge-system or operational-system connector;
- pooled budget and task-level cost reporting; and
- deployment/support runbook.

The pilot should reuse the same session, memory, observation, tool, and event contracts used by the personal app.

## Deferred experiments

- Modulo integration before Atlas has a stable provider contract
- Always-listening wake word
- Autonomous invisible background operation
- Multi-device live session handoff
- iOS or premature shared UI framework
- Provider marketplace
- Generalized vector-memory infrastructure
- Broad enterprise connector catalog
- Regulated-industry or safety certification claims

## Working order

1. Make the signed Android alpha safe and operable.
2. Run real loops and measure behavior, latency, cost, and return use.
3. Fix the failures that threaten trust or continuity.
4. Publish a coherent v0.1 developer surface.
5. Validate managed-inference pricing.
6. Select one enterprise process and build only the control-plane pieces it proves necessary.

Atlas does not need more speculative adapters before these milestones. It needs one loop people choose to keep using.
