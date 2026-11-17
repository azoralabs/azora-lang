package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers how operators are declared - one declaration each, a bodyless one
 * saying `bridge` - and that a range needs no operator declaration: `..` is
 * not an operator a type declares (RANGE_DIP §4; `RangeValueTest` covers the
 * refusal).
 */
class MultiOperImplTest {

    @Test
    fun aBodylessOperatorMustSayBridge() {
        // "The compiler implements this" is stated, not inferred from an omission.
        val failure = assertFailsWith<IllegalStateException> {
            Parser(Lexer("oper+ Int&.(rhs: Int&): Int\n").tokenize()).parse()
        }
        assertTrue("declare it 'bridge oper+'" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun theBracketEnumerationFormIsRejected() {
        // `impl oper [.. , >..] for T` was replaced by one declaration each.
        val src = "bridge impl oper [.. by 1, >.. by 1] for Int\n"
        val failure = runCatching { Parser(Lexer(src).tokenize()).parse() }.exceptionOrNull()
        assertTrue(failure != null, "the bracket enumeration form must no longer parse")
    }

    @Test
    fun rangeOverIntCompiles() {
        // An Int range is the language's own counter loop: no declaration, no std.
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
