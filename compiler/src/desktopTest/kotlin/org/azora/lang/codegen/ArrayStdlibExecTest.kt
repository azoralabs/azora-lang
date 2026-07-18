package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class ArrayStdlibExecTest {
    private val source = """
        import std.array
        import std.io

        func main() {
            fin values: Array<Int> = std::arrayOf(5, 8, 13)
            std::println(values.length)
            std::println(values[1])
            std::println(values.data[1])
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
