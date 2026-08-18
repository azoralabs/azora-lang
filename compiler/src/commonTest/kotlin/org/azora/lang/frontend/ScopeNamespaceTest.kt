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
 * `scope Name { … }` at top level is a namespace: its members are mangled with the
 * name (`concurrency__cancel`) and reached through it (`concurrency::cancel`). The
 * same name may be opened as often as it likes - a scope is a name a package agrees
 * on, not a block one file owns.
 *
 * `scope { … }` and `scope "label" { … }` are the other reading: members keep their
 * top-level names and the scope only attaches metadata, with `inline`/`deepinline`
 * carrying the usual compile-time semantics. The name after the keyword is what
 * separates the two, so neither needs a word of its own.
 *
 * A scope holds names, not state: its bindings are `fin`. Anything rebindable or
 * mutable belongs to something that owns it - see [SoloAndGraphLifetimeTest].
 *
 * These are parser tests: they pin the frontend-internal spelling `Name__member`.
 * The IR spelling is `__Name_member` - see `ScopeSymbolManglingTest`.
 */
class ScopeNamespaceTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    private fun funcNames(source: String): List<String> =
        parse(source).filterIsInstance<TopLevel.Func>().map { it.decl.name }

    // -- the namespace form -------------------------------------------------

    @Test fun aMemberIsMangledWithTheScopeName() {
        assertEquals(
            listOf("concurrency__cancel"),
            funcNames(
                """
                scope concurrency {
                    func cancel(task: Any): Unit { }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun aScopeMayHoldSeveralMembers() {
        assertEquals(
            listOf("math__abs", "math__sign"),
            funcNames(
                """
                scope math {
                    func abs(x: Int): Int { return x }
                    func sign(x: Int): Int { return 1 }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun scopesNest() {
        assertEquals(
            listOf("std__math__abs"),
            funcNames(
                """
                scope std {
                    scope math {
                        func abs(x: Int): Int { return x }
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun aScopePathMayBeWrittenInOneHeader() {
        // `::` separates the segments of a scope path - one of the few places it
        // survives, now that a library is not a namespace.
        assertEquals(
            listOf("outer__inner__abs"),
            funcNames(
                """
                scope outer::inner {
                    func abs(x: Int): Int { return x }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test fun aMemberIsReachedThroughTheScopeName() {
        val body = parse(
            """
            scope concurrency {
                func cancel(task: Any): Unit { }
            }
            func main() {
                concurrency::cancel(1)
            }
            """.trimIndent(),
        ).filterIsInstance<TopLevel.Func>().single { it.decl.name == "main" }.decl.body
        val call = assertIs<Expr.Call>(assertIs<Stmt.ExprStmt>(body.single()).expr)
        assertEquals("concurrency__cancel", call.callee)
    }

    @Test fun theSameScopeMayBeOpenedTwice() {
        assertEquals(
            listOf("math__abs", "math__sign"),
            funcNames(
                """
                scope math {
                    func abs(x: Int): Int { return x }
                }
                scope math {
                    func sign(x: Int): Int { return 1 }
                }
                """.trimIndent(),
            ),
        )
    }

    // -- a scope holds only `fin` -------------------------------------------

    @Test fun aScopeHoldsFinBindings() {
        assertEquals(
            listOf("math__PI"),
            parse("scope math {\n    fin PI: Real = 3.14\n}")
                .filterIsInstance<TopLevel.FinDecl>().map { it.name },
        )
    }

    @Test fun aScopeRejectsVar() {
        val e = assertFailsWith<IllegalStateException> { parse("scope math {\n    var seed: Int = 0\n}") }
        assertTrue("only 'fin' bindings" in e.message.orEmpty(), e.message.orEmpty())
        assertTrue("'var'" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aScopeRejectsVal() {
        val e = assertFailsWith<IllegalStateException> { parse("scope math {\n    val seed: Int = 0\n}") }
        assertTrue("'val'" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun aScopeRejectsLet() {
        val e = assertFailsWith<IllegalStateException> { parse("scope math {\n    let seed: Int = 0\n}") }
        assertTrue("'let'" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test fun theRejectionPointsAtTheNamespaceThatTakesIt() {
        val e = assertFailsWith<IllegalStateException> { parse("scope math {\n    var seed: Int = 0\n}") }
        assertTrue("'solo' namespace" in e.message.orEmpty(), e.message.orEmpty())
    }

    // -- the block form -----------------------------------------------------

    @Test fun anUnnamedScopeLeavesMemberNamesAlone() {
        // No name to mangle with, so the members stay top level; the block only
        // attaches metadata.
        assertEquals(
            listOf("cancel"),
            funcNames("scope {\n    func cancel(task: Any): Unit { }\n}"),
        )
    }

    @Test fun aLabelledScopeLeavesMemberNamesAlone() {
        assertEquals(
            listOf("cancel"),
            funcNames("scope \"tasks\" {\n    func cancel(task: Any): Unit { }\n}"),
        )
    }

    @Test fun aDeepInlineScopeIsACompileTimeBlock() {
        // `deepinline scope { … }` is how std/config.az wraps its compile-time
        // configuration: the members go into a block the CTCE evaluator resolves,
        // and they keep their own names.
        val source =
            """
            deepinline scope {
                enum CompilerTarget {
                    Interpret
                    Native
                }
            }
            """.trimIndent()
        val block = assertIs<TopLevel.DeepInlineBlock>(parse(source).single())
        assertEquals(
            listOf("CompilerTarget"),
            block.body.filterIsInstance<TopLevel.Enum>().map { it.name },
        )
        // The spelling being replaced produces exactly the same thing.
        assertEquals(
            parse(source.replaceFirst("scope", "realm")).map { it::class.simpleName },
            parse(source).map { it::class.simpleName },
        )
    }

    // -- `scope` keeps its statement meaning --------------------------------

    @Test fun aScopeInsideABodyIsStillABlock() {
        val body = parse("func f() {\n    scope {\n        fin x = 1\n    }\n}")
            .filterIsInstance<TopLevel.Func>().single().decl.body
        assertIs<Stmt.Scope>(body.single())
    }

    @Test fun aScopeInsideABodyMayHoldAnyBinding() {
        // The `fin`-only rule is about a namespace's members, not about the
        // statements in a block that happens to use the same word.
        val body = parse("func f() {\n    scope {\n        var x = 1\n    }\n}")
            .filterIsInstance<TopLevel.Func>().single().decl.body
        assertIs<Stmt.VarDecl>(assertIs<Stmt.Scope>(body.single()).body.single())
    }

    // -- the spelling being replaced ----------------------------------------

    @Test fun realmStillNamesTheSameThing() {
        // `realm` is removed in its own step; until then both spellings declare
        // the same namespace, so the replacement can be checked against it.
        assertEquals(
            listOf("concurrency__cancel"),
            funcNames("realm concurrency {\n    func cancel(task: Any): Unit { }\n}"),
        )
    }

    @Test fun realmIsNotHeldToTheFinOnlyRule() {
        // The rule arrives with `scope`; `realm` keeps taking what it always took
        // so that removing it stays a separate, reviewable change.
        assertEquals(
            listOf("math__seed"),
            parse("realm math {\n    var seed: Int = 0\n}")
                .filterIsInstance<TopLevel.VarDecl>().map { it.name },
        )
    }
}
