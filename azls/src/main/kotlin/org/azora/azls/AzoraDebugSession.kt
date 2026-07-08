/*
 * Copyright 2026 AzoraTech
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.azls

import kotlinx.coroutines.channels.Channel
import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.AzoraDebugHost
import org.azora.lang.backend.IrInterpreter
import kotlin.concurrent.thread

/**
 * One interpreter run under debugger control.
 *
 * The program is compiled in debug mode (every statement carries a
 * `__dbg(line)` marker) and executed on a dedicated thread. The debug host
 * pauses inside [AzoraDebugHost.onLine] — suspending on a command channel —
 * whenever a breakpoint (or step mode) hits a line belonging to the edited
 * document; prelude lines (other files, engine libraries) never pause.
 *
 * All state transitions are driven by simple commands so the session can be
 * polled/steered across the reflective classloader boundary from Studio.
 */
internal class AzoraDebugSession(
    source: String,
    prelude: String,
    initialBreakpoints: Set<Int>,
) {
    private enum class Command { RESUME, STEP, STOP }

    private val preludeLines = if (prelude.isBlank()) 0 else prelude.lines().size
    private val fullSource = if (prelude.isBlank()) source else prelude + "\n" + source

    @Volatile var status: String = "starting"; private set
    @Volatile var pausedLine: Int = 0; private set

    /** Monotonic pause counter so pollers can distinguish consecutive pauses. */
    @Volatile var pauseId: Int = 0; private set
    @Volatile var locals: List<Pair<String, String>> = emptyList(); private set
    @Volatile var error: String? = null; private set

    @Volatile private var breakpoints: Set<Int> = initialBreakpoints
    @Volatile private var stepMode = false

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pendingOutput = StringBuilder()

    private class StoppedByUser : RuntimeException("stopped by debugger")

    fun start() {
        val result = try {
            Compiler().compile(fullSource, release = false, debug = true)
        } catch (e: Exception) {
            status = "failed"
            error = e.message ?: "compile failed"
            return
        }
        if (result !is CompilationResult.Success) {
            status = "failed"
            error = (result as CompilationResult.Failure).errors.joinToString("\n")
            return
        }
        status = "running"
        thread(isDaemon = true, name = "azora-debug-session") {
            val interpreter = IrInterpreter()
            interpreter.outputListener = { line -> synchronized(pendingOutput) { pendingOutput.appendLine(line) } }
            interpreter.debugHost = object : AzoraDebugHost {
                override suspend fun onLine(line: Int, localVars: Map<String, Any?>) {
                    val docLine = line - preludeLines
                    if (docLine < 1) return // library/prelude code — never pause there
                    if (!stepMode && docLine !in breakpoints) return
                    pausedLine = docLine
                    locals = localVars.map { (name, value) -> name to formatValue(value) }
                    pauseId++
                    status = "paused"
                    when (commands.receive()) {
                        Command.RESUME -> { stepMode = false; status = "running" }
                        Command.STEP -> { stepMode = true; status = "running" }
                        Command.STOP -> throw StoppedByUser()
                    }
                }
            }
            try {
                interpreter.interpret(result.ir)
                status = "terminated"
            } catch (e: StoppedByUser) {
                status = "terminated"
            } catch (e: Exception) {
                if (e.cause is StoppedByUser || e.message == "stopped by debugger") {
                    status = "terminated"
                } else {
                    error = e.message
                    status = "terminated"
                }
            }
        }
    }

    fun resume() { if (status == "paused") commands.trySend(Command.RESUME) }
    fun step() {
        if (status == "paused") {
            commands.trySend(Command.STEP)
        } else if (status == "running") {
            stepMode = true // pause at the next line
        }
    }
    fun stop() {
        stepMode = true // makes the very next `__dbg` marker pause…
        commands.trySend(Command.STOP) // …where this queued STOP unwinds the run
    }

    fun setBreakpoints(lines: Set<Int>) { breakpoints = lines }

    /** Output produced since the previous drain (streamed to the Studio console). */
    fun drainOutput(): String = synchronized(pendingOutput) {
        val text = pendingOutput.toString()
        pendingOutput.setLength(0)
        text
    }

    private fun formatValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${value.take(80)}\""
        is List<*> -> "[${value.take(8).joinToString(", ") { formatValue(it) }}${if (value.size > 8) ", …" else ""}]"
        is Map<*, *> -> "{${value.entries.take(6).joinToString(", ") { "${it.key}: ${formatValue(it.value)}" }}}"
        else -> value.toString().take(120)
    }
}
