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

@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package org.azora.lang.bridge

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter

/**
 * JavaScript/WASM bridge for the playground (`code.azoralang.org`).
 *
 * Each exported function takes Azora source and returns a JSON string of the form
 * `{"success":Boolean,"output":String,"errors":String}`, matching the contract the
 * playground's `wasmLoader.js` expects (`az*` exports on the `globalThis.compiler` global).
 *
 * The new compiler has four backends — the IR interpreter, Kotlin, TypeScript, and LLVM IR.
 * C#, Python, and Swift codegen are not implemented; those exports report unsupported.
 */
private const val AZORA_VERSION = "0.0.1-alpha.2"

private fun json(success: Boolean, output: String, errors: String): String =
    "{\"success\":${success},\"output\":${jsonStr(output)},\"errors\":${jsonStr(errors)}}"

private fun jsonStr(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) when (c) {
        '\\' -> sb.append("\\\\")
        '"' -> sb.append("\\\"")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c.code < 0x20) sb.append("\\u${c.code.toString(16).padStart(4, '0')}") else sb.append(c)
    }
    sb.append('"')
    return sb.toString()
}

/** Compiles source; on success calls [onSuccess] to produce the output string. */
private fun withCompiled(source: String, onSuccess: (CompilationResult.Success) -> String): String {
    val result = Compiler().compile(source, release = false)
    return when (result) {
        is CompilationResult.Success -> json(true, onSuccess(result), "")
        is CompilationResult.Failure -> json(false, "", result.errors.joinToString("\n"))
    }
}

/** Version string shown in the playground. */
@JsExport
fun azGetVersion(): String = AZORA_VERSION

/** Returns the compiled Azora IR (pretty-printed) for the "Azora IR" tab. */
@JsExport
fun azPreprocess(source: String): String =
    withCompiled(source) { it.ir.prettyPrint() }

/** Interprets the source and returns program output (main then tests). */
@JsExport
fun azInterpret(source: String): String =
    withCompiled(source) {
        try { IrInterpreter().interpret(it.ir) } catch (e: Throwable) { "Runtime error: ${e.message ?: e.toString()}" }
    }

/** Runs the program's `test` blocks (same path as [azInterpret], which runs tests after main). */
@JsExport
fun azRunTests(source: String): String =
    withCompiled(source) {
        try { IrInterpreter().interpret(it.ir) } catch (e: Throwable) { "Runtime error: ${e.message ?: e.toString()}" }
    }

/** Generates Kotlin/JVM source. */
@JsExport
fun azGenerateKotlin(source: String): String =
    withCompiled(source) { it.kotlin }

/**
 * Generates JavaScript source. The new compiler's web backend is TypeScript; the playground's
 * JS runner strips the type annotations before executing, so the TypeScript output is returned.
 */
@JsExport
fun azGenerateJavaScript(source: String): String =
    withCompiled(source) { it.typescript }

/** Generates LLVM IR text. */
@JsExport
fun azGenerateLlvmIr(source: String): String =
    withCompiled(source) { it.llvm }

/** Generates C# / .NET source. */
@JsExport
fun azGenerateCSharp(source: String): String =
    withCompiled(source) { it.csharp }

/** Generates Python 3 source. */
@JsExport
fun azGeneratePython(source: String): String =
    withCompiled(source) { it.python }

/** Generates Rust source. */
@JsExport
fun azGenerateRust(source: String): String =
    withCompiled(source) { it.rust }

/** Generates WebAssembly text (WAT). */
@JsExport
fun azGenerateWasm(source: String): String =
    withCompiled(source) { it.wasm }

/** Generates Swift 6.3 source. */
@JsExport
fun azGenerateSwift(source: String): String =
    withCompiled(source) { it.swift }

/** Generates Dart source. */
@JsExport
fun azGenerateDart(source: String): String =
    withCompiled(source) { it.dart }
