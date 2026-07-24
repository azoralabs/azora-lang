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
import org.azora.lang.backend.LlvmCodegen
import java.io.File
import kotlin.test.fail

/**
 * Test harness that lowers Azora source to LLVM IR and executes it with the
 * LLVM interpreter (`lli`), returning the program's standard output.
 *
 * The LLVM backend lowers the **un-optimized** IR (`result.ir`) — the same IR
 * the arr@[org.azora.lang.backend.IrInterpreter] tests run against — so backend
 * output can be cross-checked against interpreter semantics without the
 * optimizer interfering.
 *
 * If no LLVM toolchain is present the harness reports arr@[available] as `false`
 * and execution tests skip themselves, so the suite stays green on machines
 * without LLVM installed.
 */
object LlvmExec {

    /** Absolute path to `lli`, or `null` if it could not be located. */
    private val lli: String? by lazy { findTool("lli") }

    /** `true` when an `lli` executable is available to run the IR. */
    val available: Boolean get() = lli != null

    private fun findTool(name: String): String? {
        val candidates = mutableListOf<String>()
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) candidates += "$dir/$name"
        }
        // Common Homebrew / system install locations.
        candidates += listOf(
            "/opt/homebrew/opt/llvm/bin/$name",
            "/usr/local/opt/llvm/bin/$name",
            "/opt/homebrew/bin/$name",
            "/usr/local/bin/$name",
            "/usr/bin/$name",
        )
        return candidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
    }

    /**
     * Compiles arr@[source] and returns the generated LLVM IR text.
     *
     * @param optimized when `true`, lowers the optimizer's output (release path);
     *   otherwise lowers the raw IR, matching the interpreter test convention.
     */
    fun compile(source: String, optimized: Boolean = false): String {
        val result = Compiler().compile(source, release = optimized)
        if (result !is CompilationResult.Success) {
            val errors = (result as CompilationResult.Failure).errors
            fail("Compilation failed:\n${errors.joinToString("\n")}")
        }
        return LlvmCodegen().generate(if (optimized) result.optimizedIr else result.ir)
    }

    /**
     * Compiles, runs the IR through `lli`, and returns trimmed standard output.
     *
     * Fails the test if `lli` returns a non-zero exit code (with the IR and
     * stderr attached for debugging).
     */
    fun run(source: String, optimized: Boolean = false): String {
        val tool = lli ?: error("lli not available")
        val ir = compile(source, optimized)

        val llFile = File.createTempFile("azora_", ".ll")
        val outFile = File.createTempFile("azora_out_", ".txt")
        val errFile = File.createTempFile("azora_err_", ".txt")
        try {
            llFile.writeText(ir)
            val proc = ProcessBuilder(tool, llFile.absolutePath)
                .redirectOutput(outFile)
                .redirectError(errFile)
                .start()
            val code = proc.waitFor()
            val stdout = outFile.readText()
            if (code != 0) {
                fail(
                    "lli exited with code $code\n" +
                        "--- stderr ---\n${errFile.readText()}\n" +
                        "--- IR ---\n$ir"
                )
            }
            return stdout.trimEnd('\n')
        } finally {
            llFile.delete()
            outFile.delete()
            errFile.delete()
        }
    }
}
