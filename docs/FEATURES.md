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
