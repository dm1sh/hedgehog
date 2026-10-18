#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP_SRC="$ROOT_DIR/app/src/main/cpp/hedgehog_opus.c"
OUT_DIR="$ROOT_DIR/cli/src/main/resources/NATIVE/linux-x64"
OUT_SO="$OUT_DIR/libhedgehog_opus.so"

if [[ -f "$OUT_SO" ]]; then
    echo "[linux-x64] Cached $OUT_SO already exists, skipping build."
    exit 0
fi

JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/jdk-11}"
if [[ ! -f "$JAVA_HOME_DIR/include/jni.h" ]]; then
    JAVA_BIN="$(command -v javac || command -v java)"
    JAVA_HOME_DIR="$(dirname "$(dirname "$(readlink -f "$JAVA_BIN")")")"
fi

echo "Using JAVA_HOME: $JAVA_HOME_DIR"

WORK_DIR="/tmp/hedgehog_ffmpeg_opus_linux_build"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

if [[ ! -f opus-1.5.2.tar.gz ]]; then
    curl -sL https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz -o opus-1.5.2.tar.gz
fi
if [[ ! -f lame-3.100.tar.gz ]]; then
    curl -sL https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz -o lame-3.100.tar.gz
fi
if [[ ! -f ffmpeg-6.1.2.tar.xz ]]; then
    curl -sL https://ffmpeg.org/releases/ffmpeg-6.1.2.tar.xz -o ffmpeg-6.1.2.tar.xz
fi

ABI_WORK="$WORK_DIR/linux-x64"
PREFIX="$ABI_WORK/install"
JOBS="$(nproc)"
if [[ "$JOBS" -gt 4 ]]; then
    JOBS=4
fi
rm -rf "$ABI_WORK"
mkdir -p "$ABI_WORK" "$PREFIX"

# 1. Build libopus
tar -xzf "$WORK_DIR/opus-1.5.2.tar.gz" -C "$ABI_WORK"
pushd "$ABI_WORK/opus-1.5.2" > /dev/null
CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections" \
./configure \
    --prefix="$PREFIX" \
    --disable-shared \
    --enable-static \
    --with-pic \
    --disable-doc \
    --disable-extra-programs > "$ABI_WORK/opus_cfg.log" 2>&1
make -j"$JOBS" > /dev/null
make install > /dev/null
popd > /dev/null

# 2. Build libmp3lame
tar -xzf "$WORK_DIR/lame-3.100.tar.gz" -C "$ABI_WORK"
pushd "$ABI_WORK/lame-3.100" > /dev/null
CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections" \
./configure \
    --prefix="$PREFIX" \
    --disable-shared \
    --enable-static \
    --with-pic \
    --disable-frontend \
    --disable-decoder \
    --disable-gtktest > "$ABI_WORK/lame_cfg.log" 2>&1
make -j"$JOBS" > /dev/null
make install > /dev/null
popd > /dev/null

# 3. Build minimal FFmpeg (mp3/pcm decoders + libopus/libmp3lame encoders + opus/mp3 muxers)
tar -xJf "$WORK_DIR/ffmpeg-6.1.2.tar.xz" -C "$ABI_WORK"
pushd "$ABI_WORK/ffmpeg-6.1.2" > /dev/null
PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" \
./configure \
    --prefix="$PREFIX" \
    --pkg-config=pkg-config \
    --pkg-config-flags="--static" \
    --extra-cflags="-I$PREFIX/include -O2 -fPIC -ffunction-sections -fdata-sections" \
    --extra-ldflags="-L$PREFIX/lib" \
    --disable-everything \
    --disable-programs \
    --disable-doc \
    --disable-network \
    --disable-autodetect \
    --disable-avdevice \
    --disable-swscale \
    --disable-postproc \
    --disable-avfilter \
    --disable-pthreads \
    --disable-asm \
    --enable-small \
    --enable-static \
    --disable-shared \
    --enable-pic \
    --enable-avutil \
    --enable-avcodec \
    --enable-avformat \
    --enable-swresample \
    --enable-libopus \
    --enable-libmp3lame \
    --enable-decoder=mp3,mp3float,pcm_s16le \
    --enable-encoder=libopus,libmp3lame \
    --enable-demuxer=mp3,wav,s16le,ogg \
    --enable-muxer=opus,ogg,mp3 \
    --enable-parser=mpegaudio,opus \
    --enable-protocol=file > "$ABI_WORK/ffmpeg_cfg.log" 2>&1
make -j"$JOBS" > /dev/null
make install > /dev/null
popd > /dev/null

# 4. Link JNI shared library libhedgehog_opus.so for Linux x86_64
mkdir -p "$OUT_DIR"
gcc -shared -fPIC -O2 -ffunction-sections -fdata-sections \
    -I"$JAVA_HOME_DIR/include" \
    -I"$JAVA_HOME_DIR/include/linux" \
    -I"$PREFIX/include" \
    "$CPP_SRC" \
    -L"$PREFIX/lib" \
    -Wl,-Bsymbolic \
    -lavformat -lavcodec -lswresample -lavutil -lopus -lmp3lame -lm \
    -Wl,--gc-sections -Wl,--exclude-libs,ALL -s \
    -o "$OUT_SO"

rm -rf "$WORK_DIR"
ls -lh "$OUT_SO"
