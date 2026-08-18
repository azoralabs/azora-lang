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
                    fin local = workgroup(8, 2, 1)
                    fin full = dispatchFor(1000, 64)
                    fin empty = dispatchFor(0, 64)
                    fin source = computeShader("main", "fn main() {}", 64)
                    fin extent = dispatch(100, 33, 5)
                    fin groups = dispatchFor3d(extent, local)
                    fin layout = rowMajor(@arr[2, 3, 4])
                    fin config = launch(groups, local, 4096, true)
                    println(local.x)
                    println(local.y)
                    println(local.z)
                    println(full.x)
                    println(empty.x)
                    println(source.stage == ShaderStage.Compute)
                    println(source.workgroup.x)
                    println(source.entry)
                    println(groups.x)
                    println(groups.y)
                    println(groups.z)
                    println(layout.strides[0])
                    println(linearIndex(layout, @arr[1, 2, 3]))
                    println(config.sharedBytes)
                    println(config.cooperative)
                }
                """,
            ),
        )
    }
}
