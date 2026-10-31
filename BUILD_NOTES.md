# Build notes

The things most likely to need a touch, and what they lean on.

## Oboe

- Output: `PerformanceMode::LowLatency`, `SharingMode::Exclusive`, falling back to `Shared`
  when exclusive is refused (it is, on many phones with another app holding the device).
  The callback is given `framesPerBurst` and never blocks; `setBufferSizeInFrames(2 * burst)` is
  the starting point and the glitch counter (`getXRunCount`) raises it one burst at a time.
- Input: `InputPreset::Unprocessed` where the device offers it, so the phone's own AGC and
  noise suppression do not fight the app's. `VoiceRecognition` where it does not.
- Sample rate: the device's native rate (`getDefaultSampleRate`) for the stream; the project
  rate is converted at the clip reader, never at the stream, so a 44.1 kHz project plays clean
  on a 48 kHz phone with one resampler per source.
- Loopback latency: a click out, the mic in, the lag by cross-correlation (`Latency.kt`);
  stored per device route.

## libstaveav (FFmpeg)

- Audio-only configure line in `tools/native/build.sh`; GPL because `--enable-gpl` is needed
  for nothing audio, but the family's FFmpeg is GPL and keeping one notice is simpler. The
  encoders: LAME for MP3, libopus, libvorbis, FFmpeg's own FLAC, ALAC, AAC, PCM.
- Handles are tagged pointers (arm64 puts the tag in the top byte); `AvHandles.failed` reads
  the error range, never the sign.
- A decoder is drained with a NULL packet at the end; without it the last frames stay inside.

## whisper.cpp

- Text comes back as bytes; `WhisperText.parseBytes` joins a character cut across two pieces.
  Windows of 28 s; a call shorter than 30 s costs a 30 s window.
- Models: `ggml-base-q5_1` (57 MB) in the asset pack and the full APK; `ggml-small-q5_1`
  (181 MB) by import. The multilingual model, always: Hindi and Malayalam are first-class.

## Rubber Band

- Built from `single/RubberBandSingle.cpp` (one translation unit, built-in FFT and resampler,
  no dependencies) fetched by CMake at configure time; the R3 engine with
  `OptionEngineFiner | OptionProcessRealTime` for the preview and `OptionProcessOffline` for
  a render. GPL.

## TinySoundFont

- `third_party/tinysoundfont/tsf.h` (MIT), header-only; `TSF_IMPLEMENTATION` in exactly one
  file (`graph/SoundFont.cpp`). SF2 and SF3 (Vorbis-compressed) both load.

## LiteRT

- 1.4.0, never TensorFlow Lite 2.16.1 (Squish: the old runtime ran converted models wrong
  without refusing them). GPU delegate for the vocoder-class models, XNNPACK for the rest.
- Models are `noCompress` assets so they memory-map.

## Gradle

- `:engine` is `org.jetbrains.kotlin.jvm` with the suites as its test source set; `:app` is
  Android. The version catalog is `gradle/libs.versions.toml` and `tools/check_dependencies.py`
  holds the import-to-alias table against it.
- Flavours `play` (asset pack) and `full` (models inside); CI builds `full`.
