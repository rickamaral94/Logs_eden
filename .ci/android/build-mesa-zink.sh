#!/bin/sh

# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

# Cross-builds Mesa's EGL with the Zink driver (desktop OpenGL 4.6 over Vulkan) for arm64
# Android and copies the libraries into the app's jniLibs, where the OpenGL backend loads
# them at run time. Needs meson, ninja, flex, bison, patchelf, python3 with mako and
# pyyaml, and the NDK.
#
#   MESA_NDK          NDK root (default: $ANDROID_HOME/ndk/28.2.13676358, the app's NDK)
#   MESA_VERSION      Mesa tag (default: mesa-25.2.0)
#   MESA_WORK         Scratch directory (default: build/mesa in the repository)

set -e

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${MESA_NDK:=$ANDROID_HOME/ndk/28.2.13676358}"
: "${MESA_VERSION:=mesa-25.2.0}"
: "${MESA_WORK:=$ROOT/build/mesa}"
API=33
OUT="$ROOT/src/android/app/src/main/jniLibs/arm64-v8a"
TOOLCHAIN="$MESA_NDK/toolchains/llvm/prebuilt/linux-x86_64"

mkdir -p "$MESA_WORK"
cd "$MESA_WORK"
if [ ! -d src ]; then
    git clone --depth 1 --branch "$MESA_VERSION" https://gitlab.freedesktop.org/mesa/mesa.git src
    git -C src apply "$ROOT/.ci/android/mesa/eden-zink.patch"
fi

cat > cross.txt <<EOF
[binaries]
c = '$TOOLCHAIN/bin/aarch64-linux-android$API-clang'
cpp = '$TOOLCHAIN/bin/aarch64-linux-android$API-clang++'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'
pkg-config = '/bin/false'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'

[built-in options]
c_args = ['-O2']
cpp_args = ['-O2']
c_link_args = ['-static-libstdc++']
cpp_link_args = ['-static-libstdc++']
EOF

# The library suffix keeps these apart from the system libEGL.so already in the process.
rm -rf build
meson setup build src --cross-file cross.txt --buildtype=release --force-fallback-for=libdrm -Dallow-fallback-for=libdrm \
    -Dplatforms=android -Dandroid-stub=true -Dandroid-libbacktrace=disabled \
    -Dplatform-sdk-version=$API \
    -Dgallium-drivers=zink -Dvulkan-drivers= \
    -Degl=enabled -Degl-lib-suffix=_mesa -Dopengl=true -Dgles1=disabled -Dgles2=disabled \
    -Dglx=disabled -Dgbm=disabled -Dllvm=disabled -Dshared-llvm=disabled \
    -Dvalgrind=disabled -Dlibunwind=disabled -Dzstd=disabled -Dexpat=disabled \
    -Dbuild-tests=false -Dtools=
ninja -C build

# libcutils, libhardware and libsync are not public NDK libraries: ship Mesa's stubs of them
# (unused on the surfaceless platform the backend uses) under names the system does not have.
# liblog and libnativewindow are public and stay as they are.
mkdir -p "$OUT" pkg
cp build/src/egl/libEGL_mesa.so build/src/gallium/targets/dri/libgallium_dri.so pkg/
cp build/subprojects/libdrm-*/libdrm.so pkg/libdrm_mesa.so
for stub in cutils hardware sync; do
    cp "build/src/android_stub/lib$stub.so" "pkg/lib${stub}_mesa.so"
done
for lib in pkg/*.so; do
    patchelf --remove-rpath "$lib"
    patchelf --set-soname "$(basename "$lib")" "$lib"
    for dep in drm cutils hardware sync; do
        patchelf --replace-needed "lib$dep.so" "lib${dep}_mesa.so" "$lib"
    done
    "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded -o "$OUT/$(basename "$lib")" "$lib"
    echo "-- $(basename "$lib")"
done
