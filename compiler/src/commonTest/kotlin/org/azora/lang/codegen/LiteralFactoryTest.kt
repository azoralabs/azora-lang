/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A `[…]` literal whose expected type is a pack is built by that pack's
 * `literal` factory: selected from the target type, fed the elements as its
 * variadic arguments, and never reachable by name.
 */
class LiteralFactoryTest {
    companion object {
        /** A generic factory, reached through bindings, arguments and returns. */
        val bag = """
            import std.io
            pack Bag<T> {
                var count: Int
                var first: T
                var last: T
            }
            impl Bag<T> {
                literal [...items: T]: Bag<T> {
                    return Bag<T>(items.size, items[0], items[items.size - 1])
                }
            }
            func width(bag: Bag<Int>): Int { return bag.last - bag.first }
            func halves(): Bag<Double> { return [0.5, 1.5, 2.5] }
            func main() {
                fin ints: Bag<Int> = [3, 4, 5]
                println(ints.count)
                println(ints.first + ints.last)
                println(width([10, 20, 35]))
                println(halves().last)
            }
        """.trimIndent()

        /** Elements run once each, left to right, and reach the factory in that order. */
        val digits = """
            import std.io
            pack Digits { var value: Int }
            impl Digits {
                literal [...parts: Int]: Digits {
                    var value = 0
                    for i in 0..<parts.size { value = value * 10 + parts[i] }
                    return Digits(value)
                }
            }
            pack Log { var digits: Int }
            func next(log: Log!, digit: Int): Int {
                log.digits = log.digits * 10 + digit
                return digit
            }
            func main() {
                var log = Log(0)
                fin number: Digits = [next(log, 1), next(log, 2), next(log, 3)]
                println(number.value)
                println(log.digits)
                fin none: Digits = []
                println(none.value)
            }
        """.trimIndent()

        /**
         * A spec's factory designates the concrete value built where the spec
         * is expected, and members read through the spec take its arguments.
         */
        val stack = """
            import std.io
            spec Stack<T> {
                prop &.size: Int
                func &.top(): T
                func &.choose(fallback: T, useFallback: Bool): T
                literal [...items: T]: Self {
                    return ArrayStack<T>(items.size, items[items.size - 1])
                }
            }
            pack ArrayStack<T> {
                var count: Int
                var last: T
            }
            impl Stack<T> for ArrayStack<T> {
                prop &.size: Int = self.count
                func &.top(): T { return self.last }
                func &.choose(fallback: T, useFallback: Bool): T {
                    return if useFallback then fallback else self.last
                }
            }
            func depth(stack: Stack<Int>): Int { return stack.size }
            func main() {
                fin ints: Stack<Int> = [1, 2, 3]
                println(ints.size)
                println(ints.top())
                fin halves: Stack<Double> = [0.5, 2.5]
                println(halves.top())
                println(halves.choose(1.25, true))
                println(depth([4, 5]))
            }
        """.trimIndent()

        /** An associative factory receives one `(key, value)` entry per pair. */
        val ledger = """
            import std.io
            pack Ledger<K, V> {
                var count: Int
                var firstKey: K
                var lastValue: V
            }
            impl Ledger<K, V> {
                literal [...entries: (K, V)]: Ledger<K, V> {
                    return Ledger<K, V>(entries.size, entries[0].0, entries[entries.size - 1].1)
                }
            }
            func total(ledger: Ledger<Int, Int>): Int { return ledger.firstKey + ledger.lastValue }
            func main() {
                fin prices: Ledger<String, Double> = ["tea": 1.5, "cake": 3.25]
                println(prices.count)
                println(prices.firstKey)
                println(prices.lastValue)
                println(total([10: 1, 20: 5]))
            }
        """.trimIndent()

        /** Each key runs before its value, and each entry before the next. */
        val pairs = """
            import std.io
            pack Log { var digits: Int }
            func next(log: Log!, digit: Int): Int {
                log.digits = log.digits * 10 + digit
                return digit
            }
            pack Pairs { var sum: Int }
            impl Pairs {
                literal [...entries: (Int, Int)]: Pairs {
                    var sum = 0
                    for i in 0..<entries.size { sum = sum * 100 + entries[i].0 * 10 + entries[i].1 }
                    return Pairs(sum)
                }
            }
            func main() {
                var log = Log(0)
                fin pairs: Pairs = [next(log, 1): next(log, 2), next(log, 3): next(log, 4)]
                println(pairs.sum)
                println(log.digits)
                fin none: Pairs = [:]
                println(none.sum)
            }
        """.trimIndent()

        /** A factory whose element is a type parameter is a sequence even of pairs. */
        val pairedBag = """
            import std.io
            pack Bag<T> {
                var count: Int
                var first: T
            }
            impl Bag<T> {
                literal [...items: T]: Bag<T> { return Bag<T>(items.size, items[0]) }
            }
            func main() {
                fin pairs: Bag<(Int, Int)> = [(1, 2), (3, 4)]
                println(pairs.count)
                println(pairs.first.1)
            }
        """.trimIndent()

        fun compile(source: String, optimized: Boolean): IrProgram {
            val result = Compiler().compile(source, release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            return result.ir
        }
    }

    private fun errors(source: String): List<String> {
        val result = Compiler().compile(source.trimIndent())
        assertIs<CompilationResult.Failure>(result, "expected rejection:\n$source")
        return result.errors
    }

    private fun assertRejects(source: String, message: String) {
        val errors = errors(source)
        assertTrue(errors.any { message in it }, "expected '$message' in $errors")
    }

    @Test fun aGenericFactoryBuildsItsTargetWhereverTheTypeIsExpected() {
        for (optimized in listOf(false, true)) {
            assertEquals("3\n8\n25\n2.5", IrInterpreter().interpret(compile(bag, optimized)).trim())
        }
    }

    @Test fun aSpecsFactoryBuildsTheValueItDesignates() {
        for (optimized in listOf(false, true)) {
            assertEquals("3\n3\n2.5\n1.25\n2", IrInterpreter().interpret(compile(stack, optimized)).trim())
        }
    }

    @Test fun aSpecWithoutAFactoryIsNotBuiltFromALiteral() = assertRejects(
        """
            spec Shape { func &.area(): Double }
            func main() { fin shape: Shape = [1.0] }
        """,
        "type 'Shape' does not define a sequence literal factory",
    )

    // A parent's factory builds the parent; the child spec asks for more.
    @Test fun aChildSpecDoesNotInheritItsParentsFactory() = assertRejects(
        """
            spec Sized<T> {
                prop &.size: Int
                literal [...items: T]: Self { return Counted<T>(items.size) }
            }
            spec Growable<T>: Sized<T> { func &.grow(): Int }
            pack Counted<T> { var count: Int }
            impl Sized<T> for Counted<T> { prop &.size: Int = self.count }
            func main() { fin grown: Growable<Int> = [1, 2] }
        """,
        "type 'Growable' does not define a sequence literal factory",
    )

    @Test fun aSpecsFactoryReturnsAnImplementation() {
        errors(
            """
                spec Shape {
                    func &.area(): Double
                    literal [...sides: Double]: Self { return Plain(1) }
                }
                pack Plain { var value: Int }
                func main() { fin shape: Shape = [1.0] }
            """,
        )
    }

    @Test fun elementsAreEvaluatedOnceInOrder() {
        for (optimized in listOf(false, true)) {
            assertEquals("123\n123\n0", IrInterpreter().interpret(compile(digits, optimized)).trim())
        }
    }

    @Test fun aTypeWithoutAFactoryIsNotBuiltFromALiteral() = assertRejects(
        """
            pack Plain { var value: Int }
            func main() { fin plain: Plain = [1] }
        """,
        "type 'Plain' does not define a sequence literal factory",
    )

    @Test fun elementsMustHaveTheFactorysElementType() = assertRejects(
        """
            pack Digits { var value: Int }
            impl Digits { literal [...parts: Int]: Digits { return Digits(parts.size) } }
            func main() { fin number: Digits = ["one"] }
        """,
        "an element of a 'Digits' literal must have type Int",
    )

    @Test fun aSpreadIsNotPassedToAFactory() = assertRejects(
        """
            pack Digits { var value: Int }
            impl Digits { literal [...parts: Int]: Digits { return Digits(parts.size) } }
            func main() {
                fin parts = [1, 2]
                fin number: Digits = [...parts]
            }
        """,
        "a spread in a collection literal",
    )

    @Test fun aFactoryBuildsItsOwnTarget() = assertRejects(
        """
            pack Other { var value: Int }
            pack Digits { var value: Int }
            impl Digits { literal [...parts: Int]: Other { return Other(parts.size) } }
            func main() { fin number: Digits = [1] }
        """,
        "the literal factory of 'Digits' builds Other",
    )

    @Test fun aFactoryTakesOneVariadicParameter() = assertRejects(
        """
            pack Digits { var value: Int }
            impl Digits { literal [parts: Int]: Digits { return Digits(parts) } }
        """,
        "a literal factory takes one variadic parameter",
    )

    @Test fun anAssociativeFactoryBuildsItsTargetFromEntries() {
        for (optimized in listOf(false, true)) {
            assertEquals("2\ntea\n3.25\n15", IrInterpreter().interpret(compile(ledger, optimized)).trim())
        }
    }

    @Test fun keysRunBeforeValuesAndEntriesInOrder() {
        for (optimized in listOf(false, true)) {
            assertEquals("1234\n1234\n0", IrInterpreter().interpret(compile(pairs, optimized)).trim())
        }
    }

    @Test fun aSequenceOfPairsIsStillASequence() {
        for (optimized in listOf(false, true)) {
            assertEquals("2\n2", IrInterpreter().interpret(compile(pairedBag, optimized)).trim())
        }
    }

    @Test fun eachShapeNeedsItsOwnFactory() {
        assertRejects(
            """
                pack Pairs { var sum: Int }
                impl Pairs { literal [...entries: (Int, Int)]: Pairs { return Pairs(entries.size) } }
                func main() { fin pairs: Pairs = [1, 2] }
            """,
            "type 'Pairs' does not define a sequence literal factory",
        )
        assertRejects(
            """
                pack Digits { var value: Int }
                impl Digits { literal [...parts: Int]: Digits { return Digits(parts.size) } }
                func main() { fin digits: Digits = [1: 2] }
            """,
            "type 'Digits' does not define an associative literal factory",
        )
    }

    @Test fun entriesMustHaveTheFactorysKeyAndValueTypes() {
        val pairs = """
            pack Pairs { var sum: Int }
            impl Pairs { literal [...entries: (Int, Int)]: Pairs { return Pairs(entries.size) } }
        """
        assertRejects("$pairs\nfunc main() { fin p: Pairs = [\"one\": 1] }", "a key of a 'Pairs' literal must have type Int")
        assertRejects("$pairs\nfunc main() { fin p: Pairs = [1: \"one\"] }", "a value of a 'Pairs' literal must have type Int")
    }

    @Test fun aTypeHasOneAssociativeFactory() = assertRejects(
        """
            pack Pairs { var sum: Int }
            impl Pairs { literal [...entries: (Int, Int)]: Pairs { return Pairs(1) } }
            impl Pairs { literal [...entries: (Int, Int)]: Pairs { return Pairs(2) } }
        """,
        "type 'Pairs' declares more than one associative literal factory",
    )

    @Test fun aTypeHasOneSequenceFactory() = assertRejects(
        """
            pack Digits { var value: Int }
            impl Digits { literal [...parts: Int]: Digits { return Digits(1) } }
            impl Digits { literal [...parts: Int]: Digits { return Digits(2) } }
        """,
        "type 'Digits' declares more than one sequence literal factory",
    )

    @Test fun aFactoryIsDeclaredInItsTargetsImpl() {
        assertRejects(
            """
                literal [...parts: Int]: Int { return 1 }
            """,
            "declare 'literal [...elements: T]: Type { … }' inside 'impl Type { … }'",
        )
        assertRejects(
            """
                pack Digits {
                    var value: Int
                    literal [...parts: Int]: Digits { return Digits(1) }
                }
            """,
            "declare 'literal [...elements: T]: Type { … }' inside 'impl Type { … }'",
        )
    }

    @Test fun literalIsReservedAndTheFactoryHasNoCallableName() {
        errors(
            """
                func main() { fin literal = 1 }
            """,
        )
        errors(
            """
                pack Digits { var value: Int }
                impl Digits { literal [...parts: Int]: Digits { return Digits(parts.size) } }
                func main() { fin number = Digits::literal(1, 2) }
            """,
        )
        errors(
            """
                pack Pairs { var sum: Int }
                impl Pairs { literal [...entries: (Int, Int)]: Pairs { return Pairs(entries.size) } }
                func main() { fin pairs = Pairs::literal_entries((1, 2)) }
            """,
        )
    }
}
