/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The short forms code is written in, on every backend:
 *
 * - `.Start, else -> v` - named cases that share the fallback, in the
 *   statement, `return when` and expression forms;
 * - `try then s` / `catch then s` / `catch e then s` for one statement;
 * - `inline for … then` as an argument list and as a statement;
 * - `return for … else 0` and `= for …`, whose values are the function's
 *   return type (`Long` here), not the `else` literal's;
 * - `::invalid` - a static value of the expected type, as `.Name` is a case;
 * - one value per line without commas: arguments whose next line opens with
 *   a name or `(`, and collection literals.
 */
class ConciseSurfaceExecTest {
    private val program = """
        import std.io

        enum Alignment { Start, Center, End }

        func offset(alignment: Alignment, available: Double, extent: Double): Double {
            return when alignment {
                .Center -> (available - extent) * 0.5
                .End -> available - extent
                .Start, else -> 0.0
            }
        }

        func edge(a: Alignment): String = when a {
            .Center -> "center"
            .Start, .End, else -> "edge"
        }

        func score(a: Alignment): Int {
            var r = 0
            when a {
                .Center -> r = 1
                .Start, else -> {
                    r = 7
                    r++
                }
            }
            return r
        }

        error ProbeError {
            Negative
        }

        func checked(n: Int): Int ?! ProbeError {
            if n < 0 then return .Negative
            return n
        }

        func add3(a: Int, b: Int, c: Int): Int = a + b * 10 + c * 100

        pack Cache {
            var keys: Array<Long> = []
            var blocks: Array<Long> = []
        }

        func Cache&.block(key: Long): Long {
            return for look: Int in 0..<self.keys.size {
                if self.keys[look] == key then break self.blocks[look]
            } else 0
        }

        func firstEven(xs: Array<Int>&): Long = for x in xs {
            if x % 2 == 0 then break x as Long
        } else -1

        pack Entity {
            var id: Int
            var generation: Int
        }

        impl Entity {
            fin invalid: Entity = .(-1, 0)
            func make(id: Int): Entity = .(id, 1)
        }

        func pick(i: Int): Entity {
            if i < 0 then return ::invalid
            return ::make(i)
        }

        fin greeting = "outer"

        pack Route {
            var points: Array<Int> = []
        }

        func sum3(a: Double, b: Double, c: Double): Double = a + b * 10.0 + c * 100.0

        func main() {
            println(offset(.Center, 10.0, 4.0))
            println(offset(.End, 10.0, 4.0))
            println(offset(.Start, 10.0, 4.0))
            println(edge(.End))
            println(edge(.Center))
            println(score(.Start))
            println(score(.Center))

            var refused = false
            try then checked(-1)
            catch then refused = true
            println(refused)
            var accepted = true
            try then checked(3) catch then accepted = false
            println(accepted)
            var named = 0
            try then checked(-2)
            catch e then named = 5
            println(named)

            println(add3(inline for i in 0..<3 then i + 1))
            var total = 0
            inline for T in [Int, Long] then total += 1
            inline for i in 0..<3 then total += i
            println(total)

            fin cache: Cache = .([5], [77])
            println(cache.block(5))
            println(cache.block(6))
            fin xs: Array<Int> = [1, 3, 4, 6]
            println(firstEven(xs))
            fin odd: Array<Int> = [1, 3]
            println(firstEven(odd))

            println(pick(-3).id)
            println(pick(4).generation)
            fin none: Entity = ::invalid
            println(none.id)
            fin greeting = "inner"
            println(::greeting)
            println(greeting)

            fin routes: Array<Route> = [
                Route([1])
                Route([
                    2
                    3
                ])
            ]
            println(routes[1].points.size)
            fin half: Double = 2.0
            println(sum3(
                half / 2.0
                (1.0 - 0.5) * 4.0
                half
            ))
        }
    """.trimIndent()

    private val expected = listOf(
        "3.0", "6.0", "0.0", "edge", "center", "8", "1",
        "true", "true", "5",
        "321", "5",
        "77", "0", "4", "-1",
        "-1", "1", "-1", "outer", "inner",
        "2", "221.0",
    ).joinToString("\n")

    @Test fun theShortFormsRunAlikeOnEveryBackend() {
        for (release in listOf(false, true)) {
            val result = Compiler().compile(program, release = release)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            assertEquals(expected, IrInterpreter().interpret(result.ir).trim(), "interpreter release=$release")
        }
        if (LlvmExec.available) for (optimized in listOf(false, true)) {
            assertEquals(expected, LlvmExec.run(program, optimized), "LLVM optimized=$optimized")
        }
        if (WasmExec.available) assertEquals(expected, WasmExec.run(program).trimEnd(), "WebAssembly")
    }

    @Test fun aCaseGroupedWithElseIsStillCheckedAgainstTheScrutinee() {
        val result = Compiler().compile(
            """
            enum Alignment { Start, Center, End }
            func offset(a: Alignment): Int = when a {
                .Center -> 1
                .Strat, else -> 0
            }
            func main() { offset(.End) }
            """.trimIndent(),
        )
        val failure = assertIs<CompilationResult.Failure>(result)
        assertTrue(failure.errors.any { "Strat" in it }, failure.errors.toString())
    }

    @Test fun aLocalReadOnlyInsideABlockIsUsed() {
        val result = Compiler().compile(
            """
            import std.io
            func each(n: Int, body: inline (Int) -> Unit) {
                var i = 0
                while i < n {
                    body(i)
                    i++
                }
            }
            func main() {
                var status: String = "ready"
                each(2) { i -> println("${'$'}{status} ${'$'}{i}") }
            }
            """.trimIndent(),
        )
        val success = assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertTrue(success.warnings.none { "never used" in it }, success.warnings.toString())
    }

    @Test fun argumentsOfAnInferredConstructionAreUses() {
        val result = Compiler().compile(
            """
            pack Pair2 {
                var a: Double
                var b: Double
            }
            func make(x: Double): Pair2 {
                fin twice = x * 2.0
                return .(
                    x
                    twice
                )
            }
            func main() { make(1.0) }
            """.trimIndent(),
        )
        val success = assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
        assertTrue(success.warnings.none { "never used" in it }, success.warnings.toString())
    }
}
