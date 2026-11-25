#!/usr/bin/env python3
"""Regenerates the Parser member declarations in src/frontend/parser.h.

Every `Ret Parser::name(params) {` definition in src/frontend/parser_*.cpp
becomes a declaration between the METHODS markers, in file order. A default
argument is written in the definition as `/*= value*/` and becomes `= value`
in the declaration. A definition preceded by a `// static` line is declared
static.
"""
import glob
import os
import re

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
header = os.path.join(root, "src/frontend/parser.h")
order = [
    "parser_core.cpp", "parser_decls.cpp", "parser_inline.cpp", "parser_pack.cpp", "parser_impl.cpp",
    "parser_bridge.cpp", "parser_macros.cpp", "parser_spec.cpp", "parser_funcs.cpp", "parser_types.cpp",
    "parser_stmts.cpp", "parser_bindings.cpp", "parser_control.cpp", "parser_exprs.cpp", "parser_primary.cpp",
]
files = [os.path.join(root, "src/frontend", f) for f in order]
files = [f for f in files if os.path.exists(f)]
extra = sorted(set(glob.glob(os.path.join(root, "src/frontend/parser_*.cpp"))) - set(files))
files += extra

decls = []
for path in files:
    text = open(path).read()
    decls.append("    // " + os.path.basename(path))
    lines = text.split("\n")
    i = 0
    while i < len(lines):
        line = lines[i]
        if "Parser::" in line and not line.startswith(" ") and not line.startswith("//") and not line.startswith("#"):
            # collect the signature: balanced parameter parentheses, then up to the body's `{`
            text = line
            j = i
            open_at = re.search(r"\bParser::\w+\(", text)
            if not open_at:
                i += 1
                continue
            k = open_at.end() - 1
            depth = 0
            close = -1
            while close < 0:
                while k < len(text):
                    c = text[k]
                    if c == "(":
                        depth += 1
                    elif c == ")":
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
            m = re.match(r"^(.*?)\bParser::(\w+)\((.*)\)(\s*const)?$", sig)
            if m and m.group(2) != "Parser":
                ret, name, params, const = m.group(1).strip(), m.group(2), m.group(3), m.group(4) or ""
                params = re.sub(r"/\*=\s*(.*?)\*/", r"= \1", params)
                params = re.sub(r"\s+", " ", params)
                is_static = i > 0 and lines[i - 1].strip() == "// static"
                prefix = "static " if is_static else ""
                decls.append("    %s%s %s(%s)%s;" % (prefix, ret, name, params, const))
            i = j + 1
            continue
        i += 1

h = open(header).read()
begin = h.index("    // METHODS BEGIN")
end = h.index("    // METHODS END")
first_line_end = h.index("\n", begin) + 1
h = h[:first_line_end] + "\n".join(decls) + "\n" + h[end:]
open(header, "w").write(h)
print("%d declarations" % sum(1 for d in decls if not d.strip().startswith("//")))
