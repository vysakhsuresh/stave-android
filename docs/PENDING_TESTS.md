# Pending device tests

Built, suites passing, **not seen on a phone**. In the order to run them when the laptop has
the moto attached. Move each line to `DEVICE_FINDINGS.md` with the date once it is seen, or
back into the roadmap with what broke.

## P0 — the empty app
1. `./gradlew assembleFullDebug` on the laptop with JDK 21 compiles; `lintFullDebug` has zero errors.
2. Install; the app opens to Home in the dark theme with Space Grotesk; the status and navigation bars match the background.
3. Settings → theme Light / Dark / Match phone recolours every screen without a restart.
4. `uiautomator dump` on Home, Guide, Contact, About and Settings: zero NAF nodes.
5. `python3 tools/verify_manifest.py` after the build (in CI) reports no network permission in debug and release.
6. Share an MP3 from a file manager to Stave: the app receives it (P2 opens it).

## P1 — engine on the phone
7. `./gradlew :engine:test` on the laptop runs the same seventeen suites as `tools/jvm/run.sh` and passes.

## P1b — the native half, never compiled in the sandbox
8. The CMake build (`libstave.so`): Oboe found through prefab; Rubber Band's tarball fetched (pin its SHA256 in CMakeLists.txt from the first download); TinySoundFont compiles with `TSF_IMPLEMENTATION` in one file. Expect the first build to name a few C++ slips - fix them there, they are not visible from here.
9. `native.yml` and `whisper.yml` on GitHub: libstaveav.so and libstavewhisper.so for four ABIs; `sh tools/native/fetch.sh` and `fetch_whisper.sh` put them under jniLibs; commit them. staveav.c is written against FFmpeg 7.1+ (`avcodec_get_supported_config`); the configure line's long demuxer and decoder lists may name a component 8.1.3 spells differently - read `config.log`'s tail.
10. Open an MP3 from the picker: it decodes to `media/<id>.f32`, the peaks build, the waveform draws, Play plays it through the graph with the fixed play-head moving, and the clock agrees with the sound.
11. Play a 44.1 kHz file in a 48 kHz session: pitch and length right (the rate ratio is folded into the clip's stretch).
12. Select a stretch, Repair → Remove clicks: the busy card shows, the clip repoints to a new source, Undo brings the old one back, the sound follows both.
13. Export to MP3 320 with a title and a chapter marker: the file lands in Music/Stave, plays in a music app, shows the chapter where one is read; FLAC and WAV 24-bit the same.
14. `uiautomator dump` on the editor: zero NAF (every chip and button has a name; the strip has a description).

## P3 — the Voice tab
15. Ready the mic: the permission dialog shows once; the meter moves with the room; "Round trip about N ms" reads something between 20 and 120 ms on the moto.
16. Record for ten seconds while the strip plays an earlier take: Stop lands "Take 2" on a second lane of the same track at the play-head's start; the first take is untouched; the two play together. `files/projects/<id>/takes/` holds the WAV; `media/` the .f32 copy.
17. Kill the app (`am kill`) mid-recording: on reopening, the take's WAV has a 0xFFFFFFFF size in its header; the next Stop in that project is not what repairs it - a take left in `takes/` with no source is the thing to add a sweep for (not yet written).
18. Transcribe a minute of English with the base model: the words appear in under a minute on the moto; tap two words and Play plays just those; Delete removes the stretch and the words after move up. The same with Hindi and Malayalam in their own scripts (the small model, imported through Settings - the import screen is not yet built; push the file by `run-as` into `files/models/`).
19. "Take out every um and uh" on a take with three ums: three cuts, one Undo.
20. Remove silences with the defaults on a take with two long pauses: the pauses shrink to the kept margin; the transcript's later words still sit under their sounds.
21. Clean up voice on a noisy phone recording: the hiss drops, the S sounds soften, the level lands at -16 LUFS (read the Export sheet's measurement after an export).

## P2b/P4/P8 — spectral, studio, finish
22. Spectral sheet → Show the spectrogram: the selected lane shows the picture within half a second; a box dragged over a whistle, Remove: the whistle goes and the words under it stay (listen, then look at the picture again).
23. Long-press a clip, drag it right and down to the next track: it follows the finger and lands snapped to the beat grid; one Undo puts it back.
24. Studio: a fader dragged while playing is heard at once and leaves one undo step per drag; mute, solo and arm read their state to TalkBack; an EQ insert's band slider changes the sound live.
25. Add a bus, send the voice to it at -9 dB with a limiter on the bus: the bus meter moves; export plays the same as the preview.
26. Two tracks (voice and music), Duck under speech on the music: the music's volume automation dips where the voice speaks (the points count shows), and the mix plays the dip.
27. Bounce to a new track makes a stereo clip equal to the mix (null test: invert the bounce, mute nothing, the master meter should read near silence - the inversion tool is on the Gain sheet as "invert" when added; until then compare by ear).
28. Finish: Measure reads a sensible LUFS; Master to Streaming brings a -23 LUFS mix to -14 within 0.5 LU and a true peak at or under -1 dBTP (read Measure again).
