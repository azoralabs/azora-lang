/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.frontend

import org.azora.lang.semantic.ConstraintEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DescendingRangeTest {
    @Test fun operatorUsesMaximalMunchWithoutChangingComparisonsOrGenericClosers() {
        val tokens = Lexer("a>..b a>b a>=b A<B<C>>").tokenize().filter { it.type != TokenType.EOF }
        assertEquals(listOf(
            TokenType.IDENTIFIER, TokenType.GREATER_DOT_DOT, TokenType.IDENTIFIER,
            TokenType.IDENTIFIER, TokenType.GREATER, TokenType.IDENTIFIER,
            TokenType.IDENTIFIER, TokenType.GREATER_EQUAL, TokenType.IDENTIFIER,
            TokenType.IDENTIFIER, TokenType.LESS, TokenType.IDENTIFIER, TokenType.LESS,
            TokenType.IDENTIFIER, TokenType.SHIFT_RIGHT,
        ), tokens.map { it.type })
        assertEquals(">..", tokens[1].lexeme)
        assertEquals(TokenType.GREATER, Lexer(">").tokenize().first().type)
    }

    @Test fun rangeCarriesDirectionAndSourceBoundOrder() {
        val program = Parser(Lexer("func main() { for i in upper()>..lower() {} }").tokenize()).parse()
        val range = assertIs<Expr.Range>(assertIs<Stmt.For>(program.functions.single().body.single()).iterable)
        assertTrue(range.descending)
        assertTrue(range.inclusive)
        assertEquals("upper", assertIs<Expr.Call>(range.from).callee)
        assertEquals("lower", assertIs<Expr.Call>(range.to).callee)
    }

    @Test fun reverseIsAnOrdinaryNameAndTheOldOperatorIsRejected() {
        assertFalse("reverse" in AzoraSyntaxVocabulary.reservedKeywords)
        assertEquals(TokenType.IDENTIFIER, Lexer("reverse").tokenize().first().type)
        Parser(Lexer("func reverse(): Int = 7\nfunc main() { var reverse = 3 }").tokenize()).parse()
        assertFailsWith<IllegalStateException> {
            Parser(Lexer("bridge oper reverse.. Int&.(rhs: Int&) by 1").tokenize()).parse()
        }
        assertFailsWith<IllegalStateException> {
            Parser(Lexer("func main() { var reverse = 0\nreverse for i in 0..<3 {} }").tokenize()).parse()
        }
    }

    @Test fun constantRangesAndConstraintsShareDescendingBoundaries() {
        val range = Expr.Range(Expr.IntLiteral(3, 1), Expr.IntLiteral(0, 1), true, 1, descending = true)
        assertEquals(listOf(2L, 1L, 0L), range.constantProgression(3, 0).toList())
        assertTrue(range.constantProgression(Long.MIN_VALUE, Long.MIN_VALUE).isEmpty())
        assertEquals(listOf(Long.MIN_VALUE), range.constantProgression(Long.MIN_VALUE + 1, Long.MIN_VALUE).toList())
        for (value in 0L..3L) {
            val clause = Expr.InCheck(Expr.IntLiteral(value, 1), range, line = 1)
            val outcome = ConstraintEvaluator.evaluate(clause, emptyMap(), null)
            if (value < 3) assertIs<ConstraintEvaluator.Outcome.Satisfied>(outcome)
            else assertIs<ConstraintEvaluator.Outcome.Violated>(outcome)
        }
    }
}
