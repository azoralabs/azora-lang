package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ListConstructionTest {
    companion object {
        val program = """
            import std.container.list::ArrayList
            func main() {
                fin single = ArrayList<Int>(17)
                assert single.size == 1 && single.get(0) == 17 panic "single variadic element is packed"
                fin initial = ArrayList<Int>(7, 11, 19)
                assert initial.size == 3 panic "constructor preserves count"
                assert initial.get(0) == 7 && initial.get(1) == 11 && initial.get(2) == 19 panic "constructor uses callee slot widths"
                fin decimals = ArrayList<Double>(1.25, 2.5)
                assert decimals.get(0) == 1.25 && decimals.get(1) == 2.5 panic "constructor preserves floating values"
                var values = ArrayList<Int>()
                assert values.size == 0 panic "new list is empty"
                values.add(7)
                assert values.get(0) == 7 panic "first insertion allocates storage"
                for i in 1..<33 { values.add(i) }
                assert values.size == 33 panic "growth preserves count"
                assert values.get(0) == 7 panic "growth preserves first element"
                assert values.get(32) == 32 panic "growth preserves last element"
                values.insert(0, 99)
                assert values.get(0) == 99 && values.get(1) == 7 panic "insertion preserves order"
                values.clear()
                assert values.size == 0 panic "clear resets size"
                values.add(42)
                assert values.get(0) == 42 panic "cleared list remains reusable"
            }
        """.trimIndent()

        fun compile(optimized: Boolean) = Compiler().compile(program, release = optimized).let {
            assertIs<CompilationResult.Success>(it, (it as? CompilationResult.Failure)?.errors.toString()).ir
        }
    }

    @Test fun concreteListConstructionAndGrowth() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(compile(optimized)))
        }
    }
}
