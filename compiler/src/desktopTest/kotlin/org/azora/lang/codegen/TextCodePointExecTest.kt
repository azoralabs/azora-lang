/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * Roadmap step A4: text on every backend, in code points.
 *
 * A `Char` is a Unicode code point and a `String` is counted and indexed in
 * code points (user decision, 2026-11-07). LLVM had counted bytes and the
 * interpreter UTF-16 units, so `"aé😀"` was 8 long natively and 4 nowhere.
 * `trim`, `split`, `replace`, `toUpper` and `toLower` are written in Azora
 * (std.core over the generated std.unicodedata), so all targets run one
 * implementation; LLVM had refused all five. Each program runs on the
 * interpreter, LLVM (debug and optimized, and under ASan/UBSan) and
 * WebAssembly.
 */
class TextCodePointExecTest {
    private fun everywhere(expected: String, source: String) {
        val program = source.trimIndent()
        val result = Compiler().compile(program)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter")
        if (LlvmExec.available) {
            assertEquals(expected, LlvmExec.run(program), "LLVM")
            assertEquals(expected, LlvmExec.run(program, optimized = true), "LLVM optimized")
            sanitized(program, expected)
        }
        if (WasmExec.available) assertEquals(expected, WasmExec.run(program), "WebAssembly")
    }

    /** Built with Apple clang and `-fsanitize=address,undefined`: the output, and no report. */
    private fun sanitized(program: String, expected: String) {
        val tool = SanitizerClang.find("Native text qualification") ?: return
        for (release in listOf(false, true)) {
            val compiled = Compiler().compile(program, release = release)
            val result = assertIs<CompilationResult.Success>(compiled, (compiled as? CompilationResult.Failure)?.errors.toString())
            val directory = Files.createTempDirectory("azora-text-").toFile()
            try {
                val ir = directory.resolve("main.ll").apply { writeText(result.llvm) }
                val binary = directory.resolve("probe")
                run(directory, tool, ir.path, "-Wno-override-module", "-fsanitize=address,undefined", "-o", binary.path)
                assertEquals(expected, run(directory, binary.path).trim(), "LLVM under ASan/UBSan, release=$release")
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun run(directory: File, vararg arguments: String): String {
        val output = directory.resolve("stdout")
        val errors = directory.resolve("stderr")
        val process = ProcessBuilder(*arguments).redirectOutput(output).redirectError(errors).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            fail("native text probe timed out")
        }
        assertEquals(0, process.exitValue(), output.readText() + errors.readText())
        if (arguments.size == 1) assertEquals("", errors.readText(), "sanitizer diagnostics")
        return output.readText()
    }

    @Test fun aStringIsCountedAndIndexedInCodePoints() = everywhere(
        "4\né\n😀\né😀\n3\n4\n!\n233\né!\ntrue\ntrue\nfalse",
        """
        import std.io
        func main() {
            fin s = "aé😀" + "!"
            println(stringLength(s))
            println(charAt(s, 1))
            println(charAt(s, 2))
            println(substring(s, 1, 3))
            println(indexOf(s, "!"))
            println(s.length)
            println(s[3])
            fin c: Char = 'é'
            println(ord(c))
            println("${'$'}{c}!")
            println(startsWith(s, "aé"))
            println(endsWith(s, "😀!"))
            println(contains(s, "é!"))
        }
        """,
    )

    @Test fun charsRoundTripThroughCodePoints() = everywhere(
        "8\nï\n😀\nnaïve 😀!\nHé😀\n3\n128512\ntrue",
        """
        import std.io
        func main() {
            fin s = "naïve 😀" + "!"
            fin cs = toChars(s)
            println(cs.size)
            println(cs[2])
            println(cs[6])
            println(fromChars(cs))
            fin built = fromChars([chr(72), chr(233), '😀'])
            println(built)
            println(built.length)
            fin smile: Char = '😀'
            println(ord(smile))
            println(smile == cs[6])
        }
        """,
    )

    @Test fun trimRemovesUnicodeWhiteSpace() = everywhere(
        "[a b]\n[x]\n[]\n[é]",
        """
        import std.io
        func main() {
            println("[" + trim("  a b \t\n") + "]")
            println("[" + trim(fromChars([chr(12288), chr(160), 'x', chr(133), chr(8233)])) + "]")
            println("[" + trim(" \t ") + "]")
            println("[" + trim("é") + "]")
        }
        """,
    )

    @Test fun splitAndReplaceWorkOnCodePoints() = everywhere(
        "4\na|b||c\n1\n[]\n5\n|a|b|c|\nnaÏve 😀 naÏve\n-a-é-\nxyz\n3\nα|β|γ",
        """
        import std.io
        func joined(parts: Array<String>&): String {
            var text = ""
            for i in 0..<parts.size {
                if i > 0 { text = text + "|" }
                text = text + parts[i]
            }
            return text
        }
        func main() {
            fin parts = split("a,b,,c", ",")
            println(parts.size)
            println(joined(parts))
            fin none = split("", ",")
            println(none.size)
            println("[" + none[0] + "]")
            fin each = split("abc", "")
            println(each.size)
            println(joined(each))
            println(replace("naïve 😀 naïve", "ï", "Ï"))
            println(replace("aé", "", "-"))
            println(replace("xyz", "q", "r"))
            fin greek = split("α→β→γ", "→")
            println(greek.size)
            println(joined(greek))
        }
        """,
    )

    @Test fun caseMappingIsUnicodesFullDefault() = everywhere(
        "HÉLLO, WÖRLD\nSTRASSE\n\uFB01→FI\nΣΟΦΙΑ\nhéllo, wörld\ni\u0307stanbul\n" +
            "\u03C3\u03BF\u03C6\u03BF\u03C2\n\u03CC\u03C3\u03BF\u03C2 \u03C3\nabc😀",
        """
        import std.io
        func main() {
            println(toUpper("Héllo, Wörld"))
            println(toUpper("Straße"))
            println("ﬁ→" + toUpper("ﬁ"))
            println(toUpper("σοφια"))
            println(toLower("HÉLLO, WÖRLD"))
            println(toLower("İstanbul"))
            println(toLower("ΣΟΦΟΣ"))
            println(toLower("ΌΣΟΣ Σ"))
            println(toLower("ABC😀"))
        }
        """,
    )
}
