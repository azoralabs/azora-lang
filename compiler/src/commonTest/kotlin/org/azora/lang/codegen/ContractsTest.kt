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
    fun aFunctionHasAtMostOneInAndOneOutClause() {
        // Several conditions go inside the one clause; a second clause would
        // split one requirement across two places for no gain.
        val twoIn = Compiler().compile(
            """
            func f(a: Int): Int
            in { assert a > 0 { "a" } } in { assert a < 9 { "b" } } scope { return a }
            func main() {}
            """.trimIndent(),
        )
        assertIs<CompilationResult.Failure>(twoIn)
        assertTrue(twoIn.errors.any { "one 'in' contract" in it }, twoIn.errors.toString())

        val twoOut = Compiler().compile(
            """
            func f(a: Int): Int
            out { assert it > 0 { "a" } } out { assert it < 9 { "b" } } scope { return a }
            func main() {}
            """.trimIndent(),
        )
        assertIs<CompilationResult.Failure>(twoOut)
        assertTrue(twoOut.errors.any { "one 'out' contract" in it }, twoOut.errors.toString())
    }

    @Test
    fun inOutScopeContractsRunOnSuccess() {
        assertEquals("0\n5\n10", run("""
            import std.io
            func clamp(x: Int, lo: Int, hi: Int): Int
            in {
                assert lo <= hi { "lo must be <= hi" }
            } out { r ->
                assert r >= lo { "result must be >= lo" }
                assert r <= hi { "result must be <= hi" }
            } scope {
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
                import std.io
                func value(x: Int): Int
                in {
                    assert x > 0 { "x must be positive" }
                } scope {
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
                import std.io
                func value(): Int
                out { r ->
                    assert r > 10 { "result too small" }
                } scope {
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
            import std.io
            func choose(flag: Bool): Int
            out { r ->
                assert r >= 10 { "branch result too small" }
            } scope {
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

    @Test
    fun computedPropertiesSupportContracts() {
        assertEquals("7", run("""
            import std.io
            pack Counter { var value: Int }
            impl Counter {
                prop current[self: Self&]: Int
                in {
                    assert self.value >= 0 { "counter must not be negative" }
                } out { result ->
                    assert result == self.value { "property returned stale data" }
                } scope {
                    return self.value
                }
            }
            func main() {
                fin counter = Counter(7)
                println(counter.current)
            }
        """.trimIndent()))
    }

    @Test
    fun asyncFuncSupportsContracts() {
        compile("""
            async func load(value: Int): Int
            in {
                assert value >= 0 { "task input must be non-negative" }
            } out { result ->
                assert result >= 0 { "task result must be non-negative" }
            } scope {
                return value
            }

            func main() {}
        """.trimIndent())
    }
}
