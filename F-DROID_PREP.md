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
- Project license: Apache-2.0

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

The Rust daemon uses crates.io dependencies plus `veilid-core` from the Veilid GitLab repository. The preparation branch pins Veilid to the exact revision already recorded by the existing lockfile:

`e2cb5a0f43deec0b1e08e032d0088b5300eb5617`

This avoids depending on the moving `0.5.7-debug` branch during future reproducible builds.

## Optional content-filter models

The classifier binaries are intentionally **not stored in the Git repository**. They are downloaded into `app/src/main/assets/content_filter` when a full filtering build is wanted.

Current pinned sources:

1. `OwenElliott/image-safety-classifier-xs`
   - file: `onnx/image-safety-classifier-xs.onnx`
   - upstream license: MIT
   - immutable revision: `606ad3dfd6a023215e3ab0797040437cc365977b`
   - SHA-256: `8c28c49d9075f3ad15ebdc2961f02d5b3f99be944815b848b49c9f0e6f3fb689`

2. `minuva/MiniLMv2-toxic-jigsaw-onnx`
   - file: `model_optimized_quantized.onnx`
   - upstream license: Apache-2.0
   - immutable revision: `edeaa44a3eed98842d1139619bfb5dc55fafcfad`
   - SHA-256: `bcd9dfb48cad802ac8f7cd789e1294f1f0b22d532797bd41f5a11694e3c269a0`
   - `vocab.txt` is fetched from the same immutable revision.

The Linux/macOS and Windows model downloaders now use those immutable revisions. Both ONNX binaries are independently SHA-256 verified. `vocab.txt` still needs its independent SHA-256 recorded from a clean download; its URL is already revision-pinned so it cannot silently follow upstream `main`.

## Completed F-Droid preparation on this branch

- [x] Add root Apache-2.0 `LICENSE`.
- [x] Pin the Veilid Git dependency to the exact revision currently used by Weave.
- [x] Pin the image-safety ONNX download to an immutable revision and verify its SHA-256.
- [x] Pin the toxicity ONNX download to an immutable revision and verify its SHA-256.
- [x] Pin `vocab.txt` to the same immutable toxicity-model revision.
- [x] Keep large optional model binaries out of Git.

## Remaining work

### 1. Record the `vocab.txt` SHA-256

Run the updated model downloader once on a clean machine, record the resulting `vocab.txt` SHA-256, and add it to both download scripts.

### 2. Refresh `Cargo.lock` after the Veilid manifest pin

The current lockfile already resolves Veilid to the same exact commit, so runtime source does not change. A clean `cargo` resolution should refresh its source notation from the old branch-qualified URL to the new `rev`-qualified URL; commit that generated lockfile rather than hand-editing it.

### 3. Release signing configuration

The current Gradle `release` build explicitly uses the debug signing configuration. That is convenient for prototype builds, but the F-Droid build path should not depend on a developer debug key. Preserve the convenient prototype workflow while providing an unsigned/F-Droid release path.

### 4. Clean Linux build test

Test from a fresh clone with JDK 17+, Android SDK/NDK, Rust and cargo-ndk. Confirm that both the Gradle/Kotlin app and embedded Rust daemon build without relying on local/generated files.

### 5. Create a tagged Weave release

Once the build path is settled, bump `versionCode`/`versionName` if needed and create an immutable Git tag for the version submitted to F-Droid.

### 6. F-Droid metadata

Create/test an `fdroiddata` recipe for package `app.weave` with:

- `License: Apache-2.0`
- `RepoType: git`
- `Repo: https://github.com/EvilPlagueDoctor/Weave.git`
- `subdir: Android/Source`
- the appropriate Gradle variant
- Rust/cargo-ndk preparation commands as needed
- exact Weave commit/tag
- deterministic preparation for the optional model assets if they are included in the F-Droid APK

## Likely F-Droid-friendly items already in place

- Full Kotlin application source is public.
- Full embedded VeilKnit Rust source is public in the same repository.
- `Cargo.lock` is checked in.
- `gradlew` and the Gradle wrapper are checked in.
- Android dependencies use standard Google/Maven Central repositories.
- ONNX Runtime is open source.
- Both optional classifier models declare FLOSS licenses upstream.
- No Google Play Services/Firebase dependency is visible in the current Gradle dependency list.

## Suggested order from here

1. Record the `vocab.txt` hash and refresh `Cargo.lock` during the next clean local build.
2. Add a dedicated F-Droid/unsigned release build path without breaking prototype installs.
3. Test a fresh Linux build.
4. Tag the release.
5. Write/test the official `fdroiddata` recipe.
6. Submit the App Inclusion merge request to F-Droid.
