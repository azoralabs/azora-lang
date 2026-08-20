package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tier 2 - types & metaprogramming features ported from azora-lang-old.
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
                    println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun inlineForUnrollsInclusiveRange() {
        assertEquals("1\n2\n3\n4", run("""
            import std.io
            func main() {
                inline for x in 1..4 {
                    println(x)
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
                println(sum)
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
                    println(doubled)
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
                    println(x)
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
                    println(x)
                }
                var x = 99
                println(x)
            }
        """.trimIndent()))
    }

    // -- deco decorators + @annotations ------------------------------------

    @Test fun decoDeclarationAndAnnotatedFunc() {
        assertEquals("hi", run("""
            import std.io
            annot Log {
                fin msg: String
            }
            @Log("entry")
            func greet(): String {
                return "hi"
            }
            func main() {
                println(greet())
            }
        """.trimIndent()))
    }

    @Test fun annotationOnVarAndPack() {
        assertEquals("3\n5", run("""
            import std.io
            annot Cached { }
            annot Deprecated { }
            @Cached
            fin PI = 3
            @Deprecated
            pack P {
                var x: Int
            }
            func main() {
                println(PI)
                var p = P(5)
                println(p.x)
            }
        """.trimIndent()))
    }

    @Test fun multipleAnnotationsOnOneDecl() {
        assertEquals("done", run("""
            import std.io
            annot A { }
            annot B { }
            @A
            @B
            func run(): String {
                return "done"
            }
            func main() {
                println(run())
            }
        """.trimIndent()))
    }

    // -- named scopes as namespaces + :: resolution -------------------------

    @Test fun namedScopeNamespaceConstAndFunc() {
        assertEquals("314\n10", run("""
            import std.io
            scope Math {
                fin PI = 314
                func double(x: Int): Int {
                    return x * 2
                }
            }
            func main() {
                println(Math::PI)
                println(Math::double(5))
            }
        """.trimIndent()))
    }

    @Test fun nestedNamedScopes() {
        assertEquals("42", run("""
            import std.io
            scope Outer {
                scope Inner {
                    fin VALUE = 42
                }
            }
            func main() {
                println(Outer::Inner::VALUE)
            }
        """.trimIndent()))
    }

    @Test fun namedScopeMemberReferencesAnotherMember() {
        assertEquals("25", run("""
            import std.io
            scope Geom {
                fin R = 5
                func area(): Int {
                    return Geom::R * Geom::R
                }
            }
            func main() {
                println(Geom::area())
            }
        """.trimIndent()))
    }

    @Test fun anAnonymousScopeStillIntroducesABlock() {
        // Anonymous `scope { … }` keeps its existing block-scope meaning.
        assertEquals("7", run("""
            import std.io
            func main() {
                var x = 5
                scope {
                    var y = 2
                    x = x + y
                }
                println(x)
            }
        """.trimIndent()))
    }
}
