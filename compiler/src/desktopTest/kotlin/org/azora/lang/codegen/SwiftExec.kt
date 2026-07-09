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
import org.azora.lang.backend.SwiftCodegen
import java.io.File
import kotlin.test.fail

/**
 * Test harness that lowers Azora source to Swift 6.3, compiles it with `swiftc`
 * into a native executable, runs it, and returns the program's standard output.
 *
 * Like [LlvmExec], the backend lowers the **un-optimized** IR (`result.ir`) —
 * the same IR the interpreter tests run against — so backend output can be
 * cross-checked against interpreter semantics.
 *
 * The generated source is written as `main.swift` so top-level statements (the
 * program entry-point call) and top-level `await` are permitted.
 *
 * `swiftc` start-up is expensive (seconds per compile), so keep the number of
 * tests using this harness small. If no `swiftc` is present the harness reports
 * [available] as `false` and execution tests skip themselves.
 */
object SwiftExec {

    /** Absolute path to `swiftc`, or `null` if it could not be located. */
    private val swiftc: String? by lazy { findTool("swiftc") }

    /** `true` when a `swiftc` executable is available. */
    val available: Boolean get() = swiftc != null

    private fun findTool(name: String): String? {
        val candidates = mutableListOf<String>()
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) candidates += "$dir/$name"
        }
        candidates += listOf(
            "/usr/bin/$name",
            "/usr/local/bin/$name",
            "/opt/homebrew/bin/$name",
        )
        return candidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
    }

    /** Compiles [source] and returns the generated Swift text. */
    fun compile(source: String): String {
        val result = Compiler().compile(source, release = false)
        if (result !is CompilationResult.Success) {
            val errors = (result as CompilationResult.Failure).errors
            fail("Compilation failed:\n${errors.joinToString("\n")}")
        }
        return SwiftCodegen().generate(result.ir)
    }

    /**
     * Compiles the generated Swift with `swiftc`, runs the resulting binary, and
     * returns trimmed standard output. Fails the test on a non-zero exit code
     * (with the generated source and stderr attached for debugging).
     */
    fun run(source: String): String {
        val tool = swiftc ?: error("swiftc not available")
        val swift = compile(source)

        val dir = File.createTempFile("azora_sw_", "").let {
            it.delete(); it.mkdirs(); it
        }
        try {
            // Must be named main.swift for top-level statements to be permitted.
            val swFile = File(dir, "main.swift")
            swFile.writeText(swift)
            val bin = File(dir, "program")

            val compileProc = ProcessBuilder(
                tool, "-swift-version", "6", "-suppress-warnings",
                "-o", bin.absolutePath, swFile.absolutePath
            ).redirectErrorStream(false).start()
            val compileErr = compileProc.errorStream.bufferedReader().readText()
            if (compileProc.waitFor() != 0) {
                fail("swiftc failed:\n$compileErr\n--- Swift ---\n$swift")
            }

            val runProc = ProcessBuilder(bin.absolutePath).start()
            val stdout = runProc.inputStream.bufferedReader().readText()
            val stderr = runProc.errorStream.bufferedReader().readText()
            if (runProc.waitFor() != 0) {
                fail("program exited non-zero\n--- stderr ---\n$stderr\n--- Swift ---\n$swift")
            }
            return stdout.trimEnd('\n')
        } finally {
            dir.deleteRecursively()
        }
    }
}
