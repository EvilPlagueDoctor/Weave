@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo [Weave + embedded VeilKnit - Android ]
echo Requires: JDK 17+, Android SDK/NDK 36, Rust, and cargo-ndk.
where java >nul 2>nul || (echo ERROR: Java was not found.& exit /b 1)
where cargo >nul 2>nul || (echo ERROR: cargo was not found. Install Rust with rustup first.& exit /b 1)
cargo ndk --version >nul 2>nul || cargo install cargo-ndk
if errorlevel 1 exit /b %errorlevel%
rustup target add aarch64-linux-android x86_64-linux-android
if errorlevel 1 exit /b %errorlevel%
if not exist "app\src\main\assets\content_filter\image_safety_xs.onnx" (
  echo.
  echo WARNING: NSFW / gore image model is not installed.
  echo Image content filtering will NOT classify or blur images in this build.
  echo Run download_content_filter_models.bat before building to install it.
  echo.
)
echo Building optimized prototype APK with embedded Rust VeilKnit core...
call gradlew.bat :app:assembleRelease || exit /b 1
echo.
echo Built APK under app\build\outputs\apk\release\
echo This prototype release is debug-signed for easy installation/testing.
endlocal
