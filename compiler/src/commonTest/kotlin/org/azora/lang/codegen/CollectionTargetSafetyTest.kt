package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Storage conversion requires a declared construction, never a short type name. */
class CollectionTargetSafetyTest {
    @Test fun arrayValuesCannotMasqueradeAsNamedLists() {
        for (target in listOf("List", "MutableList")) {
            val declaration = "pack $target<T> { var marker: Int }"
            for (body in listOf(
                "fin data = [1, 2]\nfin value: $target<Int> = data",
                "var value = $target<Int>(7)\nvalue = [1, 2]",
                "accept([1, 2])",
                "fin value = produce()",
            )) {
                val producer = if (body == "fin value = produce()")
                    "func produce(): $target<Int> {\nfin data = [1, 2]\nreturn data\n}" else ""
                val source = """
                    $declaration
                    func accept(value: $target<Int>) {}
                    $producer
                    func main() { $body }
                """.trimIndent()
                assertIs<CompilationResult.Failure>(Compiler().compile(source), source)
            }
        }
    }

    @Test fun namedCollectionsCannotMasqueradeAsArrays() {
        for (target in listOf("List", "MutableList")) {
            val source = """
                pack $target<T> { var marker: Int }
                func main() {
                    fin value = $target<Int>(7)
                    fin data: Array<Int> = value
                }
            """.trimIndent()
            assertIs<CompilationResult.Failure>(Compiler().compile(source), source)
        }
    }

    @Test fun keyedValuesCannotMasqueradeAsNamedMaps() {
        for (target in listOf("Map", "MutableMap")) {
            val source = """
                pack $target<K, V> { var marker: Int }
                func main() {
                    fin data = [1: 2]
                    fin value: $target<Int, Int> = data
                }
            """.trimIndent()
            assertIs<CompilationResult.Failure>(Compiler().compile(source), source)
        }
    }

    @Test fun explicitConstructionRetainsTheDeclaredPack() {
        val source = """
            pack List<T> { var marker: Int }
            func main() {
                fin value: List<Int> = List<Int>(7)
                assert value.marker == 7 panic "explicit construction must retain pack storage"
            }
        """.trimIndent()
        for (optimized in listOf(false, true)) {
            val result = Compiler().compile(source, release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals("", IrInterpreter().interpret(result.ir))
        }
    }
}
