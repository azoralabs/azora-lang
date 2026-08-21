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
 * A bare name inside a member is a parameter or a local, never a member.
 *
 * A field and a method both belong to the receiver, and spelling either without
 * it made one line say two different things by the same word:
 * `self.offset = offset - size` read the left `offset` as the field and the
 * right one as the field too, so a reader had no way to see which names were
 * state and which were arguments. The receiver is declared; writing it is what
 * that declaration is for.
 *
 * `with anchor { … }` is the exception, and stays: there the receiver was named
 * on purpose, which is the whole point of the form. See UPGRADE_PLAN S7.2.
 */
class SelfQualificationTest {

    private fun errorsOf(source: String): List<String> =
        (Compiler().compile(source) as? CompilationResult.Failure)?.errors ?: emptyList()

    private val pack = """
        pack Counter {
            var count: Int = 0
            var step: Int = 1
        }
    """.trimIndent()

    @Test fun aBareFieldReadIsRejectedAndSaysWhatToWrite() {
        val errors = errorsOf("""
            $pack

            impl Counter {
                func total[self: Self&](): Int {
                    return count
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(
            errors.any { "'count' is a field of Counter" in it && "write 'self.count'" in it },
            "$errors",
        )
    }

    @Test fun theQualifiedFormCompiles() {
        val errors = errorsOf("""
            $pack

            impl Counter {
                func total[self: Self&](): Int {
                    return self.count + self.step
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(errors.none { "is a field of" in it }, "$errors")
    }

    @Test fun aParameterOfTheSameNameIsStillTheParameter() {
        // The rule takes nothing away from a name that is genuinely local: a
        // parameter shadowing a field is the parameter, as it always was.
        val errors = errorsOf("""
            $pack

            impl Counter {
                func advance[self: Self!](step: Int) {
                    self.count = self.count + step
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(errors.none { "is a field of" in it }, "$errors")
    }

    @Test fun aBareMemberCallIsRejectedTheSameWay() {
        val errors = errorsOf("""
            $pack

            impl Counter {
                func bump[self: Self!]() {
                    self.count = self.count + 1
                }

                func bumpTwice[self: Self!]() {
                    bump()
                    self.bump()
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(
            errors.any { "'bump' is a member of Counter" in it && "write 'self.bump(" in it },
            "$errors",
        )
    }

    @Test fun aFreeFunctionIsStillCallableFromAMember() {
        val errors = errorsOf("""
            $pack

            func double(value: Int): Int {
                return value * 2
            }

            impl Counter {
                func doubled[self: Self&](): Int {
                    return double(self.count)
                }
            }

            func main() {}
        """.trimIndent())

        assertTrue(errors.none { "is a member of" in it || "is a field of" in it }, "$errors")
    }
}
