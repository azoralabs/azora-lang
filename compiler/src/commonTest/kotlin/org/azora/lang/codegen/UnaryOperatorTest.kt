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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unary operator overloads.
 *
 * `DIPs/OPERATOR_OVERLOADING_DIP.MD` §2.6: these never ran. `Expr.Unary`
 * lowering mapped the token straight to an `IrUnaryOp` with no overload lookup,
 * and the resolver rejected any non-numeric operand before that point - so a
 * declared unary `oper-` was dead code, `oper~` was unreachable, and `oper!`
 * could not be written at all.
 */
class UnaryOperatorTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun negationDispatchesToTheDeclaredOperator() {
        assertEquals("-3\n-4", run("""
            import std.io
            pack Vec2 {
                var x: Int
                var y: Int
            }
            oper- [self: Vec2&]: Vec2 {
                return Vec2(0 - self.x, 0 - self.y)
            }
            func main() {
                fin negated = -Vec2(3, 4)
                println(negated.x)
                println(negated.y)
            }
        """.trimIndent()))
    }

    /** `oper!` could not previously be spelled at all. */
    @Test fun logicalNotDispatchesToTheDeclaredOperator() {
        assertEquals("true\nfalse", run("""
            import std.io
            pack Flag {
                var on: Bool
            }
            oper! [self: Flag&]: Flag {
                return Flag(!self.on)
            }
            func main() {
                println((!Flag(false)).on)
                println((!Flag(true)).on)
            }
        """.trimIndent()))
    }

    @Test fun bitwiseNotDispatchesToTheDeclaredOperator() {
        assertEquals("5", run("""
            import std.io
            pack Mask {
                var bits: Int
            }
            oper~ [self: Mask&]: Mask {
                return Mask(5)
            }
            func main() {
                println((~Mask(0)).bits)
            }
        """.trimIndent()))
    }

    /**
     * Unary and binary `-` are separate members, told apart by operand count.
     * Declaring both must keep them distinct.
     */
    @Test fun unaryAndBinaryMinusCoexist() {
        assertEquals("-2\n5", run("""
            import std.io
            pack Num {
                var v: Int
            }
            oper- [self: Num&]: Num {
                return Num(0 - self.v)
            }
            oper- [self: Num&](rhs: Num&): Num {
                return Num(self.v - rhs.v)
            }
            func main() {
                println((-Num(2)).v)
                println((Num(7) - Num(2)).v)
            }
        """.trimIndent()))
    }

    /** The built-in unary operators are untouched. */
    @Test fun builtInUnaryOperatorsStillWork() {
        assertEquals("-5\nfalse\n-1", run("""
            import std.io
            func main() {
                println(-5)
                println(!true)
                println(~0)
            }
        """.trimIndent()))
    }
}
