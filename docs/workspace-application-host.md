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

No live package renderer, worker, activation endpoint, permission-grant UI or automatic conversion is enabled by the foundation. Unused experimental substrates must not be left in production after selection.

## Validation and delivery

Run `testSecureDebugUnitTest` and `lintSecureDebug`; no `assemble` task is required for implementation validation. Android workflow commits containing `[validation-only]` skip the installable job while retaining tests/lint. Manual dispatch exposes `installable:false`. Final delivery removes the validation-only marker and increments the alpha version only when the runtime is ready for user testing.

New independent tests cover package bounds/paths/schema/dependency hashes, forged request authority, grant isolation/expansion/revocation, stale revision epochs, immutable candidate branching/reopening, target changes, batch rollback, archive/delete and migration from alpha.17. Existing native/task/UI/provider suites remain mandatory.

## Runtime evaluation checkpoint

The current `dokar3/quickjs-kt` release v1.0.15 supports module loading, memory/stack limits and cancellation, but its pinned source targets Kotlin 2.4.10; Atlas currently uses Kotlin 2.1.20. Earlier v1.0.5 already targets Kotlin 2.3.20 and lacks the later interruption fixes. Do not silently pin an older runtime or disable Kotlin metadata checks to avoid a compiler migration. Evaluate an explicit compiler upgrade with full baseline tests versus a maintained Java/JNI binding or a minimal pinned native integration before selecting the worker. `OpenQuickJS/quickjs-android` exposes allocator, stack and interrupt hooks but its inspected Android packaging is ARM-only and release-AAR based; ABI/testability/update-path evaluation remains necessary. No engine dependency has been installed yet.

Primary evaluation sources: [QuickJS Kotlin v1.0.15](https://github.com/dokar3/quickjs-kt/tree/v1.0.15), [OpenQuickJS binding](https://github.com/OpenQuickJS/quickjs-android).
