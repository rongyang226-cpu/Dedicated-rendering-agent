#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="$ROOT/app/src/main/java"
fail=0
bad() { echo "[FAIL] $*"; fail=1; }
ok() { echo "[ OK ] $*"; }

# SFA JNI must stay behind the Box boundary.
if grep -RIl 'io\.nekohasekai\.libbox' "$JAVA" --include='*.kt' --include='*.java' | grep -v '/bg/box/' | grep -v '/go/Seq.java' >/tmp/hui-box-boundary.$$; then
  cat /tmp/hui-box-boundary.$$; bad 'libbox reference escaped bg/box'
else ok 'libbox references isolated'; fi
rm -f /tmp/hui-box-boundary.$$

# Mihomo JNI must stay behind Meta boundary / copied bridge sources.
if grep -RIl 'MetaNative\|Clash\.startTun\|nativeQueryTraffic' "$JAVA/io/nekohasekai/sagernet" --include='*.kt' --include='*.java' | grep -v '/bg/meta/' >/tmp/hui-meta-boundary.$$; then
  cat /tmp/hui-meta-boundary.$$; bad 'Meta JNI reference escaped bg/meta'
else ok 'Meta JNI references isolated'; fi
rm -f /tmp/hui-meta-boundary.$$

if grep -RIl 'libcore\.Libcore' "$JAVA/io/nekohasekai/sagernet/bg/box" --include='*.kt' --include='*.java' >/tmp/hui-cross-runtime.$$; then
  cat /tmp/hui-cross-runtime.$$; bad 'legacy Libcore referenced from Box process'
else ok 'no legacy Libcore in Box runtime'; fi
rm -f /tmp/hui-cross-runtime.$$

grep -q 'android:process=":box"' "$ROOT/app/src/main/AndroidManifest.xml" || bad ':box service missing'
grep -q 'android:process=":meta"' "$ROOT/app/src/main/AndroidManifest.xml" || bad ':meta service missing'
[ -f "$ROOT/app/libs/libbox-sfa-1.14.1.aar" ] || bad 'SFA AAR missing'
[ -f "$ROOT/app/src/main/jniLibs/arm64-v8a/libclash.so" ] || bad 'Mihomo native core missing'
[ -f "$ROOT/app/src/main/jniLibs/arm64-v8a/libbridge.so" ] || bad 'CMFA bridge missing'

if [ "$fail" -ne 0 ]; then exit 1; fi
ok 'dual-core boundaries verified'
