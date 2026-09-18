# Weave Phase 6.8 — Compile Fix 1

This patch addresses the Kotlin compiler errors reported after the Phase 6.8 wrapper-JAR repair.

## Fixes

1. **Duplicate `sha256Hex(ByteArray)` top-level function**
   - Phase 6.8 accidentally contained one SHA-256 helper in `GroupEventV2.kt` and a second helper with the same signature in `WidgetNetworkWire.kt`.
   - Kotlin therefore could not choose between them from `GroupEventV2`, `GroupRuntime`, `SocialNetworkController`, `WidgetNetworkManager`, and `WidgetNetworkWire`.
   - The duplicate helpers were replaced by one shared implementation in `HashUtils.kt`.

2. **Non-exhaustive `WidgetIssueCode` mapping in Widget Studio**
   - Phase 6.7 added state, expressions, `if/else`, and receive-handler diagnostics, but the Widget Studio error-text `when` was not extended for the new enum values.
   - Added mappings for:
     - `TooManyStates`
     - `DuplicateState`
     - `InvalidState`
     - `InvalidExpression`
     - `IfDepth`
     - `NetworkHandlerInvalid`
   - Added matching user-facing strings.

## Validation performed

- Confirmed there is exactly one `sha256Hex(ByteArray)` implementation in the application package.
- Confirmed all 45 `WidgetIssueCode` values are handled by the Widget Studio diagnostic mapping.
- Parsed all Android resource XML successfully.
- Recompiled the pure Kotlin widget language/runtime/network-wire core.
- Re-ran the Phase 6.8 widget smoke suite: **40/40 checks passed**.

A full Android Gradle compile could not be run in the packaging environment because Gradle 9.4.1 is not cached there and external access to `services.gradle.org` is unavailable. The fixes directly address the compiler errors from the user's real Gradle build.
