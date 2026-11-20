/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */

package org.azora.lang.frontend

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NamedArgumentShorthandTest {
    @Test fun shorthandBindsAnyIdentifierUsingTheExistingNamedArgumentAst() {
        val call = call("""
            func render(text: String, styled: String, key: String): String {
                return Text(text, modifier: styled, :key, :styled)
            }
        """)
        assertEquals(listOf("modifier" to "styled", "key" to "key", "styled" to "styled"), labels(call))
    }

    @Test fun commaFreeShorthandAfterAPositionalIdentifierIsItsOwnArgument() {
        val call = call("""
            func render(text: String, modifier: String, suffix: String): String {
                return Text(
                    text
                    :modifier
                    :suffix
                )
            }
        """)
        assertEquals("text", assertIs<Expr.Identifier>(call.args.first()).name)
        assertEquals(listOf("modifier" to "modifier", "suffix" to "suffix"), labels(call))
    }

    @Test fun commaFreeShorthandAfterAChainedNamedArgumentIsItsOwnArgument() {
        val call = call("""
            func caption(text: String, key: String = ""): Entity {
                return Text(
                    text
                    modifier: ::fillMaxWidth().height(17.0).padding(start: 1.0).fontSize(10.5).mutedText().textAlign(.Start)
                    :key
                )
            }
        """)
        assertEquals(3, call.args.size)
        assertEquals("text", assertIs<Expr.Identifier>(call.args[0]).name)
        assertEquals("modifier", assertIs<Expr.NamedArg>(call.args[1]).name)
        val key = assertIs<Expr.NamedArg>(call.args[2])
        assertEquals("key", key.name)
        assertEquals("key", assertIs<Expr.Identifier>(key.value).name)
    }

    @Test fun shorthandPassesTheValueToParametersByName() {
        val source = """
            import std.io
            func label(prefix: String, suffix: String): String = prefix + suffix
            func main() {
                fin prefix = "Az"
                fin suffix = "ora"
                println(label(:suffix, :prefix))
            }
        """.trimIndent()
        val result = assertIs<CompilationResult.Success>(Compiler().compile(source, release = false))
        assertEquals("Azora", IrInterpreter().interpret(result.ir).trim())
    }

    private fun labels(call: Expr.Call) = call.args.filterIsInstance<Expr.NamedArg>().map {
        it.name to assertIs<Expr.Identifier>(it.value).name
    }

    private fun call(source: String): Expr.Call {
        val main = Parser(Lexer(source.trimIndent()).tokenize()).parse().functions.single()
        return assertIs<Expr.Call>(assertIs<Stmt.Return>(main.body.single()).value)
    }
}
