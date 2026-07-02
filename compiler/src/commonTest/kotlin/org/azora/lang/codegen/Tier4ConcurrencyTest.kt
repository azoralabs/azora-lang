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

    @Test fun taskAwaitReturnsResult() {
        assertEquals("42", run("""
            func main() {
                var t = task {
                    42
                }
                println(await t)
            }
        """.trimIndent()))
    }

    @Test fun taskAwaitComputedResult() {
        assertEquals("30", run("""
            func compute(a: Int, b: Int): Int {
                return a * b
            }
            func main() {
                var t = task {
                    compute(5, 6)
                }
                println(await t)
            }
        """.trimIndent()))
    }

    @Test fun multipleTasksAwaited() {
        assertEquals("10\n20", run("""
            func main() {
                var t1 = task { 10 }
                var t2 = task { 20 }
                println(await t1)
                println(await t2)
            }
        """.trimIndent()))
    }

    @Test fun channelSendAndReceive() {
        assertEquals("1\n2", run("""
            func main() {
                var ch = channel()
                ch.send(1)
                ch.send(2)
                println(ch.receive())
                println(ch.receive())
            }
        """.trimIndent()))
    }

    @Test fun channelWithProducerTask() {
        // A producer task sends values; the consumer receives them via await ordering.
        assertEquals("10\n20", run("""
            func produce(ch: Channel): Int {
                ch.send(10)
                ch.send(20)
                ch.close()
                return 0
            }
            func main() {
                var ch = channel()
                var p = task {
                    produce(ch)
                }
                await p
                println(ch.receive())
                println(ch.receive())
            }
        """.trimIndent()))
    }

    @Test fun launchRunsAndIsJoinedAtEnd() {
        // `launch` is fire-and-forget; its side effect completes before main returns.
        assertEquals("main\nlaunched", run("""
            func main() {
                println("main")
                launch {
                    println("launched")
                }
            }
        """.trimIndent()))
    }

    @Test fun launchProducerWithChannelConsumer() {
        // A launched producer feeds a channel that main consumes cooperatively.
        assertEquals("1\n2", run("""
            func main() {
                var ch = channel()
                launch {
                    ch.send(1)
                    ch.send(2)
                    ch.close()
                }
                println(ch.receive())
                println(ch.receive())
            }
        """.trimIndent()))
    }
}
