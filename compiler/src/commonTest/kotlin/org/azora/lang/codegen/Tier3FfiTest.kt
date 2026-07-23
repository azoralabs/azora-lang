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
            import std.io
            bridge .C {
                func sqrt(x: Real): Real
                func sin(x: Real): Real
            }
            func main() {
                std::println(sqrt(16.0))
                std::println(sin(0.0))
            }
        """.trimIndent()))
    }

    @Test fun bridgePowTwoArgs() {
        assertEquals("1024.0", run("""
            import std.io
            bridge .C {
                func pow(val: Real, exp: Real): Real
            }
            func main() {
                std::println(pow(2.0, 10.0))
            }
        """.trimIndent()))
    }

    @Test fun bridgeEmitsExternDeclarationsInBackends() {
        val result = Compiler().compile("""
            import std.io
            bridge .C {
                func sqrt(x: Real): Real
            }
            func main() {
                std::println(sqrt(16.0))
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        assertTrue(
            "bridge func sqrt(x: Real): Real" in result.ir.prettyPrint(),
            "Azora IR should preserve bridge syntax, got:\n${result.ir.prettyPrint()}",
        )
        assertFalse(
            "extern func" in result.ir.prettyPrint(),
            "Azora IR must not expose backend extern terminology, got:\n${result.ir.prettyPrint()}",
        )
        assertTrue("// extern function sqrt" in result.javascript, "JavaScript should emit an extern comment, got:\n${result.javascript}")
        assertTrue("declare" in result.llvm && "sqrt" in result.llvm, "LLVM should emit declare, got:\n${result.llvm}")
    }
}
