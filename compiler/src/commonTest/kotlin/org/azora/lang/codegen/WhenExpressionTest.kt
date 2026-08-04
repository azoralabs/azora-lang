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
 * `when` in expression position.
 *
 * All three forms share one branch parser, so these cover the value form
 * (initializers, arguments, operands), the `return when` form — which lowers to
 * a statement `when` and therefore keeps slot destructuring — and the rule that
 * `else` is optional once every case is listed.
 */
class WhenExpressionTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}"
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun failure(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Failure>(result, "expected this to fail to compile")
        return result.errors.joinToString("\n")
    }

    @Test fun whenIsAnExpressionInAnInitializer() {
        assertEquals("2", run("""
            import std.io
            enum Kind { A, B, C }
            func pick(k: Kind): Int {
                fin v = when k {
                    Kind.A -> 1
                    Kind.B -> 2
                    else -> 3
                }
                return v
            }
            func main() {
                std::println(pick(Kind.B))
            }
        """.trimIndent()))
    }

    @Test fun whenIsAnExpressionInAnArgument() {
        assertEquals("14", run("""
            import std.io
            enum Kind { A, B }
            func doubled(n: Int): Int { return n * 2 }
            func main() {
                std::println(doubled(when Kind.A { Kind.A -> 7  else -> 1 }))
            }
        """.trimIndent()))
    }

    @Test fun elseIsOptionalOnceEveryCaseIsListed() {
        assertEquals("30", run("""
            import std.io
            enum Kind { A, B, C }
            func pick(k: Kind): Int {
                fin v = when k {
                    Kind.A -> 10
                    Kind.B -> 20
                    Kind.C -> 30
                }
                return v
            }
            func main() {
                std::println(pick(Kind.C))
            }
        """.trimIndent()))
    }

    @Test fun theGuardFormSelectsOnConditions() {
        assertEquals("negative\nzero\npositive", run("""
            import std.io
            func label(n: Int): String {
                return when true {
                    n < 0 -> "negative"
                    n == 0 -> "zero"
                    else -> "positive"
                }
            }
            func main() {
                std::println(label(-4))
                std::println(label(0))
                std::println(label(9))
            }
        """.trimIndent()))
    }

    @Test fun aBranchTakesSeveralPatterns() {
        assertEquals("10\n10\n20", run("""
            import std.io
            enum Kind { A, B, C }
            func pick(k: Kind): Int {
                return when k {
                    Kind.A, Kind.B -> 10
                    else -> 20
                }
            }
            func main() {
                std::println(pick(Kind.A))
                std::println(pick(Kind.B))
                std::println(pick(Kind.C))
            }
        """.trimIndent()))
    }

    @Test fun whenExpressionsNest() {
        assertEquals("100\n50\n0", run("""
            import std.io
            enum Kind { A, B }
            func pick(k: Kind, n: Int): Int {
                fin v = when k {
                    Kind.A -> when true {
                        n > 5 -> 100
                        else -> 50
                    }
                    else -> 0
                }
                return v
            }
            func main() {
                std::println(pick(Kind.A, 9))
                std::println(pick(Kind.A, 2))
                std::println(pick(Kind.B, 9))
            }
        """.trimIndent()))
    }

    @Test fun returnWhenDestructuresASlotPayload() {
        assertEquals("12\n25\n0", run("""
            import std.io
            variant Shape {
                Circle(r: Int)
                Rect(w: Int, h: Int)
                Empty
            }
            func area(s: Shape): Int {
                return when s {
                    Shape.Circle(r) -> r * r
                    Shape.Rect(w, h) -> w * h
                    Shape.Empty() -> 0
                }
            }
            func main() {
                std::println(area(Shape.Rect(3, 4)))
                std::println(area(Shape.Circle(5)))
                std::println(area(Shape.Empty))
            }
        """.trimIndent()))
    }

    @Test fun aReturnWhenBranchTakesABlockEndingInItsValue() {
        assertEquals("25", run("""
            import std.io
            enum Kind { A, B }
            func pick(k: Kind): Int {
                return when k {
                    Kind.A -> {
                        fin base = 5
                        base * base
                    }
                    else -> 0
                }
            }
            func main() {
                std::println(pick(Kind.A))
            }
        """.trimIndent()))
    }

    @Test fun statementWhenStillWorksUnchanged() {
        assertEquals("hit B", run("""
            import std.io
            enum Kind { A, B }
            func main() {
                when Kind.B {
                    Kind.A -> { std::println("hit A") }
                    Kind.B -> { std::println("hit B") }
                }
            }
        """.trimIndent()))
    }

    @Test fun destructuringInAnExpressionIsRefusedWithAdvice() {
        val errors = failure("""
            variant Shape {
                Circle(r: Int)
                Empty
            }
            func area(s: Shape): Int {
                fin v = when s {
                    Shape.Circle(r) -> r * r
                    else -> 0
                }
                return v
            }
            func main() { }
        """.trimIndent())
        assertTrue(
            errors.contains("cannot destructure"),
            "the error must explain that an expression cannot bind a payload, got: $errors"
        )
        assertTrue(
            errors.contains("return when"),
            "the error must point at the form that does work, got: $errors"
        )
    }

    @Test fun anEmptyWhenExpressionIsRefused() {
        val errors = failure("""
            enum Kind { A }
            func pick(k: Kind): Int {
                fin v = when k {
                }
                return v
            }
            func main() { }
        """.trimIndent())
        assertTrue(
            errors.contains("at least one branch"),
            "a `when` expression with no branches must be refused, got: $errors"
        )
    }

    @Test fun anIfExpressionConditionMayEndInACall() {
        // The branch's `{` must not be taken for a trailing lambda on the call.
        assertEquals("yes", run("""
            import std.io
            func ready(): Bool { return true }
            func main() {
                fin answer = if ready() { "yes" } else { "no" }
                std::println(answer)
            }
        """.trimIndent()))
    }

    @Test fun aWhenScrutineeMayEndInACall() {
        assertEquals("two", run("""
            import std.io
            func value(): Int { return 2 }
            func main() {
                fin name = when value() {
                    1 -> "one"
                    2 -> "two"
                    else -> "many"
                }
                std::println(name)
            }
        """.trimIndent()))
    }
}
