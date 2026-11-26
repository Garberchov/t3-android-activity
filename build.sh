#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@21/bin/java ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
bt="$sdk/build-tools/${ANDROID_BUILD_TOOLS:-35.0.0}"
jar="$sdk/platforms/android-36/android.jar"
[[ -f "$jar" && -x "$bt/aapt2" ]] || { echo "Install Android SDK Platform 36 and build tools 35.0.0 (or set ANDROID_BUILD_TOOLS)." >&2; exit 1; }
rm -rf build/gen build/classes build/dex
mkdir -p build/gen build/classes build/dex
"$bt/aapt2" compile --dir android/res -o build/resources.zip
"$bt/aapt2" link -I "$jar" --manifest android/AndroidManifest.xml --java build/gen -o build/unsigned.apk build/resources.zip
javac --release 11 -classpath "$jar" -d build/classes $(find android/src build/gen -name '*.java')
"$bt/d8" --lib "$jar" --min-api 36 --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$bt/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [[ -n "${T3_SIGNING_KEYSTORE:-}" ]]; then
  "$bt/apksigner" sign --ks "$T3_SIGNING_KEYSTORE" --ks-pass env:T3_SIGNING_PASSWORD --out build/t3-activity.apk build/aligned.apk
else
  echo "Built unsigned APK at build/aligned.apk. Set T3_SIGNING_KEYSTORE and T3_SIGNING_PASSWORD to sign."
  exit 0
fi
"$bt/apksigner" verify build/t3-activity.apk
