package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class ArrayStdlibExecTest {
    private val source = """
        import std.container.array
        import std.io

        func main() {
            fin values: Array<Int> = arrayOf(5, 8, 13)
            println(values.size)
            println(values[1])
            unsafe { println(values.data[1]) }
        }
    """.trimIndent()

    @Test
    fun arrayOfRunsViaLlvm() {
        if (!LlvmExec.available) return
        assertEquals("3\n8\n8", LlvmExec.run(source))
        assertEquals("3\n8\n8", LlvmExec.run(source, optimized = true))
    }

    @Test
    fun arrayOfRunsViaWasm() {
        if (!WasmExec.available) return
        assertEquals("3\n8\n8", WasmExec.run(source))
    }
}
