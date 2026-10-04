#!/usr/bin/env python3
"""Verify real CLI target failures and native cleanup, using isolated projects."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--compiler', type=Path, default=Path('app/build/install/azora/bin/azora'))
parser.add_argument('--native', action='store_true', help='Require clang and run ASan/UBSan cleanup checks')
args = parser.parse_args()
compiler = args.compiler.resolve()
assert compiler.is_file(), f'Build the CLI distribution first: {compiler}'


def invoke(*command):
    return subprocess.run(command, text=True, capture_output=True, timeout=60)


with tempfile.TemporaryDirectory(prefix='azora-foundation-') as temporary:
    root = Path(temporary)
    source = root / 'main.az'
    source.write_text('import std.string\nimport std.io\nfunc main() { println(strToUpper("Azora")) }\n')
    for target in ('llvm', 'll', 'wasm', 'wat'):
        result = invoke(str(compiler), 'compile', target, str(source))
        assert result.returncode != 0, f'{target} falsely accepted an unsupported transform'
        assert not result.stdout.strip(), f'{target} emitted a fake executable'
        assert 'not implemented' in result.stderr, result.stderr
    result = invoke(str(compiler), 'check', str(source))
    assert result.returncode == 0, result.stderr
    source.write_text('func main() { delay 1 }\n')
    result = invoke(str(compiler), 'compile', 'wasm', str(source))
    assert result.returncode != 0 and 'host clock' in result.stderr, result.stderr

    if args.native:
        clang = shutil.which('clang')
        assert clang, 'Native qualification requires clang'
        source.write_text('''import std.io
pack Child { var value: Int = 0 }
impl Child { dtor .() { println("child") } }
pack Parent { var child: Child = .() }
impl Parent {
    dtor .() {
        purge self.child
        println("parent")
    }
}
func work(): Int {
    let parent: Parent = .()
    defer { purge parent }
    return 7
}
func main() { println(work()) }
''')
        cleanup_source = source.read_text()
        probes = [(cleanup_source, 'child\nparent\n7')]
        for module, factory in [('shared', 'sharedOf'), ('syncshared', 'syncSharedOf')]:
            probes.append((f'''import std.io
import std.memory.{module}
func main() {{
    var first = {factory}(42)
    var second = first.clone()
    println(first.refCount)
    println(first.release())
    println(second.get)
    purge first
    purge second
}}
''', '2\n1\n42'))
        for program, expected in probes:
            source.write_text(program)
            for mode in ([], ['--debug']):
                result = invoke(str(compiler), 'compile', 'llvm', *mode, str(source))
                assert result.returncode == 0, result.stderr
                ir = root / 'main.ll'
                ir.write_text(result.stdout)
                executable = root / 'main'
                result = invoke(clang, str(ir), '-fsanitize=address,undefined', '-o', str(executable))
                assert result.returncode == 0, result.stderr
                result = invoke(str(executable))
                assert result.returncode == 0, result.stderr
                assert result.stdout.strip() == expected, result.stdout
                assert not result.stderr.strip(), result.stderr

print('CLI target integrity passed' + ('; native cleanup passed with ASan/UBSan' if args.native else ''))
