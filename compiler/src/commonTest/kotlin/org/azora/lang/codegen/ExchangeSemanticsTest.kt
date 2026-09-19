/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TokenType
import org.azora.lang.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ExchangeSemanticsTest {
    companion object {
        internal fun compilePublicProgram(optimized: Boolean) =
            assertIs<CompilationResult.Success>(Compiler().compile("""
                func main() {
                    var a = 3
                    var b = 7
                    a <> b
                    assert a == 7 && b == 3 panic "public compilation lost exchange"
                }
            """.trimIndent(), release = optimized)).ir

        internal val program = """
            bridge oper.. Int&.(rhs: Int&) by 1
            pack Box { var value: Int }
            func main() {
                var a = 3
                var b = 7
                a <> b
                assert a == 7 && b == 3 panic "variable exchange lost a value"
                a <> a
                assert a == 7 panic "self exchange changed a value"
                var first = Box(11)
                var second = Box(22)
                first <> second
                assert first.value == 22 && second.value == 11 panic "owner exchange copied or lost a handle"
                first.value <> second.value
                assert first.value == 11 && second.value == 22 panic "field exchange lost a value"
                var values = Array(0, 0, 0)
                values[0] = 10
                values[1] = 20
                values[2] = 30
                var index = 0
                values[index++] <> values[index++]
                assert index == 2 panic "locations must evaluate once, left to right"
                assert values[0] == 20 && values[1] == 10 panic "array exchange lost a value"
                values[index] <> values[index]
                assert values[2] == 30 panic "aliasing array locations changed a value"
                a <> values[0]
                assert a == 20 && values[0] == 7 panic "mixed locations must exchange"
                scope { a <> b }
                assert a == 3 && b == 20 panic "optimizer kept stale values across a scope"
                for k in 0..<1 { if k == 0 then a <> b }
                assert a == 20 && b == 3 panic "optimizer kept stale values across a loop"
                index = 0
                values[index++] <> values[--index]
                assert index == 0 && values[0] == 7 panic "alias locations must still evaluate both indices"
                var cursor = 0
                cursor <> values[cursor++]
                assert cursor == 7 && values[0] == 1 panic "values must be loaded after both locations are evaluated"
                var x: Double = 1.5
                var y: Double = 9.5
                x <> y
                assert x == 9.5 && y == 1.5 panic "scalar storage type changed"
            }
        """.trimIndent()

        internal val boundsProgram = """
            func main() {
                var values = Array(5, 5)
                values[0] <> values[2]
            }
        """.trimIndent()
    }

    @Test fun locationsExchangeInInterpreter() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(AssertionSemanticsTest.lower(program, optimized)))
        }
    }

    @Test fun exchangeUsesThePublicCompilerPipeline() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(compilePublicProgram(optimized)))
        }
        assertIs<CompilationResult.Failure>(Compiler().compile(
            "func exchange(a: Int!, b: Int!) { a <> b }",
        ))
    }

    @Test fun invalidIndexTraps() {
        for (optimized in listOf(false, true)) {
            val error = assertFailsWith<IllegalStateException> {
                IrInterpreter().interpret(AssertionSemanticsTest.lower(boundsProgram, optimized))
            }
            assertTrue("out of bounds" in error.message.orEmpty(), error.message)
        }
    }

    @Test fun exchangeIsAtomicLexicallyAndIsOnlyAStatement() {
        assertEquals(listOf(TokenType.EXCHANGE, TokenType.SPACESHIP, TokenType.LESS_EQUAL),
            Lexer("<> <=> <=").tokenize().filter { it.type !in setOf(TokenType.EOF, TokenType.NEWLINE) }.map { it.type })
        for (source in listOf("func main() { 1 <> 2 }", "func main() { fin result = a <> b }")) {
            assertFailsWith<IllegalStateException> { Parser(Lexer(source).tokenize()).parse() }
        }
    }

    @Test fun invalidStorageTypesMutabilityAndBorrowConflictsAreRejected() {
        val cases = listOf(
            "fin a = 1\nvar b = 2\na <> b" to "mutable binding",
            "var a = 1\nvar b = true\na <> b" to "same type",
            "var a = 1\nvar b = 2\nlet loan = (a&)\na <> b\nassert loan == 1 panic \"live\"" to "borrow",
        )
        for ((body, expected) in cases) {
            val errors = SemanticPipeline().analyze(Parser(Lexer("func main() { $body }").tokenize()).parse()).errors
            assertTrue(errors.any { expected in it }, errors.toString())
        }
    }

    @Test fun unsupportedOrUnsafeLocationsAreRejectedBeforeLowering() {
        val cases = listOf(
            "pack Fixed { fin value: Int }\nfunc main() { var a = Fixed(1)\nvar b = Fixed(2)\na.value <> b.value }" to "mutable stored pack field",
            "func main() { fin values = Array(1, 2)\nvalues[0] <> values[1] }" to "immutable",
            "func main() { lazy let values = Array(1, 2)\nvalues[0] <> values[1] }" to "lazy/reactive",
            "func index(): Int { return 0 }\nfunc main() { var values = Array(1, 2)\nvalues[index()] <> values[1] }" to "location-loan",
            "pack Index { var value: Int }\noper- Index&.(): Int { return self.value }\nfunc main() { var values = Array(1, 2)\nvar i = Index(0)\nvalues[-i] <> values[1] }" to "location-loan",
            "pack Index { var value: Int }\noper+ Index&.(other: Index&): Int { return self.value }\nfunc main() { var values = Array(1, 2)\nvar i = Index(0)\nvalues[i + i] <> values[1] }" to "location-loan",
            "func exchange(p: Int*, q: Int*) { p.* <> q.* }" to "raw dereferences",
            "func exchange(a: Int!, b: Int!) { a <> b }" to "borrow lowering",
            "pack Recursive { var child: Recursive }\nfunc exchange(node: Recursive!) { node <> node.child }" to "partially overlap",
        )
        for ((source, expected) in cases) {
            val errors = SemanticPipeline().analyze(Parser(Lexer(source).tokenize()).parse()).errors
            assertTrue(errors.any { expected in it }, "$source: $errors")
        }
    }
}
