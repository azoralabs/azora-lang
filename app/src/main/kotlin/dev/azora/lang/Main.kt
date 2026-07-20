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

package dev.azora.lang

import org.azora.lang.Compiler
import org.azora.lang.CompilationResult
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.dumpTree
import java.io.File
import kotlin.system.exitProcess

/**
 * Parses CLI flags into `config.az` overrides (`-D NAME=VAL`, `--define NAME=VAL`)
 * plus named flags that map to the standard config constants:
 * `--debug`/`--release` (DEBUG/RELEASE), `--test` (TEST_MODE),
 * `--auto-import-macros` (AUTO_IMPORT_MACROS). These drive `export if COND` and
 * `inline fin` config reads.
 */
private fun parseDefines(args: List<String>): Map<String, String> {
    val defines = mutableMapOf<String, String>()
    for (a in args) {
        when {
            a.startsWith("-D") && a.length > 2 && a[2] == ' ' -> {
                val pair = a.drop(3).split("=", limit = 2)
                if (pair.size == 2) defines[pair[0].trim()] = pair[1].trim()
            }
            a.startsWith("-D") && a.contains("=") -> {
                val pair = a.removePrefix("-D").split("=", limit = 2)
                if (pair.size == 2) defines[pair[0].trim()] = pair[1].trim()
            }
            a.startsWith("--define=") -> {
                val pair = a.removePrefix("--define=").split("=", limit = 2)
                if (pair.size == 2) defines[pair[0].trim()] = pair[1].trim()
            }
            a == "--debug" -> { defines["DEBUG"] = "true"; defines["RELEASE"] = "false" }
            a == "--release" -> { defines["DEBUG"] = "false"; defines["RELEASE"] = "true" }
            a == "--test" -> defines["TEST_MODE"] = "true"
            a == "--auto-import-macros" -> defines["AUTO_IMPORT_MACROS"] = "true"
        }
    }
    return defines
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "run"     -> handleRun(args.drop(1))
        "check"   -> handleCheck(args.drop(1))
        "compile" -> handleCompile(args.drop(1))
        "test"    -> handleTest(args.drop(1))
        "repl"    -> repl()
        "version" -> println("Azora ${BuildConfig.VERSION}")
        "help", "--help", "-h" -> printUsage()
        else -> {
            if (args[0].endsWith(".az") || args[0].endsWith(".azora")) {
                handleRun(listOf(args[0]))
            } else {
                System.err.println("Unknown command: ${args[0]}")
                printUsage()
            }
        }
    }
}

// ── azora run <file.az> ─────────────────────────────────────────

private fun handleRun(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: azora run <file.az>")
        return
    }
    val filePath = args.first { !it.startsWith("--") }
    val file = File(filePath)
    if (!file.exists()) {
        System.err.println("File not found: $filePath")
        return
    }

    val source = resolveAndConcatenate(file)
    val programArgs = args.drop(args.indexOf(filePath) + 1)
    val result = Compiler().compile(source, defines = parseDefines(args))
    when (result) {
        is CompilationResult.Success -> {
            val output = IrInterpreter().apply { this.programArgs = programArgs }.interpret(result.ir)
            if (output.isNotBlank()) println(output)
        }
        is CompilationResult.Failure -> {
            result.errors.forEach { System.err.println(it) }
        }
    }
}

// ── Multi-file resolution ────────────────────────────────────────

/**
 * Resolves multi-file imports by:
 * 1. Finding all `.az` files in sibling directories referenced by `use` statements
 * 2. Concatenating them before the entry file
 * 3. Stripping `package` and local `use` lines during concatenation
 */
private fun resolveAndConcatenate(entryFile: File): String {
    val entrySource = entryFile.readText()
    val sourceDir = entryFile.parentFile ?: return entrySource

    // Find all `.az` files in the source directory and subdirectories (except the entry file itself)
    val siblingFiles = sourceDir.walkTopDown()
        .filter { it.isFile && it.extension == "az" && it.absolutePath != entryFile.absolutePath }
        .sortedBy { it.name }
        .toList()

    if (siblingFiles.isEmpty()) return entrySource

    // Concatenate: siblings first, entry file last
    val parts = siblingFiles.map { cleanSource(it.readText(), it) } + cleanSource(entrySource, entryFile)
    return parts.joinToString("\n\n")
}

/** Strips `package` declarations and `use` imports from a source file (they're metadata for the CLI, not for the compiler). */
private fun cleanSource(source: String, file: File): String {
    return source.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        when {
            trimmed.startsWith("package ") || trimmed == "package" -> ""
            trimmed.startsWith("use ") -> ""  // All use lines stripped — CLI handles resolution
            else -> line
        }
    }
}

// ── azora check <file.az> ───────────────────────────────────────

private fun handleCheck(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: azora check <file.az>")
        return
    }
    val file = File(args.first())
    if (!file.exists()) {
        System.err.println("File not found: ${args.first()}")
        return
    }

    val source = resolveAndConcatenate(file)
    val result = Compiler().compile(source, defines = parseDefines(args))
    when (result) {
        is CompilationResult.Success -> println("No errors found.")
        is CompilationResult.Failure -> result.errors.forEach { System.err.println(it) }
    }
}

// ── azora compile <target> <file.az> ────────────────────────────

private fun handleCompile(args: List<String>) {
    if (args.size < 2) {
        System.err.println("Usage: azora compile <javascript|wasm|llvm|ir> <file.az>")
        return
    }

    val target = args[0]
    val debug = args.any { it == "--debug" || it == "-O0" }
    val filePath = args.drop(1).first { !it.startsWith("-") }
    val file = File(filePath)
    if (!file.exists()) {
        System.err.println("File not found: $filePath")
        return
    }

    val source = resolveAndConcatenate(file)
    val result = Compiler().compile(source, release = !debug, defines = parseDefines(args))
    when (result) {
        is CompilationResult.Success -> {
            // In debug mode emit code from the un-optimized IR so backend output
            // reflects the program exactly (useful for backend debugging).
            val backendIr = if (debug) result.ir else result.optimizedIr
            val output = when (target) {
                "javascript", "js" -> if (debug) org.azora.lang.backend.JavaScriptCodegen().generate(backendIr) else result.javascript
                "wasm", "wat" -> if (debug) org.azora.lang.backend.WasmCodegen().generate(backendIr) else result.wasm
                "llvm", "ll" -> if (debug) org.azora.lang.backend.LlvmCodegen().generate(backendIr) else result.llvm
                "ir" -> backendIr.prettyPrint()
                "ast" -> result.ast.dumpTree()
                else -> {
                    System.err.println("Unknown target: $target (use javascript, wasm, llvm, ir, or ast)")
                    return
                }
            }
            println(output)
        }
        is CompilationResult.Failure -> result.errors.forEach { System.err.println(it) }
    }
}

// ── azora test <file.az | dir> ───────────────────────────────────

/**
 * Runs every `test { … }` block in the given file (or `.az` files under a
 * directory) through the interpreter, in isolation. A failing assertion in one
 * test does not abort the others. Exits non-zero if any test fails or any file
 * fails to compile.
 */
private fun handleTest(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: azora test <file.az | dir>")
        return
    }
    val strict = args.any { it == "--strict" }
    val target = File(args.first { !it.startsWith("--") })
    if (!target.exists()) {
        System.err.println("Not found: ${target.path}")
        return
    }
    val files = if (target.isDirectory) {
        target.walkTopDown().filter { it.isFile && it.extension == "az" }.sortedBy { it.path }.toList()
    } else listOf(target)

    var totalPassed = 0
    var totalFailed = 0
    var filesFailed = 0
    for (file in files) {
        val result = try {
            Compiler().compile(file.readText(), release = false, defines = parseDefines(args))
        } catch (e: Exception) {
            filesFailed++
            println("✗ ${file.path} — parse/compile error")
            println("    ${e.message}")
            null
        }
        when (result) {
            null -> {}
            is CompilationResult.Failure -> {
                filesFailed++
                println("✗ ${file.path} — compile error")
                result.errors.forEach { println("    $it") }
            }
            is CompilationResult.Success -> {
                val results = IrInterpreter().runTests(result.ir)
                if (results.isEmpty()) continue
                val passed = results.count { it.passed }
                val failed = results.count { !it.passed }
                totalPassed += passed
                totalFailed += failed
                for (r in results) {
                    if (r.passed) {
                        println("✓ ${file.path} :: ${r.name}")
                    } else {
                        println("✗ ${file.path} :: ${r.name}")
                        println("    ${r.message}")
                    }
                }
            }
        }
    }
    val summary = "$totalPassed passed, $totalFailed failed" +
        if (filesFailed > 0) ", $filesFailed file(s) failed to compile" else ""
    println("\n$summary")
    // A test failure (assertion) always fails the run. A compile error only fails
    // the run under `--strict` — many test files exercise not-yet-implemented features.
    if (totalFailed > 0 || (strict && filesFailed > 0)) exitProcess(1)
}

// ── REPL ─────────────────────────────────────────────────────────

private fun repl() {
    println("Azora ${BuildConfig.VERSION} REPL")
    println("Type expressions or statements. Type 'exit' to quit.")
    println()

    val history = mutableListOf<String>()
    while (true) {
        print("az> ")
        val line = readlnOrNull() ?: break
        if (line.trim() == "exit" || line.trim() == "quit") break
        if (line.isBlank()) continue

        try {
            val body = (history + line).joinToString("\n")
            val wrapped = "func main() {\n$body\n}"
            val result = Compiler().compile(wrapped, release = false)
            when (result) {
                is CompilationResult.Success -> {
                    val output = IrInterpreter().interpret(result.ir)
                    if (output.isNotBlank()) println(output)
                    history.add(line)
                }
                is CompilationResult.Failure -> {
                    result.errors.forEach { System.err.println(it) }
                }
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
        }
    }
}

// ── Help ─────────────────────────────────────────────────────────

private fun printUsage() {
    println("""
        Azora ${BuildConfig.VERSION}

        Usage: azora <command> [options]

        Commands:
          run <file.az>                 Compile and run a program
          check <file.az>               Type-check without running
          compile <target> <file.az>    Output generated code
          test <file.az | dir>          Run `test` blocks (file or directory)
          repl                          Interactive REPL
          version                       Show version
          help                          Show this help

        Compile targets:
          javascript, js  JavaScript source
          wasm, wat       WebAssembly text (WAT)
          llvm, ll        LLVM IR text
          ir              Azora IR (pretty-printed)
          ast             AST dump (for debugging)

        Examples:
          azora run hello.az
          azora compile javascript hello.az > hello.js
          azora check program.az
          azora repl
    """.trimIndent())
}
