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
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * Test harness that lowers Azora source to LLVM IR and executes it with the
 * LLVM interpreter (`lli`), returning the program's standard output.
 *
 * The LLVM backend lowers the **un-optimized** IR (`result.ir`) - the same IR
 * the [org.azora.lang.backend.IrInterpreter] tests run against - so backend
 * output can be cross-checked against interpreter semantics without the
 * optimizer interfering.
 *
 * If no LLVM toolchain is present the harness reports [available] as `false`
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
     * Compiles [source] and returns the generated LLVM IR text.
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
        return runIr(compile(source, optimized))
    }

    /** How long one program may run before its test fails. */
    const val RUN_TIMEOUT_SECONDS = 60L

    /** Executes emitted IR, including isolated stage tests that need no stdlib. */
    fun runIr(ir: String): String = execute(ir) { code, stdout, stderr ->
        if (code != 0) {
            fail(
                "lli exited with code $code\n" +
                    "--- stderr ---\n$stderr\n" +
                    "--- IR ---\n$ir"
            )
        }
        stdout
    }

    /**
     * Compiles and runs a program that must stop itself - a panic or a failed
     * check - and returns what it printed, its reason included.
     */
    fun runExpectingAbort(source: String, optimized: Boolean = false): String {
        val ir = compile(source, optimized)
        return execute(ir) { code, stdout, _ ->
            if (code == 0) fail("expected the program to stop, but it finished\n--- stdout ---\n$stdout\n--- IR ---\n$ir")
            stdout
        }
    }

    private fun execute(ir: String, outcome: (code: Int, stdout: String, stderr: String) -> String): String {
        val tool = lli ?: error("lli not available")

        val llFile = File.createTempFile("azora_", ".ll")
        val outFile = File.createTempFile("azora_out_", ".txt")
        val errFile = File.createTempFile("azora_err_", ".txt")
        try {
            llFile.writeText(ir)
            val proc = ProcessBuilder(tool, llFile.absolutePath)
                .redirectOutput(outFile)
                .redirectError(errFile)
                .start()
            // A program that never ends fails its own test rather than the suite.
            if (!proc.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly().waitFor()
                fail("lli did not finish within $RUN_TIMEOUT_SECONDS s\n--- stdout ---\n${outFile.readText()}")
            }
            return outcome(proc.exitValue(), outFile.readText().trimEnd('\n'), errFile.readText())
        } finally {
            llFile.delete()
            outFile.delete()
            errFile.delete()
        }
    }
}
