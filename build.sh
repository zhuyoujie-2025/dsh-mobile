#!/usr/bin/env bash
# build.sh — 手搓构建 DSHMobile.apk (aapt2 + javac + d8 + zipalign + apksigner)
# 跨平台: Linux/WSL 直接跑;Windows 走 WSL (Git Bash 记得 MSYS_NO_PATHCONV=1)。
# 前置: tools/fetch-android-tools.sh 下载构建件; tools/make-icon.py 生成图标。
# 环境变量(均有默认值):
#   ANDROID_SDK_MINI  SDK-mini 目录,默认 $HOME/android-sdk-mini
#   JAVA_HOME         JDK 8+,默认依次试 /opt/jdk25、PATH 现有 java/javac
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_MINI:-$HOME/android-sdk-mini}"
[ -d /opt/jdk25/bin ] && export PATH="/opt/jdk25/bin:$PATH"
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

BT=$(echo "$SDK"/build-tools/android-*/)
AAPT2="${BT}aapt2"
ZIPALIGN="${BT}zipalign"
# build-tools r34 自带的 D8 8.2.2 解析 javac25 产物会 NPE,优先用 maven 的新版 r8.jar
if [ -f "$SDK/r8.jar" ]; then D8JAR="$SDK/r8.jar"; else D8JAR="${BT}lib/d8.jar"; fi
SIGNER="${BT}lib/apksigner.jar"
AJAR="$SDK/android.jar"

for t in "$AAPT2" "$ZIPALIGN" "$SIGNER" "$AJAR"; do
  [ -e "$t" ] || { echo "missing $t — run tools/fetch-android-tools.sh first"; exit 1; }
done
command -v javac >/dev/null || { echo "javac not found — set JAVA_HOME"; exit 1; }

B="$PROJ/build"
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex" "$PROJ/dist"

echo "== [1/6] aapt2 compile resources"
"$AAPT2" compile --dir "$PROJ/app/res" -o "$B/res.zip"

echo "== [2/6] aapt2 link"
"$AAPT2" link -o "$B/app-base.apk" \
  -I "$AJAR" \
  --manifest "$PROJ/app/AndroidManifest.xml" \
  --java "$B/gen" \
  "$B/res.zip"

echo "== [3/6] javac"
find "$PROJ/app/src" "$B/gen" -name '*.java' > "$B/sources.list"
javac -source 8 -target 8 -encoding UTF-8 -nowarn -g:none \
  -classpath "$AJAR" -d "$B/classes" @"$B/sources.list"

echo "== [4/6] d8"
java -cp "$D8JAR" com.android.tools.r8.D8 \
  --classpath "$AJAR" --min-api 24 --output "$B/dex" \
  $(find "$B/classes" -name '*.class')

echo "== [5/6] merge classes.dex + zipalign"
cp "$B/app-base.apk" "$B/app.apk"
python3 - "$B/app.apk" "$B/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with open(dex, "rb") as f:
    data = f.read()
with zipfile.ZipFile(apk, "a") as z:
    z.writestr(zipfile.ZipInfo("classes.dex"), data, compress_type=zipfile.ZIP_STORED)
PYEOF
"$ZIPALIGN" -f 4 "$B/app.apk" "$B/app-aligned.apk"

echo "== [6/6] sign + verify"
KS="${DSHMOBILE_KEYSTORE:-$PROJ/dshmobile.keystore}"
KSPASS="${DSHMOBILE_KS_PASS:-dshmobile}"
if [ ! -f "$KS" ]; then
  # 无签名密钥时自动生成本地调试密钥(仅自用;发布版用私有 release key 签)
  keytool -genkeypair -keystore "$KS" -alias dshmobile -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KSPASS" -keypass "$KSPASS" \
    -dname "CN=DSH Mobile, OU=Local, O=Local, L=Local, S=Local, C=CN" 2>/dev/null
fi
java -jar "$SIGNER" sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --out "$PROJ/dist/DSHMobile.apk" "$B/app-aligned.apk"
java -jar "$SIGNER" verify --print-certs "$PROJ/dist/DSHMobile.apk"

# 更新订阅源元数据(tools/apk-feed-server.cjs 与 GitHub Release 资产共用此格式)
VCODE=$(grep -oE 'versionCode="[0-9]+"' "$PROJ/app/AndroidManifest.xml" | grep -oE '[0-9]+')
VNAME=$(grep -oE 'versionName="[^"]+"' "$PROJ/app/AndroidManifest.xml" | cut -d'"' -f2)
SHA=$(sha256sum "$PROJ/dist/DSHMobile.apk" | cut -d' ' -f1)
SIZE=$(stat -c%s "$PROJ/dist/DSHMobile.apk")
printf '{"versionCode":%s,"versionName":"%s","size":%s,"sha256":"%s","url":"DSHMobile.apk"}\n' \
  "$VCODE" "$VNAME" "$SIZE" "$SHA" > "$PROJ/dist/version.json"

echo "== badging =="
"$AAPT2" dump badging "$PROJ/dist/DSHMobile.apk" | head -8
ls -la "$PROJ/dist/"
