package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SmallBatchTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun bitwiseAnd() {
        assertEquals("10", run("func main() { println(0b1110 & 0b1010) }"))
    }

    @Test fun bitwiseOr() {
        assertEquals("14", run("func main() { println(0b1000 | 0b0110) }"))
    }

    @Test fun bitwiseXor() {
        assertEquals("6", run("func main() { println(0b1100 ^ 0b1010) }"))
    }

    @Test fun bitwiseNot() {
        assertEquals("-11", run("func main() { println(~10) }"))
    }

    @Test fun shiftLeft() {
        assertEquals("20", run("func main() { println(5 << 2) }"))
    }

    @Test fun shiftRight() {
        assertEquals("2", run("func main() { println(10 >> 2) }"))
    }

    @Test fun increment() {
        assertEquals("6", run("func main() { var x = 5\n x++\n println(x) }"))
    }

    @Test fun decrement() {
        assertEquals("4", run("func main() { var x = 5\n x--\n println(x) }"))
    }

    @Test fun integerPromotion() {
        assertEquals("3.5", run("func main() { println(2 + 1.5) }"))
    }

    @Test fun integerPromotionMixed() {
        assertEquals("10", run("func main() { println(5L + 5) }"))
    }

    @Test fun guardCondition() {
        assertEquals("ok", run("""
            func check(x: Int): String {
                guard x > 0 else {
                    return "bad"
                }
                return "ok"
            }
            func main() { println(check(5)) }
        """.trimIndent()))
    }

    @Test fun guardConditionFails() {
        assertEquals("bad", run("""
            func check(x: Int): String {
                guard x > 0 else {
                    return "bad"
                }
                return "ok"
            }
            func main() { println(check(-1)) }
        """.trimIndent()))
    }

    @Test fun bitwiseInExpression() {
        // (1 | 2) & 7 = 3 & 7 = 3
        assertEquals("3", run("func main() { println((1 | 2) & 7) }"))
    }
}
