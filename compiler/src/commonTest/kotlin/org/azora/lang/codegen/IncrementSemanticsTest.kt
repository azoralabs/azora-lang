/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncrementSemanticsTest {
    companion object {
        internal val globalProgram = """
            threadlocal var sharedCounter: Int = 10
            func update(): Int { return sharedCounter++ }
            func main() {
                assert update() == 10 panic "global postfix value changed"
                assert sharedCounter == 11 panic "increment wrote a local instead of the global"
                assert --sharedCounter == 10 panic "global prefix value changed"
                assert sharedCounter == 10 panic "global decrement was lost"
            }
        """.trimIndent()

        internal val program = """
            func pair(a: Int, b: Int): Int { return a * 10 + b }
            func main() {
                var n: Int = 3
                fin old = n++
                assert old == 3 && n == 4 panic "postfix increment must return the old value"
                fin current = ++n
                assert current == 5 && n == 5 panic "prefix increment must return the new value"
                fin prior = n--
                assert prior == 5 && n == 4 panic "postfix decrement must return the old value"
                fin decreased = --n
                assert decreased == 3 && n == 3 panic "prefix decrement must return the new value"
                assert pair(n++, n++) == 34 && n == 5 panic "argument order or mutation count changed"
                var floating: Double = 1.5
                fin was = floating++
                assert was == 1.5 && floating == 2.5 panic "floating postfix value changed"
                fin now = --floating
                assert now == 1.5 && floating == 1.5 panic "floating prefix value changed"
                var untouched: Int = 0
                fin skipped = false && untouched++ > 0
                assert skipped == false && untouched == 0 panic "short circuit evaluated an increment"
                fin conditional = if true then untouched++ else ++untouched
                assert conditional == 0 && untouched == 1 panic "if branch increment changed"
                var visits: Int = 0
                var index: Int = 0
                while index++ < 3 {
                    visits++
                }
                assert index == 4 && visits == 3 panic "loop condition or body reused a stale constant"
                var scopeCounter: Int = 7
                scope { scopeCounter++ }
                assert scopeCounter == 8 panic "scope exit lost an increment"
                if scopeCounter == 8 then scopeCounter--
                assert scopeCounter == 7 panic "branch exit lost a decrement"
            }
        """.trimIndent()
    }

    @Test fun incrementValuesAndMutationsAgreeInInterpreter() {
        for (source in listOf(program, globalProgram)) {
            for (optimized in listOf(false, true)) {
                assertEquals("", IrInterpreter().interpret(AssertionSemanticsTest.lower(source, optimized)))
            }
        }
    }

    @Test fun immutableAndNonNumericTargetsAreRejected() {
        for (source in listOf(
            "func main() { fin n = 1\n n++ }",
            "func main() { var value = true\n value++ }",
        )) {
            val program = Parser(Lexer(source).tokenize()).parse()
            val errors = SemanticPipeline().analyze(program).errors.filterNot { it.startsWith("warning:") }
            assertTrue(errors.isNotEmpty(), "invalid increment accepted: $source")
        }
    }
}
