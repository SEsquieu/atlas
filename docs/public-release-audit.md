# Public-release audit

Audit date: 2026-09-09  
Initial audited branch head: `codex/closed-alpha-hardening` at `eaecf351eedb61dfcf1bd0037a368a2b2647e2d1`. Release-preparation evidence and automation were subsequently added on the same branch.

This report records checks performed during the release-preparation pass. It avoids reproducing possible secrets or personal values.

## Scope and method

- Reconstructed and byte-verified all 229 tracked blobs at the audited branch head.
- Enumerated 7 reachable GitHub branches, 116 unique reachable commits, 1 tag, and 832 unique historical blobs.
- Scanned eligible historical text for common OpenAI, GitHub, Google, AWS, Stripe, Supabase, private-key, and JWT credential patterns.
- Reviewed suspicious filenames for secrets, signing material, builds, logs, session exports, databases, and model binaries.
- Reviewed Android manifest/build configuration, signing inputs, example environment configuration, ignore rules, CI workflows, and release assets.
- Inventoried npm lockfile license metadata and direct Android dependencies.
- Ran clean npm install, build, typecheck, package/example/eval tests, and source public-readiness checks.
- Ran production-dependency `npm audit` for both root workspaces and the optional cloud app; both reported zero known vulnerabilities at audit time.

## Results

### No high-confidence secret found

No high-confidence credential pattern was found in the current source or scanned reachable historical text. No keystore, private key, model file, session log/export, database, APK, or AAB is tracked in reachable trees. `.env.example` contains placeholders only.

The automated remote CI pass subsequently scanned 121 reachable commits, 9 refs, 1,858 path-bearing objects, and 880 text blobs without a finding. The check now runs on every push and pull request with full checkout history. It intentionally redacts matching values.

This is strong evidence, not a guarantee. GitHub secret scanning or a specialist history scanner should be run independently before visibility changes, especially over deleted refs, forks, Actions logs, caches, and artifacts not represented by reachable Git objects.

### Personal metadata

Commit author/committer metadata contains a personal email address across history. This is not an application secret, but it becomes public repository metadata. The maintainer must decide whether that exposure is intentional; otherwise rewrite history before publishing and configure a GitHub no-reply address for future commits.

Release decision: preserve the existing authorship and chronology rather than rewrite shared history. Configure a no-reply identity for future commits if future exposure is unwanted.

### Build and binary artifacts

The repository has one existing Android prerelease with unsigned debug APK/ZIP assets and checksums. These assets are not tracked source, but they and their workflow logs must be reviewed for embedded configuration, debug-only behavior, signing expectations, and accidental user data. Do not present that prerelease as the first public production artifact.

The associated prerelease job log was retrieved and scanned without a high-confidence credential, private-key, JWT, or signing-material finding. Release metadata confirms that its APK is an explicitly unsigned debug artifact. Delete the prerelease and its tag before visibility changes because it is obsolete and misleading, not because a secret was found.

### Configuration and signing

Android managed-service URLs and publishable keys default to empty Gradle properties. Release signing reads paths/passwords from environment variables. The ignore rules cover common Android signing files, local properties, builds, APK/AAB output, logs, databases, session exports, and GGUF models. The LAN flavor intentionally permits cleartext only as a visibly distinct application variant; the secure flavor does not.

### Dependencies and licenses

Root npm runtime/test dependencies report MIT or Apache-2.0 licenses. The optional cloud lockfile reports MIT, Apache-2.0, ISC, BSD-3-Clause, 0BSD, CC-BY-4.0, and platform-specific LGPL-3.0-or-later libvips packages pulled through image tooling. No lockfile entry lacked license metadata.

Android runtime dependencies are AndroidX, Kotlin/coroutines, OkHttp/Okio, and their Apache-2.0 transitives. Test-only dependencies include JUnit and JSON-java. [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) records the source, Android, and optional cloud license inventory, and the APK packages a compact runtime attribution. Cloud redistributors must still preserve applicable libvips LGPL material and `caniuse-lite` CC attribution; counsel or dedicated license tooling is appropriate before a paid hosted distribution.

### Asset inventory

No photograph, generated marketing image, recorded audio, model weight, sample session export, dataset, or font is tracked. The Android icon is a source XML vector, system typography is used, and the listening sound is generated through an Android API. The separate website asset repository remains outside this audit. See [`assets-and-redistribution.md`](./assets-and-redistribution.md).

### CI and repository controls

Source workflows use read-only contents permissions and placeholder service values. Branches are currently unprotected. Private vulnerability reporting, dependency alerts, secret scanning settings, environment protection, and required checks were not verifiable through the available repository connection and remain maintainer actions.

## Changes made by this pass

- Expanded ignore and source scanning rules for common credentials, logs, databases, session exports, and model files.
- Added engineer-facing runtime ownership/code mapping and explicit Kotlin-versus-TypeScript boundaries.
- Added failure-semantics, endpoint-characterization, design-principle, build/test, release, and essay documents.
- Added the `unsupported_causal_bridge` behavioral fixture and deterministic fixture validation to `npm test`.
- Added deferred roadmap constraints for embeddings, evolving personality, and managed inference.
- Added a repeatable reachable-history audit, third-party notices, packaged Android attribution, asset inventory, and preliminary name-collision review.
- Prepared Android version `0.1.0-alpha.5` and hardened the signed-release workflow with tag/version matching, provenance, checksums, and certificate output.

## Blocking maintainer actions before public visibility

1. Decide whether the personal email in Git history may become public; rewrite before publishing if not.
2. Run an independent full history/ref secret scan and rotate any credential it identifies.
3. Review all Actions logs, caches, and downloadable release artifacts; remove unsafe artifacts before publishing.
4. Generate and review third-party notices for the resolved Android release and optional cloud distribution, including LGPL and CC-licensed transitive material.
5. Confirm rights to the Atlas name, icon, visual assets, copy, sample data, and any future screenshots/media.
6. Configure branch protection, required CI, dependency/security alerts, private vulnerability reporting, moderation, and a private maintainer security address.
7. Decide whether legacy OpenClaw examples ship in the first public source release or move to an explicitly archived location.
8. Complete a physical-device release matrix, including fresh install/upgrade, permissions, secure/LAN transport, CameraX, STT/TTS, Bluetooth, process death, late response, export/delete/retention, and tool interruption.
9. Produce a signed release APK from a protected environment and independently verify its checksum, certificate, install, and smoke loop.

Repository visibility must remain private until these decisions are closed with evidence.
