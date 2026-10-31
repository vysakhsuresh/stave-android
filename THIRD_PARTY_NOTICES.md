# Third-party notices

Everything in Stave that is not ours, with its licence. The app is GPL-3.0 (LICENSE); every
piece below is compatible with that. **Before a store release:** the OFL fonts' full licence
text and the GPL sources' corresponding-source offer travel with the build (the About screen
links here and to the repository).

## Space Grotesk
Florian Karsten. SIL Open Font License 1.1. `app/src/main/res/font/space_grotesk_*.ttf`.

## Fira Code
Nikita Prokopov and contributors. SIL Open Font License 1.1. `app/src/main/res/font/fira_code_variable.ttf`.

## FFmpeg — libstaveav.so
FFmpeg 8.1.3 (https://ffmpeg.org): libavformat, libavcodec, libswresample, libavutil, built
with `--enable-gpl --enable-version3`, so GPL-3.0. Audio demuxers, decoders, encoders and the
resampler only (`tools/native/build.sh`). Built in CI, committed under `app/src/main/jniLibs`.

## LAME
The LAME Project. LGPL-2.1. MP3 encoding inside libstaveav.so.

## Opus, Ogg, Vorbis
Xiph.Org Foundation. BSD 3-Clause. Inside libstaveav.so.

## Oboe
Google. Apache-2.0. `com.google.oboe:oboe` (prefab), linked by the CMake build.

## Rubber Band Library
Particular Programs Ltd / Chris Cannam. GPL-2.0-or-later (used under GPL-3.0). Fetched by
CMake at build time (`app/src/main/cpp/CMakeLists.txt`), `single/RubberBandSingle.cpp`.

## TinySoundFont
Bernhard Schelling, based on SFZero by Steve Folta. MIT. `third_party/tinysoundfont/`.

## whisper.cpp — libstavewhisper.so
Georgi Gerganov and contributors. MIT. Built in CI (`tools/native/build_whisper.sh`). Whisper
models: OpenAI, MIT (ggml conversions by the whisper.cpp project).

## LiteRT (TensorFlow Lite)
Google. Apache-2.0. `com.google.ai.edge.litert:litert` and `litert-gpu`.

## DTLN — voice isolation models
Nils L. Westhausen. MIT. Two-stage LSTM, converted to LiteRT; carried from Squish.

## Open-Unmix (UMX) — stem separation
Fabian-Robert Stöter and Antoine Liutkus. MIT (code and weights). Converted to int8 LiteRT.

## HT-Demucs — six-stem separation
Alexandre Défossez / Meta Research. MIT. ONNX export, delivered as an on-demand asset pack and
inside the `full` APK.

## CREPE — pitch
Jong Wook Kim et al. MIT. LiteRT conversion by the litert-community.

## Basic Pitch — audio to MIDI
Spotify. Apache-2.0. ONNX export.

## AndroidX, Kotlin, Compose
Google, JetBrains. Apache-2.0.
