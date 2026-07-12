package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Executes variadic-tuple programs through the WebAssembly backend to confirm
 * the monomorphized `__Tuple_*` packs and numeric field access work natively.
 * Skips when the `wat2wasm`/Node toolchain isn't available.
 *
 * (Wasm lays structs out as packed i32 fields tracked by name in a registry,
 * so the monomorphized name does not appear verbatim in the WAT — execution
 * output is the meaningful check.)
 */
class TupleVariadicWasmExecTest {

    @Test fun tupleOfInferredRunsViaWasm() {
        if (!WasmExec.available) return
        assertEquals("1\n2", WasmExec.run("""
            use std.container
            func main() {
                fin x = tupleOf(1, 2)
                println(x.0)
                println(x.1)
            }
        """.trimIndent()))
    }

    @Test fun tupleOfThreeElementsRunsViaWasm() {
        if (!WasmExec.available) return
        assertEquals("1\n2\n3", WasmExec.run("""
            use std.container
            func main() {
                fin t = tupleOf(1, 2, 3)
                println(t.0)
                println(t.1)
                println(t.2)
            }
        """.trimIndent()))
    }
}

