# Workspace application host: implementation chronology

**Reading guide:** this document preserves successive implementation checkpoints. Early statements such as “not enabled” and “gates still open” describe their historical checkpoint, not the current alpha. Read [current status](./current-status.md) first; the alpha.28 qualification and delivery sections below record the latest evidence.

Baseline: alpha.17, public commit `8219fd9b8121186c2dc0707b7c33cbff0fc48e0e`.
This document supersedes the permanent-format-coexistence assumption in the original design. It does not assert that executable applications already ship.

## Destination

`atlas.workspace.web.v1` is the intended single primary authoring format. One authoritative host registry will generate the SDK, capability catalog, tool schemas, errors and examples. Agent discovery must not present V1/V2 as equivalent choices after default-format rollout. Compatibility adapters are temporary loading/conversion mechanisms; remove legacy authoring tools, context guidance and interpreters when useful artifacts have been converted and acceptance gates pass. Preserve identity and data during conversion; retain an importer only if needed.

Alpha status permits decisive migration. It does not justify deleting user data, suppressing failures or exposing unproven native/network authority.

## Settled decisions

- Host owns Android permissions, platform enablement, scoped per-workspace grants, credentials and operation receipts. Generated code cannot self-grant or inherit broad agent/session privileges.
- HTML/CSS/JS package revisions are immutable. General JavaScript updates use controlled reload and durable rehydration initially.
- Presentation uses controlled local origins and a narrow transport; direct browser device/network access is denied. Headless JavaScript requires a separate supervised process and bounded execution.
- Storage and activation use expected versions, writer fencing and durable receipts. No JavaScript executes inside a SQLite transaction.
- Initial migrations are additive or explicitly reversible. Preserve writes after activation; never treat code rollback as authority to erase records.
- Compatible requested local edits may activate after checks. Expanded permissions, destructive migrations and persistent background authority require their own host decisions.
- Dependencies are packaged and pinned, with provenance/licenses. No runtime CDN execution, install scripts, native downloads or exported credentials.
- Pure tools come first. Effectful reusable tools later inherit the caller's effective authority. Cross-workspace data requires explicit delegation.
- APK generation is deferred until implementation is complete. DEV/LAN remain targeted optional builds.

## Implemented foundation

`workspace/application/WorkspaceApplicationContract.kt` owns the strict package format and JSON Schema subset. Staging bounds are explicitly provisional: 1 MiB/package, 128 text files, 256 KiB/file, 64 candidates and 16 MiB retained candidates/workspace. File paths reject traversal, reserved SDK paths, unsupported extensions and case collisions. Dependencies require matching content hashes. Fixtures are schema validated; this is not JavaScript testing.

`WorkspaceAuthority.kt` separates Core-bound principal from untrusted request body, and computes denial from installed adapter, platform enablement, OS permission, workspace scope, execution mode, current revision/epoch and grant revocation generation. This is the contract evaluator, not an installed BLE adapter or persistent grant implementation.

`WorkspacePackageRepository.kt` stores validated immutable candidates in an additive database version 15 table. Native live revisions and records remain authoritative and unchanged. Staging checks realm, source session/turn, inspected live revision and same-workspace parent candidate. Batches fail atomically, historical candidates survive reopening, archive blocks further staging, and delete removes staging rows.

`WorkspacePackageTools.kt` installs contract discovery, inspection, staging and structural validation tools through the existing ToolHarness/task-target policy. All responses explicitly distinguish structural validity from execution, tests and activation. Candidate inspection supplies file metadata rather than flooding model context with complete source. These transitional tools do not execute packages or change grants.

## Engineering gates still open

1. Select/benchmark maintained headless engine, Android ABI/license/update path, isolated process and external cancellation deadline. QuickJS is the first candidate, not yet selected.
2. Implement and test isolated WebView origins, message transport, CSP, file/content denial, service workers, navigation/popups and all direct-network paths. On-device proof is required.
3. Define canonical method registry and generate wire schemas/SDK/tool descriptions from it. Current package schema is the foundation; no complete SDK is claimed.
4. Implement bounded test execution and independent fixture reports against the same module bytes used in production.
5. Add durable application storage, staged activation/epoch, data-version conflict checks, crash recovery, startup readiness and safe rollback.
6. Add package archives/imports and cleanup before live applications are enabled.
7. Switch default creation/catalog/context to packages and remove legacy authoring choices together. Until this gate, native authoring remains unchanged; do not claim migration is complete.
8. Add scoped device broker, BLE and separately approved background jobs, including real-phone and endurance evidence.

No live package renderer, package activation endpoint, permission-grant UI or automatic conversion is enabled. The experimental native worker is not yet exposed to generated applications or agent tools; Android isolation remains a device gate. Unused experimental substrates must not be left in production after selection.

## Validation and delivery

Run `testSecureDebugUnitTest` and `lintSecureDebug`; no `assemble` task is required for implementation validation. Android workflow commits containing `[validation-only]` skip the installable job while retaining tests/lint. Manual dispatch exposes `installable:false`. Final delivery removes the validation-only marker and increments the alpha version only when the runtime is ready for user testing.

New independent tests cover package bounds/paths/schema/dependency hashes, forged request authority, grant isolation/expansion/revocation, stale revision epochs, immutable candidate branching/reopening, target changes, batch rollback, archive/delete and migration from alpha.17. Existing native/task/UI/provider suites remain mandatory.

## Runtime evaluation checkpoint

The current `dokar3/quickjs-kt` release v1.0.15 supports module loading, memory/stack limits and cancellation, but its pinned source targets Kotlin 2.4.10; Atlas currently uses Kotlin 2.1.20. Earlier v1.0.5 already targets Kotlin 2.3.20 and lacks the later interruption fixes. Do not silently pin an older runtime or disable Kotlin metadata checks to avoid a compiler migration. Evaluate an explicit compiler upgrade with full baseline tests versus a maintained Java/JNI binding or a minimal pinned native integration before selecting the worker. `OpenQuickJS/quickjs-android` exposes allocator, stack and interrupt hooks but its inspected Android packaging is ARM-only and release-AAR based; ABI/testability/update-path evaluation remains necessary. No engine dependency has been installed yet.

Primary evaluation sources: [QuickJS Kotlin v1.0.15](https://github.com/dokar3/quickjs-kt/tree/v1.0.15), [OpenQuickJS binding](https://github.com/OpenQuickJS/quickjs-android).

## Native worker spike in progress

The worker candidate is now a minimal JNI integration against QuickJS-NG v0.17.0, exact commit `6d46d07d04041b40f4f49eaa7fdebe44c314c699`, fetched by CMake with standard-library modules disabled. This avoids a Kotlin compiler/metadata migration. The application-owned JNI boundary installs only a packaged-source module loader; it installs no Java reflection, filesystem, network or device binding. Validate licensing, source/build provenance and ABI support before approving the substrate for live use.

`WorkspaceNativeEngine` validates strict export input/output and uses UTF-8 byte arrays rather than JNI modified UTF-8. Each invocation gets a fresh runtime with a provisional 32 MiB heap, 256 KiB stack, 1 second engine deadline, bounded output and package-only imports. Desktop JNI tests exercise actual modules, dynamic imports, unavailable ambient capabilities, loops, memory exhaustion, unresolved promises, malformed outputs and emoji. Native-side serialization is not a replacement for host schema validation.

`WorkspaceJavaScriptService` is a non-exported Android isolated service. `WorkspaceWorkerSupervisor` uses a unique isolated instance per request on Android 10+, an external five-second timeout and a separate control channel; the service main thread schedules a four-second process kill independently of the native evaluation thread. Android 9 worker requests fail explicitly. Core application startup skips settings/media initialization in this process. The current package contract still reports execution/activation unavailable while isolation and integration gates remain open.

Android unit/lint validation now compiles a desktop JNI library first. Android external-native builds use NDK 27.0.12077973 and CMake 3.22.1. This builds libraries for validation; validation-only commits still skip APK generation. Keep the Kotlin and AGP versions unchanged.

## Web presentation spike in progress

`WorkspacePresentationPolicy` computes a distinct HTTPS-style origin for each realm/workspace/revision/instance/mode and validates exact origin, canonical packaged GET paths, entry-page navigation and main-frame/current-epoch messages. `WorkspaceWebPresentation` requires multiprocess rendering, exact-origin message support, renderer monitoring and termination; unsupported providers fail closed. It intercepts every resource without remote fallback, applies host response-header CSP, disables raw file/content access, browser permission prompts, downloads, form/native dialogs, cookies and DOM/WebSQL settings, clears the presentation origin on disposal, and denies service-worker requests. IndexedDB/resource-retention behavior remains an explicit device gate. Unknown host methods return explicit unsupported-capability errors. This is implementation, not on-device containment evidence.

`WorkspaceHostRegistry` currently installs only `host.describe` and `host.ready`; its reserved `/atlas/host.js` SDK uses bounded asynchronous JSON messages. Generated files cannot replace this SDK. No storage/device/network broker methods are registered yet. The future renderer must dispose old surfaces on epoch changes and verify startup readiness before activation. No live application rendering or activation is enabled by this checkpoint.

WebRTC data channels require explicit auditing: CSP/connect-src alone does not establish their containment. The presentation spike additionally requires document-start guards that disable peer-connection constructors, workers, direct audio, clipboard, sharing, credential and device browser APIs before authored scripts, plus browser audio muting. These guards are defense in depth, not proof that every future browser API is contained. If physical/emulator bypass tests show an ambient network route survives the chosen boundary, replace the presentation boundary before enabling live apps. Do not convert this gate into a weaker promise.

## Durable storage integration checkpoint

Database version 16 adds host-owned application lifecycle fences, record version counters, prepared mutation requests, durable completion receipts and revision-check evidence tables. `WorkspaceApplicationData` supports foreground package records through Core-created principals only. It is deliberately not registered on the WebView bridge yet: installation/activation must provide the live revision and epoch, and previews/tests must receive isolated stores before any live method is advertised.

Queries use stable creation-time/ID pagination with optional data-version checks. Atomic batches require an expected data version; inserts, updates and deletes—including existing native writers—advance that version through database triggers. A failed batch rolls back every record and version change. Preparation allocates record IDs without changing records; commit persists changes and the receipt together. Retrying or inspecting a committed operation returns the saved receipt. Old epochs, other workspaces/realms, archived workspaces and non-foreground modes cannot dispatch or retrieve these operations.

Provisional limits: 100 query records/page, 32 mutations/batch, 32 KiB/record, 5,000 records/collection, 30,000 records and 32 MiB/workspace, 64 pending operations and 4,096 total receipts/workspace. Reaching receipt capacity fails explicitly; no receipt is silently evicted while retries may still reference it. A host retention/export policy remains required before broad rollout. Collection records remain strict JSON objects without an application-specific schema in this checkpoint; schema migrations and activation are unfinished.

Regression coverage includes retry after database reopen, failed-batch atomicity, conflicts from existing native writers, realm/workspace/mode/epoch isolation, strict numeric parameters, deletion cleanup and upgrade from database version 15. These are validation targets, not a claim of completed CI or device evidence.

## Candidate execution and activation checkpoint

`WorkspaceCandidateChecks` runs 1–16 declared pure fixtures through an injected executor; production installs `WorkspaceWorkerSupervisor`, never in-process JNI. Each call receives fresh package/input copies without storage or device bindings. The suite has a 20-second total deadline in addition to per-worker deadlines. Cancellation persists no completed check. Reports include bounded assertion previews, coverage, package hash, base revision and data version; persistence rechecks the candidate, realm, active workspace, revision and data counter. Every declared export needs a passing fixture. Reports are evidence for declared fixtures only, not independent proof of the app goal. Retention is bounded to 256 reports/workspace.

`workspace_package_test` is installed by the Android session service only where isolated workers are supported (Android 10+). Contract discovery reports its availability separately from general application execution and activation. This asynchronous tool writes test evidence, not live records; it does not advertise local synchronous diagnostics support. Existing staging and structural-validation tools retain their distinct semantics.

`WorkspaceApplicationActivation` is an internal coordinator, not an agent-callable activation tool. It validates the matching successful fixture receipt, forbids requested capabilities until the persistent grant broker exists, requires unchanged schema versions for package updates and refuses nonempty legacy conversion without a migration. The host-supplied startup probe receives a PREVIEW principal with no live data authority and has a ten-second deadline. The host must dispose the preview in every outcome. Successful readiness is followed by an atomic pointer/monotonic-epoch transaction with all fences checked again. Failed/cancelled startup or changed data leaves the old revision untouched. A crash before that transaction preserves the old app; a crash after commit retains the new revision and epoch. No live data is rewritten or restored by this coordinator.

`WorkspacePresentationProbe` now supplies the real contained-WebView `host.ready` handshake on the main thread and disposes the preview in a `finally` block, including timeout/cancellation. This is startup signaling, not visual testing. Presentation containment evidence, durable activation receipt/recovery policy, migration service, active surface replacement, and live storage bridge remain rollout gates. Do not register activation or switch default authoring on the strength of an injected test probe. Lifecycle tests use worker and startup doubles to test database/coordinator invariants; the separate existing JNI suite executes the real module bytes. Keeping these suites separate avoids loading JNI twice across Robolectric and ordinary JVM classloaders. They do not establish Android/WebView containment.

## Live alpha integration (supersedes staging-only status above)

The service now installs one package-authoring catalog: contract, create draft, target, stage, inspect, bounded source reads, pure fixture tests, pure reusable-export calls, activation, metadata, render diagnostics and visible-panel screenshot capture. V1/V2 authoring tools/catalog are omitted from the production service, while historical loading, archive conversion and their regression tests remain. `ContextAssembler` gives package-only instructions and package metadata; large completed staging batches are projected as Core receipt references without changing original audit records or leaving partial tool-call protocol in inference history.

The contained foreground surface uses the same registry/SDK for `host.describe`, `host.ready`, `data.query`, `data.prepare`, `data.commit`, `data.inspect` and `data.cancel`. The principal is bound by Core. Unknown fields, forged scope, non-foreground storage and unknown device/network methods are denied. Queries return byte-bounded pages and explicit data versions. Client request keys recover prepared/committed operations without replaying writes. Receipt capacity is bounded by both count and bytes. Source and native code never run inside record transactions. Document-start browser guards cover child origins as well as the main origin; interception/CSP and native permission denials remain separate controls.

The agent's staging argument allowance is separate from generic 16 KiB tool limits. The isolated worker receives bounded package bytes through a read-only parcelled pipe, rather than putting a package-sized string into Binder. It still receives no filesystem API or general file handle in JavaScript. Input/output remain bounded, each worker gets a fresh isolated process, and cancellation closes the pipe and terminates the worker. The device suite includes a package over 400 KiB.

Activation stores a durable idempotent receipt in the same transaction as the live revision and monotonic epoch. Async package testing and activation also write the existing Core operation journal in that transaction via `AtlasReceiptBackedAdapter`; task leases are rechecked at commit. Restart reconciliation can therefore classify an interrupted operation as committed or rolled back. Discovery's explicit resume-local path allows these installed local operations without granting research/device authority.

The workspace panel watches revision changes and disposes the old surface before replacing it. Closing the panel disposes foreground timers and callbacks; no persistent background apps are enabled. Startup/render failures disable the current application epoch and show a retry control without deleting records. Archiving fences old callbacks, including after restore. Package exports include source and all records; imported packages are validated, assigned new identity, and start disabled without grants or live handles. Existing native records are never silently converted. A compatible package update must keep its schema version; explicit migration tooling remains future work.

Release acceptance requires Secure unit tests/lint plus the Android device workflow. The latter exercises real isolated execution/termination, package transport, contained module startup, storage writes/retries, forged scope, unavailable capability calls, direct network probes and child-frame browser APIs. Passing this suite is bounded evidence for the exercised Android/WebView combination, not proof against browser/OS vulnerabilities or future APIs. The signed Secure live-test build is produced only after these checks pass. DEV/LAN remain opt-in. Real-phone usability and long-duration behavior remain the subsequent alpha test.

### Measured presentation boundary correction

The Android 15 emulator found that an initial blank child frame retained an unguarded WebRTC constructor. Document-start JavaScript guards are therefore insufficient as the isolation boundary. The host now adds a response-header CSP sandbox with scripts/forms allowed but **without allow-same-origin**, popups, downloads or top navigation. Descendant frames cannot supply a fresh same-origin JavaScript realm to the parent. Package module/style sources are pinned to the exact per-instance HTTPS asset origin and carry CORS headers for the opaque document. Network/file/content interception remains fail-closed.

The message listener must match opaque origins; dispatch consequently requires Android's native main-frame flag, opaque origin `null`, the owning WebView's packaged-only navigation, and a current Core-bound principal/epoch. No subframe can dispatch even if it also has an opaque origin. The provider's source origin is not treated as a workspace identity. The device tests verify both denied parent access to child constructors and no TCP/UDP egress from a hostile srcdoc WebRTC probe. The worker instance name also uses Android's accepted alphanumeric/underscore form. These corrections were driven by failing device tests, not assumed from static inspection.

Package assets may be fetched from the exact host-served virtual asset origin; this is not an outbound network adapter. CSP connect sources pin that origin, interception serves only packaged GET paths with no network fallback, and raw socket/transport APIs remain disabled. The device test checks a local packaged JSON fetch alongside the external TCP/UDP denial probes.

## Alpha.21: foreground BLE host adapter

`bluetooth.le` is the first installed device broker. It uses the existing main-frame transport, Core-bound workspace/revision/epoch principal, closed request validation and contained WebView. Browser Bluetooth remains disabled. Pure exports, fixture workers and activation previews have no device authority. Other device/network/background families remain unavailable.

Declare only needed operations in `manifest.capabilities`:

```json
[{"name":"bluetooth.le","operations":["scan","connect","services","read","write","subscribe"],"resources":["user_selected_device"]}]
```

Connect requires scan and connect declarations; characteristic access also requires services. The resource means the one peripheral approved in Atlas's native selected-device dialog, not arbitrary nearby hardware. Activation permits supported declarations but creates no grant. Native scan consent precedes Android runtime permission. `host.describe().ble_available` indicates a declared foreground binding, not an OS permission or workspace grant; consult `ble.status()` for those separate gates. Connecting to a scanned device needs a separate native approval naming the device and requested operations. A denial blocks further prompts until the user presses **Enable requests** in the native access bar. The bar shows connection status and **Revoke**. Permissions granted to Atlas by Android never grant another workspace access.

```js
import {host,ble} from '/atlas/host.js';
await host.ready(); // Before waiting for user hardware interaction; preview must still become ready.
// Call these from UI controls, showing actual failures and reconnect state.
const {devices}=await ble.scan();
await ble.connect(devices[0].device_id); // Show a user device list; don't blindly choose the first result.
const {services}=await ble.services();
// Service/characteristic UUIDs identify the application protocol; opaque handles address this connection.
const characteristic=services[0].characteristics.find(c=>c.readable);
const {hex}=await ble.read(characteristic.characteristic_id);
await ble.write(characteristic.characteristic_id,'0102'); // Only if writable and explicitly requested.
await ble.subscribe(characteristic.characteristic_id,true); // Only if notifiable.
const {events,dropped}=await ble.events(); // Drain notification queue; surface dropped samples.
await ble.disconnect();
```

`ble.status()` reports adapter installation, Bluetooth enabled state, OS permissions, grant/request state and connection state. The host does not turn Bluetooth on. Android 12+ requests Nearby devices; older Android requests foreground fine location for scanning. `neverForLocation` can filter some beacon advertisements. Location is not derived from BLE results. Scan/permission failures must be displayed with an explicit retry action.

BLE calls submit a per-instance operation and return immediately; the SDK polls `ble.operation` until completion. Receipts retain at most 32 operations. Polling never repeats a write. Read/write/subscribe/connect callbacks have a 12-second limit; the entire permission/operation flow has a 45-second limit. Unknown outcomes close the connection; writes are never automatically retried and users must inspect peripheral state before repeating them.

One GATT connection per presentation; five-second scans; 64 discovered devices; at most 32 services/128 characteristics. No MAC address is exposed. Device and characteristic IDs cannot be supplied from another workspace or survive reopening. Writes use hexadecimal bytes, at most 20 bytes per acknowledged GATT write (no MTU negotiation or write-without-response in this slice). Read/notification payloads are bounded to 512 bytes and explicitly mark truncation. Notification queue holds 32 items and reports drops. No pairing automation, advertising, central background service or reconnect loop.

Leaving the panel, backgrounding, revision replacement, archive/delete, permission loss, or native revocation closes scans/connections and fences delivery. Grants are ephemeral and never imported/exported. Native status is included in render diagnostics; source/fixture success is not evidence of a physical BLE transaction. Real peripheral and Samsung permission behavior require the phone test; emulator checks cover containment, transport and permission denial, while injected adapter tests cover grant and callback behavior.

### Alpha.21 verification and delivery

Application/test commit `0b2492acc53a9eebaf649fccff685a35b4609a31` passed Secure unit tests and lint in [Android run 38758398492](https://github.com/SEsquieu/atlas/actions/runs/38758398492). The signed Secure APK retains the existing alpha signing identity. All nine device tests passed on both API 35 and 36 in [device run 38758398409](https://github.com/SEsquieu/atlas/actions/runs/38758398409), including original Neon Vale foreground pixel capture, light-theme preservation, containment, storage and isolated-worker regressions, real SDK transport with an injected radio, and actual native consent denial/repeat-request blocking.

The denial test initially used Android text search, which did not locate the Compose consent button; walking the accessibility tree exercised the native Deny control successfully on both versions. Failure evidence now includes the exact test stage, page result, render diagnostics and accessibility tree. Tests were not changed to bypass native consent.

The radio simulation establishes broker grant/revision/permission gates, callback delivery, SDK operation polling, and no automatic write replay. It does not prove a physical GATT exchange. A Samsung phone and known peripheral remain the acceptance environment for actual scan/connect/read/write/notification behavior and Android's Nearby devices prompt. Follow the foreground BLE section in `workspace-alpha-test-plan.md`.

Delivered `atlas-alpha21-0b2492a-secure.apk` (69,779,290 bytes), SHA-256 `328cde83ef3b92940bec967ba6b1e31a14ee458a7e969e7967461298f72ac3a5`. Source and saved records of existing workspace apps are unchanged by the host update; generated BLE apps must declare and call the installed adapter before using hardware.

## Alpha.22: reusable consent, capability runtime and behavior checks

The device broker now handles capability-neutral authority, bounded operation receipts, native consent, platform permissions, cancellation and lifecycle teardown. Installed definitions register discovery metadata, request validation, method schemas and native adapter factories in `WorkspaceCapabilityRegistry`; the BLE adapter contains only its radio/resource behavior. `host.describe().capability_registry` and `workspace_package_contract.capability_registry` discover installed families. BLE remains the only physical adapter shipped in this slice; future families do not inherit its grants.

Foreground consent and disposable resource handles are separate. Repeated scans, ordinary radio failures, write/read timeouts and explicit disconnect retain foreground scan consent. Disconnect/failure still closes the connection and invalidates selected-device handles/grants. Backgrounding, leaving the panel, epoch/revision replacement, deletion, permission loss or explicit revoke clear authority. Native denial blocks further requests until native re-enable. Every new instance and grant creation/reuse/revocation records its reason and identity. This fixes an alpha.21 error path that cleared scan approval whenever a BLE operation failed. A future phone log can now identify any additional lifecycle reset paths.

`host.call(method,params)` supplies common operation polling. `host.observe(eventMethod,params,onBatch,{onError,intervalMs})` multiplexes bounded event streams (250..5000 ms; eight observers per document), stops on terminal failure and tolerates temporary busy responses. It never repeats writes. `await ble.watch(characteristic_id,onEvent,onError)` subscribes and drains the notification stream, returning an async stop function. Explicit subscribe/events remain available. Authors should stop watchers and clear device/characteristic selections after disconnect/revoke; `last_failure` uses `phase`, `platform_code` and `at_ms`.

Workspace and session exports include `device_audit`: newest 512 records across that workspace's sessions/revisions. Records contain grant decisions, operation phases/outcomes/counts, instance/epoch identity and release reasons. Peripheral payload bytes, names, addresses and credentials are not logged. Writes run through a bounded off-main queue; queue drops are explicit. Audit history is diagnostic only, is not imported into executable state, and is deleted with its workspace. Device-operation history can therefore be read even after the originating conversation ends.

`workspace_package_ui_test` exercises immutable candidate DOM controls in an isolated TEST presentation against a host-owned simulated BLE adapter. It accepts 1..32 `click`, `input`, `assert_text` or `wait_text` steps (each has `selector` and `value`; use empty value for click). Fixed fixture: `sim-device`, name `Simulated bench device`; characteristic `sim-char`, readable/writable/notifiable; read `2a`, notification `2b`. No live data, native permission grant or radio binding is supplied. A passing BLE report must exercise every declared operation, plus events when subscribe is declared. Activation requires both fresh pure-fixture evidence and a fresh UI simulation report for device packages. A simulation does not establish physical GATT behavior, native Android consent, painted layout or persistence. After activation, inspect the live revision and capture its visible output; do not present these evidence categories as interchangeable.

Late model-response accounting is settled once by host request ID even after its execution lease expires. This changes usage totals only: it never revives a task or executes late tool proposals. Inference reservation now reserves at least 2,048 output tokens instead of using a short spoken-response limit as the tool-output estimate.

### Alpha.22 verified release

Application commit `fbe9c30c2ad17fe10c0650ef332170a71e3dcc22` passed 283 Secure unit tests, lint and signed build in [Android run 38778883470](https://github.com/SEsquieu/atlas/actions/runs/38778883470). All eleven instrumented tests passed on API 35 and 36 in [device run 38780117530](https://github.com/SEsquieu/atlas/actions/runs/38780117530), test commit `4a7be7e7aa49d4b429f529a3cf09121646682a38`. The intervening test-only change leaves packaged application sources unchanged.

New coverage includes failed scan followed by repeated scans through the actual native consent UI (one approval, same broker through recomposition), bounded audit persistence/export without importing authority, non-BLE adapter execution through the shared runtime, exact-once late usage reconciliation, and candidate UI simulation rejecting missing notification draining while accepting the managed watcher. Existing contained browser, isolated worker, storage and rendered-pixel checks remain enabled. Physical radio behavior and Samsung permission/lifecycle behavior remain phone acceptance work.

Emulator test hardening clears stale accessibility cache entries and inspects interactive windows. The pixel test waits for two matching painted compositor frames instead of assuming a fixed delay proves painting. Its strict displayed-screen/viewport pixel equality, dark-gradient condition and authored-light checks are unchanged.

Delivered `atlas-alpha22-fbe9c30-secure.apk` (69,861,206 bytes), SHA-256 `345a3ec61e1c9a77b854e615d58183445a7b536ad85bcc4eeb6c941f47908e59`; persistent signing identity unchanged. The host update preserves installed workspace source and records. Bench Companion can now be repaired by Atlas using the stronger behavior checks; its source is not patched into the APK.

## Alpha.23: workspace inspection context recovery

Package inspection now references `workspace_package_contract` instead of embedding its full descriptor. The authoring contract publishes installed capabilities once under `capability_registry.capabilities`; the duplicate BLE descriptor was removed.

Before enforcing the existing conversation character budget, request assembly also recognizes older saved tool responses and removes those same duplicates. If the turn still exceeds the limit, large workspace inspection/contract/diagnostic/source read results become explicitly partial, bounded evidence with original call identity, character count and response hash. Tool-call/result pairs remain intact, original audit messages are preserved and mutation receipts are not reduced by this recovery path. Projected message IDs appear in `context.assembled`. This recovery runs deterministically on resume as well as the initial request.

The exported failing sequence contained 42,572 characters. Replaying duplicate removal against that sequence yields approximately 23,000 characters, below the unchanged 32,000 conversation limit. Regression tests cover older duplicate-bearing inspection batches, repeat assembly, protocol pairing and large diagnostics beside a failed activation receipt. Oversized user input and excessive message counts remain explicit local blockers rather than promises that an unchanged retry can resolve them.

Application commit `bd4631d313368c0045d357cae21a005feeaf343d` passed Secure unit tests and lint in [Android run 38797921299](https://github.com/SEsquieu/atlas/actions/runs/38797921299). All eleven instrumented tests passed on both API 35 and 36 in [device run 38797921127](https://github.com/SEsquieu/atlas/actions/runs/38797921127). This validates harness context recovery and existing host behavior; the Bench Companion repair still needs to run through Atlas and physical BLE interactions still need phone acceptance.

## Alpha.24: harness concurrency and durable app work

All Core write-transaction entry points now acquire the AtlasDatabase monitor before SQLite's writer connection. Execution bookkeeping, workspace data, package staging and device audit use this same synchronous transaction boundary. Database blocks cannot suspend or wait for presentation work. Workspace navigation dispatches its database write to IO before publishing the updated session, removing the observed main-thread transaction wait from that path. This addresses the lock-order inversion consistent with the c93c005d ANR; physical-device acceptance remains necessary.

Execution context reconstructs `work_state` from persisted operation receipts for the captured workspace target: exact latest candidate, base revision and parent candidate identifiers, changed file paths, latest pure/UI checks, failed steps with actual/expected values, missing operations and committed activation evidence. It uses the bounded full task history (up to 10,000 calls), not the 100-call UI projection. The checkpoint also stores the derived work state at slice boundaries. A restarted runner can reconstruct it before its next model request. Source remains in immutable candidates and the original audit.

`execution.progress` records whether the candidate/check/activation frontier changed and the number of slices without such a change. Reads are not counted as implementation progress. Repeated identical reads generate recovery guidance that asks the model to reuse pinned evidence and reassess the failure. This is advisory, not a new arbitrary slice or task limit, an automatic code edit, a grant of authority, or proof that the model will converge. Existing repeated deterministic failure and unknown-outcome guards remain.

An exact starter definition with no records is identified as an `empty_package_draft` in package inspection and workspace context. The model is instructed to stage its manifest and files in that workspace, rather than create a duplicate. Populated historical workspaces remain protected. Candidate-not-found errors include the exact rejected identifiers and an inspection recovery step; identifiers are never fuzzily substituted. Source reads publish range constraints and meaningful range errors.

Verification adds a competing writer/navigation lock-order test, restart evidence recovery after 104 unchanged reads, and draft classification with a record-protection check. The instrumented notification test now carries a failed simulated UI check through durable runner recovery, stages the correction against the retained parent, runs pure/UI checks, and activates the verified candidate. This is a controlled runner recovery test, not a live-model convergence benchmark or a physical Bluetooth test. Existing live-form record-preservation and containment tests remain enabled.

### Alpha.24 verified release

Application commit `3e0d7904c30f523d6bd061e77600e142ef6cb0e3` passed unit tests, lint and signing in [Android run 38824872352](https://github.com/SEsquieu/atlas/actions/runs/38824872352). Packaging required one retry after an invalid NDK download on the hosted runner. The persistent signing certificate SHA-256 remains `2ca9f44b3f730da52b9151d9a53a794c35605619ed011fccd98fd4ee3dcf079b`.

All eleven instrumented tests passed on API 35 and 36 in [device run 38825337253](https://github.com/SEsquieu/atlas/actions/runs/38825337253), test commit `e1bda8cb9dd34a52dc0ff131e0080bd6131c6a84`. The test-only correction persists fixture results through `updateToolCall`, matching runtime completion; application sources and Gradle configuration are unchanged from the signed application commit. That test commit also passed unit tests and lint in [Android run 38825337364](https://github.com/SEsquieu/atlas/actions/runs/38825337364).

Secure APK: `atlas-alpha24-3e0d790-secure.apk`, 69,893,974 bytes, SHA-256 `44f3f4b627b1a4a272839cb5ba32b74e0d43d5b8928125d0aad7d14d95885785`. Bench Companion source is unchanged by this host release.

## Alpha.25: workspace iteration recovery

Retained candidates used in a new task now participate in derived `work_state` without a new staging call. Exact test/read/activation identifiers select the candidate, and previously inspected metadata supplies its ancestry. Checks advance the progress frontier; repeated reads do not. Accepted clarification controls now have durable tool receipts. A corrected accepted control resolves an earlier rejected control with the same action and pending-question identity, retaining both attempts in the audit. Explicit workspace IDs take precedence over an unrelated displayed workspace when selecting the requested edit target.

Completed device-operation receipts retain their historical outcome after disconnect or revoke; pending work is cancelled and live handles still become unusable. Simulated UI checks distinguish attempted from successful operations. Activation requires a current verification-version-2 UI check: declared operations must succeed, and subscriptions require notification delivery plus a passed DOM assertion containing fixture payload `2b`. Generic subscription status text alone cannot satisfy this gate. Older stored UI checks must be rerun; existing live revisions are unchanged.

Workspace foreground consent is held in memory outside the disposable presentation. Its identity includes realm, workspace, revision, epoch and declared capability operations. Navigation closes connections and discards peripheral handles while preserving workspace consent and native denial state. Activity stop clears grants even when the workspace panel is absent. Revision/epoch/contract changes, OS permission loss and explicit revoke still end consent. Process restart never restores authority. This is not a persistent permission grant or background execution.

### Alpha.25 verification

Final application source commit `daf141f42c3dde163c8f1e74a709fb7f294b5011` passed the 292-test unit suite and Secure lint in [Android run 38981645874](https://github.com/SEsquieu/atlas/actions/runs/38981645874). All eleven instrumented tests passed on API 35 and 36 in [device run 38981645885](https://github.com/SEsquieu/atlas/actions/runs/38981645885). The extended real-panel regression caught a second revocation path in WebView disposal; that path is included in the verified correction. The production runner regression covers rejected clarification, accepted correction, user reply and clean task completion. Candidate recovery, completed-receipt integrity and rejection of legacy UI checks are covered without relaxing unknown-outcome guards. These are controlled tests, not a live-model convergence or physical-radio benchmark.

### Alpha.25 delivered Secure build

Release commit `64a530956d075ba2c83b7f402c4cc179c60e439d` sets Android version name `0.1.0-alpha.25`; [Android run 38982781747](https://github.com/SEsquieu/atlas/actions/runs/38982781747) passed unit tests, lint and persistent signing. The packaged manifest is `com.grinningfrog.atlas`, version code `1211`. All eleven instrumented tests also passed on both API 35 and 36 for this exact release commit in [device run 38982781778](https://github.com/SEsquieu/atlas/actions/runs/38982781778).

The single Secure APK is `atlas-alpha25-64a5309-secure.apk`, 69,926,742 bytes, SHA-256 `de0f3b50e4f26080dd5addb639077948396d788674d74b0acb19587247e6f411`. Its signing certificate SHA-256 is unchanged: `2ca9f44b3f730da52b9151d9a53a794c35605619ed011fccd98fd4ee3dcf079b`. Upload metadata was read back from App Builds. Install as an update without clearing data; physical BLE behavior and live-model iteration remain phone acceptance work.

## Alpha.26: verification recovery and delivery clarity

Workspace targeting can declare a completion requirement: activation, draft or inspect. An activation requirement keeps the task runnable until a matching candidate activation receipt exists; passing checks alone are insufficient. It cannot be weakened within the same task and grants no permission. Draft/review tasks can finish without activation. Older callers that omit delivery retain compatibility.

A model response following a failed local candidate test is treated as a progress report: the execution remains READY and continues on the same task. This is limited to package pure/UI/validation checks; actual permission/clarification waits, unknown operation outcomes, other tool failures and task allowances still stop execution. Existing repeated-failure protection remains, with verification signatures excluding fresh check IDs and diagnostic UI snapshots so unchanged retries cannot appear to progress merely through new receipts. This does not infer permission to activate drafts or promise live-model convergence.

Derived work state now includes a bounded inventory of source ranges already read, whether the package contract was fetched, and delivery status. Reinspection is still available when evidence was omitted or changed. Descriptions remain user metadata; installed contracts and manifests define runtime capabilities. Task cards distinguish staged candidates, failed verification and committed activation. The workspace itself displays its live revision prefix outside the authored page.

UI simulation supports select, value and visibility assertions; input/select dispatch both input and change events. Failed steps include bounded control state, disabled flags and labels to make prerequisite mistakes actionable. Existing successful-operation and notification-payload requirements remain. These primitives improve test construction but do not automatically establish application correctness.

Foreground consent expiry emits durable workspace audit evidence even when the panel is absent when the activity stops. This adds observability without extending authority or keeping device connections alive. No workspace app source is manually changed by this release.

### Alpha.26 verification

Application source `1bc005f044cb26b9e25db75e386759d26a24d12c` passed unit tests and Secure lint in [Android run 38987612736](https://github.com/SEsquieu/atlas/actions/runs/38987612736). The controlled production-runner regression deliberately reports an initial failed check and then reports again after a passing check without activation; both responses continue the same task, which completes only after real candidate activation. Separate coverage retains explicit user waits and the repeated-failure guard with fresh check IDs. Draft/inspection requirements can complete without activation, while activation requirements cannot be directly weakened for the same target.

The form/UI primitive and navigation/audit regressions passed all twelve instrumented tests on API 35 and 36 in [initial device run 38987030166](https://github.com/SEsquieu/atlas/actions/runs/38987030166). The subsequent completion-requirement source passed API 35; its API 36 job failed before tests when the hosted runner downloaded an invalid NDK archive. The final source passed both APIs in the run recorded below. Derived candidate status now reconciles staging metadata with later check/activation receipts rather than preserving stale staged execution flags.

### Alpha.26 delivered Secure build

Final source `1bc005f044cb26b9e25db75e386759d26a24d12c` passed all twelve instrumented tests on both API 35 and 36 in [device run 38987612746](https://github.com/SEsquieu/atlas/actions/runs/38987612746). Package commit `860bc9f86bbe1f43b4b9255be8aa127a126d79a3` changes only release metadata/docs from that tested source and passed unit tests, lint and persistent signing in [Android run 38988035298](https://github.com/SEsquieu/atlas/actions/runs/38988035298).

Secure APK `atlas-alpha26-860bc9f-secure.apk`: 69,943,126 bytes; SHA-256 `621b9ce74272cf0d5a327c3f53144058b5bc059a1f89dcb2079809171b785a09`. The packaged manifest is `com.grinningfrog.atlas`, version `0.1.0-alpha.26`, code `1215`. Signing certificate SHA-256 remains `2ca9f44b3f730da52b9151d9a53a794c35605619ed011fccd98fd4ee3dcf079b`. App Builds upload name and size were read back. Install over alpha.25 without clearing data, then continue the retained staged diagnostic-log candidate. Native revision/delivery status and exported activation receipts are the confirmation, not a model's prose alone. Physical BLE and live-model convergence remain acceptance work.

## Alpha.27 task closure and requested-behavior verification

An unavailable workspace screenshot (`WORKSPACE_NOT_OPEN`) after a durable activation matching the current live revision is diagnostic evidence, not failed delivery. The failed receipt remains intact and is listed under diagnostic operations. This exception does not cover stale captures, unknown outcomes, physical operations or failed activation. Declare `require_pixels=true` in `workspace_target` when painted-pixel inspection is required; it remains blocking and cannot be silently weakened by rebinding. An explicit user instruction to take visual verification over can be recorded with `atlas_task_handoff_pixels`: Core checks an exact quote from the latest user message in the task and saves its message/turn provenance. This marks pixels user-owned and unverified; it does not waive execution, acceptance, activation or consent gates. Resume reconciles an old optional-inspection wait against current live evidence before starting another inference slice.

Conversational follow-ups attach to the single unfinished task when it is waiting on an unresolved/repeated tool failure. Multiple unfinished tasks, permission/provider waits and unknown outcomes do not receive implicit steering. The original objective, target, receipts and follow-up turn IDs survive; tangents do not erase failures. Context supplies exact outstanding operations and bounded recent task status, so historical physical errors need not be mistaken for the current blocker.

Declare up to 16 `acceptance_criteria` strings on `workspace_target`. They accumulate for that target rather than disappearing on a later bind. UI assertion steps can include an exact `criterion` label. Only a fully passing simulation contributes `verified_criteria`; click/input steps cannot claim criteria. The activation adapter requires coverage of declared criteria on the exact candidate, independently of baseline BLE operation coverage. These are model-authored assertions, not independent proof that the criteria capture the user's intent or that painted pixels, physical effects or record persistence work. Legacy callers without declared criteria remain supported.

Activation now names the required `pure_fixtures` check kind. A wrong check ID returns the rejected ID and existing valid pure-check IDs bound to candidate/hash/base/data version, allowing correction without rerunning passing tests. UI checks remain a separate gate. No workspace source or phone records are manually repaired.

### Alpha.27 live evidence and release qualification

The motivating session was `atlas-session-7775256e.zip`, uploaded October 17 at 14:11:44 UTC. It activated the retained diagnostic-log candidate as revision `592994d2-31c2-4a30-8e32-90ff81c443f2` after correcting two UI test sequences. The outstanding failure was unavailable pixel capture, not the historical physical BLE connection error. Subsequent user-triggered resumes repeated completion prose without new tool progress. This release addresses those harness paths; it does not manually alter that workspace or establish physical connection/notification behavior.

Application source `05a9160298bd61a6fc808a980a8de97898b2c0f4` passed unit tests and Secure lint in [Android run 39041134658](https://github.com/SEsquieu/atlas/actions/runs/39041134658). Controlled tests cover optional versus required capture, explicit visual handoff with failed execution checks retained, ambiguous/multiple task selection, candidate-specific acceptance criteria, reusable pure-check IDs, and reconciliation without another inference slice. Resume database work dispatches to IO and serializes with runtime operations.

The same source passed all twelve instrumented tests on both Android API 35 and 36 in [device run 39041134604](https://github.com/SEsquieu/atlas/actions/runs/39041134604). Criterion receipts are verified alongside actual DOM filter visibility. These controlled checks do not establish live-model convergence, physical BLE behavior or every possible ANR's absence.

### Alpha.27 delivered Secure build

Package commit `f1741b0a90b122be97f455b06c089105d97e1bba` changes only release metadata/docs from the tested source. [Android run 39041830469](https://github.com/SEsquieu/atlas/actions/runs/39041830469) passed unit tests, lint and persistent signing. The installable artifact contains exactly one Secure APK.

[Secure APK: atlas-alpha27-f1741b0-secure.apk](https://drive.google.com/file/d/1OAt3xNIS94jQ5Zx33qHoR_XEJNVqh7hV/view?usp=drivesdk): 69,975,894 bytes; SHA-256 `be0742c3fca39c7355bda9b2b1f84bc661ee6880a24bffcdf8dec55320ae143f`. Binary manifest verified: package `com.grinningfrog.atlas`, version `0.1.0-alpha.27`, code `1221`. Certificate SHA-256 remains `2ca9f44b3f730da52b9151d9a53a794c35605619ed011fccd98fd4ee3dcf079b`; CI signing verification and the downloaded APK signing block agree. App Builds upload filename and size were read back. Install over alpha.26 without clearing data. A fresh workspace demo and live acceptance follow installation; no new demo workspace was created as part of this release.

## Alpha.28 authoring and storage verification

The Dinner Shuffle session `04849608` exposed an invalid `data.query limit:200`, invisible to storage-disabled UI tests. Candidate UI simulation now uses a disposable in-memory SQLite database through the real host validation, prepare/commit and read APIs. TEST-mode storage is accepted only by an in-memory database; no live phone records are cloned or modified. A reload recreates the WebView with the same test database. `verify_storage=true` requires a commit, reload and an exact `assert_record {collection,data}` after reload. Declare `require_persistence=true` in workspace_target or workspace_package_create when saved state is required; activation requires a passing matching candidate's isolated persistence evidence. This proves isolated save/reload behavior, not process restart or modification of actual user records.

Host-call diagnostics record bounded method/status/error details without payloads. A caught host failure still fails a UI check, preventing fallback content from hiding a broken data path. The authoring contract advertises query bounds 1..100 and pagination; validation feedback retains field/range details. UI steps add assert_absent, assert_count, set_checked, reload and assert_record. Action-only steps need no empty value. Optional random_values controls Math.random only in TEST mode and restarts at page reload; it is not available in live presentations. Criterion labels remain assertion-only and do not independently establish semantic correctness.

Creation retries recover an exact-name, session-owned unactivated empty draft from durable creation receipts. Multiple matches require explicit selection; new_draft=true is reserved for a separately requested app. Existing live apps, other sessions and drafts containing records are excluded. No old workspace is deleted or retargeted by installation. Identical staged bytes retain the candidate ID and explicitly report no source change. The list labels empty starter workspaces as drafts. Staging argument errors identify manifest versus files nesting, and known pre-effect validation errors can continue the existing recovery loop with the existing repeated-failure guard.

For declared durable authoring tasks, a 60-second interaction deadline reports continued generation while retaining the original provider request and lease. Cancellation/lease checks remain; ordinary conversational timeout behavior is unchanged. User-reported live errors are current evidence, not something a preview result can disprove. Viewing/status requests should declare inspect, not activation.

### Alpha.28 qualification

Final application source `43dfbed2b5fe1ea4e79f9027c6119cfc45dec71d` passed unit tests and Secure lint in [Android run 39051664155](https://github.com/SEsquieu/atlas/actions/runs/39051664155). Regression coverage includes session-owned draft reuse across interrupted tasks, unchanged-candidate reuse, rejection of TEST-mode access to the real database, exact query bounds, persistence requirements that DOM-only checks cannot satisfy, empty-draft inspection recovery, and a 71-second authoring response retaining its original request. A diagnostic assertion failure must not generate an application-console error.

The same final source passed all thirteen instrumented tests on both Android API 35 and 36 in [device run 39051664133](https://github.com/SEsquieu/atlas/actions/runs/39051664133). The new test reproduces a caught invalid query that renders fallback content yet still fails verification, then verifies real isolated prepare/commit, page reload, restored DOM and exact stored record, fresh-dataset isolation and clean assertion diagnostics. Existing containment, BLE notification and record-preservation coverage remains enabled. These controlled tests do not prove live-model convergence or phone-process restart behavior.

### Alpha.28 delivered Secure build

Package commit `4dc97f952576fc3f4d68a311af20d23fb70e9c94` changes only release metadata/docs from the tested source. [Android run 39052437693](https://github.com/SEsquieu/atlas/actions/runs/39052437693) passed unit tests, Secure lint and persistent signing. The installable artifact contains exactly one Secure APK.

[Secure APK: atlas-alpha28-4dc97f9-secure.apk](https://drive.google.com/file/d/1U_5mp1nm6NmvMOgAuZBdr0K-KuGbNe4t/view?usp=drivesdk): 70,008,662 bytes; SHA-256 `b49ff57759af97ba4306d307e97e03e3a0e4ab221fc8645bc3a56246dc1eace2`. Binary manifest verified: package `com.grinningfrog.atlas`, version `0.1.0-alpha.28`, code `1228`. Certificate SHA-256 remains `2ca9f44b3f730da52b9151d9a53a794c35605619ed011fccd98fd4ee3dcf079b`; CI signing verification and the downloaded signing block agree. App Builds upload filename and size were read back. Install over alpha.27 without clearing data. Existing workspaces and their app source were not repaired or removed; Seth will delete the old demo workspaces after updating and rerun fresh live acceptance.
