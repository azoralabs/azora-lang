/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrExpr
import org.azora.lang.ir.IrStmt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Kotlin-compatible contracts for the Unit singleton and Nothing bottom type. */
class UnitNothingSemanticsTest {
    private fun compile(source: String): CompilationResult = Compiler().compile(source.trimIndent())

    @Test
    fun unitIsTheSingleFirstClassValueOfUnit() {
        val result = assertIs<CompilationResult.Success>(compile("""
            import std.io

            func completed(): Unit { return Unit }

            func main() {
                fin first: Unit = completed()
                fin second: Unit = Unit
                println(first == second)
            }
        """))

        val completed = result.ir.functions.single { it.name == "completed" }
        val returned = completed.body.single() as IrStmt.Return
        assertTrue(returned.value === IrExpr.UnitLiteral, "Unit must have its own IR node")
        assertTrue("return Unit" in result.ir.prettyPrint(setOf("completed")))
        assertEquals("true", IrInterpreter().interpret(result.ir).trim())
    }

    @Test
    fun nothingFunctionMayTerminateWithPanicAndItsCallFitsEveryValueType() {
        val result = assertIs<CompilationResult.Success>(compile("""
            import std.io

            func stop(message: String): Nothing { panic message }

            func choose(available: Bool): Int {
                return if available { 7 } else { stop("missing") }
            }

            func main() { println(choose(true)) }
        """))

        assertEquals("7", IrInterpreter().interpret(result.ir).trim())
    }

    @Test
    fun anOrdinaryValueCannotBeReturnedAsNothing() {
        val result = assertIs<CompilationResult.Failure>(compile("""
            func invalid(): Nothing { return Unit }
        """))

        assertTrue(result.errors.any { "expected Nothing but got Unit" in it }, result.errors.toString())
    }

    @Test
    fun onlyNullableNothingAcceptsNull() {
        assertIs<CompilationResult.Success>(compile("""
            func main() { fin missing: Nothing? = null }
        """))

        val result = assertIs<CompilationResult.Failure>(compile("""
            func main() { fin impossible: Nothing = null }
        """))
        assertTrue(
            result.errors.any { "declared Nothing but initializer is Any" in it },
            result.errors.toString(),
        )
    }

    @Test
    fun defaultValueTypeUsesNothingForTheAbsentCase() {
        val result = assertIs<CompilationResult.Success>(compile("""
            deepinline func<T> DefaultValueType(hasDefault: Bool, isNullable: Bool): Type {
                return when {
                    hasDefault && isNullable => T?
                    hasDefault => T
                    else => Nothing
                }
            }

            func present(): DefaultValueType<Int>(true, false) { return 1 }
            func absent(): DefaultValueType<Int>(false, false) { panic "no default" }
        """))

        assertTrue(result.ir.functions.any { it.name == "present" && it.returnType.toString() == "Int" })
        assertTrue(result.ir.functions.any { it.name == "absent" && it.returnType.toString() == "Nothing" })
    }
}
