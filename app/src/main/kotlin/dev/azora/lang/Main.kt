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

package dev.azora.lang

import org.azora.lang.Compiler
import org.azora.lang.CompilationResult
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.dumpTree
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "run"     -> handleRun(args.drop(1))
        "check"   -> handleCheck(args.drop(1))
        "compile" -> handleCompile(args.drop(1))
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
    val result = Compiler().compile(source)
    when (result) {
        is CompilationResult.Success -> {
            val output = IrInterpreter().interpret(result.ir)
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
    val result = Compiler().compile(source)
    when (result) {
        is CompilationResult.Success -> println("No errors found.")
        is CompilationResult.Failure -> result.errors.forEach { System.err.println(it) }
    }
}

// ── azora compile <target> <file.az> ────────────────────────────

private fun handleCompile(args: List<String>) {
    if (args.size < 2) {
        System.err.println("Usage: azora compile <kotlin|typescript|swift|llvm|ir> <file.az>")
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
    val result = Compiler().compile(source, release = !debug)
    when (result) {
        is CompilationResult.Success -> {
            // In debug mode emit code from the un-optimized IR so backend output
            // reflects the program exactly (useful for backend debugging).
            val backendIr = if (debug) result.ir else result.optimizedIr
            val output = when (target) {
                "kotlin", "kt" -> if (debug) org.azora.lang.backend.KotlinCodegen().generate(backendIr) else result.kotlin
                "typescript", "ts" -> if (debug) org.azora.lang.backend.TypeScriptCodegen().generate(backendIr) else result.typescript
                "swift", "sw" -> if (debug) org.azora.lang.backend.SwiftCodegen().generate(backendIr) else result.swift
                "llvm", "ll" -> if (debug) org.azora.lang.backend.LlvmCodegen().generate(backendIr) else result.llvm
                "ir" -> backendIr.prettyPrint()
                "ast" -> result.ast.dumpTree()
                else -> {
                    System.err.println("Unknown target: $target (use kotlin, typescript, swift, llvm, ir, or ast)")
                    return
                }
            }
            println(output)
        }
        is CompilationResult.Failure -> result.errors.forEach { System.err.println(it) }
    }
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
          repl                          Interactive REPL
          version                       Show version
          help                          Show this help

        Compile targets:
          kotlin, kt      Kotlin/JVM source
          typescript, ts  TypeScript source
          swift, sw       Swift 6.3 source
          llvm, ll        LLVM IR text
          ir              Azora IR (pretty-printed)
          ast             AST dump (for debugging)

        Examples:
          azora run hello.az
          azora compile kotlin hello.az > hello.kt
          azora check program.az
          azora repl
    """.trimIndent())
}
