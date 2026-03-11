#!/usr/bin/env bash
set -euo pipefail

# Role:
#   Build libsrt shared library for Android and install it into the app JNI output tree.
#
# Responsibility boundary:
#   Resolves the Android NDK CMake toolchain, configures/builds/installs SRT,
#   and copies libsrt.so into app/src/main/jniLibs/<abi>.
#
# Behavioral guarantees:
#   - Uses the Android NDK CMake toolchain for cross-compilation.
#   - Produces a shared libsrt.so for the selected ABI.
#   - Installs SRT outputs into app/.native_deps/build-srt-<abi>/install.
#
# Preconditions:
#   - Argument 2, or ANDROID_NDK_HOME fallback, points to a valid Android NDK root.
#   - Required host build tools are installed and visible in PATH.
#
# Postconditions:
#   - libsrt.so exists under app/src/main/jniLibs/<abi>.
#   - SRT install tree exists under app/.native_deps/build-srt-<abi>/install.

trap 'exit_code=$?; echo "SRT build script failed (exit ${exit_code}) at line ${LINENO}: ${BASH_COMMAND}"; exit ${exit_code}' ERR

ABI="${1:-arm64-v8a}"
ANDROID_NDK_HOME="${2:-${ANDROID_NDK_HOME:-}}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT_DIR/app"
DEPS_DIR="$APP_DIR/.native_deps"
SRT_SRC_DIR="$DEPS_DIR/srt"
BUILD_DIR="$DEPS_DIR/build-srt-$ABI"
JNI_OUT_DIR="$APP_DIR/src/main/jniLibs/$ABI"

case "$ABI" in
  arm64-v8a)
    ANDROID_ABI="arm64-v8a"
    ;;
  armeabi-v7a)
    ANDROID_ABI="armeabi-v7a"
    ;;
  x86_64)
    ANDROID_ABI="x86_64"
    ;;
  *)
    echo "Unsupported ABI: $ABI"
    echo "Supported ABIs: arm64-v8a, armeabi-v7a, x86_64"
    exit 1
    ;;
esac

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "Android NDK path is not set."
  echo "Pass it as argument 2 or set ANDROID_NDK_HOME."
  exit 1
fi

# Normalize Windows path input when running under MSYS2/Git-style shells.
if command -v cygpath >/dev/null 2>&1; then
  ANDROID_NDK_HOME="$(cygpath -u "$ANDROID_NDK_HOME")"
fi

# Host environment normalization.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    export MSYSTEM="${MSYSTEM:-MINGW64}"
    export PATH="/mingw64/bin:/usr/bin:$PATH"
    HOST_TAG="windows-x86_64"
    ;;
  Linux*)
    HOST_TAG="linux-x86_64"
    ;;
  Darwin*)
    HOST_TAG="darwin-x86_64"
    ;;
  *)
    echo "Unsupported host OS: $(uname -s)"
    exit 1
    ;;
esac

echo "Host uname: $(uname -s)"
echo "MSYSTEM: ${MSYSTEM:-unset}"
echo "PATH: $PATH"
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

CMAKE_BIN="$(resolve_required_tool cmake)" || {
  echo "Missing required build tool: cmake"
  exit 1
}

NINJA_BIN="$(resolve_required_tool ninja)" || {
  echo "Missing required build tool: ninja"
  echo "Add Ninja to PATH or install it via Android Studio / SDK tools / MSYS2."
  exit 1
}

TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
if [[ ! -f "$TOOLCHAIN_FILE" ]]; then
  echo "Android NDK toolchain file not found: $TOOLCHAIN_FILE"
  echo "Check the NDK path passed into the script."
  exit 1
fi

mkdir -p "$DEPS_DIR"

if [[ ! -d "$SRT_SRC_DIR/.git" ]]; then
  "$GIT_BIN" clone --depth 1 --branch v1.5.4 https://github.com/Haivision/srt.git "$SRT_SRC_DIR"
fi

mkdir -p "$JNI_OUT_DIR"

echo "Resolved git: $GIT_BIN"
echo "Resolved cmake: $CMAKE_BIN"
echo "Resolved ninja: $NINJA_BIN"
echo "Using toolchain file: $TOOLCHAIN_FILE"

"$CMAKE_BIN" -G Ninja \
-S "$SRT_SRC_DIR" \
-B "$BUILD_DIR" \
-DCMAKE_BUILD_TYPE=Release \
-DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
-DANDROID_ABI="$ANDROID_ABI" \
-DANDROID_PLATFORM=android-24 \
-DCMAKE_INSTALL_PREFIX="$BUILD_DIR/install" \
-DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
-DENABLE_SHARED=ON \
-DENABLE_STATIC=OFF \
-DENABLE_APPS=OFF \
-DENABLE_CXX11=ON \
-DENABLE_UNITTESTS=OFF \
-DENABLE_ENCRYPTION=OFF

"$CMAKE_BIN" --build "$BUILD_DIR" --config Release
"$CMAKE_BIN" --install "$BUILD_DIR" --config Release

cp "$BUILD_DIR/install/lib/libsrt.so" "$JNI_OUT_DIR/libsrt.so"

echo "Built and installed: $JNI_OUT_DIR/libsrt.so"