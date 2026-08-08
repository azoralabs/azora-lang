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
            std::println(bump(4))
            std::println(n)
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
            std::println(show(0))
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
            std::println(show())
        }
        """.trimIndent(),
    )
}
