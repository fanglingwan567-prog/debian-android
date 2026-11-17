#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
android=${ANDROID_HOME:-$HOME/android-sdk}/platforms/android-34/android.jar
mkdir -p app/build/registry-tests
build=$(mktemp -d "$PWD/app/build/registry-tests/run.XXXXXX")
mkdir -p "$build/classes" "$build/work" "$build/android/dalvik-cache"
src=app/src/main/java/app/birch/debian
javac --release 8 -classpath "$android" -d "$build/classes" "$src/DebianRegistry.java" "$src/ImageStore.java" tests/RegistrySmoke.java
jar cf "$build/input.jar" -C "$build/classes" .
d8 --min-api 28 --lib "$android" --output "$build/test.jar" "$build/input.jar"
chmod 444 "$build/test.jar"
ANDROID_DATA="$build/android" LD_PRELOAD= LD_LIBRARY_PATH= /apex/com.android.art/bin/dalvikvm -Djava.io.tmpdir="$build" -cp "$build/test.jar" app.birch.debian.RegistrySmoke "$build/work" "$@"
