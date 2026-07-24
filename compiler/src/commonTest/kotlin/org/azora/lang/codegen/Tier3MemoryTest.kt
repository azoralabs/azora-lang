package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.*

/**
 * Tier 3 — memory model foundation: `unsafe { }`, `alloc`, `*ptr` deref, `*ptr = v`, `drop`, `T*`.
 *
 * Pointers are mutable cells (a `Pointer` wrapper) in the interpreter; `alloc`/`*ptr`/
 * `*ptr=v` lower to `__alloc`/`__deref`/`__derefAssign` runtime calls, so no new IR
 * expr/stmt nodes are needed. `unsafe { }` desugars to a `zone`, `drop` to evaluating
 * the expression (advisory free under GC).
 */
class Tier3MemoryTest {

    private fun run(source: String): String {
        val result = Compiler().compile(source)
        assertIs<CompilationResult.Success>(result, "Compilation failed: ${(result as? CompilationResult.Failure)?.errors}")
        return IrInterpreter().interpret(result.ir).trim()
    }

    @Test fun allocDerefAndStoreInt() {
        assertEquals("5\n99", run("""
            import std.io
            func main() {
                var p = alloc 5
                std::println(*p)
                *p = 99
                std::println(*p)
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
                var p = alloc P(10)
                std::println((*p).v)
                (*p).v = 20
                std::println((*p).v)
            }
        """.trimIndent()))
    }

    @Test fun unsafeBlockDesugarsToZone() {
        assertEquals("11", run("""
            import std.io
            func main() {
                var x = 1
                unsafe {
                    x = x + 10
                }
                std::println(x)
            }
        """.trimIndent()))
    }

    @Test fun dropIsAdvisoryNoOp() {
        assertEquals("5", run("""
            import std.io
            func main() {
                var p = alloc 5
                drop p
                std::println(*p)
            }
        """.trimIndent()))
    }

    @Test fun pointerTypeAnnotation() {
        assertEquals("42", run("""
            import std.io
            func main() {
                var p: Int* = alloc 42
                std::println(*p)
            }
        """.trimIndent()))
    }

    @Test fun derefKeywordReadsAndWritesRawPointer() {
        assertEquals("5\n12", run("""
            import std.io
            func main() {
                var p = alloc 5
                std::println(deref p)
                deref p = 12
                std::println(deref p)
            }
        """.trimIndent()))
    }

    @Test fun ptrSmartPointerUsesDerefImpl() {
        assertEquals("41\n42", run("""
            import std.io
            import std.memory.*
            func main() {
                var p = std::ptrOf(41)
                std::println(deref p)
                p.set(42)
                std::println(p.get)
            }
        """.trimIndent()))
    }

    @Test fun sliceIndexesPointerBuffer() {
        assertEquals("9\n4", run("""
            import std.io
            import std.memory.*
            func main() {
                var p = alloc Int[3]
                p[0] = 7
                p[1] = 8
                p[2] = 9
                var s = std::ptrSlice(p, 3)
                std::println(s[2])
                s[2] = 4
                std::println(p[2])
            }
        """.trimIndent()))
    }

    @Test fun isolatedProducesIndependentDeepCopy() {
        // Mutating the isolated copy must not affect the original.
        assertEquals("[1, 2, 3]\n[1, 2, 3, 99]", run("""
            import std.io
            func main() {
                var a = arr@[1, 2, 3]
                var b = isolated(a)
                b.add(99)
                std::println(a)
                std::println(b)
            }
        """.trimIndent()))
    }

    @Test fun isolatedDeepCopiesNestedStruct() {
        assertEquals("7\n1", run("""
            import std.io
            pack Box {
                var v: Int
            }
            func main() {
                var original = Box(1)
                var copy = isolated(original)
                copy.v = 7
                std::println(copy.v)
                std::println(original.v)
            }
        """.trimIndent()))
    }

    @Test fun pointerOpsEmitRuntimePreambleInBackends() {
        val result = Compiler().compile("""
            import std.io
            func main() {
                var p = alloc 5
                *p = 99
                std::println(*p)
            }
        """.trimIndent())
        assertIs<CompilationResult.Success>(result)
        // JavaScript backend must define the pointer runtime helpers it calls.
        assertTrue("__alloc" in result.javascript && "AzoraPtr" in result.javascript,
            "JavaScript should emit a pointer runtime preamble, got:\n${result.javascript}")
    }

    @Test fun zoneAllocFreesAtExit() {
        // `zone alloc { }` tracks allocations and frees them at exit.
        assertEquals("5\nnull", run("""
            import std.io
            func main() {
                var p: Int* = alloc 0
                zone alloc {
                    p = alloc 5
                    std::println(*p)
                }
                std::println(*p)
            }
        """.trimIndent()))
    }

    @Test fun friendZoneAllocFreesAtExit() {
        // `friend zone alloc { }` — arena scoping on top of shared friend scope.
        assertEquals("7\nnull", run("""
            import std.io
            func main() {
                var q: Int* = alloc 0
                friend zone alloc {
                    q = alloc 7
                    std::println(*q)
                }
                std::println(*q)
            }
        """.trimIndent()))
    }

    @Test fun pointerArithmeticOffsetAndDeref() {
        assertEquals("10\n20\n30", run("""
            import std.io
            func main() {
                var p: Int* = alloc arr@[10, 20, 30]
                std::println(*p)
                var p1 = p + 1
                std::println(*p1)
                std::println(*(p + 2))
            }
        """.trimIndent()))
    }

    @Test fun pointerArithmeticSubtract() {
        assertEquals("30\n20", run("""
            import std.io
            func main() {
                var p: Int* = alloc arr@[10, 20, 30]
                var end = p + 2
                std::println(*end)
                var back = end - 1
                std::println(*back)
            }
        """.trimIndent()))
    }

    @Test fun pointerArithmeticWriteThroughOffset() {
        assertEquals("99", run("""
            import std.io
            func main() {
                var p: Int* = alloc arr@[10, 20, 30]
                *(p + 1) = 99
                std::println(*(p + 1))
            }
        """.trimIndent()))
    }

    @Test fun pointerArithmeticDistance() {
        assertEquals("3", run("""
            import std.io
            func main() {
                var p: Int* = alloc arr@[10, 20, 30, 40]
                var q = p + 3
                std::println(q - p)
            }
        """.trimIndent()))
    }
}
