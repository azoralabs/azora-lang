package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for `threadlocal var` / `threadlocal fin` — per-coroutine independent storage.
 */
class ThreadLocalTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun threadLocalFinAccessible() {
        assertEquals("42", run("""
            import std.io
            threadlocal fin answer = 42
            func main() {
                std::println(answer)
            }
        """.trimIndent()))
    }

    @Test fun threadLocalVarAccessible() {
        val result = Compiler().compile("""
            import std.io
            threadlocal var counter = 0
            func main() {
                counter = 5
                std::println(counter)
            }
        """.trimIndent())
        when (result) {
            is CompilationResult.Success -> {
                val output = IrInterpreter().interpret(result.ir).trim()
                assertEquals("5", output)
            }
            is CompilationResult.Failure -> fail("Compilation failed: ${result.errors}")
        }
    }

    @Test fun threadLocalVarIndependentPerTask() {
        // Each task gets its own fresh copy of the thread-local variable.
        // With parallel execution the output order is nondeterministic; sort lines.
        val output = run("""
            import std.io
            threadlocal var counter = 0
            func main() {
                var t1 = task {
                    std::println(counter)
                    counter = 5
                    std::println(counter)
                }
                var t2 = task {
                    std::println(counter)
                    counter = 5
                    std::println(counter)
                }
                await t1
                await t2
            }
        """.trimIndent())
        val lines = output.lines().sorted()
        assertEquals(listOf("0", "0", "5", "5"), lines)
    }

    @Test fun threadLocalWithTypedAnnotation() {
        assertEquals("hello", run("""
            import std.io
            threadlocal var msg: String = "hello"
            func main() {
                std::println(msg)
            }
        """.trimIndent()))
    }
}
