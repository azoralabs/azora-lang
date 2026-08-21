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

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Broad, individually reported execution coverage for scalar LLVM lowering.
 * Every row is checked against both raw and optimized IR.
 */
@RunWith(Parameterized::class)
class LlvmTypeOperationMatrixExecTest(
    private val caseName: String,
    private val expected: String,
    private val source: String,
) {

    @Test
    fun executeInDebugAndOptimizedModes() {
        if (!LlvmExec.available) return
        assertEquals(expected, LlvmExec.run(source), "$caseName (debug IR)")
        assertEquals(expected, LlvmExec.run(source, optimized = true), "$caseName (optimized IR)")
    }

    companion object {
        /**
         * A numeric type, and how a literal of it is written.
         *
         * There are no width suffixes: a literal is an `IntLiteral` or a
         * `FloatLiteral`, and naming the type is what reads it at that width -
         * `Byte(10)`. `Int` and `Double` need no name at all, being what a bare
         * literal is read as.
         */
        private data class NumericType(val name: String, val bare: Boolean = false) {
            /** [value] written as a literal of this type. */
            fun literal(value: String): String = if (bare) value else "$name($value)"
        }
        private data class Case(val name: String, val expected: String, val source: String)

        private val integerTypes = listOf(
            NumericType("Int", bare = true),
            NumericType("Byte"),
            NumericType("UByte"),
            NumericType("Short"),
            NumericType("UShort"),
            NumericType("UInt"),
            NumericType("Long"),
            NumericType("ULong"),
            NumericType("Cent"),
            NumericType("UCent"),
        )

        private val floatTypes = listOf(
            NumericType("Double", bare = true),
            NumericType("Float"),
            NumericType("Quad"),
        )

        private fun program(body: String): String = "import std.io\nfunc main() {\n$body\n}"

        private fun expressionCase(name: String, expected: String, expression: String): Case =
            Case(name, expected, program("println($expression)"))

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val cases = mutableListOf<Case>()

            val arithmetic = listOf(
                Triple("add", "+", "26"),
                Triple("subtract", "-", "14"),
                Triple("multiply", "*", "120"),
                Triple("divide", "/", "3"),
                Triple("modulo", "%", "2"),
            )
            for (type in integerTypes) {
                for ((name, operator, expected) in arithmetic) {
                    cases += expressionCase(
                        "${type.name}_$name",
                        expected,
                        "${type.literal("20")} $operator ${type.literal("6")}",
                    )
                }
            }

            val comparisons = listOf(
                Triple("equal", "==", "false"),
                Triple("not_equal", "!=", "true"),
                Triple("less", "<", "false"),
                Triple("less_equal", "<=", "false"),
                Triple("greater", ">", "true"),
                Triple("greater_equal", ">=", "true"),
            )
            for (type in integerTypes) {
                for ((name, operator, expected) in comparisons) {
                    cases += expressionCase(
                        "${type.name}_$name",
                        expected,
                        "${type.literal("20")} $operator ${type.literal("6")}",
                    )
                }
            }

            val bitwise = listOf(
                Triple("bit_and", "&", "2"),
                Triple("bit_or", "|", "14"),
                Triple("bit_xor", "^", "12"),
                Triple("shift_left", "<<", "40"),
                Triple("shift_right", ">>", "2"),
            )
            for (type in integerTypes) {
                for ((name, operator, expected) in bitwise) {
                    val right = if (operator == "<<" || operator == ">>") type.literal("2") else type.literal("6")
                    cases += expressionCase(
                        "${type.name}_$name",
                        expected,
                        "${type.literal("10")} $operator $right",
                    )
                }
            }

            // A `Double` prints as a `Double`: an integral result keeps its `.0`.
            val floatArithmetic = listOf(
                Triple("add", "+", "10.0"),
                Triple("subtract", "-", "5.0"),
                Triple("multiply", "*", "18.75"),
                Triple("divide", "/", "3.0"),
            )
            for (type in floatTypes) {
                for ((name, operator, expected) in floatArithmetic) {
                    cases += expressionCase(
                        "${type.name}_$name",
                        expected,
                        "${type.literal("7.5")} $operator ${type.literal("2.5")}",
                    )
                }
                for ((name, operator, expected) in comparisons) {
                    cases += expressionCase(
                        "${type.name}_$name",
                        expected,
                        "${type.literal("7.5")} $operator ${type.literal("2.5")}",
                    )
                }
            }

            val casts = listOf(
                Triple("Byte_to_Int", "Byte(42) as Int", "42"),
                Triple("UByte_to_Int", "UByte(200) as Int", "200"),
                Triple("Short_to_Long", "Short(32000) as Long", "32000"),
                Triple("UShort_to_Long", "UShort(60000) as Long", "60000"),
                Triple("Int_to_Long", "123 as Long", "123"),
                Triple("UInt_to_ULong", "UInt(123) as ULong", "123"),
                Triple("Long_to_Int", "Long(123) as Int", "123"),
                Triple("ULong_to_UInt", "ULong(123) as UInt", "123"),
                Triple("Int_to_Byte", "127 as Byte", "127"),
                Triple("Int_to_UByte", "255 as UByte", "255"),
                Triple("Int_to_Short", "32000 as Short", "32000"),
                Triple("Int_to_UShort", "60000 as UShort", "60000"),
                Triple("Int_to_UInt", "123 as UInt", "123"),
                Triple("Long_to_ULong", "Long(123) as ULong", "123"),
                Triple("Int_to_Cent", "123 as Cent", "123"),
                Triple("UInt_to_UCent", "UInt(123) as UCent", "123"),
                Triple("Cent_to_Int", "Cent(123) as Int", "123"),
                Triple("UCent_to_UInt", "UCent(123) as UInt", "123"),
                Triple("Int_to_Real", "42 as Double", "42.0"),
                Triple("UInt_to_Real", "UInt(42) as Double", "42.0"),
                Triple("Int_to_Float", "42 as Float", "42.0"),
                Triple("Int_to_Decimal", "42 as Quad", "42.0"),
                Triple("Real_to_Int", "3.75 as Int", "3"),
                Triple("Real_to_UInt", "3.75 as UInt", "3"),
                Triple("Float_to_Int", "Float(3.75) as Int", "3"),
                Triple("Decimal_to_Int", "Quad(3.75) as Int", "3"),
                Triple("Float_to_Real", "Float(3.5) as Double", "3.5"),
                Triple("Real_to_Float", "3.5 as Float", "3.5"),
                Triple("Real_to_Decimal", "3.5 as Quad", "3.5"),
                Triple("Decimal_to_Real", "Quad(3.5) as Double", "3.5"),
                Triple("Int_to_Char", "65 as Char", "A"),
                Triple("Char_to_Int", "'A' as Int", "65"),
            )
            for ((name, expression, expected) in casts) {
                cases += expressionCase("cast_$name", expected, expression)
            }

            for (type in integerTypes) {
                val value = type.literal("42")
                cases += Case(
                    "interpolate_${type.name}",
                    "value=42",
                    program("let value: ${type.name} = $value\nprintln(\"value=${'$'}value\")"),
                )
            }
            for (type in floatTypes) {
                cases += Case(
                    "interpolate_${type.name}",
                    "value=3.5",
                    program("let value: ${type.name} = ${type.literal("3.5")}\nprintln(\"value=${'$'}value\")"),
                )
            }

            cases += Case("interpolate_Bool", "value=true", program("let value = true\nprintln(\"value=${'$'}value\")"))
            cases += Case("interpolate_Char", "value=Z", program("let value = 'Z'\nprintln(\"value=${'$'}value\")"))
            cases += Case("interpolate_expression", "sum=42", program("println(\"sum=${'$'}{20 + 22}\")"))
            cases += Case("interpolate_multiple", "a=7,b=6,p=42", program("let a = 7\nlet b = 6\nprintln(\"a=${'$'}a,b=${'$'}b,p=${'$'}{a * b}\")"))

            cases += expressionCase("string_concat_literals", "hello world", "\"hello \" + \"world\"")
            cases += expressionCase("string_concat_variables", "azora", "\"azo\" + \"ra\"")
            cases += expressionCase("string_repeat_right", "hahaha", "\"ha\" * 3")
            cases += expressionCase("string_repeat_left", "hahaha", "3 * \"ha\"")
            cases += expressionCase("string_equal", "true", "\"same\" == \"same\"")
            cases += expressionCase("string_not_equal", "true", "\"left\" != \"right\"")
            cases += Case("string_variable_concat", "ab", program("let prefix = \"a\"\nprintln(prefix + \"b\")"))
            cases += expressionCase("string_concat_number_interpolation", "n=42!", "\"n=${'$'}{40 + 2}\" + \"!\"")
            cases += expressionCase("bool_short_circuit_and", "false", "false && (1 / 0 == 0)")
            cases += expressionCase("bool_short_circuit_or", "true", "true || (1 / 0 == 0)")

            assertTrue(cases.size > 100, "LLVM matrix must retain more than 100 cases")
            return cases.map { arrayOf<Any>(it.name, it.expected, it.source) }
        }
    }
}
