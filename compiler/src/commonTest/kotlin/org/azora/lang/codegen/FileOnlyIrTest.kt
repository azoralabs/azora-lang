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

package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The IR of *this file*, which is what an editor showing one asks for.
 *
 * A program is compiled with whatever standard library it reaches, so a full
 * dump is mostly declarations nobody here wrote. Printing only the named items
 * is what makes the difference readable.
 */
class FileOnlyIrTest {

    private fun ir(source: String): CompilationResult.Success {
        val result = Compiler().compile(source.trimIndent(), release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return result
    }

    @Test fun printingOnlyTheNamedItemsLeavesTheLibraryOut() {
        val result = ir(
            """
            func add(a: Int, b: Int): Int {
                return a + b
            }

            func main() {
                var total = add(2, 3)
            }
            """,
        )

        val everything = result.ir.prettyPrint()
        val mine = result.ir.prettyPrint(setOf("add", "main"))

        assertTrue("func add" in mine, mine)
        assertTrue("func main" in mine, mine)
        // The promotion ranks and the comparison enums arrive with the standard
        // library, and are most of what a full dump is.
        assertTrue("Int_rank" in everything, "the full dump carries the library")
        assertTrue("Int_rank" !in mine, mine)
        assertTrue(mine.length < everything.length, "the filtered dump is the smaller one")
    }

    @Test fun anEmptyFilterKeepsEverything() {
        val result = ir("func main() { var x = 1 }")

        assertTrue(result.ir.prettyPrint(emptySet()) == result.ir.prettyPrint())
    }

    // ── How it reads ───────────────────────────────────────────────────

    @Test fun itemsComeBackInTheOrderTheyWereWritten() {
        // A reader comparing the IR against the source compares it line by
        // line. Grouping the enums apart from everything else made the two
        // orders disagree the moment a file mixed its declarations.
        val result = ir(
            """
            pack Alpha { var a: Int }

            func first(): Int { return 1 }

            enum Beta {
                One
                Two
            }

            func second(): Int { return 2 }

            pack Gamma { var g: Int }

            func main() { var n = first() }
            """,
        )

        val mine = result.ir.prettyPrint(setOf("Alpha", "first", "Beta", "second", "Gamma", "main"))
        val order = listOf("pack Alpha", "func first", "enum Beta", "func second", "pack Gamma", "func main")
            .map { mine.indexOf(it) }

        assertTrue(order.none { it < 0 }, "every item is printed; got:\n$mine")
        assertTrue(order == order.sorted(), "source order, got:\n$mine")
    }

    @Test fun aPackListsOneFieldToALine() {
        // The same shape an `enum` prints its variants in. Six fields on one
        // line is a line nobody reads to the end.
        val result = ir(
            """
            pack Token {
                var kind: Int
                var lexeme: String
                var line: Int
            }

            func main() { var t = Token(0, "a", 1) }
            """,
        )

        val mine = result.ir.prettyPrint(setOf("Token", "main"))

        assertTrue("pack Token {\n    kind: Int\n    lexeme: String\n    line: Int\n}" in mine, mine)
    }
}
