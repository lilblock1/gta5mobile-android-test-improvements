#!/bin/sh
set -eu
cd "$(dirname "$0")"
ENGINE_PYTHON=${NIGHTLANE_PYTHON:-python3}
for tool in cargo rustup gradle java "$ENGINE_PYTHON"; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Missing $tool. See android/README.md for setup." >&2; exit 1; }
done
if [ -z "${ANDROID_HOME:-}" ] && [ ! -f android/local.properties ]; then
    echo "Set ANDROID_HOME to your Android SDK, or configure android/local.properties." >&2
    exit 1
fi
rustup target add aarch64-linux-android
cargo ndk --version >/dev/null 2>&1 || { echo "Install cargo-ndk: cargo install cargo-ndk --locked" >&2; exit 1; }
ENGINE_ZIP="$PWD/android/app/build/engine-resources.zip"
"$ENGINE_PYTHON" tools/engine_resources.py prepare --output "$ENGINE_ZIP"
gradle -p android assembleDebug "-PengineResourcesZip=$ENGINE_ZIP"
APK=android/app/build/outputs/apk/debug/app-debug.apk
echo "Built: $APK"
if [ "${1:-}" = --install ]; then adb install -r "$APK"; fi
