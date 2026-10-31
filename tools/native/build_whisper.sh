#!/usr/bin/env bash
# Builds libstavewhisper.so for one Android ABI: whisper.cpp (MIT) and its ggml, static,
# behind app/src/main/cpp/whisper/stavewhisper.cpp, exporting only its JNI entry points.
# The model is not in it: it comes with the asset pack, the full APK, or a file the person
# imports (models/ModelStore.kt).
#
#   tools/native/build_whisper.sh arm64-v8a [workdir]
#
# Runs on Linux (.github/workflows/whisper.yml). Wants ANDROID_NDK_ROOT and cmake. The result
# lands in <workdir>/out/<abi>/libstavewhisper.so.
set -euo pipefail
ABI=${1:?abi}
WORK=$(mkdir -p "${2:-$PWD/whisper-work}" && cd "${2:-$PWD/whisper-work}" && pwd)
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NDK=${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}
API=26
JOBS=$(nproc 2>/dev/null || echo 4)

WHISPER_VERSION=v1.7.5

case "$ABI" in
  arm64-v8a)   TRIPLE=aarch64-linux-android ;;
  armeabi-v7a) TRIPLE=armv7a-linux-androideabi ;;
  x86_64)      TRIPLE=x86_64-linux-android ;;
  x86)         TRIPLE=i686-linux-android ;;
  *) echo "unknown abi $ABI" >&2; exit 1 ;;
esac
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
CXX=$TC/bin/${TRIPLE}${API}-clang++
STRIP=$TC/bin/llvm-strip

SRC=$WORK/src/whisper.cpp-${WHISPER_VERSION#v}
if [ ! -d "$SRC" ]; then
  mkdir -p "$WORK/src"
  curl -fsSL --retry 3 "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/$WHISPER_VERSION.tar.gz" | tar -xz -C "$WORK/src"
fi

BUILD=$WORK/build/$ABI
cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF \
  -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF \
  -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections" \
  -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections"
cmake --build "$BUILD" -j "$JOBS"

LIBS=$(find "$BUILD" -name 'libwhisper.a'; find "$BUILD" -name 'libggml.a'; find "$BUILD" -name 'libggml-cpu*.a'; find "$BUILD" -name 'libggml-base.a')
OUT=$WORK/out/$ABI; mkdir -p "$OUT"
cat > "$WORK/exports.map" <<'MAP'
{ global: Java_com_layerbit_stave_native_WhisperNative_*; local: *; };
MAP
"$CXX" -shared -fPIC -O2 -std=c++17 \
  -I"$SRC/include" -I"$SRC/ggml/include" \
  "$ROOT/app/src/main/cpp/whisper/stavewhisper.cpp" \
  -Wl,--start-group $LIBS -Wl,--end-group \
  -llog -static-libstdc++ -Wl,--gc-sections -Wl,--version-script="$WORK/exports.map" -Wl,-z,max-page-size=16384 \
  -o "$OUT/libstavewhisper.so"
"$STRIP" --strip-unneeded "$OUT/libstavewhisper.so"
ls -l "$OUT/libstavewhisper.so"
