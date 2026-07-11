/*
 * Copyright 2026 AzoraLabs
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
import java.io.File
import kotlin.test.fail

/**
 * Test harness that lowers Azora source to Dart and executes it with the `dart`
 * runtime (JIT), returning the program's standard output.
 *
 * Like [LlvmExec], the backend lowers the **un-optimized** IR (`result.ir`) —
 * the same IR the interpreter tests run against — so backend output can be
 * cross-checked against interpreter semantics.
 *
 * If no `dart` is present the harness reports [available] as `false` and
 * execution tests skip themselves.
 */
object DartExec {

    /** Absolute path to `dart`, or `null` if it could not be located. */
    private val dart: String? by lazy { findTool("dart") }

    /** `true` when a `dart` executable is available. */
    val available: Boolean get() = dart != null

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

    /** Compiles [source] and returns the generated Dart text. */
    fun compile(source: String): String {
        val result = Compiler().compile(source, release = false)
        if (result !is CompilationResult.Success) {
            val errors = (result as CompilationResult.Failure).errors
            fail("Compilation failed:\n${errors.joinToString("\n")}")
        }
        return DartCodegen().generate(result.ir)
    }

    /**
     * Compiles, runs the Dart through the `dart` runtime, and returns trimmed
     * standard output. Fails the test on a non-zero exit code (with the
     * generated source and stderr attached for debugging).
     */
    fun run(source: String): String {
        val tool = dart ?: error("dart not available")
        val dartSrc = compile(source)

        val dir = File.createTempFile("azora_dart_", "").let {
            it.delete(); it.mkdirs(); it
        }
        try {
            val file = File(dir, "program.dart")
            file.writeText(dartSrc)
            val proc = ProcessBuilder(tool, "run", "--no-analytics", file.absolutePath)
                .redirectErrorStream(false)
                .start()
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            if (proc.waitFor() != 0) {
                fail("dart exited non-zero\n--- stderr ---\n$stderr\n--- Dart ---\n$dartSrc")
            }
            return stdout.trimEnd('\n')
        } finally {
            dir.deleteRecursively()
        }
    }
}
