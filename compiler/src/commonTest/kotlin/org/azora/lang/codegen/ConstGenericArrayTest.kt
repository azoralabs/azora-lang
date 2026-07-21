package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Stage-1 verification of const generics: `Array<T, N: Int>` is a distinct type
 * keyed by `(T, N)`, literals infer `N`, and `.size` folds to the compile-time `N`.
 */
class ConstGenericArrayTest {

    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source.trimIndent(), release = false)
        return assertIs(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
    }

    private fun run(source: String): String =
        IrInterpreter().interpret(compile(source).ir).trim()

    @Test
    fun sizedArrayTypeHoldsCompileTimeSize() {
        val out = run(
            """
            import std.io

            func main() {
                fin a: Array<Int, 3> = [1, 2, 3]
                std::println(a.size)
                std::println(a[0])
                std::println(a[2])
            }
            """,
        )
        assertEquals("3\n1\n3", out)
    }

    @Test
    fun literalInfersItsSize() {
        // `[4, 5]` infers `Array<Int, 2>`; `.size` folds to 2.
        val out = run(
            """
            import std.io

            func main() {
                fin a = [4, 5]
                std::println(a.size)
            }
            """,
        )
        assertEquals("2", out)
    }

    @Test
    fun differentSizesAreDistinctTypes() {
        // `Array<Int, 2>` and `Array<Int, 3>` must NOT be assignable.
        val result = Compiler().compile(
            """
            func main() {
                fin a: Array<Int, 2> = [1, 2]
                fin b: Array<Int, 3> = a
            }
            """.trimIndent(),
        )
        val failure = assertIs<CompilationResult.Failure>(result, "Expected a type-mismatch failure")
        assertTrue(failure.errors.isNotEmpty(), "Expected errors for assigning Array<Int,2> to Array<Int,3>")
    }

    @Test
    fun unsizedSlotAcceptsSizedArray() {
        // An unsized `Array<Int>` slot accepts a sized literal (back-compat with existing code).
        val out = run(
            """
            import std.io

            func main() {
                fin a: Array<Int> = [1, 2, 3]
                std::println(a[1])
            }
            """,
        )
        assertEquals("2", out)
    }
}
