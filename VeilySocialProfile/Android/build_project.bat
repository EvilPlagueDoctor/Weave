@echo off
setlocal
cd /d "%~dp0"
echo [VeilySocial Profiles - Android]
echo Requires: JDK 17+ and Android SDK 36.
echo Building optimized prototype APK (release runtime, debug signing key)...
call gradlew.bat :app:assembleRelease || exit /b 1
echo.
echo Built APK under app\build\outputs\apk\release\
echo This prototype release is debug-signed for easy installation/testing.
endlocal
