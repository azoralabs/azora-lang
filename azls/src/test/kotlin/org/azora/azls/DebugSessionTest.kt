package org.azora.azls

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** End-to-end debug sessions through the polled JSON protocol. */
class DebugSessionTest {

    private val azls = AzoraLanguageServer()
    private val json = Json { ignoreUnknownKeys = true }

    private fun status(): DebugStatus =
        json.decodeFromString(DebugStatus.serializer(), azls.debugStatus())

    /** Polls until the session reports [expected], failing after 5 seconds. */
    private fun awaitStatus(expected: String, collect: StringBuilder? = null): DebugStatus {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val current = status()
            collect?.append(current.output)
            if (current.status == expected) return current
            Thread.sleep(20)
        }
        fail("timed out waiting for status=$expected, last=${status()}")
    }

    /** Polls until a NEW pause (pauseId greater than [afterPauseId]) is reported. */
    private fun awaitPause(afterPauseId: Int): DebugStatus {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val current = status()
            if (current.status == "paused" && current.pauseId > afterPauseId) return current
            Thread.sleep(20)
        }
        fail("timed out waiting for a pause after id=$afterPauseId, last=${status()}")
    }

    private val program = """
        func main() {
            var total = 0
            for i in 1..3 {
                total = total + i
            }
            println(total)
        }
    """.trimIndent()

    @Test
    fun breakpointPausesWithLocals() {
        azls.debugStart(program, "", "[4]") // inside the loop body
        val paused = awaitPause(0)
        assertEquals(4, paused.line)
        assertTrue(paused.locals.any { it.name == "total" && it.value == "0" }, "${paused.locals}")
        assertTrue(paused.locals.any { it.name == "i" && it.value == "1" }, "${paused.locals}")

        // Resume hits the same breakpoint on the next iteration with updated state.
        azls.debugResume()
        val paused2 = awaitPause(paused.pauseId)
        assertEquals(4, paused2.line)
        assertTrue(paused2.locals.any { it.name == "i" && it.value == "2" }, "${paused2.locals}")

        azls.debugSetBreakpoints("[]")
        azls.debugResume()
        val out = StringBuilder()
        awaitStatus("terminated", out)
        assertEquals("6", out.toString().trim())
    }

    @Test
    fun stepAdvancesOneStatement() {
        azls.debugStart(program, "", "[2]")
        val paused = awaitPause(0)
        assertEquals(2, paused.line)

        azls.debugStep()
        val next = awaitPause(paused.pauseId)
        assertEquals(3, next.line, "step should stop at the for statement")

        azls.debugStop()
        awaitStatus("terminated")
    }

    @Test
    fun stopTerminatesRun() {
        azls.debugStart(program, "", "[2]")
        awaitStatus("paused")
        azls.debugStop()
        awaitStatus("terminated")
    }

    @Test
    fun runWithoutBreakpointsJustFinishes() {
        azls.debugStart("func main() {\n    println(\"done\")\n}", "", "[]")
        val out = StringBuilder()
        awaitStatus("terminated", out)
        assertEquals("done", out.toString().trim())
    }

    @Test
    fun compileFailureReportsError() {
        val result = azls.debugStart("func main( {", "", "[]")
        assertTrue("error" in result || "failed" in result, result)
    }

    @Test
    fun preludeLinesNeverPause() {
        val prelude = "func helper(): Int {\n    return 41\n}"
        azls.debugStart("func main() {\n    println(helper() + 1)\n}", prelude, "[2]")
        val paused = awaitPause(0)
        assertEquals(2, paused.line, "pause maps back to the document line")
        azls.debugResume()
        val out = StringBuilder()
        awaitStatus("terminated", out)
        assertEquals("42", out.toString().trim())
    }
}
