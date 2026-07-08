package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.AzoraDebugHost
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Debug-build instrumentation + interpreter debug host. */
class DebugModeTest {

    private fun compileDebug(source: String) =
        (Compiler().compile(source, release = false, debug = true) as? CompilationResult.Success)
            ?: throw AssertionError("debug compile failed")

    @Test
    fun lineEventsFireWithLocals() {
        val result = compileDebug(
            "func main() {\n    var x = 1\n    x = x + 1\n    println(x)\n}"
        )
        val events = mutableListOf<Pair<Int, Map<String, Any?>>>()
        val interpreter = IrInterpreter()
        interpreter.debugHost = object : AzoraDebugHost {
            override suspend fun onLine(line: Int, locals: Map<String, Any?>) {
                events.add(line to locals)
            }
        }
        val out = interpreter.interpret(result.ir)
        assertEquals("2", out)
        assertEquals(listOf(2, 3, 4), events.map { it.first }, "one event per statement line")
        // At line 3 (x = x + 1) the local x is still 1; at line 4 it is 2.
        assertEquals(1L, events.first { it.first == 3 }.second["x"])
        assertEquals(2L, events.first { it.first == 4 }.second["x"])
    }

    @Test
    fun nestedBlocksAreInstrumented() {
        val result = compileDebug(
            "func main() {\n    for i in 1..2 {\n        println(i)\n    }\n}"
        )
        val lines = mutableListOf<Int>()
        val interpreter = IrInterpreter()
        interpreter.debugHost = object : AzoraDebugHost {
            override suspend fun onLine(line: Int, locals: Map<String, Any?>) { lines.add(line) }
        }
        interpreter.interpret(result.ir)
        assertEquals(listOf(2, 3, 3), lines, "loop body line fires per iteration")
    }

    @Test
    fun releaseBuildsHaveNoInstrumentation() {
        val result = Compiler().compile("func main() {\n    println(1)\n}") as CompilationResult.Success
        assertTrue("__dbg" !in result.ir.prettyPrint())
    }

    @Test
    fun outputStreamsLive() {
        val result = compileDebug("func main() {\n    println(\"a\")\n    println(\"b\")\n}")
        val streamed = mutableListOf<String>()
        val interpreter = IrInterpreter()
        interpreter.outputListener = { streamed.add(it) }
        interpreter.interpret(result.ir)
        assertEquals(listOf("a", "b"), streamed)
    }

    @Test
    fun debugBuildRunsIdenticallyWithoutHost() {
        val source = "func main() {\n    var total = 0\n    for i in 1..5 {\n        total = total + i\n    }\n    println(total)\n}"
        val debug = compileDebug(source)
        assertEquals("15", IrInterpreter().interpret(debug.ir))
    }
}
