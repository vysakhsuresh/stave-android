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

## Stems (Open-Unmix on LiteRT)

- Four models, one per stem, `umx_<stem>.tflite`, made by `tools/models/umx_to_tflite.py` and
  shipped in the full flavour's assets and the Play asset pack. The converter (litert-torch,
  formerly ai-edge-torch) does not install on Windows: `gh workflow run models.yml` makes them on
  an Ubuntu 22.04 runner and `gh run download <run> --name stem-models -D app/src/full/assets/models`
  brings them back. Each is checked against PyTorch through LiteRT before it is uploaded. The
  pins in `requirements-umx.txt` are a set that installs together (torch with its torchaudio);
  six runs went into finding one. The contract both
  sides keep: input `[1, T, 2, 2049]` magnitudes (STFT 4096/1024 at 44.1 kHz), output the same
  shape as the stem's magnitude estimate. `engine/dsp/StemSeparator` frames as torch.stft does
  (centred, periodic Hann, unnormalised), calls the models 256 frames at a time with 21 frames of
  context kept each side, Wiener-filters the mixture's complex spectrogram by each stem's share
  and overlap-adds straight to one file per stem; `StemSeparatorChecks` holds the sum to the mix
  and, with an oracle model, the alignment of each mask with its own frame.
- LiteRT's Interpreter is reached by name (`Class.forName`) so the engine door compiles with or
  without the dependency on a build; the dependency is declared in the catalog.

## LiteRT

- 1.4.0, never TensorFlow Lite 2.16.1 (Squish: the old runtime ran converted models wrong
  without refusing them). GPU delegate for the vocoder-class models, XNNPACK for the rest.
- Models are `noCompress` assets so they memory-map.

## Gradle

- `:engine` is `org.jetbrains.kotlin.jvm` with the suites as its test source set; `:app` is
  Android. The version catalog is `gradle/libs.versions.toml` and `tools/check_dependencies.py`
  holds the import-to-alias table against it.
- Flavours `play` (asset pack) and `full` (models inside); CI builds `full`.

## The pattern player (AudioTrack, not the graph)

The Beats tab's loop and pads play through `beats/PatternPlayer.kt`, a thread writing 256-frame
blocks to an `AudioTrack` (float, stereo, `PERFORMANCE_MODE_LOW_LATENCY`), not through the Oboe
graph. The graph plays sources from files; a pattern under the finger changes every tap and is
heard on the next pass with nothing written to disk. The cost is a second output: starting the
loop pauses the transport (`startLoop`), because two clocks drift. "Render to a track" goes
through `engine/sequencer/Render.kt`'s `PatternRenderer`, which the player shares its
arithmetic with (`events` and the within-block scheduling), so the clip is what was heard.

Things the first phone build settles: whether `AudioTrack.write(float[], …, WRITE_BLOCKING)`
keeps up at 256 frames on the moto (if it underruns, double `BLOCK`); whether the thread's
`MAX_PRIORITY` is enough or `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)` is
needed; and the pad's press-to-sound latency, which is a block plus the track's own buffer.

## SoundFonts (TinySoundFont through `SoundFontNative`)

Nothing is bundled: a usable General MIDI bank is 30 MB and someone else's licence. The person
adds an `.sf2`/`.sf3` from a file; it is copied under `files/soundfonts/` and named by preset
through `SoundFontStore.presetNames`, which opens the bank once at 48 kHz to read the names.
`SoundFontInstrument` has no "still sounding" from TSF (we export no `tsf_active_voice_count`),
so a released note is counted as ringing for 3 s, which is what caps a render's tail.

## Read aloud (the phone's text-to-speech)

`voice/ReadAloud.kt` drives `android.speech.tts.TextToSpeech.synthesizeToFile`. Three things
the first phone run settles: that the manifest's `<queries>` for `TTS_SERVICE` is enough for
the engine to bind on Android 11+ (Squish needed exactly this); that the engine's WAV header is
walked correctly (Google's engine writes 24 000 Hz 16-bit mono with a canonical header, Samsung's
has been seen with a LIST chunk - the reader walks chunks rather than skipping 44 bytes); and
the voice's pitch after the resample to the session's rate (a rate read wrong is a semitone
off, which is what item 47 of `docs/PENDING_TESTS.md` listens for).

