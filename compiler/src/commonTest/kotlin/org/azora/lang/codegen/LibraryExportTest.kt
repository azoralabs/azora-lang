/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `compile --export`: a library a foreign host loads, with no `main`. */
class LibraryExportTest {
    private val library = """
        import std.io
        fin base = seed()
        threadlocal var counter: Int = 0
        func seed(): Int {
            println("loaded")
            return 40
        }
        func add(a: Int, b: Int): Int {
            counter += a + b
            return base + counter
        }
        func name(): String = "flex"
        func unused(): Int = 7
        func pair(): (Int, Int) = (1, 2)
    """.trimIndent()

    @Test fun exportsKeepTheirNamesAndTheirDependencies() {
        val result = Compiler(exports = setOf("add", "name")).compile(library)
        assertIs<CompilationResult.Success>(result)
        assertTrue("define i32 @add(i32 %arg.a, i32 %arg.b)" in result.llvm, result.llvm)
        assertTrue("define i8* @name()" in result.llvm, result.llvm)
        assertFalse("@unused(" in result.llvm, "a release library keeps only what its exports reach")
        assertFalse("define i32 @main" in result.llvm)
    }

    @Test fun theLoaderRunsTheGlobalInitializers() {
        val result = Compiler(exports = setOf("add")).compile(library)
        assertIs<CompilationResult.Success>(result)
        assertTrue("@llvm.global_ctors" in result.llvm, result.llvm)
        assertTrue("call void @__azora_init_globals()" in result.llvm.substringAfter("@__azora_library_init()"))
    }

    @Test fun aProgramWithoutExportsHasNoLoaderHook() {
        val result = Compiler().compile("$library\nfunc main() { add(1, 2) }")
        assertIs<CompilationResult.Success>(result)
        assertFalse("@llvm.global_ctors" in result.llvm)
    }

    @Test fun aSiblingModulesFunctionIsExportedUnderItsPlainName() {
        val sibling = org.azora.lang.LibrarySource("host/services.az", """
            module host.services
            func hostReply(text: String): Int = 1
        """.trimIndent())
        val result = Compiler(listOf(sibling), exports = setOf("hostReply")).compile("import host.services\nfunc main() { }")
        assertIs<CompilationResult.Success>(result)
        assertTrue("define i32 @hostReply(i8* %arg.text)" in result.llvm || "@hostReply = alias" in result.llvm, result.llvm)
    }

    @Test fun aLibraryGlobalThatIsOnlyWrittenIsStillInjected() {
        val sibling = org.azora.lang.LibrarySource("host/state.az", """
            module host.state
            threadlocal var root: String = ""
            func keep(value: String) {
                root = value
            }
        """.trimIndent())
        val result = Compiler(listOf(sibling)).compile("import host.state\nfunc main() { keep(\"azora\") }")
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors.toString())
    }

    @Test fun anExportMustBeACallableTopLevelFunction() {
        for ((export, reason) in listOf(
            "missing" to "declares no top-level function",
            "pair" to "the result must be",
            "main" to "no entry point",
        )) {
            val result = Compiler(exports = setOf(export)).compile("$library\nfunc main() { }")
            assertIs<CompilationResult.Failure>(result)
            assertTrue(result.errors.any { reason in it }, result.errors.toString())
        }
    }
}
