/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Global initializers retain their transitive dependencies in release builds. */
class GlobalInitializerTest {
    companion object {
        /** `main` reaches a global, then functions, then an earlier global and its function. */
        val transitive = """
            import std.io
            fin base = makeBase()
            fin answer = finish()
            func makeBase(): Int {
                println(40)
                return 40
            }
            func addTwo(): Int { return base + 2 }
            func finish(): Int {
                println(42)
                return addTwo()
            }
            func unused(): Int {
                println("unused")
                return 0
            }
            func main() {
                println("main")
                println(answer)
                println(answer)
            }
        """.trimIndent()

        /** Factories and their helpers run once, in declaration and element order. */
        val collections = """
            import std.io
            import std.container.list
            import std.container.set
            func next(value: Int): Int {
                println(value)
                return value
            }
            fin numbers: List<Int> = [next(1), next(2)]
            fin unique: Set<Int> = [next(3), next(3), next(4)]
            fin lookup = ["answer": next(42)]
            func main() {
                println("main")
                println(numbers.get(1))
                println(unique.size)
                println(unique.contains(4))
                println(lookup["answer"])
            }
        """.trimIndent()

        /** Thread-local globals also retain target-owned factories, including empty literals. */
        val threadLocal = """
            import std.io
            pack Digits { var value: Int }
            impl Digits {
                literal [...items: Int]: Digits {
                    var result = 0
                    for i in 0..<items.size { result = result * 10 + items[i] }
                    return Digits(result)
                }
            }
            threadlocal fin digits: Digits = [1, 2, 3]
            threadlocal fin empty: Digits = []
            func main() {
                println(digits.value)
                println(empty.value)
            }
        """.trimIndent()

        val programs = listOf(
            transitive to "40\n42\nmain\n42\n42",
            collections to "1\n2\n3\n3\n4\n42\nmain\n2\n2\ntrue\n42",
            threadLocal to "123\n0",
        )

        fun compile(source: String, optimized: Boolean): IrProgram {
            val result = Compiler().compile(source, release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            return if (optimized) result.optimizedIr else result.ir
        }
    }

    private fun check(program: Pair<String, String>) {
        val (source, expected) = program
        for (optimized in listOf(false, true)) {
            assertEquals(expected, IrInterpreter().interpret(compile(source, optimized)).trim(), "optimized=$optimized")
        }
    }

    @Test fun globalsReachFunctionsAndEarlierGlobalsTransitively() = check(programs[0])
    @Test fun globalCollectionsKeepTheirFactories() = check(programs[1])
    @Test fun threadLocalGlobalsKeepTheirFactories() = check(programs[2])
}
