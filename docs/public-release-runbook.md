# Public Release Runbook

Target: **Atlas v0.1.0-alpha.5**

## 1. Freeze source

1. Confirm Core, Android, cloud, and public-readiness checks are green on the release candidate.
2. Fast-forward `main` to that exact commit.
3. Do not add another source change after recording the release SHA; restart verification if the SHA changes.

## 2. Remove misleading prototype distribution

Delete the GitHub prerelease and tag `android-v0.1.0-alpha.1`. It contains an unsigned debug prototype and must not look like the supported public download. Removing the release does not erase the underlying historical source commit.

## 3. Configure repository controls

Before changing visibility:

- enable private vulnerability reporting, dependency alerts, Dependabot security updates, and secret scanning where the plan supports them;
- protect `main`, disallow force pushes/deletion, and require pull requests plus the Atlas Core, Android, Atlas Cloud when changed, and Public readiness checks;
- retain `SECURITY.md`, issue forms, CODEOWNERS, DCO, and the pull-request template; and
- set the repository description to “Atlas — Android-native, provider-independent physical agent runtime by Seth Esquieu.”

## 4. Configure signing

Create or verify the protected `closed-alpha` GitHub environment and these secrets:

- `ATLAS_ANDROID_KEYSTORE_BASE64`
- `ATLAS_ANDROID_KEYSTORE_PASSWORD`
- `ATLAS_ANDROID_KEY_ALIAS`
- `ATLAS_ANDROID_KEY_PASSWORD`

Keep the original keystore and recovery material outside GitHub in a backed-up secure location. Losing it prevents trustworthy upgrades to the same application ID.

## 5. Produce the signed artifact

Run **Android signed alpha** from the Actions tab with tag `v0.1.0-alpha.5` against the frozen `main` commit. The workflow:

- runs unit tests and release lint;
- builds secure and trusted-LAN release APKs;
- verifies signatures;
- emits certificate evidence, SHA-256 checksums, and provenance; and
- creates a GitHub prerelease using the committed release notes.

The workflow fails if its input tag does not exactly match the Android version.

## 6. Accept on physical hardware

Download the newly created release assets, verify `SHA256SUMS.txt`, and complete [`release-device-matrix.md`](./release-device-matrix.md) on the signed APK. If code or packaging changes, delete the candidate release, create a new commit/version, and repeat.

## 7. Open the repository

1. Change repository visibility to public.
2. In a logged-out browser, verify the README, license, security policy, documentation links, source archive, release notes, and both APK downloads.
3. Confirm the old unsigned release is absent and `v0.1.0-alpha.5` targets the frozen `main` SHA.
4. Publish external announcements only after the logged-out verification succeeds.
