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
            import std.io
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
                std::println(clamp(-5, 0, 10))
                std::println(clamp(5, 0, 10))
                std::println(clamp(50, 0, 10))
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
                } zone {
                    return x
                }
                func main() { std::println(value(0)) }
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
                } zone {
                    return 3
                }
                func main() { std::println(value()) }
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
            } zone {
                if flag {
                    return 12
                } else {
                    return 20
                }
            }
            func main() {
                std::println(choose(true))
                std::println(choose(false))
            }
        """.trimIndent()))
    }

    @Test
    fun computedPropertiesSupportContracts() {
        assertEquals("7", run("""
            import std.io
            pack Counter { var value: Int }
            impl Counter {
                prop current: Int
                in {
                    assert self.value >= 0 { "counter must not be negative" }
                } out { result ->
                    assert result == self.value { "property returned stale data" }
                } zone {
                    return self.value
                }
            }
            func main() {
                fin counter = Counter(7)
                std::println(counter.current)
            }
        """.trimIndent()))
    }

    @Test
    fun taskAndFlowSupportContracts() {
        compile("""
            task load(value: Int): Int
            in {
                assert value >= 0 { "task input must be non-negative" }
            } out { result ->
                assert result >= 0 { "task result must be non-negative" }
            } zone {
                return value
            }

            flow values(value: Int): Int
            in {
                assert value >= 0 { "flow input must be non-negative" }
            } out { item ->
                assert item >= 0 { "flow item must be non-negative" }
            } zone {
                yield value
            }

            func main() {}
        """.trimIndent())
    }
}
