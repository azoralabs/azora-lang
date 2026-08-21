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

import org.azora.lang.frontend.DecoTarget
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.TopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `@Supress(kind: .Unused)`, the `.Module` target, and the `annot @Name` form
 * decorator declarations are written in.
 */
class SuppressionTest {

    private fun parse(source: String): Program = Parser(Lexer(source).tokenize()).parse()

    private fun warnings(source: String): List<String> =
        AllocDropAnalyzer().analyze(parse(source)).filter { it.startsWith("warning:") }

    // ── How a decorator is declared ────────────────────────────────────

    @Test fun aDecoratorIsDeclaredWithItsSigil() {
        val deco = parse("annot @Marker for .Pack\nfunc main() {}")
            .items.filterIsInstance<TopLevel.Deco>().single()
        assertEquals("Marker", deco.name)
        assertEquals(setOf(DecoTarget.Pack), deco.targets)
    }

    @Test fun aDecoratorWithoutItsSigilSaysSo() {
        val failure = assertFailsWith<IllegalStateException> {
            parse("annot Marker for .Pack\nfunc main() {}")
        }
        assertTrue("annot @Marker" in failure.message.orEmpty(), failure.message)
    }

    @Test fun everyTargetCanBeSpelledOut() {
        // `for .*` says what no `for` clause already means, and a reader may
        // want to see it said.
        val starred = parse("annot @Marker for .*\nfunc main() {}")
            .items.filterIsInstance<TopLevel.Deco>().single()
        val bare = parse("annot @Marker\nfunc main() {}")
            .items.filterIsInstance<TopLevel.Deco>().single()
        assertEquals(bare.targets, starred.targets)
        assertTrue(starred.targets.isEmpty(), starred.targets.toString())
    }

    // ── The module target ──────────────────────────────────────────────

    @Test fun aModuleCarriesItsOwnDecorators() {
        val program = parse(
            """
            @Supress(kind: .Unused)
            module app.main

            func main() {}
            """.trimIndent()
        )
        assertEquals("app.main", program.moduleName)
        assertEquals(listOf("Supress"), program.moduleAnnotations.map { it.name })
    }

    @Test fun decoratorsAboveAnythingElseStayWithIt() {
        // No header follows, so the rows belong to the declaration they are on.
        val program = parse(
            """
            @Stable
            func main() {}
            """.trimIndent()
        )
        assertTrue(program.moduleAnnotations.isEmpty(), program.moduleAnnotations.toString())
        assertEquals(listOf("Stable"), program.functions.single().annotations.map { it.name })
    }

    // ── What it silences ───────────────────────────────────────────────

    @Test fun anUnreadLocalIsReported() {
        val reported = warnings("func main() {\n    fin unread = 1\n}")
        assertTrue(reported.any { "unread" in it }, reported.toString())
    }

    @Test fun aSuppressedFunctionKeepsItsLocalsQuiet() {
        val reported = warnings(
            """
            @Supress(kind: .Unused)
            func main() {
                fin unread = 1
            }
            """.trimIndent()
        )
        assertTrue(reported.isEmpty(), reported.toString())
    }

    @Test fun aModuleSweepDoesNotReachInsideAFunction() {
        // A module-wide sweep answers for the names the module publishes; a
        // local nobody reads is still the author's own business.
        val reported = warnings(
            """
            @Supress(kind: .Unused)
            module app.main

            func main() {
                fin unread = 1
            }
            """.trimIndent()
        )
        assertTrue(reported.any { "unread" in it }, reported.toString())
    }

    @Test fun anotherKindDoesNotSilenceUnused() {
        val reported = warnings(
            """
            @Supress(kind: .Something)
            func main() {
                fin unread = 1
            }
            """.trimIndent()
        )
        assertTrue(reported.any { "unread" in it }, reported.toString())
    }
}
