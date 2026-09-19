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
 * `[…]` where a standard collection is expected builds that collection through
 * its `literal` factory: `List<T>` and `MutableList<T>` an `ArrayList<T>`,
 * `Set<T>` and `MutableSet<T>` a `LinkedHashSet<T>`, `Map<K, V>` and
 * `MutableMap<K, V>` a `LinkedHashMap<K, V>`. Each concrete pack builds itself.
 */
class StdCollectionLiteralTest {
    companion object {
        /**
         * Elements run once each, left to right, whatever the context: a
         * binding, an argument, a return, and an empty literal.
         */
        val lists = """
            import std.io
            import std.container.list
            func next(value: Int): Int {
                println(value)
                return value
            }
            func total(values: List<Int>): Int {
                var sum = 0
                for i in 0..<values.size {
                    sum += values.get(i)
                }
                return sum
            }
            func build(): MutableList<Int> { return [next(7), next(8)] }
            func main() {
                fin xs: List<Int> = [next(3), next(1), next(2)]
                println(xs.get(0) * 100 + xs.get(1) * 10 + xs.get(2))
                println(total([4, 5, 6]))
                var ys = build()
                ys.add(9)
                println(ys.size)
                fin zs: ArrayList<Int> = []
                println(zs.size)
            }
        """.trimIndent()

        /** Elements wider or other than the erased slot come back as themselves. */
        val typedElements = """
            import std.io
            import std.container.list
            func main() {
                fin names: MutableList<String> = ["Ada", "Grace"]
                println(names.get(1))
                var concrete: ArrayList<String> = ["Ada", "Grace"]
                println(concrete[0])
                concrete[1] = "Zed"
                concrete.set(0, "Amy")
                println(concrete[1])
                println(concrete.get(0))
                fin wide: ArrayList<Double> = [1.5, 2.5]
                println(wide[1] + wide.get(0))
            }
        """.trimIndent()

        /** An inner literal takes its context from the outer one's element type. */
        val nested = """
            import std.io
            import std.container.list
            func main() {
                fin rows: List<List<Int>> = [[1, 2], [3]]
                println(rows.size)
                println(rows.get(0).size)
                println(rows.get(1).get(0))
            }
        """.trimIndent()

        /** A global's literal runs before `main`, calling a factory declared after it. */
        val global = """
            import std.io
            import std.container.list
            fin primes: List<Int> = [2, 3, 5]
            func main() {
                println(primes.size)
                println(primes.get(2))
            }
        """.trimIndent()

        /**
         * Every element runs once, left to right, and a repeated one is kept
         * once, at its first position.
         */
        val sets = """
            import std.io
            import std.container.set
            func next(value: Int): Int {
                println(value)
                return value
            }
            func count(values: Set<Int>): Int { return values.size }
            func build(): MutableSet<Int> { return [next(7), next(7), next(8)] }
            func main() {
                fin xs: Set<Int> = [next(3), next(1), next(3), next(2), next(1)]
                println(xs.size)
                println(xs.get(0) * 100 + xs.get(1) * 10 + xs.get(2))
                println(count([4, 4, 4]))
                var ys = build()
                println(ys.add(9))
                println(ys.add(7))
                println(ys.size)
                fin empty: HashSet<Int> = []
                println(empty.size)
            }
        """.trimIndent()

        /** Each concrete set builds itself; strings compare by content. */
        val setKinds = """
            import std.io
            import std.container.set
            fin seen: Set<Int> = [1, 2, 2]
            func main() {
                fin names: MutableSet<String> = ["Ada", "Grace", "Ada"]
                println(names.size)
                println(names.get(1))
                println(names.contains("Grace"))
                fin hashed: HashSet<String> = ["x", "y", "x"]
                println(hashed.size)
                fin tree: TreeSet<Int> = [5, 5, 6]
                println(tree.size)
                fin linked: LinkedHashSet<Double> = [1.5, 2.5, 1.5]
                println(linked.get(1) + linked.get(0))
                println(seen.size)
            }
        """.trimIndent()

        /** Programs that run the same on every target. */
        val programs = listOf(
            lists to "3\n1\n2\n312\n15\n7\n8\n3\n0",
            typedElements to "Grace\nAda\nZed\nAmy\n4.0",
            nested to "2\n2\n3",
            global to "3\n5",
            sets to "3\n1\n3\n2\n1\n3\n312\n1\n7\n7\n8\ntrue\nfalse\n3\n0",
            setKinds to "2\nGrace\ntrue\n2\n2\n4.0\n2",
        )

        /**
         * Each key runs before its value, entries left to right, each once; a
         * repeated key keeps the value written last.
         */
        val maps = """
            import std.io
            import std.container.map
            func key(value: String): String {
                println(value)
                return value
            }
            func value(v: Int): Int {
                println(v)
                return v
            }
            func count(values: Map<String, Int>): Int { return values.size }
            func build(): MutableMap<Int, Int> { return [1: 10, 2: 20] }
            func main() {
                fin ages: Map<String, Int> = [key("a"): value(1), key("b"): value(2), key("a"): value(3)]
                println(ages.size)
                println(ages.get("a"))
                println(ages.get("b"))
                println(count(["x": 1]))
                var more = build()
                more.put(3, 30)
                println(more.size)
                println(more.get(3))
                fin empty: HashMap<String, Int> = [:]
                println(empty.size)
            }
        """.trimIndent()

        /** Each concrete map builds itself. */
        val mapKinds = """
            import std.io
            import std.container.map
            func main() {
                fin hashed: HashMap<String, Int> = ["x": 1, "y": 2, "x": 5]
                println(hashed.size)
                println(hashed["x"])
                fin tree: TreeMap<Int, String> = [2: "two", 1: "one"]
                println(tree.size)
                println(tree[1])
                var linked: LinkedHashMap<Int, Double> = [1: 1.5, 2: 2.5]
                linked[3] = 3.5
                println(linked[3] + linked[1])
            }
        """.trimIndent()

        /**
         * Maps run on the interpreter only. Natively, `key.hash` on an
         * unconstrained `K` cannot be lowered on WASM (019/022/044), and
         * `LinkedHashMap`'s `ctor .()` does not run on LLVM or WASM, so its
         * buckets stay zero and insertion does not end.
         */
        val interpreterPrograms = listOf(
            maps to "a\n1\nb\n2\na\n3\n2\n3\n2\n1\n3\n30\n0",
            mapKinds to "2\n5\n2\none\n5.0",
        )

        fun compile(source: String, optimized: Boolean): IrProgram {
            val result = Compiler().compile(source, release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            return result.ir
        }

        private fun rejected(source: String): List<String> {
            val result = Compiler().compile(source.trimIndent())
            return assertIs<CompilationResult.Failure>(result, "the program compiled").errors
        }
    }

    @Test fun collectionLiteralsBuildTheirTargets() {
        for ((source, expected) in programs + interpreterPrograms) for (optimized in listOf(false, true)) {
            assertEquals(expected, IrInterpreter().interpret(compile(source, optimized)).trim(), "optimized=$optimized\n$source")
        }
    }

    @Test fun anEntryIsCheckedAgainstTheMapsKeyAndValueTypes() {
        val errors = rejected("""
            import std.container.map
            func main() {
                fin ages: Map<String, Int> = ["a": "one"]
            }
        """)
        assertTrue(errors.isNotEmpty() && errors.any { "Int" in it && "String" in it }, errors.toString())
    }

    @Test fun anElementIsCheckedAgainstTheListsElementType() {
        val errors = rejected("""
            import std.container.list
            func main() {
                fin xs: List<Int> = ["a"]
            }
        """)
        assertTrue(errors.any { "an element of a 'List' literal must have type Int, got String" in it }, errors.toString())
    }

    /** Elements are upcast to a spec element type (GTC §8.6). */
    @Test fun specElementsAreUpcast() = assertEquals("2\n10", IrInterpreter().interpret(compile("""
        import std.io
        import std.container.list
        spec Shape {
            prop &.area: Int
        }
        pack Square { var side: Int }
        pack Rect {
            var w: Int
            var h: Int
        }
        impl Shape for Square { prop &.area: Int = self.side * self.side }
        impl Shape for Rect { prop &.area: Int = self.w * self.h }
        func main() {
            fin shapes: List<Shape> = [Square(2), Rect(2, 3)]
            println(shapes.size)
            println(shapes.get(0).area + shapes.get(1).area)
        }
    """.trimIndent(), optimized = false)).trim())

    /** Importing the list module does not make `[]` a list: an empty literal needs a context. */
    @Test fun anUntypedEmptyLiteralStillNeedsAContext() {
        val errors = rejected("""
            import std.container.list
            func main() {
                fin xs = []
            }
        """)
        assertTrue(errors.any { "cannot infer element type of empty sequence literal" in it }, errors.toString())
    }
}
