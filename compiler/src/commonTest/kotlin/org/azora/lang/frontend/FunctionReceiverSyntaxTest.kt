/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.azora.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FunctionReceiverSyntaxTest {
    private fun parse(source: String): Program = Parser(Lexer(source).tokenize()).parse()

    @Test
    fun bracketReceiverIsAccepted() {
        val program = parse(
            """
            pack Language {
                fin name: String
            }

            impl Language {
                func &.greeting(): String {
                    return self.name
                }
            }
            """.trimIndent(),
        )

        val impl = program.items.filterIsInstance<TopLevel.Impl>().single()
        val greeting = impl.methods.single()
        assertEquals("self", greeting.receiverName)
        assertEquals(ParamModifier.SHARED, greeting.receiverModifier)
    }

    @Test
    fun bodyReceiverIsRejected() {
        val failure = assertFailsWith<IllegalStateException> {
            parse(
                """
                pack Language

                impl Language {
                    func &.greeting(): String { self& ->
                        return "hello"
                    }
                }
                """.trimIndent(),
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("func &.greeting(...)"),
            failure.message,
        )
    }
}
