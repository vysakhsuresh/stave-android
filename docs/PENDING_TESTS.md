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
