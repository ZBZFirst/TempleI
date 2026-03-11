#!/usr/bin/env bash
set -euo pipefail

trap 'exit_code=$?; echo "FFmpeg build script failed (exit ${exit_code}) at line ${LINENO}: ${BASH_COMMAND}"; exit ${exit_code}' ERR

ABI="${1:-arm64-v8a}"
ANDROID_NDK_HOME="${2:-${ANDROID_NDK_HOME:-}}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT_DIR/app"
DEPS_DIR="$APP_DIR/.native_deps"

FFMPEG_SRC_DIR="$DEPS_DIR/ffmpeg"
FFMPEG_BUILD_DIR="$DEPS_DIR/build-ffmpeg-$ABI"
SRT_BUILD_DIR="$DEPS_DIR/build-srt-$ABI"
JNI_OUT_DIR="$APP_DIR/src/main/jniLibs/$ABI"
FFMPEG_HEADERS_OUT_DIR="$APP_DIR/src/main/cpp/third_party/ffmpeg/include"

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
  echo "Android NDK path is not set."
  echo "Pass it as argument 2 or set ANDROID_NDK_HOME."
  exit 1
fi

if command -v cygpath >/dev/null 2>&1; then
  ANDROID_NDK_HOME="$(cygpath -u "$ANDROID_NDK_HOME")"
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    export MSYSTEM="${MSYSTEM:-MINGW64}"
    export PATH="/mingw64/bin:/usr/bin:$PATH"
    HOST_TAG="windows-x86_64"
    BIN_EXT=".exe"
    ;;
  Linux*)
    HOST_TAG="linux-x86_64"
    BIN_EXT=""
    ;;
  Darwin*)
    HOST_TAG="darwin-x86_64"
    BIN_EXT=""
    ;;
  *)
    echo "Unsupported host OS: $(uname -s)"
    exit 1
    ;;
esac

echo "Host uname: $(uname -s)"
echo "MSYSTEM: ${MSYSTEM:-unset}"
echo "Using host toolchain tag: $HOST_TAG"
echo "Using ANDROID_NDK_HOME=$ANDROID_NDK_HOME"

resolve_required_tool() {
  local primary="$1"
  local fallback="${2:-}"
  if command -v "$primary" >/dev/null 2>&1; then
    command -v "$primary"
    return 0
  fi
  if [[ -n "$fallback" ]] && command -v "$fallback" >/dev/null 2>&1; then
    command -v "$fallback"
    return 0
  fi
  return 1
}

GIT_BIN="$(resolve_required_tool git)" || {
  echo "Missing required build tool: git"
  exit 1
}

PKG_CONFIG_BIN="$(resolve_required_tool pkg-config pkgconf)" || {
  echo "Missing required build tool: pkg-config"
  exit 1
}

if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
  MAKE_BIN="$(resolve_required_tool mingw32-make make)" || {
    echo "Missing required build tool: mingw32-make (or make)"
    exit 1
  }
else
  MAKE_BIN="$(resolve_required_tool make)" || {
    echo "Missing required build tool: make"
    exit 1
  }
fi

echo "Resolved git: $GIT_BIN"
echo "Resolved pkg-config: $PKG_CONFIG_BIN"
echo "Resolved make: $MAKE_BIN"

if [[ ! -f "$APP_DIR/src/main/jniLibs/$ABI/libsrt.so" ]]; then
  echo "Missing libsrt.so for $ABI at app/src/main/jniLibs/$ABI/libsrt.so"
  echo "Run gradlew :app:buildSrtArm64 first so FFmpeg can link against libsrt."
  exit 1
fi

mkdir -p "$DEPS_DIR"

if [[ ! -d "$FFMPEG_SRC_DIR/.git" ]]; then
  "$GIT_BIN" clone --depth 1 --branch n6.1.1 https://github.com/FFmpeg/FFmpeg.git "$FFMPEG_SRC_DIR"
fi

API_LEVEL="24"
TARGET_TRIPLE="${ANDROID_TARGET}${API_LEVEL}"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "Unable to find Android NDK toolchain prebuilt directory at: $TOOLCHAIN"
  exit 1
fi

CLANG_BIN="$TOOLCHAIN/bin/clang${BIN_EXT}"
CLANGXX_BIN="$TOOLCHAIN/bin/clang++${BIN_EXT}"
AR="$TOOLCHAIN/bin/llvm-ar${BIN_EXT}"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib${BIN_EXT}"
STRIP="$TOOLCHAIN/bin/llvm-strip${BIN_EXT}"

for path in "$CLANG_BIN" "$CLANGXX_BIN" "$AR" "$RANLIB" "$STRIP"; do
  if [[ ! -e "$path" ]]; then
    echo "Required NDK tool not found: $path"
    exit 1
  fi
done

CC="$CLANG_BIN --target=$TARGET_TRIPLE"
CXX="$CLANGXX_BIN --target=$TARGET_TRIPLE"

PKG_CONFIG_LIBDIR="$SRT_BUILD_DIR/install/lib/pkgconfig"
PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"
export PKG_CONFIG_LIBDIR
export PKG_CONFIG_PATH

if [[ ! -d "$PKG_CONFIG_LIBDIR" ]]; then
  echo "Missing SRT pkg-config directory: $PKG_CONFIG_LIBDIR"
  echo "Run gradlew :app:buildSrtArm64 first and confirm SRT install metadata exists."
  exit 1
fi

TMP_UNIX_DIR="/c/fftmp/ffmpeg-$ABI"
mkdir -p "$TMP_UNIX_DIR"

if command -v cygpath >/dev/null 2>&1; then
  TMP_WIN_DIR="$(cygpath -aw "$TMP_UNIX_DIR")"
else
  TMP_WIN_DIR="$TMP_UNIX_DIR"
fi

echo "Using TOOLCHAIN=$TOOLCHAIN"
echo "Using TMP_UNIX_DIR=$TMP_UNIX_DIR"
echo "Using TMP_WIN_DIR=$TMP_WIN_DIR"
echo "Compiler: $CC"
"$CLANG_BIN" --version | head -n 1 || true

echo "pkg-config probe:"
echo "  srt version: $("$PKG_CONFIG_BIN" --modversion srt)"
echo "  cflags:      $("$PKG_CONFIG_BIN" --cflags srt)"
echo "  libs:        $("$PKG_CONFIG_BIN" --libs srt)"

# Clean previous FFmpeg build/install state.
rm -rf "$FFMPEG_BUILD_DIR"
mkdir -p "$FFMPEG_BUILD_DIR" "$JNI_OUT_DIR"

# Clean source tree in case prior in-tree configure artifacts exist.
"$MAKE_BIN" -C "$FFMPEG_SRC_DIR" distclean >/dev/null 2>&1 || true

rm -f \
  "$FFMPEG_SRC_DIR/config.h" \
  "$FFMPEG_SRC_DIR/config.asm" \
  "$FFMPEG_SRC_DIR/config.log" \
  "$FFMPEG_SRC_DIR/config_components.h" \
  "$FFMPEG_SRC_DIR/Makefile"

rm -rf \
  "$FFMPEG_SRC_DIR/ffbuild"

unset TMPDIR
export TMP="$TMP_WIN_DIR"
export TEMP="$TMP_WIN_DIR"

echo "Configure TMP=$TMP"
echo "Configure TEMP=$TEMP"

export TMPDIR="$TMP_UNIX_DIR"
unset TMP
unset TEMP

echo "Configure TMPDIR=$TMPDIR"

export CC
export CXX
export AR
export RANLIB
export STRIP

pushd "$FFMPEG_BUILD_DIR" >/dev/null

# Runtime contract:
# - Android supplies encoded H.264 video and AAC audio.
# - FFmpeg only muxes encoded access units into MPEG-TS and writes over SRT.
# - Therefore no FFmpeg encoders/decoders are needed.
"$FFMPEG_SRC_DIR/configure" \
  --prefix="$FFMPEG_BUILD_DIR/install" \
  --target-os=android \
  --arch="$ANDROID_ARCH" \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$AR" \
  --ranlib="$RANLIB" \
  --strip="$STRIP" \
  --cross-prefix="$TOOLCHAIN/bin/llvm-" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --pkg-config="$PKG_CONFIG_BIN" \
  --enable-cross-compile \
  --enable-shared \
  --disable-static \
  --disable-programs \
  --disable-doc \
  --disable-debug \
  --disable-autodetect \
  --disable-avdevice \
  --disable-postproc \
  --disable-swscale \
  --disable-everything \
  --enable-avcodec \
  --enable-avformat \
  --enable-avutil \
  --enable-swresample \
  --enable-network \
  --enable-protocols \
  --enable-protocol=file \
  --enable-protocol=pipe \
  --enable-protocol=tcp \
  --enable-protocol=udp \
  --enable-protocol=libsrt \
  --enable-muxer=mpegts \
  --enable-muxer=adts \
  --enable-muxer=latm \
  --enable-bsf=aac_adtstoasc \
  --enable-bsf=h264_mp4toannexb \
  --enable-bsf=hevc_mp4toannexb \
  --enable-parser=aac \
  --enable-parser=h264 \
  --enable-parser=ac3 \
  --enable-libsrt \
  --extra-cflags="-I$SRT_BUILD_DIR/install/include" \
  --extra-ldflags="-L$SRT_BUILD_DIR/install/lib" \
  --extra-libs="-lsrt"

echo "Configured feature check:"
grep -E "^CONFIG_NETWORK=|^CONFIG_LIBSRT=|^CONFIG_LIBSRT_PROTOCOL=|^CONFIG_MPEGTS_MUXER=" ffbuild/config.mak || true

grep -q "^CONFIG_NETWORK=yes" ffbuild/config.mak || {
  echo "ERROR: FFmpeg configured without network support"
  exit 1
}

grep -q "^CONFIG_LIBSRT=yes" ffbuild/config.mak || {
  echo "ERROR: FFmpeg configured without libsrt support"
  exit 1
}

grep -q "^CONFIG_LIBSRT_PROTOCOL=yes" ffbuild/config.mak || {
  echo "ERROR: FFmpeg configured without SRT protocol support"
  exit 1
}

grep -q "^CONFIG_MPEGTS_MUXER=yes" ffbuild/config.mak || {
  echo "ERROR: FFmpeg configured without MPEG-TS muxer support"
  exit 1
}

unset TMPDIR
export TMP="$TMP_WIN_DIR"
export TEMP="$TMP_WIN_DIR"

echo "Build TMP=$TMP"
echo "Build TEMP=$TEMP"
echo "Starting FFmpeg build..."

"$MAKE_BIN" -j1 V=1

echo "Installing FFmpeg artifacts..."
"$MAKE_BIN" install >/dev/null

popd >/dev/null

cp "$FFMPEG_BUILD_DIR/install/lib/libavcodec.so"    "$JNI_OUT_DIR/libavcodec.so"
cp "$FFMPEG_BUILD_DIR/install/lib/libavformat.so"   "$JNI_OUT_DIR/libavformat.so"
cp "$FFMPEG_BUILD_DIR/install/lib/libavutil.so"     "$JNI_OUT_DIR/libavutil.so"
cp "$FFMPEG_BUILD_DIR/install/lib/libswresample.so" "$JNI_OUT_DIR/libswresample.so"

mkdir -p "$FFMPEG_HEADERS_OUT_DIR"
rm -rf \
  "$FFMPEG_HEADERS_OUT_DIR/libavcodec" \
  "$FFMPEG_HEADERS_OUT_DIR/libavformat" \
  "$FFMPEG_HEADERS_OUT_DIR/libavutil" \
  "$FFMPEG_HEADERS_OUT_DIR/libswresample"

cp -R "$FFMPEG_BUILD_DIR/install/include/libavcodec"    "$FFMPEG_HEADERS_OUT_DIR/"
cp -R "$FFMPEG_BUILD_DIR/install/include/libavformat"   "$FFMPEG_HEADERS_OUT_DIR/"
cp -R "$FFMPEG_BUILD_DIR/install/include/libavutil"     "$FFMPEG_HEADERS_OUT_DIR/"
cp -R "$FFMPEG_BUILD_DIR/install/include/libswresample" "$FFMPEG_HEADERS_OUT_DIR/"

echo "Install complete: libavcodec.so"
echo "Install complete: libavformat.so"
echo "Install complete: libavutil.so"
echo "Install complete: libswresample.so"
echo "Install complete: headers"
echo "Installed FFmpeg runtime libraries"