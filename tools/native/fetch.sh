#!/usr/bin/env bash
# Puts the libstaveav.so of the newest successful run of the native workflow (or of run [id])
# into app/src/main/jniLibs/<abi>/. Needs gh, logged in. Commit the libraries after: a Gradle
# build reads them from the tree and never needs the workflow.
#   sh tools/native/fetch.sh [run-id]
set -euo pipefail
cd "$(dirname "$0")/../.."
RUN=${1:-$(gh run list --workflow native.yml --status success --limit 1 --json databaseId -q '.[0].databaseId')}
TMP=$(mktemp -d)
gh run download "$RUN" -D "$TMP"
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  mkdir -p "app/src/main/jniLibs/$abi"
  cp "$TMP/staveav-$abi/libstaveav.so" "app/src/main/jniLibs/$abi/"
done
ls -l app/src/main/jniLibs/*/libstaveav.so
