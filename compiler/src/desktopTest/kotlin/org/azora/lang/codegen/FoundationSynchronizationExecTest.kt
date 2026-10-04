package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FoundationSynchronizationExecTest {
    @Test fun concurrentFetchAddDoesNotLoseUpdates() {
        val source = """
            import std.io
            import std.parallelism.sync
            async func main() {
                let count: Atomic = .(0)
                fin a = async [count.&] {
                    for i in 0..<10000 { count.add(1) }
                }
                fin b = async [count.&] {
                    for i in 0..<10000 { count.add(1) }
                }
                await a
                await b
                println(count.load())
                purge count
            }
        """.trimIndent()
        val r = assertIs<CompilationResult.Success>(Compiler().compile(source))
        assertEquals("20000", IrInterpreter().interpret(r.ir).trim())
        for (release in listOf(false, true)) {
            if (LlvmExec.available) assertEquals("20000", LlvmExec.run(source, release))
        }
        assertTrue(r.backendErrors["wasm"]?.contains("atomic") == true, r.backendErrors.toString())
    }

    @Test fun deferUnlocksOnAnEarlyReturn() {
        val source = """
            import std.io
            import std.parallelism.sync
            func work(lock: Mutex&): Int {
                lock.lock()
                defer { lock.unlock() }
                return 7
            }
            func main() {
                let lock: Mutex = .()
                println(work(lock))
                println(work(lock))
                purge lock
            }
        """.trimIndent()
        val r = assertIs<CompilationResult.Success>(Compiler().compile(source))
        assertEquals("7\n7", IrInterpreter().interpret(r.ir).trim())
        for (release in listOf(false, true)) {
            if (LlvmExec.available) assertEquals("7\n7", LlvmExec.run(source, release))
        }
    }

    @Test fun purgeCannotDestroyABorrow() {
        val r = assertIs<CompilationResult.Failure>(Compiler().compile("""
            pack Owner { var p: Int^ = alloc^ 0 }
            impl Owner { dtor .() { purge self.p } }
            func bad(owner: Owner&) { purge owner }
        """.trimIndent()))
        assertTrue(r.errors.any { "borrow" in it }, r.errors.toString())
    }
    @Test fun explicitPurgeRunsEachOwnedDestructorOnce() {
        val source = """
            import std.io
            pack Child { var value: Int = 0 }
            impl Child { dtor .() { println("child") } }
            pack Parent { var child: Child = .() }
            impl Parent { dtor .() {
                purge self.child
                println("parent")
            } }
            func main() {
                let parent: Parent = .()
                purge parent
            }
        """.trimIndent()
        val r = assertIs<CompilationResult.Success>(Compiler().compile(source))
        assertEquals("child\nparent", IrInterpreter().interpret(r.ir).trim())
        for (release in listOf(false, true)) {
            if (LlvmExec.available) assertEquals("child\nparent", LlvmExec.run(source, release))
        }
        if (WasmExec.available) assertEquals("child\nparent", WasmExec.runWat(r.wasm).trim())
    }

    @Test fun aTakeInsidePrintIsResolvedExactlyOnce() {
        val source = """
            import std.io
            pack Buffer { var size: Int }
            func consume(buffer: Buffer): Int { return buffer.size }
            func main() {
                var buffer = Buffer(64)
                println(consume(take buffer))
            }
        """.trimIndent()
        val r = assertIs<CompilationResult.Success>(Compiler().compile(source))
        assertEquals("64", IrInterpreter().interpret(r.ir).trim())
        if (LlvmExec.available) assertEquals("64", LlvmExec.run(source))
    }

    @Test fun indexingCannotWriteThroughAReadOnlyPointer() {
        val r = assertIs<CompilationResult.Failure>(Compiler().compile("""
            func main() {
                let p: Int* = alloc* 1
                p[0] = 2
            }
        """.trimIndent()))
        assertTrue(r.errors.any { "cannot write through 'Int*'" in it }, r.errors.toString())
    }

    @Test fun mutablePointerCanProvideAReadOnlyView() {
        val source = """
            import std.io
            func read(p: Int*): Int { return p[0] }
            func main() {
                let p: Int^ = alloc^ 42
                println(read(p))
                purge p
            }
        """.trimIndent()
        val r = assertIs<CompilationResult.Success>(Compiler().compile(source))
        assertEquals("42", IrInterpreter().interpret(r.ir).trim())
        if (LlvmExec.available) assertEquals("42", LlvmExec.run(source))
        if (WasmExec.available) assertEquals("42", WasmExec.runWat(r.wasm).trim())
    }

    @Test fun readOnlyPointerCannotAcquireWriteCapability() {
        for (source in listOf(
            "func bad(p: Int*): Int^ { return p }",
            "func bad(p: Int^^): Int*^ { return p }",
        )) {
            val r = assertIs<CompilationResult.Failure>(Compiler().compile(source))
            assertTrue(r.errors.any { "return type mismatch" in it }, r.errors.toString())
        }
    }

    @Test fun sharedClonesUseOneCounterAndSurviveOriginalRelease() {
        for (module in listOf("shared", "syncshared")) {
            val factory = if (module == "shared") "sharedOf" else "syncSharedOf"
            val source = """
                import std.io
                import std.memory.$module
                func main() {
                    var first = $factory(42)
                    var second = first.clone()
                    println(first.refCount)
                    println(second.refCount)
                    println(first.release())
                    println(second.get)
                    println(second.refCount)
                    purge first
                    purge second
                }
            """.trimIndent()
            val r = assertIs<CompilationResult.Success>(Compiler().compile(source))
            assertEquals("2\n2\n1\n42\n1", IrInterpreter().interpret(r.ir).trim())
            for (release in listOf(false, true)) {
                if (LlvmExec.available) assertEquals("2\n2\n1\n42\n1", LlvmExec.run(source, release))
            }
            if (module == "shared" && WasmExec.available) {
                assertEquals("2\n2\n1\n42\n1", WasmExec.runWat(r.wasm).trim())
            }
        }
    }

    @Test fun syncSharedCountersRemainConsistentAcrossMovedTaskOwners() {
        val source = """
            import std.io
            import std.memory.syncshared
            async func main() {
                var owner = syncSharedOf(42)
                var left = owner.clone()
                var right = owner.clone()
                fin a = async [take left] {
                    var local = take left
                    for i in 0..<10000 {
                        local.retain()
                        local.release()
                    }
                    purge local
                }
                fin b = async [take right] {
                    var local = take right
                    for i in 0..<10000 {
                        local.retain()
                        local.release()
                    }
                    purge local
                }
                await a
                await b
                println(owner.refCount)
                println(owner.get)
                purge owner
            }
        """.trimIndent()
        val r = assertIs<CompilationResult.Success>(Compiler().compile(source))
        assertEquals("1\n42", IrInterpreter().interpret(r.ir).trim())
        for (release in listOf(false, true)) {
            if (LlvmExec.available) assertEquals("1\n42", LlvmExec.run(source, release))
        }
    }

    @Test fun pointerWritesCheckBothIndexAndStoredValue() {
        for (assignment in listOf("p[0] = \"bad\"", "p.^ = \"bad\"", "p[\"bad\"] = 42", "p[0] = null")) {
            val r = assertIs<CompilationResult.Failure>(Compiler().compile("""
                func main() {
                    var p: Int^ = alloc^ 0
                    $assignment
                }
            """.trimIndent()))
            assertTrue(r.errors.any { "cannot assign" in it || "pointer index must" in it }, r.errors.toString())
        }
    }

    @Test fun readonlyPointersCannotMutatePackFieldsThroughImplicitOrExplicitDereference() {
        for (assignment in listOf("p.value = 2", "p.*.value = 2")) {
            val r = assertIs<CompilationResult.Failure>(Compiler().compile("""
                pack Box { var value: Int }
                func main() {
                    let p: Box* = alloc Box(1)
                    $assignment
                }
            """.trimIndent()))
            assertTrue(r.errors.any { "cannot write through" in it }, r.errors.toString())
        }
    }

}
