# Changelog

Dated. What a phone has seen is in `docs/DEVICE_FINDINGS.md`; everything else here is built and
executed on the JVM, not yet seen.

## 1.0.0 — unreleased

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
