#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
echo "[Weave + embedded VeilKnit - Android DEBUG]"
echo "Requires: JDK 17+, Android SDK/NDK 36, Rust, and cargo-ndk."
command -v java >/dev/null || { echo "ERROR: Java was not found."; exit 1; }
command -v cargo >/dev/null || { echo "ERROR: cargo was not found. Install Rust with rustup first."; exit 1; }
cargo ndk --version >/dev/null 2>&1 || cargo install cargo-ndk
rustup target add aarch64-linux-android x86_64-linux-android
if [ ! -f "app/src/main/assets/content_filter/image_safety_xs.onnx" ]; then
  echo
  echo "WARNING: NSFW / gore image model is not installed."
  echo "Image content filtering will NOT classify or blur images in this build."
  echo "Run ./download_content_filter_models.sh before building to install it."
  echo
fi
echo "Building APK with embedded Rust VeilKnit core..."
./gradlew :app:assembleDebug
echo "Built APK under app/build/outputs/apk/debug/"
