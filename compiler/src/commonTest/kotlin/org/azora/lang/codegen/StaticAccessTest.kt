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
 * A `.` reaches what a name holds - whether the name holds a value or a scope.
 *
 * `math.PI` and `p.x` are the same text and mean the same thing: the member of
 * what precedes the dot. Which one it is cannot be told from the text, only from
 * what the program declares, so the decision is made once the whole program is
 * known - see `ScopeAccessRewriter`.
 *
 * Every test here has a mirror: the access must NOT be taken when the name holds
 * a value. A parameter, a local, or simply a type nobody declared a static on
 * keeps the ordinary reading, silently.
 *
 * Statics declared as receiver-less `impl` members (`impl Alloc { func create()
 * }`) are not reachable by either spelling yet - nothing registers them as
 * declarations. That is S5.1's job; this step is the access path they will use.
 */
class StaticAccessTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun errors(source: String): List<String> =
        assertIs<CompilationResult.Failure>(Compiler().compile(source)).errors

    // -- a scope's members --------------------------------------------------

    @Test fun aScopeFunctionIsReachedWithADot() {
        assertEquals(
            "42",
            run(
                """
                import std.io
                scope helper {
                    func twice(n: Int): Int { return n * 2 }
                }
                func main() {
                    println(helper.twice(21))
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun aScopeBindingIsReachedWithADot() {
        assertEquals(
            "3",
            run("import std.io\nscope math {\n    fin PI: Int = 3\n}\nfunc main() {\n    println(math.PI)\n}"),
        )
    }

    @Test fun nestedScopesReadLeftToRight() {
        assertEquals(
            "5",
            run(
                """
                import std.io
                scope a {
                    scope b {
                        func f(): Int { return 5 }
                    }
                }
                func main() {
                    println(a.b.f())
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun aScopePathWrittenInOneHeaderIsReachedTheSameWay() {
        // The declaration spells its path with `::` - that is a scope path, not
        // an access - and the access is still a `.`.
        assertEquals(
            "5",
            run(
                """
                import std.io
                scope a::b {
                    func f(): Int { return 5 }
                }
                func main() {
                    println(a.b.f())
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun theArgumentsAreResolvedToo() {
        assertEquals(
            "8",
            run(
                """
                import std.io
                scope helper {
                    func twice(n: Int): Int { return n * 2 }
                    fin base: Int = 4
                }
                func main() {
                    println(helper.twice(helper.base))
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun anImplMethodReachesAScopeToo() {
        // The body of an `impl` on an ordinary type is not a scope member, so it
        // was not walked at all before statics needed it.
        assertEquals(
            "6",
            run(
                """
                import std.io
                scope math {
                    func triple(n: Int): Int { return n * 3 }
                }
                pack Counter {
                    var n: Int = 2
                }
                impl Counter {
                    func &.scaled(): Int { return math.triple(self.n) }
                }
                func main() {
                    println(Counter(2).scaled())
                }
                """.trimIndent(),
            ),
        )
    }

    // -- a type's statics ---------------------------------------------------

    @Test fun aTypeStaticIsReachedWithADot() {
        assertEquals(
            "7",
            run("import std.io\nimpl Byte {\n    inline fin limit: Int = 7\n}\nfunc main() {\n    println(Byte.limit)\n}"),
        )
    }

    @Test fun anEnumVariantIsStillReachedWithADot() {
        // A variant was already a `.` access and must stay one - it is named on
        // its type, not declared as a separate symbol.
        assertEquals(
            "1",
            run(
                """
                import std.io
                enum Color {
                    Red
                    Green
                }
                func main() {
                    fin c: Color = Color.Red
                    when c {
                        .Red -> println(1)
                        .Green -> println(2)
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    // -- and never when the name holds a value ------------------------------

    @Test fun aParameterShadowsTheScope() {
        assertEquals(
            "9",
            run(
                """
                import std.io
                pack Cfg {
                    var PI: Int = 0
                }
                scope math {
                    fin PI: Int = 3
                }
                func read(math: Cfg&): Int { return math.PI }
                func main() {
                    println(read(Cfg(9)))
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun aLocalShadowsTheScope() {
        assertEquals(
            "8",
            run(
                """
                import std.io
                pack Cfg {
                    var PI: Int = 0
                }
                scope math {
                    fin PI: Int = 3
                }
                func main() {
                    fin math: Cfg = Cfg(8)
                    println(math.PI)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun anOrdinaryFieldReadIsUntouched() {
        assertEquals(
            "4",
            run("import std.io\npack P {\n    var x: Int = 0\n}\nfunc main() {\n    fin p: P = P(4)\n    println(p.x)\n}"),
        )
    }

    @Test fun aDottedNameNobodyDeclaredStaysAValueAccess() {
        // Nothing declares `nowhere__member`, so the access keeps the ordinary
        // reading and fails as a missing value - not as a missing symbol with a
        // mangled name the source never wrote.
        val messages = errors("func main() {\n    fin x = nowhere.member\n}")
        assertTrue(
            messages.any { "nowhere" in it && "__" !in it },
            "expected the message to name what the source wrote: $messages",
        )
    }

    // -- both spellings work until S4.2 restricts `::` ----------------------

    @Test fun theQualifiedSpellingStillWorks() {
        assertEquals(
            "3",
            run("import std.io\nscope math {\n    fin PI: Int = 3\n}\nfunc main() {\n    println(math::PI)\n}"),
        )
    }
}
