# Stave — what this build does

Grows with each phase. Dated.

## 31 October 2026 — P0/P1 foundations
- A pure Kotlin engine with: FFT/STFT, RBJ biquads, parametric and graphic EQ, envelope
  followers, compressor, limiter, gate, de-esser, peak meter, windowed-sinc resampler, true
  peak, EBU R128 loudness meter with targets, fades and gain envelopes, TPDF dither, de-click,
  de-clip, DC removal, hum detection and notching, spectral denoise, phase-vocoder time and
  pitch, YIN pitch detection, a waveform peak pyramid, a fixed-play-head time window, a ruler,
  and locale-safe timecode. Every piece is executed by a suite.
- The app shell: theme, components, manifest without INTERNET, CI.
- The project model (sources, clips, tracks, markers, regions, tempo), pure session edits and an
  edit history with gesture folding; the export format table. Four more suites.
- The wave editor (v0.1, built, not yet seen on a phone): open any file FFmpeg reads, the
  waveform at every zoom with a fixed play-head, select and edit (cut, copy, paste, delete with
  or without ripple, silence, split, crop), gain and normalise (peak or LUFS), fades on the clip
  or rendered, repair (clicks, clipping, hum, noise learned or estimated, sibilance), EQ with the
  curve drawn from the filters themselves, compressor, limiter, gate, time and pitch, speed,
  markers and chapters, export to MP3, AAC, Opus, Vorbis, FLAC, ALAC, WAV, AIFF or WavPack with
  tags, chapters, loudness normalisation and a size estimate, saved to Music/Stave.
- The native graph, the FFmpeg door and the whisper door: written, awaiting the first build.
- The Voice tab (v0.2, built, not yet seen on a phone): a recorder with a meter and monitoring,
  takes on their own lanes, a Whisper transcript in any of Whisper's languages, words as chips
  that select the sound under them, Delete words and Take out every um and uh, Remove silences,
  Clean up voice, Even out volume, Loudness to target, Chapter here.
- Spectral editing (v0.1b): the spectrogram over the selected track, a box over a cough or a
  whistle, Turn down, Remove or Fill from around.
- The Studio tab (v0.3, built, not yet seen on a phone): a mixer with a strip per track and a
  master, inserts with live parameters, sends and buses, volume and pan automation points,
  clips carried by a long press, a click with its tempo, loop the selection, Bounce to a new
  track, every track exported as a stem, Duck under speech.
- The Finish tab (v0.7 brought forward): Measure the mix as a broadcast meter does, pick the
  target, Master to it in one step.

## 31 October 2026 — P5 and P6 (built, not yet seen on a phone)
- The Musician tab: Read the song (tempo, beats, the bar from the bass bands, key, chords with
  the time each starts, ½ and 2× tempo, a bar shift, Mark every bar, the click following the
  beats), a tuner off the live input, stems on a LiteRT model when one is present, a practice
  copy slowed and transposed, Notes to MIDI from a sung or played selection.
- The Beats tab: patterns in the project (ticks, 960 a quarter) on a step grid that is also the
  piano roll, pads and keys that play live and record into the loop, swing, velocity by a drag,
  quantise, humanise, legato, transpose, double, an arpeggiator, the song's chords as a pattern,
  a song of placements, Render to a track and Render the song (one track per instrument),
  Standard MIDI files in and out, a drum machine on a synthesised sixteen-sound starter kit,
  seven subtractive and five FM synth presets, and any SoundFont the person adds.
