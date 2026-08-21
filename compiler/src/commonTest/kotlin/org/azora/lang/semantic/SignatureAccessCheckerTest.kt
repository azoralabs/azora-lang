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
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `@SignatureOnly` may not be reached from a `@DeclaresAccess` body.
 *
 * The pair states one rule from two sides: a decorator marks the functions whose
 * access is declared in their signature, and a marker names what cannot be
 * acquired any other way. Both live in std, so the rule is a library's to apply
 * and the compiler learns no library's names.
 */
class SignatureAccessCheckerTest {
    /**
     * Compiled through the whole pipeline, because the rule is carried by std
     * decorators and those reach a program by injection.
     */
    private fun errorsOf(source: String): List<String> =
        (Compiler().compile(source) as? CompilationResult.Failure)?.errors ?: emptyList()

    private val declarations = """
        @DeclaresAccess
        annot @Task for .Func

        pack Bag { var value: Int }

        @SignatureOnly
        func fetch(bag: Bag&): Int {
            return bag.value
        }
    """.trimIndent()

    @Test fun aDirectCallFromADeclaringBodyIsRejected() {
        val errors = errorsOf("""
            $declarations

            @Task func run(bag: Bag&) {
                fin taken = fetch(bag)
                if taken > 0 {
                    return
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(
            errors.any { "fetch" in it && "run" in it },
            "expected the call to be refused, got ${errors}",
        )
    }

    /**
     * The reach is transitive, or the rule is one helper deep: a signature that
     * says nothing about `fetch` is exactly what a reader - or a scheduler -
     * would be misled by.
     */
    @Test fun aCallThroughAHelperIsRejected() {
        val errors = errorsOf("""
            $declarations

            func grab(bag: Bag&): Int {
                return fetch(bag)
            }

            @Task func run(bag: Bag&) {
                fin taken = grab(bag)
                if taken > 0 {
                    return
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(
            errors.any { "fetch" in it && "grab" in it },
            "expected the helper to be named in the error, got ${errors}",
        )
    }

    /** A block written in the body is part of it. */
    @Test fun aCallInsideABlockIsRejected() {
        val errors = errorsOf("""
            $declarations

            @Task func run(bag: Bag&) {
                fin values = @arr[1, 2]
                for value in values {
                    if value > 0 {
                        fin taken = fetch(bag)
                        if taken > 0 {
                            return
                        }
                    }
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(
            errors.any { "fetch" in it },
            "expected the nested call to be refused, got ${errors}",
        )
    }

    /** Nothing is refused outside a declaring body: the rule is the decorator's. */
    @Test fun anUndecoratedCallerIsUntouched() {
        val errors = errorsOf("""
            $declarations

            func plain(bag: Bag&): Int {
                return fetch(bag)
            }

            func main() {
                fin bag = Bag(1)
                fin seen = plain(bag)
                if seen > 0 {
                    return
                }
            }
        """.trimIndent())

        assertTrue(errors.isEmpty(), "expected no errors, got ${errors}")
    }

    /** Taking it as a parameter is the whole point, and stays allowed. */
    @Test fun takingItAsAParameterIsAllowed() {
        val errors = errorsOf("""
            $declarations

            @Task func run(taken: Int) {
                if taken > 0 {
                    return
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(errors.isEmpty(), "expected no errors, got ${errors}")
    }
}
