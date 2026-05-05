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
