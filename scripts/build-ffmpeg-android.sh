#!/usr/bin/env bash
set -euo pipefail

# Role:
#   Build FFmpeg shared libraries for Android and link them against a prebuilt SRT.
#
# Responsibility boundary:
#   Resolves the Android NDK toolchain, configures FFmpeg, builds/install libs,
#   and copies the runtime .so outputs into app/src/main/jniLibs/<abi>.
#
# Interaction constraints:
#   - Expects libsrt.so and SRT pkg-config metadata to already exist for the ABI.
#   - Uses in-source FFmpeg configure/make.
#   - On Windows hosts, avoids target-prefixed wrapper scripts and invokes the
#     real clang executables with --target=... to reduce wrapper-related failures.

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

for tool in git cmake make pkg-config; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing required build tool: $tool"
    echo "Install '$tool' and re-run ./gradlew :app:buildFfmpegArm64"
    echo "Tip (Windows): install Git Bash + MSYS2 and add make/pkg-config to PATH before running Gradle."
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
TARGET_TRIPLE="${ANDROID_TARGET}${API_LEVEL}"

HOST_TAG=""
case "$(uname -s)" in
  Linux*) HOST_TAG="linux-x86_64" ;;
  Darwin*) HOST_TAG="darwin-x86_64" ;;
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64" ;;
  *)
    echo "Unsupported host OS: $(uname -s)"
    echo "Supported hosts: Linux, macOS, Windows (Git Bash/MSYS2)"
    exit 1
    ;;
esac

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "Unable to find Android NDK toolchain prebuilt directory at: $TOOLCHAIN"
  echo "Check ANDROID_NDK_HOME and host OS compatibility."
  exit 1
fi

BIN_EXT=""
if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
  BIN_EXT=".exe"
fi

CLANG_BIN="$TOOLCHAIN/bin/clang${BIN_EXT}"
CLANGXX_BIN="$TOOLCHAIN/bin/clang++${BIN_EXT}"
AR="$TOOLCHAIN/bin/llvm-ar${BIN_EXT}"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib${BIN_EXT}"
STRIP="$TOOLCHAIN/bin/llvm-strip${BIN_EXT}"
PKG_CONFIG_BIN="$(command -v pkg-config)"

for path in "$CLANG_BIN" "$CLANGXX_BIN" "$AR" "$RANLIB" "$STRIP"; do
  if [[ ! -e "$path" ]]; then
    echo "Required NDK tool not found: $path"
    exit 1
  fi
done

# Critical change:
# Use the real clang executables with --target=... instead of the target-prefixed
# wrapper entry points. This matches current NDK guidance and avoids .cmd/script
# forwarding on Windows.
CC="$CLANG_BIN --target=$TARGET_TRIPLE"
CXX="$CLANGXX_BIN --target=$TARGET_TRIPLE"

mkdir -p "$FFMPEG_BUILD_DIR" "$JNI_OUT_DIR"

pushd "$FFMPEG_SRC_DIR" >/dev/null

# FFmpeg configure is in-source here; clear any stale wrapper-era config.
make distclean >/dev/null 2>&1 || true

PKG_CONFIG_LIBDIR="$SRT_BUILD_DIR/install/lib/pkgconfig"
PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"
export PKG_CONFIG_LIBDIR
export PKG_CONFIG_PATH

TMP_UNIX_DIR="/c/fftmp/ffmpeg-$ABI"
mkdir -p "$TMP_UNIX_DIR"

if command -v cygpath >/dev/null 2>&1; then
  TMP_WIN_DIR="$(cygpath -aw "$TMP_UNIX_DIR")"
else
  TMP_WIN_DIR="$TMP_UNIX_DIR"
fi

echo "Using TMP_UNIX_DIR=$TMP_UNIX_DIR"
echo "Using TMP_WIN_DIR=$TMP_WIN_DIR"

# Configure-time temp handling.
unset TMPDIR
export TMP="$TMP_WIN_DIR"
export TEMP="$TMP_WIN_DIR"

echo "Using TMP=$TMP"
echo "Using TEMP=$TEMP"
echo "Compiler: $CC"
ls -l "$CLANG_BIN" || true
"$CLANG_BIN" --version | head -n 1 || true

echo "Using pkg-config at: $PKG_CONFIG_BIN"
"$PKG_CONFIG_BIN" --modversion srt
"$PKG_CONFIG_BIN" --cflags --libs srt
echo "Using temp dir: $TMP_WIN_DIR"

# Some configure probes behave better with a Unix-looking TMPDIR under MSYS/Git Bash.
export TMPDIR="$TMP_UNIX_DIR"
unset TMP
unset TEMP

echo "Configure TMPDIR=$TMPDIR"

export CC
export CXX
export AR
export RANLIB
export STRIP

./configure \
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
  --disable-asm \
  --enable-network \
  --enable-libsrt \
  --enable-muxer=mpegts \
  --extra-cflags="-I$SRT_BUILD_DIR/install/include" \
  --extra-ldflags="-L$SRT_BUILD_DIR/install/lib" \
  --extra-libs="-lsrt"

# Build/install-time temp handling.
unset TMPDIR
export TMP="$TMP_WIN_DIR"
export TEMP="$TMP_WIN_DIR"

echo "Build TMP=$TMP"
echo "Build TEMP=$TEMP"

make -j1 V=1
make install

popd >/dev/null

cp "$FFMPEG_BUILD_DIR/install/lib/libavcodec.so"     "$JNI_OUT_DIR/libavcodec.so"
cp "$FFMPEG_BUILD_DIR/install/lib/libavformat.so"    "$JNI_OUT_DIR/libavformat.so"
cp "$FFMPEG_BUILD_DIR/install/lib/libavutil.so"      "$JNI_OUT_DIR/libavutil.so"
cp "$FFMPEG_BUILD_DIR/install/lib/libswresample.so"  "$JNI_OUT_DIR/libswresample.so"

echo "Built and installed FFmpeg runtime libs into $JNI_OUT_DIR"