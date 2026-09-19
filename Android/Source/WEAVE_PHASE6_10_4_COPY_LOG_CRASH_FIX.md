# Weave Phase 6.10.4 — Copy Log crash fix

## Problem

`Settings -> Diagnostics -> Copy log` could crash Weave when the generated diagnostic report was large.

Observed Logcat failure:

- `ClipboardManager.setPrimaryClip(...)`
- `TransactionTooLargeException`
- parcel size observed: `1196088 bytes`

Android sends clipboard data through Binder. A diagnostic report containing long-lived network/group history can exceed the Binder transaction budget.

## Changes

- Clipboard copies are capped at 200,000 characters.
- When trimming is required, Weave preserves:
  - the beginning of the report (current state/header), and
  - the newest tail of the diagnostic history.
- A visible marker is inserted where older middle detail was omitted.
- If the first clipboard operation still fails on a device/vendor ROM, Weave automatically retries with a 60,000-character report.
- Clipboard exceptions are caught and no longer crash the app.
- The UI reports `Copied recent log (trimmed)` when truncation was necessary.
- Diagnostic breadcrumbs now record:
  - `DIAGNOSTICS_COPY`
  - `DIAGNOSTICS_COPY_FALLBACK`
  - `DIAGNOSTICS_COPY_FAILED`
- Settings text now explains that very large diagnostic reports can be trimmed for Android clipboard safety.

## Validation note

A full Gradle compile could not be completed in the packaging environment because the Gradle wrapper attempted to reach `services.gradle.org`, which is unavailable there. The source change is isolated to `OnboardingScreens.kt` and should be compiled normally on the development machine.
