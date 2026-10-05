#!/usr/bin/env python3
"""Qualify the native compiler and Studio's argv/diagnostic protocol without a JVM."""
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

    def run(*arguments, success=True):
        result = subprocess.run([str(compiler), *map(str, arguments)], env=env,
                                text=True, capture_output=True, timeout=120)
        if (result.returncode == 0) != success:
            raise AssertionError(f"native {arguments}: exit {result.returncode}\n{result.stdout}\n{result.stderr}")
        return result.stdout

    assert "native macOS arm64; protocol 1" in run("version")
    linked = subprocess.run(["/usr/bin/otool", "-L", str(compiler)], text=True,
                            capture_output=True, check=True).stdout.lower()
    assert "libjvm" not in linked and "libjli" not in linked, linked
    with tempfile.TemporaryDirectory(prefix="azora native ' ;$ ", dir="/private/tmp") as temporary:
        project = Path(temporary)
        source = project / "src"
        source.mkdir()
        entry = source / "main.az"
        entry.write_text('''module smoke
import std.io
import smoke.math
pack NodeScope { var next: Int = 0 }
pack Caption
impl Caption {
    react ctor (context: NodeScope!).(text: String): Int {
        context.next += 1
        return context.next
    }
}
react func main() {
    var nodeScope = NodeScope()
    using nodeScope { println(Caption("native")) }
    println(plus(2, 3))
}
''')
        (source / "math.az").write_text("module smoke.math\nfunc plus(a: Int, b: Int): Int { return a + b }\n")
        assert "No errors found." in run("check", entry)
        for mode in ("--debug", "--release"):
            llvm = project / (mode[2:] + ".ll")
            llvm.write_text(run("compile", "llvm", entry, mode))
            executable = project / mode[2:]
            subprocess.run(["/usr/bin/clang", str(llvm), "-Wno-override-module", "-o", str(executable)], check=True)
            output = subprocess.run([str(executable)], text=True, capture_output=True, check=True).stdout
            assert output.strip() == "1\n5", output
        assert "built " in run(project, "build")
        assert run(project, "play").strip().endswith("1\n5")
        snapshot = json.loads(run(project, "inspect"))
        assert snapshot["protocol"] == 1 and not snapshot["diagnostics"], snapshot

        # A non-ASCII prefix separates UTF-16 source offsets from UTF-8 editor
        # positions. Studio must receive the actual compiler's exact token span.
        invalid = 'module smoke\nimport std.io\nfunc main() { println("é🙂"); println(missing) }\n'
        entry.write_text(invalid)
        run("check", entry, success=False)
        snapshot = json.loads(run("analyze", entry, "--version=37"))
        assert snapshot["version"] == 37 and snapshot["snapshot"], snapshot
        diagnostics = [d for d in snapshot["diagnostics"] if "missing" in d["message"]]
        assert diagnostics, snapshot
        span = diagnostics[0]
        offset = invalid.index("missing")
        utf16 = len(invalid[:offset].encode("utf-16-le")) // 2
        line = invalid[:offset].count("\n")
        byte_column = len(invalid[:offset].rsplit("\n", 1)[-1].encode("utf-8"))
        assert (span["start"], span["end"]) == (utf16, utf16 + 7), span
        assert (span["startLine"], span["startByte"]) == (line, byte_column), span
        assert Path(span["source"]).resolve() == entry.resolve(), span
        run(project, "build", success=False)
    print("native compiler passed: native executable, constructor results, modules, debug/release LLVM, build/play/inspect, UTF-8 diagnostics")


if __name__ == "__main__":
    main()
