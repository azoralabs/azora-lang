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
import org.azora.lang.backend.WasmCodegen
import java.io.File
import kotlin.test.fail

/**
 * Test harness that lowers Azora source to WebAssembly text (WAT), assembles it
 * to a `.wasm` binary with `wat2wasm` (from the `wabt` npm package, fetched via
 * `npx`), and runs it under Node.js — providing the `print_*` host imports and a
 * linear-memory string reader — returning the program's standard output.
 *
 * Skips itself when Node.js or the `wat2wasm` assembler are unavailable.
 */
object WasmExec {

    private val node: String? by lazy { findTool("node") }
    private val npx: String? by lazy { findTool("npx") }

    /** `true` when Node.js is present and the `wat2wasm` assembler can be invoked. */
    val available: Boolean by lazy { node != null && npx != null && wat2wasmWorks() }

    private fun findTool(name: String): String? {
        val candidates = mutableListOf<String>()
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) candidates += "$dir/$name"
        }
        candidates += listOf("/opt/homebrew/bin/$name", "/usr/local/bin/$name", "/usr/bin/$name")
        return candidates.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
    }

    private fun wat2wasmWorks(): Boolean = runCatching {
        val proc = ProcessBuilder(npx, "--yes", "-p", "wabt", "wat2wasm", "--version")
            .redirectErrorStream(true).start()
        proc.waitFor() == 0
    }.getOrDefault(false)

    private val driverJs = """
        const fs = require('fs');
        const bytes = fs.readFileSync(process.argv[2]);
        let mem;
        const dec = new TextDecoder();
        function readStr(ptr) {
          const dv = new DataView(mem.buffer);
          const len = dv.getInt32(ptr, true);
          return dec.decode(new Uint8Array(mem.buffer, ptr + 4, len));
        }
        const imports = { env: {
          print_i32: x => console.log(x),
          print_i64: x => console.log(x.toString()),
          print_f64: x => console.log(Number.isInteger(x) ? x.toFixed(1) : String(x)),
          print_f32: x => console.log(Number.isInteger(x) ? x.toFixed(1) : String(x)),
          print_bool: x => console.log(x ? "true" : "false"),
          print_str: ptr => console.log(readStr(ptr)),
          write_i32: x => process.stdout.write(String(x)),
          write_i64: x => process.stdout.write(x.toString()),
          write_f64: x => process.stdout.write(Number.isInteger(x) ? x.toFixed(1) : String(x)),
          write_f32: x => process.stdout.write(Number.isInteger(x) ? x.toFixed(1) : String(x)),
          write_bool: x => process.stdout.write(x ? "true" : "false"),
          write_str: ptr => process.stdout.write(readStr(ptr)),
        }};
        WebAssembly.instantiate(bytes, imports).then(({instance}) => {
          mem = instance.exports.memory;
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

    fun run(source: String): String {
        val nodeTool = node ?: error("node not available")
        val npxTool = npx ?: error("npx not available")
        val wat = compile(source)
        val dir = File.createTempFile("azora_wat_", "").let { it.delete(); it.mkdirs(); it }
        try {
            val watFile = File(dir, "program.wat")
            watFile.writeText(wat)
            val wasmFile = File(dir, "program.wasm")
            val driverFile = File(dir, "driver.js")
            driverFile.writeText(driverJs)

            val asmProc = ProcessBuilder(npxTool, "--yes", "-p", "wabt", "wat2wasm", watFile.absolutePath, "-o", wasmFile.absolutePath)
                .redirectErrorStream(true).start()
            val asmOut = asmProc.inputStream.bufferedReader().readText()
            if (asmProc.waitFor() != 0) fail("wat2wasm failed:\n$asmOut\n--- WAT ---\n$wat")

            val runProc = ProcessBuilder(nodeTool, driverFile.absolutePath, wasmFile.absolutePath).start()
            val stdout = runProc.inputStream.bufferedReader().readText()
            val stderr = runProc.errorStream.bufferedReader().readText()
            if (runProc.waitFor() != 0) fail("node exited non-zero\n--- stderr ---\n$stderr\n--- WAT ---\n$wat")
            return stdout.trimEnd('\n')
        } finally {
            dir.deleteRecursively()
        }
    }
}
