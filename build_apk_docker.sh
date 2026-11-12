#!/usr/bin/env bash
set -e

echo "Đang build APK bằng Docker..."
docker run --rm -v "$PWD":/project -w /project mingc/android-build-box bash -c "gradle assembleDebug"
echo "Hoàn tất! File APK nằm tại: app/build/outputs/apk/debug/app-debug.apk"
