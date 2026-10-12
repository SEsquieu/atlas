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
