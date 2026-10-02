/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ExplicitSyntaxTest {
    private fun parse(source: String) = Parser(Lexer(source).tokenize()).parse()

    @Test fun rejectsDeclarationAndAssignmentFanOut() {
        for (statement in listOf(
            "fin {a, b} = 0", "fin [a, b] = 0", "var {a, b}: Int = {1, 2}", "let {a, b} <- next()",
            "purge {a, b}", "{a, b} = 0", "self.{a, b} = 0", "self.{a, b} <- next()",
        )) {
            assertFailsWith<IllegalStateException>(statement) { parse("func main() { $statement }") }
        }
    }

    @Test fun rejectsReceiverAndCallFanOut() {
        for (statement in listOf(
            "{a, b}.run()", "f({a, b})", "self.{run(), stop()}",
            "fin value: {Int, String} = pair", "fin f = [&, without (a, b)] { 1 }",
        )) {
            assertFailsWith<IllegalStateException>(statement) { parse("func main() { $statement }") }
        }
    }

    @Test fun rejectsDecoratorAndImplementationLists() {
        for (source in listOf(
            "@[First, Second] pack Item", "impl (A, B) {}", "impl (First, Second) for Item {}",
            "derive (Clone, Copy) for Item", "derive Clone for (A, B)",
            "pack Item derives (Clone, Copy)", "pack Item derives [Clone, Copy]",
        )) {
            assertFailsWith<IllegalStateException>(source) { parse(source) }
        }
    }

    @Test fun preservesImportGroupsAndTupleValues() {
        val program = parse("""
            import std.container::{array::*, list::*}
            func main() {
                fin pair = (1, "two")
                fin (a, b) = pair
                fin values: Array<Int> = [1, 2]
                fin product = (a + 1) * 2
            }
        """.trimIndent())
        val use = program.items.filterIsInstance<TopLevel.UseImport>().single()
        assertEquals(2, use.importSpecs.size)
        val body = program.functions.single().body
        assertIs<Expr.TupleLit>(assertIs<Stmt.FinDecl>(body.first()).initializer)
        assertIs<Expr.ArrayLiteral>(assertIs<Stmt.FinDecl>(body[4]).initializer)
    }
}
