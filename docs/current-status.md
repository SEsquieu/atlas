# Current Atlas implementation status

Source baseline: **0.1.0-alpha.33**, Android database version 18. Alpha.33 adds an experimental, opt-in replaceable orchestration bridge. Native execution remains the default. This page describes current behavior; earlier release notes retain historical qualification evidence.

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

External bundles require an independently trusted publisher public key and native activation confirmation. There is no remote feed, automatic issue submission, GitHub credential, coding executor or autonomous runtime activation in this build. A failed pinned runtime cannot be silently replaced; cancel the task and start a new task on Native if necessary. See [architecture, update behavior and remaining gates](./replaceable-runtime.md). [Milestone #65](https://github.com/SEsquieu/atlas/issues/65) remains open.

## Current automated qualification

| Scope | Evidence |
| --- | --- |
| Final alpha.33 application unit tests and lint | [Android run 39713932019](https://github.com/SEsquieu/atlas/actions/runs/39713932019) |
| Core build, typecheck and tests | [Core run 39713932023](https://github.com/SEsquieu/atlas/actions/runs/39713932023) |
| Eighteen instrumented tests each on API35/36; zero failures/errors/skips | [Device run 39713932151](https://github.com/SEsquieu/atlas/actions/runs/39713932151) |
| Signed APK, persistent signing and package checks | [Manual build 39714968508](https://github.com/SEsquieu/atlas/actions/runs/39714968508) |
| Source boundary and history checks | [Public-readiness run 39713932059](https://github.com/SEsquieu/atlas/actions/runs/39713932059) |

Qualified PR source: `6e9ad1d74e9b79b633e51455c66affb39bd29267`. Merged application source: `ac5d1b3e0a779423b644a159d445e224162bf865`. Bridge tests cover concurrent request isolation, runaway cancellation/recovery and overlapping service ownership. Forty warm samples per emulator measured p95 2.348ms on API35 and 2.921ms on API36, excluding mutex queue wait. These small bridge inputs do not establish full-context migration costs, fresh-boot behavior, phone responsiveness or model-turn latency.

## Phone evidence and limits

Alpha.31's MazeGen updates activated successfully on the original workspace with passing pure and UI checks, but its follow-up made six stage attempts and five valid candidates before testing, with substantial provider/output churn. Alpha.32 implemented exact source edits, truthful UI activation receipts, hosted-search evidence and explicit ChatGPT reasoning effort. Alpha.32 and alpha.33 phone testing remain pending. See [alpha.32](./releases/v0.1.0-alpha.32.md), [alpha.33](./releases/v0.1.0-alpha.33.md), and [active fixes](./active-fixes.md).

Automated checks do not establish live-model convergence, hours-long phone endurance, every process-restart path, physical BLE interoperability or general semantic correctness. The alpha.33 bridge is the first independently replaceable policy surface, not the complete orchestration migration.

## Distribution and repository use

Build instructions are in [Build and test](./build-and-test.md) and the [Android guide](../apps/atlas-android/README.md). Signed alpha delivery is recorded in the [application-host chronology](./workspace-application-host.md); it is distinct from a production release or a guaranteed public GitHub Release download. CI installable debug builds use a persistent signing identity. Ordinary local debug builds may use a different key and cannot necessarily update an installed alpha.

Use this page and the documentation index as current entry points. Earlier declarative workspace designs and the application-host checkpoint narrative are retained for history. OpenClaw examples remain legacy adapters, not required setup.
