#!/usr/bin/env bash
set -euo pipefail

ABI="${1:-arm64-v8a}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT_DIR/app"
DEPS_DIR="$APP_DIR/.native_deps"
SRT_SRC_DIR="$DEPS_DIR/srt"
BUILD_DIR="$DEPS_DIR/build-srt-$ABI"
JNI_OUT_DIR="$APP_DIR/src/main/jniLibs/$ABI"

case "$ABI" in
  arm64-v8a) ANDROID_ABI="arm64-v8a" ;;
  armeabi-v7a) ANDROID_ABI="armeabi-v7a" ;;
  x86_64) ANDROID_ABI="x86_64" ;;
  *)
    echo "Unsupported ABI: $ABI"
    exit 1
    ;;
esac

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME is not set. Please export your Android NDK path."
  echo "Example: export ANDROID_NDK_HOME=\$ANDROID_SDK_ROOT/ndk/<version>"
  exit 1
fi

mkdir -p "$DEPS_DIR"

if [[ ! -d "$SRT_SRC_DIR/.git" ]]; then
  git clone --depth 1 --branch v1.5.4 https://github.com/Haivision/srt.git "$SRT_SRC_DIR"
fi

cmake -S "$SRT_SRC_DIR" -B "$BUILD_DIR" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM=android-24 \
  -DENABLE_SHARED=ON \
  -DENABLE_STATIC=OFF \
  -DENABLE_APPS=OFF \
  -DENABLE_CXX11=ON \
  -DENABLE_UNITTESTS=OFF \
  -DENABLE_ENCRYPTION=OFF

cmake --build "$BUILD_DIR" --config Release --target srt

mkdir -p "$JNI_OUT_DIR"
cp "$BUILD_DIR/libsrt.so" "$JNI_OUT_DIR/libsrt.so"

echo "Built and installed: $JNI_OUT_DIR/libsrt.so"
