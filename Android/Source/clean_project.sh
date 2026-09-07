#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf app/build build .gradle
echo "Android generated build output removed. Source tree is back to pre-compile state."
