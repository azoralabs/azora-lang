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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An `import` statement parses to one [ImportSpec] per clause, and every semantic
 * pass reads those specs back through [ImportSpec.flatten] as `(path, selector)`
 * pairs.
 *
 * The structure is what the import grammar is written against - a wildcard, a
 * dotted path, and a group are three different things, and a group member carries
 * its own full path so nesting needs no base-path bookkeeping. The flat view is
 * what the machinery downstream still consumes, so the two must agree exactly.
 */
class ImportSpecTest {

    private fun specs(source: String): List<ImportSpec> =
        Parser(Lexer(source).tokenize()).parse().items
            .filterIsInstance<TopLevel.UseImport>()
            .flatMap { it.specs }

    private fun imports(source: String): List<Pair<String, String?>> =
        Parser(Lexer(source).tokenize()).parse().items
            .filterIsInstance<TopLevel.UseImport>()
            .flatMap { it.imports }

    // -- the three selectors ------------------------------------------------

    @Test fun aDottedPathIsOneSpec() {
        val spec = specs("import std.math.abs").single()
        assertEquals("std.math.abs", spec.path)
        assertEquals(ImportSpec.Selector.Path, spec.selector)
        assertEquals(listOf("std.math.abs" to null), spec.flatten())
    }

    @Test fun aSingleSegmentPathIsOneSpec() {
        val spec = specs("import std").single()
        assertEquals("std", spec.path)
        assertEquals(ImportSpec.Selector.Path, spec.selector)
    }

    @Test fun aWildcardIsAnAllSelector() {
        val spec = specs("import std.io::*").single()
        assertEquals("std.io", spec.path)
        assertEquals(ImportSpec.Selector.All, spec.selector)
        assertEquals(listOf("std.io" to "*"), spec.flatten())
    }

    @Test fun aGroupKeepsItsBasePathAndItsMembers() {
        val spec = specs("import std.container.{list, map}").single()
        assertEquals("std.container", spec.path)
        val group = spec.selector as ImportSpec.Selector.Group
        assertEquals(listOf("std.container.list", "std.container.map"), group.members.map { it.path })
        assertTrue(group.members.all { it.selector == ImportSpec.Selector.Path })
    }

    @Test fun aGroupMemberCarriesItsOwnFullPath() {
        // Nothing downstream has to remember the base path to read a member,
        // which is what lets a group nest to any depth.
        val group = specs("import a.b.{c, d}").single().selector as ImportSpec.Selector.Group
        assertEquals(listOf("a.b.c" to null, "a.b.d" to null), group.members.flatMap { it.flatten() })
    }

    // -- the flat view agrees with the structure ----------------------------

    @Test fun flatteningAGroupYieldsOnePairPerMember() {
        assertEquals(
            listOf("std.container.list" to null, "std.container.map" to null),
            imports("import std.container.{list, map}"),
        )
    }

    @Test fun oneStatementMayCarrySeveralClauses() {
        assertEquals(
            listOf("std.io" to "*", "std.math" to null),
            imports("import std.io::*, std.math"),
        )
    }

    @Test fun everyFormFlattensAsItAlwaysDid() {
        assertEquals(
            listOf(
                "std.io" to "*",
                "std.math.abs" to null,
                "std.container.list" to null,
                "std.container.map" to null,
                "std" to null,
            ),
            imports(
                """
                import std.io::*
                import std.math::abs
                import std.container.{list, map}
                import std
                """.trimIndent(),
            ),
        )
    }

    // -- the fields the grammar will fill in next ---------------------------

    @Test fun withoutAndAliasStartEmpty() {
        // S1.3 and S1.4 give these a grammar; until then every spec parses
        // unfiltered and unaliased, and nothing downstream reads them.
        val all = specs(
            """
            import std.io::*
            import std.math::abs
            import std.container.{list, map}
            """.trimIndent(),
        )
        assertTrue(all.all { it.without.isEmpty() }, "no clause filters yet")
        assertTrue(all.all { it.alias == null }, "no clause aliases yet")
    }

    // -- construction from flat pairs ---------------------------------------

    @Test fun buildingFromFlatPairsRoundTrips() {
        // Compiler-synthesised imports (SerializationDeriver) are still written as
        // pairs, so the pair -> spec -> pair path has to be lossless.
        val pairs = listOf("std.container.list" to null, "std.io" to "*")
        assertEquals(pairs, TopLevel.UseImport.of(pairs, line = 0).imports)
    }

    @Test fun aStatementKeepsItsExportFlagAndCondition() {
        val stmt = Parser(Lexer("import std.io::*").tokenize()).parse().items
            .filterIsInstance<TopLevel.UseImport>().single()
        assertEquals(false, stmt.exported)
        assertNull(stmt.condition)
    }
}
