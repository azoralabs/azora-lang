package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers `impl oper @arr[spec, spec, ...] for Type` expansion and the oper.. range gate
 * introduced so range iteration works only where `impl oper .. for T` is declared.
 */
class MultiOperImplTest {

    @Test
    fun aBodylessOperatorMustSayBridge() {
        // "The compiler implements this" is stated, not inferred from an omission.
        val failure = assertFailsWith<IllegalStateException> {
            Parser(Lexer("oper.. [self: Int&](rhs: Int&) by 1\n").tokenize()).parse()
        }
        assertTrue("declare it 'bridge oper..'" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun eachRangeOperatorIsDeclaredOnItsOwn() {
        // Each direction is its own declaration, naming its receiver and trailing
        // its default step. A compiler-provided one says `bridge` rather than
        // leaving it to be inferred from the missing body.
        val src = "bridge oper.. [self: Int&](rhs: Int&) by 1\n" +
            "bridge oper reverse.. [self: Int&](rhs: Int&) by 1\n"
        val program = Parser(Lexer(src).tokenize()).parse()
        val operImpls = program.items.filterIsInstance<TopLevel.Impl>()
        assertEquals(2, operImpls.size, "expected one impl per oper declaration")
        val methodNames = operImpls.flatMap { it.methods.map { m -> m.name } }.toSet()
        assertEquals(setOf("oper..", "operreverse.."), methodNames)
        assertTrue(operImpls.all { it.isBridge }, "a 'bridge oper' declaration is a bridge marker")
        assertTrue(operImpls.all { it.typeName == "Int" })
    }

    @Test
    fun theBracketEnumerationFormIsRejected() {
        // `impl oper [.. , reverse..] for T` was replaced by one declaration each.
        val src = "bridge impl oper [.. by 1, reverse.. by 1] for Int\n"
        val failure = runCatching { Parser(Lexer(src).tokenize()).parse() }.exceptionOrNull()
        assertTrue(failure != null, "the bracket enumeration form must no longer parse")
    }

    @Test
    fun rangeOverIntCompiles() {
        // Int ranges compile because std/traits/core.az registers oper.. for Int.
        val result = Compiler().compile(
            """
            func main(): Int {
                var sum = 0
                for i in 0..<5 { sum = sum + i }
                return sum
            }
            """.trimIndent(),
            release = false,
        )
        assertTrue(result is CompilationResult.Success, "Int range should compile: ${(result as? CompilationResult.Failure)?.errors}")
    }
}
