# Changelog

Notable user-visible changes will be recorded here. Atlas follows semantic versioning beginning with its first tagged alpha.

## Unreleased

## 0.1.0-alpha.35 — delivered test build

- Signed main build 39801698998 from f120ef0, version code 1355; APK/provenance/signing identity checked and delivered to App Builds.

- Reject malformed exact counts before candidate execution, with indexed recovery; accept bounded integers and historical decimal strings.
- Report actual HTML input values and constraints; add bounded named DOM equality/inequality comparisons across actions and reloads.
- Recover already-read, hash-validated task source across compaction within explicit context budgets. Source grants no execution authority; telemetry records retainedSourceCharacters.
- Unit/lint, public source/history and 21 tests each on API35/36 passed. See [qualification, limits and phone cycle](./docs/releases/v0.1.0-alpha.35.md).

## 0.1.0-alpha.34 — delivered test build

- Signed main build 39727994082 from ad5587c, version code 1352. APK/provenance verified and delivered to App Builds with the persistent signing identity.

- Add local JavaScript runtime inspection, exact source proposals, isolated qualification and native-confirmed selection for new tasks. Existing task pins remain unchanged; native code and repository automation remain outside this surface.
- Return candidate lifecycle and exact source ancestry, preserve installed targets during recovery, and let resumed installed local work reach native confirmation after discovery.
- Retire narrowly matched obsolete pre-execution failures after successful delivery; unknown outcomes remain blocking.
- Unit/lint, public readiness and 20 device tests each on API35/36 passed. See [release evidence and phone test cycle](./docs/releases/v0.1.0-alpha.34.md).

## 0.1.0-alpha.33 — delivered test build

- Introduce an opt-in isolated JavaScript orchestration bridge, signed imports, immutable artifacts and durable task pins. Native remains the default; full orchestration extraction and development-service integration remain open in #65.
- Signed main build 39714968508, source ac5d1b3, version code 1342. See [qualification and delivery](./docs/releases/v0.1.0-alpha.33.md).

## 0.1.0-alpha.32 — delivered test build

- Signed main build 39673414889 from source 1466bea, version code 1333; APK/provenance verified and delivered to App Builds. Unit/lint, public readiness and API 35/36 checks passed.

- Add a separate ChatGPT reasoning-effort selector (Provider default / Low / Medium / High) and export requested/reported effort, served model, reasoning tokens and cached input counts when supplied. Missing telemetry remains unknown.

- Preserve passing non-BLE UI checks in activation evidence, including criterion and storage scope.
- Support atomic hash/preimage-checked source edits and expose pending candidate verification across slices.
- Describe actual provider-hosted research availability separately from local adapters.

## 0.1.0-alpha.31 — delivered test build

- Signed build 39534360360 from main source f7e3874, version code 1326. Unit/lint, public readiness and API 35/36 device verification passed. APK/provenance delivered to App Builds.
- Bind omitted workspace/candidate/navigation identifiers from the durable task; preserve explicit conflicts and existing freshness checks.
- Bind manual acceptance to the actual user message before native confirmation; reject a changed instruction at execution.
- Advertise action-specific required values and structured drag/attribute/record schemas.
- Send byte-identical pinned source reads once per packet and remove duplicate read bodies from task receipts; preserve the original audit.

- Keep draft editing available during active work and allow replies during user-action blockers.
- Retire resolved approval turns and stop obsolete failed acceptance attempts blocking exact delivered candidates.
- Return explicit expected/actual UI assertion counts.
- Advertise separate enumerated action/assertion schemas and accept native structured test values while retaining historical string compatibility.
- Deduplicate validation and isolate manual APK workflows from superseded validation cancellation.
- Includes the merged blocker-input, obsolete-acceptance, count-evidence and CI scheduling fixes since alpha.30. Build delivery and phone verification are recorded separately. Live-model latency/token comparisons remain pending; no measured live speedup is claimed.

## 0.1.0-alpha.30 — delivered test build

- Bound UI execution and record the stalled step; retire stuck renderers before opening replacement surfaces.
- Suppress repeated execution of identical timed-out application content and retain terminal cancellation evidence.
- Signed build 39522593617, source 7b77046, version code 1302. Device tests passed on API 35/36; phone testing confirmed MazeGen's visible solution and working existing workspaces.

## 0.1.0-alpha.29 — release preparation

- Recover misplaced source fields and bound identical deterministic retries.
- Complete delivered tasks despite unavailable optional inspection; distinguish camera and UI evidence.
- Add native-confirmed candidate acceptance for manual testing, with uncertainty retained.
- Exercise SVG dragging/cancellation and exact attribute/text acceptance evidence.
- Device and live qualification tracked separately in [release notes](./docs/releases/v0.1.0-alpha.29.md).

## Earlier consolidation

- Consolidate the active alpha.28 harness into main and refresh public documentation. Runtime churn fixes remain planned; see [live assessment](./docs/alpha28-live-assessment.md).

## 0.1.0-alpha.28 — delivered alpha baseline

- Durable execution tasks with automatic slice continuation, checkpoints, receipts, recovery, and resource ceilings.
- Contained workspace applications with scoped storage, immutable candidates, host-mediated BLE, and controlled activation.
- Session/workspace export and copy-based import with authority reset.
- Isolated storage verification using commit, reload, and exact record assertions; caught host failures invalidate checks.
- Session-owned draft reuse, unchanged-candidate reuse, improved staging feedback, and authoring request handling.
- Signed Secure alpha delivery; source and verification provenance are in [current status](./docs/current-status.md).

Known issues: malformed authoring retries, optional screenshot/handoff failures blocking task completion, camera/viewport confusion, and incomplete semantic acceptance coverage. Live activation and isolated persistence passed; favorites editing, process-restart endurance, and clean task completion remain unverified.

## Earlier public-source preparation

The entries below preserve the original preparation checklist, not the current release status.

### Added

- Apache-2.0 open-source licensing and contribution governance
- Consumer, managed-inference, and Atlas Enterprise product boundaries
- Closed-alpha operating, privacy, budget, and release gates
- Public-source readiness validation and Core CI

### Changed

- Replaced the OpenClaw-era roadmap with the native Android v0.1 roadmap
- Defined the Android app as the primary reference experience and OpenClaw packages as legacy proof adapters

## Original alpha.1 placeholder

Historical planning placeholder; superseded by the delivered alpha baseline and provenance linked above.
