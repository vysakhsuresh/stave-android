#!/usr/bin/env bash
# Puts the bundled models under app/src/full/assets/models for the `full` flavour (the APK on
# GitHub Releases and F-Droid), or under models_pack/src/main/assets/models for the `play`
# flavour's install-time asset pack. Run before the release build; the files are git-ignored.
#
#   sh tools/models/fetch_models.sh        # full
#   sh tools/models/fetch_models.sh play   # the asset pack
set -euo pipefail
cd "$(dirname "$0")/../.."
# `fetch_models.sh` fills the full flavour's assets; `fetch_models.sh play` fills the asset pack.
case "${1:-full}" in
  play) DEST=models_pack/src/main/assets/models ;;
  *) DEST=app/src/full/assets/models ;;
esac
mkdir -p "$DEST"
get() { # file url sha256
  if [ -s "$DEST/$1" ]; then echo "have $1"; else curl -fL --retry 3 -o "$DEST/$1.part" "$2" && mv "$DEST/$1.part" "$DEST/$1"; fi
  echo "$3  $DEST/$1" | sha256sum -c -
}
# whisper.cpp's quantised multilingual models (MIT; OpenAI's weights converted by the whisper.cpp project).
get ggml-base-q5_1.bin "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin" "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
# The stem models are made, not downloaded: python3 tools/models/umx_to_tflite.py "$DEST" (see its header).
for stem in vocals drums bass other; do [ -s "$DEST/umx_$stem.tflite" ] || echo "missing umx_$stem.tflite - run tools/models/umx_to_tflite.py"; done
ls -l "$DEST"
