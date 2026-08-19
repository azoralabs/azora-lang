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
 * `.(args) * count` builds `count` values, and runs.
 *
 * Two things can be repeated, and they lower differently. An array is *filled* -
 * `count` slots holding the element default, which is what an array is. Anything
 * else runs the `ctor` that said it takes a repetition, with the count arriving
 * as its last argument, in the shape the declaration put it in.
 *
 * The same text is also how `a * b` reads when a type declares `oper*`, so every
 * test that decides "this is a repetition" is a guard: anything that does not fit
 * one falls back to being multiplication, silently. `N(3) * N(2)` must keep
 * multiplying.
 */
class RepeatedConstructionTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    private val buffer =
        """
        pack Buffer {
            var size: Int = 0
            var fill: Int = 0
        }

        impl Buffer {
            ctor[self: Self!]() * count {
                self.size = count
            }

            ctor[self: Self!](fill: Int) * count {
                self.size = count
                self.fill = fill
            }
        }
        """.trimIndent()

    // -- an array is filled --------------------------------------------------

    @Test fun anArrayRepetitionFillsThatManySlots() {
        assertEquals(
            "3",
            run(
                """
                import std.io
                import std.container.array
                func main() {
                    var x: Array<Int> = .() * 3
                    println(x.size)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun theCountMayBeAnExpression() {
        // `std/algorithm/algorithm.az` asks for `a.size + b.size` slots.
        assertEquals(
            "7",
            run(
                """
                import std.io
                import std.container.array
                func main() {
                    fin a = 3
                    fin b = 4
                    var x: Array<Int> = .() * a + b
                    println(x.size)
                }
                """.trimIndent(),
            ),
        )
    }

    // -- anything else runs its repeated ctor --------------------------------

    @Test fun aRepetitionRunsTheCtorThatDeclaredIt() {
        assertEquals("7", run("import std.io\n$buffer\nfunc main() {\n    var b: Buffer = .() * 7\n    println(b.size)\n}"))
    }

    @Test fun theWrittenArgumentsComeBeforeTheCount() {
        // `.(5) * 10` selects the two-parameter ctor: the `5` it was written with
        // and the `10` the repetition supplies.
        assertEquals(
            "10\n5",
            run(
                """
                import std.io
                $buffer
                func main() {
                    var b: Buffer = .(5) * 10
                    println(b.size)
                    println(b.fill)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun theOverloadsDoNotCollideAtTheCallSite() {
        // Both ctors are repeated; only the argument count separates them.
        assertEquals(
            "2\n0\n4\n9",
            run(
                """
                import std.io
                $buffer
                func main() {
                    var a: Buffer = .() * 2
                    var b: Buffer = .(9) * 4
                    println(a.size)
                    println(a.fill)
                    println(b.size)
                    println(b.fill)
                }
                """.trimIndent(),
            ),
        )
    }

    // -- multiplication is still multiplication ------------------------------

    @Test fun anOrdinaryProductIsUntouched() {
        assertEquals("42", run("import std.io\nfunc two(): Int { return 2 }\nfunc main() {\n    println(two() * 21)\n}"))
    }

    @Test fun aUserDeclaredOperStarStillMultiplies() {
        // `N(3) * N(2)` constructs on the left and is a known type, so only the
        // count's type tells it apart from a repetition.
        assertEquals(
            "6",
            run(
                """
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
                    println((N(3) * N(2)).v)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun aTypeWithoutARepeatedCtorFallsBackToItsOperator() {
        // Nothing about `Scaled(2) * 3` is a repetition - there is no repeated
        // ctor - so it must reach the operator instead of failing.
        assertEquals(
            "6",
            run(
                """
                import std.io
                import std.traits
                pack Scaled {
                    var v: Int
                }
                impl Arithmetic for Scaled {
                    oper+ [self: Self&](rhs: Int): Scaled { return Scaled(self.v + rhs) }
                    oper- [self: Self&](rhs: Int): Scaled { return Scaled(self.v - rhs) }
                    oper* [self: Self&](rhs: Int): Scaled { return Scaled(self.v * rhs) }
                }
                func main() {
                    println((Scaled(2) * 3).v)
                }
                """.trimIndent(),
            ),
        )
    }

    // -- an allocation is repeated the same way -------------------------------

    @Test fun anAllocatedRepetitionIsABufferOfThatManySlots() {
        assertEquals(
            "7",
            run(
                """
                import std.io
                func main() {
                    var p: Int* = alloc .() * 4
                    p.*[0] = 7
                    println(p.*[0])
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun theSlotTypeMayBeNamedOutright() {
        assertEquals(
            "5",
            run(
                """
                import std.io
                func main() {
                    var p: Int* = alloc Int*() * 4
                    p.*[2] = 5
                    println(p.*[2])
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun aFieldTakesItsSlotTypeFromWhatItHolds() {
        // `self.data = alloc .() * count` inside a ctor: the field states the
        // type, so the `.()` has something to read.
        assertEquals(
            "4\n9",
            run(
                """
                import std.io
                pack Buf {
                    var data: Int* = null
                    var cap: Int = 0
                }
                impl Buf {
                    ctor[self: Self!]() * count {
                        self.data = alloc .() * count
                        self.cap = count
                    }
                }
                func main() {
                    var b: Buf = .() * 4
                    b.data.*[1] = 9
                    println(b.cap)
                    println(b.data.*[1])
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun theBracketBufferFormIsGone() {
        // `alloc T[n]` said "a buffer of n" a second way. A buffer is a repeated
        // allocation, so it is spelled like one.
        val result = Compiler().compile("func main() {\n    var p: Int* = alloc Int[3]\n}")
        val failure = assertIs<CompilationResult.Failure>(result)
        assertTrue(
            failure.errors.any { "was removed" in it && "alloc Int*() * <count>" in it },
            "expected the message to name the spelling that works: ${failure.errors}",
        )
    }

    @Test fun theSlotTypeIsReadFromTheAnnotationEvenWhenItIsGeneric() {
        // `let d: T* = alloc .() * n` inside a generic type - the shape most of
        // `std/container` is written in. `T` erases, so the buffer holds anything;
        // what matters is that the annotation is what states it.
        assertEquals(
            "4\n9",
            run(
                """
                import std.io
                pack Box<T> {
                    var _d: T* = null
                    var _n: Int = 0
                }
                impl Box<T> {
                    prop count[self: Self&]: Int = self._n

                    func put[self: Self!](at: Int, value: T) {
                        self._d.*[at] = value
                    }

                    func at[self: Self&](index: Int): T {
                        return self._d.*[index]
                    }

                    ctor[self: Self!]() * count {
                        let fresh: T* = alloc .() * count
                        self._d = fresh
                        self._n = count
                    }
                }
                func main() {
                    var b: Box<Int> = .() * 4
                    b.put(1, 9)
                    println(b.count)
                    println(b.at(1))
                }
                """.trimIndent(),
            ),
        )
    }
}
