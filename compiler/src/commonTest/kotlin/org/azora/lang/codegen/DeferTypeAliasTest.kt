package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeferTypeAliasTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun deferRunsAtFunctionExit() {
        assertEquals("start\nend\ncleanup1\ncleanup2", run("""
            import std.io
            func main() {
                std::println("start")
                defer {
                    std::println("cleanup2")
                }
                defer {
                    std::println("cleanup1")
                }
                std::println("end")
            }
        """.trimIndent()))
    }

    @Test fun deferRunsAfterReturn() {
        assertEquals("work\ncleanup\n42", run("""
            import std.io
            func doWork(): Int {
                defer {
                    std::println("cleanup")
                }
                std::println("work")
                return 42
            }
            func main() {
                std::println(doWork())
            }
        """.trimIndent()))
    }

    @Test fun deferLowersToJavaScriptRuntimeStack() {
        val result = Compiler().compile("""
            import std.io
            func main() {
                defer { std::println("cleanup") }
                std::println("body")
            }
        """.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(result)
        assertTrue("__az_defer.push" in result.javascript)
        assertTrue("finally" in result.javascript)
    }

    @Test fun typeAliasInt() {
        assertEquals("42", run("""
            import std.io
            typealias UserId = Int
            func main() {
                var id: UserId = 42
                std::println(id)
            }
        """.trimIndent()))
    }

    @Test fun typeAliasString() {
        assertEquals("hello", run("""
            import std.io
            typealias Name = String
            func main() {
                var n: Name = "hello"
                std::println(n)
            }
        """.trimIndent()))
    }
}
