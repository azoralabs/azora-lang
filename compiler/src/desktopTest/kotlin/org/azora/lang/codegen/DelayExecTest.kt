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

/**
 * `delay <ms>` suspends the current task.
 *
 * On LLVM it lowers to libc's `usleep`; the Wasm MVP target has no host clock to
 * sleep against, so it degrades to a no-op there — in both cases the program has
 * to still run and produce its output, which is what these check.
 */
class DelayExecTest {

    private val program = """
        import std.io
        func main() {
            std::println("start")
            delay 5
            std::println("end")
        }
    """.trimIndent()

    @Test fun delayRunsOnLlvm() {
        if (!LlvmExec.available) return
        assertEquals("start\nend", LlvmExec.run(program), "debug IR")
        assertEquals("start\nend", LlvmExec.run(program, optimized = true), "optimized IR")
    }

    @Test fun delayRunsOnWasm() {
        if (!WasmExec.available) return
        assertEquals("start\nend", WasmExec.run(program))
    }
}
