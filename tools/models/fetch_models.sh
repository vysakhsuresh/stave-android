#!/usr/bin/env bash
# Puts the bundled models under app/src/full/assets/models for the `full` flavour (the APK on
# GitHub Releases and F-Droid). The `play` flavour carries them in a Play asset pack instead.
# Run before `./gradlew assembleFullRelease`; the files are git-ignored (57 MB and more).
#
#   sh tools/models/fetch_models.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
DEST=app/src/full/assets/models
mkdir -p "$DEST"
get() { # file url sha256
  if [ -s "$DEST/$1" ]; then echo "have $1"; else curl -fL --retry 3 -o "$DEST/$1.part" "$2" && mv "$DEST/$1.part" "$DEST/$1"; fi
  echo "$3  $DEST/$1" | sha256sum -c -
}
# whisper.cpp's quantised multilingual models (MIT; OpenAI's weights converted by the whisper.cpp project).
get ggml-base-q5_1.bin "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin" "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
ls -l "$DEST"
