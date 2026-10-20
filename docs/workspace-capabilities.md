# Installed workspace capability catalog

**Historical design / legacy format reference:** this document retains earlier workspace contracts and checkpoints. New apps use the contained web application host. Start with [current status](./current-status.md) and the [application-host chronology](./workspace-application-host.md) for the current authoring surface.

`workspace_capabilities` is a read-only local diagnostic available without an active workspace, including during capability discovery. Its versioned response describes the installed runtime, format membership, supported V2 actions and expressions, and optional component properties and examples. Component membership and V2 property contracts use the validator's own tables.

Inspect both formats before declaring a capability missing. V1 includes a basic freehand canvas with explicit Save drawing and Clear controls; saved strokes persist locally. V2 supports show/hide sections through boolean state, a toggle action, and a visibility expression. These are compositions, not a native accordion. Workspace theme overrides remain unsupported. The catalog does not add renderer features or authorize external capabilities.

Discovery blockers and successful research receipts remain turn-scoped. Rejections retain failed status and now return an error code, current blocker IDs, and correction hints. Source rejections identify unrecorded URLs and a bounded list of recorded sources. `capability.control_rejected` and `workspace.capabilities_inspected` provide audit evidence. Corrected calls still pass the original provenance, cycle, depth, and authorization checks.

Regression coverage validates catalog examples, format-specific negatives, validator contract membership, read-only access during discovery, persisted V1 drawing records, cross-turn blocker recovery, and correction of source provenance errors.


## Catalog version 2 / Alpha 13

V2 `chart` renders native horizontal bars with a zero-inclusive automatic scale and exact value labels. Tap a bar to select its value; this ephemeral selection does not mutate records or execute actions. Use static `points:[{label,value}]` or an existing collection with `label_field` (string/enum) and `value_field` (integer/decimal). Collection charts share stable ordering with lists, support `sort_by`, `sort_direction`, and a limit of 1–50. Static points are capped at 50 and require finite numeric values. No arbitrary styling, line/scatter plots, zoom or pan is advertised. Render diagnostics include chart points and bounds, and normal workspace UI capture includes the native bars. Data interpretation and factual accuracy remain the author's responsibility.

Full V1 catalog responses now list properties consumed by each legacy renderer. This is explicitly a renderer contract; V1 validation remains permissive for old archives. Section examples use `children`.

Discovery `resume_local` requires a current-turn blocker and a reason. It enables only installed Core-approved local mutation adapters under their normal scope, risk and confirmation checks. It grants no external capability or authorization. `no_candidate` alone does not end the research-only phase; a new blocker restores it. Execution checks the transition again, including within batches.

Core validates clarification controls before changing pending state. Malformed or mixed controls return structured correction details, count toward existing tool budgets, and produce `clarification.control_rejected` audit events. Two repeated deterministic rejections hide that tool while its rejection policy remains unchanged; a corrected discovery transition can restore ordinary local tool availability. Transient executor failures do not trigger this policy. `tool.retry_suppressed` exposes this decision.
