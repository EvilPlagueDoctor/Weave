#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
echo "[VeilySocial Profile Designer - Android startup log]"
command -v adb >/dev/null 2>&1 || { echo "ERROR: adb was not found in PATH."; exit 1; }
adb get-state >/dev/null
adb logcat -c
adb shell am force-stop com.veilysocial.profiledesigner
adb shell monkey -p com.veilysocial.profiledesigner -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 4
adb logcat -d -v threadtime > startup_logcat.txt
echo "Saved: $(pwd)/startup_logcat.txt"
