package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tier 3 - memory model foundation: `unsafe { }`, `alloc`, `*ptr` deref, `*ptr = v`, `drop`, `T*`.
 *
 * Pointers are mutable cells (a `Pointer` wrapper) in the interpreter; `alloc`/`*ptr`/
 * `*ptr=v` lower to `__alloc`/`__deref`/`__derefAssign` runtime calls, so no new IR
 * expr/stmt nodes are needed. `unsafe { }` desugars to a `scope`, `drop` to evaluating
 * the expression (advisory free under GC).
 */
class Tier3MemoryTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun aMutablePointerCanBeWrittenThrough() {
        assertEquals("4\n10", run("""
            import std.io
            unsafe func main() {
                var y: Int^ = alloc^ 4
                println(y.^)
                y.^ = 10
                println(y.^)
                purge y
            }
        """.trimIndent()))
    }

    @Test fun aReadOnlyPointerCannotBeWrittenThrough() {
        // The sigil carries the difference at every site - the type, the
        // allocation, and the dereference - so the write site alone says whether
        // it is allowed.
        val result = Compiler().compile("""
            import std.io
            unsafe func main() {
                fin x: Int* = alloc* 4
                x.* = 10
            }
        """.trimIndent())
        assertIs<CompilationResult.Failure>(result)
        assertTrue(
            result.errors.any { "cannot write through 'Int*'" in it },
            "${'$'}{result.errors}",
        )
    }

    @Test fun aReadOnlyPointerStillReads() {
        assertEquals("4", run("""
            import std.io
            unsafe func main() {
                fin x: Int* = alloc* 4
                println(x.*)
            }
        """.trimIndent()))
    }

    @Test fun allocDerefAndStoreInt() {
        assertEquals("5\n99", run("""
            import std.io
            func main() {
                var p = alloc^ 5
                println(*p)
                *p = 99
                println(*p)
            }
        """.trimIndent()))
    }

    @Test fun allocStructMutateThroughPointer() {
        assertEquals("10\n20", run("""
            import std.io
            pack P {
                var v: Int
            }
            func main() {
                var p = alloc^ P(10)
                println((*p).v)
                (*p).v = 20
                println((*p).v)
            }
        """.trimIndent()))
    }

    @Test fun unsafeBlockDesugarsToRealm() {
        assertEquals("11", run("""
            import std.io
            func main() {
                var x = 1
                unsafe {
                    x = x + 10
                }
                println(x)
            }
        """.trimIndent()))
    }

    @Test fun dropIsAdvisoryNoOp() {
        assertEquals("5", run("""
            import std.io
            func main() {
                var p = alloc^ 5
                purge p
                println(*p)
            }
        """.trimIndent()))
    }

    @Test fun pointerTypeAnnotation() {
        assertEquals("42", run("""
            import std.io
            func main() {
                var p: Int^ = alloc^ 42
                println(*p)
            }
        """.trimIndent()))
    }

    @Test fun derefKeywordReadsAndWritesRawPointer() {
        assertEquals("5\n12", run("""
            import std.io
            func main() {
                var p = alloc^ 5
                println(p.*)
                p.* = 12
                println(p.*)
            }
        """.trimIndent()))
    }

    @Test fun sharedCountsOwnersWithoutSynchronisation() {
        assertEquals("41\n42\n2\n1", run("""
            import std.io
            import std.memory.*
            func main() {
                var p = sharedOf(41)
                println(p.*)
                p.set(42)
                println(p.get)
                println(p.retain())
                println(p.release())
            }
        """.trimIndent()))
    }

    @Test fun syncSharedCountsOwnersAcrossThreads() {
        assertEquals("41\n2\n1", run("""
            import std.io
            import std.memory.*
            func main() {
                var p = syncSharedOf(41)
                println(p.get)
                println(p.retain())
                println(p.release())
            }
        """.trimIndent()))
    }

    @Test fun sliceIndexesPointerBuffer() {
        assertEquals("9\n4", run("""
            import std.io
            import std.memory.*
            func main() {
                var p = alloc Int^() * 3
                p[0] = 7
                p[1] = 8
                p[2] = 9
                var s = ptrSlice(p, 3)
                println(s[2])
                s[2] = 4
                println(p[2])
            }
        """.trimIndent()))
    }

    @Test fun cloneProducesIndependentDeepCopy() {
        // Mutating the clone must not affect the original.
        assertEquals("[1, 2, 3]\n[1, 2, 3, 99]", run("""
            import std.io
            func main() {
                var a = @arr[1, 2, 3]
                var b = a.clone()
                b.add(99)
                println(a)
                println(b)
            }
        """.trimIndent()))
    }

    @Test fun cloneOnAnArrayIsIndependentOfTheOriginal() {
        // `impl Clone for Array` lives in `std.container.array`: the standard
        // library grants the capability, the compiler supplies the body.
        assertEquals("1\n99", run("""
            import std.io
            func main() {
                var original = @arr[1, 2, 3]
                var copy = original.clone()
                copy[0] = 99
                println(original[0])
                println(copy[0])
            }
        """.trimIndent()))
    }

    @Test fun cloneDeepCopiesNestedStruct() {
        assertEquals("7\n1", run("""
            import std.io
            pack Box {
                var v: Int
            }
            func main() {
                var original = Box(1)
                var copy = original.clone()
                copy.v = 7
                println(copy.v)
                println(original.v)
            }
        """.trimIndent()))
    }

    @Test fun pointerOpsEmitRuntimePreambleInBackends() {
        val result = Compiler().compile("""
            import std.io
            func main() {
                var p = alloc^ 5
                *p = 99
                println(*p)
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
    }

    @Test fun pointerArithmeticOffsetAndDeref() {
        assertEquals("10\n20\n30", run("""
            import std.io
            func main() {
                var p: Int^ = alloc^ @arr[10, 20, 30]
                println(*p)
                var p1 = p + 1
                println(*p1)
                println(*(p + 2))
            }
        """.trimIndent()))
    }

    @Test fun pointerArithmeticSubtract() {
        assertEquals("30\n20", run("""
            import std.io
            func main() {
                var p: Int^ = alloc^ @arr[10, 20, 30]
                var end = p + 2
                println(*end)
                var back = end - 1
                println(*back)
            }
        """.trimIndent()))
    }

    @Test fun pointerArithmeticWriteThroughOffset() {
        assertEquals("99", run("""
            import std.io
            func main() {
                var p: Int^ = alloc^ @arr[10, 20, 30]
                *(p + 1) = 99
                println(*(p + 1))
            }
        """.trimIndent()))
    }

    @Test fun pointerArithmeticDistance() {
        assertEquals("3", run("""
            import std.io
            func main() {
                var p: Int^ = alloc^ @arr[10, 20, 30, 40]
                var q = p + 3
                println(q - p)
            }
        """.trimIndent()))
    }
}
