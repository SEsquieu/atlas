# Atlas Decisions

## 2026-05-05 — Name

The project name is **Atlas**.

We intentionally dropped “Project” from the name. Atlas is the framework/product name, not just a temporary project label.

## 2026-05-05 — Architecture Shape

Atlas is standalone and provider-agnostic.

- OpenClaw is the first upstream provider adapter.
- Android phone is the first downstream device adapter.
- Atlas Core owns the physical session loop, context freshness, tool execution, and audit trail.
- Provider/device adapters should remain thin and swappable.

## 2026-05-05 — Phase 0 Implementation Locks

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

For the first live MVP harness, Atlas will support command-backed adapters selected from config:

- `@atlas/device-android/bridge-command`
- `@atlas/provider-openclaw/command`

The command seam is intentionally an edge integration path. It lets the CLI call local OpenClaw/Android wrappers without importing OpenClaw runtime/tool APIs into `@atlas/core`.

This is not the final daemon/runtime shape. It is a low-friction bridge for proving the loop, gathering latency/timing data, and keeping provider/device boundaries honest before building a persistent native runtime.
