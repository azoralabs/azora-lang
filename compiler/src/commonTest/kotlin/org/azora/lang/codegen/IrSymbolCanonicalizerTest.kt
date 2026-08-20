package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs

class IrSymbolCanonicalizerTest {
    @Test
    fun nestedScopeSymbolsUseOneCanonicalIrSeparatorConvention() {
        val result = Compiler().compile(
            """
            module naming

            scope acme::math {
                pack Point {
                    fin x: Int
                }

                enum Axis { X Y }

                fin origin = acme::math::Point(0)

                func identity(value: acme::math::Point): acme::math::Point {
                    return value
                }

                bridge func draw(value: acme::math::Point): Unit
            }

            func main() {
                fin point: acme::math::Point = acme::math::Point(7)
                acme::math::identity(point)
            }
            """.trimIndent(),
        )
        val success = assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        val ir = success.ir.prettyPrint()

        assertContains(ir, "pack __acme_math_Point")
        assertContains(ir, "enum __acme_math_Axis")
        assertContains(ir, "fin __acme_math_origin: __acme_math_Point")
        assertContains(ir, "func __acme_math_identity(value: __acme_math_Point): __acme_math_Point")
        assertContains(ir, "bridge func __acme_math_draw(value: __acme_math_Point): Unit")
        assertContains(ir, "fin point: __acme_math_Point = __acme_math_Point(7)")
        assertContains(ir, "__acme_math_identity(point)")
        assertFalse("acme__math" in ir, ir)
        assertFalse("__acme__math" in ir, ir)
    }

    @Test
    fun generatedAndCompilerOwnedSymbolsUseTheSameCanonicalForm() {
        val result = Compiler().compile(
            """
            scope acme::ops {
                func choose<...T>(...values: ...T): Int {
                    return 7
                }
            }

            threadlocal var calls = 0

            func main() {
                calls = acme::ops::choose(1, "two")
            }
            """.trimIndent(),
        )
        val success = assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        val ir = success.ir.prettyPrint()

        assertContains(ir, "func __acme_ops_choose(values: Array<Any>): Int")
        assertContains(ir, "__acme_ops_choose([1, \"two\"])")
        assertContains(ir, "var __tl_calls: Int = 0")
        assertFalse("__acme__ops" in ir, ir)
        assertFalse("__tl__calls" in ir, ir)
    }

    @Test
    fun namespacedSingletonFactoriesUseTheCanonicalTypeIdentity() {
        val result = Compiler().compile(
            """
            scope acme::config {
                solo pack Settings {
                    fin value: Int = 9
                }
            }

            func main() {}
            """.trimIndent(),
        )
        val success = assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        val ir = success.ir.prettyPrint()

        assertContains(ir, "pack __acme_config_Settings")
        assertContains(ir, "func __singleton_acme_config_Settings")
        assertFalse("__singleton___acme" in ir, ir)
    }
}
