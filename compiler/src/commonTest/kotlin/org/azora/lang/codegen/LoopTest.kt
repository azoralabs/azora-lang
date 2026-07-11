package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for loops and ranges: `while`, `for x in a..b` / `..<`, `loop`,
 * `break`, and `continue`, executed through the [IrInterpreter] and lowered
 * to all backends.
 */
class LoopTest {

    private fun run(source: String, release: Boolean = false): String {
        val result = Compiler().compile(source, release = release)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test
    fun forInclusiveRange() {
        assertEquals("15", run("""
            func main() {
                var sum = 0
                for i in 1..5 {
                    sum = sum + i
                }
                println(sum)
            }
        """.trimIndent()))
    }

    @Test
    fun forExclusiveRange() {
        // 0 + 1 + 2 + 3 + 4 = 10
        assertEquals("10", run("""
            func main() {
                var sum = 0
                for i in 0..<5 {
                    sum = sum + i
                }
                println(sum)
            }
        """.trimIndent()))
    }

    @Test
    fun whileLoop() {
        assertEquals("15", run("""
            func main() {
                var sum = 0
                var i = 1
                while i <= 5 {
                    sum = sum + i
                    i = i + 1
                }
                println(sum)
            }
        """.trimIndent()))
    }

    @Test
    fun loopWithBreak() {
        assertEquals("3", run("""
            func main() {
                var i = 0
                loop {
                    i = i + 1
                    if i == 3 {
                        break
                    }
                }
                println(i)
            }
        """.trimIndent()))
    }

    @Test
    fun forWithBreak() {
        // stops at i == 5, sums 0..4 = 10
        assertEquals("10", run("""
            func main() {
                var sum = 0
                for i in 0..<10 {
                    if i == 5 {
                        break
                    }
                    sum = sum + i
                }
                println(sum)
            }
        """.trimIndent()))
    }

    @Test
    fun forWithContinue() {
        // skips i == 2: 0 + 1 + 3 + 4 = 8
        assertEquals("8", run("""
            func main() {
                var sum = 0
                for i in 0..<5 {
                    if i == 2 {
                        continue
                    }
                    sum = sum + i
                }
                println(sum)
            }
        """.trimIndent()))
    }

    @Test
    fun nestedLoops() {
        // outer 0..2, inner 0..2 → 9 iterations
        assertEquals("9", run("""
            func main() {
                var count = 0
                for i in 0..<3 {
                    for j in 0..<3 {
                        count = count + 1
                    }
                }
                println(count)
            }
        """.trimIndent()))
    }

    @Test
    fun returnInsideLoop() {
        // Returns 4 when the loop variable reaches 4, proving `return`
        // propagates out of the loop and function.
        assertEquals("4", run("""
            func firstAt(limit: Int): Int {
                for i in 0..<limit {
                    if i == 4 {
                        return i
                    }
                }
                return 0
            }
            func main() {
                println(firstAt(10))
            }
        """.trimIndent()))
    }

    @Test
    fun loopsSurviveOptimization() {
        // Same as forInclusiveRange but with the optimizer enabled.
        assertEquals("15", run("""
            func main() {
                var sum = 0
                for i in 1..5 {
                    sum = sum + i
                }
                println(sum)
            }
        """.trimIndent(), release = true))
    }

    @Test
    fun loopsLowerToAllBackends() {
        val result = Compiler().compile("""
            func main() {
                for i in 0..<3 {
                    println(i)
                }
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        // JavaScript backend emits a classic for loop with `++`
        assertTrue("for (let" in result.javascript && "++" in result.javascript, "JavaScript should emit a for loop, got:\n${result.javascript}")
        // LLVM backend emits loop labels
        assertTrue("for_cond" in result.llvm || "br label" in result.llvm, "LLVM should emit loop branches, got:\n${result.llvm}")
    }
}
