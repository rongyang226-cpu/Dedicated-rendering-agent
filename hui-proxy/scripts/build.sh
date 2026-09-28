#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/android"
OUT="$ROOT/out"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
TOOLS="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
"$TOOLS/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$TOOLS/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest "$APP/AndroidManifest.xml" --java "$OUT/gen" "$OUT/res.zip" --min-sdk-version 26 --target-sdk-version 35
javac -source 8 -target 8 -encoding UTF-8 -cp "$JAR" -d "$OUT/classes" $(find "$OUT/gen" "$APP/src" -name '*.java')
"$TOOLS/d8" --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
python3 - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" "$APP/assets" <<'PY'
import os,sys
from zipfile import ZipFile,ZIP_DEFLATED
apk,dex,assets=sys.argv[1:]
with ZipFile(apk,'a',compression=ZIP_DEFLATED) as z:
 z.write(dex,'classes.dex')
 for root,dirs,files in os.walk(assets):
  for name in files:
   path=os.path.join(root,name)
   z.write(path,'assets/'+os.path.relpath(path,assets))
PY
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/Hui-aligned.apk"
if [ -n "${HUI_KEYSTORE:-}" ]; then
 "$TOOLS/apksigner" sign --ks "$HUI_KEYSTORE" --ks-key-alias "${HUI_KEY_ALIAS:-hui}" --ks-pass "env:HUI_STOREPASS" --key-pass "env:HUI_KEYPASS" --out "$OUT/Hui.apk" "$OUT/Hui-aligned.apk"
 "$TOOLS/apksigner" verify --verbose "$OUT/Hui.apk"
fi
