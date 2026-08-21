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

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `std/decimal.az` - base ten, exactly, in ordinary library code.
 *
 * The point of the type is the arithmetic no binary float can do: `0.1 + 0.2`
 * is `0.3` and a price stays the price that was written.
 */
class DecimalStdlibTest {

    private fun run(body: String): String {
        val source = """
            import std.decimal
            import std.io

            func main() {
${body.trimIndent().prependIndent("                ")}
            }
        """.trimIndent()
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun textRoundTripsThroughTheDigitsThatWereWritten() {
        assertEquals(
            "19.99\n19.990\n-0.005\n0",
            run(
                """
                println(try decimalOf("19.99").text())
                println(try decimalOf("19.990").text())
                println(try decimalOf("-0.005").text())
                println(try decimalOf("0").text())
                """,
            ),
        )
    }

    @Test fun aTenthPlusTwoTenthsIsThreeTenths() {
        // The whole reason the type exists: as `Double` this is
        // 0.30000000000000004.
        assertEquals(
            "0.3",
            run(
                """
                fin sum = try decimalOf("0.1") + try decimalOf("0.2")
                println(sum.text())
                """,
            ),
        )
    }

    @Test fun scalesAlignOnAddAndAddOnMultiply() {
        assertEquals(
            "2.75\n0.0500\n6.00",
            run(
                """
                println((try decimalOf("2.5") + try decimalOf("0.25")).text())
                println((try decimalOf("0.25") * try decimalOf("0.20")).text())
                fin exact = try decimalOf("19.99") * try decimalOf("0.3")
                println((try exact.rescaled(2, .HalfEven)).text())
                """,
            ),
        )
    }

    @Test fun divisionTakesTheScaleItIsGiven() {
        assertEquals(
            "0.333\n0.3333333333333333",
            run(
                """
                println((try decimalOf("1").dividedBy(try decimalOf("3"), 3, .HalfEven)).text())
                println((try decimalOf("1") / try decimalOf("3")).text())
                """,
            ),
        )
    }

    @Test fun halfEvenSendsAnExactHalfToTheEvenNeighbour() {
        assertEquals(
            "0.2\n0.4\n0.3\n0.2",
            run(
                """
                println((try decimalOf("0.25").rescaled(1, .HalfEven)).text())
                println((try decimalOf("0.35").rescaled(1, .HalfEven)).text())
                println((try decimalOf("0.25").rescaled(1, .HalfUp)).text())
                println((try decimalOf("0.25").rescaled(1, .HalfDown)).text())
                """,
            ),
        )
    }

    @Test fun equalNumbersNeedNotBeIdenticalValues() {
        assertEquals(
            "0\n2\n1\n2.5",
            run(
                """
                fin loose = try decimalOf("1.5")
                fin tight = try decimalOf("1.50")
                println(try loose.compareTo(tight))
                println(tight.scale)
                println(tight.trimmed().scale)
                println(try decimalOf("2.500").trimmed().text())
                """,
            ),
        )
    }
}
