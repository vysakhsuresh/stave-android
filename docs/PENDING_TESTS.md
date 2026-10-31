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
7. `./gradlew :engine:test` on the laptop runs the same thirteen suites as `tools/jvm/run.sh` and passes.
