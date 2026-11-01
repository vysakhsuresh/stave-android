# Stave

**An audio studio that cannot upload your sound.**

Record, edit, clean up, mix, separate, tune and master on your phone. Every tool is free.
Nothing is sent anywhere, and the reason to believe that is this repository rather than a
privacy policy.

A stave is the five lines music is written on, which is the thing the app puts sound onto.

## Why this exists

The audio apps people install come in three kinds. One uploads the song to a server to pull
the vocal out, then counts how many songs you may do a month. One sells a free editor with the
MP3 button behind the paywall. One is a social network with a recorder attached, and the
recorder gets smaller every release. The free tier exists to sell the paid one.

Stave does the work on the device, in code that is visible here. There is no server, so there
is nothing to pay for, so there is nothing to gate.

## Nothing leaves the phone

Stave does not declare the `INTERNET` permission, so the process cannot open a socket. Your
recordings cannot leave the device even if a dependency tried to send them, and that is
checkable on the Play listing without taking anyone's word for it.

`app/src/main/AndroidManifest.xml` strips network access explicitly, and
`tools/verify_manifest.py` fails CI if the merged debug or release manifest ever gains it back:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

Consequences that shape the code:

- **No crash reporting.** The crash log is written to private storage and offered from
  Settings; you send it, or do not.
- **No storage permission** and never `MANAGE_EXTERNAL_STORAGE`. Files come in through the
  document picker and Share, and go out through documents you create.
- **The models live in the app.** Voice isolation, stem separation, the tuner and audio-to-MIDI
  are neural models that run on the phone. The large ones arrive through Play's own asset
  delivery (the `play` flavour) or inside the APK (the `full` flavour on GitHub Releases); the
  app itself never downloads anything.

## What it does

| Pillar | Built (executed on the JVM, awaiting a phone) | Not yet |
|---|---|---|
| Edit | Every format FFmpeg reads; 32-bit float; non-destructive edits with unlimited undo; the waveform at every zoom and a **spectrogram you can edit** (a box over a cough: turn down, remove, fill from around); de-click, de-clip, de-hum, de-ess, spectral de-noise; parametric and 10- or 31-band graphic EQ with the curve drawn from the filters themselves; compressor, limiter, gate; echo, chorus, flanger, phaser, tremolo, reverb, saturation and a crusher with their knobs; stereo swap and balance; time and pitch independently; fades, gain envelopes, normalise to peak or LUFS; markers, chapters and regions; the selection's spectrum behind the EQ curve; export to every encoder, MP3 included, with publish presets, dither, tags and artwork | neural voice isolation beyond the stem model's vocals |
| Voice | Monitoring with a measured round-trip; takes that survive a crash, on their own lanes, paused and resumed, with a bar of count-in; punch in and out over the selection; a transcript from whisper.cpp in English, Hindi, Malayalam and every Whisper language; **edit the sound by editing the words**; fillers and silences out; clean up voice, keep only the voice (through the stem model), even out volume, loudness to a target; chapters and show notes; read a typed line aloud with the phone's own voice; a looper that lays each pass as a layer; typed lyrics laid against the words heard; a home-screen Record widget | search in the transcript |
| Studio | Tracks limited by the phone, not a plan; a mixer with inserts, sends and buses; volume and pan automation drawn on the strip or written by fader moves; takes in lanes, stacked on the strip, comped by swipe; a tempo map; a sample-accurate native mix; bounce, stems, freeze, duck under speech; the input device chosen in Settings | - |
| Musician | Stems pulled apart on the phone (a LiteRT model, fetched by a script, not yet bundled); tempo, beats and bars, key and chords with the time each starts; slow a passage and change its key; a tuner and a pitch trace; sing along with the written lyrics or the heard words following the play-head and the vocal muted; the chord under the play-head as a guitar shape; your melody as notes | - |
| Beats | A step sequencer that is also the piano roll, with a scale lock, chord stamps and grooves; pads, keys and a USB or Bluetooth MIDI keyboard that record into the loop; a synthesised drum kit, your own kits from a sliced loop or files on pads, subtractive, FM and wavetable synths, any SoundFont you add; swing, quantise, humanise, legato, arpeggiator; a song of patterns; MIDI files in and out | MIDI out and clock |
| Finish | Measure as a broadcast meter does; master to a platform target in one step; a report; match a reference track's tone; width; three-band compression; transients; A/B at matched loudness; dither on export | - |
| Vocal | Pitch correction to a scale over a selection; live autotune on the monitored voice, recorded dry or tuned; twelve vocal effect presets | - |

Everything in the first column is built and its arithmetic is executed by the suites in
`tools/jvm`; **none of it has been seen on a phone yet**. `docs/PENDING_TESTS.md` is the list of
what a phone has to answer, in order, and `docs/DEVICE_FINDINGS.md` is what has been seen.

`docs/FEATURES.md` lists what the current build does; `docs/ROADMAP.md` what is next;
`docs/PENDING_TESTS.md` what is built but not yet seen on a phone.

## The engine is pure, and tested without a phone

`engine/` imports nothing from `android.*`. Every filter, meter, repair and model rule is
executed against real numbers by `tools/jvm/run.sh` in seconds, on any machine with a JDK: a
0 dBFS 1 kHz sine reads -3.01 LUFS, a click injected into a tone is mended to -40 dB, a 2x
stretch of 440 Hz is 440 Hz and twice as long. CI runs it before Gradle.

## Building

```bash
./gradlew assembleFullDebug        # the APK with every model inside
./gradlew :engine:test             # the engine suites through JUnit
sh tools/jvm/run.sh                # the same suites with the standalone compiler, no SDK needed
```

The native libraries under `app/src/main/jniLibs` (FFmpeg, whisper.cpp) are built by the
`native` and `whisper` GitHub workflows and committed; `tools/native/fetch.sh` and
`fetch_whisper.sh` pull them from the newest run. The CMake build under `app/src/main/cpp`
(the Oboe render graph, Rubber Band, TinySoundFont) runs inside Gradle.

## Licence

GPL-3.0. FFmpeg's GPL build and Rubber Band make it so, and it is also the licence that keeps a
fork from closing the source and charging for it. `THIRD_PARTY_NOTICES.md` lists every piece
that is not ours.
