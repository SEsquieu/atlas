# Reproducible build and test

## Requirements

- Node.js 22 or newer and npm
- JDK 17
- Android SDK 35 with accepted licenses
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
./gradlew testSecureDebugUnitTest testLanDebugUnitTest
./gradlew lintSecureDebug lintLanDebug
./gradlew assembleSecureDebug assembleLanDebug
```

The secure flavor rejects cleartext transport. The visibly separate LAN flavor permits cleartext for trusted private-network endpoints and has an application ID suffix. Debug APKs are development artifacts, not signed releases.

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
