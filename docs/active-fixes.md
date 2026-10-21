# Active fixes

Canonical repository work queue. Evidence: [alpha.28 assessment](./alpha28-live-assessment.md). Updated October 19, 2026. Implementation work is tracked below; phone verification remains separate.

Stable IDs never change or get reused. Status: **proposed** needs design; **ready** is bounded enough to implement; **in progress** links a claim/draft PR; **blocked** names a dependency; **implemented** links merged code but has outstanding acceptance; **verified** links all required evidence. Merged code and shipped builds are separate milestones. Keep verified entries with their resolution for traceability.

Each item has one tracking issue when created. Record that issue and draft PR here; issues hold discussion, this file holds scope/status. Newly discovered defects get separate issues and maintainer-assigned IDs rather than silently expanding a PR. Maintainers update statuses; contributors propose changes in their PR. No autonomous claiming, scheduling, or publication is enabled by this file.

| ID | Outcome | Status | Tracking issue / claim |
| --- | --- | --- | --- |
| AF-001 | Reduce malformed package staging | implemented | [#38](https://github.com/SEsquieu/atlas/issues/38); [PR #35](https://github.com/SEsquieu/atlas/pull/35) |
| AF-002 | Complete delivery despite unavailable optional inspection | implemented | [#39](https://github.com/SEsquieu/atlas/issues/39); [PR #35](https://github.com/SEsquieu/atlas/pull/35) |
| AF-003 | Bound identical deterministic retries across slices | implemented | [#40](https://github.com/SEsquieu/atlas/issues/40); [PR #35](https://github.com/SEsquieu/atlas/pull/35) |
| AF-004 | Separate camera evidence from workspace viewport evidence | implemented | [#41](https://github.com/SEsquieu/atlas/issues/41); [PR #35](https://github.com/SEsquieu/atlas/pull/35) |
| AF-005 | Make acceptance assertions prove requested behavior | implemented | [#42](https://github.com/SEsquieu/atlas/issues/42); [PR #37](https://github.com/SEsquieu/atlas/pull/37) |
| AF-006 | Accept unverified behavior for an exact candidate | implemented | [#43](https://github.com/SEsquieu/atlas/issues/43); [PR #36](https://github.com/SEsquieu/atlas/pull/36) |
| AF-007 | Support pointer dragging and classify test limitations | implemented | [#44](https://github.com/SEsquieu/atlas/issues/44); [PR #37](https://github.com/SEsquieu/atlas/pull/37) |

| AF-008 | Cancel superseded validation without interrupting manual delivery | proposed | [#46](https://github.com/SEsquieu/atlas/issues/46) |
| AF-009 | Bound hung UI checks and recover workspace rendering | in progress | [#48](https://github.com/SEsquieu/atlas/issues/48) |

## AF-001: Reduce malformed package staging

- Status: implemented. Related work: AF-003 (coordination, not a hard dependency).
- Evidence: Lunchbox Lottery made ten staging attempts; seven returned malformed argument results.
- Starting points: WorkspacePackageTools.kt; WorkspacePackageRepository.kt under the Android application source tree; inspect current code before editing.
- Acceptance: A malformed file placement yields a bounded actionable correction without whole-app regeneration; retained source remains reusable; regression reproduces the observed top-level file errors.
- Constraints / validation: Preserve exact target, base revision, package validation and immutable source. Measure error count, payload and token use in a fresh live run.
- Resolution: Merged in alpha.29 source via PRs #35–#37; automated evidence and remaining scope are in the alpha.29 release notes. Phone verification remains outstanding. Live evidence: outstanding.

## AF-002: Complete delivery despite unavailable optional inspection

- Status: implemented. Related work: AF-006 (coordination, not a hard dependency).
- Evidence: An activated, storage-verified app stayed WAITING after rejected pixel handoffs.
- Starting points: ExecutionTasks.kt; WorkspaceLiveTools.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Proven delivery completes with optional inspection explicitly unverified; rejected unnecessary handoffs do not become permanent blockers; required checks and unknown effects remain blocking.
- Constraints / validation: Replay successful activation followed by unavailable capture and invalid handoff; preserve consent and receipt evidence.
- Resolution: Merged in alpha.29 source via PRs #35–#37; automated evidence and remaining scope are in the alpha.29 release notes. Phone verification remains outstanding. Live evidence: outstanding.

## AF-003: Bound identical deterministic retries across slices

- Status: implemented. Related work: None (coordination, not a hard dependency).
- Evidence: Six identical invalid handoff calls and repeated staging errors consumed inference.
- Starting points: ToolRetryPolicy.kt; ExecutionTasks.kt; ExecutionWorkState.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Equivalent deterministic failures cannot reset their retry allowance with a new slice or operation ID; corrected arguments can proceed; transient failures retain their separate handling.
- Constraints / validation: Test across slice/restart boundaries; measure useful progress, repeated calls and tokens. Do not confuse fresh IDs with progress.
- Resolution: Merged in alpha.29 source via PRs #35–#37; automated evidence and remaining scope are in the alpha.29 release notes. Phone verification remains outstanding. Live evidence: outstanding.

## AF-004: Separate camera evidence from workspace viewport evidence

- Status: implemented. Related work: None (coordination, not a hard dependency).
- Evidence: Physical camera captures were used to investigate workspace appearance.
- Starting points: ContextAssembler.kt; AndroidContextTools.kt; WorkspaceRenderSurface.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Tool descriptions and context explicitly distinguish camera versus workspace capture; unavailable viewport cannot lead to claims inferred from camera pixels.
- Constraints / validation: Test evidence provenance and inspect open-workspace capture mismatch without assuming its cause.
- Resolution: Merged in alpha.29 source via PRs #35–#37; automated evidence and remaining scope are in the alpha.29 release notes. Phone verification remains outstanding. Live evidence: outstanding.

## AF-005: Make acceptance assertions prove requested behavior

- Status: implemented. Related work: None (coordination, not a hard dependency).
- Evidence: Example names passed checks although individual examples were not labeled; circle count was proposed as drag evidence.
- Starting points: WorkspaceUiSimulation.kt; ExecutionTasks.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Tests distinguish presence from labeling and static geometry from interaction; unproven semantics remain explicit rather than claimed passed.
- Constraints / validation: Define a bounded implementable approach before marking ready; preserve candidate-specific evidence. Include filter boundary and selected directions cases.
- Resolution: Merged in alpha.29 source via PRs #35–#37; automated evidence and remaining scope are in the alpha.29 release notes. Phone verification remains outstanding. Live evidence: outstanding.

## AF-006: Accept unverified behavior for an exact candidate

- Status: implemented. Related work: None (coordination, not a hard dependency).
- Evidence: User requested activation for manual testing, but pixel handoff could not clear behavioral acceptance.
- Starting points: ExecutionTasks.kt; WorkspaceLiveTools.kt; WorkspaceApplicationActivation.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Explicit user acceptance records candidate/hash, criterion IDs, source message and remaining uncertainty; activation can use accepted-unverified criteria without recording a test pass; changed bytes invalidate acceptance.
- Constraints / validation: Design conversational and native confirmation path first. Preserve permission, schema, revision, migration, startup and unknown-effect gates. Test stale candidate, restart, replay and fabricated approval.
- Resolution: Merged in alpha.29 source via PRs #35–#37; automated evidence and remaining scope are in the alpha.29 release notes. Phone verification remains outstanding. Live evidence: outstanding.

## AF-007: Support pointer dragging and classify test limitations

- Status: implemented. Related work: None (coordination, not a hard dependency).
- Evidence: UI click failed on an SVG circle; drag behavior was never exercised.
- Starting points: WorkspaceUiSimulation.kt; WorkspaceDeviceTest.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Realistic pointer sequence verifies coordinate change; unsupported action, failed assertion and application error are distinct; pointer cancellation and touch coordinates are covered.
- Constraints / validation: Choose event and coordinate contract before implementation; use API 35/36 instrumented tests. Synthetic events do not prove every physical touch behavior.
- Resolution: Merged in alpha.29 source via PRs #35–#37; automated evidence and remaining scope are in the alpha.29 release notes. Phone verification remains outstanding. Live evidence: outstanding.

## AF-008: Cancel superseded validation without interrupting manual delivery

- Status: proposed. Tracking issue: #46.
- Evidence: push and pull_request duplicate checks; device concurrency retains obsolete runs and queues newer qualification.
- Acceptance: updates cancel/deduplicate stale validation, latest heads still receive required checks, and manual signed builds retain their exact source and are never cancelled by PR updates.
- Constraints: preserve fork read-only validation and signing-secret isolation. Workflow scheduling only, separate from runtime behavior.
- Resolution: none; proposed for the next workflow pass.

AF-005 implements stronger assertion tools and explicit evidence, not an independent semantic oracle. AF-004 clarifies evidence provenance; the reported open-workspace capture mismatch still needs live reproduction. AF-007 uses synthetic DOM input with simulated capture; native hit testing and physical touch require phone verification. All seven implementation issues remain open for their outstanding acceptance evidence.

## AF-009: Bound hung UI checks and recover workspace rendering

- Status: in progress; not included in alpha.29.
- Evidence: Maze generation UI could leave its exit unreachable and loop forever tracing the route. A separate pure fixture passed; it did not execute the UI algorithm. Repeated UI calls lacked terminal results. Other live-workspace failures are user reported; shared renderer involvement remains to be confirmed on the phone.
- Scope: per-evaluation and total deadlines, active-step failure receipts, renderer interruption before disposal, content-hash suppression after execution timeout, and terminal cancellation bookkeeping without overwriting committed receipts.
- Validation: device regression exercises a synchronous infinite-loop click, suppression despite changed test steps, corrected source and reopening an existing healthy application's surface. CI pending; local Gradle distribution download is unavailable. No physical-device recovery or semantic correctness claim.
- Constraint: WebView renderers may be shared; termination can invalidate other surfaces. Their stored packages and records must be preserved. This is recovery, not process isolation.
