#!/usr/bin/env python3
"""
Catch Modifier extensions used without their import.

`Modifier.clickable { }` is an extension function, so a missing import shows up as
`unresolved reference 'clickable'` on a *member access* - and check_unresolved.py
deliberately drops member accesses, because without the Android SDK an unresolved
member almost always means an unresolved receiver. This class of mistake therefore
slips through that filter entirely, and it has bitten this project three times
(weight, graphicsLayer, clickable).

The expected import for each extension is learned from the codebase rather than
hardcoded: if twenty files import androidx.compose.foundation.clickable, that is
what `.clickable` on a Modifier chain needs. Only chains rooted at `Modifier` are
inspected, so `list.size` and `bitmap.width` cannot be mistaken for modifiers.

The chain is walked with a bracket-aware scanner rather than a regex, because a
regex gets both of the interesting cases wrong. It stops at the first nested
paren, so `.background(accent.copy(alpha = 0.1f))` ends the chain; and it stops
at a trailing lambda, so everything after `.pointerInput(id) { ... }` is
invisible. Both of those blind spots were real: two files carried a `.padding()`
that came after a `pointerInput` lambda, and this checker reported them clean
while the compiler reported them unresolved.

Usage: python3 tools/check_modifier_imports.py
"""
import re, glob, sys, os, collections

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "app/src/main/java")


def skip_balanced(src, i, opening, closing):
    """Index just past a balanced bracket group starting at src[i], or i if none."""
    if i >= len(src) or src[i] != opening:
        return i
    depth = 0
    while i < len(src):
        c = src[i]
        if c == '"':
            i += 1
            while i < len(src) and src[i] != '"':
                i += 2 if src[i] == '\\' else 1
        elif c == opening:
            depth += 1
        elif c == closing:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return i


def skip_space(src, i):
    """Index of the next meaningful character, stepping over whitespace and comments."""
    while i < len(src):
        if src[i].isspace():
            i += 1
        elif src.startswith("//", i):
            i = src.find("\n", i)
            if i < 0:
                return len(src)
        elif src.startswith("/*", i):
            end = src.find("*/", i)
            i = len(src) if end < 0 else end + 2
        else:
            return i
    return i


def chain_links(src):
    """Every `.name` in a chain rooted at Modifier, as (position, name).

    Walks the chain instead of matching it, so a nested call or a trailing lambda
    in the middle is stepped over rather than ending the chain.
    """
    out = []
    for m in re.finditer(r'\b[Mm]odifier\b', src):
        i = m.end()
        while True:
            i = skip_space(src, i)
            if i >= len(src) or src[i] != '.':
                break
            j = skip_space(src, i + 1)
            k = j
            while k < len(src) and (src[k].isalnum() or src[k] == '_'):
                k += 1
            if k == j:
                break
            out.append((j, src[j:k]))
            i = skip_space(src, k)
            i = skip_balanced(src, i, '(', ')')
            i = skip_space(src, i)
            i = skip_balanced(src, i, '{', '}')
    return out


def main():
    files = glob.glob(os.path.join(ROOT, "**/*.kt"), recursive=True)
    sources = {f: open(f).read() for f in files}

    # Learn name -> import path from every import the project already makes.
    known = collections.defaultdict(collections.Counter)
    for src in sources.values():
        for path in re.findall(r'^import\s+([\w.]+)$', src, re.M):
            known[path.rsplit('.', 1)[-1]][path] += 1

    problems = []
    for f, src in sources.items():
        imported = {p.rsplit('.', 1)[-1] for p in re.findall(r'^import\s+([\w.]+)', src, re.M)}
        declared = set(re.findall(r'\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)\s*\(', src))

        for start, name in chain_links(src):
            if name in imported or name in declared:
                continue
            if name not in known:
                continue                           # never imported anywhere: not ours to judge
            best = known[name].most_common(1)[0][0]
            if not best.startswith("androidx.compose"):
                continue
            line = src[:start].count('\n') + 1
            problems.append((os.path.relpath(f, ROOT), line, name, best))

    if not problems:
        print("every Modifier extension used is imported")
        return 0
    print(f"MISSING MODIFIER IMPORTS ({len(problems)}):")
    for f, line, name, imp in sorted(set(problems)):
        print(f"  {f}:{line}  .{name}  -> needs `import {imp}`")
    return 1

if __name__ == "__main__":
    sys.exit(main())
