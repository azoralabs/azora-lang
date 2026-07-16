package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for character indexing into strings: `s[i]` returns the i-th [Char].
 * These back the AZON parser, which scans its input character by character.
 */
class StringIndexTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test
    fun indexReturnsChar() {
        assertEquals("e", run("""
            import std.io
            func main() {
                let s = "hello"
                std::io::println("${'$'}{s[1]}")
            }
        """.trimIndent()))
    }

    @Test
    fun indexScansStringInLoop() {
        assertEquals("digits=3", run("""
            import std.io
            func isDigit(c: Char): Bool {
                return c >= '0' && c <= '9'
            }
            func main() {
                let s = "a1b2c3"
                var i = 0
                var n = 0
                while i < s.length {
                    if isDigit(s[i]) { n = n + 1 }
                    i = i + 1
                }
                std::io::println("digits=${'$'}n")
            }
        """.trimIndent()))
    }

    @Test
    fun logicalAndShortCircuitsBeforeIndexing() {
        // The right operand indexes past the end; `&&` must short-circuit so the
        // out-of-bounds read never happens (this used to throw in the interpreter).
        assertEquals("hits=1", run("""
            import std.io
            func main() {
                let s = "ab"
                var i = 0
                var hits = 0
                while i < s.length {
                    if i + 1 < s.length && s[i + 1] == 'b' { hits = hits + 1 }
                    i = i + 1
                }
                std::io::println("hits=${'$'}hits")
            }
        """.trimIndent()))
    }

    @Test
    fun stringIndexingLowersToLlvm() {
        val result = Compiler().compile(
            """
            import std.io
            func main() {
                let s = "hi"
                std::io::println("${'$'}{s[0]}")
            }
            """.trimIndent(),
            release = true,
        )
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        assertFalse(
            result.llvm.contains("index on String — not lowered"),
            "String indexing must be lowered by the LLVM backend",
        )
    }
}
