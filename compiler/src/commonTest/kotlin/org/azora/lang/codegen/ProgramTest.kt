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
            func main() {
                println("Hello, world!")
            }
        """.trimIndent())

        val ir = result.ir.prettyPrint()
        assertTrue("println(\"Hello, world!\")" in ir)

        val kotlin = result.kotlin
        assertTrue("fun main(): Unit" in kotlin)
        assertTrue("println(\"Hello, world!\")" in kotlin)

        val ts = result.typescript
        assertTrue("function main(): void" in ts)
        assertTrue("console.log(\"Hello, world!\")" in ts)
        assertTrue(ts.trimEnd().endsWith("main()"), "TypeScript should append main() call")
    }

    // -----------------------------------------------------------------------
    // 2. helloWorldWithReturn
    // -----------------------------------------------------------------------

    @Test
    fun helloWorldWithReturn() {
        val result = compile("""
            func main(): Int {
                println("Hello")
                return 0
            }
        """.trimIndent())

        val kotlin = result.kotlin
        // Kotlin backend should emit __azora_main wrapper
        assertTrue("fun __azora_main(): Int" in kotlin, "Should have __azora_main wrapper, got:\n$kotlin")
        assertTrue("fun main(): Unit" in kotlin, "Should have Unit main entry point, got:\n$kotlin")
        assertTrue("__azora_main()" in kotlin, "main should call __azora_main, got:\n$kotlin")
    }

    // -----------------------------------------------------------------------
    // 3. arithmeticAndVariables
    // -----------------------------------------------------------------------

    @Test
    fun arithmeticAndVariables() {
        val output = run("""
            func main() {
                var x = 10
                fin y = 3
                let z = x + y
                println(z)
                x = x * 2
                println(x)
                println(x - y)
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
            func double(n: Int): Int {
                return n * 2
            }

            func addOne(n: Int): Int {
                return n + 1
            }

            func main() {
                println(double(5))
                println(addOne(double(3)))
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
            func abs(n: Int): Int {
                if n < 0 {
                    return 0 - n
                } else {
                    return n
                }
            }

            func main() {
                println(abs(5))
                println(abs(-3))
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
            func main() {
                fin greeting = "Hello"
                fin name = "World"
                println(greeting + ", " + name + "!")
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
            fin x = 9

            func main() {
                println(x)
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
            fin x = 9

            func main() {
                var x = 2
                println(x)
                println(::x)
                zone {
                    var x = 5
                    println(x)
                    println(::x)
                    println(::_::x)
                }
            }
        """.trimIndent())

        assertEquals("2\n9\n5\n2\n9", output)
    }

    // -----------------------------------------------------------------------
    // 9. friendZone
    // -----------------------------------------------------------------------

    @Test
    fun friendZone() {
        val output = run("""
            func main() {
                friend zone {
                    var shared = 10
                    println(shared)
                }
                friend zone {
                    shared = shared + 5
                    println(shared)
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
            func unused() {
                println("never called")
            }

            func main() {
                println("hello")
            }
        """.trimIndent()

        // In release mode, unused function is eliminated
        val release = compile(source, release = true)
        val releaseFuncs = release.optimizedIr.functions.map { it.name }
        assertTrue("main" in releaseFuncs)
        assertFalse("unused" in releaseFuncs, "Unused function should be eliminated in release mode")

        // In debug mode, optimization is skipped — IR and optimizedIr are the same
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
            var x = 5
            func main() {
                println(x)
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
            func main() {
                var x = 1
                var x = 2
            }
        """.trimIndent())
        assertTrue(errors.any { "already declared" in it },
            "Should reject redeclaration in same scope, got: $errors")
    }
}
