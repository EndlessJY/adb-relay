#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$SDK_ROOT/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$SDK_ROOT/platforms/android-34/android.jar}"
OUT="$ROOT/build"
APK="$ROOT/adb-relay-agent-debug.apk"
KEYSTORE="$ROOT/debug.keystore"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/generated" "$OUT/dex"

"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/src/main/res" -o "$OUT/compiled.zip"
"$BUILD_TOOLS/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/src/main/AndroidManifest.xml" \
  --java "$OUT/generated" \
  --min-sdk-version 23 \
  --target-sdk-version 28 \
  -o "$OUT/resources.apk" \
  "$OUT/compiled.zip"

find "$ROOT/src/main/java" "$OUT/generated" -name '*.java' > "$OUT/sources.txt"
javac -parameters -source 8 -target 8 -Xlint:-options \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

"$BUILD_TOOLS/d8" --min-api 23 --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/resources.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -r "$OUT/unsigned.apk" classes.dex)

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias debug \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=ADB Relay Debug,O=Local,C=US" >/dev/null
fi

"$BUILD_TOOLS/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$APK" \
  "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$APK"

echo "Built $APK"
