package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

class RealmTest {

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
    fun friendRealm_sharedScope() {
        val output = run("""
            import std.io
            fin x = 9

            func main() {
                var x = 2
                var y = 3
                std::println(x)
                std::println(::x)
                scope {
                    var x = 5
                    std::println(x)
                    std::println(::x)
                    std::println(::_::x)
                }
                std::println("----")
                scope {
                    std::println(x)
                }
            }
        """.trimIndent())

        assertEquals("2\n9\n5\n2\n9\n----\n5", output)
    }

    @Test
    fun friendRealm_multipleDeclarations() {
        val output = run("""
            import std.io
            func main() {
                scope {
                    var a = 10
                    var b = 20
                }
                scope {
                    std::println(a)
                    std::println(b)
                }
            }
        """.trimIndent())

        assertEquals("10\n20", output)
    }

    @Test
    fun friendRealm_mutation() {
        val output = run("""
            import std.io
            func main() {
                scope {
                    var x = 1
                    std::println(x)
                }
                scope {
                    x = 99
                    std::println(x)
                }
            }
        """.trimIndent())

        assertEquals("1\n99", output)
    }

    @Test
    fun friendRealm_notVisibleOutside() {
        val output = run("""
            import std.io
            func main() {
                var x = 42
                scope {
                    var x = 7
                }
                std::println(x)
            }
        """.trimIndent())

        // Parent x should still be 42 — realm's x is separate
        assertEquals("42", output)
    }

    @Test
    fun scope_siblingBlocksShareOneScope() {
        val output = run("""
            import std.io
            func main() {
                var x = 1
                scope {
                    var x = 2
                    std::println(x)
                }
                scope {
                    std::println(x)
                }
            }
        """.trimIndent())

        // Sibling realms share one scope, so the second sees the binding the
        // first made. That sharing is the whole point of a realm — otherwise it
        // would just be an extra pair of braces.
        assertEquals("2\n2", output)
    }

    @Test
    fun scope_bindingsDoNotReachTheCodeBetweenBlocks() {
        val output = run("""
            import std.io
            func main() {
                var x = 1
                scope {
                    var inner = 9
                    std::println(inner)
                }
                std::println(x)
            }
        """.trimIndent())

        // Ordinary code between realms does not see what a realm declared.
        assertEquals("9\n1", output)
    }

    @Test
    fun scopeResolution_withRealmsAndGlobals() {
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
                std::println("----")
            }
        """.trimIndent())

        assertEquals("2\n9\n5\n2\n9\n----", output)
    }
}
