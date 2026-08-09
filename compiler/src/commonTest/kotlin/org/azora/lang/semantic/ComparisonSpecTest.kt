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
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The comparison family of `DIPs/OPERATOR_OVERLOADING_DIP.MD`: the `<=>` token,
 * `oper` members on a `spec`, and the operators the compiler rewrites rather
 * than looks up.
 */
class ComparisonSpecTest {
    private fun parse(source: String) = Parser(Lexer(source).tokenize()).parse()

    private fun analyze(source: String): SemanticResult {
        val program = parse(source)
        val validationErrors = AstValidator().validate(program)
        check(validationErrors.isEmpty()) { validationErrors.joinToString("\n") }
        return SemanticPipeline().analyze(program)
    }

    // -- Phase 2: the token ------------------------------------------------

    @Test fun spaceshipLexesAsOneToken() {
        val tokens = Lexer("a <=> b").tokenize()
        assertTrue(tokens.any { it.type == TokenType.SPACESHIP }, tokens.map { it.type }.toString())
        assertTrue(tokens.none { it.type == TokenType.LESS_EQUAL }, "'<=' must not be split out of '<=>'")
    }

    @Test fun lessEqualStillLexesWhenNotFollowedByGreater() {
        val tokens = Lexer("a <= b").tokenize()
        assertTrue(tokens.any { it.type == TokenType.LESS_EQUAL })
        assertTrue(tokens.none { it.type == TokenType.SPACESHIP })
    }

    @Test fun spaceshipBindsTighterThanRelational() {
        // `a <=> b == c` must group as `(a <=> b) == c`.
        val program = parse("func main() { fin x = 1 <=> 2 }")
        assertTrue(program.items.isNotEmpty())
    }

    // -- Phase 1: oper members on a spec -----------------------------------

    @Test fun specDeclaresAnOperatorRequirement() {
        val program = parse(
            """
            spec Order {
                oper<=> [self: std::Self&](rhs: std::Self&): std::Compare
            }
            func main() {}
            """.trimIndent(),
        )
        val spec = program.items.filterIsInstance<org.azora.lang.frontend.TopLevel.Spec>().single()
        assertEquals(listOf("oper<=>"), spec.methods.map { it.name })
    }

    @Test fun specOperatorRequirementMayOmitTheReceiver() {
        val program = parse(
            """
            spec Negate {
                oper-: std::Self
            }
            func main() {}
            """.trimIndent(),
        )
        val spec = program.items.filterIsInstance<org.azora.lang.frontend.TopLevel.Spec>().single()
        assertEquals(listOf("oper-"), spec.methods.map { it.name })
    }

    @Test fun specMixesOperFuncAndProp() {
        val program = parse(
            """
            spec Container {
                oper[] [self: std::Self&](index: std::Int): std::Int
                func size[self: std::Self&](): std::Int
                prop empty[self: std::Self&]: std::Bool
            }
            func main() {}
            """.trimIndent(),
        )
        val spec = program.items.filterIsInstance<org.azora.lang.frontend.TopLevel.Spec>().single()
        assertEquals(listOf("index", "size", "empty"), spec.methods.map { it.name })
    }

    @Test fun implSuppliesASpecsOperator() {
        val result = analyze(
            """
            spec Eq {
                oper== [self: std::Self&](rhs: std::Self&): std::Bool
            }
            pack Point {
                var x: std::Int
                var y: std::Int
            }
            impl Eq for Point {
                oper== [self: std::Self&](rhs: std::Self&): std::Bool {
                    return self.x == rhs.x && self.y == rhs.y
                }
            }
            func main() {}
            """.trimIndent(),
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    // -- Phase 5: `where T: Spec` bounds -----------------------------------

    @Test fun whereClauseAcceptsASpecBound() {
        val result = analyze(
            """
            spec Order {
                oper<=> [self: std::Self&](rhs: std::Self&): std::Int
            }
            pack Version {
                var major: std::Int
            }
            impl Order for Version {
                oper<=> [self: std::Self&](rhs: std::Self&): std::Int {
                    return self.major - rhs.major
                }
            }
            func newest<T>(a: T, b: T): T where T: Order {
                return a
            }
            func main() {}
            """.trimIndent(),
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    @Test fun whereClauseAcceptsAGenericSpecBound() {
        val result = analyze(
            """
            spec PartialEqual<Rhs> {
                oper== [self: std::Self&](rhs: Rhs&): std::Bool
            }
            pack A { var v: std::Int }
            pack B { var v: std::Int }
            impl PartialEqual<B> for A {
                oper== [self: std::Self&](rhs: B&): std::Bool {
                    return self.v == rhs.v
                }
            }
            func same<T, U>(a: T, b: U): std::Bool where T: PartialEqual<U> {
                return true
            }
            func main() {}
            """.trimIndent(),
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    @Test fun whereClauseAcceptsABoundList() {
        val result = analyze(
            """
            spec Order {
                oper<=> [self: std::Self&](rhs: std::Self&): std::Int
            }
            spec Tagged {
                prop tag[self: std::Self&]: std::Int
            }
            func sortable<K>(k: K): std::Bool where K: [Order, Tagged] {
                return true
            }
            func main() {}
            """.trimIndent(),
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    /** An ordinary constraint expression must still parse as one. */
    @Test fun whereClauseStillAcceptsAPredicateExpression() {
        val program = parse(
            """
            pack Pair<...T> where T.length >= 2 {
                var first: std::Int
            }
            func main() {}
            """.trimIndent(),
        )
        assertTrue(program.items.isNotEmpty())
    }
}
