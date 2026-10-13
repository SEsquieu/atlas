# Installed workspace capability catalog

`workspace_capabilities` is a read-only local diagnostic available without an active workspace, including during capability discovery. Its versioned response describes the installed runtime, format membership, supported V2 actions and expressions, and optional component properties and examples. Component membership and V2 property contracts use the validator's own tables.

Inspect both formats before declaring a capability missing. V1 includes a basic freehand canvas with explicit Save drawing and Clear controls; saved strokes persist locally. V2 supports show/hide sections through boolean state, a toggle action, and a visibility expression. These are compositions, not a native accordion. Workspace theme overrides remain unsupported. The catalog does not add renderer features or authorize external capabilities.

Discovery blockers and successful research receipts remain turn-scoped. Rejections retain failed status and now return an error code, current blocker IDs, and correction hints. Source rejections identify unrecorded URLs and a bounded list of recorded sources. `capability.control_rejected` and `workspace.capabilities_inspected` provide audit evidence. Corrected calls still pass the original provenance, cycle, depth, and authorization checks.

Regression coverage validates catalog examples, format-specific negatives, validator contract membership, read-only access during discovery, persisted V1 drawing records, cross-turn blocker recovery, and correction of source provenance errors.
