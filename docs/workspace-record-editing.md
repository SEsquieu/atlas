# Editing collection records

V2 workspaces support updates to existing records from conversation and interactive lists.
Database record IDs remain separate from collection data. Inspection returns `{id,collection,data}`;
the interpreter receives reserved `_id` metadata, which cannot be declared or patched as a user field.

Action steps use `type`, and state bindings use bare keys:

```json
{"type":"toggle_record","collection":"entries","record_id":{"var":"row.id"},"field":"done"}
```

An interactive list can bind that action:

```json
{"id":"entries","type":"list","collection":"entries","primary_field":"name","checked_field":"done","row_action":"toggleEntry"}
```

`checked_field` must be a boolean collection field. Without it, `row_action` renders a small text
button with optional `row_action_label`. Existing display-only lists remain valid. Row actions
receive `row.id` and `row.<field>` expressions, including in action guards. Row expressions fail
clearly when no row is selected. A row named `id` in user data does not replace the stable `row.id`.

`update_record` takes `collection`, `record_id`, and a nonempty `data` patch; `toggle_record` takes
`collection`, `record_id`, and a boolean `field`. Both target exactly one existing record. Unspecified
fields remain intact. Invalid IDs, unknown fields, wrong types, oversized records, failed guards,
and failed invariants reject the entire action before any state or record writes.

For conversational editing, inspect the desired records, then call `workspace_update_record` with
the workspace ID, base revision ID, navigation sequence, collection, record ID, full `expected_data`,
and a `data` patch. The tool rechecks active context at execution. Stale revisions or changed record
values require inspection again. Setting a field to a desired value avoids replayed toggle semantics.
Conversational insertion continues to use `workspace_add_record`, now with V2 schema and count checks.

The UI checks its rendered record values and definition before applying a row action, surfaces
rejection reasons, and refreshes after success. Both paths use the same interpreter and transactional
persistence. `workspace.action_applied` events identify the source, action, and affected record IDs
in sessions with that workspace open; model calls also retain normal tool proposal/result logs.

Embedded tests can supply `initial_records` as a map of collections to arrays of valid records with
unique `_id` metadata, plus `row_collection` and `row_id`. Action sequences update the fixture records
before subsequent actions and assertions. No existing workspace is rewritten automatically: Atlas
can patch a list and add its row action after inspecting the installed build's authoring guide.

Regression coverage includes two identically named records, chat edits followed by row toggles,
database reopen, stale replay/context/revisions, invalid schemas, and rollback after a later failure.
Discovery `no_candidate` reports with source URLs now require the same recorded same-turn research
provenance as candidates; a source-free failure report remains valid.

## Alpha.10 reliability and UI evidence

V2 user records now load by creation time ascending, with stable record-ID tie-breaking. Runtime-state
snapshots continue to use most recent update order. List `sort_by` names a schema field;
`sort_direction` is `ascending` or `descending`. Sorting is numeric for numbers, boolean for booleans,
and case-insensitive for strings. Equal values retain creation order; missing values remain last.
Sort applies to all loaded records (up to the existing 5,000 per-collection bound) before the display
limit (1–200, default 50). Unsupported component properties and invalid sort fields are rejected.

Revision validation includes `record_migration` alongside state migration. Actual saved collection
records are checked; missing fields can receive valid explicit `initial` defaults. Backfill preserves
IDs, creation times, existing values and ordering. No implicit type coercion or destructive field
removal occurs. Incompatible records block the revision, identifying the record and collection.
Revision and backfill commit together. Migration is bounded to 30,000 records/32 MiB and reports
checked/backfilled counts plus at most 50 changed-record details.

`remove_record(collection,record_id)` removes one existing record through the same interpreter and
invariant checks as updates. Lists support `remove_action` and optional `remove_label`. The UI offers
Undo, and conversational `workspace_delete_record` / `workspace_restore_record` require current
workspace, revision and navigation context. Deletion also checks full `expected_data`. The last 50
deletions per workspace remain undoable across restarts. Restore is one-shot, validates the current
schema/invariants/count limit, and preserves the original ID and creation order. Failed restoration
retains the token. Tombstones are private local recovery state, excluded from workspace/session
exports; imported workspaces contain only live records. Deleting the workspace deletes its undo data.

`workspace_render_diagnostics` reports the current expected projection, schema and migration errors,
recent UI failures, and (when composed) the last actual drawn viewport snapshot. The snapshot identifies
the revision, draw time, visible component IDs, logical list order and error. Logical list order can
include rows outside the clipped viewport; it is not a claim that all those pixels are visible.
Renderer and diagnostics share `WorkspaceRecordProjection`. Model tool results invalidate cached UI
records, including after edits when the workspace stays open.

`workspace_capture_view` captures the actual visible V2 workspace graphics layer using Compose's
`rememberGraphicsLayer`, `record`, and `toImageBitmap` APIs. It excludes the session composer, system
screen, other apps, and offscreen content. The panel must be open, foreground, and drawn at the current
revision. Captures reject changed/disposed surfaces, stale context and oversized viewports; encoding
uses a 1600-pixel/750,000-byte image budget. UI images use `WORKSPACE_UI`, attach to the next model step, and
are stored/audited separately in meaning from physical camera observations. Physical freshness,
heartbeat and latest-camera queries never reuse UI screenshots.

The screenshot follows the session's observation policy, without requiring Android camera or system
screen-capture permissions. Installed scoped local diagnostics can run during read-only capability
discovery; workspace writes remain blocked once discovery starts in that turn. This does not authorize
external access, installation, permission grants or newly discovered capabilities.

Events include `workspace.records_migrated`, `workspace.rendered`, `workspace.render_failed`,
`workspace.action_rejected`, `workspace.capture_completed`, and normal tool results. Schema/test
success alone does not claim the screen is visually correct. Renderer regressions exercise toggling,
removal, undo and nonblank viewport capture; persistence tests exercise stable ordering and migration
against real SQLite records.
