#!/usr/bin/env bash
# fetch-android-tools.sh — 下载最小 Android 构建件到 SDK-mini 目录
# 内容: android.jar (API 35) + build-tools r34 (aapt2/d8/apksigner/zipalign) + r8.jar
# 用法: bash tools/fetch-android-tools.sh   (SDK 位置: ANDROID_SDK_MINI 或 $HOME/android-sdk-mini)
set -euo pipefail

T="${ANDROID_SDK_MINI:-$HOME/android-sdk-mini}"
mkdir -p "$T"
cd "$T"

# WSL 无 unzip/zip —— 用 python3 zipfile 解包
pyunzip() { python3 - "$1" "$2" <<'PYEOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    z.extractall(sys.argv[2])
PYEOF
}

if [ ! -f android.jar ]; then
  echo ">>> downloading platform-35_r02.zip"
  curl -fSL --retry 3 -o platform.zip https://dl.google.com/android/repository/platform-35_r02.zip
  pyunzip platform.zip "$T"
  cp android-*/android.jar "$T/android.jar"
  rm -rf android-*/ platform.zip
fi

if [ ! -d build-tools ]; then
  echo ">>> downloading build-tools_r34-linux.zip"
  curl -fSL --retry 3 -o build-tools.zip https://dl.google.com/android/repository/build-tools_r34-linux.zip
  mkdir -p build-tools
  pyunzip build-tools.zip "$T/build-tools"
  rm -f build-tools.zip
fi

# build-tools r34 自带 D8 8.2.2 解析 javac25 的 -target8 产物会 NPE,需新版 r8
if [ ! -f r8.jar ]; then
  echo ">>> downloading r8 9.4.18 (maven.google.com)"
  curl -fSL --retry 3 -o r8.jar https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.4.18/r8-9.4.18.jar
fi

BT_DIR=$(echo "$T"/build-tools/android-*/)
chmod +x "$BT_DIR"aapt2 "$BT_DIR"zipalign "$BT_DIR"d8 "$BT_DIR"apksigner 2>/dev/null || true

echo "== sdk-mini =="
ls -la "$T"
echo "== build-tools =="
ls "$BT_DIR" | head -40
echo "== aapt2 version =="
"$BT_DIR"aapt2 version
