# Validation notes

This merged prototype was checked in the available Linux build environment, but that environment does not contain the full native Windows/Rust/Android toolchains required for complete release builds.

## Checks completed

- **VSPF cross-platform compatibility:** the Windows C++ `ProfileCodec`/model was compiled with `g++`, used to emit a real `VEILYSOCIAL_PROFILE_V2` document, and the Android/Kotlin `ProfileCodec` successfully decoded that exact document (`My Profile | 1 page | home`).
- **MinHash wire conformance:** the Kotlin VeilySocial backbone was compiled and executed against the existing Rust conformance vector. It produced the expected 64-slot signature hash `c34a7180275a2834` and identical packed wire bytes.
- **Android network controller:** `SocialNetworkController.kt` and `SocialBackbone.kt` were compiled with the real Kotlin coroutines library plus lightweight stubs for Android/daemon/JSON surfaces. This passed (`CONTROLLER_COMPILE_OK`).
- **Source consistency:** shared daemon mailbox/gossip/SDK changes remain synchronized across Windows, Linux, and Android-native copies where those files are intended to be shared.
- **Structural scans:** edited Rust/Kotlin/C++ sources were checked for obvious delimiter/merge damage.

## Checks not possible here

- A full Cargo build of the Windows Rust network bridge/daemon, because Rust/Cargo is not installed in this environment.
- A full Win32/Direct3D link of the Windows app, because the required Windows SDK/Direct3D toolchain is not available here.
- A full Android Gradle build. The Gradle wrapper requires Gradle 9.4.1, which is not cached here, and this environment cannot reach `services.gradle.org` to download it.

## Intended local build entry points

- Windows VeilySocial Profiles app: `Apps\\VeilySocialProfile\\Windows\\build_project.bat`
- Android VeilySocial Profiles app: `Apps\\VeilySocialProfile\\Android\\build_project.bat`
- VeilKnit daemon: use the existing platform build scripts under the normal Windows/Linux/Android daemon trees.

The first local Windows/Android builds are therefore still important integration tests. Compiler output should be treated as the source of truth for any platform-specific issue missed by these checks.
