#!/bin/bash
# Build manual de NX Link (sin Gradle): aapt2 + javac + d8 + apksigner
set -e
SDK=$HOME/android-sdk
BT=$SDK/build-tools/35.0.0
AJ=$SDK/platforms/android-35/android.jar
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir app/src/main/res -o build/res.zip

echo "== aapt2 link =="
"$BT/aapt2" link -o build/app.base.apk -I "$AJ" \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 29 --target-sdk-version 34 \
  --version-code 6 --version-name "1.5" \
  --java build/gen \
  build/res.zip

echo "== javac =="
javac -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
  -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -d build/classes \
  $(find app/src/main/java build/gen -name '*.java')

echo "== d8 =="
"$BT/d8" --release --lib "$AJ" --min-api 29 --output build/dex \
  $(find build/classes -name '*.class')

echo "== empaquetado =="
cp build/app.base.apk build/app.unsigned.apk
(cd build/dex && zip -q -u "$ROOT/build/app.unsigned.apk" classes.dex)

"$BT/zipalign" -f 4 build/app.unsigned.apk build/app.aligned.apk

echo "== firma =="
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass nxlink123 -keypass nxlink123 \
    -alias nxlink -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=NXLink Debug" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:nxlink123 --key-pass pass:nxlink123 \
  --out "$ROOT/NXLink-v1.5.apk" build/app.aligned.apk

"$BT/apksigner" verify --print-certs "$ROOT/NXLink-v1.5.apk" | head -3
ls -la "$ROOT/NXLink-v1.5.apk"
echo "OK"
