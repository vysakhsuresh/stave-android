#!/usr/bin/env python3
"""
Find references to things that do not exist, in a sandbox with no Android SDK.

Without the Compose/Media3 jars, kotlinc reports thousands of unresolved
references and a genuine mistake - a renamed function, a missing import - looks
exactly like the noise. That is how three calls to a renamed `mutateVideoTrack`
survived a rename and several "clean" checks.

The filter that separates them:
  * member accesses (`foo.bar`) are dropped - an unresolved member almost always
    means an unresolved receiver, which is the classpath talking;
  * bare identifiers the file imports are dropped - that is the classpath too;
  * bare identifiers the file itself declares are dropped - a local whose own
    declaration failed to resolve.
What is left is a bare name the file neither imports nor declares, which is what
a typo, a bad rename or a forgotten import looks like.

Usage: kotlinc ... > log 2>&1 ; python3 tools/check_unresolved.py log
"""
import re, sys, os, collections

IGNORE = {
    "it", "this", "field", "value",
    # Desugared operators: `!in` reports `not`, `a in b` reports `contains`,
    # `a..b` reports `rangeTo`, and so on. These name the syntax, not a mistake.
    "not", "contains", "compareTo", "rangeTo", "inc", "dec", "unaryMinus",
    "unaryPlus", "invoke", "iterator", "hasNext", "next", "getValue", "setValue",
    "provideDelegate", "component1", "component2",
}

# Members of external scopes, called bare because their receiver is implicit.
# Their receiver is what is really unresolved; listing them every run is noise.
SCOPE_MEMBERS = {
    "drawRect", "drawLine", "drawCircle", "drawPath", "drawContent", "drawArc",
    "moveTo", "lineTo", "cubicTo", "quadraticBezierTo", "close", "density",
    "copy", "getApplication", "addFlags", "div", "times", "plus", "minus",
    "weight", "align", "clipRect", "inset", "rotate", "scale", "translate",
}

def declarations(src):
    # The receiver of an extension has to be skipped, or `fun BoxScope.TrimHandle`
    # registers BoxScope as the declaration and TrimHandle as undeclared.
    names = set(re.findall(
        r'\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>?]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(', src))
    names |= set(re.findall(
        r'\b(?:val|var|class|object|interface)\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)', src))
    names |= set(re.findall(r'\b([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=,)]+)?\s*(?:=|->)', src))
    names |= set(re.findall(r'^\s*([A-Za-z_][A-Za-z0-9_]*)\s*[:,)]', src, re.M))
    return names

def imports(src):
    return {line.rsplit('.', 1)[-1].strip()
            for line in re.findall(r'^import\s+(.+)$', src, re.M)}

def echo_is_import(lines, i):
    echo = lines[i + 1] if i + 1 < len(lines) else ""
    return echo.lstrip().startswith("import ")

def main(log_path):
    log = open(log_path, errors="replace").read()
    lines = log.splitlines()
    cache, found = {}, collections.defaultdict(list)

    for i, line in enumerate(lines):
        m = re.match(r"(\S+\.kt):(\d+):(\d+): error: unresolved reference '([A-Za-z_][A-Za-z0-9_]*)'", line)
        if not m:
            continue
        path, lineno, col, name = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
        if name in IGNORE:
            continue
        if echo_is_import(lines, i):
            continue                                   # `import androidx...` itself
        # kotlinc echoes the offending source line directly after the message.
        echo = lines[i + 1] if i + 1 < len(lines) else ""
        if col >= 2 and len(echo) >= col - 1 and echo[col - 2] == '.':
            continue                                   # member access -> cascade
        if path not in cache:
            try:
                src = open(path).read()
            except OSError:
                continue
            cache[path] = (imports(src), declarations(src))
        imported, declared = cache[path]
        if name in imported or name in declared or name in SCOPE_MEMBERS:
            continue
        found[name].append(f"{os.path.basename(path)}:{lineno}")

    # Calls on an implicit receiver inside apply/also, where the receiver's type is
    # what is really unresolved. The set is stable, so it is recorded once and only
    # additions to it are reported - which is what makes the output worth reading.
    baseline = set()
    baseline_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "unresolved_baseline.txt")
    if os.path.exists(baseline_path):
        baseline = {l.strip() for l in open(baseline_path) if l.strip() and not l.startswith("#")}

    if "--write-baseline" in sys.argv:
        with open(baseline_path, "w") as f:
            f.write("# Names kotlinc cannot resolve without the Android SDK, and which are\n")
            f.write("# not real mistakes. Regenerate with --write-baseline after a dependency change.\n")
            for name in sorted(found):
                f.write(name + "\n")
        print(f"baseline written: {len(found)} names")
        return 0

    new = {k: v for k, v in found.items() if k not in baseline}
    if not new:
        print(f"no new unresolved names ({len(found)} known, all in baseline)")
        return 0
    print(f"NEW SUSPECT REFERENCES ({len(new)}) - a rename that missed a call site looks like this:")
    for name, where in sorted(new.items()):
        print(f"  {name}  ->  {', '.join(sorted(set(where))[:8])}")
    return 1

if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
