# Alpha.37: first harness continuation test build

This batch addresses context pressure and queued-tool execution identity from issues #79/#80, with the first deterministic workload for #89. It includes the renderer lifecycle fixes from PR #78. Source merge, signed APK delivery and physical acceptance remain separate.

## Implementation

`ToolExecutionContext` carries the immutable proposal/operation identity, current executing turn and lease through coroutine context. Runtime dispatch supplies it explicitly; the harness validates task/session/proposal identity and generation before and after execution. Visual tools use this captured lease rather than adopting whichever owner is current later. A direct obsolete-slice call still fails. The durable operation and audit turn are never rewritten to manufacture current authority.

Large visual and pure/UI verification reports become bounded provider views with exact artifact/check identity, scalar outcomes, explicit partial/omitted fields, original hash and audit identifiers. `atlas_tool_result_read` provides bounded exact report fragments within the same session. Full reports, effect receipts and activation checks remain unchanged. Missing detail is unknown, never passing evidence.

When a current turn exceeds message/character limits, complete workspace batches can be represented as receipt evidence, retaining operation/report identities while removing their completed wire-protocol pairs together. Incomplete or unrelated protocol pairs remain intact. Oversized arbitrary user dialogue and unknown report formats still fail honestly; this build does not promise unlimited context or silently summarize user instructions. The 32,000-character conversation guard is unchanged.

`InferencePacketCompiler` accounts for provider-neutral system, message/proposal, tool description/schema, user and context-note text. It reserves 8,192 characters of response headroom within a 128,000-character logical text ceiling. It does not claim provider-token precision or count image encoding as text. `context.assembled` records component sizes, text UTF-8 bytes, image presence and preparation time. Token reservation now includes proposal arguments and tool descriptions rather than only message content and schemas. Full authored arguments are replaced by durable artifact references in task context; the internal progress-frontier signature is no longer repeated to the model.

This is the first packet/recovery boundary, not completion of every item in #79 or the broader quality roadmap. Capability-version caching, incremental database projections, typed provider retries/streams, foreground parking and visual geometry acceptance remain separate work.

## Automated acceptance

- Sanitized report-heavy workload exceeds the former active-turn limit and projects deterministically while retaining failed verdicts, identity and audit.
- More than 32 messages of completed workspace work recover without orphaned provider tool pairs.
- Current-lease pending dispatch succeeds after continuation; implicit old-slice, foreign-task, changed-proposal and cancelled/stale-lease attempts fail.
- Audit drilldown is exact, bounded and session-scoped.
- Packet tests account for arguments, descriptions, schemas, UTF-8 and response headroom.
- Device tests carry a queued visual open across a real slice boundary, capture actual pixels, and check the installed tool catalog at the conversation ceiling.
- Existing renderer, activation, permission, persistence, provider and restart suites remain required.

Unit workload output is labeled `HARNESS_PACKET_BASELINE`. Device catalog output is labeled `AtlasHarnessBenchmark`. These are deterministic packet/contract evidence, not a measured phone latency improvement.

## Focused phone session

Install the signed update over the existing app. Keep the same provider, effort and routing settings so comparisons are meaningful.

1. In an active Session, ask Atlas to inspect and iterate an existing retained candidate, preserving earlier work and avoiding replacement workspaces. Use a candidate whose base is still current; stale historical checks must remain rejected.
2. Ask for pure and UI tests, private visual inspection/capture and activation when authorized. Continue across multiple slices; do not open the workspace to rescue testing. Record any failure, unnecessary clarification or repeated read.
3. Exercise a failed behavioral assertion, request the missing report detail, correct only the test/source defect, and finish the requested task. Failure evidence must survive context reduction.
4. Interrupt a long run, pause/resume, and issue a correction. Cancelled/superseded work must not commit later; operation receipts must not be replayed as new effects.
5. Export the session. Check `context.assembled.packet` and `preparation_us`, provider tokens/effort/cache telemetry, model/tool calls and exact candidate/check/activation outcomes. Compare packet sizes and user interventions against the earlier workload, separating provider time from local preparation.

Actual-profile layout quality, background/focus recovery, large-DOM comparison capacity and full Galaxy compositor/soak acceptance are still outstanding. No aesthetic or hardware qualification follows from this build's passing emulator tests.
