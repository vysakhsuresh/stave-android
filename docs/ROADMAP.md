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
- [~] Shell: Home (projects), Guide, Contact, About, Settings; Share/Open-with arrivals

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
- [~] Project model: sources, clips, tracks, markers, regions; JSON codec with `putFinite`; round trip suite (`ProjectChecks`)
- [~] Edit history: undo/redo, gesture folding, `recordLate`, `amendAll`; suite (`ProjectChecks`)
- [~] Region edits: split/trim/move/ripple delete/insert/copy/paste/crop/snap over the clip list; suite (`SessionEditsChecks`)
- [~] Spectral editing: a box in time and frequency turned down with feathered edges, or filled in from the frames either side; the spectrogram the screen paints from the same STFT; suite (`SpectralEditChecks`)
- [~] Linkwitz-Riley crossover, three-band compressor, transient shaper, EQ match, width and correlation, the report (`MasteringChecks`); the leveller is Even out volume on the Voice tab
- [~] Beat tracking (spectral flux, tempo prior, dynamic programming) carried from Squish; onsets for the slicer; the bar by the bass bands (`BeatsChecks`, `OnsetsChecks`)
- [~] Chroma, chords (48 templates, Viterbi), key (Krumhansl), tap tempo and the grid (`ChromaChecks`, `ChordsChecks`, `KeyChecks`, `TempoChecks`)
- [~] Transcript rules: Whisper bytes (a character cut across pieces joined), windows and their seam, words → padded cuts merged by gap, fillers, the transcript following a cut, silences; suite (`TranscriptChecks`)
- [~] Sequencer model: patterns in ticks, steps, notes with ratchets and probability, swing, humanise, legato, arpeggiator, quantise, the arrangement; MIDI files read and written; the pattern renderer (`PatternChecks`, `MidiChecks`, `RenderChecks`)
- [~] Synths: PolyBLEP oscillators, ADSR, SVF, a subtractive and a four-operator FM synth, a sampler voice, a sixteen-pad drum machine, the slicer, the synthesised starter kit (`SynthChecks`, `DrumsChecks`, `KitSynthChecks`)
- [~] Export arithmetic: format table, bitrate and size estimates, file names (`ExportChecks`); chapters are written by libstaveav from the markers

## P1b — Native
- [~] CMake: `libstave.so` from graph/, Rubber Band single, TinySoundFont; Oboe via prefab — **written, never compiled** (no NDK in the sandbox): the first laptop build will find what the sandbox could not
- [~] Graph: snapshot swapped atomically, memory-mapped sources, track inserts, buses, sends, master, meters, metronome, looping, offline render
- [~] Input stream, monitoring ring, take recorder with a crash-safe WAV; loopback click scheduled (the measurement itself is P3)
- [~] `av/staveav.c`: probe, decode to float, encode (every encoder in the build), tags, cover art, chapters — unbuilt until `native.yml` runs
- [~] `whisper/stavewhisper.cpp`: bytes surface — unbuilt until `whisper.yml` runs
- [~] JNI objects in `native/`; `AvHandles.failed` is `== 0`

## P2 — Wave editor (v0.1)
- [~] Projects: ProjectStore, atomic save, ten-minute snapshots, bin with a 30-day sweep
- [~] Waveform canvas: peaks at every zoom, selection with edge handles, fixed play-head, two-finger scroll and zoom, double tap to fit — samples-when-fine not yet (zoom stops at the peak buckets)
- [~] Spectrogram in the selected lane (log frequency, a thousand columns at any zoom), a box dragged over it, Turn down / Remove / Fill from around on the Spectral sheet
- [~] Edit, Gain, Fades, Repair (clicks, clipping, hum, noise, S), EQ (parametric, and 10- or 31-band graphic on the same curve), Dynamics, Time & pitch, Effects (echo, chorus, flanger, phaser, tremolo, reverb, saturation, crusher, with their knobs; swap, one side on both, balance) sheets — each an offline render to a new source, undoable
- [~] Markers and chapters (regions are in the model, not yet on a sheet)
- [~] Export sheet: format, bitrate, depth, rate, mono, loudness target, tags, chapters, estimate; published to Music/Stave; Share and Open — no batch yet
- [~] Library of exports across projects (open, share, delete the private copy); Convert files: any number of files to one format, through the same exporter, to Music/Stave
- [~] Guide, Contact, About, Settings complete

## P3 — Recorder and podcast desk (v0.2)
- [~] Recorder: arm the mic (permission asked on the first press), level meter with hold, round-trip readout, monitor switch, Record over the existing sound onto a new lane, Pause and Resume within one take, Stop lands the take as a clip at its start (the WAV header repaired first, so a take cut short by a crash still lands)
- [~] Whisper transcript over the mix (or the selection), language chips (Indian languages first), words kept per project in transcript.json, stale flag when the sound changes under them
- [~] Transcript editor: tap words (the sound under them becomes the selection), Delete words, Take out every um and uh; no search yet
- [~] One-tap chain: Clean up voice (DC, denoise, de-ess, compress, loudness), Even out volume, Remove silences (adaptive threshold, min length, kept margin), Loudness to target; Duck under music is P4's (needs two tracks' levels)
- [~] Chapter here (a chapter marker at the play-head; written into the file by Export); show notes and publish presets not yet
- [~] Read aloud: a typed line through the phone's own speech engine (the manifest queries TTS_SERVICE), decoded from its WAV at the session's rate, landed at the play-head

## P4 — Multitrack studio (v0.3)
- [~] Arrangement strip: tracks as lanes under one play-head, clips carried by a long press, snapped to the beat grid
- [~] Mixer: a strip per track and the master (fader, pan, mute, solo, arm, meter), inserts with live parameters, sends and buses
- [~] Automation: volume and pan lanes drawn on the strip with a finger (the stroke thinned to its corners and replacing only its span, `AutomationChecks`), written by fader moves while playing, set by points, written by Duck under speech; played by the graph
- [~] Takes land on their own lanes; loop the selection; punch in and out over the selection; Takes chips on a track choose the lane heard (the others' clips muted as one edit); swipe comping across lanes not yet
- [~] Bounce to a new track; every track exported as a stem; Freeze (the track alone through its inserts to one clip, inserts off, Undo unfreezes); latency compensation not yet
- [~] MIDI controllers over USB and Bluetooth through MidiManager play the Beats tab's instrument and record into the loop (running status and real-time bytes handled); USB audio interfaces, MIDI out and MIDI clock not yet

## P5 — Musician's tools (v0.4)
- [~] Stems: a LiteRT runner over the UMX contract, chunked with progress, the four stems landing on their own tracks with the originals muted; the model itself is fetched by `tools/models/fetch_models.sh` (nothing is bundled yet), and a phone has not run it
- [~] Read the song: tempo, beats and the bar, key and chords off the mix; ½ and 2× tempo and a bar shift; Mark every bar; the click following the beats; a practice copy slowed and transposed
- [~] Tuner from the graph's input tap (YIN); a pitch trace is not drawn
- [~] Sing along: the transcript's words in lines following the play-head, the Vocals stem muted by a switch; typed lyrics laid against the heard words by edit-distance alignment (`LyricsChecks`), shown as the written lines with the sound's times
- [~] Notes to MIDI: the selection's melody as notes (YIN per hop, segmented), handed to the Beats tab as a pattern

## P6 — Beats and MIDI (v0.5)
- [~] Step sequencer and piano roll in one grid (steps toggled, velocity by a drag, probability and ratchets drawn), pads and keys that record into the loop, patterns in the project file, the song as placements, rendered to tracks
- [~] Drum machine on the synthesised starter kit; kits of the person's own sounds (the selection sliced at its onsets onto the pads, any file onto a pad), saved in the project; a SoundFont player for banks the person adds; seven subtractive and five FM presets; a wavetable synth not yet
- [~] Arpeggiator, swing, quantise, humanise, legato, transpose, double; MIDI files in and out; MIDI in/out/clock over USB not yet

## P7 — Vocal and live (v0.6)
- [~] Tune: TD-PSOLA pitch correction to a scale (any note, major, minor, pentatonic in a key) with strength and retune time, over the selection (`PitchCorrectChecks`); twelve vocal effect presets from delay, chorus, flanger, tremolo, reverb, saturation and a crusher (`EffectsChecks`); a looper (a loop of bars at the play-head, each take folded into layers on their own lanes); **live autotune** on the monitored voice (`graph/LiveTune`: YIN on the decimated input, the nearest scale note with hysteresis, a two-tap delay-line shifter, about 11 ms behind; the rules mirrored from `LiveTuneRules`, executed in `LiveTuneChecks`; the take recorded dry or tuned) - written, never compiled or heard

## P8 — Mastering (v0.7)
- [~] Finish tab: Measure (integrated, range, true peak, sample peak, the distance to the target), the target, Tone and Glue, Master to the target in one step; the report (correlation, balance, lows/mids/highs); Match a reference (its spectrum read off the file, the bands solved jointly, strength, Apply); Width; Three bands; Transients; Compare with before at matched loudness

## P9 — Polish and release (v1.0)
- [ ] Accessibility sweep (zero NAF), locale sweep, RTL, font scale
- [ ] Perf build frame times; graph CPU at 24 tracks
- [~] fastlane listing text and changelog (`fastlane/metadata/android/en-US`), `release/CHANGELOG.md`; the Play flavour's models as an install-time asset pack (`models_pack`); screenshots, the icon in the listing and a signed release wait for a phone and the owner's keystore
