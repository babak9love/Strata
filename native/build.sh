#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  if [[ -d "$SDK/ndk" ]]; then
    ANDROID_NDK_HOME="$SDK/ndk/$(ls "$SDK/ndk" | sort -V | tail -1)"
    export ANDROID_NDK_HOME
  fi
fi
echo "Using NDK: ${ANDROID_NDK_HOME:-<none>}"

for t in aarch64_linux_android armv7_linux_androideabi x86_64_linux_android; do
  export "CXXFLAGS_${t}=-D_POSIX_C_SOURCE=200809L"
done

cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o ../app/src/main/jniLibs \
  build --release

echo "Done. Native libraries:"
find ../app/src/main/jniLibs -name 'libstrata_native.so' -print
