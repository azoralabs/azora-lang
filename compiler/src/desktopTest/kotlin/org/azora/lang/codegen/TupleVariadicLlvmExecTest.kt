package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executes variadic-tuple programs through the LLVM backend (`lli`) to confirm
 * the monomorphized `__std_Tuple_*` packs and numeric field access work natively.
 */
class TupleVariadicLlvmExecTest {

    @Test fun tupleOfInferredRunsViaLli() {
        if (!LlvmExec.available) return
        assertEquals("1\n2", LlvmExec.run("""
            import std.io
            func main() {
                fin x = (1, 2)
                println(x.0)
                println(x.1)
            }
        """.trimIndent()))
    }

    @Test fun tupleOfThreeElementsRunsViaLli() {
        if (!LlvmExec.available) return
        assertEquals("1\n2\n3", LlvmExec.run("""
            import std.io
            func main() {
                fin t = (1, 2, 3)
                println(t.0)
                println(t.1)
                println(t.2)
            }
        """.trimIndent()))
    }

    @Test fun aTupleIsTheCompilersOwnAggregate() {
        if (!LlvmExec.available) return
        // `(Int, Int)` and `Tuple<Int, Int>` are one structural type (GTC §6.3):
        // no pack is declared for a shape, whichever spelling built the value.
        val ir = LlvmExec.compile("""
            import std.io
            func main() {
                fin x = (1, 2)
                fin y = Tuple<Int, Int>(3, 4)
                println(x.0 + y.1)
            }
        """.trimIndent())
        assertTrue("Tuple_Int_Int" !in ir, ir)
        assertEquals("5", LlvmExec.runIr(ir))
    }
}
