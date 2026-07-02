package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tier 4 — concurrency. Starting with `flow`/`yield` generators.
 *
 * A `flow` generator runs its body when called, collecting `yield`ed values into a
 * list (eager evaluation), and returns that list — so it composes with `for x in …`.
 */
class Tier4ConcurrencyTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun flowYieldsValuesIteratedByForLoop() {
        assertEquals("0\n1\n4\n9", run("""
            flow squares(n: Int): Int {
                for i in 0..<n {
                    yield i * i
                }
            }
            func main() {
                for x in squares(4) {
                    println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun flowResultIsAList() {
        assertEquals("3", run("""
            flow upto(n: Int): Int {
                var i = 0
                while i < n {
                    yield i
                    i++
                }
            }
            func main() {
                var nums = upto(3)
                println(nums.length)
            }
        """.trimIndent()))
    }

    @Test fun flowWithConditionalYield() {
        assertEquals("0\n2\n4", run("""
            flow evens(): Int {
                for i in 0..<6 {
                    if i % 2 == 0 {
                        yield i
                    }
                }
            }
            func main() {
                for x in evens() {
                    println(x)
                }
            }
        """.trimIndent()))
    }

    @Test fun flowCanTakeArguments() {
        assertEquals("3\n4\n5", run("""
            flow rangeFrom(a: Int, b: Int): Int {
                for i in a..<b {
                    yield i
                }
            }
            func main() {
                for x in rangeFrom(3, 6) {
                    println(x)
                }
            }
        """.trimIndent()))
    }
}
