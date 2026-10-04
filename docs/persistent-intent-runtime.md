# Persistent Intent and Idle Runtime

Atlas owns durable unresolved intent independently of any model session. The Android v0.1 runtime stores intent records, state transitions, evaluation snapshots, daily budget usage, and lifetime activity counters in the existing `atlas.db` database. Models do not own scheduling, permissions, priority, persistence, or state transitions.

## Runtime flow

```text
device/user/runtime event
  -> durable intent candidate or environment update
  -> deterministic eligibility
  -> transparent pressure score
  -> autonomy-mode gate
  -> cheapest registered work handler
  -> bounded work contract
  -> validated Core state transition
  -> persisted evaluation and telemetry
```

Timers only request reevaluation. With no actionable intent, the decision is `NO_WORK` and no model is called. Productive results increase progress signals; repeated identical failure adds cooldown and eventually moves work to `WAITING_EVENT`.

## Autonomy modes

- `OFF`: no idle scheduler, promotion, work, or inference. Existing intent history is retained so changing modes never destroys data.
- `PERSIST_ONLY`: event-driven ledger maintenance and reactivation only. No autonomous work or inference.
- `DETERMINISTIC`: approved registered non-inference handlers may run. Model calls are rejected by the coordinator.
- `LOCAL`: deterministic handlers and local/private inference handlers are permitted. Remote endpoints are removed from ambient route candidates; there is no cloud fallback.
- `BUDGETED`: local or cloud inference is permitted only after a conservative daily budget reservation.

Interactive turns, Live Context heartbeat review, memory compaction, diagnostics, and persistent-intent cognition are separate inference domains. Every `InferenceRequest` must carry Core-assigned `InferenceProvenance`; there is no unclassified production request path. Intent autonomy modes govern only requests whose domain is `INTENT`. They do not suppress or reroute heartbeat perception, interactive responses, or ordinary memory behavior.

The current domains and purposes are:

| Domain | Purpose |
| --- | --- |
| `INTERACTIVE` | `USER_RESPONSE` |
| `PERCEPTION` | `HEARTBEAT_SCENE_REVIEW` |
| `MEMORY` | `MEMORY_COMPACTION` |
| `INTENT` | `INTENT_ASSESSMENT`, `INTENT_WORK_SLICE` |
| `DIAGNOSTIC` | `CONNECTION_TEST` |

Core assigns the domain before policy and routing. Models cannot relabel their work to escape intent-mode or budget enforcement. Provider events include the domain, purpose, trigger, intent/work-attempt identifiers where applicable, autonomy mode, and whether the request was user initiated. This allows routing scores, usage, and UI summaries to remain independent without duplicating provider infrastructure.

## Persistence

Database schema version 9 adds `intents`, `intent_transitions`, `idle_evaluations`, `idle_work_attempts`, `intent_quarantine`, and `idle_runtime_state`. Malformed intent rows are quarantined rather than crashing the interactive runtime. Evaluation records contain the environment, autonomy mode, ranked score components, decision, selected intent, reason, and next desired evaluation time. Work records include the complete bounded contract, result, mechanism, usage, and timing. Daily budget state uses a UTC date key; lifetime telemetry remains separate.

## Promotion and execution

Promotion is deliberately conservative in v0.1. Explicit commitment language such as “remember to,” “we should,” or “revisit” creates or updates a `PROMISE`. A tool failure becomes a durable `FAILURE` only after repeated evidence. Matching is type-sensitive and conservative. Curiosity promotion is disabled.

Autonomous behavior is implemented through `IdleWorkHandler`. Each handler declares its action class, inference location, required authorities, applicability, and bounded execution contract. Core filters handlers before execution. The initial deterministic handler only performs an explicitly named intent-metadata maintenance action. New behavior should be added as another narrow handler, not as a generic recurring prompt.

## Preemption and inspection

PTT, text input, manual capture, tool approval, cancellation, and speech interruption preempt an active idle slice. Mode changes cancel work that the new mode may not permit. Partial work is returned to an eligible state with a truthful persisted summary.

The Android **System > Intent** panel exposes mode, pressure, cadence, decision reason, activity telemetry, top ranked intents, and developer actions to pause, reevaluate, mark dormant, reactivate, or abandon work.

## Conservative limits

- Android background wake timing is best effort; the foreground runtime honors `nextEvaluationAtMs`, but exact alarm delivery is not promised.
- Cloud provider price metadata is not standardized. A cloud intent-work call reserves and accounts the configured daily intent-cloud allowance. This deliberately conservative policy prevents overspend.
- Semantic deduplication beyond normalized token overlap is deferred until runtime traces justify it.
