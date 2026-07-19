package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tier 2 — types & metaprogramming features ported from azora-lang-old.
 *
 * Currently covers `inline for` compile-time loop unrolling.
 */
class Tier2MetaprogrammingTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun inlineForUnrollsExclusiveRange() {
        assertEquals("0\n1\n2", run("""
            import std.io
            func main() {
                inline for x in 0..<3 {
                    std::println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun inlineForUnrollsInclusiveRange() {
        assertEquals("1\n2\n3\n4", run("""
            import std.io
            func main() {
                inline for x in 1..4 {
                    std::println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun inlineForAccumulatesIntoRuntimeVar() {
        assertEquals("10", run("""
            import std.io
            func main() {
                var sum = 0
                inline for x in 1..4 {
                    sum = sum + x
                }
                std::println(sum)
            }
        """.trimIndent()))
    }

    @Test fun inlineFromBodyUsesLoopVarInCompileTimeExpr() {
        // The loop var feeds an `inline fin`, which is folded per iteration.
        assertEquals("2\n4\n6", run("""
            import std.io
            func main() {
                inline for x in 1..3 {
                    inline fin doubled = x * 2
                    std::println(doubled)
                }
            }
        """.trimIndent()))
    }

    @Test fun inlineForBoundsFromCompileTimeVar() {
        assertEquals("0\n1\n2\n3\n4", run("""
            import std.io
            func main() {
                inline fin count = 5
                inline for x in 0..<count {
                    std::println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun inlineForDoesNotLeakLoopVar() {
        // After the unrolled loop, `x` must not be substituted into later code.
        assertEquals("0\n1\n2\n99", run("""
            import std.io
            func main() {
                inline for x in 0..<3 {
                    std::println(x)
                }
                var x = 99
                std::println(x)
            }
        """.trimIndent()))
    }

    // -- deco decorators + @annotations ------------------------------------

    @Test fun decoDeclarationAndAnnotatedFunc() {
        assertEquals("hi", run("""
            import std.io
            deco Log {
                fin msg: String
            }
            @Log("entry")
            func greet(): String {
                return "hi"
            }
            func main() {
                std::println(greet())
            }
        """.trimIndent()))
    }

    @Test fun annotationOnVarAndPack() {
        assertEquals("3\n5", run("""
            import std.io
            deco Cached { }
            deco Deprecated { }
            @Cached
            fin PI = 3
            @Deprecated
            pack P {
                var x: Int
            }
            func main() {
                std::println(PI)
                var p = P(5)
                std::println(p.x)
            }
        """.trimIndent()))
    }

    @Test fun multipleAnnotationsOnOneDecl() {
        assertEquals("done", run("""
            import std.io
            deco A { }
            deco B { }
            @A
            @B
            func run(): String {
                return "done"
            }
            func main() {
                std::println(run())
            }
        """.trimIndent()))
    }

    // -- named zones as namespaces + :: resolution -------------------------

    @Test fun namedZoneNamespaceConstAndFunc() {
        assertEquals("314\n10", run("""
            import std.io
            zone Math {
                fin PI = 314
                func double(x: Int): Int {
                    return x * 2
                }
            }
            func main() {
                std::println(Math::PI)
                std::println(Math::double(5))
            }
        """.trimIndent()))
    }

    @Test fun nestedNamedZones() {
        assertEquals("42", run("""
            import std.io
            zone Outer {
                zone Inner {
                    fin VALUE = 42
                }
            }
            func main() {
                std::println(Outer::Inner::VALUE)
            }
        """.trimIndent()))
    }

    @Test fun namedZoneMemberReferencesAnotherMember() {
        assertEquals("25", run("""
            import std.io
            zone Geom {
                fin R = 5
                func area(): Int {
                    return Geom::R * Geom::R
                }
            }
            func main() {
                std::println(Geom::area())
            }
        """.trimIndent()))
    }

    @Test fun anonymousZoneStillBlockScopes() {
        // Anonymous `zone { … }` keeps its existing block-scope meaning.
        assertEquals("7", run("""
            import std.io
            func main() {
                var x = 5
                zone {
                    var y = 2
                    x = x + y
                }
                std::println(x)
            }
        """.trimIndent()))
    }
}
