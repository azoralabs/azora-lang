package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals

class ArrayStdlibExecTest {
    private val source = """
        import std.container.array
        import std.io

        func main() {
            fin values: std::Array<std::Int> = std::arrayOf(5, 8, 13)
            std::println(values.size)
            std::println(values[1])
            unsafe { std::println(values.data[1]) }
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
