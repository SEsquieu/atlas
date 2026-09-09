# Public-release audit

Audit date: 2026-09-09  
Initial audited branch head: `codex/closed-alpha-hardening` at `eaecf351eedb61dfcf1bd0037a368a2b2647e2d1`. Release-preparation evidence and automation were subsequently added and merged to `main` before the repository became public.

**Current state:** the source repository is public as a pre-v0.1 open-source alpha. This document records the audit evidence that supported that decision and the remaining hardening work. It does not certify Atlas for production use or broad APK distribution.

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

This is strong evidence, not a guarantee. A specialist history scanner remains worthwhile for deleted/unreachable refs, forks, Actions caches, and artifacts not represented by reachable Git objects.

### Personal metadata

Commit author/committer metadata contains a personal email address across history. This is not an application secret. The release decision was to preserve existing authorship and chronology rather than rewrite shared history. Configure a GitHub no-reply identity for future commits if future exposure is unwanted.

### Build and binary artifacts

The release-preparation audit found an obsolete Android prerelease with unsigned debug APK/ZIP assets and checksums. Its associated job log was retrieved and scanned without a high-confidence credential, private-key, JWT, or signing-material finding. It was not suitable to present as the canonical public Android release.

At the time of this canonical-state update, the GitHub Releases API returns no current releases. Broad APK distribution remains separately gated by the closed-alpha release process.

### Configuration and signing

Android managed-service URLs and publishable keys default to empty Gradle properties. Release signing reads paths/passwords from environment variables. The ignore rules cover common Android signing files, local properties, builds, APK/AAB output, logs, databases, session exports, and GGUF models. The LAN flavor intentionally permits cleartext only as a visibly distinct application variant; the secure flavor does not.

### Dependencies and licenses

Root npm runtime/test dependencies report MIT or Apache-2.0 licenses. The optional cloud lockfile reports MIT, Apache-2.0, ISC, BSD-3-Clause, 0BSD, CC-BY-4.0, and platform-specific LGPL-3.0-or-later libvips packages pulled through image tooling. No lockfile entry lacked license metadata.

Android runtime dependencies are AndroidX, Kotlin/coroutines, OkHttp/Okio, and their Apache-2.0 transitives. Test-only dependencies include JUnit and JSON-java. [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) records the source, Android, and optional cloud license inventory, and the APK packages a compact runtime attribution. Cloud redistributors must still preserve applicable libvips LGPL material and `caniuse-lite` CC attribution; counsel or dedicated license tooling is appropriate before a paid hosted distribution.

### Asset inventory

No photograph, generated marketing image, recorded audio, model weight, sample session export, dataset, or font is tracked. The Android icon is a source XML vector, system typography is used, and the listening sound is generated through an Android API. The separate website asset repository remains outside this audit. See [`assets-and-redistribution.md`](./assets-and-redistribution.md).

### CI and repository controls

Source workflows use read-only contents permissions and placeholder service values. All third-party workflow actions are pinned to reviewed full commit SHAs, with human-readable version comments. The public-readiness source check rejects mutable action tags in future changes; Dependabot remains configured to propose action updates.

The current repository rejects direct writes to `main` and reports the required `source-boundary` status check when a direct update is attempted. CodeQL is enabled and completed successfully on `main` on 2026-09-09. The connected GitHub integration cannot read all repository-administration/security settings, so private vulnerability reporting, dependency alerts, secret scanning, and related settings still require maintainer verification in GitHub.

## Changes made by the release-preparation pass

- Expanded ignore and source scanning rules for common credentials, logs, databases, session exports, and model files.
- Added engineer-facing runtime ownership/code mapping and explicit Kotlin-versus-TypeScript boundaries.
- Added failure-semantics, endpoint-characterization, design-principle, build/test, release, and essay documents.
- Added the `unsupported_causal_bridge` behavioral fixture and deterministic fixture validation to `npm test`.
- Added deferred roadmap constraints for embeddings, evolving personality, and managed inference.
- Added a repeatable reachable-history audit, third-party notices, packaged Android attribution, asset inventory, and preliminary name-collision review.
- Prepared Android version `0.1.0-alpha.5` and hardened the signed-release workflow with tag/version matching, provenance, checksums, and certificate output.

## Public-source decision

The source repository was published as a pre-v0.1 open-source alpha after the reachable-history/source audit, licensing and asset inventory, public-readiness automation, and explicit project-name decision were completed. Public source availability is intentionally separated from the stronger requirements for distributing a signed closed-alpha APK or operating paid/production services.

## Remaining maintainer actions

1. Complete a manual review of historical Actions logs, caches, and downloadable artifacts beyond the obsolete prerelease log already reviewed.
2. Verify private vulnerability reporting, dependency alerts, secret scanning, and related repository security settings are enabled where available.
3. Configure issue/discussion moderation and a private maintainer security/contact path.
4. Consider an independent specialist scan for deleted/unreachable refs and other objects outside reachable Git history.
5. Complete the physical-device release matrix before broad APK distribution, including fresh install/upgrade, permissions, secure/LAN transport, CameraX, STT/TTS, Bluetooth, process death, late response, export/delete/retention, and tool interruption.
6. Produce a signed release APK from a protected environment and independently verify its checksum, certificate, install, and smoke loop before presenting it as the canonical Android alpha.
7. Have counsel review licensing, privacy disclosures, managed-inference terms, and trademark posture before paid beta.

See [`../OPEN_SOURCE_CHECKLIST.md`](../OPEN_SOURCE_CHECKLIST.md) for the canonical current checklist and [`closed-alpha.md`](./closed-alpha.md) for the separate APK-distribution gate.
