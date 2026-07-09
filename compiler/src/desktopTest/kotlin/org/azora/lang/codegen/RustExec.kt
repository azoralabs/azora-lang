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
import org.azora.lang.backend.RustCodegen
import java.io.File
import kotlin.test.fail

/**
 * Test harness that lowers Azora source to Rust, compiles it with `rustc`, runs
 * the binary, and returns the program's standard output.
 *
 * Like [LlvmExec], the backend lowers the **un-optimized** IR (`result.ir`) so
 * backend output can be cross-checked against interpreter semantics. `rustc`
 * start-up is slow, so keep this suite compact; it skips itself when no `rustc`
 * is available.
 */
object RustExec {

    private val rustc: String? by lazy { findTool("rustc") }

    val available: Boolean get() = rustc != null

    private fun findTool(name: String): String? {
        val candidates = mutableListOf<String>()
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) candidates += "$dir/$name"
        }
        candidates += listOf(
            "${System.getProperty("user.home")}/.cargo/bin/$name",
            "/opt/homebrew/bin/$name", "/usr/local/bin/$name", "/usr/bin/$name",
        )
        return candidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
    }

    fun compile(source: String): String {
        val result = Compiler().compile(source, release = false)
        if (result !is CompilationResult.Success) {
            fail("Compilation failed:\n${(result as CompilationResult.Failure).errors.joinToString("\n")}")
        }
        return RustCodegen().generate(result.ir)
    }

    fun run(source: String): String {
        val tool = rustc ?: error("rustc not available")
        val rust = compile(source)
        val dir = File.createTempFile("azora_rs_", "").let { it.delete(); it.mkdirs(); it }
        try {
            val rsFile = File(dir, "program.rs")
            rsFile.writeText(rust)
            val bin = File(dir, "program")
            val compileProc = ProcessBuilder(tool, "-A", "warnings", "-o", bin.absolutePath, rsFile.absolutePath)
                .redirectErrorStream(false).start()
            val compileErr = compileProc.errorStream.bufferedReader().readText()
            if (compileProc.waitFor() != 0) fail("rustc failed:\n$compileErr\n--- Rust ---\n$rust")

            val runProc = ProcessBuilder(bin.absolutePath).start()
            val stdout = runProc.inputStream.bufferedReader().readText()
            val stderr = runProc.errorStream.bufferedReader().readText()
            if (runProc.waitFor() != 0) fail("program exited non-zero\n--- stderr ---\n$stderr\n--- Rust ---\n$rust")
            return stdout.trimEnd('\n')
        } finally {
            dir.deleteRecursively()
        }
    }
}
