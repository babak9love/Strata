# Bundled FFmpeg (NextLib)

Two AARs built from the same NextLib commit. Both ship identical FFmpeg libraries (`libavcodec`, `libavutil`,
`libswscale`), so the app keeps only one copy of each (`pickFirsts` in `app/build.gradle.kts`). CI checks that they match.

| AAR | What it does | SHA-256 |
|---|---|---|
| `media3ext-release.aar` | FFmpeg decoders for Media3 / ExoPlayer and the hardware / software / FFmpeg decoder switch (`DecoderManager`) | `1567bb55c1e78a9884ac0d287665ab15daf9f583f375897922bb103b91a7c137` |
| `mediainfo-release.aar` | File details through FFmpeg (`MediaInfoBuilder`): every audio track, subtitles, chapters, language and flags, plus a preview frame and cover art | `e1f51f0631a5d202d6cc5be6b21111ceab6ad98507af4431f8eca0061c6fc0e5` |

- Source: https://github.com/anilbeesetti/nextlib (GPL-3.0), modules `media3ext` and `mediainfo`, built from `main`
- FFmpeg 9.0.1 (LGPL v3 build, `--enable-version3`, no `--enable-gpl`), dav1d 1.5.4, mbedTLS 3.6.7
- Media3 1.11.1, minSdk 23, ABIs: arm64-v8a, armeabi-v7a, x86, x86_64 (x86 is dropped when packaging)
- Decoders: Vorbis, Opus, FLAC, ALAC, PCM (mu/A-law, u8, s16/s24/s32 LE/BE, f32, f64), MP3, AMR-NB/WB, AAC, AC3, EAC3, DTS, MLP, TrueHD,
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

## Descriptor leak fix in mediainfo

NextLib's `mediainfo.cpp` never closed the file when no frame loader was created (audio files, and videos whose codec
has no decoder in libavcodec), so every parse leaked a file descriptor. `mediainfo-release.aar` here is built from NextLib
with `patches/nextlib-mediainfo-close-context.patch` applied (`libmediainfo.so` no longer imports `avformat_free_context`).
Keep the patch when rebuilding from upstream NextLib: `patch -p1 < nextlib-mediainfo-close-context.patch`, then replace
**both** AARs from the same build (the shared FFmpeg libraries must be identical; CI checks this) and update the SHA-256 values above.

## Raw PCM / WAV changes (this build)

Built from a NextLib source with these changes (see `PCM_SUPPORT_CHANGES.md` in that source):

- Raw PCM (WAV: 16/24/32-bit integer, 32-bit float) is passed to the audio sink byte-for-byte by the FFmpeg renderer.
  In Auto mode `audio/raw` skips MediaCodec's raw decoder and goes to FFmpeg. 24/32-bit output needs Android 12+.
- FLAC, ALAC and TrueHD keep their bit depth (24-bit is delivered as 32-bit integer).
- 64-bit float WAV opens through `FloatWavExtractorsFactory` (used by `PlaybackService`) and plays as 32-bit float.
- Both AARs must come from this same build (CI checks the shared FFmpeg libraries). Update the SHA-256 values above after rebuilding.
