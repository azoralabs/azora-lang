package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.LibrarySource
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelectedImportImplementationTest {
    private val libraries = listOf(
        LibrarySource("lib/values.az", """
            module lib.values
            pack Value { var number: Int }
            impl Value {
                func &.read(): Int { return self.number }
            }
            func makeValue(): Value { return Value(42) }
            func unrelated(): Int { return 99 }
        """.trimIndent()),
        LibrarySource("lib/extra.az", """
            module lib.extra
            import lib.values::Value
            impl Value {
                func &.extra(): Int { return 17 }
            }
        """.trimIndent()),
    )

    @Test fun selectedTypeKeepsItsImplementations() {
        for (optimized in listOf(false, true)) {
            val result = Compiler(libraries).compile("""
                import lib.values::Value
                func main() {
                    fin value = Value(7)
                    assert value.read() == 7 panic "selected type must retain its implementation"
                }
            """.trimIndent(), release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals("", IrInterpreter().interpret(result.ir))
        }
    }

    @Test fun selectedFactoryKeepsResultImplementations() {
        val result = Compiler(libraries).compile("""
            import lib.values::makeValue
            func main() {
                fin value = makeValue()
                assert value.read() == 42 panic "factory result must retain its implementation"
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("", IrInterpreter().interpret(result.ir))
    }

    @Test fun selectedTypeDoesNotImportUnrelatedDeclarationsOrExtensions() {
        for (expression in listOf("unrelated()", "Value(7).extra()")) {
            val result = Compiler(libraries).compile("import lib.values::Value\nfunc main() { $expression }")
            val failure = assertIs<CompilationResult.Failure>(result, expression)
            assertTrue(failure.errors.any { "unrelated" in it || "extra" in it }, failure.errors.toString())
        }
    }
}
