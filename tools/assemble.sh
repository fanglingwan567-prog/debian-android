#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
AAPT2=$(command -v aapt2) || { echo 'Termux aapt2 is required' >&2; exit 1; }
exec "${GRADLE:-./gradlew}" --offline "-Pandroid.aapt2FromMavenOverride=$AAPT2" "$@"
