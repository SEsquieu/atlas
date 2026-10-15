# Workspace application host: implementation status and decisions

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
