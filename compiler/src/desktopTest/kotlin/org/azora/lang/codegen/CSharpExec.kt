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
import org.azora.lang.backend.CSharpCodegen
import java.io.File
import kotlin.test.fail

/**
 * Test harness that lowers Azora source to C#, compiles it with the Roslyn
 * `csc` compiler into an assembly, runs it with `mono`, and returns the
 * program's standard output.
 *
 * Like [LlvmExec], the backend lowers the **un-optimized** IR (`result.ir`) —
 * the same IR the interpreter tests run against — so backend output can be
 * cross-checked against interpreter semantics.
 *
 * If no `csc`/`mono` pair is present the harness reports [available] as `false`
 * and execution tests skip themselves.
 */
object CSharpExec {

    private val csc: String? by lazy { findTool("csc") }
    private val mono: String? by lazy { findTool("mono") }

    /** `true` when both `csc` and `mono` executables are available. */
    val available: Boolean get() = csc != null && mono != null

    private fun findTool(name: String): String? {
        val candidates = mutableListOf<String>()
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) candidates += "$dir/$name"
        }
        candidates += listOf(
            "/Library/Frameworks/Mono.framework/Versions/Current/Commands/$name",
            "/usr/local/bin/$name",
            "/usr/bin/$name",
            "/opt/homebrew/bin/$name",
        )
        return candidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
    }

    /** Compiles [source] and returns the generated C# text. */
    fun compile(source: String): String {
        val result = Compiler().compile(source, release = false)
        if (result !is CompilationResult.Success) {
            val errors = (result as CompilationResult.Failure).errors
            fail("Compilation failed:\n${errors.joinToString("\n")}")
        }
        return CSharpCodegen().generate(result.ir)
    }

    /**
     * Compiles the generated C# with `csc`, runs the assembly with `mono`, and
     * returns trimmed standard output. Fails the test on a non-zero exit code
     * (with the generated source and stderr attached for debugging).
     */
    fun run(source: String): String {
        val cscTool = csc ?: error("csc not available")
        val monoTool = mono ?: error("mono not available")
        val csharp = compile(source)

        val dir = File.createTempFile("azora_cs_", "").let {
            it.delete(); it.mkdirs(); it
        }
        try {
            val csFile = File(dir, "Program.cs")
            csFile.writeText(csharp)
            val exe = File(dir, "Program.exe")

            val compileProc = ProcessBuilder(
                cscTool, "-nologo", "-warn:0", "-out:${exe.absolutePath}", csFile.absolutePath
            ).redirectErrorStream(false).start()
            val compileErr = compileProc.errorStream.bufferedReader().readText()
            val compileOut = compileProc.inputStream.bufferedReader().readText()
            if (compileProc.waitFor() != 0) {
                fail("csc failed:\n$compileOut\n$compileErr\n--- C# ---\n$csharp")
            }

            val runProc = ProcessBuilder(monoTool, exe.absolutePath).start()
            val stdout = runProc.inputStream.bufferedReader().readText()
            val stderr = runProc.errorStream.bufferedReader().readText()
            if (runProc.waitFor() != 0) {
                fail("mono exited non-zero\n--- stderr ---\n$stderr\n--- C# ---\n$csharp")
            }
            return stdout.trimEnd('\n')
        } finally {
            dir.deleteRecursively()
        }
    }
}
