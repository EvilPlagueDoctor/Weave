@echo off
setlocal
cd /d "%~dp0"
echo [Weave - Android DEBUG]
echo Requires: JDK 17+ and Android SDK 36.
if not exist "app\src\main\assets\content_filter\image_safety_xs.onnx" (
  echo.
  echo WARNING: NSFW / gore image model is not installed.
  echo Image content filtering will NOT classify or blur images in this build.
  echo Run download_content_filter_models.bat before building to install it.
  echo.
)
call gradlew.bat :app:assembleDebug || exit /b 1
echo.
echo Built debug APK under app\build\outputs\apk\debug\
endlocal
