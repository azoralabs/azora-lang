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
 * `unsafe union` - a C-style untagged union.
 *
 * Every member starts at offset 0 and the whole thing is as wide as its widest
 * member, so writing one member and reading another reinterprets the same
 * storage. Nothing records which member is live, so no check can establish that
 * a read is meaningful - which is why both the declaration and every use ask for
 * `unsafe`. For a union that *does* record its live case, see `variant enum`.
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

    private fun run(source: String): String =
        IrInterpreter().interpret(success(source).ir).trim()

    private val value = """
        unsafe union Value {
            i: Int
            d: Double
            b: Bool
        }
    """.trimIndent()

    @Test
    fun aUnionIsDeclaredAndCarriedInTheIr() {
        val result = success("""
            $value
            func main() {
                unsafe { var v = Value(i: 42) }
            }
        """)
        val struct = result.ir.items.filterIsInstance<IrTopLevel.Struct>().single { it.name == "Value" }
        assertTrue(struct.isUnion, "the IR must carry the union flag so backends can overlap the members")
        assertEquals(listOf("i", "d", "b"), struct.fields.map { it.name })
    }

    @Test
    fun unionIsStillUsableAsAnOrdinaryName() {
        // `union` is contextual: only `unsafe union Name {` declares one.
        success("""
            import std.io
            func main() {
                fin union = 1
                println(union)
            }
        """)
    }

    // -- the unsafe requirement ----------------------------------------------

    @Test
    fun aUnionMustBeDeclaredUnsafe() = rejects("""
        union Value {
            i: Int
        }
    """, "declare it 'unsafe union Value'")

    @Test
    fun constructingAUnionNeedsAnUnsafeBlock() = rejects("""
        $value
        func main() {
            var v = Value(i: 42)
        }
    """, "union 'Value' can only be used inside an 'unsafe { … }' block")

    @Test
    fun readingAMemberNeedsAnUnsafeBlock() = rejects("""
        import std.io
        $value
        func main() {
            unsafe { var v = Value(i: 42) }
            var w = Value(i: 1)
        }
    """, "can only be used inside an 'unsafe { … }' block")

    @Test
    fun anUnsafeFunctionIsEnoughOfAContext() = assertEquals("42", run("""
        import std.io
        $value
        unsafe func show() {
            var v = Value(i: 42)
            println(v.i)
        }
        func main() { unsafe { show() } }
    """))

    // -- behaviour -----------------------------------------------------------

    @Test
    fun aMemberIsReadBackAfterBeingWritten() = assertEquals("42\n7", run("""
        import std.io
        $value
        func main() {
            unsafe {
                var v = Value(i: 42)
                println(v.i)
                v.i = 7
                println(v.i)
            }
        }
    """))

    @Test
    fun writingOneMemberIsSeenByTheOthers() = assertEquals("true", run("""
        import std.io
        $value
        func main() {
            unsafe {
                var v = Value(i: 1)
                v.b = true
                println(v.b)
            }
        }
    """))

    // -- construction --------------------------------------------------------

    @Test
    fun aUnionIsBuiltFromExactlyOneMember() = rejects("""
        $value
        func main() {
            unsafe { var v = Value(i: 1, d: 2.0) }
        }
    """, "is built from exactly one member")

    @Test
    fun aUnionCannotBeBuiltFromNoMember() = rejects("""
        $value
        func main() {
            unsafe { var v = Value() }
        }
    """, "is built from exactly one member")

    @Test
    fun anUnknownMemberIsRejected() = rejects("""
        $value
        func main() {
            unsafe { var v = Value(nope: 1) }
        }
    """, "has no member 'nope'")

    @Test
    fun aMemberIsTypeChecked() = rejects("""
        $value
        func main() {
            unsafe { var v = Value(b: 3) }
        }
    """, "member 'b' of union 'Value'")

    @Test
    fun anEmptyUnionIsRejected() = rejects("""
        unsafe union Nothing {
        }
        func main() {}
    """, "must declare at least one member")

    @Test
    fun theValueAxisAppliesToAUnionToo() = rejects("""
        $value
        func main() {
            unsafe {
                fin v = Value(i: 1)
                v.i = 2
            }
        }
    """, "cannot assign to member 'i' through 'v'")
}
