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
- Custom author-defined migration scripts between incompatible definitions. Core-owned safe reconciliation is automatic.

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
- Add a state field in a revision, then use it immediately; the initial value must be hydrated without clearing existing compatible state.
- Change a state field to an incompatible type; it must reset to the new initial value and report the reset during validation.
- Trigger divide-by-zero or a failed invariant; the workspace must show a controlled error and Atlas must remain running.
- Guard an action with `enabled_if`; the button and interpreter must both fail closed when the guard is false.
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

## Foreground BLE bridge (alpha.21)

Update an existing BLE package using the installed `workspace_package_contract`; declare `bluetooth.le`, exact operations, and `user_selected_device`. Preserve the schema version and saved records. Keep hardware actions behind explicit controls after `host.ready()`; preview/fixtures must skip device access. Use real bridge results rather than simulated device data.

On a phone with a known bench peripheral:

1. Open the workspace and scan. **Deny** the native scan request. Expect a visible `WORKSPACE_GRANT_REQUIRED` result, no scan, and no repeated prompt. Re-enable requests using the native access bar.
2. Scan again and allow native consent. If Android asks for Nearby devices, deny it. Expect `OS_PERMISSION_REQUIRED` and no scan. Re-enable requests and grant Android permission (use Android Settings if the OS no longer offers a prompt).
3. Scan with Bluetooth switched off; expect an explicit disabled state. Turn Bluetooth on and retry. Select a known device from real scan results. Confirm its name and requested operations in the separate native connection dialog.
4. Browse services/characteristics. Read a known readable value. On a controlled bench device, write a known benign command using at most 20 hex-encoded bytes and inspect its acknowledged result. Subscribe to a known notification source; confirm decoded values, timestamps, and dropped-event reporting. Physical effects must be observed independently.
5. Press native **Revoke** during a pending operation. Expect connection/scan teardown, cleared handles and grants, and a failed operation without automatic write retry. Repeated page requests must stay blocked until native re-enable.
6. Reconnect, then leave the workspace or background Atlas. On return, expect no live connection and fresh native consent. Kill/relaunch Atlas and repeat. Saved workspace records should remain intact.
7. With an active connection, ask Atlas to make a small visual edit and activate it. Expect the previous connection to close, old handles to fail, and the new revision to request fresh permission. Compare the actual screen with workspace capture and render diagnostics.
8. Switch to a second BLE workspace. Android's app-level permission may remain granted, but this workspace must obtain its own native approvals. Archive/delete the active workspace and verify that its connection ends.

Collect the session export after failures. Render diagnostics include BLE grant state, recent operation phases/times and Android GATT/scan error codes without dumping characteristic payloads. A timeout can leave a peripheral write outcome unknown; inspect the device before manually retrying. The automated SDK test uses an injected radio; passing emulator tests does not establish a successful physical BLE exchange.

## Alpha.22 cleanup acceptance

Before activating a new BLE revision, have Atlas run `workspace_package_ui_test` against its DOM controls. The test must cover the requested operations and display the simulated notification (`2b`); two pure filter fixtures alone cannot activate a device package. The test uses fake hardware and does not test Android consent or actual peripheral effects. After activation, inspect the live revision and capture visible output when available.

On the phone, approve scanning once and repeat scans without leaving the workspace. A failed BLE operation or an explicit disconnect must release handles without discarding foreground scan consent. Reconnection to a newly selected device still requires resource approval. Native revoke, backgrounding, panel disposal, revision/epoch change and OS permission loss remain real grant boundaries. Use the audit reason to distinguish those boundaries from an ordinary radio failure.

Ask Atlas to repair Bench Companion's notification collection, platform error display (`last_failure.phase/platform_code`) and stale selections using the installed contract. The SDK's `ble.watch()` manages notification draining; plain `subscribe()` needs an explicit event pump. Preserve saved records and distinguish simulated UI evidence from physical BLE acceptance.

Export after the task completes and after interacting with the app. Inspect `workspaces[].device_audit` in the session ZIP, or `device_audit` in the workspace export. Both include the newest 512 committed device records across sessions/revisions. `workspace_render_diagnostics` exposes the newest 32 for in-session debugging. Match timestamps, instance, revision and epoch; diagnostic history does not restore permissions or executable state on import.
