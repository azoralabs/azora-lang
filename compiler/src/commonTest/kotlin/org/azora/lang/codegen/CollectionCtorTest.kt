package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies collection constructors (`arr/set/map/tup/var(...)`), collection type spellings, and
 * `loop iterable { }`.
 */
class CollectionCtorTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun arr_ctor() = assertEquals("10\n30\n3", run("""
        func main() {
            fin a = arrayOf(10, 20, 30)
            println(a[0])
            println(a[2])
            println(a.length)
        }
    """.trimIndent()))

    @Test fun arr_empty() = assertEquals("1", run("""
        func main() {
            fin a = arrayOf(7)
            println(a.length)
        }
    """.trimIndent()))

    @Test fun map_ctor() = assertEquals("1\n20", run("""
        func main() {
            fin m = mapOf("x": 1, "y": 20)
            println(m["x"])
            println(m["y"])
        }
    """.trimIndent()))

    @Test fun set_ctor() = assertEquals("true\nfalse", run("""
        func main() {
            fin s = setOf(1, 2, 3)
            println(s.contains(2))
            println(s.contains(9))
        }
    """.trimIndent()))

    @Test fun set_index_assign_preserves_uniqueness() = assertEquals("4\n4\ntrue\nfalse", run("""
        use std
        func main() {
            var s = Set<Int>()
            s.add(1)
            s.add(2)
            s.add(3)
            s[0] = 4
            println(s[0])
            s[0] = 2
            println(s[0])
            println(s.contains(4))
            println(s.contains(1))
        }
    """.trimIndent()))

    @Test fun tup_ctor() = assertEquals("1\na", run("""
        func main() {
            fin t = (1, "a", 2.5)
            println(t.0)
            println(t.1)
        }
    """.trimIndent()))

    @Test fun var_ctor_holds_first() = assertEquals("7", run("""
        func main() {
            fin v = var(7, 1.5, "hi")
            println(v)
        }
    """.trimIndent()))

    @Test fun var_direct_assign_and_when() = assertEquals("int 42", run("""
        func main() {
            var v: Var<Int, Real, String> = 42
            when v {
                is Int -> { println("int " + v) }
                is Real -> { println("real " + v) }
                is String -> { println("str " + v) }
            }
        }
    """.trimIndent()))

    @Test fun var_when_matches_held_type() = assertEquals("real\nstr", run("""
        func describe(v: Var<Int, Real, String>): String {
            when v {
                is Int -> { return "int" }
                is Real -> { return "real" }
                is String -> { return "str" }
            }
            return "?"
        }
        func main() {
            var a: Var<Int, Real, String> = 2.5
            var b: Var<Int, Real, String> = "hi"
            println(describe(a))
            println(describe(b))
        }
    """.trimIndent()))

    @Test fun explicit_collection_types() = assertEquals("10\n99", run("""
        func main() {
            fin a: Array<Int> = arrayOf(10, 20, 30)
            println(a[0])
            fin m: Map<String, Int> = mapOf("k": 99)
            println(m["k"])
        }
    """.trimIndent()))

    @Test fun mutable_collection_packs_exist() = assertEquals("8\ntrue\nfalse\n3", run("""
        use std.container
        func main() {
            var xs = MutableList<Int>()
            xs.add(7)
            xs[0] = 8
            println(xs[0])

            var seen = MutableSet<Int>()
            seen.add(1)
            seen.add(1)
            println(seen.contains(1))
            println(seen.contains(2))

            fin scores: MutableMap<String, Int> = mapOf("azora": 3)
            println(scores["azora"])
        }
    """.trimIndent()))

    @Test fun loop_iterable() = assertEquals("3", run("""
        pack Range {
            var i: Int
            var max: Int
        }
        impl Range {
            func reset() { self.i = 0 }
            func hasNext(): Bool { return self.i < self.max }
            func next(): Int {
                fin v = self.i
                self.i = self.i + 1
                return v
            }
        }
        func main() {
            var r = Range(0, 3)
            var sum = 0
            loop r { sum += r.next() }
            println(sum)
        }
    """.trimIndent()))

    @Test fun query_tup_type_and_loop() = assertEquals("6", run("""
        pack Query {
            var i: Int
            var max: Int
        }
        impl Query {
            func reset() { self.i = 0 }
            func hasNext(): Bool { return self.i < self.max }
            func next(): Int {
                fin v = self.i
                self.i = self.i + 1
                return v
            }
        }
        func run(q: Query<(Int, String)>): Int {
            var sum = 0
            loop q { sum += q.next() }
            return sum
        }
        func main() {
            var q = Query(0, 4)
            println(run(q))
        }
    """.trimIndent()))

    @Test fun alloc_buffer_and_pointer_index() = assertEquals("10\n30\n99", run("""
        func main() {
            var p: Int* = alloc Int[3]
            p[0] = 10
            p[1] = 20
            p[2] = 30
            println(p[0])
            println(p[2])
            p[1] = 99
            println(p[1])
        }
    """.trimIndent()))
}
