@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo [Weave + embedded VeilKnit - Android RELEASE]
echo Requires JDK 17+, Android SDK/NDK, Rust, and cargo-ndk.
echo.

rem Default: optimized, R8-minified, DEBUG-SIGNED release APK for device testing.
rem Usage: build_release.bat fdroid   = unsigned release for F-Droid to sign.
set "EXTRA_GRADLE_ARG="
if /I "%~1"=="fdroid" (
    set "EXTRA_GRADLE_ARG=-PweaveFdroidBuild=true"
    echo Building unsigned F-Droid release variant.
) else if not "%~1"=="" (
    echo ERROR: Unknown argument "%~1". Use "fdroid" or no argument.
    exit /b 2
) else (
    echo Building optimized release APK signed using the local DEBUG key.
    echo This is NOT a production/distribution signing key.
)

where java >nul 2>nul
if errorlevel 1 (echo ERROR: Java was not found. Install JDK 17+.& exit /b 1)
where cargo >nul 2>nul
if errorlevel 1 (echo ERROR: cargo was not found. Install Rust/rustup.& exit /b 1)
where rustup >nul 2>nul
if errorlevel 1 (echo ERROR: rustup was not found.& exit /b 1)

cargo ndk --version >nul 2>nul
if errorlevel 1 (
    echo Installing cargo-ndk...
    cargo install cargo-ndk
    if errorlevel 1 (echo ERROR: cargo-ndk installation failed.& exit /b 1)
)
rustup target add aarch64-linux-android x86_64-linux-android
if errorlevel 1 (echo ERROR: Rust Android targets installation failed.& exit /b 1)

rem Release builds must ship both actual ONNX models and the tokenizer vocabulary.
for %%M in (
    "app\src\main\assets\content_filter\image_safety_xs.onnx"
    "app\src\main\assets\content_filter\toxic_minilm_int8.onnx"
    "app\src\main\assets\content_filter\vocab.txt"
) do (
    if not exist %%M (
        echo ERROR: Required content filter asset missing: %%~M
        echo Run download_content_filter_models.bat first.
        exit /b 1
    )
)

echo.
echo Building release APK with R8/ProGuard and bundled models...
call gradlew.bat :app:assembleRelease %EXTRA_GRADLE_ARG%
if errorlevel 1 (echo ERROR: Release build failed.& exit /b 1)

set "APK=app\build\outputs\apk\release\Weave-release.apk"
if not exist "%APK%" (echo ERROR: Gradle succeeded but expected APK is missing: %APK%& exit /b 1)
echo.
echo SUCCESS: %CD%\%APK%
if not defined EXTRA_GRADLE_ARG (
    echo Signed with the Android debug key, for testing only.
) else (
    echo Unsigned APK for the F-Droid signing pipeline.
)
exit /b 0
