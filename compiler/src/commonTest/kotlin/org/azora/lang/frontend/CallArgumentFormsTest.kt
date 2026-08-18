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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every argument list reads the same, wherever it is written.
 *
 * A named call, a leading-dot construction, an `alloc` - each is a list of
 * arguments, so each accepts what an argument may be:
 *
 *  - `inline for x in xs { expr }`, one argument per iteration
 *  - `...arr`, the array's elements spread
 *  - `name: value`, a named argument
 *
 * They used to be four separate loops, and only the named-call one had learned
 * about `inline for` - so `alloc .( inline for e in args { e } )` was a syntax
 * error for no reason a reader could see.
 */
class CallArgumentFormsTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source).tokenize()).parse().items

    private fun initializerOf(source: String): Expr =
        assertIs<Stmt.VarDecl>(
            parse("func f() {\n$source\n}")
                .filterIsInstance<TopLevel.Func>().single().decl.body.first(),
        ).initializer

    /** The argument list of whatever construction [source] initializes with. */
    private fun argsOf(source: String): List<Expr> = when (val e = initializerOf(source)) {
        is Expr.Call -> e.args
        is Expr.InferredMember -> e.ctorArgs.orEmpty()
        is Expr.Alloc -> when (val v = e.value) {
            is Expr.Call -> v.args
            is Expr.InferredMember -> v.ctorArgs.orEmpty()
            else -> error("no argument list in $v")
        }
        else -> error("no argument list in $e")
    }

    private val inlineForArg = "inline for e in args { e }"

    // -- `inline for` in every argument list --------------------------------

    @Test fun aNamedCallTakesAnInlineFor() {
        assertIs<Expr.InlineForArgs>(argsOf("var x: Int = build($inlineForArg)").single())
    }

    @Test fun aLeadingDotConstructionTakesAnInlineFor() {
        assertIs<Expr.InlineForArgs>(argsOf("var x: Point = .($inlineForArg)").single())
    }

    @Test fun anAllocatedLeadingDotConstructionTakesAnInlineFor() {
        // `std/container/list.az` builds its buffer this way.
        assertIs<Expr.InlineForArgs>(argsOf("var p: T* = alloc .($inlineForArg)").single())
    }

    @Test fun anAllocatedNamedConstructionTakesAnInlineFor() {
        assertIs<Expr.InlineForArgs>(argsOf("var p: T* = alloc Point*($inlineForArg)").single())
    }

    @Test fun theLoopKeepsItsVariableAndBody() {
        val loop = assertIs<Expr.InlineForArgs>(argsOf("var p: T* = alloc .($inlineForArg)").single())
        assertEquals("e", loop.name)
        assertEquals("args", assertIs<Expr.Identifier>(loop.iterable).name)
        assertEquals("e", assertIs<Expr.Identifier>(loop.body).name)
    }

    @Test fun theBraceOpensTheLoopBodyAndNotATrailingLambda() {
        // `inline for f in reflect<Self>.fields { … }` - the `{` is the body, so the
        // iterable must not swallow it as a lambda.
        val loop = assertIs<Expr.InlineForArgs>(
            argsOf("var x: Point = .(inline for f in reflect<Self>.fields { f })").single(),
        )
        assertEquals("f", loop.name)
    }

    // -- spread and named arguments, everywhere -----------------------------

    @Test fun everyListTakesASpread() {
        assertIs<Expr.Spread>(argsOf("var x: Int = build(...arr)").single())
        assertIs<Expr.Spread>(argsOf("var x: Point = .(...arr)").single())
        assertIs<Expr.Spread>(argsOf("var p: T* = alloc .(...arr)").single())
        assertIs<Expr.Spread>(argsOf("var p: T* = alloc Point*(...arr)").single())
    }

    @Test fun everyListTakesANamedArgument() {
        for (source in listOf(
            "var x: Int = build(width: 3)",
            "var x: Point = .(width: 3)",
            "var p: T* = alloc .(width: 3)",
            "var p: T* = alloc Point*(width: 3)",
        )) {
            assertEquals("width", assertIs<Expr.NamedArg>(argsOf(source).single()).name, source)
        }
    }

    // -- the ordinary forms are unchanged -----------------------------------

    @Test fun plainArgumentsStillWork() {
        assertEquals(2, argsOf("var x: Point = .(1, 2)").size)
        assertEquals(0, argsOf("var x: Point = .()").size)
        assertEquals(3, argsOf("var x: Int = build(1, 2, 3)").size)
    }

    @Test fun commasStayOptionalAcrossLines() {
        assertEquals(
            3,
            argsOf(
                """
                var x: Point = .(
                    1
                    2
                    3
                )
                """.trimIndent(),
            ).size,
        )
    }

    @Test fun aTrailingLambdaStillJoinsTheArguments() {
        // The named-call list is the one that grows a trailing lambda after the
        // `)`, so sharing the argument parser must not have cost it that.
        val args = argsOf("var x: Int = build(1) { it }")
        assertEquals(2, args.size)
        assertTrue(args.last() is Expr.Lambda, "the trailing lambda joined the call")
    }

    @Test fun severalArgumentFormsMayShareOneList() {
        val args = argsOf("var x: Point = .(1, ...rest, width: 3, $inlineForArg)")
        assertEquals(4, args.size)
        assertIs<Expr.IntLiteral>(args[0])
        assertIs<Expr.Spread>(args[1])
        assertIs<Expr.NamedArg>(args[2])
        assertIs<Expr.InlineForArgs>(args[3])
    }
}
