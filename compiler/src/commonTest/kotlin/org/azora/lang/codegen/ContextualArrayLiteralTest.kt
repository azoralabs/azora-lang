package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ContextualArrayLiteralTest {
    companion object {
        val program = """
            fin globalBytes: Array<Byte> = [21, 22]
            func first(values: Array<Byte>): Byte { return values[0] }
            func empty(): Array<Int> { return [] }
            func make(): Array<Byte> { return [3, 4] }
            pack Row { var values: Array<Byte> }
            func main() {
                assert globalBytes[1] == 22 panic "global collection context"
                fin ordinary = [1, 2, 3]
                assert ordinary[1] == 2 panic "default array"
                assert [1, 2, 3].size == 3 panic "literal member access"
                assert [[1, 2], [3, 4]][1][0] == 3 panic "literal indexing must not parse as captures"
                fin bytes: Array<Byte> = [1, 2, 3]
                assert bytes[2] == 3 panic "contextual element width"
                var wide: Array<Double> = [1, 2.5, 3]
                assert wide[1] == 2.5 panic "wide literal storage"
                wide[1] = 4
                wide[0] <> wide[2]
                assert wide[0] == 3.0 && wide[1] == 4.0 && wide[2] == 1.0 panic "typed array reads and writes"
                fin longs: Array<Long> = [1, 4294967296]
                assert longs[1] == 4294967296 panic "integer array storage width"
                fin narrow: Array<Int<7>> = [63, -64]
                assert narrow[0] == 63 && narrow[1] == -64 panic "nonstandard integer width context"
                fin widened = narrow[1] as Byte
                assert widened == -64 panic "same-byte-width sign extension"
                fin restored = widened as Int<7>
                assert restored == -64 panic "same-byte-width truncation"
                assert narrow[0] + (1 as Byte) == (64 as Byte) panic "custom-width numeric promotion"
                fin nested: Array<Array<Byte>> = [[4], [5, 6]]
                assert nested[1][1] == 6 panic "nested context"
                fin fixedRows: Array<Array<Byte, 2>, 2> = [[1, 2], [3, 4]]
                assert fixedRows[1][1] == 4 panic "nested fixed-size context"
                fin row = Row([7, 8])
                assert row.values[0] == 7 panic "field context"
                assert first([9]) == 9 panic "argument context"
                var assigned: Array<Byte> = []
                assigned = [10, 11]
                assert assigned[1] == 11 panic "assignment context"
                fin conditional: Array<Byte> = if true then [12] else [13]
                assert conditional[0] == 12 panic "branch context"
                assert make()[1] == 4 panic "return context"
                assert empty().size == 0 panic "empty return context"
            }
        """.trimIndent()

        fun compile(source: String = program, optimized: Boolean = false) = Compiler().compile(source, release = optimized)
            .let { assertIs<CompilationResult.Success>(it, (it as? CompilationResult.Failure)?.errors.toString()).ir }
    }

    @Test fun contextualArraysExecute() {
        for (optimized in listOf(false, true)) assertEquals("", IrInterpreter().interpret(compile(optimized = optimized)))
    }

    @Test fun globalInitializersAreTypeChecked() {
        for (source in listOf(
            "fin values: Array<Byte> = [300]",
            "fin values: Array<Int, 2> = [1]",
            "fin values = [1, true]",
            "fin values = []",
            "pack Target {}\nfin values: Target = [1, 2]",
            "fin wrong: Int = true",
        )) assertIs<CompilationResult.Failure>(Compiler().compile(source + "\nfunc main() {}"), source)
    }

    @Test fun incompatibleElementsAreRejected() {
        for (body in listOf(
            "fin x: Array<Int> = [1, true]",
            "fin x: Array<Byte> = [300]",
            "fin x: Array<Array<Int>> = [[1], [true]]",
            "fin x = []",
            "var x: Array<Byte> = [1]\nx[0] = 300",
            "fin x: Array<Int<7>> = [64]",
            "fin x: Array<Array<Int, 2>> = [[1, 2, 3]]",
            "fin x: Array<Any> = [1, \"unboxed\"]",
            "fin x = [:]",
            "fin x = [1: 2, \"bad\": 3]",
        )) assertIs<CompilationResult.Failure>(Compiler().compile("func main() { $body }"), body)
    }
}
