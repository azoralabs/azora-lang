/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every standard-library module compiles on its own and passes its own `test`
 * blocks, as `azora test std` runs them.
 *
 * A program reaches only the library declarations it names, so the rest of a
 * module - its other members, its derives, its tests - is never compiled by a
 * program that does not use it. A module that stopped compiling therefore
 * went unnoticed until someone imported the part that broke.
 */
@RunWith(Parameterized::class)
class StdlibSelfTest(private val module: String) {
    companion object {
        private val root = File("../std")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modules(): List<Array<Any>> = root.walkTopDown()
            .filter { it.isFile && it.extension == "az" }
            .map { it.relativeTo(root).path }
            .sorted()
            .map { arrayOf<Any>(it) }
            .toList()
    }

    @Test fun compilesAndPassesItsOwnTests() {
        val result = Compiler().compile(File(root, module).readText(), release = false)
        assertIs<CompilationResult.Success>(
            result,
            "std/$module does not compile on its own:\n" +
                (result as? CompilationResult.Failure)?.errors?.joinToString("\n"),
        )
        val failed = IrInterpreter().runTests(result.ir).filterNot { it.passed }
        assertTrue(failed.isEmpty(), failed.joinToString("\n") { "${it.name}: ${it.message}" })
    }
}
