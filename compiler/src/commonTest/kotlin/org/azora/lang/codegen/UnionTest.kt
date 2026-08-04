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

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrTopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `union` — a C-style untagged union.
 *
 * Every member starts at offset 0 and the whole thing is as wide as its widest
 * member, so writing one member and reading another reinterprets the same
 * storage. Nothing records which member is live; that is what separates a
 * `union` from a `variant`.
 */
class UnionTest {
    private fun compile(source: String): CompilationResult = Compiler().compile(source.trimIndent())

    private fun success(source: String): CompilationResult.Success {
        val result = compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return result
    }

    private fun rejects(source: String, needle: String) {
        val failure = assertIs<CompilationResult.Failure>(compile(source))
        assertTrue(failure.errors.any { needle in it }, failure.errors.toString())
    }

    private val value = """
        union Value {
            var i: Int
            var d: Double
            var b: Bool
        }
    """.trimIndent()

    @Test
    fun aUnionIsDeclaredAndCarriedInTheIr() {
        val result = success("""
            $value
            func main() {
                var v = Value(i: 42)
            }
        """)
        val struct = result.ir.items.filterIsInstance<IrTopLevel.Struct>().single { it.name == "Value" }
        assertTrue(struct.isUnion, "the IR must carry the union flag so backends can overlap the members")
        assertEquals(listOf("i", "d", "b"), struct.fields.map { it.name })
    }

    @Test
    fun unionIsStillUsableAsAnOrdinaryName() {
        // `union` is contextual: only `union Name {` declares one.
        success("""
            import std.io
            func main() {
                fin union = 1
                std::println(union)
            }
        """)
    }

    @Test
    fun aMemberIsReadBackAfterBeingWritten() {
        val out = success("""
            import std.io
            $value
            func main() {
                var v = Value(i: 42)
                std::println(v.i)
                v.i = 7
                std::println(v.i)
            }
        """)
        assertEquals("42\n7", run(out))
    }

    @Test
    fun writingOneMemberIsSeenByTheOthers() {
        // The members share one slot, so the last write is what every member sees.
        val out = success("""
            import std.io
            $value
            func main() {
                var v = Value(i: 1)
                v.b = true
                std::println(v.b)
            }
        """)
        assertEquals("true", run(out))
    }

    @Test
    fun aUnionIsBuiltFromExactlyOneMember() {
        rejects("""
            $value
            func main() {
                var v = Value(i: 1, d: 2.0)
            }
        """, "is built from exactly one member")
    }

    @Test
    fun aUnionCannotBeBuiltFromNoMember() {
        rejects("""
            $value
            func main() {
                var v = Value()
            }
        """, "is built from exactly one member")
    }

    @Test
    fun anUnknownMemberIsRejected() {
        rejects("""
            $value
            func main() {
                var v = Value(nope: 1)
            }
        """, "has no member 'nope'")
    }

    @Test
    fun aMemberIsTypeChecked() {
        rejects("""
            $value
            func main() {
                var v = Value(b: 3)
            }
        """, "member 'b' of union 'Value'")
    }

    @Test
    fun anEmptyUnionIsRejected() {
        rejects("""
            union Nothing {
            }
            func main() {}
        """, "must declare at least one member")
    }

    @Test
    fun theValueAxisAppliesToAUnionToo() {
        rejects("""
            $value
            func main() {
                fin v = Value(i: 1)
                v.i = 2
            }
        """, "cannot assign to member 'i' through 'v'")
    }

    private fun run(result: CompilationResult.Success): String =
        IrInterpreter().interpret(result.ir).trim()
}
