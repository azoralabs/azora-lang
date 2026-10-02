/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */

package org.azora.lang.codegen

import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.AstValidator
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.ir.IrGenerator
import org.azora.lang.ir.IrOptimizer
import org.azora.lang.ir.IrProgram
import org.azora.lang.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tests the real compiler stages without importing the currently broken stdlib. */
class AssertionSemanticsTest {
    companion object {
        internal val lazyMessageProgram = """
            pack Counter { var value: Int }
            func condition(calls: Counter!): Bool {
                calls.value = calls.value + 1
                return true
            }
            func message(): String {
                assert false panic "a successful assertion evaluated its message"
                return "unused"
            }
            func main() {
                var calls = Counter(0)
                assert condition(calls) panic message()
                assert calls.value == 1 panic "assert condition must execute exactly once"
            }
        """.trimIndent()

        internal val singleStatementContractProgram = """
            func checked(n: Int): Int
            in assert n >= 0 panic
                "negative input"
            out assert it < 10 panic "result too large"
            scope if n == 0
            then return 0
            else return n
            func main() {
                assert checked(0) == 0 panic "wrong zero"
                assert checked(7) == 7 panic "wrong positive"
            }
        """.trimIndent()

        internal val singleStatementMemberProgram = """
            pack Counter { var value: Int }
            impl Counter {
                ctor .(n: Int)
                in assert n >= 0 panic "negative counter"
                scope self.value = n
                prop &.current: Int scope return self.value
                dtor .() scope self.value = 0
            }
            func main() {
                var counter = Counter(7)
                assert counter.current == 7 panic "constructor/property lost the value"
            }
        """.trimIndent()

        internal val sharedConditionProgram = """
            pack Counter { var value: Int }
            func choose(counter: Counter!): Bool {
                counter.value = counter.value + 1
                return true
            }
            func main() {
                var counter = Counter(0)
                fin selected = choose(counter)
                var sign: Int = if selected then -1 else 1
                var index: Int = if selected then 1 else 0
                assert sign == -1 && index == 1 panic "wrong selected values"
                assert counter.value == 1 panic "condition evaluated more than once"
                var positive: Int = if false then -1 else 1
                var start: Int = if false then 1 else 0
                assert positive == 1 && start == 0 panic "wrong alternative values"
            }
        """.trimIndent()

        internal fun lower(source: String, optimized: Boolean = false): IrProgram {
            val program = Parser(Lexer(source.trimIndent()).tokenize()).parse()
            assertEquals(emptyList(), AstValidator().validate(program))
            val semantic = SemanticPipeline().analyze(program)
            assertEquals(emptyList(), semantic.errors)
            val ir = IrGenerator(semantic.symbolTable).generate(semantic.program)
            return if (optimized) IrOptimizer().optimize(ir) else ir
        }
    }

    @Test fun conditionRunsOnceAndPassingMessageIsNotEvaluated() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(lower(lazyMessageProgram, optimized)))
        }
    }

    @Test fun failureEvaluatesTheMessageExpression() {
        val source = """
            func message(): String { return "computed assertion message" }
            func main() { assert false panic message() }
        """
        for (optimized in listOf(false, true)) {
            val error = assertFailsWith<IllegalStateException> {
                IrInterpreter().interpret(lower(source, optimized))
            }
            assertTrue("Assertion failed: computed assertion message" in error.message.orEmpty())
        }
    }

    @Test fun conditionMustBeBoolean() {
        val program = Parser(Lexer("func main() { assert 42 panic \"bad condition\" }").tokenize()).parse()
        val errors = SemanticPipeline().analyze(program).errors
        assertTrue(errors.any { "assert condition must be Bool" in it }, errors.toString())
    }

    @Test fun messageMustBeAStringEvenForAPassingAssertion() {
        val program = Parser(Lexer("func main() { assert true panic 42 }").tokenize()).parse()
        val errors = SemanticPipeline().analyze(program).errors
        assertTrue(errors.any { "assert message must be String" in it }, errors.toString())
    }

    @Test fun removedAndMissingMessageFormsAreRejected() {
        for (message in listOf("{ \"old\" }", "then \"old\"", "else \"old\"", "")) {
            for (prefix in listOf("assert", "inline assert")) {
                val error = assertFailsWith<IllegalStateException> {
                    Parser(Lexer("func main() { $prefix true $message }").tokenize()).parse()
                }
                assertTrue("Expected 'panic'" in error.message.orEmpty(), error.message)
            }
        }
    }

    @Test fun compileTimeAssertionsUseTheSameMessageGrammar() {
        val good = "inline assert true panic \"checked\"\nfunc main() {}"
        assertEquals("", IrInterpreter().interpret(lower(good)))
        val bad = Parser(Lexer("inline assert false panic \"compile-time failure\"\nfunc main() {}").tokenize()).parse()
        val errors = SemanticPipeline().analyze(bad).errors
        assertTrue(errors.any { "compile-time failure" in it }, errors.toString())
    }

    @Test fun contractsPreserveTheirConditionsAndMessages() {
        val source = """
            func identity(n: Int): Int
            in { assert n >= 0 panic "negative input" }
            out { assert it == n panic "changed result" }
            scope { return n }
            func main() { assert identity(7) == 7 panic "wrong result" }
        """
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(lower(source, optimized)))
            val error = assertFailsWith<IllegalStateException> {
                IrInterpreter().interpret(lower(source.replace("identity(7)", "identity(-1)"), optimized))
            }
            assertTrue("negative input" in error.message.orEmpty(), error.message)
        }
    }

    @Test fun singleStatementContractsCheckEveryReturn() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(lower(singleStatementContractProgram, optimized)))
            for ((argument, message) in listOf("-1" to "negative input", "10" to "result too large")) {
                val source = singleStatementContractProgram.replace("checked(7)", "checked($argument)")
                val error = assertFailsWith<IllegalStateException> {
                    IrInterpreter().interpret(lower(source, optimized))
                }
                assertTrue(message in error.message.orEmpty(), error.message)
            }
        }
    }

    @Test fun singleStatementMemberBodiesRetainTheirSemantics() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(lower(singleStatementMemberProgram, optimized)))
            val error = assertFailsWith<IllegalStateException> {
                IrInterpreter().interpret(lower(singleStatementMemberProgram.replace("Counter(7)", "Counter(-1)"), optimized))
            }
            assertTrue("negative counter" in error.message.orEmpty(), error.message)
        }
    }

    @Test fun explicitConditionIsEvaluatedOnceForAllBindings() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(lower(sharedConditionProgram, optimized)))
        }
    }

    @Test fun unsafeSingleStatementDoesNotMakeTheNextStatementUnsafe() {
        val source = """
            unsafe func raw(): Int { return 1 }
            func main() {
                unsafe assert raw() == 1 panic "inside"
                assert raw() == 1 panic "outside"
            }
        """.trimIndent()
        val program = Parser(Lexer(source).tokenize()).parse()
        val errors = SemanticPipeline().analyze(program).errors
        assertTrue(errors.any { "unsafe" in it && "raw" in it }, errors.toString())
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(lower(source.replace("assert raw() == 1 panic \"outside\"", ""), optimized)))
        }
    }
}
