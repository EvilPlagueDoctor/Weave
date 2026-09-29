# Weave — F-Droid preparation notes

This branch is for preparing Weave for submission to the official F-Droid repository.

## Current Android package

- Application ID: `app.weave`
- Android project: `Android/Source`
- Minimum SDK: 29
- Target SDK: 36
- Compile SDK: 36.1
- Current versionCode: 44
- Current versionName: `0.11.6-startup-tips`
- Java level: 17
- Supported packaged ABIs: `arm64-v8a`, `x86_64`

## Build layout

The Android app is a Gradle project under `Android/Source`.

The VeilKnit daemon/core is included as Rust source under:

`Android/Source/native/veilknit-daemon`

The app's Gradle build invokes `cargo ndk` and builds the Rust core as a `cdylib` for Android before packaging it in the APK.

Normal release build entry point:

```sh
cd Android/Source
./build_project.sh
```

The underlying Gradle task is:

```sh
./gradlew :app:assembleRelease
```

The build currently requires JDK 17+, Android SDK/NDK, Rust/rustup, and `cargo-ndk`.

## Dependency/repository notes

Gradle dependencies are resolved only from Google Maven and Maven Central. The Android app uses AndroidX/Compose, Kotlin coroutines, and Microsoft ONNX Runtime.

The Rust daemon uses crates.io dependencies plus `veilid-core` from the Veilid GitLab repository on branch `0.5.7-debug`.

For F-Droid reproducibility, the Veilid dependency should ideally be pinned to an immutable commit/tag rather than a moving branch.

## Optional content-filter models

Weave's classifier models are not stored in the repository and are downloaded separately.

Current sources:

1. `OwenElliott/image-safety-classifier-xs`
   - file: `onnx/image-safety-classifier-xs.onnx`
   - declared upstream license: MIT
   - current known SHA-256: `8c28c49d9075f3ad15ebdc2961f02d5b3f99be944815b848b49c9f0e6f3fb689`

2. `minuva/MiniLMv2-toxic-jigsaw-onnx`
   - file: `model_optimized_quantized.onnx`
   - declared upstream license: Apache-2.0
   - SHA-256 already verified by Weave: `bcd9dfb48cad802ac8f7cd789e1294f1f0b22d532797bd41f5a11694e3c269a0`
   - `vocab.txt` comes from the same upstream model repository.

Before F-Droid submission, the model downloader should use immutable upstream revisions and verify every downloaded artifact, including the image model and vocabulary file. The F-Droid build recipe can then fetch the exact approved FLOSS assets deterministically, or the F-Droid build can omit the optional models if preferred.

## Known submission blockers / work remaining

### 1. Project license

There is currently no root `LICENSE` file. F-Droid requires the app itself to be released under an accepted FLOSS license. The project owner must choose the license before a valid F-Droid metadata file can be finalized.

### 2. Release signing configuration

The current Gradle `release` build explicitly uses the debug signing configuration. That is convenient for prototype builds, but should be separated from the F-Droid/release build path. F-Droid normally signs its own builds, so the source build should produce an unsigned or normally release-configured APK without depending on a developer debug key.

### 3. Pin the Veilid source revision

`veilid-core` currently follows the mutable Git branch `0.5.7-debug`. For reproducible builds this should be changed to an immutable Git `rev` (commit SHA) or a stable published version/tag after confirming the exact Veilid source revision Weave currently expects.

### 4. Pin content-filter model revisions and hashes

The download script currently uses Hugging Face `main` URLs. Replace them with immutable commit/revision URLs and validate SHA-256 for all three assets.

### 5. Create a tagged Weave release

Once the above changes are settled, bump `versionCode`/`versionName` if needed and create an immutable Git tag for the version submitted to F-Droid.

### 6. F-Droid metadata

After the project license is chosen and the build is reproducible, create an `fdroiddata` recipe for package `app.weave` with:

- `RepoType: git`
- `Repo: https://github.com/EvilPlagueDoctor/Weave.git`
- `subdir: Android/Source`
- Gradle release build
- Rust/cargo-ndk preparation commands as needed
- exact Weave commit/tag
- any deterministic preparation needed for the optional model assets

## Likely F-Droid-friendly items already in place

- Full Kotlin application source is public.
- Full embedded VeilKnit Rust source is public in the same repository.
- `Cargo.lock` is checked in.
- `gradlew` and the Gradle wrapper are checked in.
- Android dependencies use standard Google/Maven Central repositories.
- ONNX Runtime is open source (MIT).
- Both optional classifier models declare FLOSS licenses upstream.
- No Google Play Services/Firebase dependency is visible in the current Gradle dependency list.

## Suggested order

1. Choose the Weave/VeilKnit project license.
2. Pin the Veilid revision.
3. Make model downloads immutable and hash-verified.
4. Remove debug-key signing from the normal release build path.
5. Test a clean Linux release build.
6. Tag the release.
7. Write/test the F-Droid `fdroiddata` recipe.
8. Submit the App Inclusion merge request to F-Droid.
