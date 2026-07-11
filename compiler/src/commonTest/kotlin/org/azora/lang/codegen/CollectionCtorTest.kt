package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies the new collection-constructor syntax (`arr/set/map/tup/var(...)`) and `loop iterable { }`,
 * which are added alongside the old bracket/tuple literals (additive — old syntax still works).
 */
class CollectionCtorTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source, release = false)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun arr_ctor() = assertEquals("10\n30\n3", run("""
        func main() {
            fin a = arr(10, 20, 30)
            println(a[0])
            println(a[2])
            println(a.length)
        }
    """.trimIndent()))

    @Test fun arr_empty() = assertEquals("1", run("""
        func main() {
            fin a = arr(7)
            println(a.length)
        }
    """.trimIndent()))

    @Test fun map_ctor() = assertEquals("1\n20", run("""
        func main() {
            fin m = map("x": 1, "y": 20)
            println(m["x"])
            println(m["y"])
        }
    """.trimIndent()))

    @Test fun set_ctor() = assertEquals("true\nfalse", run("""
        func main() {
            fin s = set(1, 2, 3)
            println(s.contains(2))
            println(s.contains(9))
        }
    """.trimIndent()))

    @Test fun tup_ctor() = assertEquals("1\na", run("""
        func main() {
            fin t = tup(1, "a", 2.5)
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

    @Test fun explicit_collection_types() = assertEquals("10\n99", run("""
        func main() {
            fin a: Arr<Int> = arr(10, 20, 30)
            println(a[0])
            fin m: Map<String, Int> = map("k": 99)
            println(m["k"])
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
}
