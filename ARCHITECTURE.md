# Architecture

Stave is three layers, with the arrows pointing one way.

```
 ┌──────────────────────────────────────────────────────────────────────┐
 │ :app  (Compose, Android)                                              │
 │   ui/            screens, sheets, the waveform canvas, the mixer       │
 │   audio/         AudioService (foreground), Devices, Transport         │
 │   files/         ProjectStore, PeakStore, MediaDoor (picker, Share)    │
 │   export/        RenderService, Encoders (libstaveav, MediaCodec)      │
 │   models/        ModelStore: asset pack, full-APK assets, imports      │
 │   native/        the JNI objects: EngineNative, AvNative, WhisperNative│
 └───────────────▲───────────────────────────────────▲───────────────────┘
                 │ depends on                        │ loads
 ┌───────────────┴───────────────────┐   ┌───────────┴───────────────────┐
 │ :engine (pure Kotlin)              │   │ app/src/main/cpp (C++)         │
 │   dsp/      filters, dynamics,     │   │   graph/   Oboe render graph:  │
 │             loudness, repair,      │   │            tracks, clips,      │
 │             stft, time/pitch       │   │            inserts, buses,     │
 │   wave/     peaks, time window     │   │            automation, meters  │
 │   project/  session, history, json │   │   av/      libstaveav JNI      │
 │   transcript/ words → cuts         │   │            (FFmpeg, in CI)     │
 │   music/    chords, key, beats     │   │   whisper/ whisper.cpp JNI     │
 │   sequencer/ patterns, MIDI        │   │   rubberband/, tsf/ (vendored) │
 │   export/   formats, targets       │   └────────────────────────────────┘
 └────────────────────────────────────┘
```

## Who decides what

**The engine decides; the app displays; the graph plays.** Every number a screen shows and
every sample an offline tool changes comes from `:engine`, which has no Android in it and is
executed by `tools/jvm/run.sh`. The native graph is the realtime half: it mixes what the
session says, with its own copies of the few DSP pieces that must run in the audio callback
(gain ramps, pan, the insert chain's biquads and dynamics, the metronome). Where the two have
the same arithmetic - a biquad, a compressor - the Kotlin one is the reference the suite
checks and the C++ one is checked against it by a null test on the phone.

## The project

A project is a folder under `files/projects/<id>/`:

```
project.json      the session: tracks, clips, automation, markers, settings  (ProjectJson)
history.json      the edit history, so undo survives a restart               (History)
media/            imported sources, copied in, never the user's original
peaks/            one .peaks per source channel                             (Peaks.toFloats)
takes/            recordings, 32-bit float WAV, finalised on crash
renders/          frozen tracks, stems, bounces
snapshots/        a copy of project.json every ten minutes, five kept
```

Nothing is destructive. A clip refers to a source by id with an in-point, an out-point and a
list of processes; an offline tool that changes samples (de-click, denoise) writes a *new*
source and the clip points at it, so Undo is a pointer swap and the original is on disk until
the project is deleted. The JSON codec is written by hand (no reflection) and every float goes
through `putFinite`.

## The graph

`graph/Engine.cpp` owns one Oboe output stream (exclusive, low latency, falls back to shared)
and optionally one input stream. The audio callback reads a lock-free snapshot of the session
(`Snapshot`), swapped in whole by the UI thread through a triple buffer, so the callback never
takes a lock and never allocates. Each track pulls its clips' samples from `ClipReader`s that
stream from disk on a worker thread into ring buffers with a two-second read-ahead; a seek
drains and refills them. Inserts run per track, buses sum, the master meters and limits. Every
parameter change is a ramp over one buffer, so a fader never clicks. Latency compensation adds
the chain's reported delay to every other track.

## Doors into media

Every file enters through `MediaDoor`: the document picker or a Share intent gives a Uri, the
app copies it into the project (a Uri's grant does not survive a restart and is not the app's
to keep), and `AvNative` probes and decodes it. There is no second way to read a file, so the
formats only FFmpeg reads cannot go black somewhere else.

## Models

`models/ModelStore` finds a model in this order: the Play asset pack directory, the `full`
flavour's assets, a file the person imported through the picker. Nothing downloads. Each model's
licence, size and source is in `THIRD_PARTY_NOTICES.md`.
