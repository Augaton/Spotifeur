#!/usr/bin/env bash
# Compiles the Java adapters in native-runtime/bootstrap into bootstrap.dex.
# The compile stubs only satisfy javac; at runtime the real types come from the
# DEX files of the supplied Spotify package.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
jar="${JAVA_HOME:+$JAVA_HOME/bin/}jar"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" && -f local.properties ]]; then
  sdk="$(sed -n 's/^sdk\.dir=//p' local.properties)"
fi
[[ -d "$sdk" ]] || { echo "Set ANDROID_HOME or sdk.dir in local.properties." >&2; exit 1; }

platform="$(ls -d "$sdk"/platforms/android-* | sort -V | tail -n 1)/android.jar"
d8="$(ls -d "$sdk"/build-tools/* | sort -V | tail -n 1)/d8"

work="app/build/generated/spotifyRuntime/bootstrap-build"
assets="app/build/generated/spotifyRuntime/assets/spotify-runtime"
rm -rf "$work"
mkdir -p "$work/classes" "$work/dex" "$assets"

find native-runtime/compile-stubs native-runtime/bootstrap -name '*.java' > "$work/sources.txt"
"$javac" --release 8 -nowarn -classpath "$platform" -d "$work/classes" @"$work/sources.txt"
# Only probe.bootstrap.* goes into the DEX; the stubs are excluded.
"$jar" cf "$work/bootstrap.jar" -C "$work/classes" probe
"$d8" --min-api 26 --lib "$platform" --output "$work/dex" "$work/bootstrap.jar"
cp "$work/dex/classes.dex" "$assets/bootstrap.dex"
