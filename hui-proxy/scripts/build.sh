#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/android"
OUT="$ROOT/out"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
TOOLS="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/vendor" "$OUT/apk/lib/arm64-v8a"
MIHOMO="$OUT/vendor/libmihomo-v0.3.5.aar"
KOTLIN="$OUT/vendor/kotlin-stdlib-2.2.10.jar"
if [ -n "${HUI_MIHOMO_AAR:-}" ]; then cp "$HUI_MIHOMO_AAR" "$MIHOMO"; fi
if [ ! -s "$MIHOMO" ]; then
 curl -fLsS --connect-timeout 15 --max-time 120 --retry 2 'https://github.com/oviron/libmihomo-android/releases/download/v0.3.5/libmihomo-android-v0.3.5.aar' -o "$MIHOMO"
fi
echo '41ddcce67de37f406377798c5adbc7ff925e6cd9cadbef3f187da3a03e564176  '"$MIHOMO" | sha256sum -c -
if [ -n "${HUI_KOTLIN_STDLIB:-}" ]; then cp "$HUI_KOTLIN_STDLIB" "$KOTLIN"; fi
if [ ! -s "$KOTLIN" ]; then
 curl -fLsS --connect-timeout 15 --max-time 60 --retry 2 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.10/kotlin-stdlib-2.2.10.jar' -o "$KOTLIN"
fi
echo '9c67cc79efd6b9215b49d2a4308f5f3433537376c7c88e89bdd6729bd096e61a  '"$KOTLIN" | sha256sum -c -
unzip -p "$MIHOMO" classes.jar > "$OUT/vendor/mihomo-classes.jar"
for name in libclash.so libmihomo-jni.so; do
 unzip -p "$MIHOMO" "jni/arm64-v8a/$name" > "$OUT/apk/lib/arm64-v8a/$name"
done
"$TOOLS/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$TOOLS/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest "$APP/AndroidManifest.xml" --java "$OUT/gen" "$OUT/res.zip" --min-sdk-version 26 --target-sdk-version 35
javac -source 8 -target 8 -encoding UTF-8 -cp "$JAR:$OUT/vendor/mihomo-classes.jar:$KOTLIN" -d "$OUT/classes" $(find "$OUT/gen" "$APP/src" -name '*.java')
if ! timeout 150s "$TOOLS/d8" --min-api 26 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') "$OUT/vendor/mihomo-classes.jar" "$KOTLIN" >"$OUT/d8.log" 2>&1; then
 tail -50 "$OUT/d8.log"; exit 1
fi
echo "D8 complete (diagnostics: $OUT/d8.log)"
python3 - "$OUT/unsigned.apk" "$OUT/dex" "$APP/assets" "$OUT/apk" <<'PY'
import os,sys
from zipfile import ZipFile,ZIP_DEFLATED
apk,dex,assets,native=sys.argv[1:]
with ZipFile(apk,'a',compression=ZIP_DEFLATED) as z:
 for name in os.listdir(dex):
  if name.endswith('.dex'):z.write(os.path.join(dex,name),name)
 for base,prefix in ((assets,'assets'),(native,'')):
  for root,dirs,files in os.walk(base):
   for name in files:
    path=os.path.join(root,name)
    z.write(path,os.path.join(prefix,os.path.relpath(path,base)).lstrip('/'))
PY
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/Hui-aligned.apk"
if [ -n "${HUI_KEYSTORE:-}" ]; then
 : "${HUI_STOREPASS:?Export HUI_STOREPASS for signing}"
 : "${HUI_KEYPASS:?Export HUI_KEYPASS for signing}"
 "$TOOLS/apksigner" sign --ks "$HUI_KEYSTORE" --ks-key-alias "${HUI_KEY_ALIAS:-hui}" --ks-pass "env:HUI_STOREPASS" --key-pass "env:HUI_KEYPASS" --out "$OUT/Hui.apk" "$OUT/Hui-aligned.apk"
 "$TOOLS/apksigner" verify --verbose "$OUT/Hui.apk"
fi
