#!/usr/bin/env bash
set -euo pipefail

RELEASE="chromium/8086"
BASE="https://github.com/bblanchon/pdfium-binaries/releases/download/${RELEASE/\//%2F}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() {
  local name="$1" sum="$2"
  curl -fsSL --retry 4 -o "$WORK/$name" "$BASE/$name"
  echo "$sum  $WORK/$name" | sha256sum -c -
  mkdir -p "$WORK/${name%.tgz}"
  tar -xzf "$WORK/$name" -C "$WORK/${name%.tgz}"
}

fetch pdfium-android-arm64.tgz f6ea29495d64795b9af61e41768030795b45f82a21415498ee4ab2c61e135756
fetch pdfium-android-arm.tgz fdf3a2b329759f5ba8a9997329ae79dad3570b1d90ecde60b5a56f1a9ff6a98c
fetch pdfium-android-x64.tgz 772870e7ed846097d779aa0163f8abfd08a13257e16edf0e1cd1a364f11c8c3c
fetch pdfium-linux-x64.tgz 588577cf52dabc1a444988bac841920df54cc2f141801424de97ab04f4fbb935

mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a" "$ROOT/app/src/main/jniLibs/armeabi-v7a" "$ROOT/app/src/main/jniLibs/x86_64"
cp "$WORK/pdfium-android-arm64/lib/libpdfium.so" "$ROOT/app/src/main/jniLibs/arm64-v8a/libpdfium.so"
cp "$WORK/pdfium-android-arm/lib/libpdfium.so" "$ROOT/app/src/main/jniLibs/armeabi-v7a/libpdfium.so"
cp "$WORK/pdfium-android-x64/lib/libpdfium.so" "$ROOT/app/src/main/jniLibs/x86_64/libpdfium.so"
mkdir -p "$ROOT/native/third_party/pdfium/linux-x64" "$ROOT/native/third_party/pdfium/licenses"
cp "$WORK/pdfium-linux-x64/lib/libpdfium.so" "$ROOT/native/third_party/pdfium/linux-x64/libpdfium.so"
cp "$WORK/pdfium-android-arm64/LICENSE" "$WORK/pdfium-android-arm64/VERSION" "$ROOT/native/third_party/pdfium/"
cp "$WORK/pdfium-android-arm64/licenses/"* "$ROOT/native/third_party/pdfium/licenses/"
mkdir -p "$ROOT/app/src/main/assets/licenses/pdfium"
cp "$WORK/pdfium-android-arm64/licenses/"* "$ROOT/app/src/main/assets/licenses/pdfium/"
cp "$WORK/pdfium-android-arm64/LICENSE" "$ROOT/app/src/main/assets/licenses/pdfium/pdfium-binaries.txt"
echo "PDFium $RELEASE in place"
