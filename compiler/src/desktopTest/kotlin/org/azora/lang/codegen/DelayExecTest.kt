/*
 * Copyright 2026 AzoraLabs
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

package org.azora.lang.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.azora.lang.Compiler
import org.azora.lang.CompilationResult

/**
 * `delay <ms>` suspends the current task.
 *
 * On LLVM it lowers to libc's `usleep`; the Wasm MVP target has no host clock to
 * sleep against, so it reports an unsupported-target diagnostic. A program
 * requiring a delay must never compile to a silent no-op.
 */
class DelayExecTest {

    private val program = """
        import std.io
        func main() {
            println("start")
            delay 5
            println("end")
        }
    """.trimIndent()

    @Test fun delayRunsOnLlvm() {
        if (!LlvmExec.available) return
        assertEquals("start\nend", LlvmExec.run(program), "debug IR")
        assertEquals("start\nend", LlvmExec.run(program, optimized = true), "optimized IR")
    }

    @Test fun wasmDelayRequiresAHostClock() {
        val r = assertIs<CompilationResult.Success>(Compiler().compile(program))
        assertTrue(r.backendErrors["wasm"]?.contains("host clock") == true, r.backendErrors.toString())
        assertEquals("", r.wasm)
    }
}
