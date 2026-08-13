package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Verifies GPU geometry and shader artifacts through ordinary stdlib injection. */
class GpuStdlibTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test
    fun geometryAndComputeArtifactsRemainInspectable() {
        assertEquals(
            "8\n2\n1\n16\n0\ntrue\n64\nmain\n13\n17\n5\n12\n23\n4096\ntrue",
            run(
                """
                import std.gpu
                import std.io

                func main() {
                    fin local = std::workgroup(8, 2, 1)
                    fin full = std::dispatchFor(1000, 64)
                    fin empty = std::dispatchFor(0, 64)
                    fin source = std::computeShader("main", "fn main() {}", 64)
                    fin extent = std::dispatch(100, 33, 5)
                    fin groups = std::dispatchFor3d(extent, local)
                    fin layout = std::rowMajor(@std::arr[2, 3, 4])
                    fin config = std::launch(groups, local, 4096, true)
                    std::println(local.x)
                    std::println(local.y)
                    std::println(local.z)
                    std::println(full.x)
                    std::println(empty.x)
                    std::println(source.stage == std::ShaderStage.Compute)
                    std::println(source.workgroup.x)
                    std::println(source.entry)
                    std::println(groups.x)
                    std::println(groups.y)
                    std::println(groups.z)
                    std::println(layout.strides[0])
                    std::println(std::linearIndex(layout, @std::arr[1, 2, 3]))
                    std::println(config.sharedBytes)
                    std::println(config.cooperative)
                }
                """,
            ),
        )
    }
}
