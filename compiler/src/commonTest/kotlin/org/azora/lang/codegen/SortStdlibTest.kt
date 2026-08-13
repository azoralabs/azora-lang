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
                fin values: std::Array<std::Int> = @std::arr[5, 3, 9, 1, 5, 8, 2]
                fin sorted: std::Array<std::Int> = std::sort<std::Int>(values)
                var line = ""
                for i in 0..<sorted.size {
                    line = line + "${'$'}{sorted[i]} "
                }
                std::println(line)
                std::println(values[0])
                std::println(std::isSorted<std::Int>(sorted))
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
                fin values: std::Array<std::Int> = @std::arr[4, 1, 7, 1, 9]
                fin down: std::Array<std::Int> = std::sortDescending<std::Int>(values)
                var line = ""
                for i in 0..<down.size {
                    line = line + "${'$'}{down[i]} "
                }
                std::println(line)
                std::println(std::isSortedDescending<std::Int>(down))
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
                var values: std::Array<std::Int> = std::Array::fill<std::Int>(count)
                for i in 0..<count {
                    values[i] = (count - i) * 7 % 31
                }
                fin sorted: std::Array<std::Int> = std::sort<std::Int>(values)
                std::println(sorted.size)
                std::println(std::isSorted<std::Int>(sorted))
                std::println(sorted[0])
                std::println(sorted[count - 1])
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
                fin empty: std::Array<std::Int> = std::Array::fill<std::Int>(0)
                std::println(std::sort<std::Int>(empty).size)
                fin one: std::Array<std::Int> = @std::arr[42]
                fin sorted: std::Array<std::Int> = std::sort<std::Int>(one)
                std::println(sorted.size)
                std::println(sorted[0])
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
                var tag: std::String = ""
                var rank: std::Int = 0
            }

            func main() {
                var entries: std::Array<Entry> = std::Array::fill<Entry>(5)
                entries[0] = Entry("a", 2)
                entries[1] = Entry("b", 1)
                entries[2] = Entry("c", 2)
                entries[3] = Entry("d", 1)
                entries[4] = Entry("e", 2)

                fin byRank: std::Array<Entry> = std::sortBy<Entry, std::Int>(
                    entries, { e: Entry -> e.rank }
                )
                var line = ""
                for i in 0..<byRank.size {
                    line = line + byRank[i].tag
                }
                std::println(line)
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
                var name: std::String = ""
                var age: std::Int = 0
            }

            func main() {
                var people: std::Array<Person> = std::Array::fill<Person>(3)
                people[0] = Person("ann", 30)
                people[1] = Person("bob", 20)
                people[2] = Person("cy", 25)

                fin byAge: std::Array<Person> = std::sortBy<Person, std::Int>(
                    people, { p: Person -> p.age }
                )
                var ages = ""
                for i in 0..<byAge.size {
                    ages = ages + "${'$'}{byAge[i].name} "
                }
                std::println(ages)

                fin byName: std::Array<Person> = std::sortBy<Person, std::String>(
                    people, { p: Person -> p.name }
                )
                var names = ""
                for i in 0..<byName.size {
                    names = names + byName[i].name + " "
                }
                std::println(names)
            }
            """,
        )

        assertEquals("bob cy ann \nann bob cy", IrInterpreter().interpret(result.ir).trim())
    }
}
