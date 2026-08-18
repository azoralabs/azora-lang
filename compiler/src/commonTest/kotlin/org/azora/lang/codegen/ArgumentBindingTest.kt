package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * How a call's arguments reach their parameters.
 *
 * Three rules, and they must hold identically for free functions and methods:
 *
 *  1. a named argument takes *its* parameter, and any parameter it skips takes
 *     its default - it does not slide left into the gap;
 *  2. a parameter with a default may simply be omitted;
 *  3. a block written after the parentheses is the last parameter's argument,
 *     whatever else the call did or did not supply.
 *
 * Rule 1 previously produced a silently wrong call: `f(b: 5)` against
 * `f(a: Int = 1, b: Int = 2)` ran as `f(5, 2)`. Nothing failed - it simply
 * computed with the wrong arguments, which is why these are pinned.
 */
class ArgumentBindingTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source.trimIndent(), release = false)
        val success = assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(success.ir).trim()
    }

    @Test
    fun aNamedArgumentDoesNotSlideIntoAnEarlierGap() {
        val output = run(
            """
            import std.io

            func f(a: Int = 1, b: Int = 2): Int {
                return a * 10 + b
            }

            func main() {
                println(f(b: 5))
                println(f(a: 9))
                println(f())
                println(f(7))
            }
            """,
        )

        // `f(b: 5)` is a=1 (default), b=5. Reading the 5 positionally would
        // give 52 - the call that used to be generated.
        assertEquals("15\n92\n12\n72", output)
    }

    @Test
    fun methodsTakeDefaultsAndNamedArgumentsToo() {
        val output = run(
            """
            import std.io

            pack P { var n: Int }

            impl P {
                func f[self: Self&](a: Int = 1, b: Int = 2): Int {
                    return a * 10 + b
                }
            }

            func main() {
                fin p = P(0)
                println(p.f())
                println(p.f(7))
                println(p.f(b: 5))
                println(p.f(a: 9))
            }
            """,
        )

        assertEquals("12\n72\n15\n92", output)
    }

    @Test
    fun aTrailingBlockBindsToTheLastParameterNotTheFirstFreeOne() {
        val output = run(
            """
            import std.io

            func k(a: Int = 3, body: () -> Unit): Int {
                body()
                return a
            }

            func main() {
                println(k { println("ran") })
                println(k(7) { println("ran") })
            }
            """,
        )

        // Without the rule the block fills `a` and `body` is left missing.
        assertEquals("ran\n3\nran\n7", output)
    }

    @Test
    fun aTrailingBlockBindsAlongsideNamedArguments() {
        val output = run(
            """
            import std.io

            pack P { var n: Int }

            impl P {
                func build[self: Self&](
                    spacing: Double = 0.0,
                    padding: Double = 0.0,
                    body: () -> Unit,
                ): Double {
                    body()
                    return spacing * 100.0 + padding
                }
            }

            func main() {
                fin p = P(0)
                println(p.build(spacing: 8.0) { println("inner") })
            }
            """,
        )

        // `spacing` is named, `padding` defaults, and the block is `body` -
        // not `padding`, which is where a leftmost-free-slot rule would put it.
        assertEquals("inner\n800.0", output)
    }

    /** A method may introduce type parameters the type itself does not have. */
    @Test
    fun aMethodsOwnTypeParametersAreInScope() {
        val output = run(
            """
            import std.io

            pack P { var n: Int }

            impl P {
                func each<R>[self: Self&](label: String = "x", body: () -> R): String {
                    body()
                    return label
                }
            }

            func main() {
                fin p = P(0)
                println(p.each { 42 })
                println(p.each(label: "named") { "any type at all" })
            }
            """,
        )

        assertEquals("x\nnamed", output)
    }

    /** A lambda argument that is genuinely positional keeps its position. */
    @Test
    fun aFullyAppliedCallIsUnaffected() {
        val output = run(
            """
            import std.io

            func apply(body: () -> Unit, times: Int): Int {
                body()
                return times
            }

            func main() {
                println(apply({ println("once") }, 3))
            }
            """,
        )

        // Every parameter is supplied, so nothing is rebound - and the lambda
        // is not the last argument, so the trailing-block rule cannot apply.
        assertEquals("once\n3", output)
    }
}
