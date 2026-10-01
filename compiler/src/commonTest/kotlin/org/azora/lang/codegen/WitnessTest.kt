/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrExpr
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrStmt
import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.IrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A type parameter bounded by `Hash` or `Equal` carries a descriptor of the
 * concrete type, so `x.hash` and `a == b` inside generic code apply that
 * type's own operation to an erased value. Without one, a string built at run
 * time hashed and compared by its address on LLVM and Wasm.
 */
class WitnessTest {
    companion object {
        /** Bounded packs and functions over strings built at run time, integers and a derived pack. */
        val bounded = """
            import std.io
            import std.traits
            pack Box<K> where K: Hash {
                var item: K
            }
            impl Box<K> {
                func &.same(other: K): Bool { return self.item == other }
                prop &.code: ULong = self.item.hash
            }
            pack Key {
                var a: Int
                var b: Int
            }
            derive (Equal) for Key
            func<T> sameHash(a: T, b: T): Bool where T: Hash { return a.hash == b.hash }
            func<T> equalAll(a: T, b: T): Bool where T: Equal { return a == b }
            func<T> forwarded(a: T, b: T): Bool where T: Hash { return sameHash(a, b) && equalAll(a, b) }
            func joined(a: String, b: String): String { return a + b }
            func main() {
                fin b = Box<String>("ab")
                println(b.same(joined("a", "b")))
                println(b.code == "ab".hash)
                println(sameHash(joined("x", "y"), "xy"))
                println(sameHash(3, 4))
                println(equalAll(joined("p", "q"), "pq"))
                println(equalAll(Key(1, 2), Key(1, 2)))
                println(equalAll(Key(1, 2), Key(2, 1)))
                println(Box<Key>(Key(5, 6)).same(Key(5, 6)))
                println(forwarded(joined("m", "n"), "mn"))
            }
        """.trimIndent()

        /** The standard maps and sets, keyed by strings built at run time and grown past their first buffers. */
        val collections = """
            import std.io
            import std.container.map
            import std.container.set
            func joined(a: String, b: String): String { return a + b }
            func main() {
                var ages: MutableMap<String, Int> = ["ada": 36, "grace": 85]
                ages.put(joined("al", "an"), 41)
                println(ages.size)
                println(ages.containsKey(joined("gr", "ace")))
                println(ages.containsKey("alan"))
                fin hashed: HashMap<String, Int> = ["x": 1, "y": 2, "x": 5]
                println(hashed.size)
                println(hashed[joined("", "x")])
                var many: HashMap<Int, Int> = .()
                for i in 0..<40 {
                    many.put(i, i * i)
                }
                println(many.size)
                println(many[39])
                fin names: Set<String> = ["Ada", "Grace", joined("A", "da")]
                println(names.size)
                var seen: HashSet<String> = ["a"]
                println(seen.add(joined("", "a")))
                println(seen.add("b"))
                println(seen.size)
                var linked: LinkedHashMap<String, Int> = .()
                for i in 0..<30 {
                    linked.put(joined("k", "${'$'}{i}"), i)
                }
                println(linked.size)
                println(linked[joined("k", "29")])
            }
        """.trimIndent()

        /**
         * `hash` on a built-in gives the same number on every target: a string
         * the 64-bit FNV-1a of its bytes, a float its bit pattern.
         */
        val builtins = """
            import std.io
            func joined(a: String, b: String): String { return a + b }
            func main() {
                println("ab".hash == joined("a", "b").hash)
                println("ab".hash == "ba".hash)
                println("abc".hash % (1000 as ULong))
                println(5.hash == 5 as ULong)
                println(true.hash == 1 as ULong)
                println('a'.hash == 97 as ULong)
                println(2.5.hash % (1000 as ULong))
                println((0 - 3).hash % (1000 as ULong))
            }
        """.trimIndent()

        val programs = listOf(
            bounded to "true\ntrue\ntrue\nfalse\ntrue\ntrue\nfalse\ntrue\ntrue",
            collections to "3\ntrue\ntrue\n2\n5\n40\n1521\n2\nfalse\ntrue\n2\n30\n29",
            builtins to "true\nfalse\n931\ntrue\ntrue\ntrue\n976\n613",
        )

        fun compile(source: String, optimized: Boolean): IrProgram {
            val result = Compiler().compile(source, release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            return if (optimized) result.optimizedIr else result.ir
        }

        private fun rejected(source: String): List<String> =
            assertIs<CompilationResult.Failure>(Compiler().compile(source.trimIndent()), "the program compiled").errors
    }

    @Test fun boundedOperationsApplyTheConcreteTypes() {
        for ((source, expected) in programs) for (optimized in listOf(false, true)) {
            assertEquals(expected, IrInterpreter().interpret(compile(source, optimized)).trim(), "optimized=$optimized\n$source")
        }
    }

    /**
     * Each target of a grouped assignment gets the value's expression typed by
     * that target: a buffer of `Bool` for one and of `ULong` for the other.
     * Before, the last target's reading was used for all of them, so `HashMap`
     * grew its eight-byte buffers at one byte per slot.
     */
    @Test fun eachGroupedTargetAllocatesItsOwnElementType() {
        val ir = compile("""
            pack Mixed {
                var flags: Bool* = alloc .() * 2
                var wide: ULong* = alloc .() * 2
            }
            impl Mixed {
                func !.fresh(n: Int) {
                    self.{flags, wide} = alloc .() * n
                }
            }
            func main() {
                var m: Mixed = .()
                m.fresh(4)
            }
        """.trimIndent(), optimized = false)
        val fresh = ir.items.filterIsInstance<IrTopLevel.Func>().single { it.function.name.endsWith("fresh") }
        val writes = (fresh.function.body.single() as IrStmt.Scope).body.filterIsInstance<IrStmt.MemberAssign>()
        assertEquals(
            listOf("flags" to IrType.Pointer(IrType.Bool), "wide" to IrType.Pointer(IrType.ULong)),
            writes.map { it.name to (it.value as IrExpr.Call).type },
        )
    }

    @Test fun aKeyTypeThatIsNotHashIsRejected() {
        val explicit = rejected("""
            import std.container.set
            func main() {
                fin s = HashSet<Double>()
            }
        """)
        assertTrue(explicit.any { "line 3: 'HashSet<Double>' does not satisfy its 'where' clause" in it && "Double does not implement Hash" in it }, explicit.toString())
        val literal = rejected("""
            import std.container.set
            func main() {
                fin s: Set<Double> = [1.5]
            }
        """)
        assertTrue(literal.any { "'T is Equal': Double does not implement Equal" in it }, literal.toString())
        val call = rejected("""
            func<T> code(x: T): ULong where T: Hash { return x.hash }
            func main() {
                fin c = code(2.5)
            }
        """)
        assertTrue(call.any { "line 3: 'code' does not satisfy its 'where' clause here" in it && "does not implement Hash" in it }, call.toString())
    }

    @Test fun genericCodeMustDeclareTheBoundItPassesOn() {
        val construction = rejected("""
            import std.container.set
            func<T> make(x: T): Int {
                var s = HashSet<T>()
                s.add(x)
                return s.size
            }
            func main() {
                fin n = make(3)
            }
        """)
        assertTrue(construction.any { "line 3: 'T' of 'HashSet' must be Hash, and 'T' is not declared to be; add 'where T: Hash'" in it }, construction.toString())
        val call = rejected("""
            import std.container.set
            func<T> wrap(x: T): Int {
                return setOf(x, x).size
            }
            func main() {
                fin n = wrap(3)
            }
        """)
        assertTrue(call.any { "'T' of 'setOf' must be Equal, and 'T' is not declared to be; add 'where T: Equal'" in it }, call.toString())
    }

    /** A 64-bit unsigned value divides and orders by its magnitude, as the native targets do. */
    @Test fun theInterpreterDividesAndOrdersULongUnsigned() {
        val ir = compile("""
            import std.io
            func main() {
                fin big = (0 - 3) as ULong
                println(big % (1000 as ULong))
                println(big / (1000000000000000000 as ULong))
                println(big > (5 as ULong))
            }
        """.trimIndent(), optimized = false)
        assertEquals("613\n18\ntrue", IrInterpreter().interpret(ir).trim())
    }
}
