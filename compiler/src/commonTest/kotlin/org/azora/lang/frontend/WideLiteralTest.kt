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
 * A `Cent` literal is 128 bits wide, and a `Long` cannot carry one.
 *
 * The exact digits travel with the literal; the value beside them is the low 64
 * bits, which is all the readers that only handle small numbers ever look at.
 */
class WideLiteralTest {

    private fun literal(source: String): Expr.IntLiteral {
        val body = Parser(Lexer("func f() {\n    var x = $source\n}").tokenize()).parse()
            .items.filterIsInstance<TopLevel.Func>().single().decl.body
        val declared = body.single() as Stmt.VarDecl
        return declared.initializer as Expr.IntLiteral
    }

    @Test fun aCentMaximumKeepsItsDigits() {
        val value = literal("170_141_183_460_469_231_731_687_303_715_884_105_727")
        assertEquals("170141183460469231731687303715884105727", value.text)
    }

    @Test fun aUCentMaximumKeepsItsDigits() {
        val value = literal("340_282_366_920_938_463_463_374_607_431_768_211_455")
        assertEquals("340282366920938463463374607431768211455", value.text)
    }

    @Test fun aNegativeCentMinimumKeepsItsDigits() {
        val body = Parser(
            Lexer("func f() {\n    var x = -170_141_183_460_469_231_731_687_303_715_884_105_728\n}").tokenize(),
        ).parse().items.filterIsInstance<TopLevel.Func>().single().decl.body
        val negated = (body.single() as Stmt.VarDecl).initializer as Expr.Unary
        assertEquals("170141183460469231731687303715884105728", (negated.operand as Expr.IntLiteral).text)
    }

    @Test fun anOrdinaryLiteralCarriesNoDigits() {
        // Only a literal too wide for its value needs them.
        assertNull(literal("42").text)
        assertNull(literal("9_223_372_036_854_775_807").text)
        // A `ULong` maximum still fits, as the bit pattern it always was.
        assertNull(literal("18_446_744_073_709_551_615").text)
    }

    @Test fun aWideHexLiteralKeepsItsDigits() {
        val value = literal("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")
        assertTrue(value.text.orEmpty().endsWith("FFFFFFFF"), "${value.text}")
    }
}
