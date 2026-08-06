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
import kotlin.test.assertTrue

/**
 * Operator families, `DIPs/OPERATOR_OVERLOADING_DIP.MD` §12.1–12.2.
 *
 * `Arithmetic` groups `+ - * / %` and their in-place twins into one spec, and a
 * type implements the part it wants - the rule that makes grouping better than
 * the ten specs it replaces rather than worse.
 */
class OperatorFamilyTest {
    private fun compile(source: String) = Compiler().compile(source, release = false)

    private fun run(source: String): String {
        val result = compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    /** The DIP's own example: a Matrix that only adds in place. */
    @Test fun aFamilyMayBeImplementedInPart() {
        assertEquals("7", run("""
            import std.io
            import std.traits
            pack Acc {
                var total: Int
            }
            impl Arithmetic for Acc {
                oper+= [self: Self!](rhs: Self&) {
                    self.total = self.total + rhs.total
                }
            }
            func main() {
                var acc = Acc(3)
                acc += Acc(4)
                std::println(acc.total)
            }
        """.trimIndent()))
    }

    /** A named member of a spec is still required - only operators are optional. */
    @Test fun aNamedMemberIsStillRequired() {
        val result = compile("""
            spec Sized {
                func size[self: Self&](): Int
                func capacity[self: Self&](): Int
            }
            pack Buffer {
                var n: Int
            }
            impl Sized for Buffer {
                func size[self: Self&](): Int { return self.n }
            }
            func main() {}
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result, "a missing func member must still be reported")
        assertTrue(
            result.errors.any { "does not implement" in it && "capacity" in it },
            "the error should name the missing member, got: ${result.errors}",
        )
    }

    /** Several operators of one family, in one impl block. */
    @Test fun oneImplCarriesTheWholeFamily() {
        assertEquals("5\n1\n6", run("""
            import std.io
            import std.traits
            pack N {
                var v: Int
            }
            impl Arithmetic for N {
                oper+ [self: Self&](rhs: Self&): N { return N(self.v + rhs.v) }
                oper- [self: Self&](rhs: Self&): N { return N(self.v - rhs.v) }
                oper* [self: Self&](rhs: Self&): N { return N(self.v * rhs.v) }
            }
            func main() {
                std::println((N(3) + N(2)).v)
                std::println((N(3) - N(2)).v)
                std::println((N(3) * N(2)).v)
            }
        """.trimIndent()))
    }

    /** `Neg` is its own spec: unary and binary `-` are different operations. */
    @Test fun negationIsSeparateFromSubtraction() {
        assertEquals("-3\n1", run("""
            import std.io
            import std.traits
            pack N {
                var v: Int
            }
            impl Neg for N {
                oper- [self: Self&]: N { return N(0 - self.v) }
            }
            impl Arithmetic for N {
                oper- [self: Self&](rhs: Self&): N { return N(self.v - rhs.v) }
            }
            func main() {
                std::println((-N(3)).v)
                std::println((N(3) - N(2)).v)
            }
        """.trimIndent()))
    }

    /** A `where T: Arithmetic` bound accepts a partial implementor. */
    @Test fun aFamilyBoundAcceptsAPartialImplementor() {
        assertEquals("5", run("""
            import std.io
            import std.traits
            pack N {
                var v: Int
            }
            impl Arithmetic for N {
                oper+ [self: Self&](rhs: Self&): N { return N(self.v + rhs.v) }
            }
            func total<T>(a: T, b: T): T where T: Arithmetic {
                return a + b
            }
            func main() {
                std::println(total(N(3), N(2)).v)
            }
        """.trimIndent()))
    }
}
