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
            func main() {
                inline for x in 0..<3 {
                    println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun inlineForUnrollsInclusiveRange() {
        assertEquals("1\n2\n3\n4", run("""
            func main() {
                inline for x in 1..4 {
                    println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun inlineForAccumulatesIntoRuntimeVar() {
        assertEquals("10", run("""
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
            deco Log {
                msg: String
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
            deco Cached { }
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

    @Test fun annotationWithUseSiteTarget() {
        assertEquals("ok", run("""
            @file:experimental
            func main() {
                println("ok")
            }
        """.trimIndent()))
    }

    @Test fun queryParameterAnnotationUsesTupleTypeShape() {
        assertEquals("7", run("""
            deco Query {}
            pack QueryCursor {
                var n: Int
            }
            pack LocalTransform {}
            pack Spin {}
            pack Paused {}
            pack Without<T> {}
            func spinSystem(
                world: ref Int,
                q: @Query (mut ref LocalTransform, ref Spin, Without<Paused>),
                dt: Real
            ): Int {
                return q.n
            }
            func main() {
                var world = 0
                fin q = QueryCursor(7)
                println(spinSystem(world, q, 0.0))
            }
        """.trimIndent()))
    }

    @Test fun queryParameterAnnotationRejectsAmpersandRefs() {
        assertFailsWith<IllegalStateException> {
            Compiler().compile("""
                deco Query {}
                pack QueryCursor {
                    var n: Int
                }
                pack LocalTransform {}
                pack Spin {}
                func bad(q: @Query (&LocalTransform, ref Spin)) {
                }
            """.trimIndent())
        }
    }

    @Test fun multipleAnnotationsOnOneDecl() {
        assertEquals("done", run("""
            deco A { }
            deco B { }
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

    // -- named zones as namespaces + :: resolution -------------------------

    @Test fun namedZoneNamespaceConstAndFunc() {
        assertEquals("314\n10", run("""
            zone Math {
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

    @Test fun nestedNamedZones() {
        assertEquals("42", run("""
            zone Outer {
                zone Inner {
                    fin VALUE = 42
                }
            }
            func main() {
                println(Outer::Inner::VALUE)
            }
        """.trimIndent()))
    }

    @Test fun namedZoneMemberReferencesAnotherMember() {
        assertEquals("25", run("""
            zone Geom {
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

    @Test fun anonymousZoneStillBlockScopes() {
        // Anonymous `zone { … }` keeps its existing block-scope meaning.
        assertEquals("7", run("""
            func main() {
                var x = 5
                zone {
                    var y = 2
                    x = x + y
                }
                println(x)
            }
        """.trimIndent()))
    }
}
