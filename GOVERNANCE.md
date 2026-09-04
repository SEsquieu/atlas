# Atlas governance

Atlas is maintainer-led during v0.1. Seth Esquieu is the initial project maintainer and final decision-maker for release scope, architecture, security response, and use of official project identity.

## Decision process

- Small implementation decisions happen through pull-request review.
- Public contract, storage, security, licensing, and architecture changes require an issue or design document before merge.
- Decisions should cite tests, measurements, user evidence, or explicit product constraints.
- Accepted architecture decisions are recorded under `docs/` rather than remaining only in conversation or review comments.

The maintainer may reject a technically sound change when it expands the v0.1 surface, couples Core to a provider, weakens user control, creates unsupported operational burden, or conflicts with the product boundary.

## Releases

- Semantic versioning begins with the first tagged alpha.
- Pre-1.0 releases may change, but breaking changes require release notes and a migration path when durable user data is involved.
- Release artifacts must be built from a tagged commit and accompanied by checksums and known issues.
- Only explicitly signed release artifacts are official. CI debug artifacts are test outputs.

## Future governance

Committer and reviewer roles will be documented after sustained external contribution exists. Governance should grow from actual participation rather than creating ceremonial roles before a community forms.
