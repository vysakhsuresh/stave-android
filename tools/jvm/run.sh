#!/usr/bin/env bash
# Compiles the pure engine (engine/src/main/kotlin) together with every suite under tools/jvm
# using the standalone Kotlin compiler - NO Android classes on the path - and runs the suites.
#
# What this proves on every run, on any machine with a JDK and no Android SDK:
#   1. Nothing under engine/ imports android.* - the compile fails if it does. The engine is
#      where every number the app shows and every sample it changes is decided; keeping it pure
#      is what lets those decisions be executed in seconds rather than on a phone.
#   2. Every suite passes. A suite is an object implementing Suite (tools/jvm/Checks.kt) and is
#      listed in Registry; RunnerChecks fails if a *Checks.kt file exists that Registry does not
#      name, because a suite nothing runs reads as cover and is none.
#
#   sh tools/jvm/run.sh            # every suite
#   sh tools/jvm/run.sh fft eq     # the named suites only (by Suite.name)
#   sh tools/jvm/run.sh --list
#
# Jars are fetched once from Maven Central into ~/.stave-kt (STAVE_KT_HOME overrides).
# The desktop runs the same suites through Gradle: ./gradlew :engine:test.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K="${STAVE_KT_HOME:-$HOME/.stave-kt}"
KV=2.2.21
MC=https://repo.maven.apache.org/maven2
mkdir -p "$K"
is_jar() { [ -s "$1" ] && [ "$(head -c 2 "$1")" = "PK" ]; }
fetch() { # name path
  if ! is_jar "$K/$1"; then
    for try in 1 2 3 4 5; do
      if curl -sSL -f -o "$K/$1.part" "$MC/$2" && is_jar "$K/$1.part"; then mv "$K/$1.part" "$K/$1"; break; fi
      sleep $((try * 5))
    done
  fi
  is_jar "$K/$1" || { echo "could not fetch $1"; exit 1; }
}
fetch kotlin-compiler-embeddable.jar "org/jetbrains/kotlin/kotlin-compiler-embeddable/$KV/kotlin-compiler-embeddable-$KV.jar"
fetch kotlin-stdlib.jar "org/jetbrains/kotlin/kotlin-stdlib/$KV/kotlin-stdlib-$KV.jar"
fetch kotlin-script-runtime.jar "org/jetbrains/kotlin/kotlin-script-runtime/$KV/kotlin-script-runtime-$KV.jar"
fetch kotlin-reflect.jar "org/jetbrains/kotlin/kotlin-reflect/$KV/kotlin-reflect-$KV.jar"
fetch kotlin-daemon-embeddable.jar "org/jetbrains/kotlin/kotlin-daemon-embeddable/$KV/kotlin-daemon-embeddable-$KV.jar"
fetch trove4j.jar "org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar"
fetch annotations.jar "org/jetbrains/annotations/13.0/annotations-13.0.jar"
fetch coroutines.jar "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.jar"

COMPILER_CP="$K/kotlin-compiler-embeddable.jar:$K/kotlin-stdlib.jar:$K/kotlin-script-runtime.jar:$K/kotlin-reflect.jar:$K/kotlin-daemon-embeddable.jar:$K/trove4j.jar:$K/annotations.jar:$K/coroutines.jar"
OUT="${STAVE_JVM_OUT:-$ROOT/tools/out}"
rm -rf "$OUT" && mkdir -p "$OUT"

ENGINE="${STAVE_ENGINE_SRC:-$ROOT/engine/src/main/kotlin}"
SUITES="$ROOT/tools/jvm"

if grep -rn "^import android" "$ENGINE"; then
  echo "engine/ must not import android.* (see above)"; exit 1
fi

echo "compiling engine/ and the suites"
# The JUnit shim in tools/jvm is for Gradle; here the suites are driven by MainKt.
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -nowarn -cp "$K/kotlin-stdlib.jar" -d "$OUT" \
  $(find "$ENGINE" -name '*.kt') $(find "$SUITES" -name '*.kt' ! -name 'EngineSuitesTest.kt') 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
[ -f "$OUT/MainKt.class" ] || { echo "compile failed"; exit 1; }

java -cp "$OUT:$K/kotlin-stdlib.jar" MainKt "$@" 2>&1 | grep -v JAVA_TOOL_OPTIONS
