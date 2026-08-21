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

package org.azora.lang.codegen

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.ir.IrGenerator
import org.azora.lang.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `Cent` and `UCent` limits, as `std/primitive.az` writes them.
 *
 * A 128-bit literal has no `Long` to live in, so its digits travel with it and
 * are what LLVM is handed. The declarations here are the ones in the standard
 * library, character for character.
 */
class WideLiteralIrTest {

    private fun llvm(source: String): String {
        val program = Parser(Lexer(source).tokenize()).parse()
        val semantic = SemanticPipeline().analyze(program)
        assertTrue(semantic.errors.none { !it.startsWith("warning:") }, semantic.errors.toString())
        return LlvmCodegen().generate(IrGenerator(semantic.symbolTable).generate(semantic.program))
    }

    @Test fun theCentLimitsSurviveToLlvm() {
        // An `inline fin` is folded where it is read, so the limits are read
        // here - which is what `Cent.maxValue` does at every use site.
        val out = llvm(
            """
            bridge pack Cent(__int)

            func main() {
                var smallest: Cent = -170_141_183_460_469_231_731_687_303_715_884_105_728
                var largest: Cent = 170_141_183_460_469_231_731_687_303_715_884_105_727
            }
            """.trimIndent(),
        )

        assertTrue("-170141183460469231731687303715884105728" in out, out)
        assertTrue("170141183460469231731687303715884105727" in out, out)
        assertTrue("i128" in out, out)
    }

    @Test fun theUCentMaximumSurvivesToLlvm() {
        val out = llvm(
            """
            bridge pack UCent(__int)

            func main() {
                var largest: UCent = 340_282_366_920_938_463_463_374_607_431_768_211_455
            }
            """.trimIndent(),
        )

        assertTrue("340282366920938463463374607431768211455" in out, out)
    }

    @Test fun theLimitsAsTheStandardLibraryWritesThem() {
        // The declarations of `std/primitive.az`, character for character.
        val program = Parser(
            Lexer(
                """
                bridge pack Cent(__int)
                bridge pack UCent(__int)

                impl Cent {
                    inline fin minValue: Cent = -170_141_183_460_469_231_731_687_303_715_884_105_728
                    inline fin maxValue: Cent = 170_141_183_460_469_231_731_687_303_715_884_105_727
                }

                impl UCent {
                    inline fin minValue: UCent = 0
                    inline fin maxValue: UCent = 340_282_366_920_938_463_463_374_607_431_768_211_455
                }

                func main() {}
                """.trimIndent(),
            ).tokenize(),
        ).parse()

        val semantic = SemanticPipeline().analyze(program)
        assertTrue(semantic.errors.none { !it.startsWith("warning:") }, semantic.errors.toString())
    }

    // -- `Quad`, whose significand is wider than the `Double` it parsed into --

    @Test fun aQuadLimitReachesLlvmAsBinary128() {
        // Past `Double`'s range the parsed value is already infinity, so this
        // is only right if the digits travelled with the literal.
        val out = llvm(
            """
            bridge pack Quad(__float)

            func main() {
                var largest: Quad = 1.18973149535723176508575932662800702E4932
                var smallest: Quad = 3.36210314311209350626267781732175260E-4932
            }
            """.trimIndent(),
        )

        assertTrue("0xLFFFFFFFFFFFFFFFF7FFEFFFFFFFFFFFF" in out, out)
        assertTrue("0xL00000000000000000001000000000000" in out, out)
        assertTrue("0xL00000000000000007FFF000000000000" !in out, "a limit became infinity:\n$out")
    }

    @Test fun aQuadKeepsBitsADoubleWouldHaveDropped() {
        // `0.1` is 53 correct bits through a `Double` and 113 through its own
        // digits; the tail is what tells the two apart.
        val out = llvm(
            """
            bridge pack Quad(__float)

            func main() {
                var tenth: Quad = 0.1
            }
            """.trimIndent(),
        )

        assertTrue("0xL999999999999999A3FFB999999999999" in out, out)
    }
}
