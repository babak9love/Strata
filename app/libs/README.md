# Bundled FFmpeg (NextLib)

Two AARs built from the same NextLib commit. Both ship identical FFmpeg libraries (`libavcodec`, `libavutil`,
`libswscale`), so the app keeps only one copy of each (`pickFirsts` in `app/build.gradle.kts`). CI checks that they match.

| AAR | What it does | SHA-256 |
|---|---|---|
| `media3ext-release.aar` | FFmpeg decoders for Media3 / ExoPlayer and the hardware / software / FFmpeg decoder switch (`DecoderManager`) | `042d9e74edb483a7ee9321d24919183a2025d2fdf9ac73f677ed177211430ad4` |
| `mediainfo-release.aar` | File details through FFmpeg (`MediaInfoBuilder`): every audio track, subtitles, chapters, language and flags, plus a preview frame and cover art | `a94179f0fc9fb40a78e6c66f5c033ebae4271bf624720b1a0f2f0437eb72ade8` |

- Source: https://github.com/anilbeesetti/nextlib (GPL-3.0), modules `media3ext` and `mediainfo`, built from `main`
- FFmpeg 9.0.1 (LGPL v3 build, `--enable-version3`, no `--enable-gpl`), dav1d 1.5.4, mbedTLS 3.6.7
- Media3 1.11.1, minSdk 23, ABIs: arm64-v8a, armeabi-v7a, x86, x86_64 (x86 is dropped when packaging)
- Decoders: Vorbis, Opus, FLAC, ALAC, PCM mu/A-law, MP3, AMR-NB/WB, AAC, AC3, EAC3, DTS, MLP, TrueHD,
  H.264, HEVC, MPEG-2, VP8, VP9, AV1 (dav1d). Frame previews through FFmpeg work for these video codecs only;
  other codecs use the phone's own decoder.

## Rebuilding

```bash
git clone https://github.com/anilbeesetti/nextlib && cd nextlib
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleRelease   # needs the Android CLI, meson, ninja and nasm; see its .github/workflows/build.yaml
cp media3ext/build/outputs/aar/media3ext-release.aar mediainfo/build/outputs/aar/mediainfo-release.aar <Strata>/app/libs/
```

The CI step "Verify the bundled FFmpeg" fails if an AAR is missing, if the shared libraries differ between the two
AARs, or if FFmpeg is not 9.0.1.

## Known leak in NextLib's mediainfo (fix included)

`mediainfo.cpp` never closes the file when no frame loader is created (audio files, and videos whose codec has no
decoder in libavcodec), so each parse leaks a file descriptor. Strata keeps recent results in a small cache, which
limits this to one leak per file you inspect. `patches/nextlib-mediainfo-close-context.patch` fixes it
(`patch -p1 < nextlib-mediainfo-close-context.patch` in the NextLib checkout). After rebuilding NextLib with it,
replace **both** AARs from the same build (the shared FFmpeg libraries must be identical; CI checks this) and
update the SHA-256 values above.
