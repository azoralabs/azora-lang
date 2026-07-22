package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers `impl oper arr![spec, spec, ...] for Type` expansion and the oper.. range gate
 * introduced so range iteration works only where `impl oper .. for T` is declared.
 */
class MultiOperImplTest {

    @Test
    fun multiOperExpandsToOneImplPerSpec() {
        // `bridge impl oper [.. by 1, reverse.. by 1] for Int` expands to two impls
        // (oper.. and operreverse..), both bridge markers carrying a bodyless method.
        val src = "bridge impl oper [.. by 1, reverse.. by 1] for Int\n"
        val program = Parser(Lexer(src).tokenize()).parse()
        val operImpls = program.items.filterIsInstance<TopLevel.Impl>()
        assertEquals(2, operImpls.size, "expected one impl per oper spec")
        val methodNames = operImpls.flatMap { it.methods.map { m -> m.name } }.toSet()
        assertEquals(setOf("oper..", "operreverse.."), methodNames)
        assertTrue(operImpls.all { it.isBridge }, "every expanded oper impl must be a bridge marker")
        assertTrue(operImpls.all { it.typeName == "Int" })
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
