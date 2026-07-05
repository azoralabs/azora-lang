package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tests for FFI `bridge` — extern function declarations.
 *
 * The interpreter resolves common C-math functions to `kotlin.math`.
 * Codegens emit real extern declarations (`external fun` / `declare function` / LLVM `declare`).
 */
class Tier3FfiTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun bridgeSinAndSqrt() {
        assertEquals("4.0\n0.0", run("""
            bridge C {
                func sqrt(x: Real): Real
                func sin(x: Real): Real
            }
            func main() {
                println(sqrt(16.0))
                println(sin(0.0))
            }
        """.trimIndent()))
    }

    @Test fun bridgePowTwoArgs() {
        assertEquals("1024.0", run("""
            bridge C {
                func pow(val: Real, exp: Real): Real
            }
            func main() {
                println(pow(2.0, 10.0))
            }
        """.trimIndent()))
    }

    @Test fun bridgeEmitsExternDeclarationsInBackends() {
        val result = Compiler().compile("""
            bridge C {
                func sqrt(x: Real): Real
            }
            func main() {
                println(sqrt(16.0))
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        assertTrue("external fun sqrt" in result.kotlin, "Kotlin should emit external fun, got:\n${result.kotlin}")
        assertTrue("declare function sqrt" in result.typescript, "TypeScript should emit declare function, got:\n${result.typescript}")
        assertTrue("declare" in result.llvm && "sqrt" in result.llvm, "LLVM should emit declare, got:\n${result.llvm}")
    }
}
