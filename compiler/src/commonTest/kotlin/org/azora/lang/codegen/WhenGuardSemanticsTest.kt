/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WhenGuardSemanticsTest {
    companion object {
        internal val program = """
            pack Counter { var value: Int }
            func matches(counter: Counter!, digit: Int, answer: Bool): Bool {
                counter.value = counter.value * 10 + digit
                return answer
            }
            func forbidden(): Int {
                assert false panic "unselected when branch evaluated"
                return 0
            }
            func main() {
                var counter = Counter(0)
                fin selected = when {
                    matches(counter, 1, false) -> forbidden()
                    matches(counter, 2, true), matches(counter, 3, true) -> 42
                    matches(counter, 4, true) -> forbidden()
                    else -> forbidden()
                }
                assert selected == 42 panic "wrong first matching branch"
                assert counter.value == 12 panic "guards must run once, in order, and short circuit"
                fin fallback = when {
                    matches(counter, 5, false) -> forbidden()
                    else -> 7
                }
                assert fallback == 7 && counter.value == 125 panic "wrong fallback"
                counter.value = 0
                fin andSkipped = matches(counter, 1, false) && matches(counter, 2, true)
                assert andSkipped == false && counter.value == 1 panic "false && must skip its RHS"
                fin andTaken = matches(counter, 3, true) && matches(counter, 4, false)
                assert andTaken == false && counter.value == 134 panic "true && must evaluate its RHS"
                fin orSkipped = matches(counter, 5, true) || matches(counter, 6, false)
                assert orSkipped && counter.value == 1345 panic "true || must skip its RHS"
                fin orTaken = matches(counter, 7, false) || matches(counter, 8, true)
                assert orTaken && counter.value == 134578 panic "false || must evaluate its RHS"
                assert (6 & 3) == 2 && (6 | 3) == 7 panic "bitwise operators changed"
            }
        """.trimIndent()
    }

    @Test fun callsWithArgumentsAreLazyBooleanGuards() {
        for (source in listOf(program, program.replace("when {", "when true {"))) {
            for (optimized in listOf(false, true)) {
                assertEquals("", IrInterpreter().interpret(AssertionSemanticsTest.lower(source, optimized)))
            }
        }
    }

    @Test fun nonBooleanGuardsAreRejectedBySemantics() {
        for (value in listOf("when { 42 -> 1 else -> 2 }", "if 42 then 1 else 2")) {
            val parsed = Parser(Lexer("func main() { fin x = $value }").tokenize()).parse()
            val errors = SemanticPipeline().analyze(parsed).errors
            assertTrue(errors.any { "condition must be Bool" in it }, errors.toString())
        }
    }

    @Test fun missingFallbackCannotSilentlyDiscardTheLastGuard() {
        val error = assertFailsWith<IllegalStateException> {
            Parser(Lexer("func main() { fin x = when { false -> 42 } }").tokenize()).parse()
        }
        assertTrue("guard" in error.message.orEmpty() && "else" in error.message.orEmpty(), error.message)
    }
}
