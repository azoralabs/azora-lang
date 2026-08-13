package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Verifies the source-level AI/ML helpers through normal stdlib injection. */
class AiMlStdlibTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test
    fun structuredAiAndMlHelpersRemainBackendNeutral() {
        assertEquals(
      "2\n2\n2.0\n0.5",
            run(
                """
                import std.ai
                import std.ml
                import std.container.array
                import std.io

                func main() {
                    var chat = std::conversation()
                    chat.system("be concise")
                    chat.user("hello")
                    std::println(chat.size)
                    fin probabilities = std::softmax(@std::arr[-1.0, 0.0, 2.0])
                    std::println(std::argmax(probabilities))
                    std::println(std::meanSquaredError(@std::arr[1.0, 2.0], @std::arr[1.0, 4.0]))
                    std::println(std::accuracy(@std::arr[1, 0], @std::arr[1, 2]))
                }
                """,
            ),
        )
    }
}
