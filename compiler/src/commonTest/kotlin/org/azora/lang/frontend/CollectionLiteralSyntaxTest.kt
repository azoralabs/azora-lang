package org.azora.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CollectionLiteralSyntaxTest {
    private fun value(source: String): Expr =
        (Parser(Lexer("func main() { fin x = $source }").tokenize()).parse()
            .functions.single().body.single() as Stmt.FinDecl).initializer

    @Test fun sequenceAndAssociativeShapesAreDistinct() {
        assertTrue(assertIs<Expr.ArrayLiteral>(value("[]")).elements.isEmpty())
        assertTrue(assertIs<Expr.MapLit>(value("[:]")).entries.isEmpty())
        assertEquals(2, assertIs<Expr.ArrayLiteral>(value("[\n1,\n2,\n]")).elements.size)
        assertEquals(2, assertIs<Expr.MapLit>(value("[\n1: 2,\n3: 4,\n]")).entries.size)
    }

    @Test fun literalsCanBeIndexedAndHaveMembers() {
        assertIs<Expr.Index>(value("[[1, 2], [3, 4]][1][0]"))
        assertIs<Expr.Member>(value("[1, 2].size"))
        assertIs<Expr.MethodCall>(value("[1, 2].clone()"))
    }

    @Test fun mixedShapesAndMalformedEmptyMapsAreRejected() {
        for (source in listOf("[1, 2: 3]", "[1: 2, 3]", "[:1]", "![1, 2]")) {
            assertFailsWith<IllegalStateException> { value(source) }
        }
    }
}
