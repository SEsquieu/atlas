# Harness packet recovery: alpha.38 acceptance

## Problem and implementation

The prior phone pass reached `INFERENCE_PACKET_LIMIT` with 123,112 text characters plus 8,192 reserved response characters against a 128,000 character ceiling. Three user resumes encountered the same capacity limit. Conversation projection ran, but additional task evidence, source, diagnostics and schemas had independent allowances.

`InferencePacketCompiler.prepare` reserves the required policy, current conversation/tool protocol, schemas, user text, runtime guidance, discovery state and task obligations before allocating optional evidence from the remaining shared budget. Optional sections are whole blocks with an explicit omission manifest. It does not remove tool schemas or cut assistant/tool pairs, rewrite source, increase the ceiling, or infer success from missing evidence. Accounting remains provider-neutral text accounting; image payloads, provider wire overhead and exact token limits are separate constraints.

`TaskContextProjection` keeps the objective, target, delivery/acceptance requirements, unresolved operations, exact candidate/check/activation bindings, status, scalar verdicts and counters. It replaces duplicated nested check detail with partial-evidence markers and audit references. Completed checkpoint ID lists and duplicate raw result prefixes are excluded from inference. Original task state, operation receipts and audit rows remain unchanged. `atlas_tool_result_read` retrieves bounded exact result/error fragments for session-owned workspace/runtime operations; other sessions and unrelated tool families remain denied.

`context.assembled.packet` records required text size, component sizes, section sizes, retained/omitted optional sections and recovery mode. Essential context that still cannot fit emits `context.capacity_blocked` with diagnostics and parks the task at `context_capacity` without automatic retry. Manual resume is allowed and rebuilds current state; it cannot magically fit an unchanged oversized essential request. No provider request is made for that blocked assembly.

This pass does not make arbitrary dialogue or tool responses unbounded. Conversation selection still has its existing protocol-safe 32-message/32,000-character constraints. Large essential discovery state, steering, schema catalogs or outstanding failures can still require a capacity stop. Native capacity decisions cannot be overridden by editable JavaScript guidance.

## Automated acceptance

- Shared allocation under source/diagnostic pressure with complete tool protocol and schemas preserved.
- Required-content overflow with structured diagnostics and no provider dispatch or automatic retry.
- Deterministic task projection across 200 synthetic continuation checkpoints, preserving failed checks, unknown operations, required pixels, acceptance criteria and candidate bindings; original audit unchanged.
- Scoped exact runtime/workspace result/error reads, bounded Unicode-safe pagination, and denial of cross-session/unrelated reads.
- Existing renderer lifecycle, cancellation, process death, stale handle and repeated-cycle tests, plus allocation with the installed device tool catalog.

Synthetic fixture success and emulator results do not establish Samsung compositor qualification or real-session latency improvements. Record actual results in the PR/build provenance.

## Phone acceptance

1. Update over the existing install using the persistent signing identity. Keep app data.
2. Ask Atlas to inspect the retained MazeGen candidate and current live revision before continuing. The previous pass staged and tested a candidate but did not activate it. A changed base must be handled normally.
3. Continue the random/default seed and single-screen requirements. Run at least three edit/test/capture iterations, then another refinement request to keep evidence accumulating.
4. Ask what is tested, what remains unverified, and whether the candidate is live. Compare against tool receipts. A visible element alone is insufficient evidence of a usable maze.
5. Interrupt and resume during work. Confirm current candidate/criteria survive and committed effects are not repeated.
6. Exercise private open/inspect/capture/close with immediate reopen. Automated tests cover 20 cycles; record any phone-specific failures and timing.
7. Ask Atlas to inspect its runtime and propose a bounded runtime improvement. Guidance/infer/yield are editable; native projection/provider/build repairs require the repository development loop. Runtime selection must not silently change the running task's pinned runtime.
8. Export the session to Atlas Related / Session Logs. Include brief notes on stalls, recovery, delivery clarity and whether the app remained usable.

## Destination and subsequent passes

The target is sustained development with bounded context, responsive local controls, durable recovery and evidence-backed delivery. Follow this packet pass with provider transport/error classification (#81/#82), responsive controls and bounded history projections (#83/#84), stronger actual-viewport/semantic acceptance (#85/#86), and a structured native repair handoff/quality benchmark (#87–#89). Refs #79, #80, #90. UI polish follows behavioral reliability. No automatic merge or hardware qualification is implied by an APK delivery.
