package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `effect <condition> { … }` - rising-edge effects.
 *
 * `REACTIVE_ECS_UI.MD` §11.1: evaluate the condition in the owner, run the body
 * when it goes false→true, do not repeat while it stays true, re-arm when it
 * goes false again, and never treat it as a polling loop.
 *
 * A bare name keeps its old meaning - `effect count { … }` is a *dependency*,
 * saying when to reconsider, not whether to act. One spelling cannot be both.
 */
class ConditionalEffectTest {
    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source.trimIndent(), release = false)
        return assertIs(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
    }

    private fun run(source: String): String =
        IrInterpreter().interpret(compile(source).ir).trim()

    @Test
    fun theBodyRunsOnceWhenTheConditionBecomesTrue() {
        val output = run(
            """
            import std.io

            react func gate(step: Int) {
                remember var open = false
                effect open == true {
                    println("fired at ${'$'}{step}")
                }
                if step == 2 { open = true }
            }

            react func main() {
                var i = 0
                while i < 6 {
                    gate(i)
                    i = i + 1
                }
            }
            """,
        )

        // `open` is set after the effect is evaluated, so the transition is seen
        // on the next invocation - and only once, though it stays true after.
        assertEquals("fired at 3", output)
    }

    @Test
    fun theEffectReArmsAfterTheConditionGoesFalse() {
        val output = run(
            """
            import std.io

            react func gate(step: Int) {
                remember var open = false
                effect open == true {
                    println("fired at ${'$'}{step}")
                }
                if step == 1 { open = true }
                if step == 3 { open = false }
                if step == 4 { open = true }
            }

            react func main() {
                var i = 0
                while i < 7 {
                    gate(i)
                    i = i + 1
                }
            }
            """,
        )

        assertEquals("fired at 2\nfired at 5", output)
    }

    @Test
    fun aConditionThatIsNeverTrueNeverFires() {
        val output = run(
            """
            import std.io

            react func gate() {
                remember var open = false
                effect open == true { println("should not appear") }
            }

            react func main() {
                gate()
                gate()
                println("done")
            }
            """,
        )

        assertEquals("done", output)
    }

    /** A bare name is a dependency, unchanged: it says when, not whether. */
    @Test
    fun aBareNameRemainsADependencyNotACondition() {
        val output = run(
            """
            import std.io

            react func watcher() {
                remember var count = 0
                effect count { println("count=${'$'}{count}") }
                count += 1
            }

            react func main() {
                watcher()
                watcher()
            }
            """,
        )

        // Runs on every invocation and again on each write - never gated on the
        // value being "true".
        assertEquals("count=0\ncount=1\ncount=1\ncount=2", output)
    }

    /** A comparison against something other than a literal is still a condition. */
    @Test
    fun anyExpressionFormIsTreatedAsACondition() {
        val output = run(
            """
            import std.io

            react func gate(step: Int) {
                remember var total = 0
                effect total > 2 { println("crossed at ${'$'}{step}") }
                total = total + 1
            }

            react func main() {
                var i = 0
                while i < 8 {
                    gate(i)
                    i = i + 1
                }
            }
            """,
        )

        // total reaches 3 during invocation 2, so invocation 3 is the first that
        // sees the condition hold - and it fires only there.
        assertEquals("crossed at 3", output)
    }
}
