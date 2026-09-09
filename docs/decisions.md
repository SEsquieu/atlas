# Atlas Decisions

This is a chronological decision log. Earlier entries describe the system as it existed when the decision was made; later entries supersede them. In particular, all May 2026 OpenClaw-first and bridge-first decisions are historical prototype choices superseded by the September 2026 native Android runtime decision below.

## 2026-05-05 — Name

The project name is **Atlas**.

We intentionally dropped “Project” from the name. Atlas is the framework/product name, not just a temporary project label.

## 2026-05-05 — Architecture Shape

**Status: partially superseded.** Provider independence and Core ownership remain; the OpenClaw-first and downstream-device implementation choices do not.

Atlas is standalone and provider-agnostic.

- Historical choice at the time: OpenClaw would be the first upstream provider adapter.
- Android phone is the first downstream device adapter.
- Atlas Core owns the physical session loop, context freshness, tool execution, and audit trail.
- Provider/device adapters should remain thin and swappable.

## 2026-05-05 — Phase 0 Implementation Locks

**Status: superseded implementation plan.** These constraints document the TypeScript/bridge prototype, not the native Android release path.

Phase 0 decisions are locked as the base for implementation:

- **Store:** JSONL event log + JSON materialized state first. Migrate to SQLite later only when search/concurrency/session volume justify it.
- **Android integration:** wrap the existing OpenClaw Android Camera Bridge first. Build a direct Android client later.
- **OpenClaw adapter path:** treat OpenClaw as an agent runtime adapter, not a raw LLM/completions adapter. Use the most stable callable OpenClaw path available from the Node/test harness first.
- **Image analysis boundary:** Atlas Core owns objective metadata and policy signals such as capture time, source, freshness, stale/unstable flags, and later cheap blur/motion signals. Provider/runtime owns semantic interpretation.
- **Significance gate:** start with simple heuristics: freshness, stability, confidence, summary changes, and explicit user intent requiring fresh context. Avoid embeddings/CV complexity until needed.
- **Memory boundary:** Atlas session memory is authoritative for task state, observations, audit, context freshness, and session progress. Provider memory is optional/runtime-specific context, not the source of truth.

## 2026-05-06 — Context, Memory, and Spillover Shape

Atlas should preserve the architectural shape for richer situated memory without requiring the full system for MVP.

- **Dynamic heartbeat cadence:** heartbeat timing should become a policy output based on scene stability, motion, active task state, idle state, and risk level. Fixed cadence is acceptable only as an initial implementation detail.
- **Freshness decay:** physical context freshness should decay using contextual multipliers such as motion, confidence, stability, volatility, task type, and risk.
- **Memory as weighted belief:** future Atlas memory should represent claims about reality over time, with importance, confidence, freshness, relevance, truth status, decay policy, scope, and provenance. Truth can transition `current → stale → historical → archived`.
- **Context lanes:** physical context, supporting context, and spillover must remain separate.
- **Spillover quarantine:** unrelated side requests should be detected and quarantined/delegated rather than written into physical session memory.
- **Provider sidebands:** provider adapters may emit structured suggestions, but Atlas independently validates and adopts or rejects them. Providers suggest meaning; Atlas owns physical truth.
- **Budgeted sidecars:** future parallel inference sidecars may enrich context, but each must have a reason, budget, destination, and permission to be ignored.

See `docs/context-memory-and-sidecars.md` for the detailed shape and MVP/deferred split.

## 2026-05-06 — Command-backed live harness seam

**Status: retained only as a legacy test seam.** It is not the current application architecture.

For the first live MVP harness, Atlas will support command-backed adapters selected from config:

- `@atlas/device-android/bridge-command`
- `@atlas/provider-openclaw/command`

The command seam is intentionally an edge integration path. It lets the CLI call local OpenClaw/Android wrappers without importing OpenClaw runtime/tool APIs into `@atlas/core`.

This is not the final daemon/runtime shape. It is a low-friction bridge for proving the loop, gathering latency/timing data, and keeping provider/device boundaries honest before building a persistent native runtime.

## 2026-09-03 — Android-owned runtime supersedes the bridge

The native Android app is the v0.1 product direction. OpenClaw and its camera bridge remain historical prototype adapters; neither owns the mobile session nor defines the media contract. Atlas Core on the phone owns lifecycle, capture, freshness, heartbeat, interaction, routing, and the audit trail.

Captured camera files are normalized behind `MediaRepository`. Raw camera output is temporary. Durable observations reference an opaque private artifact with dimensions, size, digest, purpose, and processing latency. Provider adapters receive image bytes, never Android filesystem paths.

## 2026-09-03 — BYOI plus an optional metered Atlas Cloud adapter

Bring-your-own inference remains usable without an Atlas identity or Atlas-operated service. Atlas Cloud is an optional provider endpoint, not a session runtime or control plane.

Managed inference uses user authentication and a server-held inference credential. Product pricing is expressed through an internal integer credit ledger. Each call atomically reserves a maximum charge and settles against provider-reported usage. Stripe subscriptions grant recurring allowance; one-time purchases grant non-expiring credit blocks. Capability routes and rates remain server configuration so the Android runtime is not coupled to provider model names.

Modulo integration remains deferred until Atlas's phone-owned loop is independently dependable and Modulo presents a stable adapter target.
