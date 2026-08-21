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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `bridge pack Byte(__int)` - a type that says which literal writes it.
 *
 * A primitive is written, not built: `4` is already an `IntLiteral`, and
 * `Byte`, `Int` and `Long` are widths it can be read at. Naming one says which,
 * and says nothing else.
 */
class LiteralWidthTest {

    private fun pack(source: String): TopLevel.Pack =
        Parser(Lexer("$source\nfunc main() {}").tokenize()).parse()
            .items.filterIsInstance<TopLevel.Pack>().single()

    @Test fun aBridgePackSaysWhichLiteralWritesIt() {
        assertEquals(Literals.INT, pack("bridge pack Byte(__int)").literalKind)
        assertEquals(Literals.REAL, pack("bridge pack Double(__float)").literalKind)
    }

    @Test fun aPlainBridgePackSaysNothingAboutLiterals() {
        assertNull(pack("bridge pack Opaque").literalKind)
        assertNull(pack("bridge pack String").literalKind)
    }

    @Test fun onlyABridgePackMayBeWrittenAsALiteral() {
        val failure = assertFailsWith<IllegalStateException> { pack("pack Money(__int)") }
        assertTrue("bridge form" in failure.message.orEmpty(), failure.message)
    }

    @Test fun theLiteralHasToBeOne() {
        val failure = assertFailsWith<IllegalStateException> { pack("bridge pack Money(Coins)") }
        assertTrue("is not a literal" in failure.message.orEmpty(), failure.message)
    }
}
