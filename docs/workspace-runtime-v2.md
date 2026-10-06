# Workspace Runtime V2

Status: alpha vertical slice, October 2026

Workspace Runtime V2 is a deterministic application language interpreted by Atlas Core. It expands composable workspaces beyond static dashboards while keeping generated definitions non-executable and subordinate to the ToolHarness.

## What is testable now

- Typed scalar state: string, integer, decimal, boolean, timestamp, and enum.
- Typed collection declarations and durable record insertion.
- Multiple views with deterministic navigation.
- Pure expressions over state and collection counts.
- Bounded actions: set, increment, toggle, insert, navigate, sequence, branch, and stop.
- Reactive text, metric, status, button, toggle, progress, list, section, and divider rendering.
- Responsive row and bounded grid layouts, plus compact sections for phone-first interfaces.
- Guarded actions with user-readable disabled reasons and root-level post-action invariants.
- Embedded deterministic tests and a model-facing validate/simulate tool.
- Immutable definition revisions and persisted runtime state reconciled against each new schema.
- Existing `atlas.workspace.v1` definitions remain valid and render through the V1 path.

This alpha intentionally does not yet expose device capabilities, timers, durable background tasks, resources, arbitrary record update/delete, custom migration scripts, or draft promotion. Those require their own Core-owned adapters and policy surfaces. Definitions cannot execute Kotlin, JavaScript, Python, shell commands, raw networking, or Android APIs.

## Definition shape

```json
{
  "format": "atlas.workspace.v2",
  "title": "Example",
  "entry_view": "home",
  "state": {
    "count": { "type": "integer", "initial": 0 }
  },
  "collections": {
    "history": {
      "fields": { "label": { "type": "string" } }
    }
  },
  "actions": {
    "advance": {
      "enabled_if": { "op": "lt", "args": [{ "var": "state.count" }, 10] },
      "disabled_message": "The counter is already full.",
      "steps": [
        { "type": "increment", "key": "count", "by": 1 },
        { "type": "insert", "collection": "history", "data": { "label": "Advanced" } }
      ]
    }
  },
  "views": [
    {
      "id": "home",
      "title": "Example",
      "components": [
        {
          "id": "controls", "type": "row", "children": [
            { "id": "count", "type": "metric", "label": "Count", "value": { "var": "state.count" } },
            { "id": "advance", "type": "button", "label": "Advance", "action": "advance" }
          ]
        }
      ]
    }
  ],
  "tests": [
    {
      "name": "advance increments",
      "initial_state": { "count": 0 },
      "actions": ["advance"],
      "assert": { "op": "eq", "args": [{ "var": "state.count" }, 1] }
    }
  ]
}
```

## Safety and execution constraints

| Boundary | Alpha limit |
| --- | ---: |
| Definition | 256 KiB |
| Views | 32 |
| Components | 500 |
| Action steps | 64 |
| Action nesting | 8 |
| Expression depth | 16 |
| Embedded tests | 64 |
| Records per collection | 5,000 |

Validation rejects unknown components, action steps, state references, collection references, and expression operators. An action executes against a copied state snapshot. Its local mutations and resulting state snapshot are then persisted in one SQLite transaction. Native capabilities never execute inside the interpreter.

Persisted state is reconciled whenever a definition is loaded or replaced. Compatible values survive, newly declared keys receive their schema initial value, incompatible values reset, removed keys are dropped, and invalid view selections return to the entry view. Expression failures, failed guards, divide-by-zero, and invariant failures are surfaced inside the Workspace UI instead of escaping through the Android click handler.

## Model authoring contract

Atlas receives `workspace_validate_definition` in addition to the existing create, inspect, replace, and record tools. The intended loop is:

1. Resolve material ambiguity in the requested application.
2. Build a complete V2 definition with at least one behavioral test.
3. Call `workspace_validate_definition` with the active `workspace_id` when revising so tests also exercise migrated durable state.
4. Repair all schema or simulation failures.
5. Create or replace the workspace only when `ready_to_apply` is true.
6. Describe only behavior that the definition and tests actually support.

Normal inference context receives a compact workspace outline rather than the complete definition. Use `workspace_inspect` in compact mode for diagnosis and request `mode=full` only immediately before editing. Record bodies are opt-in and bounded. This keeps routine turns fast and avoids repeatedly sending historical runtime snapshots to the model.

The validator does not grant authority. Future camera, location, Bluetooth, search, export, or notification steps must still be normalized into ToolHarness proposals and pass Atlas policy, Android permission, confirmation, and audit controls.

## Next implementation gates

1. Typed input editing and form submission.
2. Record query/update/delete/upsert with schema enforcement and recoverable deletion.
3. Resource references for images, drawings, audio, documents, and exports.
4. Core capability request steps routed through ToolHarness.
5. Durable tasks, timers, checkpointing, cancellation, and visible background status.
6. Draft revisions, custom migration plans, atomic promotion, and rollback UI.
7. Richer simulation with event traces, unreachable-view detection, and cycle detection.
