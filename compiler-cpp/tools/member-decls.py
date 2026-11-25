#!/usr/bin/env python3
"""Regenerates a struct's member-function declarations from its definitions.

    tools/member-decls.py <header> <Struct> <file.cpp>...

Every `Ret Struct::name(params) {` definition in the given files (in the
order given) becomes a declaration between the header's
`// MEMBERS BEGIN` and `// MEMBERS END` lines. A default argument is written
in the definition as `/*= value*/` and becomes `= value` in the declaration.
A definition preceded by a `// static` line is declared static.
"""
import os
import re
import sys


def declarations(struct, path):
    out = ["    // " + os.path.basename(path)]
    lines = open(path).read().split("\n")
    qualifier = struct + "::"
    i = 0
    while i < len(lines):
        line = lines[i]
        if qualifier in line and not line.startswith((" ", "//", "#", "}")):
            text = line
            j = i
            start = re.search(r"\b" + re.escape(struct) + r"::\w+\(", text)
            if not start:
                i += 1
                continue
            k = start.end() - 1
            depth = 0
            close = -1
            while close < 0:
                while k < len(text):
                    if text[k] == "(":
                        depth += 1
                    elif text[k] == ")":
                        depth -= 1
                        if depth == 0:
                            close = k
                            break
                    k += 1
                if close < 0:
                    j += 1
                    text += " " + lines[j].strip()
            while "{" not in text[close:]:
                j += 1
                text += " " + lines[j].strip()
            sig = text[:text.index("{", close)].strip()
            m = re.match(r"^(.*?)\b" + re.escape(struct) + r"::(\w+)\((.*)\)(\s*const)?$", sig)
            if m and m.group(2) != struct:
                ret, name, params, const = m.group(1).strip(), m.group(2), m.group(3), m.group(4) or ""
                params = re.sub(r"/\*=\s*(.*?)\*/", r"= \1", params)
                params = re.sub(r"\s+", " ", params)
                static = "static " if i > 0 and lines[i - 1].strip() == "// static" else ""
                out.append("    %s%s %s(%s)%s;" % (static, ret, name, params, const))
            i = j + 1
            continue
        i += 1
    return out


def main():
    header, struct, files = sys.argv[1], sys.argv[2], sys.argv[3:]
    decls = []
    for path in files:
        decls += declarations(struct, path)
    h = open(header).read()
    begin = h.index("    // MEMBERS BEGIN")
    end = h.index("    // MEMBERS END")
    first_line_end = h.index("\n", begin) + 1
    open(header, "w").write(h[:first_line_end] + "\n".join(decls) + "\n" + h[end:])
    print("%d declarations" % sum(1 for d in decls if not d.strip().startswith("//")))


main()
