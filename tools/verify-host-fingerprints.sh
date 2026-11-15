#!/usr/bin/env bash
# Scan every production fingerprint without installing or executing either host APK.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17+ directory}"
if [[ "$#" -lt 1 ]]; then
  echo "Usage: $0 HOST_APK [HOST_APK ...]" >&2
  exit 2
fi
probe_dir="$(mktemp -d "${TMPDIR:-/tmp}/colorpods-fingerprints.XXXXXX")"
trap 'rm -rf "$probe_dir"' EXIT
./gradlew :app:assembleDebug
module_apk="$PWD/app/build/outputs/apk/debug/app-debug.apk"
# compileSdk/buildTools match the module build; no dependency downloads outside Gradle.
d8="$ANDROID_HOME/build-tools/36.0.0/d8"
mkdir -p "$probe_dir/classes" "$probe_dir/dex"
unzip -p "$module_apk" lib/arm64-v8a/libdexkit.so > "$probe_dir/libdexkit.so"
dexkit_aar="$(rg --files "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.luckypray/dexkit/2.3.0" | rg '\.aar$' | sed -n '1p')"
unzip -p "$dexkit_aar" classes.jar > "$probe_dir/dexkit.jar"
"$JAVA_HOME/bin/javac" -cp "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:$probe_dir/dexkit.jar" -d "$probe_dir/classes" tools/HostFingerprintProbe.java
"$JAVA_HOME/bin/jar" cf "$probe_dir/probe.jar" -C "$probe_dir/classes" .
"$d8" --min-api 33 --lib "$ANDROID_HOME/platforms/android-36/android.jar" --classpath app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes --classpath "$probe_dir/dexkit.jar" --output "$probe_dir/dex" "$probe_dir/probe.jar"
# Only task-owned files under this temporary device directory are replaced.
remote="/data/local/tmp/colorpods-fingerprints"
adb shell mkdir -p "$remote"
adb push "$module_apk" "$remote/module.apk"
adb push "$probe_dir/libdexkit.so" "$remote/libdexkit.so"
adb push "$probe_dir/dex/classes.dex" "$remote/probe.dex"
apk_args=""
index=0
for apk in "$@"; do
  index=$((index+1))
  adb push "$apk" "$remote/host-$index.apk"
  apk_args="$apk_args $remote/host-$index.apk"
done
adb shell "CLASSPATH=$remote/probe.dex:$remote/module.apk app_process /system/bin com.tlsu.pods.HostFingerprintProbe $remote/libdexkit.so$apk_args"
