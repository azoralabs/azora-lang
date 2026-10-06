#!/usr/bin/env python3
"""Qualify native semantic queries using resolved compiler facts and UTF-8 positions."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", type=Path, default=root / "compiler/build/bin/macosArm64/debugExecutable/azora.kexe")
    args = parser.parse_args()
    compiler = args.compiler.resolve(strict=True)
    env = dict(os.environ, AZORA_STDLIB=str(root / "std"))

    def query(command, path, text, needle, *, last=False, prefix=""):
        offset = text.rindex(needle) if last else text.index(needle)
        line = text[:offset].count("\n")
        byte = len(text[:offset].rsplit("\n", 1)[-1].encode("utf-8"))
        arguments = [str(compiler), command, str(path), f"--line={line}", f"--byte={byte}", "--version=57"]
        if prefix:
            arguments.append(f"--prefix={prefix}")
        result = subprocess.run(arguments, env=env, text=True, capture_output=True, timeout=120)
        assert result.returncode == 0, (arguments, result.stdout, result.stderr)
        snapshot = json.loads(result.stdout)
        assert snapshot["protocol"] == 1 and snapshot["version"] == 57 and snapshot["snapshot"], snapshot
        assert snapshot["query"] == command, snapshot
        return snapshot

    with tempfile.TemporaryDirectory(prefix="azora semantic ' ;$ ", dir="/private/tmp") as temporary:
        entry = Path(temporary) / "main.az"
        library = Path(temporary) / "math.az"
        library.write_text("module demo.math\nfunc plus(a: Int, b: Int): Int { return a + b }\n")
        text = '''import std.io
import demo.math::plus
pack Box {
    fin value: Int
    fin label: String
}
func main() {
    fin box = Box(7, "item")
    fin value = box.value
    if true {
        fin value = "inner"
        println("é🙂" + value)
    }
    println(value)
    println(plus(2, 3))
}
'''
        entry.write_text(text)
        hover = query("hover", entry, text, "value)")
        assert hover["completeness"] == "complete" and hover["result"]["type"] == "String", hover
        assert query("hover", entry, text, "value)", last=True)["result"]["type"] == "Int"
        inner = query("definition", entry, text, "value)")["result"]
        outer = query("definition", entry, text, "value)", last=True)["result"]
        assert inner["start"] == text.index('value = "inner"'), inner
        assert outer["start"] == text.index("value = box.value"), outer
        assert query("complete", entry, text, "value)", prefix="value")["result"][0]["type"] == "String"
        assert query("complete", entry, text, "value)", last=True, prefix="value")["result"][0]["type"] == "Int"
        member = query("complete", entry, text, "value\n")["result"]
        assert {item["name"] for item in member} == {"value", "label"}, member
        assert query("hover", entry, text, "value\n")["result"]["type"] == "Int"
        field = query("definition", entry, text, "value\n")["result"]
        assert field["start"] == text.index("value: Int"), field
        imported = query("definition", entry, text, "plus(2")["result"]
        assert Path(imported["source"]).resolve() == library.resolve(), imported
        assert imported["start"] == library.read_text().index("plus"), imported

        partial = "import std.io\nfunc main() { fin value = 41; println(value); missing() }\n"
        entry.write_text(partial)
        facts = query("hover", entry, partial, "value)")
        assert facts["completeness"] == "partial" and facts["result"]["type"] == "Int" and facts["diagnostics"], facts
        assert query("hover", entry, partial, "missing")["result"] is None
        assert query("definition", entry, partial, "missing")["result"] is None
        assert query("complete", entry, partial, "missing", prefix="value")["result"][0]["type"] == "Int"

        blocked = "func main() { fin value = Box(\n"
        entry.write_text(blocked)
        facts = query("complete", entry, blocked, "value")
        assert facts["completeness"] == "blocked" and facts["result"] == [] and facts["diagnostics"], facts
    print("native semantic queries passed: inferred receiver, field/function definitions, shadowing, imports, UTF-8, partial/blocked analysis")


if __name__ == "__main__":
    main()
