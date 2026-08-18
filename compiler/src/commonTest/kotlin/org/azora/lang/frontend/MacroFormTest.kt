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
 * How a macro declaration is written down.
 *
 * The braces hold *alternatives*, so a macro with one arm does not need them:
 *
 * ```
 * macro @arr[...$items] => std.container.array::Array(...$items)
 * macro @arr { [...$items] => std.container.array::Array(...$items) }
 * ```
 *
 * When there are alternatives, a sigil in front of an arm says which spelling of
 * the name takes it. `@vec` and `@vec!` remain different macros - the sigil is
 * part of the name - so writing their arms together only saves declaring the name
 * twice.
 *
 * A template may name its symbol through the module that declares it, as
 * `std.container.array::Array`: the dotted path picks the module, and the name
 * after `::` is what the symbol is called inside it.
 */
class MacroFormTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    private fun metas(source: String): List<TopLevel.Meta> =
        parse(source).filterIsInstance<TopLevel.Meta>().filter { !it.name.startsWith("__") }

    private fun meta(source: String, name: String): TopLevel.Meta =
        metas(source).single { it.name == name }

    // -- one arm needs no braces --------------------------------------------

    @Test fun aSingleArmMacroNeedsNoBraces() {
        val m = meta("macro @arr[...\$items] => Array(...\$items)", "arr")
        val arm = m.arms.single()
        assertEquals(MacroDelimiter.BRACKET, arm.delimiter)
        assertEquals("\$items", assertIs<MacroPattern.SeqCapture>(arm.pattern).name)
    }

    @Test fun theBracedAndUnbracedFormsAgree() {
        val unbraced = meta("macro @arr[...\$items] => Array(...\$items)", "arr")
        val braced = meta("macro @arr {\n    [...\$items] => Array(...\$items)\n}", "arr")
        assertEquals(braced.arms.size, unbraced.arms.size)
        assertEquals(braced.arms.single().delimiter, unbraced.arms.single().delimiter)
        assertEquals(braced.arms.single().pattern, unbraced.arms.single().pattern)
    }

    @Test fun aSingleArmMayUseAnyDelimiter() {
        assertEquals(MacroDelimiter.PAREN, meta("macro @f(...\$x) => g(...\$x)", "f").arms.single().delimiter)
        assertEquals(MacroDelimiter.BRACKET, meta("macro @f[...\$x] => g(...\$x)", "f").arms.single().delimiter)
    }

    @Test fun anEmptyPatternNeedsNoBracesEither() {
        val m = meta("macro @empty[] => emptyList()", "empty")
        assertEquals(MacroPattern.Empty, m.arms.single().pattern)
    }

    @Test fun braceStillOpensTheArmListAndNotAnArm() {
        // `macro @name { … }` is always the list. A brace-delimited arm is written
        // inside it like any other.
        val m = meta("macro @f {\n    {...\$x} => g(...\$x)\n}", "f")
        assertEquals(MacroDelimiter.BRACE, m.arms.single().delimiter)
    }

    @Test fun aSourceFragmentMacroIsUnaffected() {
        // `macro @name => inline "…"` has no arms at all and keeps its own path.
        val items = parse("macro @react => inline \"[; &]\"")
        assertTrue(items.filterIsInstance<TopLevel.Meta>().single().name.startsWith("__fragment__"))
    }

    // -- sigils select which spelling takes the arm -------------------------

    @Test fun aSigilArmBelongsToTheSigilledName() {
        val source =
            """
            macro @vec {
                [...${'$'}items] => List(...${'$'}items)
                ![...${'$'}items] => mutableListOf(...${'$'}items)
            }
            """.trimIndent()
        assertEquals(listOf("vec", "vec!"), metas(source).map { it.name })
        assertEquals(1, meta(source, "vec").arms.size)
        assertEquals(1, meta(source, "vec!").arms.size)
    }

    @Test fun eachSpellingKeepsOnlyItsOwnArms() {
        val source =
            """
            macro @vec {
                [] => emptyList()
                [...${'$'}items] => List(...${'$'}items)
                ![] => emptyMutableList()
                ![...${'$'}items] => mutableListOf(...${'$'}items)
            }
            """.trimIndent()
        assertEquals(2, meta(source, "vec").arms.size)
        assertEquals(2, meta(source, "vec!").arms.size)
    }

    @Test fun aMacroMayDeclareOnlyASigilledSpelling() {
        val source = "macro @vec {\n    ![...\$items] => mutableListOf(...\$items)\n}"
        assertEquals(listOf("vec!"), metas(source).map { it.name })
    }

    @Test fun everySigilIsAvailable() {
        val source =
            """
            macro @m {
                [] => a()
                ?[] => b()
                &[] => c()
                *[] => d()
                ^[] => e()
            }
            """.trimIndent()
        assertEquals(listOf("m", "m?", "m&", "m*", "m^"), metas(source).map { it.name })
    }

    @Test fun separateSigilDeclarationsStillWork() {
        // The merged block is a convenience, not a replacement: the two names may
        // still be declared apart.
        val source =
            """
            macro @vec[...${'$'}items] => List(...${'$'}items)
            macro @vec![...${'$'}items] => mutableListOf(...${'$'}items)
            """.trimIndent()
        assertEquals(listOf("vec", "vec!"), metas(source).map { it.name })
    }

    // -- a template may name its module -------------------------------------

    @Test fun aTemplateMayReachThroughAModulePath() {
        // `std.container.array::Array` - the path picks the module, and inside it
        // the symbol is declared as `Array`, so that is what it resolves to.
        val template = meta("macro @arr[...\$items] => std.container.array::Array(...\$items)", "arr")
            .arms.single().template
        assertEquals("Array", assertIs<Expr.Call>(template).callee)
    }

    @Test fun aSingleNameBeforeTheColonsIsStillANamespace() {
        // `Name::member` is scope access and keeps mangling to `Name__member`.
        // The library is no longer a namespace, so a scope is what still spells it.
        val body = parse("func f() {\n    concurrency::cancel(1)\n}")
            .filterIsInstance<TopLevel.Func>().single().decl.body
        val call = assertIs<Expr.Call>(assertIs<Stmt.ExprStmt>(body.single()).expr)
        assertEquals("concurrency__cancel", call.callee)
    }

    @Test fun aModulePathOfAnyDepthWorks() {
        val template = meta("macro @d[] => a.b.c.d.e::Thing()", "d").arms.single().template
        assertEquals("Thing", assertIs<Expr.Call>(template).callee)
    }

    @Test fun theColonsStillNeedANameToTheirLeft() {
        val e = assertFailsWith<IllegalStateException> { parse("func f() {\n    g()::h()\n}") }
        assertTrue("must follow a namespace name" in e.message.orEmpty(), e.message.orEmpty())
    }
}
