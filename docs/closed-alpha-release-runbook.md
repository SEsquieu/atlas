# Closed-alpha release runbook

## 1. Freeze and verify

- Select one commit; require public-readiness, Core, cloud, and both Android variant checks.
- Run the twenty scripted golden loops on the target commit and attach results to the release issue.
- Test fresh install and upgrade on the Galaxy S22 Ultra plus two Android/OS variants, including permission denial, Bluetooth, screen-off service, network loss, process death, export, delete, and retention cleanup.
- Confirm the build profile does not enable Atlas Managed unless its separate operational gates are complete.

## 2. Protect signing

Create a protected GitHub environment named `closed-alpha`, restrict approvers, and add `ATLAS_ANDROID_KEYSTORE_BASE64`, `ATLAS_ANDROID_KEYSTORE_PASSWORD`, `ATLAS_ANDROID_KEY_ALIAS`, and `ATLAS_ANDROID_KEY_PASSWORD`. Keep an encrypted offline backup of the keystore and recovery instructions. Never place signing or provider secrets in Gradle properties committed to git.

## 3. Build and attest

Dispatch **Android signed alpha** with a unique tag. The workflow tests/lints both variants, signs both APKs, verifies signatures, records the certificate, and publishes SHA-256 sums. Download once as a maintainer, independently verify the checksum/signature, install, and execute a smoke loop before inviting testers.

## 4. Distribute and operate

- Send testers the package distinction, checksum, certificate fingerprint, tester guide, privacy/limitations notice, known issues, and private support route.
- Keep the prior known-good release available. Record cohort membership and installed version without collecting session content.
- Triage with the severity table in `closed-alpha.md`. Stop distribution for S1/S2, preserve evidence without asking users to expose unnecessary content, then patch or roll back.

## 5. Exit criteria

Expand beyond five testers only after one week without S1, no unresolved S2, and reviewed evidence that speech, process recovery, context freshness, export, deletion, and transport behavior match the documented product promise.
