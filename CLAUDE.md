# Working on Stave

Read this first. Every session starts with no memory of the last one, and most of this
codebase was written from a cloud sandbox by a session that cannot be asked anything now.

Stave is an Android audio studio in the Layerbit family (Squish, Still, Sheaf, LayerLink).
Every tool is free, there is no account, no cap, no ad, no subscription, and the manifest does
not declare INTERNET. The competitors and the plan are in `docs/STAVE_BRIEF.md`; the work
order is `docs/ROADMAP.md`, worked from its first unchecked item.

## Where you are decides what "verified" is allowed to mean

**The cloud sandbox (claude.ai/code).** No Android SDK, no emulator, no device. The standalone
Kotlin compiler is fetched by `tools/jvm/run.sh`, which compiles `:engine` with no Android
class on the path and executes every suite. That is the honest ceiling here: "the arithmetic is
executed on the JVM and the app compiles structurally". The app's Compose code cannot be
compiled here; `tools/check_*.py` find the real mistakes in a kotlinc log full of unresolved
Compose references, if a kotlinc log exists, and otherwise `check_nesting.py`,
`check_modifier_imports.py` and `check_dependencies.py` run on the source alone.

**The laptop (Windows, Git Bash).** `./gradlew assembleFullDebug` is a real compile;
`./gradlew :engine:test` runs the same suites through JUnit; a phone can be driven. Build with
JDK 21 (`JAVA_HOME=$HOME/.jdks/jbr-21.0.11`), never Android Studio's bundled JBR 25; set
`ANDROID_HOME=C:\Users\vysak\AppData\Local\Android\Sdk` in a worktree; Python is not installed,
so the `.py` checkers run only in the sandbox; never touch a source file while a Gradle build is
running; prefix `adb` with `MSYS_NO_PATHCONV=1`. Everything Squish's CLAUDE.md says about the
phone (the moto g84, serial ZY32J8HF2S) holds here.

**Never write "verified" for something this session could not observe.** Say which kind of
confidence you have, every time. `docs/PENDING_TESTS.md` is the list of what is built and not
seen, in the order to run it when a phone is attached; `docs/DEVICE_FINDINGS.md` is what was
seen, dated.

## Verifying, in order of what it costs

```
sh tools/jvm/run.sh                      # every engine suite, ~5 s; `run.sh fft eq` for some
python3 tools/check_nesting.py           # a declaration swallowed by a stray brace
python3 tools/check_modifier_imports.py  # a Modifier extension used unimported
python3 tools/check_dependencies.py      # a library imported but never declared
# with a kotlinc log of the app (sandbox):
python3 tools/check_unresolved.py log ; python3 tools/check_calls.py log
# on the laptop, and this is the one that settles things:
./gradlew :engine:test assembleFullDebug lintFullDebug
python3 tools/verify_manifest.py         # (CI) the merged manifests carry no network permission
```

Every pure rule gets a suite in `tools/jvm` (an `object XChecks : Suite`, named in
`Registry.kt`; `RunnerChecks` fails on a file no runner names). Every suite must have been seen
failing: copy the engine file to the scratchpad, put the fault back, point `STAVE_ENGINE_SRC`
at the copy, run the one suite, read the failure, restore. A check nobody has seen fail is a
check nobody has tested. When a mistake escapes, write the check that would have caught it
before writing the fix.

## The shape

- `engine/` is pure Kotlin: DSP (`dsp/`), the waveform pyramid and the time window (`wave/`),
  the project model and history (`project/`), transcript rules (`transcript/`), music analysis
  (`music/`), the sequencer and its renderer (`sequencer/`), the synths and the drum
  machine (`synth/`), export arithmetic (`export/`). Nothing in it
  imports `android.*`; `run.sh` and `RunnerChecks` both refuse it.
- `app/src/main/cpp/` is the realtime half: the Oboe render graph (`graph/`), libstaveav's JNI
  (`av/`, FFmpeg built in CI into `jniLibs`), whisper.cpp's JNI (`whisper/`), Rubber Band and
  TinySoundFont. `ARCHITECTURE.md` draws it.
- `app/src/main/java/com/layerbit/stave/` is Compose and the doors: `ui/`, `audio/` (the
  service, the device list), `files/`, `export/`, `models/` (the asset-pack and imported models),
  `beats/` (instruments by id, SoundFonts, the pattern player on its own AudioTrack),
  `stems/` (the LiteRT runner), `voice/` (Whisper).

## Conventions worth not rediscovering (carried from Squish, each one cost a day there)

- **`.format()` takes the phone's locale; a localised string is never cut up by an ASCII
  literal.** `Timecode.display` is for the screen, `Timecode.srt`/`vtt` for files
  (`Locale.ROOT`). `TimecodeChecks` executes both under `ar-EG`.
- **Count code points, not chars; three letters is a Latin rule.** A whole name in Malayalam or
  Chinese can be two characters.
- **A thing you choose says it is chosen.** `selectable`/`toggleable`, never `clickable`, where a
  tint is the only mark. A control whose only content is a colour or a glyph needs a name.
  Zero NAF nodes in `uiautomator dump` on every screen is a release gate.
- **A result that lands in the background goes in with `recordLate`**, and a one-shot command
  carries no gesture id (`project/History.kt` says why).
- **Compose compares an unstable parameter by identity.** `app/compose_stability.conf` declares
  the read-only types stable; never mutate a list you have passed down.
- **The play-head is a fixed line at `TimeWindow.PLAYHEAD_FRACTION` (0.5) and the film moves.**
  A drag subtracts its travel. Do not reintroduce a walking play-head near the start.
- **LiteRT 1.4.0, never TensorFlow Lite 2.16.1.** The old runtime ran converted models wrong
  without refusing them.
- **A native handle can be negative.** arm64 tags heap pointers in the top byte; `AvHandles.failed`
  is the only test for a failed open. "<= 0" threw every open away on the phone.
- **Every float into a project file goes through `putFinite`.** JSON has no NaN; one non-finite
  number took every save with it, silently.
- **Comments say why, not what.** Where a line looks odd, the comment explains the bug that made
  it that way. Those comments are the only record of a lot of hard-won detail.
- **No Media3 player in the engine.** A studio mixes every track in one callback; Media3 plays
  one stream. The graph is C++ on Oboe.

## Things this session learned (31 October), for the next stranger

- **Negative tests run on a copy.** `cp -r engine/src/main/kotlin <scratch>/src`, `sed` the
  fault in, `STAVE_ENGINE_SRC=<scratch>/src STAVE_JVM_OUT=<scratch>/out sh tools/jvm/run.sh
  <suite>`, read the failure. Several can run at once in the background with their own `OUT`.
  Three checks written that day did not bite on the first try (a fade check every sound met
  anyway, a transient check on a fixture with no decay, a crossover alignment the suite's own
  crossovers were too far apart to need): the negative test is what found each.
- **A suite another session left failing is a question, not a nuisance.** Four were, when the
  agents writing them hit a limit. Each turned out to be either the check reading the wrong
  thing (Hann leakage taken for distortion; an echo's peak where its area was the quantity) or
  the code wrong in a way the check had rightly caught (the onset floor, the downbeat on a
  snare). Loosening a check to green is the one move that is never right.
- **The Beats tab plays through its own AudioTrack**, not the graph (`beats/PatternPlayer`), so
  starting its loop pauses the transport. `BUILD_NOTES.md` says why and what the first phone
  build must settle.
- **The log compression that serves a beat tracker erases accents.** Two kicks at 0.9 and 0.7
  read 6.58 and 6.69 in log flux; the bar is found on linear bass magnitude instead
  (`BeatDetector.fluxEnvelopes`).
- **Compose scope members read as unresolved** in the sandbox's kotlinc log (`drawRoundRect`,
  `tryAwaitRelease`, `viewConfiguration`): they belong in `tools/unresolved_baseline.txt`, one
  name a line, once the call is known good.
- **The app's structural compile** is `java -cp <compiler jars> K2JVMCompiler -cp
  <stdlib>:<engine classes> -d <out> $(find app/src/main/java -name '*.kt') > log`, then the
  four checkers over the log. Everything but MainActivity and StaveApplication's Android
  overrides compiles; those two are the known cascade.

- **`graph/LiveTune.cpp` mirrors `engine/dsp/LiveTuneRules.kt` line for line** (the target
  with its hold, the ratio by strength, the one-pole by retune time). The Kotlin is executed by
  `LiveTuneChecks`; the C++ is not executable here. Change one and change the other, and the
  phone compares them by ear (PENDING_TESTS 64). The C++ has never been compiled.
- **A stroke on the strip replaces its span on the lane as it was when the stroke began**
  (`strokeBase` in the view model), not on its own last result, so every pointer event is one
  idempotent replacement and the gesture folds into one undo step.

- **A check must ask the code, not work the answer out for itself.** The wavetable's mip check
  first computed which bank a note *should* read from the same arithmetic the voice uses and
  passed against a voice that always read the full bank; it now asks `bankMaxHarmonic` after
  `noteOn`. The same shape caught the comping split: the check counts the pieces.
- **The Beats grid draws take lanes stacked** (`laneCountOf`, `takeLaneAt` in `Waveform.kt`), so
  anything that reads a clip's `lane` for drawing must divide the row by that count.
- **Preset ids are checked against the tables they name** (`ExportChecks`): "aac" is not a
  format id (the table says "m4a") and "broadcast" is not a target (it says "ebu"). Both were
  wrong on the first write and the suite said so before a phone could.

## Things the first phone run taught (1 November), for the next stranger

- **An export is checked against its source sample by sample, not by ear or by its meter.**
  The first WAV export sounded plausible and measured only 0.1 LU off; it was 192 frames late
  with three bursts mixed in twice, because `Engine::renderOffline` ran `processBlock` while
  the Oboe callback ran it too (now `renderLock`). Found by pulling the WAV and the project's
  `media/<id>.f32` (`adb exec-out run-as com.layerbit.stave cat ...`) and diffing them with
  `od -tu1 -w3` (24-bit) and `od -tf4` (the float source) through awk: no Python is needed.
  Any C++ that touches the graph from a non-audio thread must take `renderLock` or be atomic.
- **`drawText` without a size throws when the text starts past the canvas edge**, and wraps
  it a letter a line when it starts near it; the editor crashed on the first sound opened.
  `Waveform.kt`'s `labelAt` measures at the text's own width and clips.
- **A missing `}` in Compose compiles.** The Export sheet's Artist/Album row swallowed the next
  row, which took the width and left two fields a few pixels wide. Only a screenshot showed it.
- **Live playback is checked the same way: debug builds capture it.** Play starts a 30 s capture
  of exactly what the output callback hands the speaker; Pause writes it to `cache/capture.f32`
  and logs `StaveGraph: capture: N frames from frame F`. Pull it with `run-as ... cat`, pull the
  source with `run-as ... dd if=files/projects/<id>/media/<src>.f32 bs=384000 count=N` (cut the
  80 bytes of dd statistics exec-out appends), and diff with `od -tf4` and awk. On 1 November
  12.4 s of a song came back bit-exact. The owner had heard "terrible" sound: Play was looping
  a selection an accidental drag had made. Since 2 November Play ramps up over its first 256 frames
  and Pause down over 256 (Engine.h), so compare from frame 256 and stop 256 short of the end.
- **Driving the phone**: `uiautomator dump` text matches are substrings unless anchored ("320"
  matched a sentence before the chip); a sheet's own buttons share names with the chip row
  ("Export"), so tap by position after checking the dump; a swipe starting below a sheet's
  bottom edge does not scroll it. `adb push` from Git Bash needs a `cygpath -m` local path
  when `MSYS_NO_PATHCONV=1` is set, because the prefix stops the local path's rewrite too.

- **Run lint before every push, not only once.** A cast of LocalContext to Activity passed the
  build and failed CI's lint an hour later. `./gradlew :engine:test assembleFullDebug
  lintFullDebug` is the push gate on this laptop.
- **Screen coordinates are 1080x2400; the screenshots shown here are scaled to 900 wide.**
  Multiply what you read off a screenshot by 1.2 before `input tap`. And while the mic is
  armed the level meter keeps the window busy, so `uiautomator dump` fails: tap by coordinates.
- **Whole-song tools must stream.** A 6-minute stereo song is 133 MB as floats; holding a few
  copies killed the app. Use `RawAudio.readPlanar`/`writePlanar` and `streamed()`, never
  `interleave()` on a whole song; DynamicsChecks proves each processor streams exactly.

- **A test sweep never runs on the owner's own projects.** The 1 November button sweep pressed
  "Remove clicks" in a project holding the owner's song, and the next day the owner heard the
  processed copy and called it grainy. Sweep in a project made for it, and a destructive tool
  is checked against a real record (`STAVE_RECORD=<a project's media .f32> sh tools/jvm/run.sh
  repair`), not only a sine.

## Working agreement

All work lands on `main` and is pushed when the suites pass. Commit messages say why. No model
names in commits, code or docs. `docs/ROADMAP.md` is ticked honestly: built-not-seen is not done.
