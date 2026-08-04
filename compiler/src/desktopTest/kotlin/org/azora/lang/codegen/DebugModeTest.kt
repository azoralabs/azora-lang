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
            "import std.io\nfunc main() {\n    var x = 1\n    x = x + 1\n    std::println(x)\n}"
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
        assertEquals(listOf(3, 4, 5), events.map { it.first }, "one event per statement line")
        // At line 4 (x = x + 1) the local x is still 1; at line 5 it is 2.
        assertEquals(1L, events.first { it.first == 4 }.second["x"])
        assertEquals(2L, events.first { it.first == 5 }.second["x"])
    }

    @Test
    fun nestedBlocksAreInstrumented() {
        val result = compileDebug(
            "import std.io\nfunc main() {\n    for i in 1..2 {\n        std::println(i)\n    }\n}"
        )
        val lines = mutableListOf<Int>()
        val interpreter = IrInterpreter()
        interpreter.debugHost = object : AzoraDebugHost {
            override suspend fun onLine(line: Int, locals: Map<String, Any?>) { lines.add(line) }
        }
        interpreter.interpret(result.ir)
        assertEquals(listOf(3, 4, 4), lines, "loop body line fires per iteration")
    }

    @Test
    fun releaseBuildsHaveNoInstrumentation() {
        val result = Compiler().compile("import std.io\nfunc main() {\n    std::println(1)\n}") as CompilationResult.Success
        assertTrue("__dbg" !in result.ir.prettyPrint())
    }

    @Test
    fun outputStreamsLive() {
        val result = compileDebug("import std.io\nfunc main() {\n    std::println(\"a\")\n    std::println(\"b\")\n}")
        val streamed = mutableListOf<String>()
        val interpreter = IrInterpreter()
        interpreter.outputListener = { streamed.add(it) }
        interpreter.interpret(result.ir)
        assertEquals(listOf("a", "b"), streamed)
    }

    @Test
    fun debugBuildRunsIdenticallyWithoutHost() {
        val source = "import std.io\nfunc main() {\n    var total = 0\n    for i in 1..5 {\n        total = total + i\n    }\n    std::println(total)\n}"
        val debug = compileDebug(source)
        assertEquals("15", IrInterpreter().interpret(debug.ir))
    }
}
