/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Individually named roadmap checks, shared with the LLVM/WASM execution matrix. */
class VariadicSpreadTest {
    data class Case(val name: String, val expected: String, val source: String)

    companion object {
        private val encode = """
            func<...T> encode(values: ...T): Int {
                var result = 0
                for value in values { result = result * 10 + (value as Int) }
                return result
            }
        """.trimIndent()
        private val second = "func<T> second(...values: T): T { return values[1] }"
        private fun case(name: String, expected: String, body: String, declarations: String = encode) =
            Case(name, expected, "import std.io\n$declarations\nfunc main() {\n$body\n}")

        val cases = listOf(
            case("nonempty", "123", "fin values = [1, 2, 3]\nprintln(encode(...values))"),
            case("empty", "0", "fin values: Array<Int, 0> = []\nprintln(count(...values))",
                "func<...T> count(values: ...T): Int { return values.size }"),
            case("singleton", "7", "fin values = [7]\nprintln(encode(...values))"),
            case("mixed", "123456", """
                fin first = [1, 2]
                fin empty: Array<Int, 0> = []
                fin last = [4, 5]
                println(encode(0, ...first, 3, ...empty, ...last, 6))
            """.trimIndent()),
            case("fixedPrefix", "9123", "fin values = [1, 2, 3]\nprintln(prefixed(9, ...values))", """
                func<...T> prefixed(first: Int, values: ...T): Int {
                    var result = first
                    for value in values { result = result * 10 + (value as Int) }
                    return result
                }
            """.trimIndent()),
            case("homogeneousInference", "20", "fin values = [10, 20]\nprintln(second(...values))", second),
            case("doubleSlots", "2.5", "fin values: Array<Double, 2> = [1.5, 2.5]\nprintln(second<Double>(...values))", second),
            case("byteSlots", "9", "fin values: Array<Byte, 2> = [7, 9]\nprintln(second<Byte>(...values))", second),
            case("floatSlots", "2.5", "fin values: Array<Float, 2> = [1.5, 2.5]\nprintln(second<Float>(...values))", second),
            case("stringInference", "two", "fin values = [\"one\", \"two\"]\nprintln(second(...values))", second),
            case("plainGenericVariadic", "9\n2.5\n2.5\ntwo", """
                println(second<Byte>(Byte(7), Byte(9)))
                println(second<Double>(Double(1.5), Double(2.5)))
                println(second<Float>(Float(1.5), Float(2.5)))
                println(second("one", "two"))
            """.trimIndent(), second),
            case("evaluationOrder", "1\n2\n4\n5\n7\n1234567", """
                println(encode(mark(1), ...make(2), mark(4), ...make(5), mark(7)))
            """.trimIndent(), """
                $encode
                func mark(value: Int): Int { println(value) return value }
                func make(value: Int): Array<Int, 2> { println(value) return [value, value + 1] }
            """.trimIndent()),
            case("sourceIsCopied", "9\n1", """
                var values = [1, 2]
                println(touch(...values))
                println(values[0])
            """.trimIndent(), "func touch(...values: Int): Int { values[0] = 9 return values[0] }"),
            case("spreadSnapshot", "123\n9", "var values = [1, 2]\nprintln(encode(...values, change(values)))\nprintln(values[0])", """
                $encode
                func change(values: Array<Int, 2>!): Int { values[0] = 9 return 3 }
            """.trimIndent()),
            case("nestedForwarding", "123", "fin values = [1, 2, 3]\nprintln(forward(...values))", """
                $encode
                func<...T> forward(values: ...T): Int { return encode(...values) }
            """.trimIndent()),
            case("variadicLambda", "3", """
                fin length = <...T> { values: ...T -> values.size }
                fin values = [1, 2, 3]
                println(length(...values))
            """.trimIndent(), ""),
            case("indexBreakContinue", "134", """
                var result = 0
                for value in [1, 2, 3, 4, 5] with index {
                    if index == 1 { continue }
                    if index == 4 { break }
                    result = result * 10 + value
                }
                println(result)
            """.trimIndent(), ""),
            case("nestedIteration", "13142324", """
                var result = 0
                for outer in [1, 2] {
                    for inner in [3, 4] { result = result * 100 + outer * 10 + inner }
                }
                println(result)
            """.trimIndent(), ""),
            case("iterableEvaluatedOnce", "99\n123", """
                var result = 0
                for value in make() { result = result * 10 + value }
                println(result)
            """.trimIndent(), "func make(): Array<Int, 3> { println(99) return [1, 2, 3] }"),
            case("typedIteration", "7\n9\n1.5\n2.5\none\ntwo", """
                fin bytes: Array<Byte, 2> = [7, 9]
                fin floats: Array<Float, 2> = [1.5, 2.5]
                for byte in bytes { println(byte) }
                for real in floats { println(real) }
                for text in ["one", "two"] { println(text) }
            """.trimIndent(), ""),
        )
    }

    private fun run(name: String) {
        val case = cases.single { it.name == name }
        for (optimized in listOf(false, true)) {
            val result = Compiler().compile(case.source, release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            val ir = if (optimized) result.optimizedIr else result.ir
            assertEquals(case.expected, IrInterpreter().interpret(ir).trim(), "$name; optimized=$optimized")
        }
    }

    @Test fun nonempty() = run("nonempty")
    @Test fun empty() = run("empty")
    @Test fun singleton() = run("singleton")
    @Test fun mixed() = run("mixed")
    @Test fun fixedPrefix() = run("fixedPrefix")
    @Test fun homogeneousInference() = run("homogeneousInference")
    @Test fun doubleSlots() = run("doubleSlots")
    @Test fun byteSlots() = run("byteSlots")
    @Test fun floatSlots() = run("floatSlots")
    @Test fun stringInference() = run("stringInference")
    @Test fun plainGenericVariadic() = run("plainGenericVariadic")
    @Test fun evaluationOrder() = run("evaluationOrder")
    @Test fun sourceIsCopied() = run("sourceIsCopied")
    @Test fun spreadSnapshot() = run("spreadSnapshot")
    @Test fun nestedForwarding() = run("nestedForwarding")
    @Test fun variadicLambda() = run("variadicLambda")
    @Test fun indexBreakContinue() = run("indexBreakContinue")
    @Test fun nestedIteration() = run("nestedIteration")
    @Test fun iterableEvaluatedOnce() = run("iterableEvaluatedOnce")
    @Test fun typedIteration() = run("typedIteration")

    private fun rejects(source: String, fragment: String) {
        for (optimized in listOf(false, true)) {
            val result = Compiler().compile(source, release = optimized)
            assertIs<CompilationResult.Failure>(result, "invalid spread compiled; optimized=$optimized")
            assertTrue(result.errors.any { fragment in it }, result.errors.toString())
        }
    }

    @Test fun aScalarCannotBeSpread() = rejects("""
        func<...T> count(values: ...T): Int { return values.size }
        func main() { count(...42) }
    """.trimIndent(), "spread requires an array")

    @Test fun homogeneousSpreadsMustShareAnElementType() = rejects("""
        func<T> second(...values: T): T { return values[1] }
        func main() { second(...[1, 2], ...["one", "two"]) }
    """.trimIndent(), "must share a type")

    @Test fun aTypedVariadicRejectsAnIncompatibleSpread() = rejects("""
        func count(...values: Int): Int { return values.size }
        func main() { count(...["one", "two"]) }
    """.trimIndent(), "expected Int, got String")

    @Test fun aSpreadCannotOccupyAFixedVariadicSlotYet() = rejects("""
        func<...T> prefixed(first: Int, values: ...T): Int { return values.size }
        func main() { prefixed(...[1, 2, 3]) }
    """.trimIndent(), "spread cannot fill fixed parameters")
}
