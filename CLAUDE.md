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
  (`music/`), the sequencer (`sequencer/`), export arithmetic (`export/`). Nothing in it
  imports `android.*`; `run.sh` and `RunnerChecks` both refuse it.
- `app/src/main/cpp/` is the realtime half: the Oboe render graph (`graph/`), libstaveav's JNI
  (`av/`, FFmpeg built in CI into `jniLibs`), whisper.cpp's JNI (`whisper/`), Rubber Band and
  TinySoundFont. `ARCHITECTURE.md` draws it.
- `app/src/main/java/com/layerbit/stave/` is Compose and the doors: `ui/`, `audio/` (the
  service, the device list), `files/`, `export/`, `models/` (the asset-pack and imported models).

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

## Working agreement

All work lands on `main` and is pushed when the suites pass. Commit messages say why. No model
names in commits, code or docs. `docs/ROADMAP.md` is ticked honestly: built-not-seen is not done.
