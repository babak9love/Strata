# Bundled FFmpeg (NextLib)

`media3ext-release.aar` adds FFmpeg software decoders to Media3 / ExoPlayer and lets the video player
switch between hardware, software and FFmpeg decoders (`DecoderManager`).

- Source: https://github.com/anilbeesetti/nextlib (GPL-3.0), `media3ext` module, built from `main`
- FFmpeg 9.0.1 (LGPL v3 build, `--enable-version3`, no `--enable-gpl`), dav1d 1.5.4, mbedTLS 3.6.7
- Media3 1.11.1, minSdk 23, ABIs: arm64-v8a, armeabi-v7a, x86, x86_64 (x86 is dropped when packaging)
- Decoders: Vorbis, Opus, FLAC, ALAC, PCM mu/A-law, MP3, AMR-NB/WB, AAC, AC3, EAC3, DTS, MLP, TrueHD,
  H.264, HEVC, MPEG-2, VP8, VP9, AV1 (dav1d)
- SHA-256: `042d9e74edb483a7ee9321d24919183a2025d2fdf9ac73f677ed177211430ad4`

## Rebuilding

```bash
git clone https://github.com/anilbeesetti/nextlib && cd nextlib
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleRelease   # needs the Android CLI, meson, ninja and nasm; see its .github/workflows/build.yaml
cp media3ext/build/outputs/aar/media3ext-release.aar <Strata>/app/libs/
```

The CI step "Verify the bundled FFmpeg" fails if the AAR is missing or is not FFmpeg 9.0.1.
