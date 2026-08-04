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
 * End-to-end `lli` tests for `union`.
 *
 * These pin down what an untagged union actually is: one block of storage every
 * member addresses. The interpreter models a union as a single value slot until
 * the self-hosted compiler gives it a real memory model, so for now this is the
 * only place the byte-level view is observable.
 */
class UnionExecTest {

    private fun check(expected: String, source: String) {
        if (!LlvmExec.available) return
        assertEquals(expected, LlvmExec.run(source), "debug IR")
        assertEquals(expected, LlvmExec.run(source, optimized = true), "optimized IR")
    }

    @Test fun aMemberSurvivesARoundTrip() = check("42", """
        import std.io
        unsafe union Value {
            i: Int
            d: Double
        }
        func main() {
            unsafe {
                var v = Value(i: 42)
                std::println(v.i)
            }
        }
    """.trimIndent())

    @Test fun writingThenReadingTheSameMemberIsStable() = check("7\n9", """
        import std.io
        unsafe union Value {
            i: Int
            d: Double
        }
        func main() {
            unsafe {
                var v = Value(i: 7)
                std::println(v.i)
                v.i = 9
                std::println(v.i)
            }
        }
    """.trimIndent())

    @Test fun theMembersShareOneStorageSlot() = check("1", """
        import std.io
        unsafe union Flag {
            raw: Int
            on: Bool
        }
        func main() {
            unsafe {
                var f = Flag(on: true)
                std::println(f.raw)
            }
        }
    """.trimIndent())

    @Test fun aUnionIsAsWideAsItsWidestMember() = check("1.5", """
        import std.io
        unsafe union Scalar {
            small: Byte
            wide: Double
        }
        func main() {
            unsafe {
                // `wide` is the widest member, so the storage is 8 bytes and the
                // double survives intact even though `small` is declared first.
                var n = Scalar(wide: 1.5)
                std::println(n.wide)
            }
        }
    """.trimIndent())
}
