# Composable Workspace Alpha Test Plan

Status: live-device checklist for the first experimental implementation slice. This plan does not imply that every milestone in the full [Composable Workspaces](./composable-workspaces.md) specification has shipped.

## Implemented test surface

- Session / Workspace / System top-level navigation.
- Persistent workspace library with create, open, archive, restore, export, and delete-after-archive.
- One active composable workspace per session, tracked separately from the existing Realm ownership boundary.
- Explicit workspace ID, revision, definition, record count, and navigation sequence in inference context.
- Model tools to list, create, inspect, revise, and add records.
- Immutable validated definition revisions with optimistic base-revision checks.
- Native components for text, status, metric, notes, counters, checklists, forms, lists, galleries-as-record views, buttons, sections, dividers, and a persistent touch canvas.
- Anchored text/PTT interaction in the workspace view using the current Atlas session.
- Workspace records and definitions that survive session end and app restart.
- JSON workspace bundle export with capability grants and credentials excluded.
- Durable Atlas memory admitted beyond a single session at the existing personal Realm scope.

## Not yet in this slice

- Camera attachments and a real thumbnail gallery.
- Ambient-light capture and deterministic image-luminance transforms.
- PNG canvas export; canvas strokes currently export in the workspace JSON bundle.
- Revision history, visual diff, rollback, and draft-promotion UI.
- Workspace-specific capability grant UI.
- Background workspace Work runs and overnight iteration.
- Import.
- Automated schema migration between incompatible generated definitions.

## Install and migration smoke test

1. Install the new APK over the current Atlas alpha without clearing app data.
2. Launch Atlas and verify the existing latest session, providers, ChatGPT connection, usage history, and settings still appear.
3. Verify the bottom navigation is Session / Workspace / System.
4. Open and use the existing Session without creating a workspace.
5. Restart the app and confirm it launches without a database migration failure.

Pass: existing session behavior remains usable and no existing session/media/provider state is erased.

## Agent-created workspace

Start or resume a tool-capable session and say:

> Create a workspace called Bench Log. Give it a fixture status, a test counter, a checklist for power, connection, and tag detection, a form for serial number and notes, and a list showing saved tests.

Expected:

1. Atlas calls `workspace_create` with a complete `atlas.workspace.v1` definition.
2. The tool result contains Workspace, revision, and navigation-sequence IDs.
3. Open Workspace. `Bench Log` is visible and marked active for Atlas.
4. Counter, checklist, form, and list controls render natively.
5. Saving the form creates a record that appears in the list when both use the same collection.

## Conversational revision

While looking at Bench Log, say from the Workspace composer:

> Add a metric at the top for total tests and put the checklist inside a section called Preflight. Keep everything else.

Expected:

1. Atlas knows the currently displayed Workspace without being told its name.
2. Atlas inspects it if necessary and submits the current revision and navigation sequence.
3. The validator accepts the supported definition.
4. A new immutable revision becomes live.
5. The Workspace updates without an APK rebuild or app restart.
6. Existing records remain intact.

## Workspace switching and stale-result safety

1. Create a second Workspace named Scratch Counter.
2. Start a request that asks Atlas to revise Bench Log.
3. Before the response completes, open Scratch Counter.
4. Observe the eventual result.

Pass: a proposal tied to Bench Log cannot mutate Scratch Counter. A stale navigation sequence or base revision is rejected instead of silently rebased.

## Session boundary

1. End the active Session while Bench Log is displayed.
2. Use deterministic Workspace controls such as notes, counter, checklist, or form.
3. Confirm the composer clearly indicates that Atlas is stopped and does not accept an inference turn.
4. Start a new Session, reopen Bench Log, and ask, “What workspace am I looking at, and what can I do here?”

Pass: the Workspace and records survive; session-only transcript state is not treated as Workspace data; the new session receives current Workspace context.

## Canvas persistence

Ask Atlas to add a canvas component, or create a Workspace definition containing one.

1. Draw multiple strokes.
2. Tap Save drawing.
3. Navigate to another Workspace and return.
4. Restart Atlas and return again.
5. Export the Workspace bundle.

Pass: the saved drawing returns after navigation/restart and its strokes are present in the exported JSON. PNG export is intentionally not part of this slice.

## Archive, export, restore, and delete

1. Export an active Workspace through Android's document picker.
2. Inspect the JSON and verify it contains the definition and records but no provider credentials or capability grants.
3. Archive the Workspace.
4. Confirm it remains listed as archived but cannot become active for Atlas.
5. Restore it and confirm it is usable.
6. Archive it again and choose Delete.
7. Confirm the destructive dialog and delete it.

Pass: active workspaces cannot be deleted directly; archived deletion is explicit; other Workspaces and Session audit history remain intact.

## Failure probes

- Ask Atlas to add an unsupported component such as `execute_kotlin`; validation must reject it.
- Submit duplicate component IDs; validation must reject them.
- Ask Atlas to revise a Workspace using an old revision ID; optimistic concurrency must reject it.
- Revoke inference availability; deterministic Workspace controls must continue working.
- Force-stop Atlas after saving data; saved records must remain.
- Rotate the phone and change font scale; the Workspace must remain navigable and the composer reachable.

## Evidence to collect

For each failure or surprising behavior, export the Atlas session log and note:

- Workspace name and ID;
- live revision prefix;
- exact natural-language request;
- whether the request originated in Session or Workspace;
- visible Workspace before and after;
- whether data or definition changed;
- approximate time of the event.

Do not include ChatGPT OAuth tokens, API keys, or unrelated private Workspace record contents in a public issue.
