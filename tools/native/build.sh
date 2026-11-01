#!/usr/bin/env bash
# Builds libstaveav.so for one Android ABI: FFmpeg's audio demuxers, decoders, encoders and
# resampler, with libmp3lame, libopus and libvorbis, and the JNI wrapper in
# app/src/main/cpp/av, linked into one library that exports nothing but its JNI entry points.
# Squish's build.sh is the parent of this one, with the video half (dav1d, libjxl, LibRaw,
# swscale) taken out: a studio reads the sound of a video and never its picture.
#
#   tools/native/build.sh arm64-v8a [workdir]
#
# Runs on Linux (.github/workflows/native.yml). Wants ANDROID_NDK_ROOT and nasm, cmake, make,
# pkg-config on the PATH. The result lands in <workdir>/out/<abi>/libstaveav.so.
#
# Versions are pinned here and only here; a bump is a rebuild of all four.
set -euo pipefail

ABI=${1:?abi: arm64-v8a, armeabi-v7a, x86_64 or x86}
WORK=$(mkdir -p "${2:-$PWD/native-work}" && cd "${2:-$PWD/native-work}" && pwd)
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NDK=${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}
API=26
JOBS=$(nproc 2>/dev/null || echo 4)

FFMPEG_VERSION=8.1.3
LAME_VERSION=3.100
OPUS_VERSION=1.5.2
OGG_VERSION=1.3.5
VORBIS_VERSION=1.3.7

case "$ABI" in
  arm64-v8a)   TRIPLE=aarch64-linux-android;     ARCH=aarch64; CPU=armv8-a;  HOST=aarch64-linux-android; ASM="--enable-neon" ;;
  armeabi-v7a) TRIPLE=armv7a-linux-androideabi;  ARCH=arm;     CPU=armv7-a;  HOST=arm-linux-androideabi; ASM="--enable-neon" ;;
  x86_64)      TRIPLE=x86_64-linux-android;      ARCH=x86_64;  CPU=x86-64;   HOST=x86_64-linux-android;  ASM="" ;;
  # 32-bit x86 assembly is not position-independent; Android refuses text relocations.
  x86)         TRIPLE=i686-linux-android;        ARCH=x86;     CPU=i686;     HOST=i686-linux-android;    ASM="--disable-asm" ;;
  *) echo "unknown abi $ABI" >&2; exit 1 ;;
esac

TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
SYSROOT=$TC/sysroot
CC=$TC/bin/${TRIPLE}${API}-clang
CXX=$TC/bin/${TRIPLE}${API}-clang++
AR=$TC/bin/llvm-ar
NM=$TC/bin/llvm-nm
RANLIB=$TC/bin/llvm-ranlib
STRIP=$TC/bin/llvm-strip
PREFIX=$WORK/prefix/$ABI
SRC=$WORK/src
BUILD=$WORK/build/$ABI
OUT=$WORK/out/$ABI
mkdir -p "$PREFIX/lib/pkgconfig" "$PREFIX/include" "$SRC" "$BUILD" "$OUT"

BIN=$WORK/bin/$ABI
mkdir -p "$BIN"
ln -sf "$AR" "$BIN/ar"; ln -sf "$RANLIB" "$BIN/ranlib"
export PATH="$BIN:$PATH"
# 16 KB pages, which Android 15 devices may use and Play requires.
LDPAGE="-Wl,-z,max-page-size=16384"
COMMON_CFLAGS="-O2 -fPIC -fdata-sections -ffunction-sections"
export CC CXX AR RANLIB STRIP
export CFLAGS="$COMMON_CFLAGS" LDFLAGS="$LDPAGE"

fetch() { # url file
  [ -s "$SRC/$2" ] || curl -fsSL --retry 3 -o "$SRC/$2" "$1"
}

# A fresh build directory for one library; the :? forms make an unset name an error, never a
# removal at the root.
fresh() { # name
  local dir="${BUILD:?}/${1:?}"
  [ -d "$dir" ] && find "$dir" -mindepth 1 -delete
  mkdir -p "$dir"
  echo "$dir"
}

autotools_lib() { # name tarball-url tarball configure-args...
  local name=$1 url=$2 tar=$3; shift 3
  [ -f "$PREFIX/lib/lib$name.a" ] && return
  fetch "$url" "$tar"
  local dir; dir=$(fresh "$name")
  tar xf "$SRC/$tar" -C "$dir" --strip-components=1
  (cd "$dir" && ./configure --host="$HOST" --prefix="$PREFIX" --disable-shared --enable-static --with-pic "$@" \
      && make -j"$JOBS" && make install)
}

# ---- LAME (LGPL): MP3 encoding, the button Lexis charges for -------------
build_lame() {
  autotools_lib mp3lame "https://downloads.sourceforge.net/project/lame/lame/$LAME_VERSION/lame-$LAME_VERSION.tar.gz" lame.tar.gz \
    --disable-frontend --disable-decoder --disable-gtktest
}

# ---- Opus (BSD) ----------------------------------------------------------
build_opus() {
  autotools_lib opus "https://downloads.xiph.org/releases/opus/opus-$OPUS_VERSION.tar.gz" opus.tar.gz \
    --disable-doc --disable-extra-programs
}

# ---- Ogg and Vorbis (BSD) ------------------------------------------------
build_vorbis() {
  autotools_lib ogg "https://downloads.xiph.org/releases/ogg/libogg-$OGG_VERSION.tar.gz" ogg.tar.gz
  [ -f "$PREFIX/lib/libvorbis.a" ] && return
  fetch "https://downloads.xiph.org/releases/vorbis/libvorbis-$VORBIS_VERSION.tar.gz" vorbis.tar.gz
  local dir; dir=$(fresh vorbis)
  tar xf "$SRC/vorbis.tar.gz" -C "$dir" --strip-components=1
  # libvorbis 1.3.7's configure adds -mno-ieee-fp for i?86 hosts, a gcc-only flag the NDK's
  # clang refuses ("unknown argument"): the x86 build died on it until it was taken out here.
  sed -i 's/-mno-ieee-fp//g' "$dir/configure"
  (cd "$dir" && PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" ./configure --host="$HOST" --prefix="$PREFIX" --disable-shared --enable-static --with-pic \
      --with-ogg="$PREFIX" --disable-oggtest --disable-docs --disable-examples && make -j"$JOBS" && make install)
}

# ---- FFmpeg (GPL build, audio only) -------------------------------------
# Every component name below was checked against 8.1.3's own registries (allformats.c,
# allcodecs.c, parsers.c, bitstream_filters.c) on 1 November 2026. MP4/M4A are read by `mov`,
# Opus files by `ogg`, WebM by `matroska`, ADTS by `aac`, MPEG-PS is `mpegps`, G.722/G.726 are
# `adpcm_g722`/`adpcm_g726`, 8SVX is `eightsvx_*`. There is no --disable-postproc: 8.0 removed
# libpostproc and configure refuses the switch.
build_ffmpeg() {
  [ -f "$PREFIX/lib/libavcodec.a" ] && return
  fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" ffmpeg.tar.xz
  local dir; dir=$(fresh ffmpeg)
  tar xJf "$SRC/ffmpeg.tar.xz" -C "$dir" --strip-components=1
  (cd "$dir" && PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" ./configure \
      --prefix="$PREFIX" --target-os=android --arch="$ARCH" --cpu="$CPU" --enable-cross-compile \
      --cc="$CC" --cxx="$CXX" --ar="$AR" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP" \
      --sysroot="$SYSROOT" --pkg-config=pkg-config --pkg-config-flags=--static \
      --extra-cflags="$COMMON_CFLAGS -I$PREFIX/include" \
      --extra-ldflags="-L$PREFIX/lib $LDPAGE" \
      --extra-libs="-lc++_static -lc++abi -lm" \
      --enable-static --disable-shared --enable-pic --enable-gpl --enable-version3 \
      --disable-doc --disable-debug --disable-network --disable-devices --disable-hwaccels \
      --disable-mediacodec --disable-jni --disable-vulkan --disable-iconv --disable-autodetect \
      --disable-programs --disable-avdevice --disable-avfilter --disable-swscale \
      --disable-encoders --disable-decoders --disable-muxers --disable-demuxers --disable-parsers --disable-bsfs --disable-protocols \
      --enable-zlib --enable-libmp3lame --enable-libopus --enable-libvorbis \
      --enable-demuxer='mp3,aac,ac3,ape,aiff,asf,au,avi,caf,dsf,dts,eac3,flac,flv,matroska,mov,mpc,mpc8,ogg,pcm_*,tak,tta,w64,wav,wv,xwma,amr,gsm,mlp,truehd,voc,rm,mpegps,mpegts,shorten,g722,g723_1,g726,g729,ilbc,sbc,aptx,aptx_hd,codec2,oma,xa,vag,wsaud,bink' \
      --enable-decoder='mp3*,aac*,ac3*,eac3,alac,ape,flac,vorbis,opus,pcm_*,adpcm_*,wmav1,wmav2,wmapro,wmalossless,wmavoice,mp2*,mp1*,dca,truehd,mlp,tta,tak,wavpack,mpc7,mpc8,amrnb,amrwb,gsm,gsm_ms,g723_1,g729,ilbc,sbc,aptx,aptx_hd,atrac*,cook,ra_144,ra_288,sipr,shorten,dsd_*,libopus,libvorbis,qdm2,qdmc,nellymoser,bink*,twinvq,smackaud,comfortnoise,s302m,dolby_e,evrc,qcelp,eightsvx_*,metasound,on2avc,interplay_acm,xma1,xma2,mace3,mace6,imc,iac,truespeech,vmdaudio,dss_sp,dst,msnsiren,fastaudio,hca,siren,hcom' \
      --enable-encoder='libmp3lame,libopus,libvorbis,flac,alac,aac,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_s16be,pcm_s24be,pcm_f32be,pcm_u8,pcm_alaw,pcm_mulaw,wavpack,tta,adpcm_ima_wav,adpcm_ms,adpcm_g722,adpcm_g726,comfortnoise,wmav2,ac3,eac3' \
      --enable-muxer='mp3,ogg,opus,flac,ipod,mp4,mov,caf,wav,w64,aiff,au,voc,adts,wv,tta,matroska,webm,oga,ac3,eac3,asf,mpegts' \
      --enable-parser='aac,aac_latm,ac3,flac,mpegaudio,opus,vorbis,dca,mlp,tak,gsm,amr,g729,cook,sipr' \
      --enable-bsf='aac_adtstoasc,remove_extradata,setts,pcm_rechunk' \
      --enable-protocol=file,pipe \
      $ASM) || { tail -60 "$dir/ffbuild/config.log"; exit 1; }
  make -C "$dir" -j"$JOBS"
  make -C "$dir" install
}

# ---- the library ----------------------------------------------------------
build_library() {
  local cpp=$ROOT/app/src/main/cpp/av
  local o=$BUILD/jni && mkdir -p "$o"
  "$CC" $COMMON_CFLAGS -Wall -Wextra -Wno-unused-parameter -Werror=implicit-function-declaration \
    -I"$PREFIX/include" -c "$cpp/staveav.c" -o "$o/staveav.o"
  "$CC" -shared -o "$OUT/libstaveav.so" "$o/staveav.o" \
    -L"$PREFIX/lib" -lavformat -lavcodec -lswresample -lavutil -lmp3lame -lopus -lvorbisenc -lvorbis -logg \
    -lz -lm -llog -landroid -static-libstdc++ \
    -Wl,--version-script="$cpp/exports.map" -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-Bsymbolic \
    -Wl,--no-undefined -Wl,-z,noexecstack $LDPAGE
  "$STRIP" --strip-unneeded "$OUT/libstaveav.so"
  ls -l "$OUT/libstaveav.so"
}

build_lame
build_opus
build_vorbis
build_ffmpeg
build_library
