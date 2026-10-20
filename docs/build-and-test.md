# Reproducible build and test

## Requirements

- Node.js 22 or newer and npm
- JDK 17
- Android SDK 35 with accepted licenses, NDK 27.0.12077973, and CMake 3.22.1
- A host C/C++ toolchain and CMake for desktop JNI unit tests; network access to the pinned QuickJS-NG source
- Network access to npm, Google Maven, Maven Central, and the Gradle distribution host for a clean first build

## Provider-independent packages

```bash
npm ci
npm run build
npm run typecheck
npm test
npm run check:public
```

`npm test` includes package tests, fake Android/OpenClaw wrapper contracts, and structural behavioral-eval fixture validation. Generated test stores are ignored under `.atlas-runs`.

## Android

```bash
cd apps/atlas-android
cmake -S app/src/main/cpp -B app/build/workspace-native-host -DCMAKE_BUILD_TYPE=Release
cmake --build app/build/workspace-native-host --target atlas_workspace_js -j2
./gradlew testSecureDebugUnitTest
./gradlew lintSecureDebug
./gradlew assembleSecureDebug
```

Routine delivery is the Secure standard APK only. The Android workflow supports manual opt-in `lan` and `developer` inputs for targeted validation and signed builds; see [developer build policy](./developer-build.md). Increment the alpha version for each user-facing runtime release; record the commit and CI version code.

The secure flavor rejects cleartext transport. The visibly separate LAN flavor permits cleartext for trusted private-network endpoints and has an application ID suffix. CI installable debug APKs use the persistent alpha signing identity; they remain development builds, not production release builds.

For a release build, provide signing values only through the environment variables documented in [`closed-alpha-release-runbook.md`](./closed-alpha-release-runbook.md). Never commit a keystore or passwords. Record the source commit, build variant, checksum, and certificate fingerprint.

## Optional cloud reference

```bash
cd apps/atlas-cloud
npm ci
npm test
npm run typecheck
npm run build
```

Copy `.env.example` to a local ignored `.env` and replace placeholders. The cloud app is optional and is not required to build or use BYOI Atlas.

## Expected limitations

- Unit tests and emulator builds do not prove CameraX, STT/TTS, Bluetooth audio, thermal behavior, foreground-service survival, or process-death recovery on a physical device.
- Gradle's first run needs external artifact access. CI is the canonical clean-room Android build when a local environment cannot reach those repositories.
- A successful debug build is not evidence that signing/release provenance is configured correctly.


### Installing CI builds without losing data

Only `atlas-android-installable` is intended for phone installs. Android unit tests and lint run
without signing secrets, including on PRs. A separate manual-dispatch-only `closed-alpha` job uses the
persistent alpha key; it fails explicitly rather than distributing APKs with a fresh runner key.
Configure the four signing secrets listed in `closed-alpha-release-runbook.md`. Keep the key backed
up outside git. CI uses versionCode `1000 + Android workflow run_number`; the alpha.29 local default is 30.
A signed release replacing a CI install must explicitly set `ATLAS_ANDROID_VERSION_CODE` higher
than the installed build. LAN and Secure have different application IDs: update the same flavor.

Previously distributed runner-signed debug APKs cannot be upgraded with a new signing identity.
If the old private key was not retained, export sessions/workspaces from the old app first, then
perform one reinstall with the persistent-key build. Version bumps cannot fix certificate mismatch.

## Delivery cadence

PR and push validation does not generate installable APKs. Manually dispatch Android with installable=true for a chosen ref; builds include build-provenance.json. See [contributor and build workflow](./contributor-workflow.md). The obsolete alpha.5 publisher has been retired.
