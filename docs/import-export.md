# Android session and workspace recovery

Open **Session → Import session** for an Atlas session ZIP (containing `session.json`) or raw session JSON. Open **Workspace → workspace library → Import workspace** for an `.atlas-workspace.json` file. The picker accepts files from Drive or local storage. Review the name, record/message count and recovery warnings before importing.

An import creates a separate copy with fresh local IDs. Importing the same source snapshot again opens the existing imported copy. A changed snapshot creates another copy. Local sessions and workspaces are not overwritten. All restoration writes, including embedded workspaces, commit together or roll back together.

New session exports include the transcript, turns, terminal tool history, memory, checkpoint, audit metadata and related live workspace bundles. Workspace exports include the live definition and all stored collection records, including saved runtime state. Original files from older builds are accepted. Those workspace exports may have capped records at 200; an importer cannot recover records that were never exported. Legacy sessions can reconnect to their separately imported active workspace in either import order.

## Resuming a session

Imported sessions open **paused** in manual context mode with default permissions. Resume deliberately when ready. Pending turns become interrupted, unresolved tools become unknown, and speech/clarification controls never resume. Provider continuations and historical tool-message protocol are cleared. Old events are labeled `archive.*`; their data and tool outcomes are historical evidence, not proof of a new effect.

Memory retains its confidence, status and original expiry. All imported memory is restricted to the recovered session, including formerly durable facts. Observation evidence is kept in the exported audit, not attached as a current sensor reading. Other sessions cannot use imported session facts automatically.

A valid checkpoint is restored only if its exact message cutoff exists and its text is at most 8,000 characters. An invalid checkpoint is discarded with an explicit notice and the full transcript remains. Model requests identify omitted history and clipped checkpoints. An oversized current turn fails with a context-budget reason instead of sending an unbounded inference packet. Saved transcript and memory remain available for export. Checkpoint updates include complete turns only and advance only through messages supplied to the summarizer. Empty, oversized and incomplete provider responses retain the previous checkpoint and emit a `memory.compaction_failed` event that is also supplied to Atlas on its next request.

## Boundaries

Exports do not include images/audio bytes, credentials, capability grants or revision history. Old observation and speech metadata survives in the exported historical audit. This is state recovery, not an execution replay or a byte-for-byte device backup.

Imports accept at most 32 MiB of raw or decompressed archive data and 30,000 rows per session table. ZIP files are read in memory with entry-count, duplicate-name and traversal checks and are never extracted to disk. Workspaces export at most 5,000 stored records. Exports exceeding these limits fail explicitly; they never silently create a partial bundle.

Android database recovery tests cover transactions, ID and checkpoint remapping, paused recovery, scoped memory, duplicate imports, legacy relinking, record counts beyond 200 and repeated recovery cycles. Codec/context unit tests cover corrupt references, unsafe ZIP paths, malformed checkpoints, omitted history and context-budget failures.

APK upgrades still require the same signing certificate as the installed app and a higher versionCode. Import support does not bypass Android signing rules. Preserve your existing exports before any reinstall; configure the persistent signing secrets described in `docs/build-and-test.md` for future CI builds.
