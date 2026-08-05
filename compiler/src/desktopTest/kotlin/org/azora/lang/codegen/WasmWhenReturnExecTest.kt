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
 * A value-returning function whose every path returns from inside a `when` or
 * an `if`.
 *
 * Such a body lowers to `(if …)` blocks that type as `[]`, so the Wasm
 * validator rejected it with "type mismatch in implicit return, expected [i32]
 * but got []" even though control never reaches the end. `emitFunction` now
 * closes the body with `unreachable`, which types as bottom.
 */
class WasmWhenReturnExecTest {
    private fun check(expected: String, source: String) {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(source))
    }

    @Test
    fun whenWithEveryArmReturningValidates() = check(
        "20\n10\n0",
        """
        import std.io
        enum Direction {
            Up
            Down
            Level
        }
        func step(d: Direction): Int {
            when d {
                Direction.Up -> { return 20 }
                Direction.Down -> { return 10 }
                else -> { return 0 }
            }
        }
        func main() {
            std::println(step(Direction.Up))
            std::println(step(Direction.Down))
            std::println(step(Direction.Level))
        }
        """.trimIndent(),
    )

    @Test
    fun ifChainWithEveryBranchReturningValidates() = check(
        "1\n2",
        """
        import std.io
        func classify(n: Int): Int {
            if n < 0 {
                return 1
            } else {
                return 2
            }
        }
        func main() {
            std::println(classify(-5))
            std::println(classify(5))
        }
        """.trimIndent(),
    )
}
