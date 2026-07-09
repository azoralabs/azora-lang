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
import org.azora.lang.backend.TypeScriptCodegen
import java.io.File
import kotlin.test.fail

/**
 * Test harness that lowers Azora source to TypeScript and executes it with
 * Node.js (which strips erasable TypeScript type annotations natively since
 * v22.6), returning the program's standard output.
 *
 * Like [LlvmExec], the backend lowers the **un-optimized** IR (`result.ir`) —
 * the same IR the interpreter tests run against — so backend output can be
 * cross-checked against interpreter semantics.
 *
 * If no suitable `node` (>= 22.6) is present the harness reports [available]
 * as `false` and execution tests skip themselves.
 */
object NodeExec {

    /** Absolute path to `node`, or `null` if it could not be located. */
    private val node: String? by lazy { findTool("node") }

    /** `true` when a Node.js able to run `.ts` files directly is available. */
    val available: Boolean by lazy { node != null && supportsTypeStripping() }

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

    private fun supportsTypeStripping(): Boolean {
        val tool = node ?: return false
        return runCatching {
            val proc = ProcessBuilder(tool, "--version").start()
            proc.waitFor()
            // "v23.6.0" -> 23.6 ; type stripping shipped in 22.6.
            val v = proc.inputStream.bufferedReader().readText().trim().removePrefix("v")
            val parts = v.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            major > 22 || (major == 22 && minor >= 6)
        }.getOrDefault(false)
    }

    /** Compiles [source] and returns the generated TypeScript text. */
    fun compile(source: String): String {
        val result = Compiler().compile(source, release = false)
        if (result !is CompilationResult.Success) {
            val errors = (result as CompilationResult.Failure).errors
            fail("Compilation failed:\n${errors.joinToString("\n")}")
        }
        return TypeScriptCodegen().generate(result.ir)
    }

    /**
     * Compiles, runs the TypeScript through `node`, and returns trimmed
     * standard output. Fails the test on a non-zero exit code (with the
     * generated source and stderr attached for debugging).
     */
    fun run(source: String): String {
        val tool = node ?: error("node not available")
        val ts = compile(source)

        val tsFile = File.createTempFile("azora_", ".ts")
        val outFile = File.createTempFile("azora_out_", ".txt")
        val errFile = File.createTempFile("azora_err_", ".txt")
        try {
            tsFile.writeText(ts)
            val proc = ProcessBuilder(tool, "--experimental-strip-types", "--no-warnings", tsFile.absolutePath)
                .redirectOutput(outFile)
                .redirectError(errFile)
                .start()
            val code = proc.waitFor()
            val stdout = outFile.readText()
            if (code != 0) {
                fail(
                    "node exited with code $code\n" +
                        "--- stderr ---\n${errFile.readText()}\n" +
                        "--- TypeScript ---\n$ts"
                )
            }
            return stdout.trimEnd('\n')
        } finally {
            tsFile.delete()
            outFile.delete()
            errFile.delete()
        }
    }
}
