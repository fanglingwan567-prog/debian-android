#!/bin/sh
# Compile only the fixture. All app classes, including Kotlin stdlib, come from the built APK.
set -eu
cd "$(dirname "$0")/.."
suite=$1
case "$suite" in
    StartupSmoke|RegistrySmoke|RegistryHttpSmoke|ConsoleSmoke) ;;
    *) exit 2 ;;
esac
shift
apk=$PWD/app/build/outputs/apk/debug/app-debug.apk
android=${ANDROID_HOME:-$HOME/android-sdk}/platforms/android-34/android.jar
out=$(mktemp -d "$PWD/app/build/art-test.XXXXXX")
trap 'rm -rf -- "$out"' EXIT
mkdir -p "$out/classes" "$out/files" "$out/android/dalvik-cache"
javac --release 8 -sourcepath tests -cp "app/build/tmp/kotlin-classes/debug:$android" -d "$out/classes" "tests/$suite.java"
jar cf "$out/input.jar" -C "$out/classes" .
d8 --min-api 28 --lib "$android" --output "$out/test.jar" "$out/input.jar"
cp "$apk" "$out/app.apk"
chmod 444 "$out/test.jar" "$out/app.apk"
ANDROID_DATA="$out/android" LD_PRELOAD= LD_LIBRARY_PATH= /apex/com.android.art/bin/dalvikvm -Djava.io.tmpdir="$out" -cp "$out/test.jar:$out/app.apk" "app.birch.debian.$suite" "$out/files" "$@"
