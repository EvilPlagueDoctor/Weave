#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
echo "[Weave - Android]"
echo "Building optimized prototype APK (release runtime, debug signing key)..."
./gradlew :app:assembleRelease
echo "Built APK under app/build/outputs/apk/release/"
