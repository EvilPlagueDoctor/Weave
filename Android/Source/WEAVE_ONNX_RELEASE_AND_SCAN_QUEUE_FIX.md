# Weave Android: ONNX release crash + fail-closed image scan queue

## Changes

- `app/proguard-rules.pro`: preserve `ai.onnxruntime.**` including members in R8/minified builds, as recommended by ONNX Runtime for Android.
- `build_release.bat`: builds `:app:assembleRelease`, checking Rust/cargo-ndk targets and required content filter assets. Produces `app/build/outputs/apk/release/Weave-release.apk`.
  - Run `build_release.bat` for a release-optimized **debug-key-signed** APK for local testing.
  - Run `build_release.bat fdroid` for an **unsigned** APK suitable for F-Droid's signing pipeline (existing Gradle `weaveFdroidBuild` property).
  - This does **not** create a production-signed APK. A private signing key must be configured for independent public distribution.
- `ContentFilter.kt`: classification results now distinguish `Scanned(scores)`, `NotScanned(reason)`, and `Disabled`. A classifier that is loading, missing, or has failed **never returns safe zero scores**, and only successful scans enter the score cache.
- ONNX image initialization is serialized with inference using the same coroutine `Mutex`, so simultaneous image requests wait in a suspended queue until initialization is completed. ONNX environment loading is on the worker dispatcher and protected against JNI/native linkage failures.
- Invalid ONNX model inputs or outputs are scan failures, not clean scores. No automatic reveal is offered for unscanned images.
- `ContentFilterUi.kt`: image views show a spinner while queued, and a *Not scanned* placeholder if the model fails to load or classification fails. Image content is shown only after a real scan, unless both image filters are disabled/non-blocking. Switching preferences restarts or disables pending scans.
- Settings now say *installed — loads on first scan* until a model session actually exists; then show *initializing*, *ready*, or *failed*.

## Manual testing (on a device)

1. On an R8 release APK, enable sexual-image filtering (High/Blur), open a profile or group containing an NSFW image. Confirm the model loads and filtering works; inspect `WeaveContentFilter` logcat messages `IMAGE_MODEL_LOAD_BEGIN`, `IMAGE_MODEL_LOAD_OK`, `IMAGE_CLASSIFY_RESULT`.
2. Open multiple profiles/group images immediately on startup. They should display a *Checking your content filters…* spinner while the first ONNX model initializes. No image should appear before receiving `IMAGE_CLASSIFY_RESULT`.
3. Temporarily supply an invalid ONNX model in a **debug build** to simulate initialization failure. The view should remain covered with *Not scanned* and log `IMAGE_MODEL_LOAD_FAILED` or `IMAGE_NOT_SCANNED`, never show the image as safe. (Release builds verify that assets exist, not that model bytes are valid.)
4. Switch sexual and gore filtering both to Off. Images should be visible immediately without queuing or loading the model. Turn filtering back On: the visible images must require classification again.
5. Also test thumbnails, full-screen group images, profile images, and group avatars; verify no stale clean result after changing an image under the same ID.
6. If model initialization fails on a device, check supported ABI (`arm64-v8a` or `x86_64`), Logcat, ONNX Runtime's native libraries, and any R8-reported missing JNI classes.

## Environment limitation

The APK has not been rebuilt by this patch: a Windows/Android SDK/NDK + cargo-ndk build is required to verify it on device. The existing compiled APK in the original input was not updated and should not be tested as if it contained these changes.
