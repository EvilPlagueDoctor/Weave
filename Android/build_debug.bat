@echo off
setlocal
cd /d "%~dp0"
echo [Weave - Android DEBUG]
echo Requires: JDK 17+ and Android SDK 36.
call gradlew.bat :app:assembleDebug || exit /b 1
echo.
echo Built debug APK under app\build\outputs\apk\debug\
endlocal
