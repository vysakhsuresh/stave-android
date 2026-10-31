#!/usr/bin/env bash
# Puts the libstavewhisper.so of the newest successful run of the whisper workflow (or of run
# [id]) into app/src/main/jniLibs/<abi>/. Needs gh, logged in.
#   sh tools/native/fetch_whisper.sh [run-id]
set -euo pipefail
cd "$(dirname "$0")/../.."
RUN=${1:-$(gh run list --workflow whisper.yml --status success --limit 1 --json databaseId -q '.[0].databaseId')}
TMP=$(mktemp -d)
gh run download "$RUN" -D "$TMP"
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  mkdir -p "app/src/main/jniLibs/$abi"
  cp "$TMP/stavewhisper-$abi/libstavewhisper.so" "app/src/main/jniLibs/$abi/"
done
ls -l app/src/main/jniLibs/*/libstavewhisper.so
