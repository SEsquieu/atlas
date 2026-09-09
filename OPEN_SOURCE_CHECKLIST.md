# Open-source readiness checklist

Atlas is now a public pre-v0.1 open-source alpha. This checklist records the release-readiness work that preceded publication and the repository hardening that remains. Closed-alpha APK distribution and paid/open beta remain separate gates below.

## Completed for public source release

- [x] Apache License 2.0 added
- [x] Package metadata identifies Apache-2.0
- [x] Open platform, managed inference, and Enterprise boundaries documented
- [x] Contribution process and DCO documented
- [x] Security reporting and community conduct documented
- [x] Closed-alpha scope, pricing framework, and release gates documented
- [x] Public-readiness script and CI added
- [x] Example configuration uses placeholders
- [x] Generated artifacts and local secrets are ignored
- [x] Third-party source/distribution inventory and Android runtime attribution added
- [x] Tracked media, font, audio, sample-data, and model assets inventoried
- [x] Preliminary public-name collision review documented
- [x] Full reachable-history scan is automated in public-readiness CI
- [x] Preserve existing author history and recommend no-reply metadata for future commits
- [x] Keep OpenClaw-era code and documents as explicitly historical evidence and contract coverage
- [x] Pin every third-party GitHub Action to a reviewed full commit SHA and enforce the rule in CI
- [x] Confirm the full-history CI result over every reachable GitHub branch and tag; separately consider deleted/unreachable refs
- [x] No credential requiring rotation was identified by the source/history scans
- [x] Inventory dependency licenses and generate third-party notices for the source and Android artifact
- [x] Review tracked assets, icons, sounds, fonts, sample media, and datasets for redistribution rights
- [x] Record the decision to publish as `Atlas`, attributed to Seth Esquieu, and accept name collision for the open-source alpha
- [x] Repository is public as a pre-v0.1 open-source alpha
- [x] Main branch rejects direct writes and requires the `source-boundary` status check through the current protected-branch configuration
- [x] CodeQL is enabled and has completed successfully on `main`

## Remaining public-repository hardening

These items should be completed promptly, but they do not retroactively make the published source release private or imply that the Android alpha APK is ready for broad distribution.

- [ ] Complete a manual review of historical GitHub Actions logs, caches, and downloadable artifacts for secrets/user data; the obsolete prerelease log already reviewed showed no high-confidence credential finding
- [ ] Enable GitHub private vulnerability reporting if not already enabled
- [ ] Verify dependency alerts and secret scanning are enabled where available
- [ ] Configure issue/discussion moderation and a private maintainer contact path
- [ ] Consider an independent specialist scan for deleted/unreachable refs and other objects outside reachable Git history
- [ ] Have counsel review licensing, privacy disclosures, managed-inference terms, and trademark posture before paid beta

## Blocking before closed alpha APK distribution

- [ ] Complete every runtime, privacy, quality, and operations gate in [`docs/closed-alpha.md`](./docs/closed-alpha.md)
- [ ] Produce a signed, versioned release APK; do not distribute a CI debug APK as the canonical alpha release
- [ ] Publish alpha privacy notice, tester terms, known limitations, and reporting instructions
- [ ] Activate per-user and global sponsored-inference caps and alerts
- [ ] Verify delete/export and diagnostic-upload consent on a physical device
- [ ] Rehearse service disable, rollback, signing-key recovery, and compromised-account response
- [ ] Record source commit, build configuration, checksum, and release notes

## Blocking before paid/open beta

- [ ] Separate provider cost from customer charge in usage records
- [ ] Validate pricing against real alpha route and cost distributions
- [ ] Add rate limiting, fraud controls, stuck-reservation reconciliation, and operator dashboards
- [ ] Complete payment, tax, refund, allowance-expiration, and subscription-cancellation review
- [ ] Publish terms of service, privacy policy, subprocessors, and support expectations
- [ ] Run external security review for auth, tenancy, billing, client secrets, and tool authorization

The public repository remains alpha software. Public source availability is not a claim that Atlas is ready for unattended, emergency, safety-critical, production, or broad APK distribution.
