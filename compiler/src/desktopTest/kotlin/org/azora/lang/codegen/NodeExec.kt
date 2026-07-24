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
import org.azora.lang.backend.JavaScriptCodegen
import java.io.File
import kotlin.test.fail

/**
 * Test harness that lowers Azora source to JavaScript and executes it with
 * Node.js, returning the program's standard output.
 *
 * Like [LlvmExec], the backend lowers the **un-optimized** IR (`result.ir`) —
 * the same IR the interpreter tests run against — so backend output can be
 * cross-checked against interpreter semantics.
 *
 * If no `node` is present the harness reports arr@[available] as `false` and
 * execution tests skip themselves.
 */
object NodeExec {

    /** Absolute path to `node`, or `null` if it could not be located. */
    private val node: String? by lazy { findTool("node") }

    /** `true` when a Node.js runtime is available. */
    val available: Boolean by lazy { node != null }

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

    /** Compiles arr@[source] and returns the generated JavaScript text. */
    fun compile(source: String): String {
        val result = Compiler().compile(source, release = false)
        if (result !is CompilationResult.Success) {
            val errors = (result as CompilationResult.Failure).errors
            fail("Compilation failed:\n${errors.joinToString("\n")}")
        }
        return JavaScriptCodegen().generate(result.ir)
    }

    /**
     * Compiles, runs the JavaScript through `node`, and returns trimmed
     * standard output. Fails the test on a non-zero exit code (with the
     * generated source and stderr attached for debugging).
     */
    fun run(source: String): String {
        val tool = node ?: error("node not available")
        val js = compile(source)

        val jsFile = File.createTempFile("azora_", ".js")
        val outFile = File.createTempFile("azora_out_", ".txt")
        val errFile = File.createTempFile("azora_err_", ".txt")
        try {
            jsFile.writeText(js)
            val proc = ProcessBuilder(tool, jsFile.absolutePath)
                .redirectOutput(outFile)
                .redirectError(errFile)
                .start()
            val code = proc.waitFor()
            val stdout = outFile.readText()
            if (code != 0) {
                fail(
                    "node exited with code $code\n" +
                        "--- stderr ---\n${errFile.readText()}\n" +
                        "--- JavaScript ---\n$js"
                )
            }
            return stdout.trimEnd('\n')
        } finally {
            jsFile.delete()
            outFile.delete()
            errFile.delete()
        }
    }
}
