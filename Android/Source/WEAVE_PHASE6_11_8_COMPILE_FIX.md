# Weave Phase 6.11.8 — Compile Fix

This build is a compile-only repair on top of Phase 6.11.7. No protocol or UX behavior from 6.11.7 was intentionally removed.

## Fixes

- Groups-v2 late-claim redelivery now checks `SignedGroupEventV2.eventType` using `GroupEventTypeV2` instead of the legacy `GroupEvent.kind` API.
- Translation strings used by clipboard, toast, and feature callbacks are resolved in composable scope and captured as ordinary strings before entering non-composable callbacks.
- Removed the invalid top-level `androidx.compose.runtime.onDispose` import. `onDispose { ... }` remains inside `DisposableEffect`, where it is provided by `DisposableEffectScope`.
- Version bumped to `versionCode 36`, `versionName 0.10.5-compile-fix`.

## Build note

The `android:extractNativeLibs` manifest message is an Android Gradle Plugin warning and was not the cause of the Kotlin compilation failure.
