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
 * A `[…]` literal whose expected type is a pack calls that pack's variadic
 * constructor (`ctor .(...elements: T)`) with the elements as its arguments:
 * `fin b: Bag<Int> = [3, 4, 5]` is `Bag<Int>(3, 4, 5)`. Where a spec is
 * expected, the spec's own `ctor .(...elements: T): Self { … }` builds the
 * value. (These were `literal` factories until 2026-11-06.)
 */
class LiteralFactoryTest {
    companion object {
        /** A generic factory, reached through bindings, arguments and returns. */
        val bag = """
            import std.io
            pack Bag<T> {
                var count: Int = 0
                var first: T
                var last: T
            }
            impl Bag<T> {
                ctor .(...items: T) {
                    self.count = items.size
                    self.first = items[0]
                    self.last = items[items.size - 1]
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
            pack Digits { var value: Int = 0 }
            impl Digits {
                ctor .(...parts: Int) {
                    var value = 0
                    for i in 0..<parts.size { value = value * 10 + parts[i] }
                    self.value = value
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
                ctor .(...items: T): Self {
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
                var count: Int = 0
                var firstKey: K
                var lastValue: V
            }
            impl Ledger<K, V> {
                ctor .(...entries: (K, V)) {
                    self.count = entries.size
                    self.firstKey = entries[0].0
                    self.lastValue = entries[entries.size - 1].1
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
            pack Pairs { var sum: Int = 0 }
            impl Pairs {
                ctor .(...entries: (Int, Int)) {
                    var sum = 0
                    for i in 0..<entries.size { sum = sum * 100 + entries[i].0 * 10 + entries[i].1 }
                    self.sum = sum
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
                var count: Int = 0
                var first: T
            }
            impl Bag<T> {
                ctor .(...items: T) {
                    self.count = items.size
                    self.first = items[0]
                }
            }
            func main() {
                fin pairs: Bag<(Int, Int)> = [(1, 2), (3, 4)]
                println(pairs.count)
                println(pairs.first.1)
            }
        """.trimIndent()

        /** A factory's `where` clause is decided for the target's arguments. */
        val keyed = """
            import std.io
            spec Keyed { prop &.key: Int }
            pack Name { var key: Int }
            impl Keyed for Name { prop &.key: Int = self.key }
            pack Index<T> { var count: Int = 0 }
            impl Index<T> {
                ctor .(...items: T) where T is Keyed { self.count = items.size }
            }
            func<T> wrap(item: T): Index<T> { return [item] }
            func main() {
                fin names: Index<Name> = [Name(1), Name(2)]
                println(names.count)
                println(wrap(Name(3)).count)
            }
        """.trimIndent()

        /** A factory that can fail makes the literal fail as a call to it would. */
        val bounded = """
            import std.io
            error SizeError {
                TooMany
            }
            pack Small { var count: Int = 0 }
            impl Small {
                ctor .(...items: Int) ?! SizeError {
                    if items.size > 2 {
                        error SizeError.TooMany
                    }
                    self.count = items.size
                }
            }
            func main() {
                fin two: Small = [1, 2]
                println(two.count)
                fin none: Small = [1, 2, 3] catch Small()
                println(none.count)
            }
        """.trimIndent()

        /** Elements are arguments: `take` moves one into the factory. */
        val owned = """
            import std.io
            pack Res { var id: Int }
            pack Holder { var first: Res = Res(0) }
            impl Holder {
                ctor .(...items: Res) { self.first = items[0] }
            }
            func main() {
                fin res = Res(7)
                fin holder: Holder = [take res]
                println(holder.first.id)
            }
        """.trimIndent()

        fun compile(source: String, optimized: Boolean): IrProgram {
            val result = Compiler().compile(source, release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            return if (optimized) result.optimizedIr else result.ir
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
        "type 'Shape' has no variadic constructor for a '[…]' literal",
    )

    // A parent's constructor builds the parent; the child spec asks for more.
    @Test fun aChildSpecDoesNotInheritItsParentsFactory() = assertRejects(
        """
            spec Sized<T> {
                prop &.size: Int
                ctor .(...items: T): Self { return Counted<T>(items.size) }
            }
            spec Growable<T>: Sized<T> { func &.grow(): Int }
            pack Counted<T> { var count: Int }
            impl Sized<T> for Counted<T> { prop &.size: Int = self.count }
            func main() { fin grown: Growable<Int> = [1, 2] }
        """,
        "type 'Growable' has no variadic constructor for a '[…]' literal",
    )

    @Test fun aSpecsFactoryReturnsAnImplementation() {
        errors(
            """
                spec Shape {
                    func &.area(): Double
                    ctor .(...sides: Double): Self { return Plain(1) }
                }
                pack Plain { var value: Int }
                func main() { fin shape: Shape = [1.0] }
            """,
        )
    }

    @Test fun aSatisfiedWhereClauseAdmitsTheLiteral() {
        for (optimized in listOf(false, true)) {
            assertEquals("2\n1", IrInterpreter().interpret(compile(keyed, optimized)).trim())
        }
    }

    @Test fun aViolatedWhereClauseRejectsTheLiteral() = assertRejects(
        keyed.replace("fin names: Index<Name> = [Name(1), Name(2)]", "fin names: Index<Int> = [1, 2]"),
        "a 'Index' literal does not satisfy its constructor's 'where' clause",
    )

    @Test fun aFailingFactoryFailsTheLiteralLikeACall() {
        for (optimized in listOf(false, true)) {
            assertEquals("2\n0", IrInterpreter().interpret(compile(bounded, optimized)).trim())
        }
        val caught = """
            import std.io
            error SizeError {
                TooMany
            }
            pack Small { var count: Int = 0 }
            impl Small {
                ctor .(...items: Int) ?! SizeError {
                    error SizeError.TooMany
                }
            }
            func main() {
                try {
                    fin small: Small = [1]
                    println(small.count)
                } catch {
                    e -> println(e)
                }
            }
        """.trimIndent()
        assertEquals("TooMany", IrInterpreter().interpret(compile(caught, optimized = false)).trim())
    }

    @Test fun aTakenElementMovesLikeATakenArgument() {
        for (optimized in listOf(false, true)) {
            assertEquals("7", IrInterpreter().interpret(compile(owned, optimized)).trim())
        }
        assertRejects(
            owned.replace("println(holder.first.id)", "println(res.id)"),
            "use of taken value 'res'",
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
        "type 'Plain' has no variadic constructor for a '[…]' literal",
    )

    @Test fun elementsMustHaveTheFactorysElementType() = assertRejects(
        """
            pack Digits { var value: Int = 0 }
            impl Digits { ctor .(...parts: Int) { self.value = parts.size } }
            func main() { fin number: Digits = ["one"] }
        """,
        "an element of a 'Digits' literal must have type Int",
    )

    @Test fun aSpreadIsNotPassedToAFactory() = assertRejects(
        """
            pack Digits { var value: Int = 0 }
            impl Digits { ctor .(...parts: Int) { self.value = parts.size } }
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
            pack Digits { var value: Int = 0 }
            impl Digits { ctor .(...parts: Int): Other { return Other(parts.size) } }
            func main() { fin number: Digits = [1] }
        """,
        "the variadic constructor of 'Digits' builds Other",
    )

    /** Only a constructor whose one parameter is variadic is what a `[…]` calls. */
    @Test fun aConstructorWithoutAVariadicParameterIsNotALiteralTarget() {
        assertRejects(
            """
                pack Digits { var value: Int = 0 }
                impl Digits { ctor .(parts: Int) { self.value = parts } }
                func main() { fin number: Digits = [1] }
            """,
            "type 'Digits' has no variadic constructor for a '[…]' literal",
        )
        assertRejects(
            """
                spec Shape {
                    func &.area(): Double
                    ctor .(side: Double): Self { return Plain(1) }
                }
                pack Plain { var value: Int }
            """,
            "a spec's constructor takes one variadic parameter",
        )
    }

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

    /** A constructor of entries takes `[k: v]`; given `[a, b]` its elements must be entries too. */
    @Test fun eachShapeNeedsItsOwnConstructor() {
        assertRejects(
            """
                pack Pairs { var sum: Int = 0 }
                impl Pairs { ctor .(...entries: (Int, Int)) { self.sum = entries.size } }
                func main() { fin pairs: Pairs = [1, 2] }
            """,
            "an element of a 'Pairs' literal must have type (Int, Int)",
        )
        assertRejects(
            """
                pack Digits { var value: Int = 0 }
                impl Digits { ctor .(...parts: Int) { self.value = parts.size } }
                func main() { fin digits: Digits = [1: 2] }
            """,
            "type 'Digits' has no variadic constructor for a '[k: v]' literal",
        )
    }

    @Test fun entriesMustHaveTheFactorysKeyAndValueTypes() {
        val pairs = """
            pack Pairs { var sum: Int = 0 }
            impl Pairs { ctor .(...entries: (Int, Int)) { self.sum = entries.size } }
        """
        assertRejects("$pairs\nfunc main() { fin p: Pairs = [\"one\": 1] }", "a key of a 'Pairs' literal must have type Int")
        assertRejects("$pairs\nfunc main() { fin p: Pairs = [1: \"one\"] }", "a value of a 'Pairs' literal must have type Int")
    }

    /** Two variadic constructors would be two answers to one `[…]`. */
    @Test fun aTypeHasOneVariadicConstructor() {
        val found = errors(
            """
                pack Digits { var value: Int = 0 }
                impl Digits { ctor .(...parts: Int) { self.value = 1 } }
                impl Digits { ctor .(...parts: Int) { self.value = 2 } }
                func main() { fin d: Digits = [1] }
            """,
        )
        assertTrue(found.any { "more than one" in it || "already" in it || "duplicate" in it }, found.toString())
    }

    /**
     * `literal` is an ordinary name again (it was a keyword until 2026-11-06),
     * and the member a literal calls has no name a program can write.
     */
    @Test fun literalIsAnOrdinaryNameAndTheTargetHasNone() {
        val result = Compiler().compile(
            """
                import std.io
                func main() {
                    fin literal = 1
                    println(literal)
                }
            """.trimIndent(),
        )
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertEquals("1", IrInterpreter().interpret(result.ir).trim())
        errors(
            """
                pack Digits { var value: Int = 0 }
                impl Digits { ctor .(...parts: Int) { self.value = parts.size } }
                func main() { fin number = Digits::literal(1, 2) }
            """,
        )
    }
}
