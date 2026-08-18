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
 * The type a `when` or `if` expression's arms are read in.
 *
 * [WhenExpressionTest] covers the forms themselves; this covers what a `.Name`
 * inside one is understood to belong to. A `when` expression desugars to a
 * chain of if-expressions, so its patterns reach the resolver as equalities
 * against the scrutinee rather than as patterns - each case below fixes one
 * position where the type has to be carried across that rewrite.
 */
class WhenExpressionTypingTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    private val enums = """
        import std.io
        enum Color { Red, Blue }
        enum Width { Thin, Thick }
    """.trimIndent()

    @Test fun whenExpressionArmsAreTypedFromTheScrutinee() {
        assertEquals("Width.Thin\nWidth.Thick", run("""
            $enums
            func pick(c: Color): Width {
                fin w: Width = when c {
                    .Red -> .Thin
                    else -> .Thick
                }
                return w
            }
            func main() {
                println(pick(Color.Red))
                println(pick(Color.Blue))
            }
        """.trimIndent()))
    }

    /**
     * A branching argument is still an argument: the parameter's type states
     * what each arm's `.Name` means.
     *
     * Deliberately not tested through `return when`: there, `.Thin` is the
     * error-variant shorthand (`return .Variant` fails the function), so a
     * `when` in return position means something else entirely.
     */
    @Test fun argumentPositionTypesBothArms() {
        assertEquals("Width.Thin\nWidth.Thick", run("""
            $enums
            func show(w: Width) {
                println(w)
            }
            func report(c: Color) {
                show(when c {
                    .Red -> .Thin
                    else -> .Thick
                })
            }
            func main() {
                report(Color.Red)
                report(Color.Blue)
            }
        """.trimIndent()))
    }

    @Test fun ifExpressionBranchesAreTypedFromTheBinding() {
        assertEquals("Width.Thin\nWidth.Thick", run("""
            $enums
            func pick(c: Color): Width {
                fin w: Width = if c == .Red { .Thin } else { .Thick }
                return w
            }
            func main() {
                println(pick(Color.Red))
                println(pick(Color.Blue))
            }
        """.trimIndent()))
    }

    @Test fun comparisonStatesTheTypeOfABareMember() {
        assertEquals("true\nfalse", run("""
            $enums
            func main() {
                fin c = Color.Red
                println(c == .Red)
                println(c == .Blue)
            }
        """.trimIndent()))
    }

    @Test fun statementWhenPatternsNameVariantsWithoutTheirType() {
        assertEquals("red\nblue", run("""
            $enums
            func report(c: Color) {
                when c {
                    .Red -> { println("red") }
                    .Blue -> { println("blue") }
                }
            }
            func main() {
                report(Color.Red)
                report(Color.Blue)
            }
        """.trimIndent()))
    }

    /**
     * Writing the patterns the short way must not cost exhaustiveness: an
     * `else`-less `when` that covers every variant as `.Name` is as complete as
     * one that spells each type out.
     */
    @Test fun bareMemberPatternsCountTowardsExhaustiveness() {
        assertEquals("blue", run("""
            $enums
            func main() {
                fin c = Color.Blue
                when c {
                    .Red -> { println("red") }
                    .Blue -> { println("blue") }
                }
            }
        """.trimIndent()))
    }

    /**
     * A pack field default may be written as a `when` over the fields before
     * it, and it is the *construction* that decides which branch that takes.
     */
    @Test fun packFieldDefaultReadsAnEarlierField() {
        assertEquals("Width.Thin\nWidth.Thick", run("""
            $enums
            pack Pen {
                color: Color = .Blue
                width: Width = when color {
                    .Red -> .Thin
                    else -> .Thick
                }
            }
            func main() {
                println(Pen(Color.Red).width)
                println(Pen(Color.Blue).width)
            }
        """.trimIndent()))
    }
}
