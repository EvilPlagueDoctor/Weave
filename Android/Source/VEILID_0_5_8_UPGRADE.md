# Veilid 0.5.8 dependency update — Android dev10

This dev10 source is based on `Weave-Android-dev9-Critical-Account-Isolation-Fix.zip` and retains the account-isolation fixes.

The embedded Rust daemon now requests the **upstream GitLab `v0.5.8` tag** of `veilid-core` with an exact package-version guard (`=0.5.8`) instead of the older commit `dd8e2cdd72b5bf817172ac31c20872b168c5c8fc` (whose lockfile reports 0.5.7).

## IMPORTANT: regenerate and commit Cargo.lock

The included `Cargo.lock` is **still the prior 0.5.7 lockfile**. It was intentionally preserved instead of fabricating a new lockfile: this packaging environment has no Rust/Cargo toolchain or access to GitLab to resolve dependencies. On your Windows development machine, in `Android\Source`, run:

```powershell
cd native\veilknit-daemon
cargo update -p veilid-core
cargo check --lib
cd ..\..
.\build_debug.bat
```

The normal Gradle/cargo-ndk builds do not pass `--locked`, so Cargo should also resolve and refresh the lockfile during a standard build when upstream is accessible, but running the command explicitly makes failures clearer. Commit the regenerated `native/veilknit-daemon/Cargo.lock` alongside `Cargo.toml` after a successful build.

If Cargo reports that `v0.5.8` cannot be found, first check that the upstream Veilid tag has actually been published and is accessible (`https://gitlab.com/veilid/veilid/-/tags`). Do not silently switch to `main`, since that is not a reproducible release pin.

## Verification

No native Rust compilation or Android APK build was possible here. The tag and exact version are selected based on the reported 0.5.8 release; public caches had not yet confirmed the tag at packaging time. Please keep the dev9 ZIP as a rollback option. Test fresh-account signup, account-switch isolation, local encrypted discovery cache, mailbox send/read, and group posts across the two phones before deploying beyond testing.
