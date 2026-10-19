#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP_SRC="$ROOT_DIR/app/src/main/cpp/hedgehog_opus.c"
JNILIBS_DIR="$ROOT_DIR/app/src/main/jniLibs"

# Determine Android NDK path
NDK_DIR="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK_DIR" || ! -d "$NDK_DIR" ]]; then
    if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
        NDK_DIR="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -n 1)"
    fi
fi

if [[ -z "$NDK_DIR" || ! -d "$NDK_DIR" ]]; then
    echo "ERROR: Android NDK not found" >&2
    exit 1
fi

TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
echo "Using Android NDK toolchain: $TOOLCHAIN"

WORK_DIR="/tmp/hedgehog_ffmpeg_opus_build"
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

build_for_abi() {
    local ABI="$1"
    local HOST_TRIPLE="$2"
    local CLANG_TRIPLE="$3"
    local FF_ARCH="$4"
    local FF_CPU="$5"

    local OUT_SO="$JNILIBS_DIR/$ABI/libhedgehog_opus.so"
    if [[ -f "$OUT_SO" ]]; then
        echo "[$ABI] Cached $OUT_SO already exists, skipping build."
        return 0
    fi

    echo "=== Building minimal libopus + FFmpeg for $ABI ==="
    local ABI_WORK="$WORK_DIR/$ABI"
    local PREFIX="$ABI_WORK/install"
    rm -rf "$ABI_WORK"
    mkdir -p "$ABI_WORK" "$PREFIX"

    local CC="$TOOLCHAIN/bin/${CLANG_TRIPLE}24-clang"
    local CXX="$TOOLCHAIN/bin/${CLANG_TRIPLE}24-clang++"
    local AR="$TOOLCHAIN/bin/llvm-ar"
    local RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    local NM="$TOOLCHAIN/bin/llvm-nm"
    local STRIP="$TOOLCHAIN/bin/llvm-strip"

    # 1. Build libopus
    tar -xzf "$WORK_DIR/opus-1.5.2.tar.gz" -C "$ABI_WORK"
    pushd "$ABI_WORK/opus-1.5.2" > /dev/null
    CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" NM="$NM" STRIP="$STRIP" \
    CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections" \
    ./configure \
        --host="$HOST_TRIPLE" \
        --prefix="$PREFIX" \
        --disable-shared \
        --enable-static \
        --with-pic \
        --disable-doc \
        --disable-extra-programs > "$ABI_WORK/opus_cfg.log" 2>&1
    make -j"$(nproc)" > /dev/null
    make install > /dev/null
    popd > /dev/null

    # 2. Build libmp3lame
    tar -xzf "$WORK_DIR/lame-3.100.tar.gz" -C "$ABI_WORK"
    pushd "$ABI_WORK/lame-3.100" > /dev/null
    CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" NM="$NM" STRIP="$STRIP" \
    CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections" \
    ./configure \
        --host="$HOST_TRIPLE" \
        --prefix="$PREFIX" \
        --disable-shared \
        --enable-static \
        --with-pic \
        --disable-frontend \
        --disable-decoder \
        --disable-gtktest > "$ABI_WORK/lame_cfg.log" 2>&1
    make -j"$(nproc)" > /dev/null
    make install > /dev/null
    popd > /dev/null

    # 3. Build minimal FFmpeg (mp3/pcm decoders + libopus/libmp3lame encoders + opus/mp3 muxers)
    tar -xJf "$WORK_DIR/ffmpeg-6.1.2.tar.xz" -C "$ABI_WORK"
    pushd "$ABI_WORK/ffmpeg-6.1.2" > /dev/null
    PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" \
    ./configure \
        --prefix="$PREFIX" \
        --enable-cross-compile \
        --target-os=android \
        --arch="$FF_ARCH" \
        --cpu="$FF_CPU" \
        --cc="$CC" \
        --cxx="$CXX" \
        --ar="$AR" \
        --ranlib="$RANLIB" \
        --nm="$NM" \
        --strip="$STRIP" \
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
    make -j"$(nproc)" > /dev/null
    make install > /dev/null
    popd > /dev/null

    # 4. Link JNI shared library libhedgehog_opus.so
    mkdir -p "$JNILIBS_DIR/$ABI"
    "$CC" -shared -fPIC -O2 -pthread -ffunction-sections -fdata-sections \
        -I"$PREFIX/include" \
        "$CPP_SRC" \
        -L"$PREFIX/lib" \
        -lavformat -lavcodec -lswresample -lavutil -lopus -lmp3lame -lm \
        -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -s \
        -o "$OUT_SO"

    ls -lh "$OUT_SO"
}

build_for_abi "arm64-v8a"   "aarch64-linux-android"   "aarch64-linux-android"     "aarch64" "armv8-a"
build_for_abi "armeabi-v7a" "arm-linux-androideabi"   "armv7a-linux-androideabi"  "arm"     "armv7-a"
build_for_abi "x86_64"      "x86_64-linux-android"    "x86_64-linux-android"      "x86_64"  "x86-64"
