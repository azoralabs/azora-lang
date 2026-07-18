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
            import std.io
            fin x = 9

            func main() {
                var x = 2
                var y = 3
                std::println(x)
                std::println(::x)
                friend zone {
                    var x = 5
                    std::println(x)
                    std::println(::x)
                    std::println(::_::x)
                }
                std::println("----")
                friend zone {
                    std::println(x)
                }
            }
        """.trimIndent())

        assertEquals("2\n9\n5\n2\n9\n----\n5", output)
    }

    @Test
    fun friendZone_multipleDeclarations() {
        val output = run("""
            import std.io
            func main() {
                friend zone {
                    var a = 10
                    var b = 20
                }
                friend zone {
                    std::println(a)
                    std::println(b)
                }
            }
        """.trimIndent())

        assertEquals("10\n20", output)
    }

    @Test
    fun friendZone_mutation() {
        val output = run("""
            import std.io
            func main() {
                friend zone {
                    var x = 1
                    std::println(x)
                }
                friend zone {
                    x = 99
                    std::println(x)
                }
            }
        """.trimIndent())

        assertEquals("1\n99", output)
    }

    @Test
    fun friendZone_notVisibleOutside() {
        val output = run("""
            import std.io
            func main() {
                var x = 42
                friend zone {
                    var x = 7
                }
                std::println(x)
            }
        """.trimIndent())

        // Parent x should still be 42 — friend zone's x is separate
        assertEquals("42", output)
    }

    @Test
    fun zone_regularScopesAreIndependent() {
        val output = run("""
            import std.io
            func main() {
                var x = 1
                zone {
                    var x = 2
                    std::println(x)
                }
                zone {
                    std::println(x)
                }
            }
        """.trimIndent())

        // Second zone sees parent x=1, NOT the first zone's x=2
        assertEquals("2\n1", output)
    }

    @Test
    fun scopeResolution_withZonesAndGlobals() {
        val output = run("""
            import std.io
            fin x = 9

            func main() {
                var x = 2
                std::println(x)
                std::println(::x)
                zone {
                    var x = 5
                    std::println(x)
                    std::println(::x)
                    std::println(::_::x)
                }
                std::println("----")
            }
        """.trimIndent())

        assertEquals("2\n9\n5\n2\n9\n----", output)
    }
}
