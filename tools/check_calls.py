#!/usr/bin/env python3
"""Finds call sites that no longer match the function they call.

Changing a function's parameters and missing one of its callers is the single
easiest mistake to make in a codebase this size, and it is invisible to every
other check here: the file still parses, every name still resolves, and the
imports are all present. It is a compile error, and the compile log had it - but
it was being filtered out along with the thousands of unresolved-reference errors
that the missing Android SDK produces, on the grounds that everything in that log
was noise. It was not.

Only the three arity errors are looked at: an argument missing, an argument too
many, a name that is not a parameter. Those are structural facts about two pieces
of our own code, so unlike a type error they do not arise from the framework
being absent - argument type mismatches were tried here and were pure cascade,
twenty-seven of them saying things like "passes Quality where Quality was
wanted". The one exception is a parameter called p1, p2 and so on: the synthetic
name of a lambda whose type could not be resolved, which is cascade, and the only
thing skipped.

    python3 tools/check_calls.py <compile log>
"""
import re
import sys

MISSING_ARG = re.compile(r"error: no value passed for parameter '([^']+)'")
TOO_MANY = re.compile(r"error: too many arguments for")
UNKNOWN_NAME = re.compile(r"error: cannot find a parameter with this name: (\S+)")

# Synthetic parameter names, produced when a lambda's type could not be resolved.
CASCADE_PARAM = re.compile(r"^p\d+$")

LOCATION = re.compile(r"^(\S+?\.kt):(\d+):(\d+): ")


def main(path: str) -> int:
    findings = []
    with open(path, encoding="utf-8", errors="replace") as log:
        for line in log:
            where = LOCATION.match(line)
            if not where:
                continue

            missing = MISSING_ARG.search(line)
            if missing:
                if CASCADE_PARAM.match(missing.group(1)):
                    continue
                findings.append((where.group(1), where.group(2), f"no argument for '{missing.group(1)}'"))
                continue

            if TOO_MANY.search(line):
                findings.append((where.group(1), where.group(2), "too many arguments"))
                continue

            named = UNKNOWN_NAME.search(line)
            if named:
                findings.append((where.group(1), where.group(2), f"no parameter called {named.group(1)}"))

    if not findings:
        print("every call site matches the function it calls")
        return 0

    print(f"CALL SITES OUT OF STEP ({len(findings)}) - a changed signature with a caller left behind:")
    for path_, line, what in findings:
        print(f"  {path_}:{line}  {what}")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "build.log"))
