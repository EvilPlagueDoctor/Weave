# Weave Phase 6.10.1 — Chess template load / ANR fix

The Phase 6.10 full Chess widget is intentionally large (~122 KiB source / ~3,400 lines). It remained within the 128 KiB widget-source limit and compiled correctly, but selecting it from the built-in widget picker exposed a UI-thread performance bug.

## What the device log showed

On the affected phone, selecting Chess produced large young-GC cycles, sustained main-thread CPU use, and eventually an Android input-dispatch ANR while the widget picker popup was open. The old picker performed template compilation, verification, encrypted-vault serialization, Binder IPC, and placement synchronously from the Compose click handler.

## Changes

- Built-in template preparation now runs off the Android main thread.
  - parse/compile/verify: `Dispatchers.Default`
  - encrypted private-vault save / Binder call: `Dispatchers.IO`
- The picker remains visible and shows `Preparing <widget>…` while this work is happening.
- Dismiss/cancel is disabled only during the short preparation operation so the picker coroutine is not destroyed halfway through a save.
- Added diagnostics breadcrumb `WIDGET_TEMPLATE_PREPARE` with source size and separate compile/save/total timings.
- Locally compiled built-ins now use a source-only prepared-package save path. It reuses the already verified `WidgetProgram` rather than immediately recompiling the same large source simply to serialize it. No bytecode is persisted.
- The editor primes its in-memory widget-program cache with the already verified program, avoiding another full bytecode verification on the main thread during placement.
- WidgetRepository now keeps an account-scoped in-memory package cache. A saved Chess package is not repeatedly decoded/recompiled every time the library is reopened in the same session.
- The Widget Library list itself now loads on a background dispatcher, so a previously saved large widget cannot block Compose while the picker opens after a fresh launch.
- Hot parser Regex patterns are compiled once rather than recreated for each Chess source/action line, reducing temporary allocation during large-widget compilation.
- `Choose a built-in widget` now has a stronger 2 dp primary-colour outline and subtle filled background so it reads as clearly as the Widget Studio button.

## Security model unchanged

The optimization does not make published/supplied binaries authoritative. Local vault storage is still source-only. Network/publication export still recompiles and verifies source. A package loaded from storage or received from another user is still compiled locally and independently verified before execution.

## Validation in this environment

- pure Kotlin widget core compilation: PASS
- Android resource XML parsing: PASS
- all built-in templates still compile through the local widget compiler
- Chess remains under the current source and bytecode limits
- final Android Gradle build must still be run on the Windows JDK 17+ / Android SDK 36 environment
