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

    @Test fun associativeFactoriesAreNotYetAccepted() = assertRejects(
        """
            pack Pairs { var count: Int }
            impl Pairs { literal [...entries: (String, Int)]: Pairs { return Pairs(entries.size) } }
        """,
        "associative literal factories",
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
    }
}
