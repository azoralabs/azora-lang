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
 * A `prop` declares its type, whatever its body looks like.
 *
 * A property is part of its type's surface: a caller writes `value.isLess` and
 * has to know what comes back without reading the body. A local binding may
 * infer because its initializer is right beside it; a member may not.
 *
 * A parser test rather than a compile test because the bundled stdlib does not
 * parse yet (see the Baseline section of UPGRADE_PLAN.MD), which would fail any
 * whole-program compile for reasons unrelated to this form.
 */
class DeclaredPropertyTypeTest {

    private fun property(source: String, name: String): FuncDecl =
        Parser(Lexer(source).tokenize()).parse().items
            .filterIsInstance<TopLevel.Impl>()
            .flatMap { it.methods }
            .single { it.name == name }

    private fun rejection(source: String): String =
        assertFailsWith<Exception> { property(source, "isLess") }.message.orEmpty()

    @Test fun anExpressionPropertyMustWriteItsType() {
        val message = rejection(
            """
            impl Compare {
                prop &.isLess = self == .Less
            }
            """.trimIndent()
        )
        assertTrue("must declare its type" in message, message)
        assertTrue("prop isLess[…]: T = …" in message, "the error names the spelling: $message")
    }

    @Test fun aBracedPropertyMustWriteItsTypeToo() {
        assertTrue(
            "must declare its type" in rejection(
                """
                impl Compare {
                    prop &.isLess { return self == .Less }
                }
                """.trimIndent()
            )
        )
    }

    @Test fun aWrittenTypeIsStillHonoured() {
        val prop = property(
            """
            impl Compare {
                prop &.isLess: Bool = self == Compare.Less
            }
            """.trimIndent(),
            "isLess",
        )
        val declared = prop.returnType
        assertIs<TypeAnnotation.Explicit>(declared)
        assertTrue("Bool" in declared.ref.toString(), declared.ref.toString())
    }

    @Test fun aWrittenTypeOnABracedBodyIsHonoured() {
        val prop = property(
            """
            impl Compare {
                prop &.isLess: Bool { return self == .Less }
            }
            """.trimIndent(),
            "isLess",
        )
        assertIs<TypeAnnotation.Explicit>(prop.returnType)
        assertEquals(MemberCallStyle.PROPERTY, prop.memberCallStyle)
        assertIs<Stmt.Return>(prop.body.single())
    }
}
