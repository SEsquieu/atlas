# Active fixes

Canonical repository work queue. Evidence: [alpha.28 assessment](./alpha28-live-assessment.md). Updated October 19, 2026. No item below is implemented by this workflow change.

Stable IDs never change or get reused. Status: **proposed** needs design; **ready** is bounded enough to implement; **in progress** links a claim/draft PR; **blocked** names a dependency; **implemented** links merged code but has outstanding acceptance; **verified** links all required evidence. Merged code and shipped builds are separate milestones. Keep verified entries with their resolution for traceability.

Each item has one tracking issue when created. Record that issue and draft PR here; issues hold discussion, this file holds scope/status. Newly discovered defects get separate issues and maintainer-assigned IDs rather than silently expanding a PR. Maintainers update statuses; contributors propose changes in their PR. No autonomous claiming, scheduling, or publication is enabled by this file.

| ID | Outcome | Status | Tracking issue / claim |
| --- | --- | --- | --- |
| AF-001 | Reduce malformed package staging | ready | Unassigned; no issue yet |
| AF-002 | Complete delivery despite unavailable optional inspection | ready | Unassigned; no issue yet |
| AF-003 | Bound identical deterministic retries across slices | ready | Unassigned; no issue yet |
| AF-004 | Separate camera evidence from workspace viewport evidence | ready | Unassigned; no issue yet |
| AF-005 | Make acceptance assertions prove requested behavior | proposed | Unassigned; no issue yet |
| AF-006 | Accept unverified behavior for an exact candidate | proposed | Unassigned; no issue yet |
| AF-007 | Support pointer dragging and classify test limitations | proposed | Unassigned; no issue yet |

## AF-001: Reduce malformed package staging

- Status: ready. Related work: AF-003 (coordination, not a hard dependency).
- Evidence: Lunchbox Lottery made ten staging attempts; seven returned malformed argument results.
- Starting points: WorkspacePackageTools.kt; WorkspacePackageRepository.kt under the Android application source tree; inspect current code before editing.
- Acceptance: A malformed file placement yields a bounded actionable correction without whole-app regeneration; retained source remains reusable; regression reproduces the observed top-level file errors.
- Constraints / validation: Preserve exact target, base revision, package validation and immutable source. Measure error count, payload and token use in a fresh live run.
- Resolution: none. Live evidence: outstanding.

## AF-002: Complete delivery despite unavailable optional inspection

- Status: ready. Related work: AF-006 (coordination, not a hard dependency).
- Evidence: An activated, storage-verified app stayed WAITING after rejected pixel handoffs.
- Starting points: ExecutionTasks.kt; WorkspaceLiveTools.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Proven delivery completes with optional inspection explicitly unverified; rejected unnecessary handoffs do not become permanent blockers; required checks and unknown effects remain blocking.
- Constraints / validation: Replay successful activation followed by unavailable capture and invalid handoff; preserve consent and receipt evidence.
- Resolution: none. Live evidence: outstanding.

## AF-003: Bound identical deterministic retries across slices

- Status: ready. Related work: None (coordination, not a hard dependency).
- Evidence: Six identical invalid handoff calls and repeated staging errors consumed inference.
- Starting points: ToolRetryPolicy.kt; ExecutionTasks.kt; ExecutionWorkState.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Equivalent deterministic failures cannot reset their retry allowance with a new slice or operation ID; corrected arguments can proceed; transient failures retain their separate handling.
- Constraints / validation: Test across slice/restart boundaries; measure useful progress, repeated calls and tokens. Do not confuse fresh IDs with progress.
- Resolution: none. Live evidence: outstanding.

## AF-004: Separate camera evidence from workspace viewport evidence

- Status: ready. Related work: None (coordination, not a hard dependency).
- Evidence: Physical camera captures were used to investigate workspace appearance.
- Starting points: ContextAssembler.kt; AndroidContextTools.kt; WorkspaceRenderSurface.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Tool descriptions and context explicitly distinguish camera versus workspace capture; unavailable viewport cannot lead to claims inferred from camera pixels.
- Constraints / validation: Test evidence provenance and inspect open-workspace capture mismatch without assuming its cause.
- Resolution: none. Live evidence: outstanding.

## AF-005: Make acceptance assertions prove requested behavior

- Status: proposed. Related work: None (coordination, not a hard dependency).
- Evidence: Example names passed checks although individual examples were not labeled; circle count was proposed as drag evidence.
- Starting points: WorkspaceUiSimulation.kt; ExecutionTasks.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Tests distinguish presence from labeling and static geometry from interaction; unproven semantics remain explicit rather than claimed passed.
- Constraints / validation: Define a bounded implementable approach before marking ready; preserve candidate-specific evidence. Include filter boundary and selected directions cases.
- Resolution: none. Live evidence: outstanding.

## AF-006: Accept unverified behavior for an exact candidate

- Status: proposed. Related work: None (coordination, not a hard dependency).
- Evidence: User requested activation for manual testing, but pixel handoff could not clear behavioral acceptance.
- Starting points: ExecutionTasks.kt; WorkspaceLiveTools.kt; WorkspaceApplicationActivation.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Explicit user acceptance records candidate/hash, criterion IDs, source message and remaining uncertainty; activation can use accepted-unverified criteria without recording a test pass; changed bytes invalidate acceptance.
- Constraints / validation: Design conversational and native confirmation path first. Preserve permission, schema, revision, migration, startup and unknown-effect gates. Test stale candidate, restart, replay and fabricated approval.
- Resolution: none. Live evidence: outstanding.

## AF-007: Support pointer dragging and classify test limitations

- Status: proposed. Related work: None (coordination, not a hard dependency).
- Evidence: UI click failed on an SVG circle; drag behavior was never exercised.
- Starting points: WorkspaceUiSimulation.kt; WorkspaceDeviceTest.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Realistic pointer sequence verifies coordinate change; unsupported action, failed assertion and application error are distinct; pointer cancellation and touch coordinates are covered.
- Constraints / validation: Choose event and coordinate contract before implementation; use API 35/36 instrumented tests. Synthetic events do not prove every physical touch behavior.
- Resolution: none. Live evidence: outstanding.
