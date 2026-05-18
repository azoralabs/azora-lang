package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

class ZoneTest {

    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    private fun run(source: String): String {
        val result = compile(source)
        return IrInterpreter().interpret(result.ir)
    }

    @Test
    fun friendZone_sharedScope() {
        val output = run("""
            fin x = 9

            func main() {
                var x = 2
                var y = 3
                println(x)
                println(::x)
                friend zone {
                    var x = 5
                    println(x)
                    println(::x)
                    println(::_::x)
                }
                println("----")
                friend zone {
                    println(x)
                }
            }
        """.trimIndent())

        assertEquals("2\n9\n5\n2\n9\n----\n5", output)
    }

    @Test
    fun friendZone_multipleDeclarations() {
        val output = run("""
            func main() {
                friend zone {
                    var a = 10
                    var b = 20
                }
                friend zone {
                    println(a)
                    println(b)
                }
            }
        """.trimIndent())

        assertEquals("10\n20", output)
    }

    @Test
    fun friendZone_mutation() {
        val output = run("""
            func main() {
                friend zone {
                    var x = 1
                    println(x)
                }
                friend zone {
                    x = 99
                    println(x)
                }
            }
        """.trimIndent())

        assertEquals("1\n99", output)
    }

    @Test
    fun friendZone_notVisibleOutside() {
        val output = run("""
            func main() {
                var x = 42
                friend zone {
                    var x = 7
                }
                println(x)
            }
        """.trimIndent())

        // Parent x should still be 42 — friend zone's x is separate
        assertEquals("42", output)
    }

    @Test
    fun zone_regularScopesAreIndependent() {
        val output = run("""
            func main() {
                var x = 1
                zone {
                    var x = 2
                    println(x)
                }
                zone {
                    println(x)
                }
            }
        """.trimIndent())

        // Second zone sees parent x=1, NOT the first zone's x=2
        assertEquals("2\n1", output)
    }

    @Test
    fun scopeResolution_withZonesAndGlobals() {
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
                println("----")
            }
        """.trimIndent())

        assertEquals("2\n9\n5\n2\n9\n----", output)
    }
}
