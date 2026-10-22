# Replaceable runtime milestone

Tracking: [#65](https://github.com/SEsquieu/atlas/issues/65). Status: **in progress, experimental and opt-in**. This milestone is not complete when the first bridge compiles. The native loop remains the default until device and phone acceptance establish parity.

## Intended behavior

An installed Android host can run a separately versioned orchestration bundle. Changes to context selection, guidance, progression and recovery should eventually need a runtime release rather than an APK. Android lifecycle, durable state, operation reconciliation, permission enforcement, credentials, provider transport and device adapters remain host responsibilities. Runtime decisions never constitute permission grants or verification evidence.

The same portable core should run in a desktop test harness. An optional remote development executor may build proposed bundles and draft PRs. Standalone phone operation must not require a gateway server, development account, GitHub connection, or laptop. Development automation and runtime activation are independent capabilities.

## First bridge: implemented scope

`packages/atlas-runtime-core/core.js` is a portable, synchronous JavaScript policy compiled into a reproducibly generated JSON asset by `bundle.mjs`. It is JavaScript rather than TypeScript initially; no Node globals, imports, timers or I/O are available on the phone. Host code calls the core before inference with step and monotonic elapsed time. It returns infer/yield and bounded additional orchestration guidance. Existing context projection, model response interpretation and recovery handlers remain Kotlin. This is not yet a full replacement of the agent loop.

The host validates exact response fields, protocol version, request identity, command allowlist, response size and guidance size. It checks cancellation and execution lease again before using a response. Core decisions cannot execute tools, change budgets, manufacture receipts, waive approval, change task targets or extend host slice limits. A yield ends a slice, not the durable task.

The JavaScript engine is separate from workspace execution. One warm Jetpack sandbox/isolate is owned by the session service; calls are serialized. It is never called for individual streamed tokens, speech chunks or pointer events. Features required: isolate termination and heap limits. Unsupported devices retain the native path. Explicitly selected JS tasks fail honestly if the engine is unavailable; there is no silent mid-task switch of implementation.

Current bounds: 256KiB bundle, 16MiB isolate heap, 32KiB returned command, 16Ki characters guidance, five-second cold startup deadline, one-second warm deadline. These are failure ceilings, not latency targets. A timeout or cancellation terminates the isolate/sandbox and does not execute a command. Error classification and richer recovery UI are outstanding.

## Trust, identity and activation

A bundle contains `format`, `version`, `bridge`, `sha256`, `source`, and for external imports `signature`. The source hash is SHA-256 of UTF-8 source bytes. Publisher signatures use SHA256withECDSA over UTF-8 JSON array `[format, version, bridge, sha256]`. Public keys are base64 DER SubjectPublicKeyInfo EC keys supplied by the human in native UI. No private signing key ships in Atlas. Bundle identity is SHA-256 of that same signing payload, so changing a version cannot replace a pinned artifact in place.

The app's Data & activity page has Native, Bundled core and signed-import controls. External imports are size bounded and signature verified before an explicit native activation prompt. Selection applies only to tasks that have not acquired a runtime pin. A content-addressed immutable copy of the bundled core is retained too; future APKs cannot change an old task's source. There is no remote update feed or implicit publisher trust in this slice.

Task pins live in the durable execution task document and are retained across continuation, store recreation, process recovery and selection changes. Bundle files and selection use atomic writes. Artifacts are retained; garbage collection is deliberately absent until task/archive references can be enumerated safely. Native selection is rollback for new tasks. It does not change an already pinned task. A broken pinned task must be cancelled and a new task started; safe explicit migration is a later feature. Missing/corrupt pinned artifacts block rather than replay effects under a different implementation.

Import/install and runtime selection are host operations. Generated workspace content and the model have no direct access to those controls. Trusted publishers can change agent behavior; signature verification proves provenance and integrity, not correctness. Model-proposed fixes must go through repository validation and the configured activation policy.

## Concurrency and effects

The existing task runner and execution lease remain authoritative. The bridge is not a second scheduler. Pending tool calls retain current ordering, approval and reconciliation paths. The core receives no raw database handle, credential or native object. Every asynchronous boundary must preserve cancellation, session generation, task identity, lease ownership and navigation identity. Existing operation IDs and receipts remain the only evidence of completed effects.

Future effect-bearing commands require a durable command journal keyed by task ID, runtime ID, event sequence and command index. Persist intent before execution and distinguish retryable reads, atomically committed local mutations, confirmed external outcomes and unknown outcomes. Never promise universal exactly-once physical/network execution. Unknown effects require reconciliation; process death does not justify blind replay.

Concurrent update/import cannot change a pinned task. Concurrent core requests serialize; request IDs reject mismatched responses. Runtime activation must never cancel a provider request, approval dialog, tool operation or active speech stream. Artifact identity is included in decision diagnostics. A resumed user task must keep its original runtime, even when the default has changed.

## Performance gates

Measure cold startup separately from warm evaluation, queue wait, serialization, provider first-token latency, tool latency and user-visible response latency. Do not attribute provider variation to the bridge. Current device tests log cold, warm p50/p95 and sample count and provisionally gate warm evaluation p95 below 100ms on API35/36 emulators. That is a regression ceiling; phone measurements must establish a tighter practical target before default rollout. Emulator measurements do not establish Galaxy S22 Ultra performance.

Replay identical state/events through native and portable implementations before moving each decision. Preserve context budgets, protocol pairing, compaction, workspace evidence, task target bindings, clarifications and provider fallback semantics. Test long transcripts and large tool receipts; don't copy the complete audit history into every bridge call. The first bridge sends only a small snapshot and adds a short guidance block; it does not yet optimize token usage.

## Development connection (not implemented)

A configurable connection advertises capabilities and accessible projects. Installation configuration selects service and opaque project ID. Repository-specific contribution and release rules live in the repo. No maintainer route, repository owner, shared GitHub token or coding service is compiled into the Android host.

Required flow: local finding → durable outbox → bounded evidence preview/redaction → idempotent service submission → issue receipt → optional isolated coding job → draft PR/check receipts. Store observations separately from proposed diagnoses. Deduplicate by stable finding/submission identity; retry transient delivery using backoff and reconcile uncertain responses. Background work must remain cancellable and visible. Network loss must not lose a finding or block the interactive loop.

The service holds scoped repository credentials; the app holds only its connection credentials. Project permissions are enforced server-side. Session/workspace text is untrusted evidence, never authority to select a repository, grant merge rights or execute arbitrary CI commands. Job workspaces are isolated; concurrency and resource budgets are explicit. Public evidence must exclude credentials, private transcript and device identifiers unless deliberately selected. Automatic merging and bundle activation are separate policies, initially human controlled. Builds remain batchable independently of PR completion.

## Completion gates and remaining work

| Gate | Current state |
| --- | --- |
| Portable bounded bridge and signed local import | Implemented; qualification pending |
| Durable task version pins and explicit future-task rollback | Implemented; qualification pending |
| Native parity, cancelled/stale decisions and adversarial bundles | Automated coverage started; full replay matrix outstanding |
| API35/36 engine execution, cancellation/recovery and latency | Device tests added; results pending |
| Full context assembly and response orchestration extraction | Not implemented |
| Stateful runtime migration, command journal and crash reconciliation | Not implemented; required before effect-bearing commands |
| Runtime health history, actionable failure UI and automatic safe rollback policy | Not implemented |
| Configurable finding outbox and development connection | Not implemented |
| Issue/PR executor and remote update distribution | Not implemented |
| Signed APK, phone parity, UX and latency acceptance | Pending |

Full milestone verification must cover pause/resume, process death during inference and tool execution, approval wait/reply, navigation changes, late provider results, streaming speech, long-running task continuation, corrupt/missing artifacts, publisher changes, failed updates, racing imports and duplicate development submissions. Include real MazeGen iteration and physical BLE scenarios. Retain native execution until those gates pass.

## Development commands

- `node packages/atlas-runtime-core/bundle.mjs`: regenerate the bundled asset.
- `npm run build`: checks that the generated asset matches source, alongside existing packages.
- `npm test`: portable policy tests alongside existing packages.
- Android unit tests cover signature/integrity, rejected commands, store retention and task pinning.
- Device verification exercises the actual isolated engine on API35/36; `AtlasRuntimeBenchmark` log lines contain timings.

To create an external test bundle, generate your own EC signing key, supply its public SPKI base64 through native UI, then run `ATLAS_RUNTIME_SIGNING_KEY=/path/to/private.pem node packages/atlas-runtime-core/bundle.mjs /path/to/runtime-bundle.json`. Keep private keys out of source control. The script currently uses the source-declared version; change that version deliberately for a new published artifact.
