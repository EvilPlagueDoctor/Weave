@echo off
setlocal
cd /d "%~dp0"
echo [Weave - Android startup log]
echo Requires: adb in PATH and one Android device connected with USB debugging enabled.
where adb >nul 2>nul || (echo ERROR: adb was not found in PATH.& exit /b 1)
adb get-state >nul 2>nul || (echo ERROR: no adb device is available.& exit /b 1)
adb logcat -c
adb shell am force-stop app.weave
adb shell monkey -p app.weave -c android.intent.category.LAUNCHER 1 >nul 2>nul
timeout /t 4 /nobreak >nul
adb logcat -d -v threadtime > startup_logcat.txt
echo Saved: %CD%\startup_logcat.txt
endlocal
