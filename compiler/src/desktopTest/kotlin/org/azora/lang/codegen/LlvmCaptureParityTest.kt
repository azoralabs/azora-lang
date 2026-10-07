package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A referenced capture is the original binding, on every backend
 * (LAMBDA_CONTEXT_CAPTURE_DIP.MD §3).
 *
 * These are the two programs the DIP measured the divergence with: the
 * interpreter kept the scope chain and referenced, while the native backends
 * copied into a heap environment and snapshotted. Both now hold the address.
 */
class LlvmCaptureParityTest {

    private fun interpreted(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun agrees(expected: String, source: String) {
        assertEquals(expected, interpreted(source), "interpreter")
        if (!LlvmExec.available) return
        assertEquals(expected, LlvmExec.run(source).trim(), "llvm, debug IR")
        assertEquals(expected, LlvmExec.run(source, optimized = true).trim(), "llvm, optimized IR")
    }

    /** The closure's own write is visible outside it. */
    @Test fun aMutableCaptureWritesThroughToTheOriginal() = agrees(
        "5\n5",
        """
        import std.io
        func main() {
            var n = 1
            fin bump = [n.!] { x: Int -> n = n + x
                return n }
            println(bump(4))
            println(n)
        }
        """.trimIndent(),
    )

    /** A write after the closure is made is visible inside it. */
    @Test fun aSharedCaptureSeesLaterWrites() = agrees(
        "99",
        """
        import std.io
        func main() {
            var n = 1
            fin show = [n.&] { x: Int -> n }
            n = 99
            println(show(0))
        }
        """.trimIndent(),
    )

    /** A copy is independent of the binding it was taken from. */
    @Test fun aCopyCaptureIsIndependent() = agrees(
        "3",
        """
        import std.io
        func main() {
            var retries = 3
            fin show = [retries] { retries }
            retries = 5
            println(show())
        }
        """.trimIndent(),
    )

    /** Spawned task contexts keep references as references too. */
    @Test fun anAsyncMutableCaptureWritesThroughItsTaskContext() = agrees(
        "5\n5",
        """
        import std.io
        func main() {
            var n = 1
            fin worker = async [n.!] {
                n = n + 4
                n
            }
            println(await worker)
            println(n)
        }
        """.trimIndent(),
    )

    /**
     * An inline callable called from a block nested inside another inline call.
     *
     * The child block reads the parameter only as a callee. It still has to reach
     * the environment by reference: copying it would hand the block an owner of a
     * closure the enclosing function also frees, and leaving it out entirely left
     * the native backend loading a module global that does not exist.
     */
    @Test fun aNestedBlockCallsAnInlineCallableParameter() = agrees(
        "plain 7\nreact 8",
        """
        import std.io

        func outer(children: inline () -> Unit) { children() }

        func dock(count: Int, content: inline (Int) -> Unit) {
            outer {
                content(count)
            }
        }

        react func reactOuter(children: inline react () -> Unit) { children() }

        react func reactDock(count: Int, content: inline react (Int) -> Unit) {
            reactOuter {
                content(count + 1)
            }
        }

        react func main() {
            dock(7) { i -> println("plain ${'$'}{i}") }
            reactDock(7) { i -> println("react ${'$'}{i}") }
        }
        """.trimIndent(),
    )
}
