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
            use std.io
            fin x = 9

            func main() {
                var x = 2
                var y = 3
                std::println(x)
                std::println(::x)
                zone {
                    var x = 5
                    std::println(x)
                    std::println(::x)
                    std::println(::_::x)
                }
                std::println("----")
                zone {
                    std::println(x)
                }
            }
        """.trimIndent())

        assertEquals("2\n9\n5\n2\n9\n----\n5", output)
    }

    @Test
    fun friendZone_multipleDeclarations() {
        val output = run("""
            use std.io
            func main() {
                zone {
                    var a = 10
                    var b = 20
                }
                zone {
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
            use std.io
            func main() {
                zone {
                    var x = 1
                    std::println(x)
                }
                zone {
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
            use std.io
            func main() {
                var x = 42
                zone {
                    var x = 7
                }
                std::println(x)
            }
        """.trimIndent())

        // Parent x should still be 42 — zone's x is separate
        assertEquals("42", output)
    }

    @Test
    fun zone_siblingBlocksShareOneScope() {
        val output = run("""
            use std.io
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

        // Sibling zones share one scope, so the second sees the binding the
        // first made. That sharing is the whole point of a zone — otherwise it
        // would just be an extra pair of braces.
        assertEquals("2\n2", output)
    }

    @Test
    fun zone_bindingsDoNotReachTheCodeBetweenBlocks() {
        val output = run("""
            use std.io
            func main() {
                var x = 1
                zone {
                    var inner = 9
                    std::println(inner)
                }
                std::println(x)
            }
        """.trimIndent())

        // Ordinary code between zones does not see what a zone declared.
        assertEquals("9\n1", output)
    }

    @Test
    fun scopeResolution_withZonesAndGlobals() {
        val output = run("""
            use std.io
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
