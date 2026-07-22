package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArrayStdlibTest {
    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source.trimIndent(), release = false)
        return assertIs(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
    }

    @Test
    fun arrayOfAndLiteralProduceEquivalentValues() {
        val result = compile(
            """
            import std.container.array
            import std.io

            func main() {
                fin factory: Array<Int> = std::arrayOf(1, 2, 3)
                fin literal: Array<Int> = [1, 2, 3]
                std::println(factory.length)
                std::println(factory[1])
                std::println(factory.data[1])
                std::println(literal.length)
                std::println(literal[1])
            }
            """,
        )

        assertEquals("3\n2\n2\n3\n2", IrInterpreter().interpret(result.ir).trim())
    }

    @Test
    fun arrayTypeSugarIsCanonicalAcrossFunctionBoundaries() {
        val result = compile(
            """
            import std.container.array
            import std.io

            func first(values: Array<String>): String {
                return values[0]
            }

            func last(values: Array<String>): String {
                return values[values.length - 1]
            }

            func main() {
                std::println(first(["a", "b"]))
                std::println(last(std::arrayOf("a", "b")))
            }
            """,
        )

        assertEquals("a\nb", IrInterpreter().interpret(result.ir).trim())
        val firstType = result.ir.functions.first { it.name == "first" }.params.single().second
        val lastType = result.ir.functions.first { it.name == "last" }.params.single().second
        assertEquals(IrType.Array(IrType.String), firstType)
        assertEquals(firstType, lastType)
    }

    @Test
    fun arrayOfSupportsNestedHomogeneousArrays() {
        val result = compile(
            """
            import std.container.array
            import std.io

            func main() {
                fin rows: Array<Array<Int>> = std::arrayOf([1, 2], [3, 4])
                std::println(rows[1][0])
            }
            """,
        )

        assertEquals("3", IrInterpreter().interpret(result.ir).trim())
    }

    @Test
    fun arrayOfSupportsAnExplicitlyTypedEmptyArray() {
        val result = compile(
            """
            import std.container.array
            import std.io

            func main() {
                fin values: Array<Int> = std::arrayOf<Int>()
                std::println(values.length)
            }
            """,
        )

        assertEquals("0", IrInterpreter().interpret(result.ir).trim())
    }

    @Test
    fun arrayOfRejectsMixedElementTypes() {
        val mixed = Compiler().compile(
            """
            import std.container.array

            func main() {
                fin values = std::arrayOf(1, "two")
            }
            """.trimIndent(),
            release = false,
        )

        assertIs<CompilationResult.Failure>(mixed)
        assertTrue(mixed.errors.any { "must share a type" in it }, mixed.errors.toString())

        val wrongExplicitType = Compiler().compile(
            """
            import std.container.array

            func main() {
                fin values = std::arrayOf<String>(1, 2)
            }
            """.trimIndent(),
            release = false,
        )

        assertIs<CompilationResult.Failure>(wrongExplicitType)
        assertTrue(
            wrongExplicitType.errors.any { "must share a type" in it },
            wrongExplicitType.errors.toString(),
        )
    }

    @Test
    fun homogeneousVarargsUseValueSideEllipsisOnly() {
        val valid = Compiler().compile(
            """
            func<T> collect(...elements: T): Array<T> {
                return elements
            }

            func main() {
                fin values = collect(1, 2, 3)
            }
            """.trimIndent(),
            release = false,
        )
        assertIs<CompilationResult.Success>(valid)

        val invalid = Compiler().compile(
            """
            func<T> collect(elements: ...T): Array<T> {
                return elements
            }
            """.trimIndent(),
            release = false,
        )
        assertIs<CompilationResult.Failure>(invalid)
        assertTrue(
            invalid.errors.any { "requires a matching variadic generic declaration '<...T>'" in it },
            invalid.errors.toString(),
        )
    }
}
