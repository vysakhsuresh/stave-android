# Stave — the audio editor that cannot upload your sound

Written 31 October 2026, from the sibling repositories (Squish, Still, Sheaf, LayerLink)
and the five competitors' own listings, help centres and reviews. **Nothing in the
competitor sections was seen by driving those apps**; it was read. Where a claim is
marketing language it is repeated as marketing language.

Six parts:

1. The name
2. What the five do, and where each is weak
3. What Stave builds, pillar by pillar, and why each beats them
4. The design language
5. The task audit, in build order, each with the check that proves it
6. The command for the laptop CLI (replace `REPO_NAME`)

---

## 1. The name: **Stave**

A stave is the five lines music is written on. It is the thing the app puts sound onto,
the way a sheaf is the bundle Sheaf makes and a still is the picture Still makes. It keeps
the family's shape: one short English noun, one syllable, an S — **Squish, Still, Sheaf,
Stave**. No Android audio app of that name was found (an iOS notation sketchbook and a
tab editor called Staventabs are the nearest).

| | |
|---|---|
| App name | Stave |
| Package | `com.layerbit.stave` |
| Suggested repo | `stave-android` |
| Tagline | An audio studio that cannot upload your sound. |
| Icon | Five horizontal lines, the middle one broken by a waveform, in the family's ink with one Signal-lime band. |

Runners-up if Stave is ever blocked: **Strum** (guitar-leaning), **Skein** (a bundle of
thread; obscure), **Lilt** (a lightweight melody; a CLI transcoder has the name).

---

## 2. The five, read off their own pages

### BandLab (free, account, cloud, social) — 110–140M installs
- 16 tracks free, 32 with Membership, **15-minute project cap on both**.
- 60+ effects, 300+ presets, 4 free mastering presets (more for members).
- Splitter: 4 stems free, 7 for members, **in the cloud** (the file is uploaded).
- SongStarter (AI idea generator, cloud), Audio-to-MIDI, Voice Cleaner, Voice Changer.
- Looper, sampler, drum machine, MIDI editor, autotune-style tuner.
- Palette loop library — reported paywalled and ad-gated from May 2026 (20 samples a month free).
- Cloud projects, collaboration, feed, distribution. Per-track export is M4A on Android.

**Weak where we are strong:** account and internet required for the features people
install it for; the Splitter uploads your song; caps on tracks and minutes; ads and a
moving paywall; the social layer is noise for someone who wants to edit.

### Audio Evolution Mobile Studio (~$12, add-ons) — the deepest recorder
- Multitrack audio and MIDI; piano roll with MPE; drum pattern editor.
- Every mixer parameter and effect automatable.
- Real-time effects: chorus, compressor, delays, EQs, reverb, gate, pitch shifter, vocal tune.
- Punch in/out, loop playback, time-stretch, normalise, auto-split.
- USB audio and MIDI interfaces; a **custom USB driver is a €3.99 purchase**; Vocal Tune
  Studio and effect packs are paid add-ons; Android gets a trial.

**Weak where we are strong:** a 2010s desktop UI shrunk onto a phone; paid pieces inside
a paid app; nothing on-device AI (no stems, no transcript, no chords); no spectral tools.

### FL Studio Mobile (~$15, IAP packs) — the beat maker
- Step sequencer, piano roll (pitch, length, velocity), 8 instruments, 30+ effects.
- Thousands of sounds, **most in paid packs**.
- Audio recording with Pitcher 2 live tuning; USB interface recording and monitoring.
- Automation of volume, pan, filters; MIDI controllers.
- Export WAV, MP3, AAC, FLAC, MIDI; `.flp` to desktop FL Studio.

**Weak where we are strong:** paid up front and again for sounds; wave editing is an
afterthought (no spectrogram, no repair); no stems, no transcript; the mixer is cramped.

### Moises (free with caps, Premium ~$4–6/mo, Pro ~$10–30/mo) — 43–56M installs
- Stem separation: free vocals/drums/bass/other with a **monthly cap** (5 reported);
  Premium adds guitars and main/background vocals; Pro adds Hi-Fi models, drum parts,
  180-minute uploads.
- Chord detection with diagrams, key and BPM detection, pitch shift and speed change
  independently, metronome click, setlists; lyrics transcription and mastering reported
  (mastering export is Pro).
- **Every separation is an upload** to their servers.

**Weak where we are strong:** three subscription tiers for one feature; a cap on how
many songs you may practise a month; nothing works offline; it edits nothing — you cannot
cut, record a take over the stems, or mix.

### Lexis Audio Editor (free, paid MP3 save) — 10M+ installs, 4.6★
- One track, destructive: cut, copy, paste, trim, insert silence, fade in/out, normalise.
- Noise reduction, 10-band EQ, compressor, tempo/speed/pitch, reverb removal, voice cleaner.
- Spectrogram **view** (not editing). Mix with one other file. Vocal/accompaniment split. TTS.
- Record and play. Formats: MP3 (paid), WAV 16-bit, FLAC, M4A, AAC, WMA.

**Weak where we are strong:** a single track; 16-bit only; no non-destructive history;
a spectrogram you can look at but not touch; the MP3 button is the paywall; the UI is
from another decade. It is the most-installed "pure editor" on Android because nobody
has replaced it.

---

## 3. What Stave builds

The rule from Squish's competitor doc still holds: **do not chase parity, chase the
thing they cannot do**. Four things none of the five can do: everything on-device;
nothing capped; one app that is a wave editor, a recorder, a multitrack studio, a beat
maker and a musician's practice tool; and the hard arithmetic executed and checked.

Everything below runs on the phone. The APK does not declare INTERNET.

### Pillar A — the wave editor (beats Lexis)
- Open **every** format FFmpeg reads (MP3, AAC, M4A, FLAC, WAV, AIFF, OGG, Opus, WMA, APE,
  WavPack, DSD, AMR, CAF, the audio of any video) through `libstaveav`, Squish's FFmpeg
  build with the video decoders stripped.
- 32-bit float pipeline end to end; any sample rate to 384 kHz; up to 8 channels.
- Non-destructive edits over a region list; **unlimited undo across sessions** (the
  history is in the project file); every edit replayable.
- Waveform + **spectrogram editing**: a brush and lasso that attenuate, replace or
  interpolate a region of the spectrogram (a cough, a phone ring, a chair squeak).
  Nothing on Android has this; desktop iZotope RX charges $400 for it.
- Repair suite: de-click, de-crackle, de-hum (50/60 Hz and harmonics, tracked),
  de-clip (interpolated peaks), de-ess, de-plosive, de-breath, spectral de-noise
  (learned profile) **and** DTLN neural voice isolation (Squish's `DtlnTflite`).
- Dynamics: compressor, multiband compressor, limiter (true-peak, look-ahead), gate,
  expander, transient shaper, leveller. EQ: parametric with unlimited bands and a
  spectrum analyser behind it; graphic 10/31 band; dynamic EQ; tilt; linear-phase option.
- Time and pitch independently (Rubber Band R3, formant-preserving); reverse; speed
  with pitch following or not; **variable-speed ramps** (Squish's `SpeedRamp`).
- Fades with curve shapes, clip gain envelopes, normalise to peak **or to LUFS**,
  silence insertion, trim silence, split on silence, batch processing of a folder.
- Markers, regions, chapters, loops; cue sheets; ID3/Vorbis/MP4 metadata and cover art.
- Export to every encoder FFmpeg has (MP3 LAME, AAC, Opus, Vorbis, FLAC, ALAC, WAV 16/24/32f,
  AIFF) with dither; **MP3 is free**, because that is the paywall Lexis puts on 10 million people.

### Pillar B — the recorder and the podcast desk (the empty seat on Android)
- Record with monitoring through Oboe/AAudio at the lowest latency the phone offers;
  measured round-trip latency with a loopback tap; input gain, auto-gain, limiter on the way in.
- Multi-mic: phone mic, USB interface, Bluetooth; per-take file; a take never lost
  (a `.part` file is finalised on crash, as Squish's voiceover does).
- **Transcript editing** from whisper.cpp (Squish's `libsquishwhisper`, `WhisperWindows`,
  `editor/edits/TextEdits`): tap words to cut audio; "Take out every um and uh"; search the audio by
  word; English, Hindi, Malayalam and every Whisper language, on-device.
- Remove silences, Even out volume, Duck music under speech, Enhance voice — all four
  exist in Squish (`VoiceCleaner`, `Loudness`, `DuckRules`, silence rules) and come across.
- Speaker labelling by voice embedding clustering (stretch; mark as such on the roadmap).
- Loudness to platform targets (−16 LUFS stereo podcast, −14 streaming, −23 EBU R128)
  with true-peak ceiling, and a meter that shows integrated, short-term, momentary and range.
- Chapters with titles and art, written into the MP3/M4A; show-notes template from the
  transcript; publish-ready export presets (Spotify, Apple, RSS-safe).
- Read aloud with the downloaded Indic voices (Squish's `Tts`, `NeuralVoice`), so an
  intro or a correction can be synthesised and dropped in.

### Pillar C — the multitrack studio (beats Audio Evolution)
- Tracks limited by the device, not by a plan; buses, sends, sidechains; a mixer with
  per-channel inserts, pan laws, mute/solo/arm, meters at every stage.
- Non-destructive clips: gain, fades, stretch, pitch, reverse per clip; crossfades on
  overlaps; clip FX.
- **Automation on everything** (volume, pan, every effect parameter), drawn or
  recorded, with curves; Squish's `ValueKey` track model is the start.
- Punch in/out, loop recording into **take lanes with comping**; pre-roll; count-in.
- Sample-accurate native render graph (C++, lock-free, Oboe output), disk-streamed clips,
  automatic latency compensation per insert.
- Freeze, bounce in place, stem export per track or per bus.
- Elastic time: warp markers on a clip; tempo map with changes; snap to grid or to
  detected beats (Squish's `BeatDetector`).
- USB audio class interfaces through AAudio with no paid driver; MIDI over USB and BLE.
- Project files are readable JSON plus referenced media, with ten-minute snapshots and a
  bin (Squish's `ProjectAutosave`, `HousekeepingChecks` pattern).

### Pillar D — the musician's tools (beats Moises)
- **On-device stem separation**, unlimited: a bundled int8 Open-Unmix (4 stems, ~40 MB)
  for every phone, and an HT-Demucs 6-stem pack (vocals, drums, bass, guitar, piano,
  other) delivered as a Play on-demand asset pack / included in the GitHub "full" APK.
  No upload, no cap, no minute limit beyond storage.
- Chords with diagrams (guitar, piano, ukulele), transposition and capo; key detection;
  BPM with a tap-to-correct; a smart click that follows the detected beat.
- Pitch shift and speed change independently, loop a section, slow a passage, mute a
  stem and play along — and **record your take over the stems in the same project**.
- Tuner (CREPE on LiteRT, GPU) and a pitch trace over your take against the song's.
- Lyrics from Whisper aligned to the waveform; karaoke view over the instrumental stem.
- Audio-to-MIDI (Spotify's Basic Pitch, Apache-2.0, ONNX/TFLite) into the piano roll.
- Setlists and practice logs stay on the phone.

### Pillar E — beats and MIDI (beats FL Studio Mobile)
- Step sequencer with per-step velocity, probability, ratchet and swing.
- Piano roll with scale lock, chord stamps, strum, humanise, velocity lane, CC lanes.
- Drum sampler with 16 pads, choke groups, slicing of a loop to pads, one-shot/loop/ADSR.
- Instruments: a SoundFont player (SF2/SF3 via TinySoundFont, MIT), a subtractive synth,
  a 4-op FM synth, a wavetable synth, a sampler — all free, with a starter bank under an
  open licence.
- Arpeggiator, chord track, groove templates; tempo map; song arrangement of patterns.
- MIDI import/export (type 0/1), MIDI controllers in, MIDI clock; export stems and MIDI
  together for a desktop DAW.

### Pillar F — vocal and live (beats Voloco and BandLab's tuner)
- Autotune: live with low latency and offline with a scale, retune speed, humanise,
  and note-by-note correction on a pitch graph.
- Vocal effects chain presets (doubler, harmoniser, formant, vocoder, telephone, radio,
  megaphone — Squish's `VoiceEffects` are the start), de-esser and compressor on the way in.
- A looper (overdub, undo layer, quantised) and a karaoke mode over separated stems.

### Pillar G — mastering and finish (beats BandLab's four presets)
- A mastering chain with reference loudness per platform, EQ match to a reference
  track, multiband compression, stereo width, limiter with true-peak, dither.
- A/B with level matching; a loudness and spectrum report; stems and master exported
  together; album metadata and art.

### Pillar H — trust, shell and finish (the family's edge)
- No account, no watermark, no cap, no subscription, no ads, **no INTERNET permission**;
  `tools/verify_manifest.py` fails CI if it ever appears.
- Projects grid, library of exports, recently deleted, autosave, versions, "Open with"
  and Share-in for any audio or video.
- Every control named for a screen reader, every switch with a state, every slider with
  a value (Squish's sweep ten); RTL-safe timeline; 48 dp targets; haptics on snaps.
- Settings: default sample rate and bit depth, latency mode, loudness target, theme.

---

## 4. The design language

"Ultra premium" here means **quiet**: ink, one colour, measured numbers, nothing moving
that was not asked to move.

- **Type.** Space Grotesk for every word (LayerLink's face, already in Still and Sheaf:
  Regular, Medium, SemiBold, Bold). **Fira Code variable for every measured number** —
  timecode, dB, Hz, BPM, LUFS, sample counts — so a changing value does not jitter.
  Display sizes with negative tracking (52/42/32/26 sp at −2.0/−1.5/−0.8/−0.5), body 15/13.5/12.5.
- **Colour.** The family base: cool blue-black ink (background `#070A12`, surface
  `#121828`, borders `#1F2940`/`#35425F`, text `#F6F7FB`, muted `#A8B3C9`, dim `#6F7B95`)
  and paper for light (`#F5F4EF`/`#FFFFFF`). One saturated colour per app on the primary
  action alone: Sheaf magenta, Still amber, Squish orange — **Stave's is Signal, the lime
  of a meter's healthy band** (`#C8FF4D` on ink, `#5E9A00` on paper), spent on record,
  play-head, the one primary button and the icon's band. Meters use the semantic trio
  (`#4CCB8F` good, `#F0A93D` hot, `#E8705F` clipping). Nothing else is coloured; the
  waveform is the brightest thing on the screen.
- **Shape.** Panels 18 dp radius, buttons 14 dp, press scale 0.975 on a medium spring
  (Sheaf's `pressScale`), glass surfaces at 74% with a 16% white top edge.
- **Motion.** Springs, never linear; panels grow, never jump (Squish's `SquishCard`);
  a sheet rises on a spring and its chips fade in; the play-head is a fixed line and the
  film moves (Squish's `PLAYHEAD_FRACTION` lesson — set once, with the comment).
- **Waveforms.** Peak files mipmapped per zoom level, cached on disk, drawn on a
  GPU-backed canvas; RMS inside peak; a spectrogram with a perceptual colour map that
  reads in both themes; 90/120 Hz scrubbing at under 8 ms of app work.
- **Gestures.** Pinch to zoom time, two-finger drag to scroll, drag to select, long press
  to carry, double tap to fit, swipe anywhere on a sheet to move the chosen slider.
- **Words.** Plain English, no jargon on the first level ("Clean up voice", "Even out
  volume", "Pull apart the song"), the engineering word on the second ("DTLN", "LUFS").
- **Accessibility is design.** `uiautomator dump` with zero NAF nodes on every screen is
  a release gate.

---

## 5. The task audit — in build order

Each phase ends with a release tag. Each task names the check that proves it, because a
check nobody has seen fail is a check nobody has tested (Squish's CLAUDE.md).

### P0 — Foundations (week 1)
1. Repo `REPO_NAME`: Gradle 8.9, AGP 8.6.1, Kotlin 2.2.x, Compose BOM 2024.09, minSdk 26,
   compileSdk/targetSdk 36, modules `:app`, `:engine` (pure Kotlin, no `android.*`),
   `:native` (C++/Oboe/FFmpeg). GPL-3.0 licence (FFmpeg GPL build and Rubber Band GPL
   make it so; THIRD_PARTY_NOTICES.md from day one).
2. Manifest strips INTERNET and ACCESS_NETWORK_STATE; `tools/verify_manifest.py` in CI.
3. Theme: tokens above, Space Grotesk + Fira Code in `res/font`, light/dark, `StaveColors`
   holder (Still's pattern), `Panel`, `Button`, `Chip`, `Sheet`, `Meter`, `Knob`, `Fader`.
4. `CLAUDE.md` written for the next stranger: where you are decides what "verified" means;
   the checker list; the conventions carried from Squish (locale `.format`, no ASCII cut of
   a localised string, code points not chars, `recordLate`, `selectable` not `clickable`,
   LTR-locked timeline, comments say why).
5. Tools copied and adapted from Squish: `check_unresolved.py`, `check_calls.py`,
   `check_nesting.py`, `check_modifier_imports.py`, `check_dependencies.py`, `tools/jvm/run.sh`,
   `jc.sh`, `run_desktop.sh`, `RunnerChecks.kt`; Still's `jvm-check.sh` for `:engine`.
6. CI: `android.yml` (engine checks, then `assembleDebug`, `lintDebug` zero errors),
   `native.yml` (FFmpeg audio-only for 4 ABIs, Oboe, Rubber Band, TinySoundFont, whisper.cpp),
   `release.yml` (signed APK + AAB to GitHub Releases, F-Droid metadata).
7. Docs: `README.md` in the family's voice ("An audio studio that cannot upload your
   sound"), `ARCHITECTURE.md`, `docs/COMPETITORS.md` (section 2 above, dated),
   `docs/ROADMAP.md` (this section as checkboxes), `docs/DEVICE_FINDINGS.md` (empty, dated),
   `docs/PENDING_TESTS.md`.
   **Check:** CI green on an empty app that opens, shows the grid and the theme, and
   declares no network permission in the merged manifest report.

### P1 — Native audio core (weeks 2–4)
8. `libstaveav`: Squish's `squishav.c` trimmed to audio demux/decode/encode/resample
   (LAME, Opus, Vorbis, FLAC, AAC via FFmpeg's encoder or MediaCodec), `AvHandles.failed`
   kept (tagged pointers are negative). **Check:** `AvChecks` on the JVM against a stub;
   on the phone `AvSelfTestActivity` runs ~60 odd files from `testmedia.yml`.
9. Render graph in C++: lock-free command queue, clip readers streaming from disk with a
   read-ahead ring, per-track insert chain, buses, sends, master, automation sampled per
   buffer with linear ramps, Oboe output with an exclusive low-latency stream and a
   fallback. **Check:** a JVM-side `GraphChecks` over a Kotlin model of the same mixing
   arithmetic (gain, pan law, summing, automation ramps), negative-tested; on device a
   null-test — a clip through the graph at unity equals the file to 1e-6.
10. Input: Oboe input with monitoring, loopback latency measurement, device list
    (phone, USB class, Bluetooth), per-take WAV 32f writer that finalises on crash.
    **Check:** a recorded click aligned to the played click within one buffer.
11. DSP library in `:engine` (pure Kotlin, so it is executed on the JVM) with C++
    twins where speed demands: biquads, FFT (Squish's `Fft`), STFT/overlap-add,
    resampler (Squish's `AacWriter`-adjacent code), envelope followers, true-peak
    (4x oversampled), EBU R128 (Squish's `Loudness`). **Check:** every block has a suite
    with real numbers (a 1 kHz tone at −20 dBFS reads −20.0 LUFS ± 0.1; a brick-wall at
    −1 dBTP never exceeds it on an inter-sample peak).
12. Rubber Band R3 wrapped (time, pitch, formant) with a JVM check of its phase
    continuity on a chirp; reverse; speed ramps via Squish's `SpeedRamp`.

### P2 — The wave editor, first release (weeks 5–8)
13. Project model: regions, edits as a replayable list, unlimited undo persisted,
    JSON codec with `putFinite` (Squish's `DraftNumbers` lesson), round-trip suite.
14. Waveform renderer: peak-file mipmaps on disk, GPU canvas, RMS-in-peak, selection,
    play-head fixed in the middle, pinch/scroll/fling, `WindowChecks`-style model suite.
15. Spectrogram renderer and **spectral editing** (brush, lasso, attenuate/interpolate/
    replace) with the STFT round trip checked to −80 dB reconstruction error.
16. Edit set: cut/copy/paste/trim/insert silence/split/join, fades with shapes, clip gain
    envelopes, normalise (peak and LUFS), reverse, silence trim/split. Suite per rule.
17. Repair: de-click, de-crackle, de-hum (tracked), de-clip, de-ess, de-plosive,
    spectral de-noise with a learned profile, DTLN voice isolation (Squish's
    `DtlnTflite`, LiteRT 1.4.0 — **never 2.16.1**). Each with a synthetic-fault suite
    (a click injected, removed to within −60 dB; a 50 Hz hum at −30 dBFS removed to −70).
18. Dynamics and EQ sheets with the analyser behind the curve; presets; A/B; the knob
    and fader components with names and values for accessibility.
19. Export sheet: format, rate, depth, dither, metadata, art, loudness target, size
    estimate; batch over a folder; `ExportSettingsChecks` pattern.
20. Shell: projects grid, open any file via picker/Share/Open-with, library, bin,
    settings, storage card, coach mark once.
    **Release gate:** install on the moto; open an MP3, cut a region, de-click, export to
    MP3 320 and FLAC; `Mp4Probe`-style probe of the outputs; zero NAF nodes; frame time
    under 8 ms scrubbing on the perf build.

### P3 — Recorder and podcast desk (weeks 9–12)
21. Recorder screen: level meter with true-peak, input chain (gain, HPF, de-esser,
    limiter), monitoring with latency readout, count-in, take lanes.
22. Whisper on-device (Squish's `libsquishwhisper`, `WhisperWindows` at 28 s windows,
    bytes not strings): transcript aligned to the waveform; language chooser;
    Hindi and Malayalam in their own scripts. **Check:** WER on `edgemedia`'s espeak
    files via Squish's `Wer.kt` (English ≤ 3%).
23. Transcript editor: tap words to select audio, Delete, "Take out every um and uh"
    (fillers merged per line, never across — Squish sweep five), search, undo as one step.
    Suite: `TranscriptChecks`, `SpanRemovalChecks` carried over and extended.
24. One-tap chain: Clean up voice (DTLN + de-ess + de-plosive), Even out volume,
    Remove silences (with a floor and a minimum gap), Duck music under speech
    (`DuckRules`), Loudness to target. Each a suite; each reversible.
25. Chapters, show notes from the transcript, publish presets; ID3v2.4 chapters and
    MP4 chapter tracks written and read back by a probe.
26. Read aloud with the Indic voices (Squish's `voices-v1` release pattern; the owner
    publishes the release). Speaker labelling marked stretch.
    **Release gate:** record a 10-minute two-voice conversation on the moto, transcribe,
    cut three words and two silences from the transcript, loudness −16, export M4A with
    chapters; open in a podcast app and see the chapters.

### P4 — Multitrack studio (weeks 13–18)
27. Tracks, buses, sends, sidechain; mixer with meters; inserts; pan laws.
28. Clip operations non-destructive (gain, fades, stretch, pitch, reverse, crossfade on
    overlap); the strip from Squish's `TimelineLanes`/`LaneChecks` adapted to audio
    (rows, snapping, carry by long press, trim handles that follow the finger).
29. Automation on everything, drawn and recorded, curves; `ValueKey` tracks, suite.
30. Punch/loop recording into take lanes; comping by swipe; pre-roll.
31. Latency compensation per insert, measured and checked by a null test.
32. Tempo map, warp markers, beat snapping via `BeatDetector`; grid in bars/beats or time.
33. Freeze, bounce in place, stem export; project snapshots and bin.
34. USB audio and MIDI interfaces through AAudio/`MidiManager`; BLE MIDI.
    **Release gate:** a 24-track session with 6 inserts each plays glitch-free at the
    phone's low-latency buffer; a null test on a bounced track; a USB interface records
    four channels.

### P5 — Musician's tools (weeks 19–24)
35. Stems: Open-Unmix UMX int8 (LiteRT/ONNX Runtime Mobile) bundled; HT-Demucs 6-stem
    as an on-demand asset pack and in the "full" APK; chunked inference with overlap;
    progress; cancel. **Check:** SDR on a MUSDB18 excerpt matches the published figure
    within 0.5 dB on the JVM port of the post-processing; on the moto a 4-minute song
    separates in under real time ×2.
36. Chords (chroma + HMM, or a small model), key, BPM with tap-correct, smart click,
    section loop, pitch and speed independent. Suites with synthesised progressions.
37. Tuner (CREPE LiteRT), pitch trace of a take against the reference.
38. Lyrics (Whisper) aligned; karaoke view over the instrumental stem.
39. Audio-to-MIDI (Basic Pitch ONNX) into the piano roll; chord track to MIDI.
    **Release gate:** pull apart a song, mute the vocal, record a take over it, tune the
    take, export the mix — nothing uploaded, no counter anywhere.

### P6 — Beats and MIDI (weeks 25–30)
40. Step sequencer, piano roll (scale lock, chord stamps, velocity/CC lanes), arrangement.
41. Drum sampler (pads, choke, slice-to-pads), SoundFont player (TinySoundFont),
    subtractive, FM and wavetable synths, open-licensed starter bank.
42. Arpeggiator, groove templates, swing; MIDI in/out/clock; MIDI file import/export.
    **Release gate:** a four-bar beat with a sampled break sliced to pads, a bass line in
    the piano roll, exported as stems + MIDI and opened in a desktop DAW in time.

### P7 — Vocal and live (weeks 31–33)
43. Autotune live (low latency) and offline with a note graph; vocal effect presets
    (Squish's `VoiceEffects`); looper; karaoke over stems.

### P8 — Mastering and finish (weeks 34–36)
44. Mastering chain, EQ match, width, limiter, dither, A/B level-matched, report;
    stems + master export; album metadata.

### P9 — Polish and release (weeks 37–40)
45. Accessibility sweep (zero NAF, every state spoken), locale sweep (Arabic digits,
    German decimal, Turkish i — Squish's `ControlChecks`, `LutChecks`), RTL, font scale.
46. Performance on the perf build: scrub under 8 ms app time, 0 frames at rest, graph
    under 30% of one core at 24 tracks; `TraceTop` for anything over.
47. Store assets, screenshots in both themes, F-Droid metadata, reproducible release
    build, GitHub Release with APK, AAB, checksums, THIRD_PARTY_NOTICES.
48. `docs/DEVICE_FINDINGS.md` filled; `docs/PENDING_TESTS.md` emptied or honest.

---

## 6. The command for the laptop CLI

Paste everything inside the fence into Claude Code on the laptop, from an empty folder,
after replacing `REPO_NAME` (the repo you create on GitHub) and checking that
`squish-android`, `layerbit-image-editor-android`, `sheaf-android` and `layerlink-android`
are cloned beside it (the command clones them if not).

It is written to be resumed: a later session given the same text starts at the first
unchecked item in `docs/ROADMAP.md`.

```text
You are building Stave, an open-source Android audio studio, end to end, in the
repository git@github.com:vysakhsuresh/REPO_NAME.git. Read every word of this before
touching a file. If REPO_NAME is still literally "REPO_NAME", stop and ask for the name.

== WHO YOU ARE WORKING FOR ==
The owner builds a family of Android apps that do the whole job on the phone and
upload nothing: Squish (video editor, vysakhsuresh/squish-android), Still (picture
editor, vysakhsuresh/layerbit-image-editor-android), Sheaf (PDF toolkit,
vysakhsuresh/sheaf-android) and LayerLink (screen share, vysakhsuresh/layerlink-android).
Stave is the fifth: the audio editor. Every tool is free, forever, with no account,
no watermark, no cap, no ad and no subscription. The reason to believe that is the
repository, not a privacy policy: the app does not declare the INTERNET permission.

== THE MISSION ==
Beat BandLab, Audio Evolution Mobile, FL Studio Mobile, Moises and Lexis Audio Editor
on every tool they sell, on the phone, offline, free. Not parity: better. The brief
that lists their features and ours is docs/STAVE_BRIEF.md once you have copied it in
(the owner will give it to you; if it is not present, ask for it before planning).

== SETUP, ONCE ==
1. git clone git@github.com:vysakhsuresh/REPO_NAME.git stave-android (or init it if
   empty) and cd into it. All work lands on main and is pushed; no long-lived branches.
2. Beside it, make sure these exist (clone if not): ../squish-android,
   ../layerbit-image-editor-android, ../sheaf-android, ../layerlink-android. They are
   REFERENCE and SOURCE OF COPIES. Never edit them from this session. Never depend on
   them at build time: copy what you need into this repo with a comment saying where it
   came from, and keep the file's own "why" comments.
3. Read, in this order, before writing code: ../squish-android/CLAUDE.md (the working
   rules, the "never write verified for what you could not observe" rule, the checker
   list and the conventions section), ../squish-android/docs/COMPETITORS.md (how we
   write about competitors), ../layerbit-image-editor-android/README.md and its
   app/src/main/java/com/layerbit/still/ui/theme/Theme.kt (the family theme, the font
   setup, the "no INTERNET" manifest pattern and tools/verify_manifest.py),
   ../sheaf-android/app/src/main/java/com/layerbit/sheaf/ui/components/Common.kt (the
   shared shapes and pressScale), ../squish-android/BUILD_NOTES.md and ARCHITECTURE.md.
4. Build environment on this laptop (from Squish's CLAUDE.md): JDK 21 at
   $HOME/.jdks/jbr-21.0.11, never Android Studio's bundled JBR 25; ANDROID_HOME at
   C:\Users\vysak\AppData\Local\Android\Sdk; Git Bash rewrites /sdcard paths so prefix
   adb with MSYS_NO_PATHCONV=1; Python is not installed here so the tools/check_*.py
   checkers run only in the cloud sandbox and ./gradlew assembleDebug is the check here;
   never touch a source file while a Gradle build is running; never edit a runner script
   while it is running. The phone is the owner's moto g84 (serial ZY32J8HF2S); before
   any tap confirm it is free and Stave is the resumed activity; never delete the owner's
   media; clean up test files by MediaStore id.

== NON-NEGOTIABLES ==
- Package com.layerbit.stave. minSdk 26, compileSdk 36, targetSdk 36, Kotlin 2.2.x,
  Compose BOM 2024.09.02 with material-icons-extended pinned 1.7.8, AGP 8.6.1,
  Gradle 8.9 (match ../layerbit-image-editor-android unless a library forces otherwise;
  write down why if it does).
- AndroidManifest.xml removes INTERNET and ACCESS_NETWORK_STATE with tools:node="remove".
  tools/verify_manifest.py (copied from Still) fails CI if the merged manifest gains
  either back. Never resolve a merger conflict by deleting those lines.
- Licence GPL-3.0 for the app (the FFmpeg build and Rubber Band are GPL). Every
  third-party piece in THIRD_PARTY_NOTICES.md the day it is added, with its licence text
  requirement noted ("Before a store release" lines as Squish does).
- Three modules: :engine (pure Kotlin, imports nothing from android.*, every rule and
  every piece of arithmetic that can be separated from the framework lives here and is
  executed on the JVM), :native (C++: Oboe, libstaveav from Squish's FFmpeg, Rubber Band,
  TinySoundFont, whisper.cpp, the render graph), :app (Compose UI, Android glue).
- Typography: Space Grotesk for every word (copy the four TTFs from
  ../layerlink-android/core/src/main/res/font), Fira Code variable for every measured
  number (copy from Still; set the weight axis or Android draws the lightest instance).
  Typography scale and tokens as Still's Theme.kt. The family base palette is Still's
  Dark and Light palettes verbatim; Stave's one accent is "Signal", lime #C8FF4D on ink
  and #5E9A00 on paper, spent on record, the play-head, the single primary button and
  the icon band. Meters use #4CCB8F / #F0A93D / #E8705F. Nothing else is coloured.
- Design: panels 18 dp, buttons 14 dp, pressScale 0.975 on a medium spring, glass
  surfaces, springs never linear animations, a fixed play-head with the film moving under
  it (read the PLAYHEAD_FRACTION comment in Squish's TimelineWindow.kt and keep its
  lesson), plain-English first-level words with the engineering word on the second
  level, 48 dp targets, a name and a state on every control. Zero NAF nodes in
  uiautomator dump on every screen is a release gate.
- Conventions carried from Squish (write them into CLAUDE.md): comments say why not
  what; String.format(Locale.ROOT) for anything machine-readable and never cut a
  localised string with an ASCII literal; count code points not chars; three letters is
  a Latin rule; a result that lands in the background goes in with recordLate; a one-shot
  command carries no gesture id; Modifier.selectable not clickable where a tint is the
  only mark; the timeline subtree is locked to LayoutDirection.Ltr; Compose compares
  unstable parameters by identity so declare stable collections in
  compose_stability.conf and never mutate a list you have passed down; LiteRT 1.4.0 and
  never TensorFlow Lite 2.16.1; a native handle can be negative so AvHandles.failed is
  the only test for a failed open; every float into a draft goes through putFinite.
- Verification, in Squish's words: never write "verified" for something this session
  could not observe. In the sandbox the ceiling is "the arithmetic is executed on the
  JVM and it compiles structurally". On this laptop ./gradlew assembleDebug and
  ./gradlew lintDebug (zero errors) settle a build, and only the phone settles a sound.
  Every pure rule gets a tools/jvm suite that is EXECUTED with real numbers, and every
  suite is negative-tested: copy the file to the scratchpad, put the fault back, run the
  suite against the copy, watch it fail, then restore. A check nobody has seen fail is a
  check nobody has tested. When a mistake escapes, write the check that would have
  caught it before writing the fix.
- Git: commit after every task with a message that says why; push to main when the
  build passes, without asking. Do not put any model name in commits, code or docs.
  Never force-push.

== WHAT TO CARRY ACROSS FROM SQUISH (copy, attribute, adapt) ==
From ../squish-android/app/src/main/java/com/squish/app/media/audio: Fft, Loudness,
BeatDetector, PcmDecoder, Waveform, WaveformCache, VoiceCleaner, NoiseReducer,
NoiseProcessor, DtlnTflite, FadeProcessor, GainProcessor, GainCurveProcessor,
SpeechSegmenter, Transcriber, Whisper, WhisperText, WhisperWindows, Tts, TtsRules,
NeuralVoice, VoiceRecorder, VoiceSplit(ter), AacWriter, AudioFinish, FinishProcessor,
MusicSynth (for the starter sound bank only). From av/: AvNative.kt (its AvHandles.failed test for a failed open).
From timeline/: SpeedRamp, ValueTracks, Keyframe, TimelineWindow, TimelineLanes and their
LaneChecks/WindowChecks/MagneticChecks suites. From editor/edits/: TextEdits (transcript
editing), TranscriptChecks, SpanRemovalChecks, Wer.kt. From data/: DraftNumbers,
ProjectAutosave, HousekeepingChecks. From tools/: check_unresolved.py, check_calls.py,
check_nesting.py, check_modifier_imports.py, check_dependencies.py, tools/jvm/run.sh,
jc.sh, run_desktop.sh, RunnerChecks.kt, TraceTop.kt, Mp4Probe.kt, bench.sh pattern.
From app/src/main/cpp/squishav.c and tools/native/build.sh: the FFmpeg build, trimmed to
audio demuxers, decoders, encoders (lame, opus, vorbis, flac, aac) and swresample, as
libstaveav; whisper.cpp as in tools/native/whisper and whisper.yml. From .github/
workflows: native.yml, whisper.yml, testmedia.yml (audio files only), release pattern.
Media3 is NOT the engine here: a DAW needs a sample-accurate native graph. Use Media3
only where it is the simplest correct door (the system media session, nothing else).

== NEW NATIVE PIECES (Apache/MIT/BSD first, GPL where quality demands) ==
Oboe (Apache-2.0) for input and output with a measured loopback latency. Rubber Band R3
(GPL) for time, pitch and formant. TinySoundFont (MIT) for SF2/SF3. Open-Unmix UMX
(MIT) int8 bundled for 4-stem separation, HT-Demucs (MIT) 6-stem as a Play on-demand
asset pack and inside the GitHub "full" flavour APK, both run through LiteRT or ONNX
Runtime Mobile with chunked overlap-add inference. CREPE LiteRT (MIT) for the tuner.
Spotify Basic Pitch (Apache-2.0, ONNX) for audio-to-MIDI. For chords use chroma + HMM
in :engine first; a model only if the suite shows it is better. RNNoise (BSD) beside
DTLN if DTLN is weak on music. Every model's licence, size and source URL into
THIRD_PARTY_NOTICES.md; models that are large go in the asset pack, never a download
the app performs (there is no INTERNET).

== THE ORDER OF WORK ==
docs/ROADMAP.md is the checklist, written from section 5 of docs/STAVE_BRIEF.md with
every task as a checkbox and its check beside it. Work from the first unchecked item.
Do not skip ahead to a shinier pillar: P0 foundations, P1 native core, P2 wave editor
(first release tag v0.1), P3 recorder and podcast desk (v0.2), P4 multitrack (v0.3),
P5 musician's tools (v0.4), P6 beats and MIDI (v0.5), P7 vocal and live (v0.6),
P8 mastering (v0.7), P9 polish (v1.0). Each phase ends with its release gate run on the
phone if it is attached and written into docs/DEVICE_FINDINGS.md with the date; if the
phone is not attached, the gate goes into docs/PENDING_TESTS.md in the order it should be
run, and the roadmap item is marked built-not-seen, never done.

Within a task: write the pure rule in :engine first, write its suite, run it, break it
and watch it fail, restore; then the native or UI piece; then ./gradlew assembleDebug
and lintDebug; then commit and push. Keep CLAUDE.md current with anything the next
stranger would need: what took an hour to find out goes in there in one paragraph.

== DOCUMENTS TO WRITE IN P0 ==
README.md in the family's voice (read Still's and Sheaf's: "A ... that cannot upload
your ..."; why this exists; nothing leaves the phone; the table of what it does).
CLAUDE.md as above. ARCHITECTURE.md (the three modules, the graph, the project file,
where every door into media is). BUILD_NOTES.md (Oboe stream choices, the FFmpeg
configure line, LiteRT/ONNX delegate choices, the things most likely to need a touch).
docs/COMPETITORS.md (section 2 of the brief, dated, "read, not driven").
docs/ROADMAP.md, docs/DEVICE_FINDINGS.md, docs/PENDING_TESTS.md, docs/FEATURES.md
(grows with each phase), THIRD_PARTY_NOTICES.md, LICENSE (GPL-3.0), store-assets/
(icon source SVG, feature graphic, both-theme screenshots as they exist), fastlane/
metadata for F-Droid.

== WHAT DONE LOOKS LIKE FOR THIS SESSION ==
Work until the context runs low. Before you stop: every change committed and pushed to
main; CI green or the failure named in docs/PENDING_TESTS.md; docs/ROADMAP.md ticked
honestly; CLAUDE.md told what you learned; a final message that says exactly which
roadmap items are done, which are built-not-seen, and what the next session should do
first. Do not describe anything as working on a phone unless you watched it work there.
Begin with P0 item 1 unless docs/ROADMAP.md already exists, in which case begin at its
first unchecked item.
```

---

## What to expect, honestly

- This is a year of work for one person with a CLI; the command is built to be run
  session after session, each starting at the roadmap's first open box. The first
  shippable thing (P2, the wave editor that replaces Lexis for 10 million people) is
  about two months in.
- The on-device stems are the one feature where quality depends on model size. The
  bundled 4-stem model will be good, not Moises-Pro good; the 6-stem HT-Demucs pack
  closes most of that gap at ~80–300 MB, which is why it is an asset pack and not in
  the base APK.
- GPL-3.0 is the honest licence: FFmpeg's GPL build and Rubber Band make it so. That is
  also the licence that keeps a fork from closing the source and charging for it.
- Speaker labelling and a wavetable synth are marked stretch; everything else on the
  list has an open implementation to stand on.
