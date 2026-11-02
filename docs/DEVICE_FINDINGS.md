# Device findings

What was seen on a phone, dated, newest first. Nothing goes here that was not watched happening.
The phone is the moto g84 5G (Android 15), driven over adb from the Windows laptop.
"Seen" means watched on the screen, in `uiautomator dump` or in logcat; "measured" means a
number was read; "heard" means a person listened. Where a line says "not heard", the ear part
of that test is still open in `docs/PENDING_TESTS.md`.

## 1 November 2026 — the first real build and the first run

**The build (PENDING 1, 7, 8, 9)**
- `./gradlew :engine:test assembleFullDebug lintFullDebug` with JDK 21 compiled and passed after
  the fixes in the commits of this date: 37 Kotlin errors in 12 files, 22 icons tinted with
  AppCompat's attribute, one string defined twice and four bare apostrophes. Lint: 0 errors
  (58 warnings, 56 hints).
- `:engine:test` (`EngineSuitesTest`, every suite in `Registry`) passed through Gradle, the
  same suites `tools/jvm/run.sh` runs.
- `libstave.so` (graph, recorder, live tuner, Rubber Band, TinySoundFont) compiled for all four
  ABIs on the first try once CMake could fetch Rubber Band (GitHub's tag archive, SHA256
  pinned); its 48 JNI exports match the `external fun`s by name.
- `native.yml` built `libstaveav.so` (FFmpeg 8.1.3) for four ABIs after three build-script
  fixes; `staveav.c` compiled with no warnings; 21 exports match `AvNative`. `whisper.yml`
  built `libstavewhisper.so` on its first run; 4 exports match `WhisperNative`. Both committed
  under `jniLibs`.

**The empty app (PENDING 2, 3, 4, 5)**
- Installed and opened to Home in the dark theme, Space Grotesk, the status and navigation
  bars over the background. No crash on launch.
- Settings → Theme: Light recoloured Settings and then Home at once, the status-bar icons
  turning dark with it; Match phone followed the phone's dark mode; Dark brought it back. No
  restart.
- `uiautomator dump`: 0 NAF nodes on Home, Settings, Guide, Contact and About (each dump
  confirmed to be that screen by its title). Home's five round buttons read Library, Convert
  files, Guide, Contact, Settings.
- The merged debug manifest has no INTERNET permission. It did carry READ_PHONE_STATE,
  implied by LiteRT's GPU API AAR; removed (`tools/verify_manifest.py` forbids it, so CI would
  have failed on it). The release manifest is checked at release time.

**Opening a sound (PENDING 6, part of 10, 11 and 14)**
- A SEND of an MP3 (`Kalimba.mp3`, 44.1 kHz, 5:48) from the shell, with a read grant, opened
  Stave into a new project: FFmpeg decoded it, the peaks built and the waveform drew over the
  whole 5:48. An Ogg Vorbis file (`Example.ogg`, 6 s) the same.
- The first such open crashed the editor (a ruler label drawn past the canvas edge); fixed.
- The graph's output opened at 48 000 Hz, burst 192 frames, shared mode (logcat `StaveGraph`).
- Play: the clock went from 2:54.02 to 2:59.46 in about 5.5 s of wall time with the film moving
  under the fixed play-head, and AudioFlinger listed the app's stream as active. **Not heard**:
  whether the sound is right, and in time with the clock, waits for an ear (10, 11).
- The editor's dump: 0 NAF; the strip reads "Waveform, 5:48", every button has a name.
- Found and fixed on the way: the first Undo after opening read "Undo Rename" (the session's
  name was a second step); Settings → Input listed "moto g84 5G (phone)" twice and the FM
  tuner, now "Phone mic (bottom)" and "Phone mic (back)" only.

**The editor, export and the recorder (PENDING 10-17)**
- 10: the MP3 decoded to `media/<id>.f32` (16 706 665 frames at 48 kHz = 348.06 s, the file's
  5:48); peaks, waveform and Play as above. Opened by a share, not the picker (the system
  picker cannot be driven over adb). **Not heard.**
- 11: the 44.1 kHz MP3 was resampled to the 48 kHz session at decode, length exact (above).
  **Pitch not heard.**
- 12: a 2.09 s selection, Repair → Remove clicks: a new 2.095 s source in `media/`, the clip
  split in three with the middle on it; Undo put the single original clip back. The work
  finished inside 0.4 s, so the busy card was not caught. **Not heard.**
- 13: Export MP3 320 with a title and a chapter marker at 2.77 s: `Music/Stave/Stave first
  export.mp3`, MPEG-1 layer III 320 kbps 48 kHz stereo, ID3v2.4 with TIT2 and a CTOC/CHAP
  "Chapter 1" from 2 772 to 6 104 ms (read from the bytes); MediaStore reads the title. FLAC
  24-bit: 292 994 samples, exactly the source's frames. WAV 24-bit: **the first one was wrong**
  (192 frames late, three bursts doubled; the offline render raced the output callback, fixed
  with `renderLock`); the export after the fix equals the source sample for sample. The result
  card reads size, LUFS and true peak, with Share and Open. Not played in a music app.
- 14: the editor, Repair, Markers and Export sheets dump with 0 NAF once each control is fully
  on screen (a field cut by the sheet's edge dumps as NAF with no text; that is uiautomator).
  The Export sheet's Artist/Album fields were crushed by a brace slip; fixed.
- 15: Ready the mic: the permission dialog showed once; the input opened with
  `AUDIO_SOURCE_UNPROCESSED`; "Round trip about 92 ms"; the meter rose when the speaker played.
- 16: Record over the clip while it played: "Take 1" landed on a second lane of Track 1 at
  frame 181 824 (3.79 s; the recording began at about 3.84 s by the clock), the first clip
  untouched; `takes/` holds the WAV (8.9 s, float mono) and `media/` the `.f32` copy.
  **The two together not heard.**
- 17: force-stopped mid-take: the WAV's RIFF and data sizes read 0xFFFFFFFF. Nothing sweeps the
  orphaned take into the project yet (as the test says, not written).
- 86 (part): typing spaces in the Export sheet's Title field did not start the transport.

**The transcript (PENDING 18, English with the base model)**
- A 38-word English script read by the phone's own voice (Read aloud, 11.5 s mono) was
  transcribed word for word, punctuation included: 0 errors in 38 words. 9.5 s for the clip
  (one 30 s window) before DTW, 11.5 s with it; a minute of speech is therefore expected well
  under a minute (extrapolated, not timed).
- The word times were wrong at first: one-word segments laid end to end, so a sentence's last
  word ran through the pause after it ("dog." ended 340 ms after the speech, "phone." 310 ms)
  and tapping "lazy dog." selected mostly silence. Fixed in two parts: whisper.cpp's DTW times
  (found on the phone to mark a word's end, not its start) and `WordTiming` (a word's edges
  pulled to the pauses in the sound). After: "lazy dog." 1760-2490 ms against speech ending
  at 2420, "phone." ending 70 ms late instead of 310.
- Tapping "lazy" and "dog." selected 1.76-2.49 s; Delete 2 words cut 771 ms as one undo step
  ("Undo Delete words"), and every later word moved up by 770 ms. **Play on the two words not
  heard.** Hindi and Malayalam wait for the small model (not on the phone).

**Fillers, silences, clean-up (PENDING 19-21)**
- 19: Read aloud "So um, I think we should, uh, go home now. Um, yes." and Transcribe: whisper
  kept all three fillers. Take out every um and uh: three cuts, 1.35 s (4 940 → 3 590 ms), one
  undo step. **Undo restored the sound but not the transcript** (fillers gone, later words
  1.35 s early); fixed so the transcript moves with the history, then seen: Undo 4 940 ms with
  the fillers, Redo 3 590 ms without.
- 20: Remove silences at the default "longer than 800 ms" left a clip whose longest pause was
  590 ms alone (right); at 399 ms with 200 ms kept it shortened that pause by 260 ms as one
  step, and the later words moved up by exactly 260 ms. The message read "1 pauses"; counts
  are plurals now.
- 21: Clean up voice on a mono read-aloud clip ran as one step, but Finish → Measure then read
  the mix at -13.1 LUFS against a -16 target: a mono source was measured as mono and played
  on two channels (+3 LU). Fixed (`integratedAsPlayed`, LoudnessChecks); **the re-measure on
  the phone after the fix is still to do** (the phone dropped off the network mid-test).

**Playback quality, gestures and the icon (the owner's own test, evening)**
- The owner played songs and reported bad sound and that dragging made selections. Measured:
  the decoded MP3s are healthy (smooth, channels 0.99 correlated, nothing clipped); the cause
  was Play looping whatever was selected, and a drag selecting. Fixed: a drag scrolls (and
  seeks), hold-then-drag selects, Play hears a selection once.
- **Measured: live playback is bit-exact.** The debug capture of the output callback, 593 472
  frames (12.4 s of Kalimba from frame 0), equals the decoded source sample for sample (zero
  difference). The stream's underrun counter stayed at 0 through 20 s of playback.
- The launcher icon was replaced (ink waveform on Signal lime).

**The night sweep: every tab and sheet, the controls' look (1-2 November)**
- Every Edit sheet (Edit, Gain, Fades, Repair, Spectral, EQ, Dynamics, Time & pitch, Effects,
  Markers, Export) and every tab (Voice, Studio, Musician, Beats, Finish) opened without a
  crash once the plural formats were mended; uiautomator found no unnamed control except
  slivers cut by a panel's edge.
- The Studio tab **crashed** on opening ("%1 points", a format broken by the evening's plural
  change); fixed, and ResourceChecks now reads strings.xml as aapt2 and Formatter do.
- Choices and actions are even grids now (the owner: "stones of different sizes thrown on the
  ground"); sliders are a thin track and a round thumb; the drum pads were thin pills floating
  in their rows and now fill 64 dp cells; the step grid's pill clip hid its corner steps.
- 23 (changed by design): a hold, not a plain drag, picks up a clip, and only on the Studio tab;
  on the other tabs a hold-then-drag selects.
- 37: kick on steps 1, 5 and 9, Play loop: the button became Stop, the play-head column moved
  across the grid, both audio outputs were open; Stop stopped it. **Timing and the loop point
  not heard.**
- 45 (part): the Beats tab's controls are named; the grid is one node described as
  "Pattern A: 7 notes over 16 steps…".

**Speed, on the release build (perf), 2 November** (STEP 6)
- Scrub, 12 drags across the strip: UI thread median 5.0 ms, p90 6.3 ms a frame (gate: under
  8 ms); RenderThread median 10.2 ms; 3.1% janky frames (was 6.3% before the waveform's lines
  were batched).
- At rest: 0 frames rendered in 10 s (gate: 0).
- Play: 377 frames in 5 s, 0.27% janky, after the play-head followed the frame clock and the
  display was asked for 60 Hz while playing (before: 32 frames a second, 99% late).
- Graph CPU at 24 tracks (one song on each): the audio thread 14-20% of a core (gate: under
  30%), after a fast path for unstretched clips (it was 35%); playing them, 0.6% janky frames.
- Master to Streaming on a 5:48 song: 47-62 s; Measure about 40 s. Both ran out of memory
  before the whole-song tools were made to stream.
- 28: Master to Streaming music: -14.4 LUFS, -1.0 dBTP (target -14, -1); true peak had been
  -0.5 dBTP before the limiter's margin and the true-peak trim.

**Studio, Musician, Library (2 November)**
- 27: Bounce to a new track on the 5:48 song took 9 s; the bounce is byte-identical to the
  source over the 10 s compared (one track at unity: the null test, exact).
- 48: Freeze took 9 s and named itself ("Bounce 1 is frozen. Undo brings its clips and
  inserts back."). **The sound before and after not compared by ear.**
- 33: Read the song on the 5:48 song: 12 s, 119.0 bpm, E♭ minor, chords listed. **Not
  checked against the record's known tempo and key.**
- 32: Library lists every export newest first with Share and Delete (Delete had been drawn as
  a plus). Convert files opens; picking files needs the system picker (not drivable).
- Undo now survives closing the app (history.json).

**The release builds (2 November)**
- 5: the merged release manifests (fullRelease, playRelease) declare no INTERNET, no phone
  state and no storage permission.
- 63 (part): `bundlePlayRelease` builds; the bundle's `models` asset pack holds the Whisper
  model (59.7 MB) and the full APK carries it in its own assets. bundletool not run here.
- Both release builds (R8-minified) built; the minified perf build ran every tab without a
  missing class. **Not signed yet: the owner's keystore is needed.**

**Every Edit tool on a selection (2 November)**
- Fades, Remove clicks, Repair clipping, EQ, Dynamics and Effects (Delay) each rendered and
  made one undo step in about 5 s, with no crash; Undo took each back. Gain and Time & pitch at
  their defaults change nothing and make no step.
- Time & pitch through Rubber Band (never run before): +12.6 semitones on a 440 Hz tone gave
  913 Hz (expected 911); on speech the level held (-18.5 -> -18.7 dBFS RMS). On a pure tone
  the level falls by 36 dB, which is the voice-character (formant) option doing its job.
- 86 (part): Ctrl+A from the keyboard selected the whole 8 s session.
- 34, 64: the tuners were fed a 440 Hz tone through the phone's own speaker, but the media
  volume was at -39 dB and only 2% of what the mic caught was the tone; both read
  "Listening…". **Needs a sung or played note.**

**Sweeps: Studio, Musician, Beats actions; Arabic (2 November)**
- Studio (Add track, Add bus, an insert, Loop the selection, Export each track), Musician
  (Read the song, Trace the selection, Make a practice copy, Notes to MIDI: "10 notes heard"),
  Beats (instruments Pluck, Morph, Choir, Drums; Play loop and Stop; Humanise, Quantise,
  Legato, transpose; Add to song; Render the song to tracks): no crash, each edit one undo step.
  Stems: "Separate" with no model says so rather than failing.
- The app in Arabic (per-app locale ar-EG): every tab, the Edit sheets and Settings opened with
  no crash; the clock reads in Arabic digits as designed. The interface strings are English
  only (no translations yet), so the layout stays left to right.

## 2 November 2026 — "grainy next to Poweramp"

- The owner played a 320 kbps MP3 in Stave and in Poweramp; Stave's sounded grainy. The project
  opened from the list was not playing the song: its clip pointed at a "Remove clicks" render
  made by the automated button sweep on 1 November. The old click detector (second difference
  against a 10 ms mean) had flattened about 7 real attacks a second, by up to 0.70 of full
  scale. The project was put back on the decoded song; the detector was rebuilt on linear
  prediction with an isolation and an onset test (`RepairChecks`: a band with abrupt attacks is
  untouched, clicks in it are still found; on this song, 3 and 2 mends of 5 samples in 115 s,
  the whole changed by -68.6 and -62.6 dB, where the old one made about 800).
- Measured after: 15 s played from the start, captured at the output callback, byte-identical
  to the decoded MP3 (737,664 frames, `cmp`). The decoded song peaks at 0.957, nothing over
  full scale, so nothing clips on the way out.

## 2 November 2026, evening — the grain was the output path

The owner still heard the song grainy beside Poweramp after the Remove-clicks render was taken
out. Measured on the phone, in order:

- **The samples are right.** The debug capture of a play matched the decoded source bit for bit
  (398,400 frames), a pause faded linearly over one 192-frame buffer, and a jump while playing
  dipped over 192 frames out and 192 in, then matched the source again 50.58 s on. The steepest
  step across the jump was 0.13 against the song's own 0.33.
- **No underruns.** 60 s of play logged none.
- **The path was the difference.** Every play opened a low-latency, exclusive-first stream, which
  landed as a FAST track on the primary output. `dumpsys media.audio_flinger` with the normal
  path shows what a music player gets and a fast track does not: two effects on the app's own
  session, "Mot Speaker Helper: Music" (Motorola) and "Music Listener" (Dolby), the speaker
  tuning every music app on this phone is heard through. The primary output itself is 16-bit,
  as it is for every app.

Since then a plain play opens `PerformanceMode::None`, shared (20 ms bursts, a 60 ms buffer),
and only an open mic switches to low latency; the play-head is drawn the output latency behind
the rendered position, so it shows what is heard. Built and seen on the path; the owner's ear
on the speaker is PENDING_TESTS 99.

Also seen this evening: names tidied at startup (the list read "Ramayana_ ... (MP3_320K)" until
the project was opened); Read the song left the play-head at the song's end (the offline render
was polled as if playing); Measure on a 24-track six-minute project starved the phone of memory
for 15 s (the render now streams to its file and Measure reads it in blocks; 13.2 LUFS, 26.6
dBTP both ways); Home's Record opened on Edit; at the largest font "Match phone" shrank and a
track count ran under the menu button. Release-build cold start: 389, 490, 422 ms. Home at rest
draws 87 frames in 10 s (the backdrop's 10 steps a second); scrolling Home, 5% slow frames.

## 3 November 2026, after midnight — stems on the phone for the first time

The four Open-Unmix models were made by `.github/workflows/models.yml` (int8 weights for drums,
bass and other, 13.7 MB each, within 2-3.5 % of PyTorch through LiteRT; vocals stays float,
40 MB, because int8 strayed 11 %) and run on the moto over Kalimba (5:48):

- **Separate works.** Vocals, drums, bass and other landed on their own tracks with the
  original muted, in 6 min 14 s.
- **The stems summed to the mix only to -25 dB**, against -141.7 on the JVM: every model's last
  layer is a ReLU and whole bins came back zero from all four, so the Wiener shares were 0/0 and
  the bin was dropped. Shared evenly since: -70.3 dB, the rest being the 44.1 kHz round trip.
- **Memory.** Four interpreters open at once: 3.2 GB of native heap (3.8 GB in all). One at a
  time with the estimates spooled to disk: 2.3 GB at the float vocals pass, about 0.5 GB for the
  int8 ones. XNNPACK off: 57 MB, the same 6 minutes, but three stems came back NaN, so it stays
  on. XNNPACK packs the weights again for each unrolled LSTM step, so the models are converted
  at 128 frames instead of 256 (PENDING_TESTS 101 measures the result). A model's NaN is now
  silence in its stem, not NaN in all four.
