# Alpha.28 live assessment and next work

Assessment date: October 19, 2026 (America/New_York). This records the current implementation before further runtime changes. Main is being consolidated around the alpha.28 source baseline, not promoted to a production-stability claim.

## Evidence

Private maintainer session exports were inspected locally. Raw transcripts, images, and session archives are not committed to the public repository.

- Dinner Shuffle, session `04849608`, October 17: the October 19 re-export contains identical turns, messages, tool calls, events and execution snapshots to the earlier export. This is pre-fix evidence, not alpha.28 validation. The activated app requested query limit 200 where the host allows 100; preview-only storage hid the failure. Repeated malformed staging created three drafts. A later directions candidate never activated before session cancellation.
- Lunchbox Lottery, session `005c4648`, October 19: one workspace created, two candidate stages succeeded, one activation committed. Ten staging attempts comprised one policy rejection, seven structured argument-error results, and two successes. File paths repeatedly escaped the files object despite corrective feedback.
- The first UI check failed by using select on a checkbox. The corrected 17-step check passed adding, filtering, picking, reload, and exact record verification against isolated storage. Evidence reports two commits, one reload, storage_verified=true, and zero host failures. Actual exported workspace records contain the seeded meals. This does not establish user-entered live record survival through process death.
- After activation, workspace screenshot capture reported WORKSPACE_NOT_OPEN. Six identical invalid visual-handoff calls used text that did not quote a user instruction. The task ended in WAITING with unresolved_tool_failure despite delivered activation and passing persistence checks. Camera capture was used to investigate appearance; Atlas later acknowledged that camera evidence does not show the workspace screen. The user reported that the workspace looked fine.
- The list did not individually label example meals. Assertions checked example names, so their success did not prove the labeling requirement. The favorites follow-up was not performed in this export.

Task accounting for Lunchbox Lottery: 1,057,814 input tokens, 26,165 output tokens, 48 model steps, 44 dispatched tool calls, 12 slices, and 689,983 ms recorded active execution. These are recorded cumulative usage, not unique context size, invoice cost, or an endurance benchmark. The tool-call table includes 45 proposals including the rejection. Repeated malformed source submissions and post-delivery inspection contributed avoidable work.

No new crash is established. The retained six-second SQLite main-thread stall predates both tests; worker lifecycle exits are distinct from main-process crashes.

## Next implementation priorities

1. Make package/file authoring structurally easier to submit correctly. Prefer bounded corrections and reuse retained source rather than whole-app regeneration after deterministic argument errors. Evaluate schema and tool-surface changes against the actual malformed calls.
2. Separate required delivery evidence from optional inspection failures. A failed handoff must not waive required checks or consent, but an unnecessary rejected control must not permanently prevent a proven delivery from finishing. Preserve receipts and failure visibility.
3. Bound identical deterministic retries across continuation slices. Detect meaningful progress rather than counting fresh operation IDs or repeated explanations as progress. Keep transient provider handling distinct.
4. Identify physical camera and workspace viewport evidence unambiguously in tools, context and results. Diagnose the reported open-workspace capture mismatch before claiming its cause.
5. Make acceptance assertions prove the requested behavior, including example labels, filter boundaries and selected-item directions. Verify favorites changes preserve existing user records.

## Acceptance for the next pass

Replay regression cases for malformed staging, identical rejected handoffs, and successful activation followed by unavailable optional capture. Preserve persistence gates, target/revision fences, permission boundaries, and unresolved-effect handling. Run a fresh live creation plus favorites edit using saved user records. Compare staging-error count, repeated-call count, token usage, elapsed time, and terminal task state with this baseline. Passing unit tests alone does not establish reduced live-model churn.

This consolidation changes documentation only on top of alpha.28. The priorities above remain open; no new APK or runtime fix is claimed.
