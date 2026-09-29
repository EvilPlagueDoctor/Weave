#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf app/build build .gradle native/veilknit-daemon/target app/src/main/jniLibs
echo "Android generated build output removed. Source tree is back to pre-compile state."
