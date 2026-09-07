# Weave compile fix — 2026-09-05

Fixed the Kotlin compiler error in `DaemonClient.awaitReadyDaemonIdentity()`:

`Return type mismatch: expected 'DaemonClient.DaemonIdentity', actual 'Unit'.`

Kotlin gives `while (true)` the type `Unit` even though the readiness loop either returns a
`DaemonIdentity` or throws on timeout. The function now ends the `withContext` lambda with an
explicit `error(...)` expression (`Nothing` type), making the expected return type unambiguous.
The line is unreachable at runtime unless the infinite loop semantics themselves change.

A standalone Kotlin compiler check of this return-type pattern succeeds (with only the expected
"unreachable code" warning). Full Android/Gradle compilation still requires the local Android
build environment.
