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

## P7 — tune and effects
29. A flat sung line, Tune to C major at full strength, 0 ms: the notes snap (robotic on purpose); at 60%, 120 ms the line is quietly in tune. Listen for grain noise on long vowels - PSOLA's known artefact; if it is heard, the grain window in `PitchCorrect.psola` is the thing to widen.
30. Each of the twelve effect presets on a spoken line: none clips (the Export sheet's true peak), each sounds like its name.
31. Settings → Add a model file with ggml-small-q5_1.bin from Downloads: "Better speech model added" shows and Transcribe uses it (slower, better Hindi).
32. Library: every export of every project listed newest first; Share hands the file to another app; Delete removes the private copy and leaves Music/Stave's.

## P5 — the Musician tab
33. Open a song with drums, Musician → Read the song: the tempo lands within a BPM of the known one, the beats sit on the hits, the key reads right for a song you know, the chords change where the song changes; ½ tempo and 2× tempo move the figure and the beat list together; Mark every bar puts a marker on every downbeat and "Bar starts a beat later" moves them a beat; the click follows the song's beats when the toggle is on.
34. The tuner: play an A and it reads A4 within 2 cents; the needle settles rather than jitters; nothing while the input is not armed.
35. Stems with a UMX model under `files/models/`: the four stems land on four tracks, the originals muted, and the mix of the four sounds like the song; the progress climbs; memory does not blow on a four-minute song (watch `dumpsys meminfo`).
36. Select a sung phrase, Notes to MIDI: the Beats tab opens with a banner; Make a pattern of it gives a Melody pattern on Lead whose notes are the sung ones to a semitone.

## P6 — the Beats tab
37. New pattern, tap kick on steps 1, 5, 9, 13 and hat on every step, Play loop: it plays in time at the session's tempo with the play-head column moving; the loop keeps time for a minute against the metronome of another app; nothing clicks at the loop point.
38. A step tapped while the loop plays is heard on the next pass; a drag up on a note raises its velocity (louder on the next pass) and Undo takes the drag back as one step.
39. Record the pads on, hit pads while the loop plays: each hit is written to the nearest step and plays from the next bar; a hit between steps lands on the nearer one.
40. Switch the instrument to Pluck: the grid becomes the piano roll; the keys play at once on press and stop on release; the octave buttons move the roll and the keys together.
41. Swing at 60% delays every other 16th audibly; Quantise after Humanise puts the notes back; Legato joins a line; Arpeggio over a chord entered on the grid runs up it at the grid's rate.
42. Render to a track ×4: a four-bar clip lands on a new MIDI track at the play-head and plays the same as the loop did; the file's first hit is on its first sample (zoom in).
43. Add two patterns to the song, ×2 and ×1, Render the song: one track per instrument, the second pattern starting where the first's repeats end; Export song writes a .mid that another app opens with both at the right bar.
44. Import a MIDI file with drums on channel 10 and a piano track: two patterns, the first on Drums, the second on Keys, at the file's tempo.
45. Add a SoundFont (.sf2) from Downloads: its name becomes a chip, the preset arrows step through the bank's names, a key plays the preset; Remove takes it off and the pattern falls back to the kit with the message. Then `uiautomator dump` the tab: zero NAF - the grid is one named node, every pad and key a named button.
46. Play loop while the main transport plays: the transport pauses (two outputs would drift); the AudioTrack output and the graph's are at the same level roughly (Pluck at velocity 100 against a -12 dBFS clip).

## P3/P4/P5/P7 — read aloud, freeze, sing along, the looper
47. Voice → Read aloud: type a sentence, Read it: the phone's engine speaks it into a clip at the play-head on a free lane, at the session's rate (play it: the pitch is right, not a semitone off from a rate mismatch); with the language chip on Hindi the Hindi voice is used, or the message names the missing voice.
48. Studio → a track with an EQ insert → Freeze: a single clip the track's length replaces its clips, the inserts show off, the mix sounds the same before and after (null test by inverting a bounce of each); Undo brings the clips and the inserts back.
49. Musician → Sing along: with a transcript, the words move with the play-head in lines of seven, the sung word lit; with stems separated, Mute the vocals silences the Vocals track and the switch reads its state.
50. Voice → Looper: set 2 bars at the play-head (the selection and the transport loop follow), Record a layer, play two passes and a bit, Stop: two layers land on the Loop track on their own lanes starting at the loop's start, the late bit discarded, and the loop keeps running; a second Record adds layers on new lanes; Undo takes the last take's layers back as one step.

## P8 — the Finish tab's additions
51. Measure: the report's correlation reads near 1 on a mono file and lower on a wide mix; the balance reads centred on a centred mix; the sentence under it matches what you hear.
52. Match a reference: pick a well-mastered track from the phone; the bands listed are a handful of dB at most; Apply the match and Measure again - the lows/mids/highs figures move toward the reference's; the project's media folder holds no copy of the reference afterwards.
53. Width at mono then Apply: the report's correlation reads 1.00; Width 150% on a stereo mix widens it audibly without the centre moving.
54. Three bands at Lows 60%: a boomy bass note is held while the vocal above it keeps its level; Transients Attack +60% on a drum loop sharpens the hits; Sustain -60% shortens the room.
55. Master, then Compare with before: the switch plays the previous mix at the same loudness (the meter in another app or the master meter agrees within 1 dB) and the toggle's state is spoken by TalkBack.

## The second pass - punch, takes, MIDI, kits, lyrics, graphic EQ, the asset pack
56. Voice → select two seconds in the middle of a take, Punch in and out on, Record: the take starts at the selection's start, the recorder stops itself at its end, the new clip sits exactly over the selection on a new lane; with the toggle off the take runs until Stop.
57. Studio → a track with two lanes: Takes chips appear; Take 2 mutes lane 1's clips (the strip draws them dim) and the mix plays lane 2 alone; Hear all brings both back; Undo steps the choice back.
58. Beats → MIDI keyboard on, plug a USB MIDI keyboard (or pair a Bluetooth one): its name shows under the switch; keys play the instrument with the pads' latency; with Record on, notes land on the grid; unplugging it clears the name without a crash.
59. Beats → select a drum loop on the strip, Slice the selection to pads: the slices land on the pads in order, named Slice 1…, a new kit chip appears and the pattern plays it; tap pad 1 and hear the first hit; the project reopens with the kit.
60. Beats → Load a file onto pad 5 from Downloads: the pad plays it; Clear pad 5 silences it; Undo brings it back.
61. Voice → with a transcript, paste the song's lyrics a line each, Lay them against the sound: "N of M lines found"; Musician → Sing along shows the written lines moving with the play-head, the current one large.
62. Edit → EQ → 10 bands: the faders are vertical, each named by its frequency and reading its gain to TalkBack; +6 at 1 kHz draws a bump on the curve and Apply renders it (Measure before and after on Finish shows the mids up); 31 bands the same, narrower.
63. `./gradlew bundlePlayRelease` builds with the `models` asset pack present (`sh tools/models/fetch_models.sh play` first), and `bundletool` lists the pack as install-time; the play flavour's ModelStore finds the model through assets/models/.

## Live tune, drawn automation, effects, convert
64. Voice → Ready the mic, headphones on, Tune live on with C major: sing a slow scale - each note is pulled to the scale within the retune time and the readout shows "Hearing X → Y"; a note bent across a boundary does not trill (the hold); strength 50% pulls half way; retune 0 ms snaps. Record a take with "Record the tuned voice" off and the clip is dry; on, it is tuned. Then the null test the C++ owes the Kotlin: Tune the dry take offline with the same scale and compare by ear with the tuned take - they should sit on the same notes (the shifter's timbre will differ).
65. The live tune's latency: tap the mic once with tune on and off and read the two echoes in a recording of the monitor output (or `inputLatencyMs` plus 11 ms); under 30 ms total is what headphones tolerate.
66. Studio → Automation → Draw on the strip: Volume; drag a finger across the selected track's lane: a teal curve follows it, the stroke's corners become points (the count under Automation), the lane outside the stroke is untouched; the mix follows the curve when played; one Undo takes the whole stroke back. Pan the same. With Write on, move a fader while playing: points land at the play-head, the fader itself does not change.
67. Edit → Effects: each of the eight on a spoken line sounds like its name and none clips; Stereo → Swap L/R on a file with speech on the left puts it on the right; Balance +6 dB leans right.
68. Voice → Record, Pause after five seconds, wait, Resume, Stop: one take whose length is the recorded time without the pause, with no click at the join.
69. Home → Convert files: pick six files of mixed formats, MP3 192: six files land in Music/Stave with their names, the list says 6 of 6, and the cache holds no scratch folder afterwards (`run-as … ls cache`).
70. Long-press the home screen → widgets → Stave → Record: the pill lands; a tap opens the app on the Voice tab with the mic armed (the same route as the launcher shortcut); TalkBack reads "Start a recording in Stave".

## Comping, the wavetable, chords, the analyser, regions, the device chooser
71. A track with two takes: the strip draws them stacked in the row; Studio → Takes → Comp by swipe on; swipe across the second lane for a bar: that bar is heard from take 2 and the rest from both, the clips split at the bar's edges (count them), one Undo puts it back.
72. Beats → instrument Morph, play a held note: the colour sweeps on the attack; Glass rings; Choir swells; at A6 nothing buzzes (the mip level) - compare a saw at A2 and A6 by ear for aliasing.
73. Musician → after Read the song, the chord under the play-head shows as a guitar shape with × and o over the strings; a C major shows x32010.
74. Edit → EQ: a faint spectrum sits behind the curve and changes when the selection moves; on a hiss it slopes, on a voice it peaks around 200 Hz to 3 kHz.
75. Markers → Regions: From selection with a name lists it; Select puts the selection and the play-head back on it; Delete removes it; the project reopens with it.
76. Studio → Metronome: Tempo change here at bar 9 with 140: the entry lists; the beat grid after bar 9 is tighter (snap a clip to it); Delete on the entry restores one tempo.
77. Settings → Audio → Input: a USB interface plugged in lists by its name; chosen, Ready the mic records through it (the meter moves with its input, not the phone's mic); unplugged and chosen again, the phone's mic opens instead of nothing.
78. Voice → Keep only the voice on a sung line over a backing track with the model present: the backing is gone from the selection and the voice stays in place and in time.

