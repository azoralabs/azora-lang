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
 * Test harness that lowers Azora source to Python and executes it with
 * `python3`, returning the program's standard output.
 *
 * Like [LlvmExec], the backend lowers the **un-optimized** IR (`result.ir`) so
 * backend output can be cross-checked against interpreter semantics. Skips
 * itself when no `python3` is available.
 */
object PythonExec {

    private val python: String? by lazy { findTool("python3") ?: findTool("python") }

    val available: Boolean get() = python != null

    private fun findTool(name: String): String? {
        val candidates = mutableListOf<String>()
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) candidates += "$dir/$name"
        }
        candidates += listOf("/opt/homebrew/bin/$name", "/usr/local/bin/$name", "/usr/bin/$name")
        return candidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
    }

    fun compile(source: String): String {
        val result = Compiler().compile(source, release = false)
        if (result !is CompilationResult.Success) {
            fail("Compilation failed:\n${(result as CompilationResult.Failure).errors.joinToString("\n")}")
        }
        return PythonCodegen().generate(result.ir)
    }

    fun run(source: String): String {
        val tool = python ?: error("python not available")
        val py = compile(source)
        val file = File.createTempFile("azora_py_", ".py")
        try {
            file.writeText(py)
            val proc = ProcessBuilder(tool, file.absolutePath).start()
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            if (proc.waitFor() != 0) fail("python exited non-zero\n--- stderr ---\n$stderr\n--- Python ---\n$py")
            return stdout.trimEnd('\n')
        } finally {
            file.delete()
        }
    }
}
