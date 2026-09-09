# Building Atlas

Atlas's primary runtime is the native Android application in [`apps/atlas-android`](./apps/atlas-android). It owns the physical session on the phone and connects directly to user-configured OpenAI-compatible inference: an on-device model, a LAN endpoint, a BYOK cloud endpoint, or the optional Atlas Managed gateway.

OpenClaw is **not** the current upstream runtime, MVP provider, or Android transport. It was the first prototype integration used to prove provider/device seams before the native application existed. Its adapter, CLI harness, and original plan remain in the repository as historical engineering evidence.

Current alpha testing includes a Qwen 3.5 2B GGUF served by llama.cpp directly on the phone. That configuration is deliberately not privileged in Core: the same endpoint contract supports LAN-hosted inference and compatible cloud providers, while Atlas retains session identity and state across provider changes.

## Current build path

For prerequisites and exact reproducible commands, use:

- [Build and test](./docs/build-and-test.md) for repository-wide checks.
- [Android README](./apps/atlas-android/README.md) for Android Studio, device, endpoint, and build-variant setup.
- [Runtime code map](./docs/runtime-code-map.md) to trace a native turn through Core, inference, tools, persistence, voice, and perception.
- [Engineering documentation index](./docs/README.md) for the rest of the architecture.

The minimum repository verification is:

```bash
npm ci
npm run build
npm run typecheck
npm test
npm run check:public
```

The Android verification is:

```bash
cd apps/atlas-android
./gradlew testSecureDebugUnitTest testLanDebugUnitTest
./gradlew lintSecureDebug lintLanDebug assembleSecureDebug assembleLanDebug
```

`secure` rejects cleartext traffic. `lan` permits cleartext only at the Android network-policy layer; Atlas separately restricts it to device/private-network addresses.

## Historical build plan

The original May 2026 OpenClaw-first plan is preserved at [Historical OpenClaw prototype build plan](./docs/history/openclaw-prototype-build-plan.md). It explains how Atlas first tested the ownership boundaries that later became the native runtime, but its milestones and “first provider” statements are superseded and must not be used as current setup instructions.
