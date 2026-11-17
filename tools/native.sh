#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
NDK=${ANDROID_NDK_HOME:-$HOME/android-ndk-r29}; sysroot=$(find "$NDK/toolchains/llvm/prebuilt" -type d -name sysroot | head -1)
# Host clang on Termux with the NDK sysroot, no dependency on Termux at runtime.
mkdir -p app/build/native app/src/main/jniLibs/arm64-v8a
clang --target=aarch64-linux-android28 --sysroot="$sysroot" -fPIE -O2 -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=2 -c app/src/main/cpp/launcher.c -o app/build/native/launcher.o
lib="$sysroot/usr/lib/aarch64-linux-android/28"
ld.lld -pie -z relro -z now -z max-page-size=16384 --dynamic-linker=/system/bin/linker64 "$lib/crtbegin_dynamic.o" app/build/native/launcher.o -L"$lib" -lc "$lib/crtend_android.o" -o app/src/main/jniLibs/arm64-v8a/libbirch.so
