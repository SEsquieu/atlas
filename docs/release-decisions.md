# v0.1 Public Release Decisions

Decision date: 2026-09-09

## Public identity

The agent and open-source project remain **Atlas**, published by Seth Esquieu. The maintainer accepts the project's weak search distinctiveness for this experimental open-source release rather than adopting a compound name that does not fit the project. Package coordinates, the Android application ID, repository name, database identifiers, and event schemas remain unchanged for v0.1.

This decision does not assert exclusive rights to the name and does not prevent a later rename. A distinct name and professional clearance remain gates before substantial commercial investment or paid distribution.

## Git attribution

The release preserves existing commit authorship and chronology. It does not rewrite shared history merely to obscure author metadata. Maintainers who do not want an address attached to future public commits should configure GitHub's no-reply commit address before contributing further.

## Prototype history

The OpenClaw adapter, example, measurements, and original plan remain in the source release as explicitly labeled historical engineering evidence. They demonstrate the provider/device seam that preceded native Android ownership and continue to provide deterministic adapter-contract tests. They are not supported application setup paths.

## Release line

The first serious public source release is `v0.1.0-alpha.5`. The prior `android-v0.1.0-alpha.1` release contains an unsigned prototype debug build and should be removed before repository visibility changes so it cannot be mistaken for the supported release candidate.

The `main` branch should fast-forward to the verified release candidate. Old development branches may remain temporarily because the reachable-history audit covers them, but branch protection and the default-branch experience must make `main` authoritative.

## Distribution boundary

The public source release includes the Android application, provider-independent TypeScript contracts, optional cloud reference implementation, and historical adapters. It does not claim that Atlas Managed is an operating production service. Only a signed Android artifact produced by the protected prerelease workflow should be attached to the release.
