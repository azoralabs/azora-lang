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

package org.azora.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `solo pack Name` is a pack there is one of: one instance, ready when the program
 * starts. Everything else about it reads as a pack does - fields in the body,
 * members in `impl Name { … }` - so `solo` is a word about how many, not about what.
 *
 * A singleton that carries no state has nothing left for a body to hold, so the
 * braces come off: `solo pack Alloc` on its own line.
 *
 * Inside a `graph`, `solo` keeps that meaning as a provider lifetime. Its
 * per-scope sibling is spelled `scoped`, not `scope`: a `scope` is a namespace,
 * and a lifetime borrowing the word would put two unrelated meanings on it.
 *
 * These are parser tests because the bundled stdlib does not parse yet (see the
 * Baseline section of UPGRADE_PLAN.MD).
 */
class SoloAndGraphLifetimeTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    // -- solo pack ----------------------------------------------------------

    @Test fun aStatelessSingletonNeedsNoBody() {
        val solo = parse("solo pack Alloc\n").filterIsInstance<TopLevel.Solo>().single()
        assertEquals("Alloc", solo.name)
        assertTrue(solo.fields.isEmpty())
    }

    @Test fun aSingletonWithStateHoldsItsFields() {
        val solo = parse(
            """
            solo pack Config {
                var url: String
                fin retries: Int
            }
            """.trimIndent(),
        ).filterIsInstance<TopLevel.Solo>().single()
        assertEquals(listOf("url", "retries"), solo.fields.map { it.name })
    }

    @Test fun aSingletonStillRequiresTheWordPack() {
        // `solo` is a modifier on `pack`, so there is one way to declare a type.
        val e = assertFailsWith<IllegalStateException> { parse("solo Alloc\n") }
        assertTrue("Expected 'pack' after 'solo'" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aSingletonPutsItsMembersInAnImpl() {
        val e = assertFailsWith<IllegalStateException> {
            parse("solo pack Config {\n    func f(): Int { return 1 }\n}")
        }
        assertTrue("put its members in" in e.message.orEmpty(), e.message.orEmpty())
    }

    // -- graph lifetimes ----------------------------------------------------

    @Test fun aGraphSpellsItsPerScopeLifetimeScoped() {
        val graph = parse(
            """
            graph AppGraph {
                solo Repository(inject Db)
                factory LoginViewModel(inject Api)
                scoped ProfileViewModel(inject Api)
            }
            """.trimIndent(),
        ).filterIsInstance<TopLevel.Graph>().single()
        assertEquals(
            listOf(
                TopLevel.ProviderLifetime.SOLO,
                TopLevel.ProviderLifetime.FACTORY,
                TopLevel.ProviderLifetime.SCOPED,
            ),
            graph.registrations.map { it.lifetime },
        )
    }

    @Test fun aGraphRejectsScopeAsALifetime() {
        // `scope` declares a namespace; a lifetime sharing the word would put two
        // unrelated meanings on it.
        val e = assertFailsWith<IllegalStateException> {
            parse("graph AppGraph {\n    scope ProfileViewModel(inject Api)\n}")
        }
        assertTrue("written 'scoped'" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun soloIsStillALifetimeInsideAGraph() {
        // The word means "one of" in both places, so a graph entry and a
        // declaration do not disagree about it.
        val graph = parse("graph G {\n    solo Config(\"u\")\n}")
            .filterIsInstance<TopLevel.Graph>().single()
        assertEquals(TopLevel.ProviderLifetime.SOLO, graph.registrations.single().lifetime)
    }
}
