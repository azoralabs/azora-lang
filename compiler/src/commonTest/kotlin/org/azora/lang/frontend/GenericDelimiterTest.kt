/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */

package org.azora.lang.frontend

import org.azora.lang.ir.IrType
import org.azora.lang.semantic.SymbolCollector
import org.azora.lang.semantic.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** A closing `>>` must close both a nested type and its enclosing declaration. */
class GenericDelimiterTest {
    private fun parse(source: String): Program = Parser(Lexer(source).tokenize()).parse()

    private fun shape(type: TypeRef): String {
        val named = assertIs<TypeRef.Named>(type)
        return named.name + if (named.args.isEmpty()) "" else named.args.joinToString(", ", "<", ">", transform = ::shape)
    }

    @Test fun nestedConstraintClosesTheFunctionTypeParameters() {
        val decl = parse("func<T, I: Indexed<T>> findIndex(value: T): T { return value }")
            .items.filterIsInstance<TopLevel.Func>().single().decl
        assertEquals("findIndex", decl.name)
        assertEquals(listOf("T", "I"), decl.typeParams)
        assertEquals(listOf("value"), decl.params.map { it.name })
    }

    @Test fun nestedDefaultsMatchSpacedClosingDelimiters() {
        for (depth in 1..4) {
            val type = "Box<".repeat(depth) + "Int" + ">".repeat(depth)
            val compact = parse("spec Holder<T = $type> {}")
                .items.filterIsInstance<TopLevel.Spec>().single()
            val spaced = parse("spec Holder<T = ${type.replace(">", "> ")}> {}")
                .items.filterIsInstance<TopLevel.Spec>().single()
            assertEquals(spaced.typeParams, compact.typeParams)
            assertEquals(spaced.typeDefaults.mapValues { shape(it.value) },
                compact.typeDefaults.mapValues { shape(it.value) })
        }
    }

    @Test fun aNestedDefaultDoesNotConsumeTheNextParameter() {
        val decl = parse("spec Holder<T = Box<Box<Int>>, U = Bool> {}")
            .items.filterIsInstance<TopLevel.Spec>().single()
        assertEquals(listOf("T", "U"), decl.typeParams)
        assertEquals("Box<Box<Int>>", shape(decl.typeDefaults.getValue("T")))
        assertEquals("Bool", shape(decl.typeDefaults.getValue("U")))
    }

    @Test fun aNestedArgumentDoesNotConsumeTheOuterArgument() {
        val program = parse("""
            pack Box<T> { var value: T }
            pack Pair<A, B> { var first: A
                var second: B }
            pack Holder { var value: Pair<Box<Box<Int>>, Bool> }
        """.trimIndent())
        val decl = program.items.filterIsInstance<TopLevel.Pack>().single { it.name == "Holder" }
        assertEquals("Pair<Box<Box<Int>>, Bool>", shape(decl.fields.single().type))

        // Verify the type handed to the remaining compiler stages too: Bool is
        // Pair's second argument, not an extra argument of the inner Box.
        val symbols = SymbolTable()
        assertEquals(emptyList(), SymbolCollector().collect(program, symbols))
        val pair = assertIs<IrType.Named>(symbols.lookupStruct("Holder")!!.field("value")!!.type)
        assertEquals(2, pair.args.size)
        assertEquals(IrType.Bool, pair.args[1])
        val outerBox = assertIs<IrType.Named>(pair.args[0])
        val innerBox = assertIs<IrType.Named>(outerBox.args.single())
        assertEquals(IrType.Int, innerBox.args.single())
    }

    @Test fun aMissingDeclarationDelimiterStillFails() {
        assertFailsWith<IllegalStateException> {
            parse("func<T: Indexed<T> findIndex(value: T): T { return value }")
        }
    }

    @Test fun anExtraDeclarationDelimiterStillFails() {
        for (params in listOf("<T: Indexed<T>>>", "<T>>")) {
            assertFailsWith<IllegalStateException> {
                parse("func$params findIndex(value: T): T { return value }")
            }
        }
    }

    @Test fun aSuffixAfterTwoClosersAppliesToTheOuterType() {
        val field = parse("pack Holder { var value: Box<Box<Int>>? }")
            .items.filterIsInstance<TopLevel.Pack>().single().fields.single()
        assertEquals("Box<Box<Int>>", shape(assertIs<TypeRef.Nullable>(field.type).inner))
    }

    @Test fun genericCallArgumentsKeepTheirNesting() {
        val decl = parse("func main() { use<Box<Box<Int>>, Bool>() }")
            .items.filterIsInstance<TopLevel.Func>().single().decl
        val call = assertIs<Expr.Call>(assertIs<Stmt.ExprStmt>(decl.body.single()).expr)
        assertEquals(listOf("Box<Box<Int>>", "Bool"), call.typeArgs.map(::shape))
    }

    @Test fun shiftAfterAGenericDeclarationKeepsItsOperator() {
        val decl = parse("func<T: Indexed<T>> shift(value: Int): Int { return value >> 1 }")
            .items.filterIsInstance<TopLevel.Func>().single().decl
        val expression = assertIs<Expr.Binary>(assertIs<Stmt.Return>(decl.body.single()).value)
        assertEquals(TokenType.SHIFT_RIGHT, expression.op)
    }
}
