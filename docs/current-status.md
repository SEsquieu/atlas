# Current Atlas implementation status

Source baseline: **0.1.0-alpha.35**, Android database version 18. Alpha.35 adds bounded DOM comparison evidence, preflight count validation, HTML input diagnostics and durable task source retention across compaction. Native execution remains the default. This page describes current behavior; earlier release notes retain historical qualification evidence.

## Available in the Android alpha

- Native foreground sessions with voice/text, camera context, durable evidence, and replaceable inference providers.
- Durable execution tasks that checkpoint and continue automatically across bounded slices. Clarification, authorization, ambiguous effects, repeated failures, and resource ceilings can stop progression. See [execution semantics](../apps/atlas-android/DURABLE_EXECUTION.md).
- Contained HTML/CSS/JavaScript workspace packages, immutable candidates, controlled activation, scoped records, and isolated candidate checks. The host owns native authority; app code cannot grant itself Android permissions.
- Host-mediated BLE with workspace consent and OS permission checks. Simulated BLE checks do not prove physical peripheral interoperability.
- Isolated save/reload verification through the real storage APIs. Persistence-required activation needs matching candidate evidence; DOM-only checks are insufficient.
- Session-owned empty draft recovery during authoring retries, unchanged-candidate reuse, and bounded host-failure diagnostics.
- Session and workspace export/import. Imports create copies and do not restore executable authority. See [recovery behavior](./import-export.md).

The app supports Android 9+, but isolated workspace JavaScript execution requires Android 10+. Secure is the standard delivery variant; LAN and DEV builds are targeted opt-ins.

## Replaceable-runtime milestone

The first slice is merged in [PR #66](https://github.com/SEsquieu/atlas/pull/66). Data & activity offers Native, Bundled core and signed-runtime import. The portable core supplies bounded orchestration guidance and infer/yield decisions. The host retains context projection, response handling, provider transport/streaming, execution leases, permissions and tool effects. Tasks pin an immutable runtime artifact at creation; changing defaults or importing an update does not change an existing task. Legacy tasks stay native.

External bundles require an independently trusted publisher public key and native activation confirmation. The model can inspect runtime status, read installed JavaScript source, propose exact source edits and request native-confirmed selection after isolated contract probes. Editing schemas are advertised only after status inspection. This does not edit Kotlin or migrate an active task. There is no remote feed, automatic issue submission, GitHub credential, coding executor or autonomous runtime activation in this build. A failed pinned runtime cannot be silently replaced; cancel the task and start a new task on Native if necessary. See [architecture, update behavior and remaining gates](./replaceable-runtime.md). [Milestone #65](https://github.com/SEsquieu/atlas/issues/65) remains open.

## Current automated qualification

| Scope | Evidence |
| --- | --- |
| Application unit tests and lint | [Android run 39800363335](https://github.com/SEsquieu/atlas/actions/runs/39800363335) |
| Twenty-one tests each on API35/36; zero failures/errors/skips | [Device run 39800362558](https://github.com/SEsquieu/atlas/actions/runs/39800362558) |
| Signed main APK, package and persistent signing checks | [Manual build 39801698998](https://github.com/SEsquieu/atlas/actions/runs/39801698998) |
| Source boundary and history checks | [Public-readiness run 39800363273](https://github.com/SEsquieu/atlas/actions/runs/39800363273) |

Qualified head `40ef677a7a5a15c616486e942e3ec5c9e902202f`; merged application source `f120ef0eba5b025ce3d1297e3aa308c6997af19f`. Device artifact ZIP digests and JUnit reports were verified. The new real WebView regression checks same-seed equality, changed geometry despite an unchanged count, rejected-input preservation, comparison across reload, range clamping/number sanitization, and count rejection before candidate lookup. Source retention tests cover scope, changed/forged bytes, offsets, duplicate suppression, durable operation recovery and escaped-JSON budgets. See [alpha.35 qualification and delivery](./releases/v0.1.0-alpha.35.md).

## Phone evidence and limits

Alpha.34 completed MazeGen activation in its original workspace and finished the task, but took 428.852 seconds, 46 model steps and 51 tool calls. Twenty source reads and seven compactions showed remaining churn. The passing UI test labeled status/count assertions as repeatability/preservation without comparing maze geometry. A separate runtime proposal passed isolated probes and correctly remained unselected; no activation/approval acceptance was exercised. All 63 usage reports showed Medium requested/served. See [the alpha.34 assessment](./alpha34-live-assessment.md).

Alpha.35 supplies stronger host evidence primitives, input diagnostics and bounded durable source recovery. Its phone acceptance, token/latency impact and the pending runtime proposal's behavioral benefit remain unmeasured. DOM comparison excludes painted pixels, computed styles and JavaScript-only state. Automated checks do not establish live-model convergence, hours-long endurance, every recovery path, physical BLE interoperability or general semantic correctness. The replaceable runtime remains a partial migration under #65.

## Distribution and repository use

Build instructions are in [Build and test](./build-and-test.md) and the [Android guide](../apps/atlas-android/README.md). Signed alpha delivery is recorded in the [application-host chronology](./workspace-application-host.md); it is distinct from a production release or a guaranteed public GitHub Release download. CI installable debug builds use a persistent signing identity. Ordinary local debug builds may use a different key and cannot necessarily update an installed alpha.

Use this page and the documentation index as current entry points. Earlier declarative workspace designs and the application-host checkpoint narrative are retained for history. OpenClaw examples remain legacy adapters, not required setup.
