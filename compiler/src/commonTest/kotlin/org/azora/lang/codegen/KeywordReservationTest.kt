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
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `using` is reserved and `use` is gone.
 *
 * The two go together: `use` was spent on two jobs it did not need to hold -
 * a second spelling of `import`, and a spec member's call-site alias - and both
 * are now written a single way. `using` takes its place as a word set *aside*
 * rather than spent, so giving it a meaning later takes nothing away from
 * anybody. Until then its one legal position is a macro's name, where the `@`
 * has already said the word is a name and no keyword is ambiguous.
 */
class KeywordReservationTest {

    private fun compile(source: String): CompilationResult =
        Compiler().compile(source.trimIndent(), release = false)

    private fun errorsOf(source: String): List<String> =
        assertIs<CompilationResult.Failure>(compile(source)).errors

    // ── `using` is reserved ────────────────────────────────────────────

    @Test fun aMacroMayBeNamedUsing() {
        assertIs<CompilationResult.Success>(
            compile(
                """
                macro ${'$'}a @using ${'$'}b => ${'$'}a + ${'$'}b
                func main() {}
                """,
            ),
        )
    }

    @Test fun bareUsingIsRejected() {
        val errors = errorsOf(
            """
            func main() {
                using x
            }
            """,
        )
        assertTrue(errors.any { "'using' is reserved" in it }, errors.toString())
    }

    @Test fun usingIsNotADeclarationHead() {
        // Nothing at the top level opens with it either - the word carries no
        // grammar at all yet, which is the whole point of reserving it.
        assertIs<CompilationResult.Failure>(compile("using std.io"))
    }

    // ── `use` is gone ──────────────────────────────────────────────────

    @Test fun useIsNoLongerASecondSpellingOfImport() {
        assertIs<CompilationResult.Failure>(
            compile(
                """
                use std.io
                func main() {}
                """,
            ),
        )
    }

    @Test fun useAsInACompactSpecSaysWhatToWriteInstead() {
        val errors = errorsOf("""spec Into<T>[self: Self&]: T use as "to${'$'}{T.typeName}"""")
        assertTrue(errors.any { "'use' is not a keyword" in it }, errors.toString())
        assertTrue(errors.any { "inline prop" in it }, errors.toString())
    }

    @Test fun useAsOnASpecMemberSaysWhatToWriteInstead() {
        val errors = errorsOf(
            """
            spec Into<T> {
                prop<T> &.into: T
                use into<T> as "to${'$'}{T.typeName}"
            }
            """,
        )
        assertTrue(errors.any { "'use' is not a keyword" in it }, errors.toString())
        assertTrue(errors.any { "inline prop" in it }, errors.toString())
    }

    @Test fun useIsAnOrdinaryNameNow() {
        // A word the language does not claim is a word a program may spend.
        assertIs<CompilationResult.Success>(
            compile(
                """
                func main() {
                    fin use = 3
                    fin doubled = use + use
                }
                """,
            ),
        )
    }
}
