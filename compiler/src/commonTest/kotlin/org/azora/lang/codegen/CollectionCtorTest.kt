package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies stdlib collection factories, tuple literals, variant assignment, and
 * `loop iterable { }`.
 */
class CollectionCtorTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun set_ctor() = assertEquals("true\nfalse", run("""
        import std.io
        import std

        func main() {
            fin s = std::setOf(1, 2, 3)
            std::println(s.contains(2))
            std::println(s.contains(9))
        }
    """.trimIndent()))

    @Test fun set_add_preserves_uniqueness() = assertEquals("3\ntrue\nfalse", run("""
        import std.io
        import std
        func main() {
            var s = LinkedHashSet<Int>()
            s.add(1)
            s.add(2)
            s.add(3)
            s.add(2)
            std::println(s.size)
            std::println(s.contains(2))
            std::println(s.contains(9))
        }
    """.trimIndent()))

    @Test fun tup_ctor() = assertEquals("1\na", run("""
        import std.io
        import std.container
        func main() {
            fin t = (1, "a", 2.5)
            std::println(t.0)
            std::println(t.1)
        }
    """.trimIndent()))

    @Test fun var_direct_assign_and_when() = assertEquals("int 42", run("""
        import std.io
        func main() {
            var v: Var<Int, Real, String> = 42
            when v {
                is Int -> { std::println("int " + v) }
                is Real -> { std::println("real " + v) }
                is String -> { std::println("str " + v) }
            }
        }
    """.trimIndent()))

    @Test fun var_when_matches_held_type() = assertEquals("real\nstr", run("""
        import std.io
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
            std::println(describe(a))
            std::println(describe(b))
        }
    """.trimIndent()))

    @Test fun explicit_set_type() = assertEquals("true", run("""
        import std.io
        import std

        func main() {
            fin s: Set<Int> = std::setOf(10, 20, 30)
            std::println(s.contains(20))
        }
    """.trimIndent()))

    @Test fun mutable_list_pack_exists() = assertEquals("8", run("""
        import std.io
        import std.container
        func main() {
            var xs = ArrayList<Int>()
            xs.add(7)
            xs[0] = 8
            std::println(xs[0])
        }
    """.trimIndent()))

    @Test fun mutable_set_pack_exists() = assertEquals("true\nfalse", run("""
        import std.io
        import std.container
        func main() {
            var seen = LinkedHashSet<Int>()
            seen.add(1)
            seen.add(1)
            std::println(seen.contains(1))
            std::println(seen.contains(2))
        }
    """.trimIndent()))

    @Test fun mutable_map_pack_exists() = assertEquals("3", run("""
        import std.io
        import std.container
        func main() {
            var scores = HashMap<String, Int>()
            scores.put("azora", 3)
            std::println(scores["azora"])
        }
    """.trimIndent()))

    @Test fun loop_iterable() = assertEquals("3", run("""
        import std.io
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
            std::println(sum)
        }
    """.trimIndent()))

    @Test fun query_tup_type_and_loop() = assertEquals("6", run("""
        import std.io
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
        func run(q: Query<Tuple<Int, String>>): Int {
            var sum = 0
            loop q { sum += q.next() }
            return sum
        }
        func main() {
            var q = Query(0, 4)
            std::println(run(q))
        }
    """.trimIndent()))

    @Test fun alloc_buffer_and_pointer_index() = assertEquals("10\n30\n99", run("""
        import std.io
        func main() {
            var p: Int* = alloc Int[3]
            p[0] = 10
            p[1] = 20
            p[2] = 30
            std::println(p[0])
            std::println(p[2])
            p[1] = 99
            std::println(p[1])
        }
    """.trimIndent()))
}
