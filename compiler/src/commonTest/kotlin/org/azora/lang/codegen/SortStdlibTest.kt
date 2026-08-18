package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `sort` and `sortBy` (VERSION_0_1_ROADMAP §4.4).
 *
 * `sort` was insertion sort - O(n²) - and `sortBy`'s key function was typed
 * `(T) -> T`, so it could not sort people by age. Both are checked here through
 * the ordinary injection path, because a standard-library module is not
 * compilable on its own.
 *
 * Stability is asserted rather than assumed: sorting by one key then another is
 * how multi-key ordering is expressed, and that only works if equal elements
 * keep the order the previous sort gave them.
 */
class SortStdlibTest {
    private fun compile(source: String): CompilationResult.Success {
        val result = Compiler().compile(source.trimIndent(), release = false)
        return assertIs(
            result,
            "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}",
        )
    }

    @Test
    fun sortOrdersAscendingAndLeavesTheInputAlone() {
        val result = compile(
            """
            import std.container.array
            import std.algorithm.sort
            import std.io

            func main() {
                fin values: Array<Int> = @arr[5, 3, 9, 1, 5, 8, 2]
                fin sorted: Array<Int> = sort<Int>(values)
                var line = ""
                for i in 0..<sorted.size {
                    line = line + "${'$'}{sorted[i]} "
                }
                println(line)
                println(values[0])
                println(isSorted<Int>(sorted))
            }
            """,
        )

        assertEquals("1 2 3 5 5 8 9 \n5\ntrue", IrInterpreter().interpret(result.ir).trim())
    }

    @Test
    fun sortDescendingReversesTheOrder() {
        val result = compile(
            """
            import std.container.array
            import std.algorithm.sort
            import std.io

            func main() {
                fin values: Array<Int> = @arr[4, 1, 7, 1, 9]
                fin down: Array<Int> = sortDescending<Int>(values)
                var line = ""
                for i in 0..<down.size {
                    line = line + "${'$'}{down[i]} "
                }
                println(line)
                println(isSortedDescending<Int>(down))
            }
            """,
        )

        assertEquals("9 7 4 1 1 \ntrue", IrInterpreter().interpret(result.ir).trim())
    }

    /**
     * More elements than one merge run, so the merge passes actually execute -
     * a sort that only ever insertion-sorts a short run would pass the small
     * cases and fail here.
     */
    @Test
    fun sortMergesRunsForInputsLongerThanOneRun() {
        val result = compile(
            """
            import std.container.array
            import std.algorithm.sort
            import std.io

            func main() {
                fin count = 50
                var values: Array<Int> = Array::fill<Int>(count)
                for i in 0..<count {
                    values[i] = (count - i) * 7 % 31
                }
                fin sorted: Array<Int> = sort<Int>(values)
                println(sorted.size)
                println(isSorted<Int>(sorted))
                println(sorted[0])
                println(sorted[count - 1])
            }
            """,
        )

        assertEquals("50\ntrue\n0\n30", IrInterpreter().interpret(result.ir).trim())
    }

    @Test
    fun sortHandlesEmptyAndSingleElementArrays() {
        val result = compile(
            """
            import std.container.array
            import std.algorithm.sort
            import std.io

            func main() {
                fin empty: Array<Int> = Array::fill<Int>(0)
                println(sort<Int>(empty).size)
                fin one: Array<Int> = @arr[42]
                fin sorted: Array<Int> = sort<Int>(one)
                println(sorted.size)
                println(sorted[0])
            }
            """,
        )

        assertEquals("0\n1\n42", IrInterpreter().interpret(result.ir).trim())
    }

    @Test
    fun sortIsStable() {
        val result = compile(
            """
            import std.container.array
            import std.algorithm
            import std.io

            pack Entry {
                var tag: String = ""
                var rank: Int = 0
            }

            func main() {
                var entries: Array<Entry> = Array::fill<Entry>(5)
                entries[0] = Entry("a", 2)
                entries[1] = Entry("b", 1)
                entries[2] = Entry("c", 2)
                entries[3] = Entry("d", 1)
                entries[4] = Entry("e", 2)

                fin byRank: Array<Entry> = sortBy<Entry, Int>(
                    entries, { e: Entry -> e.rank }
                )
                var line = ""
                for i in 0..<byRank.size {
                    line = line + byRank[i].tag
                }
                println(line)
            }
            """,
        )

        // Rank 1: b then d. Rank 2: a, c, e. Any other order means equal
        // elements were reordered.
        assertEquals("bdace", IrInterpreter().interpret(result.ir).trim())
    }

    /** The whole point of the fix: a key whose type is not the element type. */
    @Test
    fun sortByAcceptsAKeyOfAnotherType() {
        val result = compile(
            """
            import std.container.array
            import std.algorithm
            import std.io

            pack Person {
                var name: String = ""
                var age: Int = 0
            }

            func main() {
                var people: Array<Person> = Array::fill<Person>(3)
                people[0] = Person("ann", 30)
                people[1] = Person("bob", 20)
                people[2] = Person("cy", 25)

                fin byAge: Array<Person> = sortBy<Person, Int>(
                    people, { p: Person -> p.age }
                )
                var ages = ""
                for i in 0..<byAge.size {
                    ages = ages + "${'$'}{byAge[i].name} "
                }
                println(ages)

                fin byName: Array<Person> = sortBy<Person, String>(
                    people, { p: Person -> p.name }
                )
                var names = ""
                for i in 0..<byName.size {
                    names = names + byName[i].name + " "
                }
                println(names)
            }
            """,
        )

        assertEquals("bob cy ann \nann bob cy", IrInterpreter().interpret(result.ir).trim())
    }
}
