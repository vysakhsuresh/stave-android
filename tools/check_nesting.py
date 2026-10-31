#!/usr/bin/env python3
"""Catch a top-level declaration that has been swallowed into another function.

This exists because of a real bug: a floating button was inserted one brace too
deep, which left `private fun ShareTarget` nested inside the screen composable.
Nothing else noticed. The symbol checker sees only names, so a declaration in
the wrong scope is invisible to it; the compiler said so in a single line buried
under five thousand classpath errors.

The rule is simple enough to be exact: in this codebase every declaration that
starts at column zero is meant to be top level, so at that point the brace depth
must be zero. Anything else means a block above it was never closed.

Run with no arguments from the repo root.
"""

import pathlib
import re
import sys

DECL = re.compile(r"^(@|(public |private |internal |)(inline |suspend |)(fun|val|var|class|object|enum|data|sealed|interface)\b)")


def depth_of(path: pathlib.Path) -> list[str]:
    """Report every column-zero declaration that is not actually top level."""
    problems: list[str] = []
    depth = 0
    in_block_comment = False
    for number, raw in enumerate(path.read_text().split("\n"), start=1):
        line = raw
        if not in_block_comment and DECL.match(line) and depth != 0:
            problems.append(f"{path}:{number}: nested {depth} deep — {line.strip()[:70]}")

        # Strip what would otherwise be counted: comments, strings, and chars.
        i = 0
        while i < len(line):
            two = line[i:i + 2]
            if in_block_comment:
                if two == "*/":
                    in_block_comment = False
                    i += 2
                    continue
                i += 1
                continue
            if two == "/*":
                in_block_comment = True
                i += 2
                continue
            if two == "//":
                break
            if line[i:i + 3] == '"""':
                end = line.find('"""', i + 3)
                if end == -1:
                    # A raw string running past the end of the line. Skip to the
                    # line that closes it rather than counting its braces.
                    return problems + _skip_raw(path, number, depth)
                i = end + 3
                continue
            if line[i] == '"':
                i += 1
                while i < len(line) and line[i] != '"':
                    i += 2 if line[i] == "\\" else 1
                i += 1
                continue
            if line[i] == "{":
                depth += 1
            elif line[i] == "}":
                depth -= 1
            i += 1
    if depth != 0:
        problems.append(f"{path}: file ends at brace depth {depth}")
    return problems


def _skip_raw(path: pathlib.Path, number: int, depth: int) -> list[str]:
    """Multi-line raw strings are rare here; report rather than guess."""
    return [f"{path}:{number}: multi-line raw string — not checked past this point"]


def main() -> int:
    root = pathlib.Path("app/src/main/java")
    problems = []
    for path in sorted(root.rglob("*.kt")):
        problems += depth_of(path)
    if problems:
        for p in problems:
            print(p)
        return 1
    print("every top-level declaration is actually top level")
    return 0


if __name__ == "__main__":
    sys.exit(main())
