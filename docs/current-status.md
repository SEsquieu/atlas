# Current Atlas implementation status

Source baseline: **0.1.0-alpha.36**, Android database version 18. Alpha.36 adds optional autonomous workspace visual testing through private-display rendering, actual device profiles, persistent isolated interaction and candidate-bound evidence. Merged in [PR #76](https://github.com/SEsquieu/atlas/pull/76); 28 tests each on API35/36 and unit/lint passed. Physical-device acceptance remains outstanding. See [alpha.36 qualification and delivery](./releases/v0.1.0-alpha.36.md). Native execution remains the default. This page describes current behavior; earlier release notes retain historical qualification evidence.

## Available in the Android alpha

- Native foreground sessions with voice/text, camera context, durable evidence, and replaceable inference providers.
- Durable execution tasks that checkpoint and continue automatically across bounded slices. Clarification, authorization, ambiguous effects, repeated failures, and resource ceilings can stop progression. See [execution semantics](../apps/atlas-android/DURABLE_EXECUTION.md).
- Contained HTML/CSS/JavaScript workspace packages, immutable candidates, controlled activation, scoped records, and isolated candidate checks. The host owns native authority; app code cannot grant itself Android permissions.
- Optional [autonomous visual workspace testing](./workspace-visual-testing.md): private compositor rendering while Session remains visible, real screenshots, bounded geometry, fixed interactions and optional verification receipts. The model chooses testing by need; no universal activation gate is added.
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
| Application unit tests and lint | [Android run 41121101474](https://github.com/SEsquieu/atlas/actions/runs/41121101474) |
| Twenty-eight tests each on API35/36; zero failures/errors/skips | [Device run 41121101520](https://github.com/SEsquieu/atlas/actions/runs/41121101520) |
| Signed main build | [Manual build 41122817446](https://github.com/SEsquieu/atlas/actions/runs/41122817446) — see the delivery record for completion/distribution |
| Source boundary and history checks | [Public-readiness run 41121101467](https://github.com/SEsquieu/atlas/actions/runs/41121101467) |

Qualified head `72dd346c0807fbc3ae0e4c689556bfa10aa63c5f`; merged application source `b1b45267b2c04ef7f811b7d765324b8f724538fc`. Both artifact ZIP digests and JUnit reports were verified. Coverage includes actual compositor/canvas pixels, native input, device-aware layout, storage/mock BLE isolation, cancellation, process death, foreground renderer survival, cross-slice agent-tool state, screenshot observations and matching activation evidence. See [alpha.36 qualification](./releases/v0.1.0-alpha.36.md). Earlier release notes retain prior qualification records.

## Phone evidence and limits

Alpha.36 requires Galaxy S22 qualification of private-display compositing, canvas fidelity, input/focus isolation, lifecycle recovery and sustained resource use. Per-open calibration and emulator tests are evidence about the tested path, not a substitute for that acceptance. See the [real-device plan](./workspace-visual-testing.md#qualification-and-real-device-acceptance).

Alpha.34 completed MazeGen activation in its original workspace and finished the task, but took 428.852 seconds, 46 model steps and 51 tool calls. Twenty source reads and seven compactions showed remaining churn. The passing UI test labeled status/count assertions as repeatability/preservation without comparing maze geometry. A separate runtime proposal passed isolated probes and correctly remained unselected; no activation/approval acceptance was exercised. All 63 usage reports showed Medium requested/served. See [the alpha.34 assessment](./alpha34-live-assessment.md).

Alpha.35 supplies stronger host evidence primitives, input diagnostics and bounded durable source recovery. Its phone acceptance, token/latency impact and the pending runtime proposal's behavioral benefit remain unmeasured. DOM comparison excludes painted pixels, computed styles and JavaScript-only state. Automated checks do not establish live-model convergence, hours-long endurance, every recovery path, physical BLE interoperability or general semantic correctness. The replaceable runtime remains a partial migration under #65.

## Distribution and repository use

Build instructions are in [Build and test](./build-and-test.md) and the [Android guide](../apps/atlas-android/README.md). Signed alpha delivery is recorded in the [application-host chronology](./workspace-application-host.md); it is distinct from a production release or a guaranteed public GitHub Release download. CI installable debug builds use a persistent signing identity. Ordinary local debug builds may use a different key and cannot necessarily update an installed alpha.

Use this page and the documentation index as current entry points. Earlier declarative workspace designs and the application-host checkpoint narrative are retained for history. OpenClaw examples remain legacy adapters, not required setup.
