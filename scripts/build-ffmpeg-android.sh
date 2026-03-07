#!/usr/bin/env bash
set -euo pipefail

ABI="${1:-arm64-v8a}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT_DIR/app"
DEPS_DIR="$APP_DIR/.native_deps"
FFMPEG_SRC_DIR="$DEPS_DIR/ffmpeg"
FFMPEG_BUILD_DIR="$DEPS_DIR/build-ffmpeg-$ABI"
SRT_BUILD_DIR="$DEPS_DIR/build-srt-$ABI"
JNI_OUT_DIR="$APP_DIR/src/main/jniLibs/$ABI"

case "$ABI" in
  arm64-v8a)
    ANDROID_TARGET="aarch64-linux-android"
    ANDROID_ARCH="aarch64"
    ;; 
  *)
    echo "Unsupported ABI for now: $ABI"
    echo "Supported ABIs: arm64-v8a"
    exit 1
    ;;
esac

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ANDROID_NDK_HOME is not set. Please export your Android NDK path."
  echo "Example: export ANDROID_NDK_HOME=\$ANDROID_SDK_ROOT/ndk/<version>"
  exit 1
fi

for tool in git cmake make; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing required build tool: $tool"
    echo "Install '$tool' and re-run ./gradlew :app:buildFfmpegArm64"
    exit 1
  fi
done

if [[ ! -f "$APP_DIR/src/main/jniLibs/$ABI/libsrt.so" ]]; then
  echo "Missing libsrt.so for $ABI at app/src/main/jniLibs/$ABI/libsrt.so"
  echo "Run ./gradlew :app:buildSrtArm64 first so FFmpeg can link against libsrt."
  exit 1
fi

mkdir -p "$DEPS_DIR"

if [[ ! -d "$FFMPEG_SRC_DIR/.git" ]]; then
  git clone --depth 1 --branch n6.1.1 https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_SRC_DIR"
fi

API_LEVEL="24"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/${ANDROID_TARGET}${API_LEVEL}-clang"
CXX="$TOOLCHAIN/bin/${ANDROID_TARGET}${API_LEVEL}-clang++"

if [[ ! -x "$CC" ]]; then
  echo "Unable to find Android clang compiler at $CC"
  exit 1
fi

JOBS="$(command -v nproc >/dev/null 2>&1 && nproc || getconf _NPROCESSORS_ONLN || echo 1)"

mkdir -p "$FFMPEG_BUILD_DIR" "$JNI_OUT_DIR"
pushd "$FFMPEG_SRC_DIR" >/dev/null

PKG_CONFIG_PATH="$SRT_BUILD_DIR"
export PKG_CONFIG_PATH

./configure \
  --prefix="$FFMPEG_BUILD_DIR/install" \
  --target-os=android \
  --arch="$ANDROID_ARCH" \
  --cc="$CC" \
  --cxx="$CXX" \
  --cross-prefix="$TOOLCHAIN/bin/llvm-" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --enable-cross-compile \
  --enable-shared \
  --disable-static \
  --disable-programs \
  --disable-doc \
  --disable-debug \
  --enable-network \
  --enable-protocol=srt \
  --enable-libsrt \
  --enable-muxer=mpegts \
  --extra-cflags="-I$SRT_BUILD_DIR" \
  --extra-ldflags="-L$APP_DIR/src/main/jniLibs/$ABI" \
  --extra-libs="-lsrt"

make -j"$JOBS"
make install
popd >/dev/null

cp "$FFMPEG_BUILD_DIR/install/lib/"libavcodec.so "$JNI_OUT_DIR/libavcodec.so"
cp "$FFMPEG_BUILD_DIR/install/lib/"libavformat.so "$JNI_OUT_DIR/libavformat.so"
cp "$FFMPEG_BUILD_DIR/install/lib/"libavutil.so "$JNI_OUT_DIR/libavutil.so"
cp "$FFMPEG_BUILD_DIR/install/lib/"libswresample.so "$JNI_OUT_DIR/libswresample.so"

echo "Built and installed FFmpeg runtime libs into $JNI_OUT_DIR"
