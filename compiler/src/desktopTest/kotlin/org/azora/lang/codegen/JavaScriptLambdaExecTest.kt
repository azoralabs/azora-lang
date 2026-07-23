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

/** JavaScript execution coverage for stored and contextual callables. */
class JavaScriptLambdaExecTest {
    private fun check(expected: String, source: String) {
        if (!NodeExec.available) return
        assertEquals(expected, NodeExec.run(source))
    }

    @Test
    fun capturedLambdaIsCallable() = check(
        "7",
        """
        import std.io
        func main() {
            fin offset = 3
            fin add = func(value: Int) { return value + offset }
            std::println(add(4))
        }
        """.trimIndent(),
    )

    @Test
    fun contextualReceiverIsPassedToTheLambda() = check(
        "10",
        """
        import std.io
        fin scale: Func[Int](Int) -> Int =
            func(value: Int) { factor: Int -> value * factor }

        func main() {
            with 5 {
                std::println(scale(2))
            }
        }
        """.trimIndent(),
    )
}
