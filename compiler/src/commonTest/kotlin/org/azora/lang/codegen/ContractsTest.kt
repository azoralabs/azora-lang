package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContractsTest {

    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return result
    }

    private fun run(source: String): String =
        IrInterpreter().interpret(compile(source).ir).trim()

    @Test
    fun inOutZoneContractsRunOnSuccess() {
        assertEquals("0\n5\n10", run("""
            func clamp(x: Int, lo: Int, hi: Int): Int
            in {
                assert lo <= hi { "lo must be <= hi" }
            } out { r ->
                assert r >= lo { "result must be >= lo" }
                assert r <= hi { "result must be <= hi" }
            } zone {
                if x < lo { return lo }
                if x > hi { return hi }
                return x
            }

            func main() {
                println(clamp(-5, 0, 10))
                println(clamp(5, 0, 10))
                println(clamp(50, 0, 10))
            }
        """.trimIndent()))
    }

    @Test
    fun preconditionFailureStopsBeforeBody() {
        val failure = assertFailsWith<IllegalStateException> {
            run("""
                func value(x: Int): Int
                in {
                    assert x > 0 { "x must be positive" }
                } zone {
                    return x
                }
                func main() { println(value(0)) }
            """.trimIndent())
        }
        assertTrue(failure.message.orEmpty().contains("x must be positive"))
    }

    @Test
    fun postconditionFailureSeesResultValue() {
        val failure = assertFailsWith<IllegalStateException> {
            run("""
                func value(): Int
                out { r ->
                    assert r > 10 { "result too small" }
                } zone {
                    return 3
                }
                func main() { println(value()) }
            """.trimIndent())
        }
        assertTrue(failure.message.orEmpty().contains("result too small"))
    }

    @Test
    fun postconditionRunsForNestedBranchReturns() {
        assertEquals("12\n20", run("""
            func choose(flag: Bool): Int
            out { r ->
                assert r >= 10 { "branch result too small" }
            } zone {
                if flag {
                    return 12
                } else {
                    return 20
                }
            }
            func main() {
                println(choose(true))
                println(choose(false))
            }
        """.trimIndent()))
    }
}
