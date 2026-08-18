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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The import grammar.
 *
 * ```
 * import std.io.*                          every symbol below a path
 * import std.math.abs                      one dotted path
 * import std.container.[list.*, map.*]     a group
 * import std.x.[A, f.*, u.P]               a group of mixed selectors
 * ```
 *
 * A group member is a clause in its own right - that is the whole design. It is
 * what lets groups nest and mix selectors without a second grammar for the inside
 * of one, and it is why a member carries its own fully-joined path rather than
 * leaving readers to track a base down the tree.
 *
 * See [ImportSpecTest] for how a clause flattens into the `(path, selector)` pairs
 * the import machinery consumes.
 */
class ImportGrammarTest {

    private fun specs(source: String): List<ImportSpec> =
        Parser(Lexer(source).tokenize()).parse().items
            .filterIsInstance<TopLevel.UseImport>().flatMap { it.specs }

    private fun imports(source: String): List<Pair<String, String?>> =
        Parser(Lexer(source).tokenize()).parse().items
            .filterIsInstance<TopLevel.UseImport>().flatMap { it.imports }

    private fun members(spec: ImportSpec): List<ImportSpec> =
        assertIs<ImportSpec.Selector.Group>(spec.selector).members

    // -- bracketed groups ---------------------------------------------------

    @Test fun aBracketGroupCarriesItsMembers() {
        // `std/container/map.az:44` is written this way.
        val spec = specs("import std.container.[array.*, list.*]").single()
        assertEquals("std.container", spec.path)
        assertEquals(
            listOf("std.container.array", "std.container.list"),
            members(spec).map { it.path },
        )
        assertTrue(members(spec).all { it.selector == ImportSpec.Selector.All })
    }

    @Test fun aGroupMayCarryOneSelectedSymbol() {
        val spec = specs("import std.serializer.[Serializable]").single()
        assertEquals(listOf("std.serializer.Serializable"), members(spec).map { it.path })
        assertEquals(ImportSpec.Selector.Path, members(spec).single().selector)
    }

    @Test fun aOneMemberGroupSelectsWhatTheDottedPathSelects() {
        // `import a.b.[c]` and `import a.b.c` reach the same symbol; the group only
        // says it with room for a second name later.
        assertEquals(
            imports("import std.serializer.Serializable"),
            imports("import std.serializer.[Serializable]"),
        )
    }

    @Test fun aGroupMayMixSelectors() {
        val spec = specs("import std.x.[A, f.*, u.P]").single()
        assertEquals(
            listOf(
                "std.x.A" to null,
                "std.x.f" to "*",
                "std.x.u.P" to null,
            ),
            spec.flatten(),
        )
    }

    @Test fun groupsNest() {
        // A member is a clause, so a member may itself be a group.
        val spec = specs("import a.[b.[c, d.*], e.*]").single()
        assertEquals(
            listOf("a.b.c" to null, "a.b.d" to "*", "a.e" to "*"),
            spec.flatten(),
        )
    }

    @Test fun aNestedGroupKeepsItsOwnBasePath() {
        val inner = members(specs("import a.[b.[c, d]]").single()).single()
        assertEquals("a.b", inner.path)
        assertEquals(listOf("a.b.c", "a.b.d"), members(inner).map { it.path })
    }

    @Test fun aGroupMemberMayBeADeepPath() {
        val spec = specs("import std.[container.list.List, math.abs]").single()
        assertEquals(
            listOf("std.container.list.List" to null, "std.math.abs" to null),
            spec.flatten(),
        )
    }

    // -- separators ---------------------------------------------------------

    @Test fun membersMayBeSeparatedByNewlinesAlone() {
        val spec = specs(
            """
            import std.container.[
                array.*
                list.*
                map.*
            ]
            """.trimIndent(),
        ).single()
        assertEquals(3, members(spec).size)
    }

    @Test fun oneStatementMayCarrySeveralClauses() {
        assertEquals(
            listOf("std.io" to "*", "std.math.abs" to null),
            imports("import std.io.*, std.math.abs"),
        )
    }

    @Test fun aClauseAfterAGroupStillParses() {
        assertEquals(
            listOf("a.b" to null, "a.c" to null, "d" to "*"),
            imports("import a.[b, c], d.*"),
        )
    }

    // -- the brace spelling still reads -------------------------------------

    @Test fun theBraceSpellingIsStillAccepted() {
        assertEquals(
            imports("import std.container.[list, map]"),
            imports("import std.container.{list, map}"),
        )
    }

    @Test fun theBraceSpellingAlsoNestsNow() {
        // It goes through the same parser, so it gained what the bracket form has.
        assertEquals(listOf("a.b.c" to null, "a.d" to "*"), imports("import a.{b.{c}, d.*}"))
    }

    // -- what is rejected ---------------------------------------------------

    @Test fun anEmptyGroupIsRejected() {
        val e = assertFailsWith<IllegalStateException> { specs("import std.container.[]") }
        assertTrue("at least one name" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun anUnclosedGroupIsRejected() {
        val e = assertFailsWith<IllegalStateException> { specs("import std.container.[list, map") }
        assertTrue("Expected ']'" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun colonsAreNotImportSyntax() {
        val e = assertFailsWith<IllegalStateException> { specs("import std.container::list") }
        assertTrue("not import syntax" in e.message.orEmpty(), e.message.orEmpty())
        assertTrue("import module.[a, b]" in e.message.orEmpty(), e.message.orEmpty())
    }

    // -- the plain forms are unchanged --------------------------------------

    @Test fun theUngroupedFormsStillParse() {
        assertEquals(
            listOf("std.io" to "*", "std.math.abs" to null, "std" to null),
            imports("import std.io.*\nimport std.math.abs\nimport std"),
        )
    }
}
