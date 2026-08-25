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
 * Compound assignment, `DIPs/OPERATOR_OVERLOADING_DIP.MD` §12.4.
 *
 * `a += b` desugars to `a = a + b` in the parser, which has no types to decide
 * with - so a type that declared an in-place `oper+=` never had it called.
 */
class CompoundAssignOperatorTest {
    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    /** With only `oper+`, `+=` is build-and-assign - which is right for a small value. */
    @Test fun compoundAssignmentFallsBackToTheBinaryOperator() {
        assertEquals("7", run("""
            import std.io
            pack Money {
                var cents: Int
            }
            oper+ Money&.(rhs: Money&): Money {
                return Money(self.cents + rhs.cents)
            }
            func main() {
                var total = Money(3)
                total += Money(4)
                println(total.cents)
            }
        """.trimIndent()))
    }

    /**
     * With `oper+=`, the in-place operator runs instead. The marker line proves
     * it: build-and-assign would never reach that body.
     */
    @Test fun declaredCompoundOperatorRunsInPlace() {
        assertEquals("in place\n7", run("""
            import std.io
            pack Acc {
                var total: Int
            }
            oper+= Acc!.(rhs: Acc&) {
                println("in place")
                self.total = self.total + rhs.total
            }
            func main() {
                var acc = Acc(3)
                acc += Acc(4)
                println(acc.total)
            }
        """.trimIndent()))
    }

    /** A type may declare both; `+=` takes the in-place one. */
    @Test fun inPlaceOperatorWinsOverTheBinaryOne() {
        assertEquals("in place\n7", run("""
            import std.io
            pack Acc {
                var total: Int
            }
            oper+ Acc&.(rhs: Acc&): Acc {
                println("built a new one")
                return Acc(self.total + rhs.total)
            }
            oper+= Acc!.(rhs: Acc&) {
                println("in place")
                self.total = self.total + rhs.total
            }
            func main() {
                var acc = Acc(3)
                acc += Acc(4)
                println(acc.total)
            }
        """.trimIndent()))
    }

    /** The other compound operators dispatch the same way. */
    @Test fun subtractAssignDispatchesToItsOperator() {
        assertEquals("2", run("""
            import std.io
            pack Acc {
                var total: Int
            }
            oper-= Acc!.(rhs: Acc&) {
                self.total = self.total - rhs.total
            }
            func main() {
                var acc = Acc(5)
                acc -= Acc(3)
                println(acc.total)
            }
        """.trimIndent()))
    }

    /** Compound assignment on the built-in types is untouched. */
    @Test fun builtInCompoundAssignmentStillWorks() {
        assertEquals("7\n4\n12", run("""
            import std.io
            func main() {
                var a = 3
                a += 4
                println(a)
                a -= 3
                println(a)
                a *= 3
                println(a)
            }
        """.trimIndent()))
    }
}
