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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `add<Short, _, Long>(1, 2, 3)` - a caller pins the arguments that matter and
 * leaves the rest to the values.
 */
class TypeArgumentHoleTest {

    private fun call(source: String): Expr.Call {
        val body = Parser(Lexer("func f() {\n    $source\n}").tokenize()).parse()
            .items.filterIsInstance<TopLevel.Func>().single().decl.body
        return assertIs(body.single())
    }

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T, "expected ${T::class.simpleName}, was $value")
        return value as T
    }

    private fun typeArgs(source: String): List<TypeRef> =
        assertIs<Expr.Call>(assertIs<Stmt.ExprStmt>(
            Parser(Lexer("func f() {\n    $source\n}").tokenize()).parse()
                .items.filterIsInstance<TopLevel.Func>().single().decl.body.single()
        ).expr).typeArgs

    @Test fun aHoleIsWrittenAndKept() {
        val args = typeArgs("add<Short, _, Long>(1, 2, 3)")

        assertEquals(3, args.size)
        assertFalse(args[0].isHole)
        assertTrue(args[1].isHole, "${args[1]}")
        assertFalse(args[2].isHole)
    }

    @Test fun everyCombinationParses() {
        assertEquals(3, typeArgs("add<Short, _, _>(1, 2, Long(3))").count { true })
        assertEquals(2, typeArgs("add<_, Long>(1, 2)").count { true })
        assertEquals(1, typeArgs("add<_>(1)").count { true })
        assertTrue(typeArgs("add<_, _>(1, 2)").all { it.isHole })
    }

    @Test fun anOrdinaryArgumentIsNotAHole() {
        assertTrue(typeArgs("add<Short, Long>(1, 2)").none { it.isHole })
        assertTrue(typeArgs("add(1, 2)").isEmpty())
    }
}
