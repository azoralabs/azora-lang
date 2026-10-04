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
 * `using` is a keyword and `use` is gone.
 *
 * The two go together: `use` was spent on two jobs it did not need to hold -
 * a second spelling of `import`, and a spec member's call-site alias - and both
 * are now written a single way. `using` was set aside in its place, and has
 * since been given one meaning: `using value { … }` supplies a contextual
 * receiver to the block (LAMBDA_CONTEXT_CAPTURE_DIP §11). A macro may still be
 * named with it, where the `@` has already said the word is a name.
 */
class KeywordReservationTest {

    private fun compile(source: String): CompilationResult =
        Compiler().compile(source.trimIndent(), release = false)

    private fun errorsOf(source: String): List<String> =
        assertIs<CompilationResult.Failure>(compile(source)).errors

    // ── `using` opens a receiver context ───────────────────────────────

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

    @Test fun usingWithoutABlockIsRejected() {
        // The receiver it supplies has nowhere to be supplied to.
        val errors = errorsOf(
            """
            func main() {
                using x
            }
            """,
        )
        assertTrue(errors.any { "Expected '{' after contextual values" in it }, errors.toString())
    }

    @Test fun usingIsNotADeclarationHead() {
        // A receiver context is a statement; nothing at the top level opens with it.
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
