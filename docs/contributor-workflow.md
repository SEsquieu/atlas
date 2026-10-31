# Contributor and build workflow

Humans and agents use the same review surface. Start from current main and a ready entry in [active fixes](./active-fixes.md). Open or find its scoped tracking issue, reference its stable AF ID, and open a draft PR as a visible claim. Check existing claims first. Proposed work needs scope/design resolved before implementation.

One PR should establish one coherent outcome. Include regression evidence, constraints, and remaining uncertainties. New discoveries belong in separate issues linked back to the originating PR; do not post raw private exports. Automated contributors must not claim tests ran when they did not, expand authorization, or self-approve changes. Follow DCO sign-off and normal maintainer review.

Validation runs on PRs. Merge changes the source baseline; it does not mean a new APK was distributed or phone acceptance passed. Use Refs rather than Closes for issues whose live criteria remain outstanding. Update the backlog to implemented with the merge commit, then verified only when all acceptance evidence exists.

## Independent installable builds

Maintainers run the **Android** workflow manually, selecting the desired branch/ref in GitHub Actions. Default to main; record its exact resolved commit. installable=true generates the standard Secure APK after unit/lint validation. LAN and DEV are explicit opt-ins. installable=false runs validation only. Pushes and PRs never enter the signing/installable job.

Builds can batch several merged fixes. The artifact includes build-provenance.json with source commit, ref, run ID, version code, APK sizes and SHA-256 hashes. CI verifies signatures using the persistent alpha key. Record tested AF IDs/PRs, build run, source commit, variant, version/code and phone results in the relevant issues/backlog. In-flight newer merges do not change the checked-out build source.

Increment the source alpha version deliberately in a delivery-preparation PR when a new named alpha is wanted; merging an ordinary fix does not bump it. The existing Android workflow run-number version code keeps manual builds increasing. Keep the workflow identity and signing key stable. Artifact retention is finite; a CI artifact is not a permanent GitHub Release. Public release publishing remains a separate maintainer decision under the release runbook.

The old alpha.5-specific publishing workflow was removed because its version, notes and build path were stale. This change does not create a replacement automatic release publisher or schedule any agent work.

## Maintainer review

Check the requested outcome, test evidence, data/authority boundaries and scope. Merge independently from delivery. Keep outstanding phone checks visible. If a fix exposes another problem, create a linked issue with reproduction, expected behavior and acceptance criteria; assign the next unused AF ID during triage. Do not reuse IDs or mark a known failure verified merely because its PR merged.

## Harness quality review

When changing harness code, apply the [quality roadmap](./harness-quality-roadmap.md): check task/lease and cancellation boundaries, resource ownership, hot-path queries and payload size, typed outcomes and user-visible recovery. Record adjacent discoveries as scoped issues with evidence/confidence and acceptance instead of silently broadening the PR. Measure relevant preparation/response latency, packet/query costs and task quality; a shorter answer alone does not establish improvement. Keep original audit truth, permissions and uncertain-effect recovery intact. Sanitized workload fixtures belong in tests; raw private session exports do not.
