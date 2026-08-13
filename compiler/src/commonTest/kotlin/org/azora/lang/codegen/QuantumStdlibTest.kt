package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Verifies that the first quantum circuit builders are ordinary stdlib code. */
class QuantumStdlibTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test
    fun circuitBuildersProduceInspectableGateSequences() {
        assertEquals(
            "2\n4\n4\n7\n34\n2",
            run(
                """
                import std.quantum
                import std.container.array
                import std.io

                func main() {
                    fin bell = std::bellPair()
                    fin hidden = std::bernsteinVazirani(@std::arr[1, 0, 1])
                    fin fourier = std::quantumFourierTransform(3)
                    fin search = std::groverSearch(3, 5)
                    std::println(bell.qubits)
                    std::println(bell.gates.size)
                    std::println(hidden.qubits)
                    std::println(fourier.gates.size)
                    std::println(search.gates.size)
                    std::println(std::groverIterations(3))
                }
                """,
            ),
        )
    }
}
