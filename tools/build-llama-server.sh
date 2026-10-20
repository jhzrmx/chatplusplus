#!/usr/bin/env bash
# Cross-compiles upstream llama.cpp's `llama-server` for Android and drops it into
# app/src/main/jniLibs/<abi>/libllama_server.so (the app executes it from nativeLibraryDir).
#
# Requirements: git, cmake (3.14+), Android NDK r25+.
#   export ANDROID_NDK=/path/to/ndk
#   tools/build-llama-server.sh
#
# Options (env vars):
#   ABIS="arm64-v8a armeabi-v7a x86_64"   ABIs to build (default: arm64-v8a)
#   ANDROID_PLATFORM=android-28           API level of the binary (see README)
#   LLAMA_REF=master                      llama.cpp branch/tag to build
#   CPU_ARM_ARCH=armv8.2-a+dotprod        faster on modern phones, but crashes on older CPUs
set -euo pipefail

: "${ANDROID_NDK:?Set ANDROID_NDK to your NDK path}"
ABIS="${ABIS:-arm64-v8a}"
PLATFORM="${ANDROID_PLATFORM:-android-28}"
LLAMA_REF="${LLAMA_REF:-master}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/tools/llama.cpp"

if [ ! -d "$SRC" ]; then
  git clone --depth 1 --branch "$LLAMA_REF" https://github.com/ggml-org/llama.cpp "$SRC"
fi

for ABI in $ABIS; do
  BUILD="$ROOT/tools/build/$ABI"
  EXTRA=()
  if [ "$ABI" = "arm64-v8a" ] && [ -n "${CPU_ARM_ARCH:-}" ]; then
    EXTRA+=("-DGGML_CPU_ARM_ARCH=$CPU_ARM_ARCH")
  fi
  cmake -S "$SRC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="$PLATFORM" -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
    -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=ON \
    "${EXTRA[@]}"
  cmake --build "$BUILD" --target llama-server -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)"

  DEST="$ROOT/app/src/main/jniLibs/$ABI"
  mkdir -p "$DEST"
  cp "$BUILD/bin/llama-server" "$DEST/libllama_server.so"
  echo "==> $DEST/libllama_server.so"
done
