package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

class ProgramTest {

    private fun compile(source: String, release: Boolean = true): CompilationResult.Success {
        val result = Compiler().compile(source, release = release)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    private fun run(source: String): String {
        val result = compile(source)
        return IrInterpreter().interpret(result.ir)
    }

    private fun expectFailure(source: String): List<String> {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Failure>(result, "Expected compilation failure but got success")
        return result.errors
    }

    // -----------------------------------------------------------------------
    // 1. helloWorld
    // -----------------------------------------------------------------------

    @Test
    fun helloWorld() {
        val result = compile("""
            import std.io
            func main() {
                std::println("Hello, world!")
            }
        """.trimIndent())

        val ir = result.ir.prettyPrint()
        assertTrue("println(\"Hello, world!\")" in ir)
    }

    // -----------------------------------------------------------------------
    // 2. helloWorldWithReturn
    // -----------------------------------------------------------------------

    @Test
    fun helloWorldWithReturn() {
        val result = compile("""
            import std.io
            func main(): Int {
                std::println("Hello")
                return 0
            }
        """.trimIndent())

        val ir = result.ir.prettyPrint()
        assertTrue("main" in ir, "Should emit a main function, got:\n$ir")
        assertTrue("return 0" in ir, "Should preserve the return, got:\n$ir")
    }

    // -----------------------------------------------------------------------
    // 3. arithmeticAndVariables
    // -----------------------------------------------------------------------

    @Test
    fun arithmeticAndVariables() {
        val output = run("""
            import std.io
            func main() {
                var x = 10
                fin y = 3
                let z = x + y
                std::println(z)
                x = x * 2
                std::println(x)
                std::println(x - y)
            }
        """.trimIndent())

        assertEquals("13\n20\n17", output)
    }

    // -----------------------------------------------------------------------
    // 4. functionCalls
    // -----------------------------------------------------------------------

    @Test
    fun functionCalls() {
        val output = run("""
            import std.io
            func double(n: Int): Int {
                return n * 2
            }

            func addOne(n: Int): Int {
                return n + 1
            }

            func main() {
                std::println(double(5))
                std::println(addOne(double(3)))
            }
        """.trimIndent())

        assertEquals("10\n7", output)
    }

    // -----------------------------------------------------------------------
    // 5. ifElse
    // -----------------------------------------------------------------------

    @Test
    fun ifElse() {
        val output = run("""
            import std.io
            func abs(n: Int): Int {
                if n < 0 {
                    return 0 - n
                } else {
                    return n
                }
            }

            func main() {
                std::println(abs(5))
                std::println(abs(-3))
            }
        """.trimIndent())

        assertEquals("5\n3", output)
    }

    // -----------------------------------------------------------------------
    // 6. stringOperations
    // -----------------------------------------------------------------------

    @Test
    fun stringOperations() {
        val output = run("""
            import std.io
            func main() {
                fin greeting = "Hello"
                fin name = "World"
                std::println(greeting + ", " + name + "!")
            }
        """.trimIndent())

        assertEquals("Hello, World!", output)
    }

    // -----------------------------------------------------------------------
    // 7. globalFin
    // -----------------------------------------------------------------------

    @Test
    fun globalFin() {
        val output = run("""
            import std.io
            fin x = 9

            func main() {
                std::println(x)
            }
        """.trimIndent())

        assertEquals("9", output)
    }

    // -----------------------------------------------------------------------
    // 8. scopeResolution
    // -----------------------------------------------------------------------

    @Test
    fun scopeResolution() {
        val output = run("""
            import std.io
            fin x = 9

            func main() {
                var x = 2
                std::println(x)
                std::println(::x)
                scope {
                    var x = 5
                    std::println(x)
                    std::println(::x)
                    std::println(::_::x)
                }
            }
        """.trimIndent())

        assertEquals("2\n9\n5\n2\n9", output)
    }

    // -----------------------------------------------------------------------
    // 9. friendRealm
    // -----------------------------------------------------------------------

    @Test
    fun friendRealm() {
        val output = run("""
            import std.io
            func main() {
                scope {
                    var shared = 10
                    std::println(shared)
                }
                scope {
                    shared = shared + 5
                    std::println(shared)
                }
            }
        """.trimIndent())

        assertEquals("10\n15", output)
    }

    // -----------------------------------------------------------------------
    // 10. releaseVsDebug
    // -----------------------------------------------------------------------

    @Test
    fun releaseVsDebug() {
        val source = """
            import std.io
            func unused() {
                std::println("never called")
            }

            func main() {
                std::println("hello")
            }
        """.trimIndent()

        // In release mode, unused function is eliminated
        val release = compile(source, release = true)
        val releaseFuncs = release.optimizedIr.functions.map { it.name }
        assertTrue("main" in releaseFuncs)
        assertFalse("unused" in releaseFuncs, "Unused function should be eliminated in release mode")

        // In debug mode, optimization is skipped - IR and optimizedIr are the same
        val debug = compile(source, release = false)
        val debugFuncs = debug.optimizedIr.functions.map { it.name }
        assertTrue("main" in debugFuncs)
        assertTrue("unused" in debugFuncs, "Unused function should be kept in debug mode")
    }

    // -----------------------------------------------------------------------
    // 11. topLevelVarRejected
    // -----------------------------------------------------------------------

    @Test
    fun topLevelVarRejected() {
        val errors = expectFailure("""
            import std.io
            var x = 5
            func main() {
                std::println(x)
            }
        """.trimIndent())
        assertTrue(errors.any { "not allowed" in it && "thread-safe" in it },
            "Should reject top-level var as not thread-safe, got: $errors")
    }

    // -----------------------------------------------------------------------
    // 12. redeclarationRejected
    // -----------------------------------------------------------------------

    @Test
    fun redeclarationRejected() {
        val errors = expectFailure("""
            import std.io
            func main() {
                var x = 1
                var x = 2
            }
        """.trimIndent())
        assertTrue(errors.any { "already declared" in it },
            "Should reject redeclaration in same scope, got: $errors")
    }
}
