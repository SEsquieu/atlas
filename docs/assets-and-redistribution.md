# Assets and Redistribution Review

Review date: 2026-09-09

## Tracked asset inventory

The release source tree contains no tracked photographs, generated marketing images, audio recordings, model weights, sample camera media, session exports, datasets, or bundled font files.

The native application contains one source vector, `apps/atlas-android/app/src/main/res/drawable/ic_atlas.xml`. It is a simple project-created geometric mark defined directly in XML. The application uses Android/Compose system typography rather than redistributing a font. Its listening cue is generated at runtime with Android `ToneGenerator`; no sound recording is bundled.

The Gradle wrapper JAR is the only tracked binary required by the build toolchain. Its version and distribution checksum are pinned by the wrapper configuration.

Test-generated images under `.atlas-runs` are disposable, ignored files and are not part of the repository or release.

## Public website boundary

Website photography and generated hero art are not stored in this repository. Their provenance and usage rights must be reviewed in the separately deployed website repository before that repository or an asset bundle is published. This source review does not grant rights to those assets.

## Project identity

The source license does not grant trademark rights. See [Public name collision review](./name-review.md) and [`TRADEMARKS.md`](../TRADEMARKS.md). Name clearance remains separate from copyright/asset provenance.

## Result

No third-party media asset blocks publication of this repository. Re-run this inventory whenever screenshots, recordings, model files, sample exports, custom fonts, or website assets are introduced.
