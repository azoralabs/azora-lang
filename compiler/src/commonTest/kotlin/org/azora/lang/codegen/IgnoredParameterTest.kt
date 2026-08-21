package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `_` is the name a signature gives a value it deliberately ignores.
 *
 * A receiver and a parameter are part of a contract: the call site passes them
 * whether or not the body reads them, so the answer to an unused one is to stop
 * naming it, not to delete it. `_` says exactly that, and because it names
 * nothing it may appear as many times as a signature needs.
 */
class IgnoredParameterTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test
    fun aParameterMayBeNamedNothing() {
        assertEquals("7", run("""
            import std.io

            func constant(_: Int): Int {
                return 7
            }

            func main() {
                println(constant(1))
            }
        """.trimIndent()))
    }

    @Test
    fun severalParametersMayBeNamedNothing() {
        assertEquals("7", run("""
            import std.io

            func constant(_: Int, _: Int, _: String): Int {
                return 7
            }

            func main() {
                println(constant(1, 2, "three"))
            }
        """.trimIndent()))
    }

    @Test
    fun aReceiverMayBeNamedNothing() {
        assertEquals("7", run("""
            import std.io

            pack Box {
                var value: Int
            }

            impl Box {
                func constant[_: Self&](_: Int): Int {
                    return 7
                }
            }

            func main() {
                var box = Box(1)
                println(box.constant(2))
            }
        """.trimIndent()))
    }
}
