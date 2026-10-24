#!/usr/bin/env bash
# Builds a size-optimized llama.cpp llama-server for Android.
# Output: app/src/main/jniLibs/<abi>/libllama_server.so
#
# Requirements: git, cmake, Android NDK, and preferably Ninja.
#
# Usage:
#   export ANDROID_NDK=/path/to/android-ndk
#   tools/build-llama-server.sh
#
# Optional environment variables:
#   ABIS="arm64-v8a"                  ABIs to build
#   ANDROID_PLATFORM=android-28       Minimum Android API
#   LLAMA_REF=master                  llama.cpp branch/tag
#   CPU_ARM_ARCH=armv8.2-a+dotprod    Optional ARM CPU tuning

set -Eeuo pipefail

: "${ANDROID_NDK:?Set ANDROID_NDK to your Android NDK path}"

ABIS="${ABIS:-arm64-v8a}"
PLATFORM="${ANDROID_PLATFORM:-android-28}"
LLAMA_REF="${LLAMA_REF:-master}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS_DIR="$ROOT/tools"
SRC="$TOOLS_DIR/llama.cpp"

# ---- Locate NDK llvm-strip ----
STRIP="${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

if [ ! -x "$STRIP" ]; then
  STRIP="$(find "$ANDROID_NDK/toolchains/llvm/prebuilt" \
    -type f -path '*/bin/llvm-strip' -print -quit 2>/dev/null || true)"
fi

if [ -z "$STRIP" ] || [ ! -x "$STRIP" ]; then
  echo "ERROR: Could not find executable llvm-strip in $ANDROID_NDK"
  exit 1
fi

# ---- Clone llama.cpp ----
if [ ! -d "$SRC/.git" ]; then
  mkdir -p "$TOOLS_DIR"
  git clone --depth 1 --branch "$LLAMA_REF" \
    https://github.com/ggml-org/llama.cpp "$SRC"
fi

# ---- Build each requested ABI ----
for ABI in $ABIS; do
  echo
  echo "========================================"
  echo "Building llama-server for $ABI"
  echo "========================================"

  BUILD="$TOOLS_DIR/build/$ABI"
  DEST="$ROOT/app/src/main/jniLibs/$ABI"
  BIN="$BUILD/bin/llama-server"

  mkdir -p "$DEST"

  # Optional CPU-specific tuning, as in the original script.
  EXTRA=()
  if [ "$ABI" = "arm64-v8a" ] && [ -n "${CPU_ARM_ARCH:-}" ]; then
    EXTRA+=("-DGGML_CPU_ARM_ARCH=$CPU_ARM_ARCH")
  fi

  # ---- Configure size-optimized Release build ----
  cmake -S "$SRC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="$PLATFORM" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS_RELEASE="-Oz -DNDEBUG -ffunction-sections -fdata-sections" \
    -DCMAKE_CXX_FLAGS_RELEASE="-Oz -DNDEBUG -ffunction-sections -fdata-sections" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,--gc-sections" \
    -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DGGML_CUDA=OFF \
    -DGGML_VULKAN=OFF \
    -DGGML_SYCL=OFF \
    -DGGML_METAL=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=ON \
    "${EXTRA[@]}"

  # ---- Compile ----
  JOBS="${BUILD_JOBS:-2}"

  cmake --build "$BUILD" \
    --target llama-server \
    --parallel "$JOBS"

  if [ ! -f "$BIN" ]; then
    echo "ERROR: llama-server executable not found: $BIN"
    find "$BUILD" -type f -name 'llama-server' -print
    exit 1
  fi

  # ---- Strip unnecessary symbols ----
  "$STRIP" --strip-unneeded "$BIN"

  # ---- Export under the app's expected filename ----
  cp "$BIN" "$DEST/libllama_server.so"
  chmod 755 "$DEST/libllama_server.so"

  echo
  echo "===== BUILD COMPLETE: $ABI ====="
  file "$DEST/libllama_server.so"
  ls -lh "$DEST/libllama_server.so"
  du -h "$DEST/libllama_server.so"
done

echo
echo "All requested ABI builds completed successfully."