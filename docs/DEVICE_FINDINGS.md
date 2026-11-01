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
