package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tier 4 - concurrency. Starting with `flow`/`yield` generators.
 *
 * A `flow` generator runs its body when called, collecting `yield`ed values into a
 * list (eager evaluation), and returns that list - so it composes with `for x in …`.
 */
class Tier4ConcurrencyTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun asyncIsContextualNotReserved() {
        // `async` names a task only when a `func` follows it; on its own it is
        // still the builtin that spawns a lambda, and still a usable name.
        assertEquals("5\n21\n20", run("""
            import std.io

            async func compute(n: Int): Int {
                return n * 2
            }

            func main() {
                var async = 5
                std::println(async)
                fin handle = async func { 21 }
                std::println(await handle)
                std::println(await compute(10))
            }
        """.trimIndent()))
    }

    @Test fun taskAwaitReturnsResult() {
        assertEquals("42", run("""
            import std.io
            func main() {
                var t = async func {
                    42
                }
                std::println(await t)
            }
        """.trimIndent()))
    }

    @Test fun taskAwaitComputedResult() {
        assertEquals("30", run("""
            import std.io
            func compute(a: Int, b: Int): Int {
                return a * b
            }
            func main() {
                var t = async func {
                    compute(5, 6)
                }
                std::println(await t)
            }
        """.trimIndent()))
    }

    @Test fun multipleTasksAwaited() {
        assertEquals("10\n20", run("""
            import std.io
            func main() {
                var t1 = async func { 10 }
                var t2 = async func { 20 }
                std::println(await t1)
                std::println(await t2)
            }
        """.trimIndent()))
    }

    @Test fun channelSendAndReceive() {
        assertEquals("1\n2", run("""
            import std.io
            func main() {
                var ch = channel()
                ch.send(1)
                ch.send(2)
                std::println(ch.receive())
                std::println(ch.receive())
            }
        """.trimIndent()))
    }

    @Test fun channelWithProducerTask() {
        // A producer task sends values; the consumer receives them via await ordering.
        assertEquals("10\n20", run("""
            import std.io
            func produce(ch: Channel): Int {
                ch.send(10)
                ch.send(20)
                ch.close()
                return 0
            }
            func main() {
                var ch = channel()
                var p = async func {
                    produce(ch)
                }
                await p
                std::println(ch.receive())
                std::println(ch.receive())
            }
        """.trimIndent()))
    }

    @Test fun parallelTasksAggregateResults() {
        // Two independent tasks run in parallel (Dispatchers.Default); both are awaited
        // and their results combined.
        assertEquals("300", run("""
            import std.io
            func main() {
                var t1 = async func { 100 }
                var t2 = async func { 200 }
                std::println(await t1 + await t2)
            }
        """.trimIndent()))
    }
}
