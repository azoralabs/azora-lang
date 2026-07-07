/*
 * Copyright 2026 AzoraTech
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.azora.lang.codegen

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.KotlinCodegen
import java.io.File
import kotlin.test.fail

/**
 * Test harness that lowers Azora source to Kotlin, compiles it with `kotlinc`
 * into a runnable jar, executes it with the current JVM, and returns the
 * program's standard output.
 *
 * Like [LlvmExec], the backend lowers the **un-optimized** IR (`result.ir`) —
 * the same IR the interpreter tests run against — so backend output can be
 * cross-checked against interpreter semantics.
 *
 * `kotlinc` start-up is expensive (seconds per compile), so keep the number
 * of tests using this harness small. If no `kotlinc` is present the harness
 * reports [available] as `false` and execution tests skip themselves.
 */
object KotlinExec {

    /** Absolute path to `kotlinc`, or `null` if it could not be located. */
    private val kotlinc: String? by lazy { findTool("kotlinc") }

    /** `true` when a `kotlinc` executable is available. */
    val available: Boolean get() = kotlinc != null

    private fun findTool(name: String): String? {
        val candidates = mutableListOf<String>()
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) candidates += "$dir/$name"
        }
        candidates += listOf(
            "/opt/homebrew/bin/$name",
            "/usr/local/bin/$name",
            "/usr/bin/$name",
        )
        return candidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
    }

    /** Compiles [source] and returns the generated Kotlin text. */
    fun compile(source: String): String {
        val result = Compiler().compile(source, release = false)
        if (result !is CompilationResult.Success) {
            val errors = (result as CompilationResult.Failure).errors
            fail("Compilation failed:\n${errors.joinToString("\n")}")
        }
        return KotlinCodegen().generate(result.ir)
    }

    /**
     * Compiles the generated Kotlin with `kotlinc -include-runtime`, runs the
     * resulting jar, and returns trimmed standard output. Fails the test on a
     * non-zero exit code (with generated source and stderr for debugging).
     */
    fun run(source: String): String {
        val tool = kotlinc ?: error("kotlinc not available")
        val kotlin = compile(source)

        val dir = File.createTempFile("azora_kt_", "").let {
            it.delete(); it.mkdirs(); it
        }
        try {
            val ktFile = File(dir, "Program.kt")
            ktFile.writeText(kotlin)
            val jar = File(dir, "program.jar")

            val compileProc = ProcessBuilder(tool, ktFile.absolutePath, "-include-runtime", "-d", jar.absolutePath, "-nowarn")
                .redirectErrorStream(false)
                .start()
            val compileErr = compileProc.errorStream.bufferedReader().readText()
            if (compileProc.waitFor() != 0) {
                fail("kotlinc failed:\n$compileErr\n--- Kotlin ---\n$kotlin")
            }

            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val runProc = ProcessBuilder(java, "-jar", jar.absolutePath).start()
            val stdout = runProc.inputStream.bufferedReader().readText()
            val stderr = runProc.errorStream.bufferedReader().readText()
            if (runProc.waitFor() != 0) {
                fail("java exited non-zero\n--- stderr ---\n$stderr\n--- Kotlin ---\n$kotlin")
            }
            return stdout.trimEnd('\n')
        } finally {
            dir.deleteRecursively()
        }
    }
}
