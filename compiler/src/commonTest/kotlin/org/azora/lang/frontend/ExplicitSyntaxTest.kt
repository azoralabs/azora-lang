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
            "fin value: {Int, String} = pair",
        )) {
            assertFailsWith<IllegalStateException>(statement) { parse("func main() { $statement }") }
        }
    }

    @Test fun declarationListsRejectBracesAndBrackets() {
        for (source in listOf(
            "@[First, Second] pack Item", "@{First, Second} pack Item",
            "impl [A, B] {}", "impl {A, B} {}",
            "impl [First, Second] for Item {}", "impl First for [A, B] {}",
            "derive [Clone, Copy] for Item", "derive {Clone, Copy} for Item",
            "derive Clone for [A, B]", "derive Clone for {A, B}",
            "pack Item derives [Clone, Copy]", "pack Item derives {Clone, Copy}",
        )) {
            assertFailsWith<IllegalStateException>(source) { parse(source) }
        }
    }

    @Test fun packDerivesUsesABareSingleSpecOrOneListOfSeveralSpecs() {
        for (source in listOf(
            "pack NavOwnerId derives Copy derives Clone derives Equal derives Hash {}",
            "pack NavOwnerId derives (Copy, Clone) derives Equal {}",
            "pack NavOwnerId derives (Copy, Clone)\nderives (Equal, Hash) {}",
            "pack NavOwnerId derives Copy, Clone {}",
            "pack NavOwnerId derives (Copy) {}",
            "pack NavOwnerId derives () {}",
        )) {
            assertFailsWith<IllegalStateException>(source) { parse(source) }
        }
        val program = parse("pack NavOwnerId derives (Copy, Clone, Equal, Hash) {}")
        assertEquals(listOf("Copy", "Clone", "Equal", "Hash"), program.items.filterIsInstance<TopLevel.Impl>().map { it.traitName })
        assertEquals("Equal", parse("pack Point derives Equal {}").items.filterIsInstance<TopLevel.Impl>().single().traitName)
    }

    @Test fun parenthesizedDerivesPreserveEverySpecAndItsOrder() {
        val implementations = parse("pack Text derives (Copy, Clone, Equal, Hash, Compose)")
            .items.filterIsInstance<TopLevel.Impl>()
        assertEquals(listOf("Copy", "Clone", "Equal", "Hash", "Compose"), implementations.map { it.traitName })
        assertEquals(List(5) { "Text" }, implementations.map { it.typeName })
    }

    @Test fun parenthesizedListsExpandDerivationsAndDecoratorTargets() {
        val program = parse("""
            @(First, Second(value: 1)) pack Item
            derive (Copy, Clone) for (A, B)
            impl (First, Second) for (Item::x, Item::y) {}
        """.trimIndent())
        assertEquals(listOf("First", "Second"), program.items.filterIsInstance<TopLevel.Pack>().single().annotations.map { it.name })
        val implementations = program.items.filterIsInstance<TopLevel.Impl>()
        assertEquals(listOf("A", "A", "B", "B", "Item.x", "Item.x", "Item.y", "Item.y"), implementations.map { it.typeName })
    }

    @Test fun parenthesizedInherentImplementationsKeepTheirMembers() {
        val implementations = parse("impl (A, B) { func &.value(): Int = 1 }")
            .items.filterIsInstance<TopLevel.Impl>()
        assertEquals(listOf("A", "B"), implementations.map { it.typeName })
        assertEquals(listOf(listOf("value"), listOf("value")), implementations.map { it.methods.map { method -> method.name } })
    }

    @Test fun declarationListsSupportMultilineTrailingCommasAndGenericSpecs() {
        val implementations = parse("""
            pack Item<T>
            derives (
                Clone,
                Iterator<T>,
            )
        """.trimIndent()).items.filterIsInstance<TopLevel.Impl>()
        assertEquals(listOf("Clone", "Iterator"), implementations.map { it.traitName })
        assertEquals(1, implementations.last().traitArgs.size)
    }

    @Test fun lambdaCaptureExclusionsKeepParenthesizedLists() {
        parse("func main() { fin f = [&, without (a, b)] { 1 } }")
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
