package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Where a bare `.Case` is a value and where it is an error.
 *
 * `return .Case` is how a failable callable reports an error, so a `when` whose
 * arms are bare cases must not be read that way when the `when` is producing the
 * callable's ordinary result.
 */
class QualifiedWhenPatternTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun program(member: String, call: String): String = """
        import std.io

        enum Colour {
            Red
            Green
            Blue
        }

        impl Colour {
$member
        }

        func main() {
            println("${'$'}{$call}")
        }
    """.trimIndent()

    @Test fun aBareCaseIsAValueOnTheRightOfAnEquals() {
        assertEquals(
            "Colour.Green",
            run(program("            prop &.next: Colour = .Green", "Colour.Red.next")),
        )
    }

    @Test fun aBareCaseIsAValueInALocalBinding() {
        assertEquals(
            "Colour.Green",
            run(
                program(
                    """            prop &.next: Colour {
                fin answer: Colour = .Green
                return answer
            }""",
                    "Colour.Red.next",
                )
            ),
        )
    }

    @Test fun aWhenOverBareCasesIsAValueInALocalBinding() {
        assertEquals(
            "Colour.Green",
            run(
                program(
                    """            prop &.next: Colour {
                fin answer: Colour = when self {
                    .Red -> .Green
                    .Green -> .Red
                    else -> .Blue
                }
                return answer
            }""",
                    "Colour.Red.next",
                )
            ),
        )
    }

    @Test fun aReturnedWhenOverBareCasesIsAlsoAValue() {
        assertEquals(
            "Colour.Green",
            run(
                program(
                    """            prop &.next: Colour {
                return when self {
                    .Red -> .Green
                    .Green -> .Red
                    else -> .Blue
                }
            }""",
                    "Colour.Red.next",
                )
            ),
        )
    }
}
