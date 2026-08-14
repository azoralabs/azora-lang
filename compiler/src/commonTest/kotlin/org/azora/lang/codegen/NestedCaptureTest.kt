package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Captures that cross more than one closure boundary.
 *
 * A closure can only hand on what it captured itself, so a value read two
 * lambdas deep has to be captured at *both* boundaries. Two independent places
 * assumed one was enough:
 *
 *  - the resolver recorded `[; &]` on the innermost frame only, so the outer
 *    closure never learned it had to take the binding;
 *  - the LLVM backend's free-variable scan skipped nested lambda bodies, so the
 *    outer closure's environment was built without them.
 *
 * Either one alone leaves the inner environment naming a binding nothing
 * supplied. Natively that lowered to a read of an undefined module global -
 * a link error when the name survived, and silently nothing when it did not.
 * The interpreter resolved names dynamically and was unaffected, which is why
 * this only showed up in a native build.
 */
class NestedCaptureTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source.trimIndent(), release = false)
        val success = assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(success.ir).trim()
    }

    @Test
    fun aValueReadTwoClosuresDeepIsCapturedAtBothBoundaries() {
        val output = run(
            """
            import std.io

            func run<R>(body: () -> R) { body() }

            func outer(h: std::Double) {
                run [; &] {
                    run [; &] {
                        std::println(h)
                    }
                }
            }

            func main() { outer(2.5) }
            """,
        )

        assertEquals("2.5", output)
    }

    /** The outer block never reads it itself - only the nested one does. */
    @Test
    fun anOuterBlockCapturesWhatOnlyItsNestedBlockReads() {
        val output = run(
            """
            import std.io

            func run<R>(body: () -> R) { body() }

            func outer(label: std::String, n: std::Int) {
                run [; &] {
                    run [; &] {
                        var i = 0
                        while i < n {
                            std::println(label)
                            i = i + 1
                        }
                    }
                }
            }

            func main() { outer("x", 2) }
            """,
        )

        assertEquals("x\nx", output)
    }

    /** Sibling blocks: a later one may be the only reader. */
    @Test
    fun aLaterSiblingBlockStillReachesTheOuterBinding() {
        val output = run(
            """
            import std.io

            func run<R>(body: () -> R) { body() }

            func outer(h: std::Double) {
                run [; &] {
                    run [; &] { std::println("first") }
                    run [; &] { std::println(h) }
                }
            }

            func main() { outer(7.5) }
            """,
        )

        assertEquals("first\n7.5", output)
    }

    /** A name the inner lambda binds itself is not free, and is not captured. */
    @Test
    fun anInnerBindingShadowsRatherThanCaptures() {
        val output = run(
            """
            import std.io

            func run<R>(body: () -> R) { body() }

            func outer(h: std::Int) {
                run [; &] {
                    run [; &] {
                        var h = 99
                        std::println(h)
                    }
                }
            }

            func main() { outer(1) }
            """,
        )

        assertEquals("99", output)
    }

    /** Three levels: every boundary in the chain takes the binding. */
    @Test
    fun captureChainsThroughThreeLevels() {
        val output = run(
            """
            import std.io

            func run<R>(body: () -> R) { body() }

            func outer(h: std::Int) {
                run [; &] {
                    run [; &] {
                        run [; &] {
                            std::println(h)
                        }
                    }
                }
            }

            func main() { outer(3) }
            """,
        )

        assertEquals("3", output)
    }
}
