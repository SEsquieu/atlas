# Open-source readiness checklist

Repository licensing and governance can be prepared before visibility changes. Do not make the repository public until every blocking item is complete.

## Completed in source

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

## Blocking before repository visibility changes

- [ ] Confirm the full-history CI result over every reachable GitHub branch and tag; separately consider deleted/unreachable refs
- [ ] Rotate any credential identified by automated scanning or manual artifact review
- [ ] Review GitHub Actions logs and downloadable artifacts for secrets/user data
- [x] Inventory dependency licenses and generate third-party notices for the source and Android artifact
- [x] Review tracked assets, icons, sounds, fonts, sample media, and datasets for redistribution rights
- [x] Adopt `Atlas Alongside` as the qualified open-source project identity; retain professional clearance as a commercial-launch task
- [ ] Enable GitHub private vulnerability reporting
- [ ] Enable branch protection, required CI, dependency alerts, and secret scanning where available
- [ ] Configure issue/discussion moderation and a private maintainer contact path
- [ ] Have counsel review licensing, privacy disclosures, managed-inference terms, and trademark posture before paid beta

## Blocking before closed alpha

- [ ] Complete every runtime, privacy, quality, and operations gate in [`docs/closed-alpha.md`](./docs/closed-alpha.md)
- [ ] Produce a signed, versioned release APK; do not distribute the CI debug APK
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

The current repository remains alpha software until these gates are explicitly closed with evidence.
