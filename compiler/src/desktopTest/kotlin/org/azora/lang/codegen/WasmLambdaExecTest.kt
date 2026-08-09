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

/** WebAssembly execution coverage for first-class callable values. */
class WasmLambdaExecTest {
    private fun check(expected: String, source: String) {
        if (!WasmExec.available) return
        assertEquals(expected, WasmExec.run(source))
    }

    @Test
    fun storedLambdaCallsThroughTheFunctionTable() = check(
        "10",
        """
        import std.io
        func main() {
            fin double = { value: std::Int -> return value * 2 }
            std::println(double(5))
        }
        """.trimIndent(),
    )

    @Test
    fun closureLoadsCapturedValuesFromItsEnvironment() = check(
        "7",
        """
        import std.io
        func main() {
            fin offset = 3
            fin add = [; offset.&] { value: std::Int -> return value + offset }
            std::println(add(4))
        }
        """.trimIndent(),
    )

    @Test
    fun mutableCaptureAndOuterBindingShareOneCell() = check(
        "5\n9\n9",
        """
        import std.io
        func main() {
            var n = 1
            fin add = [; n.!] { value: std::Int -> n = n + value
                return n }
            fin read = [; n.&] { n }
            std::println(add(4))
            n = 9
            std::println(read())
            std::println(n)
        }
        """.trimIndent(),
    )

    @Test
    fun copyDefaultSnapshotsOnlyReferencedValues() = check(
        "3",
        """
        import std.io
        func main() {
            var n = 3
            fin read = [; =] { n }
            n = 8
            std::println(read())
        }
        """.trimIndent(),
    )

    @Test
    fun closureCanCrossAHigherOrderFunctionBoundary() = check(
        "16",
        """
        import std.io
        func apply(operation: (std::Int) -> std::Int, value: std::Int): std::Int {
            return operation(value)
        }
        func main() {
            fin square = { value: std::Int -> return value * value }
            std::println(apply(square, 4))
        }
        """.trimIndent(),
    )

    @Test
    fun closureEnvironmentPreservesMixedMachineWidths() = check(
        "7.5",
        """
        import std.io
        func main() {
            fin whole = 3
            fin fraction = 0.5
            fin add = [; whole.&, fraction.&] { value: std::Double ->
                return value + whole + fraction
            }
            std::println(add(4.0))
        }
        """.trimIndent(),
    )

    @Test
    fun contextualReceiversUseTheSameClosureAbi() = check(
        "10",
        """
        import std.io
        fin scale: [std::Int](std::Int) -> std::Int =
            [value: std::Int] { factor: std::Int -> value * factor }

        func main() {
            with 5 {
                std::println(scale(2))
            }
        }
        """.trimIndent(),
    )
}
