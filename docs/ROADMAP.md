# Roadmap

The work order. A session starts at the first box that is not `[x]`. Three marks:
`[x]` seen working on a phone and written into `docs/DEVICE_FINDINGS.md`; `[~]` built,
suites passing, **not seen** - listed in `docs/PENDING_TESTS.md`; `[ ]` not built.
Built-not-seen is never called done.

## P0 — Foundations
- [~] Gradle 8.13 / AGP 8.13.2 / Kotlin 2.2.21; `:engine` (pure) and `:app`; flavours `play` and `full`
- [~] Manifest strips INTERNET and storage; `tools/verify_manifest.py` in CI
- [~] Theme: ink and paper, Signal lime; Space Grotesk; Fira Code numerals; `StaveColors`
- [~] Shared components: Panel, buttons, chips (`selectable`), ToggleRow (`toggleable`), TopBar, Ambient
- [~] `CLAUDE.md`, `README.md`, `ARCHITECTURE.md`, `BUILD_NOTES.md`, `THIRD_PARTY_NOTICES.md`, docs/
- [~] `tools/jvm/run.sh` + Registry + RunnerChecks; the Squish checkers adapted
- [~] CI: android.yml (engine, checks, build, manifest), native.yml, whisper.yml, release.yml
- [ ] Shell: Home (projects), Guide, Contact, About, Settings; Share/Open-with arrivals

## P1 — Engine core (pure Kotlin, executed)
- [~] FFT, windows, STFT with exact reconstruction (`FftChecks`)
- [~] RBJ biquads, parametric and graphic EQ with the drawn curve from the same coefficients (`BiquadChecks`)
- [~] Envelope followers, compressor (soft knee, look-ahead, linked), limiter, gate, de-esser, peak meter (`DynamicsChecks`)
- [~] Windowed-sinc resampler, 4x true peak, EBU R128 loudness with gating and LRA, targets (`LoudnessChecks`, `ResamplerChecks`)
- [~] Fade shapes, crossfade, gain envelope in dB, TPDF dither (`FadesChecks`)
- [~] De-click, de-clip, DC, hum find and notch (`RepairChecks`)
- [~] Spectral denoise with learned and estimated profiles (`DenoiseChecks`)
- [~] Phase vocoder stretch, pitch shift, tape speed (`TimePitchChecks`)
- [~] YIN pitch, note names (`PitchChecks`)
- [~] Peak pyramid, fixed-play-head time window, ruler (`PeaksChecks`)
- [~] Timecode for screen and file (`TimecodeChecks`)
- [ ] Project model: sources, clips, tracks, markers, regions; JSON codec with `putFinite`; round trip suite
- [ ] Edit history: undo/redo, gesture folding, `recordLate`; suite
- [ ] Region edits: cut/copy/paste/trim/silence/split/join over the clip list; suite
- [ ] Spectral editing geometry: a brush or lasso in time-frequency to bin masks; suite
- [ ] Multiband compressor, transient shaper, leveller; suites
- [ ] Beat tracking (onsets, tempo, dynamic programming) carried from Squish; suite
- [ ] Chroma, chords (templates + Viterbi), key (Krumhansl); suite
- [ ] Transcript rules: words → cuts, fillers per line, silence removal, alignment; suite
- [ ] Sequencer model: patterns, steps, notes, swing, humanise, arpeggiator, quantise; MIDI file read/write; suite
- [ ] Synth DSP reference: PolyBLEP oscillators, ADSR, SVF, FM operator; suite
- [ ] Export arithmetic: format table, bitrate and size estimates, chapter writing (ID3v2.4, MP4 chpl); suite

## P1b — Native
- [ ] CMake: `libstave.so` from graph/, Rubber Band single, TinySoundFont; Oboe via prefab
- [ ] Graph: snapshot triple-buffer, clip readers with read-ahead, track inserts, buses, master, meters, metronome
- [ ] Input stream, monitoring, loopback latency
- [ ] `av/staveav.c`: probe, decode to float, encode (every encoder in the build), metadata and art, chapters
- [ ] `whisper/stavewhisper.cpp`: bytes surface, windows
- [ ] JNI objects in `native/`; `AvHandles.failed`

## P2 — Wave editor (v0.1)
- [ ] Projects: ProjectStore, autosave, snapshots, bin
- [ ] Waveform canvas: peaks at every zoom, samples when fine, selection, fixed play-head, pinch/scroll/fling
- [ ] Spectrogram canvas and the spectral brush
- [ ] Edit sheet, Repair sheet, EQ sheet (curve + analyser), Dynamics sheet, Time & Pitch sheet, Fades
- [ ] Markers, regions, chapters
- [ ] Export sheet: format, rate, depth, dither, loudness target, metadata, art; batch
- [ ] Library of exports; Share; "Open with"
- [ ] Guide, Contact, About, Settings complete

## P3 — Recorder and podcast desk (v0.2)
- [ ] Recorder screen with true-peak meter, input chain, count-in, takes
- [ ] Whisper transcript aligned to the waveform; language chooser
- [ ] Transcript editor: tap words to cut; fillers; search
- [ ] One-tap chain: Clean up voice, Even out, Remove silences, Duck, Loudness
- [ ] Chapters and show notes; publish presets
- [ ] Read aloud (TTS door)

## P4 — Multitrack studio (v0.3)
- [ ] Arrangement strip: tracks, clips, carry by long press, snapping to grid and beats
- [ ] Mixer: faders, pans, meters, inserts, sends, buses
- [ ] Automation lanes, drawn and recorded
- [ ] Punch/loop into take lanes; comping
- [ ] Latency compensation, freeze, bounce, stems
- [ ] USB audio and MIDI interfaces

## P5 — Musician's tools (v0.4)
- [ ] Stems: UMX int8 bundled; HT-Demucs pack; chunked inference with progress
- [ ] Chords, key, BPM, smart click, section loop, pitch and speed
- [ ] Tuner (CREPE), pitch trace
- [ ] Lyrics aligned; karaoke view
- [ ] Audio-to-MIDI (Basic Pitch)

## P6 — Beats and MIDI (v0.5)
- [ ] Step sequencer, piano roll, arrangement of patterns
- [ ] Drum sampler, SoundFont player, subtractive, FM and wavetable synths; starter bank
- [ ] Arpeggiator, groove; MIDI in/out/clock; MIDI files

## P7 — Vocal and live (v0.6)
- [ ] Autotune live and on a note graph; vocal effect presets; looper; karaoke over stems

## P8 — Mastering (v0.7)
- [ ] Mastering chain, EQ match, width, limiter, dither, A/B, report; stems + master

## P9 — Polish and release (v1.0)
- [ ] Accessibility sweep (zero NAF), locale sweep, RTL, font scale
- [ ] Perf build frame times; graph CPU at 24 tracks
- [ ] Store assets, F-Droid metadata, release notes, signed release
