# Weave Groups v2 Phase 2 — Compile Fix 1

Fixes the Kotlin compiler errors reported after the initial Phase 2 transport/hardening build.

## Changes

1. `GroupRuntime.kt`
   - Replaced the nonexistent `GroupBranchNetwork.readPostIndexEntry(...)` call with the existing `readPostDecision(...)` API.
   - The returned `GroupPostIndexEntry` exposes `state` and `postHash`, preserving the intended DHT-level idempotency check.

2. `SocialNetworkController.kt`
   - Removed the duplicate private `sha256Hex(ByteArray)` helper.
   - The controller now uses the package-level `sha256Hex(ByteArray)` from `GroupEventV2.kt`.
   - The separate direct `MessageDigest` usage elsewhere in `SocialNetworkController.kt` was intentionally retained.

## Validation

- Exactly one `sha256Hex(ByteArray)` function remains under `app.weave`.
- No references to `readPostIndexEntry` remain.
- `readPostDecision` exists in `GroupBranchNetwork` and returns `GroupPostIndexEntry?`.
- Full Gradle compilation could not be run in the build environment because the wrapper requires downloading Gradle 9.4.1 from `services.gradle.org`, which is inaccessible there.
