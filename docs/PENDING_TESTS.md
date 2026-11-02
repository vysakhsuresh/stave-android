# Pending device tests

Built, suites passing, **not seen on a phone**. In the order to run them when the laptop has
the moto attached. Move each line to `DEVICE_FINDINGS.md` with the date once it is seen, or
back into the roadmap with what broke.

## P0 — the empty app

## P1 — engine on the phone

## P1b — the native half, never compiled in the sandbox
10. (Seen 1 Nov 2026 but for the ear) Play an MP3 and listen: the clock agrees with the sound.
11. (Length seen right 1 Nov 2026) Play a 44.1 kHz file in a 48 kHz session and listen: the pitch is right.

## P3 — the Voice tab
18. (English seen 1 Nov 2026) Listen to Play on two tapped words: just those two are heard. Hindi and Malayalam in their own scripts with the small model (imported through Settings, or pushed by `run-as` into `files/models/`).
21. Clean up voice on a noisy phone recording: the hiss drops, the S sounds soften (listen), and Finish → Measure reads -16 LUFS within 0.5 LU (the mono fix of 1 Nov 2026 not yet re-measured).

## P2b/P4/P8 — spectral, studio, finish
22. Spectral sheet → Show the spectrogram: the selected lane shows the picture within half a second; a box dragged over a whistle, Remove: the whistle goes and the words under it stay (listen, then look at the picture again).
23. Long-press a clip, drag it right and down to the next track: it follows the finger and lands snapped to the beat grid; one Undo puts it back.
24. Studio: a fader dragged while playing is heard at once and leaves one undo step per drag; mute, solo and arm read their state to TalkBack; an EQ insert's band slider changes the sound live.
25. Add a bus, send the voice to it at -9 dB with a limiter on the bus: the bus meter moves; export plays the same as the preview.
26. Two tracks (voice and music), Duck under speech on the music: the music's volume automation dips where the voice speaks (the points count shows), and the mix plays the dip.
28. Finish: Measure reads a sensible LUFS; Master to Streaming brings a -23 LUFS mix to -14 within 0.5 LU and a true peak at or under -1 dBTP (read Measure again).

## P7 — tune and effects
29. A flat sung line, Tune to C major at full strength, 0 ms: the notes snap (robotic on purpose); at 60%, 120 ms the line is quietly in tune. Listen for grain noise on long vowels - PSOLA's known artefact; if it is heard, the grain window in `PitchCorrect.psola` is the thing to widen.
30. Each of the twelve effect presets on a spoken line: none clips (the Export sheet's true peak), each sounds like its name.
31. Settings → Add a model file with ggml-small-q5_1.bin from Downloads: "Better speech model added" shows and Transcribe uses it (slower, better Hindi).

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
79. Voice → Count in one bar on, Record: four clicks at the session's tempo, then the take starts with the play-head where it was; Stop during the clicks records nothing.
80. Voice → Show notes → Copy: the clipboard holds the chapters with times and first words and the transcript; Share offers the text to another app.
81. Beats → on a synth, Chord stamp Major with a Strum: a tap lays three notes a few ticks apart (zoom the grid); on Drums the stamp is ignored; the groove chips move the swing slider.
82. Musician → select a sung phrase, Trace the selection: the line follows the voice, sits on the semitone lines when in tune, and breaks between phrases rather than gliding.
83. Export → Podcast MP3 preset: the chips jump to MP3 128 and the podcast target; track 4, year and genre typed are in the file's tags (read them back in a tag editor).
84. Voice → Punch with Pre-roll a bar: Record plays the bar before the selection, the take lands at the selection's start (the bar is not in the clip), and stops at its end.
85. Edit → the Undo history lists the steps newest first; tapping the third goes back three steps and Redo returns them one at a time.
86. With a Bluetooth keyboard: Space plays and pauses, Delete cuts the selection, Ctrl+Z and Ctrl+Y undo and redo, Ctrl+A selects all, Home and End jump; typing in a name field does not trigger any of them.


## 2 November 2026 — built while the phone was away
87. Export the 6-minute "24 tracks" project to WAV 24-bit with no target, then to MP3 at
    Streaming: no out-of-memory, the WAV matches the mix (od/awk diff against a captured mix),
    the MP3 reads -14 LUFS and at most -1 dBTP in Finish → Measure. (ExportChain, streamed.)
88. Export a project at 44.1 kHz (Export → rate 44.1k): plays at the right pitch and length.
89. Open the Ramayana project: the title reads "Ramayana Ravana Tandavam (Song) - Ranbir Yash -
    …" without "(MP3_320K)"; Undo still works after reopening (the history was rewritten with it).
90. Remove clicks on a song with no clicks: the clip label gains "· Remove clicks" and the
    sound is unchanged by ear; on a vinyl rip the ticks go.
91. Record, then leave without recording anything; restart the app: no empty "Untitled" row.
92. Home at rest with Lively on, release build: `dumpsys gfxinfo com.layerbit.stave` over
    10 s reads about 100 frames (the backdrop steps at 10 a second), not 600.
93. Master a loud song to Streaming and listen to the loudest chorus against the original:
    no added crackle on drum hits (the limiter now ramps its gain). A Limiter insert on the
    master, live, the same (the C++ twin has never been heard).
94. EQ: a +6 dB band at 12 kHz on cymbals sounds like a lift of the air, not a narrow
    whistle; the live insert and the exported file sound alike (both use the matched band).
95. Play and pause a loud song ten times in its chorus, on headphones: no pop at either end.
    Mute and unmute the drums in "Night drive" while it plays: no click. Tap far along the
    ruler while playing: the jump is a quick dip, not a crack. The debug capture after a play
    from the start matches the decoded source from frame 256 on (the ramp before it).
96. Cold start on the release build, before and after this build (`am start -W` three times
    after `am force-stop`, read TotalTime): the app's own baseline profile should take
    noticeable time off the first launch and the first scroll of Home.
