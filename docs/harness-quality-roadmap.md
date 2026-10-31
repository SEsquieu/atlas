# Harness quality roadmap

Tracker: [#90](https://github.com/SEsquieu/atlas/issues/90). Proposed work, October 31, 2026. This extends the [active fixes](./active-fixes.md) queue; it does not replace its stable AF IDs or mark earlier fixes verified.

The goal is dependable task completion with concise context and communication, responsive controls and bounded resource use. Session logs are workload evidence. Code review, adversarial tests and benchmarks should discover issues before a phone session encounters them.

This review inspected Android execution, context construction, provider routing/transport, tool outcomes, task/UI projections, workspace visual acceptance and session exports at source `7758380` (PR #78). It is an initial cross-cutting pass, not an exhaustive review of every native capability. The documentation branch is based on main; citations below identify the reviewed implementation explicitly.

## Prioritized work

P0 identifies demonstrated continuity blockers. P1 covers foundational correctness, responsiveness or measurement work. P2 is bounded capacity/usability work. These priorities do not imply implementation or physical acceptance. AF IDs remain available for maintainer triage rather than being silently reassigned here.

| Priority | Tracking issue / outcome | Evidence level |
| --- | --- | --- |
| P0 | [#79](https://github.com/SEsquieu/atlas/issues/79) Compile bounded inference packets and recover active context pressure | Confirmed device failures and code-path defect |
| P0 | [#80](https://github.com/SEsquieu/atlas/issues/80) Authorize queued tools with explicit proposal and execution identities | Confirmed device failure and contract mismatch |
| P1 | [#81](https://github.com/SEsquieu/atlas/issues/81) Preserve typed provider failures through routing and retry decisions | Code-path defect found beyond the session log |
| P1 | [#82](https://github.com/SEsquieu/atlas/issues/82) Bound provider streams and enforce terminal response semantics | Code-path defect and unbounded resource paths beyond the session log |
| P1 | [#83](https://github.com/SEsquieu/atlas/issues/83) Keep workspace navigation and controls responsive during model work | Code-path coupling; visible latency requires targeted measurement |
| P1 | [#84](https://github.com/SEsquieu/atlas/issues/84) Make durable projections incremental and bound task-history query costs | Repeated work confirmed; performance impact to benchmark |
| P1 | [#85](https://github.com/SEsquieu/atlas/issues/85) Bind requested visual acceptance to actual profiles and recoverable eligibility | Confirmed acceptance gap; related existing milestones |
| P2 | [#86](https://github.com/SEsquieu/atlas/issues/86) Classify dom comparison capacity and support bounded large outputs | Confirmed device limitation; related test semantics work |
| P1 | [#87](https://github.com/SEsquieu/atlas/issues/87) Normalize tool outcome and recovery contracts across adapters | Architecture weakness demonstrated by adapter-specific classification |
| P1 | [#88](https://github.com/SEsquieu/atlas/issues/88) Project authoritative task continuity into a concise user experience | Device evidence and broader UX opportunity |
| P1 | [#89](https://github.com/SEsquieu/atlas/issues/89) Add a reproducible quality and performance evaluation loop | Cross-cutting measurement and feedback foundation |

Existing related work remains tracked in #53 (model boundary), #42/#44 (acceptance/test semantics), #65 (replaceable runtime and development connection), and #75 (physical visual qualification). New issues link these dependencies rather than duplicate historical implementation claims.

## Architecture to evolve toward

Use small explicit contracts at existing boundaries, migrating adapters incrementally:

1. **Execution context:** distinguish the immutable proposal from the current executing task/slice/lease, operation ID and target binding. Queued work can continue under valid authority without pretending the old slice is current.
2. **Tool outcome:** separate invocation completion, verification verdict, effect certainty, artifact identity, recovery/wait condition and evidence scope. Keep transport completion distinct from successful verification. Legacy persisted JSON needs compatibility views.
3. **Inference packet:** one compiler accounts for the complete outgoing request and response headroom. Static contracts, current task facts, source ranges, proposals/results and schemas have explicit budgets and provenance. Detailed reports remain durable and retrievable.
4. **Current task view:** derive objective/steering, candidate, committed effects, pending criteria, next action and blocker from authoritative state. Use it consistently in the model packet and UI. Historical summaries cannot override current receipts.
5. **Provider lifecycle:** typed route failures and bounded terminal stream contracts make retry, cancellation and incomplete output decisions consistent across providers.

These boundaries should reduce repeated decisions and parsing; they are not justification for a generic framework or whole-runtime rewrite. Preserve existing database receipts, task serialization, native permissions, source/revision guards and isolated compositor-backed rendering.

## Evidence from beyond the latest incident

### Provider error classification is lost

In [CapabilityRouter](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/provider/CapabilityRouter.kt), route/stream aggregation constructs a new exception without retaining providerCode. [DurableTaskExecutor](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/runtime/ExecutionTasks.kt) uses terminal provider codes to suppress retries. Preserve typed causes so permanent account/quota conditions do not enter ordinary transient backoff. See #81.

### Stream completion and resource limits need explicit contracts

[OpenAiCompatibleBackend](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/provider/OpenAiCompatibleBackend.kt) can emit Completed at EOF with partial text and no terminal marker. Its SSE line/argument/text accumulation and non-stream body reads lack explicit byte ceilings. [ChatGptPlanBackend](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/provider/ChatGptPlanBackend.kt) checks response.completed but has similar accumulation paths. Test terminal-then-idle connections, truncation, oversized data and cancellation throughout body reading. See #82.

### Long work shares a control mutex

[AtlasMobileRuntime](../apps/atlas-android/app/src/main/java/com/grinningfrog/atlas/runtime/AtlasMobileRuntime.kt) holds operations across the entire turn loop while workspace navigation uses the same mutex. The coupling is visible in code; its phone latency must be measured with a delayed provider. Narrow state/commit serialization while retaining generation fences. Explicit cancellation already has a separate path. See #83.

### Projection cost grows with history

ExecutionTaskStore.context repeatedly loads full history and recomputes work state, outstanding operations and receipts. ExecutionWorkState reparses JSON and scans historical calls; turnDiagnostics can scan 2,000 event rows for each recent turn. Avoid caching guesses: first share a consistent input snapshot, then benchmark indexed/materialized projections with authoritative replay parity. See #84.

The service already uses Dispatchers.IO, token deltas already use publishTransient, and reasoning/cache telemetry already exists. Those are foundations to retain, not missing features to reinvent. A historical SQLite stall does not establish a new stall in the latest build.

## Measurement and release gates

Start a minimal deterministic benchmark alongside #79/#80; do not wait for a large benchmark framework before fixing demonstrated defects. Add 10, 100, 1,000 and 10,000-operation histories, large report/grid fixtures and continuation at each legal batch boundary.

| Dimension | Measure | Acceptance principle |
| --- | --- | --- |
| User experience | Input/local-control acknowledgement, unnecessary clarification, user interventions | Long provider work must not hide or serialize unrelated local controls; preserve the actual user requirement. |
| Local runtime | Context preparation, SQL queries/rows/decoded bytes, allocations, projection rebuild | Show bounded/scalable behavior and replay parity; benchmark before claiming improvement. |
| Model interaction | Complete packet components, input/output tokens, cached input, effort, model/tool round trips | Reduce repeated payloads and redundant work without omitting authority, source identity or negative evidence. |
| End-to-end delivery | Time to verified activation/draft, first-attempt valid calls, preserved criteria | A faster final sentence is insufficient if requested work is missing or unverified. |
| Recovery | Cancel acknowledgement, resource retirement, resumed work, duplicate/lost effects | Zero stale-owner commits or automatic replay of unknown effects. State cleanup deadlines separately from immediate UI acknowledgement. |
| Rendering | Open/capture latency, profile correctness, focus/IME/rotation behavior, display/process counts | Actual-profile evidence stays distinct from alternatives; retain real compositor pixels and isolation. |

Use p50/p95 distributions and resource counts, not a single best run. Proposed latency targets must be calibrated on the supported phone and workload before becoming release claims. No numeric speedup has been established by this review.

Testing layers:

- Deterministic fake-provider tests isolate local preparation, scheduling and failure semantics.
- Provider transport tests exercise frame sizes, terminal markers, ambiguity, retries and cancellation.
- Multi-step workloads score continuity and semantic requirement preservation, not only schema validity.
- API35/36 instrumentation checks the real native lifecycle and compositor path.
- Matched physical runs record task, source/build, device/provider versions, effort/cache settings, timings and outcome. Emulator success cannot qualify the Samsung compositor.
- Soak tests cover long history, restart, pending approvals, rapid reopen, lock/focus/rotation, slow providers and cancellation at phase boundaries.

Session exports should include build/version/commit and relevant provider/WebView/profile metadata plus a compact blocker timeline. Preserve the complete audit within declared export bounds. Do not publish private session content as benchmark fixtures; derive sanitized generic workloads.

## Concise interaction contract

Concise should mean less repeated work and clearer state:

- Progress states the concrete change or finding, current wait/blocker and next action when useful.
- Final replies distinguish changed, verified and still pending, with exact receipts available for detail.
- Tool defaults return small structured outcomes; explicit detail reads retrieve bounded source/layout/report sections.
- A resource wait should name its condition and resume policy. Do not repeatedly ask the user to reselect an already authorized objective.
- Never compress away a failed assertion, uncertain effect, missing verification or relevant user correction.

This is a quality criterion, not a fixed chat template or an instruction to hide necessary explanations.

## Delivery sequence

1. Minimal baseline + #79/#80: bounded packets/context recovery and pending-dispatch identity.
2. Incremental #87 outcome contract plus #81/#82 provider correctness. Avoid making one migration depend on a wholesale adapter rewrite.
3. #83/#84 control responsiveness and projection costs, with race and long-history measurements.
4. #85/#86/#88 visual acceptance, test capacity and continuity experience. Link existing #42/#44/#75 acceptance rather than closing them by association.
5. Matched provider/device workloads and physical soak under #89; tune the demonstrated bottlenecks.

Keep each PR to a coherent outcome with regressions and measurable acceptance. Use Refs for outstanding device criteria. Source review, regression pass, merged source, shipped APK and physical acceptance are separate states. PR #78 remains independently reviewable; this roadmap does not merge it or enable any automatic scheduler/publication.

## Ongoing scrutiny

For each touched subsystem, examine authority/cancellation boundaries, resource ownership, hot-path queries/payloads, outcome semantics and user-visible recovery. Record newly discovered defects in scoped issues with code evidence, confidence, impact, acceptance and dependencies. Mark unmeasured optimizations as experiments. Necessary local cleanup can accompany a fix; unrelated redesign belongs in another issue/PR.
