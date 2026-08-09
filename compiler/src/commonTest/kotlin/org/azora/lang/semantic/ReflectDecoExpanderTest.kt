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

package org.azora.lang.semantic

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `inline for X in std::reflect<*>.withDeco<D>` over decorated declarations.
 *
 * The point of the loop is that a library can find what a program declared
 * without the program registering anything. That only works if the loop can
 * enumerate the declarations that matter - functions as well as types - and if
 * the body can read back what each one wrote on its decorator. These cover both
 * halves, and the one property the whole scheme rests on: that two separate
 * loops enumerate in the same order, so an index picked in one identifies the
 * same declaration in the other.
 */
class ReflectDecoExpanderTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}"
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun errors(source: String): List<String> {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Failure>(result, "Expected compilation to fail")
        return result.errors
    }

    private val marked = """
        annot Marked for [.Pack, .Func] {
            fin order: std::Int = 0
            fin tag: std::String = "none"
        }
    """.trimIndent()

    @Test fun decoratedFunctionsAreEnumeratedAndCalled() {
        assertEquals("alpha\nbeta", run("""
            import std.io
            $marked

            @Marked
            func alpha() { std::println("alpha") }

            @Marked
            func beta() { std::println("beta") }

            func main() {
                inline for S in std::reflect<*>.withDeco<Marked> {
                    S()
                }
            }
        """.trimIndent()))
    }

    @Test fun decoratedPacksAreStillEnumerated() {
        assertEquals("7\n7", run("""
            import std.io
            $marked

            @Marked
            pack Alpha { var v: std::Int }

            @Marked
            pack Beta { var v: std::Int }

            func main() {
                inline for T in std::reflect<*>.withDeco<Marked> {
                    std::println(T(7).v)
                }
            }
        """.trimIndent()))
    }

    @Test fun theBoundDeclarationKnowsItsOwnName() {
        assertEquals("alpha\nbeta", run("""
            import std.io
            $marked

            @Marked
            func alpha() {}

            @Marked
            func beta() {}

            func main() {
                inline for S in std::reflect<*>.withDeco<Marked> {
                    std::println(std::reflect<S>.declName)
                }
            }
        """.trimIndent()))
    }

    @Test fun theBodyReadsBackWhatEachDeclarationWroteOnTheDecorator() {
        // `beta` passes neither field, so both must come from the defaults -
        // the decorator declaration is as much a source of the value as the
        // application is.
        assertEquals("alpha 3 first\nbeta 0 none", run("""
            import std.io
            $marked

            @Marked(order: 3, tag: "first")
            func alpha() {}

            @Marked
            func beta() {}

            func main() {
                inline for S in std::reflect<*>.withDeco<Marked> {
                    std::println("${'$'}{std::reflect<S>.declName} ${'$'}{std::reflect<S>.annotMeta<Marked>.order} ${'$'}{std::reflect<S>.annotMeta<Marked>.tag}")
                }
            }
        """.trimIndent()))
    }

    @Test fun anEnumValuedDecoratorFieldSurvives() {
        assertEquals("draw true\ntick false", run("""
            import std.io
            enum Phase {
                Update
                Render
            }
            annot Staged for .Func {
                fin phase: Phase = Phase.Update
            }

            @Staged(phase: Phase.Render)
            func draw() {}

            @Staged
            func tick() {}

            func main() {
                inline for S in std::reflect<*>.withDeco<Staged> {
                    std::println("${'$'}{std::reflect<S>.declName} ${'$'}{std::reflect<S>.annotMeta<Staged>.phase == Phase.Render}")
                }
            }
        """.trimIndent()))
    }

    @Test fun hasDecoAnswersForTheBoundDeclaration() {
        assertEquals("true false", run("""
            import std.io
            $marked
            annot Other for .Func

            @Marked
            @Other
            func both() {}

            func main() {
                inline for S in std::reflect<*>.withDeco<Marked> {
                    std::println("${'$'}{std::reflect<S>.hasDeco<Other>} ${'$'}{std::reflect<S>.hasDeco<Marked> == false}")
                }
            }
        """.trimIndent()))
    }

    @Test fun twoLoopsEnumerateInTheSameOrder() {
        // The whole indexed-dispatch scheme rests on this: a slot number picked
        // while walking one loop has to name the same declaration in the other.
        assertEquals("0 alpha\n1 beta\n2 gamma\npicked beta", run("""
            import std.io
            $marked

            @Marked
            func alpha() { std::println("picked alpha") }

            @Marked
            func beta() { std::println("picked beta") }

            @Marked
            func gamma() { std::println("picked gamma") }

            func main() {
                var index = 0
                inline for S in std::reflect<*>.withDeco<Marked> {
                    std::println("${'$'}index ${'$'}{std::reflect<S>.declName}")
                    index = index + 1
                }

                fin wanted = 1
                var cursor = 0
                inline for S in std::reflect<*>.withDeco<Marked> {
                    if cursor == wanted {
                        S()
                    }
                    cursor = cursor + 1
                }
            }
        """.trimIndent()))
    }

    @Test fun theLoopUnrollsInsideAnOrdinaryFunction() {
        // Not just in `main`: a library's own function is where this is useful.
        assertEquals("alpha 5\nbeta 5", run("""
            import std.io
            $marked

            @Marked
            func alpha(n: std::Int) { std::println("alpha ${'$'}n") }

            @Marked
            func beta(n: std::Int) { std::println("beta ${'$'}n") }

            func runAll(n: std::Int) {
                inline for S in std::reflect<*>.withDeco<Marked> {
                    S(n)
                }
            }

            func main() {
                runAll(5)
            }
        """.trimIndent()))
    }

    @Test fun aDecoratorNothingCarriesUnrollsToNothing() {
        assertEquals("done", run("""
            import std.io
            annot Unused for .Func

            func main() {
                inline for S in std::reflect<*>.withDeco<Unused> {
                    S()
                }
                std::println("done")
            }
        """.trimIndent()))
    }

    @Test fun annotMetaOutsideAnyLoopIsStillCompileTimeOnly() {
        // The loop is what supplies the compile-time context. Without one, an
        // ordinary runtime position must still be rejected rather than folded.
        val reported = errors("""
            import std.io
            $marked

            @Marked(order: 3)
            pack Alpha { var v: std::Int }

            func main() {
                std::println(std::reflect<Alpha>.annotMeta<Marked>.order)
            }
        """.trimIndent())
        assertTrue(
            reported.any { "compile-time-only" in it },
            "expected a compile-time-only diagnostic, got: $reported"
        )
    }
}
