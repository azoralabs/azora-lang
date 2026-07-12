package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executes variadic-tuple programs through the LLVM backend (`lli`) to confirm
 * the monomorphized `__Tuple_*` packs and numeric field access work natively.
 */
class TupleVariadicLlvmExecTest {

    @Test fun tupleOfInferredRunsViaLli() {
        if (!LlvmExec.available) return
        assertEquals("1\n2", LlvmExec.run("""
            use std.container
            func main() {
                fin x = tupleOf(1, 2)
                println(x.0)
                println(x.1)
            }
        """.trimIndent()))
    }

    @Test fun tupleOfThreeElementsRunsViaLli() {
        if (!LlvmExec.available) return
        assertEquals("1\n2\n3", LlvmExec.run("""
            use std.container
            func main() {
                fin t = tupleOf(1, 2, 3)
                println(t.0)
                println(t.1)
                println(t.2)
            }
        """.trimIndent()))
    }

    @Test fun llvmEmitsMonomorphizedStruct() {
        if (!LlvmExec.available) return
        val ir = LlvmExec.compile("""
            use std.container
            func main() {
                fin x = tupleOf(1, 2)
                println(x.0)
            }
        """.trimIndent())
        // The monomorphized struct must be emitted and referenced by name.
        assertTrue(ir.contains("__Tuple_Int_Int"), ir)
    }
}
