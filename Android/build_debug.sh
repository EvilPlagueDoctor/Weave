#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
echo "[Weave - Android DEBUG]"
./gradlew :app:assembleDebug
echo "Built debug APK under app/build/outputs/apk/debug/"
