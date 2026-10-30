# Active fixes

Canonical repository work queue. Evidence: [alpha.28 assessment](./alpha28-live-assessment.md). Updated October 30, 2026. Implementation work is tracked below; phone verification remains separate.

Stable IDs never change or get reused. Status: **proposed** needs design; **ready** is bounded enough to implement; **in progress** links a claim/draft PR; **blocked** names a dependency; **implemented** links merged code but has outstanding acceptance; **verified** links all required evidence. Merged code and shipped builds are separate milestones. Keep verified entries with their resolution for traceability.

Each item has one tracking issue when created. Record that issue and draft PR here; issues hold discussion, this file holds scope/status. Newly discovered defects get separate issues and maintainer-assigned IDs rather than silently expanding a PR. Maintainers update statuses; contributors propose changes in their PR. No autonomous claiming, scheduling, or publication is enabled by this file.

| ID | Outcome | Status | Tracking issue / claim |
| --- | --- | --- | --- |
| AF-001 | Reduce malformed package staging | implemented | [#38](https://github.com/SEsquieu/atlas/issues/38); [PR #35](https://github.com/SEsquieu/atlas/pull/35) |
| AF-002 | Complete delivery despite unavailable optional inspection | verified | [#39](https://github.com/SEsquieu/atlas/issues/39); [PR #35](https://github.com/SEsquieu/atlas/pull/35) |
| AF-003 | Bound identical deterministic retries across slices | verified | [#40](https://github.com/SEsquieu/atlas/issues/40); [PR #35](https://github.com/SEsquieu/atlas/pull/35) |
| AF-004 | Separate camera evidence from workspace viewport evidence | implemented | [#41](https://github.com/SEsquieu/atlas/issues/41); [PR #35](https://github.com/SEsquieu/atlas/pull/35) |
| AF-005 | Make acceptance assertions prove requested behavior | implemented | [#42](https://github.com/SEsquieu/atlas/issues/42); [PR #37](https://github.com/SEsquieu/atlas/pull/37) |
| AF-006 | Accept unverified behavior for an exact candidate | implemented | [#43](https://github.com/SEsquieu/atlas/issues/43); [PR #36](https://github.com/SEsquieu/atlas/pull/36) |
| AF-007 | Support pointer dragging and classify test limitations | implemented | [#44](https://github.com/SEsquieu/atlas/issues/44); [PR #37](https://github.com/SEsquieu/atlas/pull/37) |
| AF-008 | Cancel superseded validation without interrupting manual delivery | verified | [#46](https://github.com/SEsquieu/atlas/issues/46); [PR #56](https://github.com/SEsquieu/atlas/pull/56) |
| AF-009 | Bound hung UI checks and recover workspace rendering | verified | [#48](https://github.com/SEsquieu/atlas/issues/48); [PR #49](https://github.com/SEsquieu/atlas/pull/49) |
| AF-010 | Keep blocker replies available and retire approval turns | implemented; phone verification pending | [#50](https://github.com/SEsquieu/atlas/issues/50); [PR #54](https://github.com/SEsquieu/atlas/pull/54) |
| AF-011 | Resolve obsolete acceptance failures after matching delivery | implemented; phone verification pending | [#51](https://github.com/SEsquieu/atlas/issues/51); [PR #54](https://github.com/SEsquieu/atlas/pull/54) |
| AF-012 | Return explicit expected and actual assertion counts | implemented; phone verification pending | [#52](https://github.com/SEsquieu/atlas/issues/52); [PR #54](https://github.com/SEsquieu/atlas/pull/54) |
| AF-013 | Audit and reduce model-boundary schema and identity churn | implemented; phone benchmark pending | [#53](https://github.com/SEsquieu/atlas/issues/53); [PR #55](https://github.com/SEsquieu/atlas/pull/55), [PR #58](https://github.com/SEsquieu/atlas/pull/58) |
| AF-014 | Reduce untested candidate rewrites with exact source edits | implemented; phone benchmark pending | [#60](https://github.com/SEsquieu/atlas/issues/60); [PR #63](https://github.com/SEsquieu/atlas/pull/63) |
| AF-015 | Include matching non-BLE UI activation evidence | implemented; phone verification pending | [#61](https://github.com/SEsquieu/atlas/issues/61); [PR #63](https://github.com/SEsquieu/atlas/pull/63) |
| AF-016 | Distinguish hosted research from missing local adapters | implemented; phone verification pending | [#62](https://github.com/SEsquieu/atlas/issues/62); [PR #63](https://github.com/SEsquieu/atlas/pull/63) |

AF-017 tracks the alpha.33 revision/approval regressions below: implemented and delivered in alpha.34, with phone verification pending ([#68](https://github.com/SEsquieu/atlas/issues/68), [PR #69](https://github.com/SEsquieu/atlas/pull/69)).

## AF-001: Reduce malformed package staging

- Status: implemented. Related work: AF-003 (coordination, not a hard dependency).
- Evidence: Lunchbox Lottery made ten staging attempts; seven returned malformed argument results.
- Starting points: WorkspacePackageTools.kt; WorkspacePackageRepository.kt under the Android application source tree; inspect current code before editing.
- Acceptance: A malformed file placement yields a bounded actionable correction without whole-app regeneration; retained source remains reusable; regression reproduces the observed top-level file errors.
- Constraints / validation: Preserve exact target, base revision, package validation and immutable source. Measure error count, payload and token use in a fresh live run.
- Resolution: Merged in alpha.29 source via PRs #35–#37; automated evidence and remaining scope are in the alpha.29 release notes. Phone verification remains outstanding. Live evidence: outstanding.

## AF-002: Complete delivery despite unavailable optional inspection

- Status: verified. Related work: AF-006 (coordination, not a hard dependency).
- Evidence: An activated, storage-verified app stayed WAITING after rejected pixel handoffs.
- Starting points: ExecutionTasks.kt; WorkspaceLiveTools.kt under the Android application source tree; inspect current code before editing.
- Acceptance: Proven delivery completes with optional inspection explicitly unverified; rejected unnecessary handoffs do not become permanent blockers; required checks and unknown effects remain blocking.
- Constraints / validation: Replay successful activation followed by unavailable capture and invalid handoff; preserve consent and receipt evidence.
- Resolution: Merged in alpha.29 source via PRs #35–#37; automated evidence and remaining scope are in the alpha.29 release notes. Phone verification remains outstanding. Live evidence: outstanding.

## AF-003: Bound identical deterministic retries across slices

- Status: verified. Related work: None (coordination, not a hard dependency).
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

- Status: verified. Tracking issue: #46; implementation PR #56, operational evidence from PR #58 and the alpha.31 build.
- Evidence: push and pull_request duplicate checks; device concurrency retains obsolete runs and queues newer qualification.
- Acceptance: updates cancel/deduplicate stale validation, latest heads still receive required checks, and manual signed builds retain their exact source and are never cancelled by PR updates.
- Constraints: preserve fork read-only validation and signing-secret isolation. Workflow scheduling only, separate from runtime behavior.
- Resolution: superseded Android/device runs cancelled on PR update; current checks passed and manual dispatch remained independent. See alpha.31 release evidence.

AF-005 implements stronger assertion tools and explicit evidence, not an independent semantic oracle. AF-004 clarifies evidence provenance; the reported open-workspace capture mismatch still needs live reproduction. AF-007 uses synthetic DOM input with simulated capture; native hit testing and physical touch require phone verification. Outstanding phone acceptance is tracked individually; AF-002/003 and AF-008 have completed their acceptance evidence.

## AF-009: Bound hung UI checks and recover workspace rendering

- Status: verified in alpha.30; merged PR #49. Issue #48 closed.
- Evidence: Maze generation UI could leave its exit unreachable and loop forever tracing the route. A separate pure fixture passed; it did not execute the UI algorithm. Repeated UI calls lacked terminal results. Other live-workspace failures are user reported; shared renderer involvement remains to be confirmed on the phone.
- Scope: per-evaluation and total deadlines, active-step failure receipts, renderer interruption before disposal, content-hash suppression after execution timeout, and terminal cancellation bookkeeping without overwriting committed receipts.
- Validation: device regression exercises a synchronous infinite-loop click, suppression despite changed test steps, corrected source and reopening an existing healthy application's surface. CI passed on API 35/36; the local Gradle distribution download is unavailable. No physical-device recovery or semantic correctness claim.
- Constraint: WebView renderers may be shared; termination can invalidate other surfaces. Their stored packages and records must be preserved. This is recovery, not process isolation.

### AF-009 acceptance evidence

Final alpha.30 source 7b77046 passed Android unit/lint, signed build 39522593617, and all 15 device tests on API 35/36 in run 39522526541. The device regression exercises a real infinite-loop click and subsequent recovery. In session ab31192d, MazeGen activated successfully and completed UI checks in about 2.4 seconds; the user confirmed the visible solver and separately confirmed the other workspaces worked. That phone run did not trigger the watchdog itself.

## AF-010–012: Alpha.30 follow-up

Session ab31192d reproduced disabled text entry until pause/resume, an obsolete failed acceptance blocking delivered work, and a zero-count assertion misread as evidence of no path. Current work separates draft editing from submission gating, retires resolved authorization turns on continuation, scopes failed-acceptance supersession to the exact delivered candidate, and returns typed expected/actual counts. Unit/lint and API 35/36 checks passed before merge; these fixes are delivered in alpha.31 and phone acceptance remains separate. Unknown effects and other-candidate failures stay blockers.

## AF-013: Model boundary audit

Confirmed ChatGptPlanBackend serializes function tools with strict=false; OpenAiCompatibleBackend forwards parameters without a strict declaration. Workspace UI tests accept native structured values and historical JSON strings. Do not globally enable strict mode until tool schemas have been made compatible and the transport is tested. Follow-up must measure first-attempt validity, model calls, cumulative input and elapsed activation time against fixed tasks. No measured latency improvement is claimed by AF-010–012.

### Alpha.30 follow-up resolution

PR #54 resolves code defects tracked by #50–52: drafting remains available during active work, blocker replies can be submitted, resolved authorization turns retire without approving tools, exact delivered candidates supersede failed acceptance attempts, and count reports carry expected/actual values through checkpoints. Phone verification of these new changes remains pending; no new APK is implied by merging source.

PR #55 provides enumerated UI action/assertion schema alternatives and native object-valued gesture/attribute/record inputs with historical string compatibility. #58 implements the identity/evidence binding and context compaction follow-up and audits the remaining native JSON fields. #53 remains open for measured call/token/latency benchmarks. Provider strict mode is not enabled globally.

PR #56 restricts push validation to main, retains PR validation including forks, cancels superseded same-ref validation and isolates manual dispatch by run ID with cancellation disabled. #46 is verified by observed PR supersession, independent manual dispatch, and successful signed alpha.31 delivery.

Issues #39 and #40 were closed against their narrow alpha.30 phone evidence: activation succeeded without optional viewport pixels, and deterministic rejection suppression fired. The remaining failed acceptance and semantic/context churn were not hidden by those closures.

Automated follow-up evidence: PR #54 combined head passed Android run `39531315814`, public-readiness `39531315848`, and API 35/36 device run `39531315767` after incorporating #55. PR #55 separately passed Android `39530255640` and device `39530255541`. PR #56 scheduling-only source passed Android `39530362220` and device `39530362306` before its base update; no Android runtime code is changed by that PR.

## Alpha.31 authoring boundary follow-up (#53)

Omitted identifiers on stage/validate/test/UI-test/accept/activate resolve from the durable task target and latest successful staged candidate. Explicit identifiers are preserved for ordinary conflict checks; reference reads and explicit retargeting are unchanged. Base revisions and pure check IDs remain explicit. Native manual acceptance binds a user message ID before confirmation and rejects changed instructions. Action-specific schemas require applicable values and describe structured gesture/attribute/record fields. Existing source, input and execution-time bounds still apply.

Byte-identical pinned source/contract reads retain the newest body and project older duplicates with audit references. Recent-operation summaries omit read bodies already represented by transcript and source inventory. Tests measure deterministic packet reduction; live model first-attempt validity, cumulative input/output tokens, calls and elapsed time still require the next fixed phone task. Global strict mode remains disabled because package file maps, record data and export inputs are dynamic; this build does not claim provider-enforced strict decoding.

Alpha.31 delivery: PR #58 merged; signed main build `39534360360` delivered with matching provenance and persistent signing identity. Unit/lint, public readiness, and API 35/36 passed. AF-008 operational acceptance is recorded in the [alpha.31 notes](./releases/v0.1.0-alpha.31.md). AF-010–013 are included in this APK; phone acceptance and the fixed-task performance comparison remain pending.

## Alpha.32 follow-up: #60–62 and reasoning observability

PR #63 implements exact hash/preimage-checked stage edits, pending verification guidance, matching non-BLE UI activation evidence, and request-bound hosted search availability. Phone performance and broad-goal decision quality remain separate acceptance in #53. Alpha.32 adds an explicit ChatGPT effort selector and optional provider reasoning/cache telemetry; the on-device thinking checkbox does not control ChatGPT. See the [alpha.32 test cycle](./releases/v0.1.0-alpha.32.md).

Alpha.32 delivery: PR #63 merged; signed main source `1466bea`, run `39673414889`, version code `1333`. APK/provenance hashes, signing identity and Drive metadata were verified. Issues #60–62 are closed against implemented code and automated regression evidence; #53 retains phone benchmarks and broad-goal decision quality. The ChatGPT effort selector is independent of the on-device thinking checkbox and defaults to the provider's choice until explicitly changed. See the alpha.32 release notes for the two-round autonomy test.

## Major milestone: replaceable runtime and development connection

Tracking [#65](https://github.com/SEsquieu/atlas/issues/65). First opt-in bridge merged in [PR #66](https://github.com/SEsquieu/atlas/pull/66), with automated qualification; full milestone remains in progress and phone acceptance is pending. See [architecture, invariants and completion gates](./replaceable-runtime.md). First bridge is not full orchestration extraction; issue/PR automation remains outstanding.

## AF-017: Revision recovery and native approval after discovery

Tracking [#68](https://github.com/SEsquieu/atlas/issues/68). Status: merged in [PR #69](https://github.com/SEsquieu/atlas/pull/69) and [PR #70](https://github.com/SEsquieu/atlas/pull/70), delivered in alpha.34; unit/lint and API35/36 qualification passed. Phone acceptance pending.

Alpha.33 phone evidence showed an already-live candidate being retested against its historical base, a candidate source hash used without its parent candidate, an unnecessary replacement draft, and discovery rejecting a user-requested native confirmation before the dialog appeared. The revision and source guards were correct; lifecycle guidance and discovery routing were not.

The fix returns explicit live/pending/stale candidate state, current live identity and exact source ancestry; rejects stale tests before worker execution; prevents ordinary creation from replacing an installed task target; and permits resumed installed local work to reach its normal native confirmation gate. Discovery does not grant permission. Obsolete pre-execution candidate-base failures and refused replacement-creation attempts retire only after receipt-backed delivery on the same bound workspace; unknown outcomes remain blockers. Runtime proposal failures can retire after a later successful selection from the same installed parent, without rewriting their audit status.

See [the sanitized phone assessment](./alpha33-live-assessment.md). User-requested behavioral acceptance remains exact-candidate and explicitly unverified; no test pass is invented.

## AF-018: Host verification and source retention

Tracking [#72](https://github.com/SEsquieu/atlas/issues/72). Status: merged in [PR #73](https://github.com/SEsquieu/atlas/pull/73), delivered in alpha.35; unit/lint and 21 tests each on API35/36 passed. Phone acceptance pending.

Alpha.34 completed delivery but exposed malformed exact-count handling, repeated impossible input attempts, weak determinism/preservation assertions and source rereads through compaction. Scope: bounded preflight count rejection, native constraint diagnostics, named DOM comparison evidence, and durable task read recovery with hash/identity/budget checks. Keep checks candidate-specific, retain original audit/protocol pairs, and do not promote status/count evidence to semantic proof. See [assessment](./alpha34-live-assessment.md) and [alpha.35 acceptance](./releases/v0.1.0-alpha.35.md).

## AF-019: Autonomous workspace visual testing

Tracking [#75](https://github.com/SEsquieu/atlas/issues/75), implementation [PR #76](https://github.com/SEsquieu/atlas/pull/76). Status: in progress; physical-device qualification pending.

Atlas could author a Snake application but the visible-workspace capture required the user to open it. Scope: app-private compositor window in a separate host process; authoritative measured device profile and shared native chrome; production WebView containment with disposable SQLite/mock BLE; persistent task-owned handles; fixed native/DOM inputs, geometry, actual screenshots and optional bound receipts; cancellation/expiry/process-death cleanup; API35/36 regression evidence and a Galaxy S22 acceptance plan. The model chooses testing by need. Activation, permission and persistence gates are not weakened. See [the implementation contract](./workspace-visual-testing.md).
