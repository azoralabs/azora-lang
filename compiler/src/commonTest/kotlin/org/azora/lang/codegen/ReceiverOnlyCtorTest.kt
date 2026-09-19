/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.ctorRunSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `ctor .()` runs wherever a construction writes no arguments: `.()` where the
 * type is stated, `Type()` and `Type<Args>()`. The IR asks for it, so every
 * backend runs it, and runs it once; the interpreter used to call it on its own
 * and the native targets never did.
 */
class ReceiverOnlyCtorTest {
    companion object {
        /** A plain and a generic pack, built each way; the ctor runs exactly once. */
        val counter = """
            import std.io
            pack Counter {
                var x: Int = 1
                var runs: Int = 0
                var buf: Int* = alloc .() * 4
            }
            impl Counter {
                ctor .() {
                    self.runs += 1
                    self.x = 5
                    for i in 0..<4 {
                        self.buf[i] = -1
                    }
                }
            }
            pack Gen<T> { var x: Int = 1 }
            impl Gen<T> { ctor .() { self.x = 7 } }
            func main() {
                var c: Counter = .()
                println(c.x)
                println(c.buf[2])
                fin d = Counter()
                println(d.x)
                println(d.runs)
                var g: Gen<Int> = .()
                println(g.x)
                fin h = Gen<Int>()
                println(h.x)
            }
        """.trimIndent()

        /**
         * Beside other ctors, `ctor .()` is the one a call writing no arguments
         * runs - ahead of one whose parameters all have defaults - and a call
         * with arguments runs only the ctor it selected.
         */
        val overloads = """
            import std.io
            pack P { var x: Int = 1 }
            impl P {
                ctor .() { self.x = 10 }
                ctor .(v: Int) { self.x = self.x + v * 2 }
            }
            pack D { var x: Int = 1 }
            impl D {
                ctor .() { self.x = 10 }
                ctor .(v: Int = 3) { self.x = v }
            }
            pack V { var n: Int = 0 }
            impl V {
                ctor .() { self.n = 1 }
                ctor .(a: Int, b: Int) { self.n = a * 10 + b }
            }
            func main() {
                var a: P = .()
                println(a.x)
                println(P(4).x)
                println(D().x)
                println(D(4).x)
                fin v: V = .()
                println(v.n)
                println(V(b: 4, a: 1).n)
            }
        """.trimIndent()

        /** Filling the fields is its own construction; `ctor .()` does not run over it. */
        val memberwise = """
            import std.io
            pack Q {
                var x: Int = 1
                var y: Int = 2
            }
            impl Q { ctor .() { self.x = 50 } }
            func main() {
                fin q = Q(8, 9)
                println(q.x)
                println(q.y)
                fin r = Q()
                println(r.x)
                println(r.y)
            }
        """.trimIndent()

        /**
         * A field default, a return, `Self()`, a default parameter and a generic
         * function each construct with the ctor.
         */
        val contexts = """
            import std.io
            pack Inner { var v: Int = 1 }
            impl Inner {
                ctor .() { self.v = 42 }
                func &.fresh(): Inner { return Self() }
            }
            pack Outer {
                var inner: Inner = .()
                var n: Int = 3
            }
            pack Box<T> {
                var items: T* = alloc .() * 2
                var tag: Int = 0
            }
            impl Box<T> { ctor .() { self.tag = 99 } }
            func build(): Inner { return .() }
            func read(i: Inner = .()): Int { return i.v }
            func readBox(b: Box<Int> = .()): Int { return b.tag }
            func<T> freshBox(): Box<T> { return Box<T>() }
            func main() {
                fin o = Outer()
                println(o.inner.v)
                fin b = build()
                println(b.v)
                println(b.fresh().v)
                println(read())
                println(readBox())
                fin f = freshBox<Int>()
                println(f.tag)
                var bd: Box<Double> = .()
                println(bd.tag)
            }
        """.trimIndent()

        /**
         * Open addressing whose empty bucket is -1, set only by `ctor .()` - the
         * shape of `LinkedHashMap`. Without the ctor the buckets read 0, look
         * full, and nothing is ever stored.
         */
        val table = """
            import std.io
            pack Table<V> {
                var keys: Int* = alloc .() * 8
                var values: V* = alloc .() * 8
                var buckets: Int* = alloc .() * 8
                var size: Int = 0
            }
            impl Table<V> {
                ctor .() {
                    for i in 0..<8 {
                        self.buckets[i] = -1
                    }
                }
                func &.find(key: Int): Int {
                    var bucket = key % 8
                    for tries in 0..<8 {
                        fin index = self.buckets[bucket]
                        if index < 0 {
                            return -1
                        }
                        if self.keys[index] == key {
                            return index
                        }
                        bucket = (bucket + 1) % 8
                    }
                    return -1
                }
                func !.put(key: Int, value: V): Bool {
                    fin found = self.find(key)
                    if found >= 0 {
                        self.values[found] = value
                        return true
                    }
                    var bucket = key % 8
                    for tries in 0..<8 {
                        if self.buckets[bucket] < 0 {
                            self.keys[self.size] = key
                            self.values[self.size] = value
                            self.buckets[bucket] = self.size
                            self.size += 1
                            return true
                        }
                        bucket = (bucket + 1) % 8
                    }
                    return false
                }
            }
            func main() {
                var t: Table<Int> = .()
                println(t.put(3, 30))
                println(t.put(11, 110))
                println(t.put(3, 31))
                println(t.size)
                println(t.find(3))
                println(t.find(11))
                println(t.find(19))
            }
        """.trimIndent()

        val programs = listOf(
            counter to "5\n-1\n5\n1\n7\n7",
            overloads to "10\n9\n10\n4\n1\n14",
            memberwise to "8\n9\n50\n2",
            contexts to "42\n42\n42\n42\n99\n99\n99",
            table to "true\ntrue\ntrue\n2\n0\n1\n-1",
        )

        /** The IR a backend is given: the optimizer's output when [optimized]. */
        fun compile(source: String, optimized: Boolean): IrProgram {
            val result = Compiler().compile(source, release = optimized)
            assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
            return if (optimized) result.optimizedIr else result.ir
        }
    }

    @Test fun theCtorRunsOnTheInterpreter() {
        for ((source, expected) in programs) for (optimized in listOf(false, true)) {
            assertEquals(expected, IrInterpreter().interpret(compile(source, optimized)).trim(), "optimized=$optimized\n$source")
        }
    }

    @Test fun theConstructionAsksForTheCtorInIr() {
        // Nothing is left for a backend to infer: `main` calls the function that
        // runs the ctor, and that function calls it.
        val functions = compile(counter, optimized = false).functions.associateBy { it.name }
        val run = assertNotNull(functions[ctorRunSymbol("Counter")], "no ${ctorRunSymbol("Counter")} in ${functions.keys}")
        assertTrue("Call(name=Counter_ctor," in run.body.toString(), run.body.toString())
        val main = functions.getValue("main").body.toString()
        assertEquals(2, Regex("""Call\(name=${ctorRunSymbol("Counter")},""").findAll(main).count(), main)
        assertTrue("Call(name=${ctorRunSymbol("Gen")}," in main, main)
    }
}
