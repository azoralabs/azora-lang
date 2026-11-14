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
import org.azora.lang.backend.WasmAssembler
import org.azora.lang.backend.WasmAssemblyError
import org.azora.lang.backend.WasmCodegen
import org.azora.lang.backend.WasmHost
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * Test harness that lowers Azora source to WebAssembly text (WAT), assembles it
 * to a `.wasm` binary with the compiler's own [WasmAssembler], and runs it under
 * Node.js - providing the `print_*` host imports and a linear-memory string
 * reader - returning the program's standard output.
 *
 * Skips itself when Node.js is unavailable.
 */
object WasmExec {

    private val node: String? by lazy { findTool("node") }

    /** `true` when Node.js is present to run the assembled module. */
    val available: Boolean by lazy {
        val found = node != null
        check(found || System.getenv("AZORA_REQUIRE_NATIVE_TESTS") != "1") { "Native qualification requires node" }
        found
    }

    private fun findTool(name: String): String? {
        val candidates = mutableListOf<String>()
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) candidates += "$dir/$name"
        }
        candidates += listOf("/opt/homebrew/bin/$name", "/usr/local/bin/$name", "/usr/bin/$name")
        return candidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
    }


    /**
     * Runs the module under Node with the host the compiler generated for it
     * ([WasmHost]), so every test also exercises that glue. Tests may provide
     * further imports, which can read `mem`, the module's memory.
     */
    private val driverJs = """
        const fs = require('fs');
        const bytes = fs.readFileSync(process.argv[2]);
        let mem;
        const createHost = /*GLUE*/;
        const host = createHost((text) => process.stdout.write(text), {
          /*HOST_IMPORTS*/
        });
        WebAssembly.instantiate(bytes, host.imports).then(({instance}) => {
          mem = instance.exports.memory;
          host.attach(instance);
          instance.exports.main();
        }).catch(e => { console.error(e); process.exit(1); });
    """.trimIndent()

    fun compile(source: String): String {
        val result = Compiler().compile(source, release = false)
        if (result !is CompilationResult.Success) {
            fail("Compilation failed:\n${(result as CompilationResult.Failure).errors.joinToString("\n")}")
        }
        return WasmCodegen().generate(result.ir)
    }

    fun run(source: String): String = runWat(compile(source))

    /** Compiles and runs a program that must trap; returns what it printed first. */
    fun runExpectingTrap(source: String, trap: String = "unreachable"): String = runWatExpectingTrap(compile(source), trap)

    /**
     * Execute emitted WAT independently of standard-library loading.
     * [hostImports] adds entries to the host's `env` object, as JavaScript
     * (`name: (args) => …,`); they may use `mem` and `readStr`.
     */
    fun runWat(wat: String, hostImports: String = ""): String = execute(wat, hostImports) { exit, stdout, stderr ->
        if (exit != 0) fail("node exited non-zero\n--- stderr ---\n$stderr\n--- WAT ---\n$wat")
        stdout
    }

    /**
     * Execute WAT that must stop with the WebAssembly [trap] (for example
     * `unreachable`); returns what it printed before trapping.
     */
    fun runWatExpectingTrap(wat: String, trap: String = "unreachable", hostImports: String = ""): String = execute(wat, hostImports) { exit, stdout, stderr ->
        if (exit == 0) fail("expected a '$trap' trap, but the program finished\n--- stdout ---\n$stdout\n--- WAT ---\n$wat")
        if ("RuntimeError: $trap" !in stderr) fail("expected a '$trap' trap\n--- stderr ---\n$stderr\n--- WAT ---\n$wat")
        stdout
    }

    /** Execute WAT that must fail with an error whose text contains [message]; returns what it printed first. */
    fun runWatExpectingError(wat: String, message: String, hostImports: String = ""): String = execute(wat, hostImports) { exit, stdout, stderr ->
        if (exit == 0) fail("expected the program to fail, but it finished\n--- stdout ---\n$stdout")
        if (message !in stderr) fail("expected an error containing '$message'\n--- stderr ---\n$stderr")
        stdout
    }

    private fun execute(wat: String, hostImports: String = "", outcome: (exit: Int, stdout: String, stderr: String) -> String): String {
        val nodeTool = node ?: error("node not available")
        val dir = File.createTempFile("azora_wat_", "").let { it.delete(); it.mkdirs(); it }
        try {
            val watFile = File(dir, "program.wat")
            watFile.writeText(wat)
            val wasmFile = File(dir, "program.wasm")
            val driverFile = File(dir, "driver.js")
            driverFile.writeText(driverJs.replace("/*GLUE*/", WasmHost.glueFor(wat)).replace("/*HOST_IMPORTS*/", hostImports))

            try {
                wasmFile.writeBytes(WasmAssembler.assemble(wat))
            } catch (e: WasmAssemblyError) {
                fail("assembling the WAT failed: ${e.message}\n--- WAT ---\n$wat")
            }

            // `console.log(number)` *inspects* its argument, and node colours an
            // inspected number when it believes stdout is a terminal - which turns
            // `14` into `\u001b[33m14\u001b[39m` and fails a comparison that is
            // about the program, not about node. The driver prints strings, and
            // this says so a second time for anything that slips through.
            val outFile = File(dir, "stdout.txt")
            val errFile = File(dir, "stderr.txt")
            val runProc = ProcessBuilder(nodeTool, driverFile.absolutePath, wasmFile.absolutePath)
                .apply { environment()["NO_COLOR"] = "1"; environment()["FORCE_COLOR"] = "0" }
                .redirectOutput(outFile)
                .redirectError(errFile)
                .start()
            // A program that never ends fails its own test rather than the suite.
            if (!runProc.waitFor(LlvmExec.RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                runProc.destroyForcibly().waitFor()
                fail("node did not finish within ${LlvmExec.RUN_TIMEOUT_SECONDS} s\n--- stdout ---\n${outFile.readText()}")
            }
            return outcome(runProc.exitValue(), outFile.readText().trimEnd('\n'), errFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
