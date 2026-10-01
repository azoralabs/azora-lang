/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Pack predicates count expanded elements, including fixed-size spread sources. */
class PackSpreadConstraintTest {
    data class Case(val name: String, val expected: String, val source: String)

    companion object {
        private fun case(name: String, expected: String, clause: String, body: String, extra: String = "") = Case(
            name, expected, """
                import std.io
                func<...Types> count(values: ...Types): Int where $clause { return values.size }
                $extra
                func main() { $body }
            """.trimIndent(),
        )

        val cases = listOf(
            case("knownSize", "2", "Types.size == 2", "fin values = [10, 20]\nprintln(count(...values))"),
            case("emptyIsVacuouslyConforming", "0", "Types.size == 0 && Types is Hash",
                "fin values: Array<Double, 0> = []\nprintln(count(...values))"),
            case("mixedSegments", "6", "Types.size == 6",
                "fin first = [1, 2]\nfin last = [4, 5]\nprintln(count(0, ...first, 3, ...last))"),
            case("knownFunctionResult", "3", "Types.size == 3", "println(count(...make()))",
                "func make(): Array<Int, 3> { println(99) return [1, 2, 3] }").copy(expected = "99\n3"),
            case("groupedRange", "2", "(...Types).size in 2..3", "println(count(...[1, 2]))"),
            case("legacyLength", "2", "Types.length == 2", "println(count(...[1, 2]))"),
            case("unknownSizeForwarding", "2", "Types.size == 2", "println(forward([1, 2]))",
                "func forward(values: Array<Int>): Int { return count(...values) }"),
            case("elementConformance", "4", "Types is Hash && Types.size == 4",
                "fin bytes: Array<Byte, 2> = [7, 9]\nprintln(count(1, ...bytes, \"key\"))"),
            Case("fixedPrefix", "2", """
                import std.io
                func<Head, ...Tail> count(head: Head, values: ...Tail): Int
                    where Tail.size == 2 && Tail is Hash { return values.size }
                func main() { println(count(Double(1.5), ...[1, 2])) }
            """.trimIndent()),
            case("disjunction", "2", "Types is Hash || Types.size == 2",
                "fin values: Array<Double, 2> = [1.5, 2.5]\nprintln(count(...values))"),
            case("resizedSource", "3", "Types.size == 3",
                "var values = [1, 2]\nvalues.add(3)\nprintln(count(...values))"),
            case("frozenResizedSource", "3", "Types.size == 3",
                "var values = [1, 2]\nvalues.add(3)\nfin frozen = values\nprintln(count(...frozen))"),
            case("unprovenProducer", "3", "Types.size == 3", "println(count(...make()))",
                "func make(): Array<Int, 2> { var values = [1, 2]\nvalues.add(3)\nreturn values }"),
            case("emptyDirect", "0", "Types.size == 0 && Types is Hash", "println(count())"),
            Case("fixedHeadWitness", "true\n2", """
                import std.io
                func<Head, ...Tail> count(head: Head, values: ...Tail): Int
                    where Head is Hash && Tail is Hash && Tail.size == 2 {
                    println(head.hash == head.hash)
                    return values.size
                }
                func main() { println(count(7, ...[1, 2])) }
            """.trimIndent()),
            case("globalSource", "2", "Types.size == 2", "println(count(...values))",
                "fin values: Array<Int, 2> = [1, 2]"),
            case("immutableAlias", "2", "Types.size == 2",
                "fin values = [1, 2]\nfin alias = values\nprintln(count(...alias))"),
        )
    }

    private fun run(name: String) {
        val case = cases.single { it.name == name }
        for (optimized in listOf(false, true)) {
            val ir = PackSizeConstraintTest.compile(case.source, optimized)
            assertEquals(case.expected, IrInterpreter().interpret(ir).trim(), "$name; optimized=$optimized")
        }
    }

    private fun rejects(clause: String, body: String, fragment: String, extra: String = "") {
        val source = """
            func<...Types> count(values: ...Types): Int where $clause { return values.size }
            $extra
            func main() { $body }
        """.trimIndent()
        for (optimized in listOf(false, true)) {
            val failure = assertIs<CompilationResult.Failure>(Compiler().compile(source, release = optimized),
                "invalid call compiled; optimized=$optimized")
            assertTrue(failure.errors.any { "line " in it && "'where' clause" in it && fragment in it },
                failure.errors.toString())
        }
    }

    @Test fun knownSize() = run("knownSize")
    @Test fun emptyIsVacuouslyConforming() = run("emptyIsVacuouslyConforming")
    @Test fun mixedSegments() = run("mixedSegments")
    @Test fun knownFunctionResult() = run("knownFunctionResult")
    @Test fun groupedRange() = run("groupedRange")
    @Test fun legacyLength() = run("legacyLength")
    @Test fun unknownSizeForwarding() = run("unknownSizeForwarding")
    @Test fun elementConformance() = run("elementConformance")
    @Test fun fixedPrefix() = run("fixedPrefix")
    @Test fun disjunction() = run("disjunction")
    @Test fun resizedSource() = run("resizedSource")
    @Test fun frozenResizedSource() = run("frozenResizedSource")
    @Test fun unprovenProducer() = run("unprovenProducer")
    @Test fun emptyDirect() = run("emptyDirect")
    @Test fun fixedHeadWitness() = run("fixedHeadWitness")
    @Test fun globalSource() = run("globalSource")
    @Test fun immutableAlias() = run("immutableAlias")

    @Test fun aKnownShortSpreadIsRejected() = rejects("Types.size == 2", "count(...[1])", "1 vs 2")
    @Test fun aKnownLongSpreadIsRejected() = rejects("Types.size == 2", "count(...[1, 2, 3])", "3 vs 2")
    @Test fun anEmptySpreadCountsAsZero() = rejects("Types.size > 0",
        "fin values: Array<Int, 0> = []\ncount(...values)", "0 vs 0")
    @Test fun scalarsAndSpreadElementsBothCount() = rejects("Types.size == 2", "count(0, ...[1, 2])", "3 vs 2")
    @Test fun multipleSpreadSizesAreAdded() = rejects("Types.size == 3", "count(...[1, 2], ...[3, 4])", "4 vs 3")
    @Test fun aKnownResultSizeIsChecked() = rejects("Types.size == 2", "count(...make())", "3 vs 2",
        "func make(): Array<Int, 3> { return [1, 2, 3] }")
    @Test fun everyScalarMustConform() = rejects("Types is Hash", "count(1, Double(2.5))", "Double does not implement Hash")
    @Test fun everySpreadElementMustConform() = rejects("Types is Hash",
        "fin values: Array<Double, 2> = [1.5, 2.5]\ncount(...values)", "Double does not implement Hash")
    @Test fun anEmptyGoodSpreadDoesNotHideABadScalar() = rejects("Types is Hash",
        "fin values: Array<Int, 0> = []\ncount(...values, Double(2.5))", "Double does not implement Hash")
    @Test fun anUnknownSpreadDoesNotHideAKnownBadScalar() = rejects("Types is Hash",
        "forward([1, 2])", "Double does not implement Hash",
        "func forward(values: Array<Int>): Int { return count(...values, Double(2.5)) }")

    @Test fun shadowedBindingsRetainTheirOwnSizes() = rejects("Types.size == 2",
        "fin values = [1, 2]\nif true { fin values = [3]\ncount(...values) }", "1 vs 2")

    @Test fun aBadFixedHeadIsStillRejectedWithASpreadTail() {
        for (optimized in listOf(false, true)) {
            val result = Compiler().compile("""
                func<Head, ...Tail> count(head: Head, values: ...Tail): Int
                    where Head is Hash && Tail.size == 2 { return values.size }
                func main() { count(Double(1.5), ...[1, 2]) }
            """.trimIndent(), release = optimized)
            val failure = assertIs<CompilationResult.Failure>(result)
            assertTrue(failure.errors.any { "'where' clause" in it && "Double does not implement Hash" in it },
                failure.errors.toString())
        }
    }

    @Test fun immutableArraysCannotBeResized() {
        for (operation in listOf("add(3)", "insert(0, 3)", "remove(0)", "fill(3)")) {
            for (optimized in listOf(false, true)) {
                val result = Compiler().compile("func main() { fin values = [1, 2]\nvalues.$operation }", release = optimized)
                val failure = assertIs<CompilationResult.Failure>(result, "$operation; optimized=$optimized")
                assertTrue(failure.errors.any { "resize an array" in it && "values" in it }, failure.errors.toString())
            }
        }
    }

    private fun rejectsPackWitnessDispatch(bound: String, expression: String) {
        for (optimized in listOf(false, true)) {
            val result = Compiler().compile("""
                import std.io
                func<...Types> unsupported(values: ...Types): Int where Types is $bound {
                    $expression
                    return values.size
                }
                func main() { unsupported(1, 2) }
            """.trimIndent(), release = optimized)
            val failure = assertIs<CompilationResult.Failure>(result)
            assertTrue(failure.errors.any { "line " in it && "element-wise witness dispatch" in it && "not supported yet" in it },
                failure.errors.toString())
        }
    }

    @Test fun packIndexHashDispatchIsExplicitlyUnsupported() = rejectsPackWitnessDispatch("Hash", "println(values[0].hash)")
    @Test fun packLoopHashDispatchIsExplicitlyUnsupported() = rejectsPackWitnessDispatch("Hash", "for value in values { println(value.hash) }")
    @Test fun packEqualityDispatchIsExplicitlyUnsupported() = rejectsPackWitnessDispatch("Equal", "println(values[0] == values[1])")
    @Test fun packOrderDispatchIsExplicitlyUnsupported() = rejectsPackWitnessDispatch("Order", "println(values[0] < values[1])")
}
