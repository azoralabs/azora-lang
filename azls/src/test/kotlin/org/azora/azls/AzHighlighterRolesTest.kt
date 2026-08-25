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

package org.azora.azls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three roles a client needs to colour an import clause and a doc comment.
 *
 * `modulePath` is the part of an import that says *where to look*; what is
 * selected out of it keeps whatever colour that declaration has, exactly as at
 * a use site. `doc`/`docTag`/`docTagValue` split a doc comment into the
 * sentence, the `@param` that opens a clause of it, and the name that clause is
 * about.
 */
class AzHighlighterRolesTest {

    private fun roleOf(source: String, word: String, occurrence: Int = 0): String? {
        var seen = 0
        for (span in AzHighlighter.highlight(source)) {
            if (source.substring(span.start, span.end) != word) continue
            if (seen++ == occurrence) return span.type
        }
        return null
    }

    private fun textsOf(source: String, role: String): List<String> =
        AzHighlighter.highlight(source).filter { it.type == role }.map { source.substring(it.start, it.end) }

    @Test
    fun `decimal hexadecimal and binary integers are complete number spans`() {
        val source = "fin decimal = 1\nfin hexadecimal = 0xFF\nfin binary = 0b1010\n"
        assertEquals(listOf("1", "0xFF", "0b1010"), textsOf(source, "number"))
    }

    @Test
    fun `fractional scientific and trailing dot floats are complete number spans`() {
        val source = "fin fraction = 3.14\nfin scientific = 1.5e3\nfin trailing = 2.\n"
        assertEquals(listOf("3.14", "1.5e3", "2."), textsOf(source, "number"))
    }

    // -- import: path versus selection ---------------------------------------

    @Test
    fun `a dotted path is module path`() {
        assertEquals("modulePath", roleOf("import std.format::Display", "std"))
        assertEquals("modulePath", roleOf("import std.format::Display", "format"))
    }

    @Test
    fun `a selected symbol is not module path`() {
        assertTrue(roleOf("import std.format::Display", "Display") != "modulePath")
    }

    @Test
    fun `a wildcard leaves the whole path as path`() {
        assertEquals("modulePath", roleOf("import std.io::*", "io"))
    }

    @Test
    fun `a grouped selector does not repaint its members as the module path`() {
        val source = "import std.container::{list, map}"
        assertTrue(roleOf(source, "list") != "modulePath")
        assertTrue(roleOf(source, "map") != "modulePath")
    }

    @Test
    fun `a colons group holds selections`() {
        val source = "import std.format::{Display, Debug}"
        assertTrue(roleOf(source, "Display") != "modulePath")
        assertTrue(roleOf(source, "Debug") != "modulePath")
    }

    @Test
    fun `a member may select inside its own module`() {
        val source = "import std::{math::abs, io::*}"
        assertEquals("scope", roleOf(source, "math"))
        assertTrue(roleOf(source, "abs") != "modulePath")
        assertEquals("scope", roleOf(source, "io"))
    }

    @Test
    fun `a module header is a path too`() {
        assertEquals("modulePath", roleOf("module app.main\n", "app"))
        assertEquals("modulePath", roleOf("module app.main\n", "main"))
    }

    @Test
    fun `the clause does not leak into the file`() {
        val source = "import std.io::*\n\npack Point {\n    var x: Int = 0\n}\n"
        assertTrue(roleOf(source, "Point") != "modulePath")
        assertTrue(roleOf(source, "x") != "modulePath")
    }

    // -- doc comments --------------------------------------------------------

    @Test
    fun `a doc comment is its own role, not a comment`() {
        val roles = AzHighlighter.highlight("/** Builds it. */").map { it.type }
        assertTrue("doc" in roles, "roles were $roles")
        assertTrue("comment" !in roles, "a doc comment is not a plain comment: $roles")
    }

    @Test
    fun `a plain block comment stays a comment`() {
        assertEquals(listOf("comment"), AzHighlighter.highlight("/* plain */").map { it.type })
    }

    @Test
    fun `tags and their names are split out`() {
        val source = "/**\n * Builds it.\n *\n * @param capacity The size.\n * @return it.\n */"
        assertEquals(listOf("@param", "@return"), textsOf(source, "docTag"))
        assertEquals(listOf("capacity"), textsOf(source, "docTagValue"))
    }

    @Test
    fun `a tag that introduces prose takes no name`() {
        assertEquals(emptyList<String>(), textsOf("/** @return The buffer. */", "docTagValue"))
    }

    @Test
    fun `an at sign inside a word is not a tag`() {
        assertEquals(emptyList<String>(), textsOf("/** mail support@azora.dev */", "docTag"))
    }

    // -- string interpolation ------------------------------------------------

    @Test
    fun `a hole's delimiters are punctuation and its content is code`() {
        val source = "fin s = \"total: \${count + 1}\""
        assertEquals(
            listOf("$", "{", "}"),
            textsOf(source, "interpolation-punctuation"),
        )
        assertTrue(
            AzHighlighter.highlight(source).any {
                source.substring(it.start, it.end) == "count" && it.type != "string"
            },
            "the name inside the hole is code, not string text",
        )
    }

    @Test
    fun `an associated type is distinct at declaration and every use`() {
        val source = """
            spec Iterator assoc Item {
                func &.current(): Item
            }

            impl Iterator for Rows assoc Item = Row {
                func &.current(): Item { return self.row }
            }
        """.trimIndent()
        val roles = AzHighlighter.highlight(source)
            .filter { source.substring(it.start, it.end) == "Item" }
            .map { it.type }

        assertTrue(roles.isNotEmpty())
        assertTrue(roles.all { it == "associatedType" }, roles.toString())
    }

    @Test
    fun `decorators enum cases receivers parameters and generics keep distinct roles`() {
        val source = """
            @Supress(.Unused)
            @Since("0.1")
            enum PartialCompare {
                Less
                Equal
                Greater
                Unordered
            }

            func<T, U, S> (self: T&, other: U&).compare(value: S): PartialCompare {
                fin left: T = self
                fin right: U = other
                fin input: S = value
                return PartialCompare.Less
            }
        """.trimIndent()
        val spans = AzHighlighter.highlight(source)
        fun roles(word: String) = spans
            .filter { source.substring(it.start, it.end) == word }
            .map { it.type }

        for (decorator in listOf("@Supress", "@Since")) {
            assertTrue(
                spans.any { it.type == "annotation" && source.substring(it.start, it.end) == decorator },
                "$decorator: $spans",
            )
        }
        for (entry in listOf("Less", "Equal", "Greater", "Unordered")) {
            assertTrue(roles(entry).all { it == "enumMember" }, "$entry: ${roles(entry)}")
        }
        for (generic in listOf("T", "U", "S")) {
            assertTrue(roles(generic).isNotEmpty() && roles(generic).all { it == "generic" }, "$generic: ${roles(generic)}")
        }
        for (receiver in listOf("self", "other")) {
            assertTrue(
                roles(receiver).isNotEmpty() && roles(receiver).all { it == "contextParameter" },
                "$receiver: ${roles(receiver)}",
            )
        }
        assertTrue(roles("value").all { it == "parameter" }, roles("value").toString())
    }

    @Test
    fun `grouped import selections and uses retain their type role`() {
        val source = """
            import std.traits::{PartialEqual, Equal, Order, Hash}
            bridge pack Char derives (PartialEqual, Equal, Order, Hash)
        """.trimIndent()
        val types = setOf("PartialEqual", "Equal", "Order", "Hash")
        val spans = AzHighlighter.highlight(source, visibleTypes = types)
        for (name in types) {
            val roles = spans.filter { source.substring(it.start, it.end) == name }.map { it.type }
            assertEquals(listOf("type", "type"), roles, "$name: $roles")
        }
    }
}
