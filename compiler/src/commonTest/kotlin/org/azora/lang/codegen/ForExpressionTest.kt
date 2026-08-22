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
 * `for x in xs { … } else { value }` in value position.
 *
 * The loop is the search and the `else` is what the search is worth when it
 * finds nothing. An iteration answers by ending in a value, and answering ends
 * the loop - which is what lets `any` read as the question it asks instead of
 * as a flag and a `return`.
 */
class ForExpressionTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    private fun errorsOf(source: String): String {
        val message = runCatching { Compiler().compile(source.trimIndent(), release = false) }
            .fold(
                onSuccess = { (it as? CompilationResult.Failure)?.errors?.joinToString() ?: "" },
                onFailure = { it.message.orEmpty() },
            )
        return message
    }

    @Test fun theAnswerIsTheValueAnIterationEndsIn() {
        assertEquals(
            "true\nfalse",
            run(
                """
                import std.io

                func anyEven(xs: Array<Int>): Bool {
                    return for i in 0..<xs.length {
                        if xs[i] % 2 == 0 {
                            true
                        }
                    } else { false }
                }

                func main() {
                    println(anyEven(@arr[1, 4, 5]))
                    println(anyEven(@arr[1, 3, 5]))
                }
                """,
            ),
        )
    }

    @Test fun theElseIsTheAnswerWhenNoIterationProducesOne() {
        assertEquals(
            "40\n-1",
            run(
                """
                import std.io

                func firstBig(xs: Array<Int>): Int {
                    fin found = for i in 0..<xs.length {
                        if xs[i] > 10 {
                            xs[i]
                        }
                    } else { -1 }
                    return found
                }

                func main() {
                    println(firstBig(@arr[1, 40, 5]))
                    println(firstBig(@arr[1, 2, 3]))
                }
                """,
            ),
        )
    }

    @Test fun answeringEndsTheLoop() {
        // The first match wins: a second one would overwrite the answer if the
        // loop kept going, and the count says how far it got.
        assertEquals(
            "7\n2",
            run(
                """
                import std.io

                func main() {
                    fin xs = @arr[4, 7, 9]
                    var seen = 0
                    fin answer = for i in 0..<xs.length {
                        seen = seen + 1
                        if xs[i] % 2 == 1 {
                            xs[i]
                        }
                    } else { 0 }
                    println(answer)
                    println(seen)
                }
                """,
            ),
        )
    }

    @Test fun anIterationMayAnswerFromEitherBranch() {
        assertEquals(
            "small",
            run(
                """
                import std.io

                func classify(xs: Array<Int>): String {
                    return for i in 0..<xs.length {
                        if xs[i] > 100 {
                            "big"
                        } else {
                            "small"
                        }
                    } else { "none" }
                }

                func main() {
                    println(classify(@arr[3]))
                }
                """,
            ),
        )
    }

    @Test fun itWalksWhateverForWalks() {
        assertEquals(
            "found",
            run(
                """
                import std.io

                func main() {
                    fin names = @arr["a", "b"]
                    println(
                        for name in names {
                            if name == "b" {
                                "found"
                            }
                        } else { "missing" },
                    )
                }
                """,
            ),
        )
    }

    // -- what it refuses --------------------------------------------------

    @Test fun aForUsedAsAValueNeedsAnElse() {
        val message = errorsOf(
            """
            func main() {
                fin x = for i in 0..<3 {
                    if i == 1 { i }
                }
            }
            """,
        )
        assertTrue("'else'" in message, message)
    }

    @Test fun aForThatCouldNeverAnswerIsRejected() {
        // Nothing in the body ends in a value, so the `else` would be the only
        // possible answer and the loop is decoration.
        val message = errorsOf(
            """
            func main() {
                fin x = for i in 0..<3 {
                    var doubled = i * 2
                } else { 0 }
            }
            """,
        )
        assertTrue("must produce one" in message, message)
    }

    @Test fun aBodyEndingInACallIsAnAnswerOfNothing() {
        // The tail is the answer, so a tail that is worth nothing says so -
        // `Unit` is not a value the `else` could match.
        val message = errorsOf(
            """
            import std.io

            func main() {
                fin x = for i in 0..<3 {
                    println(i)
                } else { 0 }
            }
            """,
        )
        assertTrue("Unit" in message, message)
    }

    @Test fun reverseStaysAFunctionWhereNoLoopFollows() {
        assertEquals(
            "3",
            run(
                """
                import std.io
                import std.algorithm.sort

                func main() {
                    fin xs = reverse(@arr[1, 2, 3])
                    println(xs[0])
                }
                """,
            ),
        )
    }
}
