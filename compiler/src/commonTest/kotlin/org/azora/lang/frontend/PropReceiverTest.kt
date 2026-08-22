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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A `prop` observes; a `func` acts.
 *
 * A property takes `[self&]` and nothing else. `[self!]` says that reading it
 * writes through what it was read from, and `[self]` says that reading it ends
 * the value - a reader who cannot tell which of the three a `prop` is has to
 * check every one of them, which is the whole cost the distinction saves.
 */
class PropReceiverTest {

    private fun parse(source: String): List<TopLevel> =
        Parser(Lexer(source.trimIndent()).tokenize()).parse().items

    private fun refusal(source: String): String =
        assertFailsWith<IllegalStateException> { parse(source) }.message.orEmpty()

    // -- what a prop takes --------------------------------------------------

    @Test fun aSharedBorrowIsTheReceiverAPropTakes() {
        parse(
            """
            impl Point {
                prop magnitude[self&]: Double = self.x
                prop scaled[self: Self&]: Double = self.x
            }
            """,
        )
    }

    @Test fun anExclusiveBorrowIsRefused() {
        val message = refusal(
            """
            impl Cursor {
                prop next[self!]: Int = self.index
            }
            """,
        )
        assertTrue("only observes" in message, message)
        assertTrue("'[self&]'" in message, message)
        assertTrue("writes through" in message, message)
    }

    @Test fun aConsumingReceiverIsRefused() {
        val message = refusal(
            """
            impl Compare {
                prop reversed[self]: Compare = self
            }
            """,
        )
        assertTrue("only observes" in message, message)
        assertTrue("consumes" in message, message)
    }

    @Test fun aBareTypeIsRefusedTheSameWay() {
        // `[self: Self]` reads as the by-value form even though a bare type
        // still borrows: the sigil is what says which one was meant.
        val message = refusal(
            """
            impl Compare {
                prop reversed[self: Self]: Compare = self
            }
            """,
        )
        assertTrue("only observes" in message, message)
    }

    @Test fun theRuleHoldsInASpecToo() {
        val message = refusal(
            """
            spec Walker {
                prop next[self: Self!]: Int
            }
            """,
        )
        assertTrue("only observes" in message, message)
    }

    @Test fun aPropWithNoReceiverIsUntouched() {
        // A receiver-less `prop` belongs to the type, not to a value of it.
        parse("impl Byte {\n    prop widest: Int = 8\n}")
    }

    // -- what a func takes --------------------------------------------------

    @Test fun aFuncTakesAnyOfTheThree() {
        parse(
            """
            impl Cursor {
                func peek[self&](): Int { return self.index }
                func advance[self!]() { self.index = self.index + 1 }
                func into[self](): Int { return self.index }
            }
            """,
        )
    }
}
