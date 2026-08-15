/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.lang.semantic

import org.azora.lang.frontend.AstValidator
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decorator field defaults computed from the fields before them, and the
 * branches that seal what they compute.
 *
 * A default like `when level { .Error -> sealed "!! "  else -> "" }`
 * has no single answer at the declaration: which branch it takes is decided by
 * each application. The seal rides on the branch rather than the field, so the
 * same field is fixed for one application and free for the next.
 */
class SealedDecoratorDefaultTest {
    private fun analyze(source: String): SemanticResult {
        val program = Parser(Lexer(source).tokenize()).parse()
        val validationErrors = AstValidator().validate(program)
        check(validationErrors.isEmpty()) { validationErrors.joinToString("\n") }
        return SemanticPipeline().analyze(program)
    }

    private fun returnedExpression(result: SemanticResult, functionName: String): Expr {
        val function = result.program.items.filterIsInstance<TopLevel.Func>()
            .single { it.decl.name == functionName }
        return function.decl.body.filterIsInstance<Stmt.Return>().single().value
            ?: error("Expected a return value")
    }

    private val declaration = """
        enum Level { Error, Info }

        annot Log for .Pack {
            fin level: Level = .Info
            fin prefix: std::String = when level {
                .Error -> sealed "!! "
                else -> ""
            }
        }
    """.trimIndent()

    @Test fun defaultIsFoldedAgainstWhatTheApplicationPassed() {
        val result = analyze("""
            $declaration

            @Log(level: .Error) pack Loud
            @Log pack Plain

            func loud(): std::String {
                inline fin value = std::reflect<Loud>.annotMeta<Log>.prefix
                return value
            }

            func plain(): std::String {
                inline fin value = std::reflect<Plain>.annotMeta<Log>.prefix
                return value
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("!! ", (returnedExpression(result, "loud") as Expr.StringLiteral).value)
        assertEquals("", (returnedExpression(result, "plain") as Expr.StringLiteral).value)
    }

    @Test fun theDefaultIsAlsoFoldedForAPositionalApplication() {
        val result = analyze("""
            $declaration

            @Log(.Error) pack Loud

            func loud(): std::String {
                inline fin value = std::reflect<Loud>.annotMeta<Log>.prefix
                return value
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("!! ", (returnedExpression(result, "loud") as Expr.StringLiteral).value)
    }

    @Test fun settingASealedFieldIsRejected() {
        val result = analyze("""
            $declaration

            @Log(level: .Error, prefix: "mine") pack Loud

            func main() {}
        """.trimIndent())

        assertTrue(
            result.errors.any { it.contains("sealed") && it.contains("prefix") },
            "expected the seal to be enforced, got ${result.errors}",
        )
    }

    /** The seal belongs to one branch, so every other application still sets the field freely. */
    @Test fun settingTheFieldIsFineWhereTheBranchDoesNotSeal() {
        val result = analyze("""
            $declaration

            @Log(level: .Info, prefix: "mine") pack Plain

            func plain(): std::String {
                inline fin value = std::reflect<Plain>.annotMeta<Log>.prefix
                return value
            }

            func main() {}
        """.trimIndent())

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("mine", (returnedExpression(result, "plain") as Expr.StringLiteral).value)
    }

    @Test fun sealedOutsideADecoratorDefaultIsRejected() {
        val result = analyze("""
            enum Level { Error, Info }

            func label(l: Level): std::String {
                fin value: std::String = when l {
                    .Error -> sealed "!! "
                    else -> ""
                }
                return value
            }

            func main() {}
        """.trimIndent())

        assertTrue(
            result.errors.any { it.contains("sealed") },
            "expected 'sealed' to be rejected outside a decorator field default, got ${result.errors}",
        )
    }
}
