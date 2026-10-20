# Current Atlas implementation status

Source baseline: **0.1.0-alpha.29** (release preparation; last delivered phone baseline alpha.28), Android database version 18. This page summarizes current behavior; design documents and release chronology retain earlier decisions.

## Available in the Android alpha

- Native foreground sessions with voice/text, camera context, durable evidence, and replaceable inference providers.
- Durable execution tasks that checkpoint and continue automatically across bounded slices. Clarification, authorization, ambiguous effects, repeated failures, and resource ceilings can stop progression. See [execution semantics](../apps/atlas-android/DURABLE_EXECUTION.md).
- Contained HTML/CSS/JavaScript workspace packages, immutable candidates, controlled activation, scoped records, and isolated candidate checks. The host owns native authority; app code cannot grant itself Android permissions.
- Host-mediated BLE with workspace consent and OS permission checks. Simulated BLE checks do not prove physical peripheral interoperability.
- Isolated save/reload verification through the real storage APIs. Persistence-required activation needs matching candidate evidence; DOM-only checks are insufficient.
- Session-owned empty draft recovery during authoring retries, unchanged-candidate reuse, and bounded host-failure diagnostics.
- Session and workspace export/import. Imports create copies and do not restore executable authority. See [recovery behavior](./import-export.md).

The app supports Android 9+, but isolated workspace JavaScript execution requires Android 10+. Secure is the standard delivery variant; LAN and DEV builds are targeted opt-ins.

## Alpha.29 changes

See [release notes and phone acceptance](./releases/v0.1.0-alpha.29.md) and [manual acceptance](./manual-acceptance.md). Churn, completion, candidate acceptance and pointer/assertion changes are implemented for automated qualification; pointer tests explicitly use synthetic DOM events with test-only capture, not native or physical touch; live convergence and data-preserving follow-up edits remain unverified until the next phone cycle.

## Alpha.28 verification baseline

The implementation chronology records these alpha.28 checks:

| Scope | Evidence |
| --- | --- |
| Final application source unit tests and Secure lint | [Android run 39051664155](https://github.com/SEsquieu/atlas/actions/runs/39051664155) |
| Thirteen instrumented tests on each of API 35 and 36 | [Device run 39051664133](https://github.com/SEsquieu/atlas/actions/runs/39051664133) |
| Packaged alpha.28 tests, lint, and persistent signing | [Android run 39052437693](https://github.com/SEsquieu/atlas/actions/runs/39052437693) |

Final application source: `43dfbed2b5fe1ea4e79f9027c6119cfc45dec71d`. Package commit: `4dc97f952576fc3f4d68a311af20d23fb70e9c94`. These are recorded qualification results, not new checks performed by this documentation update.

Controlled tests do not establish live-model convergence, hours-long phone endurance, every process-restart path, or physical BLE connection/notification behavior. The October 19 live Lunchbox Lottery test activated one app and passed isolated save/reload verification, but the task remained waiting after failed optional visual handoffs. Full acceptance is therefore incomplete. Generated apps can still require correction. See the [alpha.28 live assessment and next work](./alpha28-live-assessment.md).

## Distribution and repository use

Build instructions are in [Build and test](./build-and-test.md) and the [Android guide](../apps/atlas-android/README.md). Signed alpha delivery is recorded in the [application-host chronology](./workspace-application-host.md); it is distinct from a production release or a guaranteed public GitHub Release download. CI installable debug builds use a persistent signing identity. Ordinary local debug builds may use a different key and cannot necessarily update an installed alpha.

Use this page and the documentation index as current entry points. Earlier declarative workspace designs and the application-host checkpoint narrative are retained for history. OpenClaw examples remain legacy adapters, not required setup.
