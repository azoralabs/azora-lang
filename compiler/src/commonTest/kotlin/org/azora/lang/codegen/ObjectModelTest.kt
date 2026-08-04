package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for the object model: `prop`, `ctor`.
 */
class ObjectModelTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun propComputesValue() {
        assertEquals("10", run("""
            import std.io
            pack Box {
                var v: Int
            }
            impl Box {
                prop doubled[self: Self&]: Int {
                    return self.v + self.v
                }
            }
            func main() {
                var b = Box(5)
                std::println(b.doubled)
            }
        """.trimIndent()))
    }
}
