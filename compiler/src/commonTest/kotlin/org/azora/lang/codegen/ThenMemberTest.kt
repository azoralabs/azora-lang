/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThenMemberTest {
    companion object {
        internal val program = """
            enum Compare { Less, Equal, Greater }
            impl Compare {
                func &.then(next: Compare): Compare = if self == .Equal { next } else { self }
            }
            func main() {
                assert Compare.Equal.then(Compare.Less) == Compare.Less panic "equal must chain"
                assert Compare.Less.then(Compare.Greater) == Compare.Less panic "decisive must remain"
                assert Compare.Greater.then(Compare.Equal) == Compare.Greater panic "greater must remain"
                if Compare.Equal.then(Compare.Greater) == Compare.Greater
                then assert true panic "branch"
                else assert false panic "then keyword confused with member"
            }
        """.trimIndent()
    }

    @Test fun comparisonChainingExecutesAlongsideThenBranches() {
        for (optimized in listOf(false, true)) {
            assertEquals("", IrInterpreter().interpret(AssertionSemanticsTest.lower(program, optimized)))
        }
    }

    @Test fun thenIsNotAnUnqualifiedIdentifier() {
        for (source in listOf("func then() {}", "func main() { var then = 1 }", "func main() { then() }")) {
            assertFailsWith<IllegalStateException> { Parser(Lexer(source).tokenize()).parse() }
        }
    }

    @Test fun explicitMemberPositionsAcceptThen() {
        for (source in listOf(
            "spec Chain { func &.then(next: Self): Self }",
            "func Compare&.then(next: Compare): Compare = next",
            "impl Chain { func then(): Int = 1 } func main() { Chain::then() }",
            "impl Chain { prop &.then: Int = 1 }",
            "spec Chain { prop &.then: Int }",
            "func main() { value?.then() }",
        )) {
            Parser(Lexer(source).tokenize()).parse()
        }
    }
}
