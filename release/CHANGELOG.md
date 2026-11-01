# Changelog

Dated. What a phone has seen is in `docs/DEVICE_FINDINGS.md`; everything else here is built and
executed on the JVM, not yet seen.

## 1.0.0 — 2 November 2026

First built, run and measured on a phone (moto g84 5G, Android 15). Seen there: opening MP3,
Ogg and WAV; live playback byte-identical to the source; export to MP3, FLAC and WAV (the WAV
byte-identical to the source); recording takes on lanes; Whisper transcripts word for word
with word times; fillers and silences cut with Undo, which also survives closing the app;
Master to Streaming at -14.4 LUFS and -1.0 dBTP; bounce byte-identical to the mix; 24 tracks
at under 20% of a core; smooth scrubbing and playback on the release build. Not in this
build: the stem separation model (Stems and Keep only the voice say so). Not yet heard: the
live tuner and the tuners on a sung note, the pattern player's timing, comping.
`docs/DEVICE_FINDINGS.md` has the numbers.

- The wave editor: any file FFmpeg reads, peaks at every zoom under a fixed play-head, selection
  edits, gain, fades, repair, EQ, dynamics, time and pitch, markers and chapters, export to
  every format libstaveav encodes, saved to Music/Stave.
- Voice: recorder, takes on lanes, Whisper transcript as words, word edits, fillers, silences,
  clean-up chain, loudness to target, chapters, read a line aloud.
- Studio: mixer, inserts, sends, buses, automation, click, loop, bounce, stems export, freeze,
  duck under speech.
- Musician: stems on LiteRT (model fetched, not bundled), Read the song, tuner, practice copy,
  notes to MIDI.
- Beats: patterns, grid and piano roll, pads and keys, live loop, starter kit, synths,
  SoundFonts, arpeggiator, song, render to track, MIDI in and out.
- Finish: measure, master to target, report, match a reference, width, three bands,
  transients, level-matched A/B.
