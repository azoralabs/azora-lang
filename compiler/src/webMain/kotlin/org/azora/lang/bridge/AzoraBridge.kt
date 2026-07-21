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

@file:OptIn(kotlin.js.ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

package org.azora.lang.bridge

import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.LibrarySource
import org.azora.lang.backend.IrInterpreter

/**
 * JavaScript/WASM bridge for the playground (`code.azoralang.org`).
 *
 * Each exported function takes Azora source and returns a JSON string of the form
 * `{"success":Boolean,"output":String,"errors":String}`, matching the contract the
 * playground's `wasmLoader.js` expects (`az*` exports on the `globalThis.compiler` global).
 *
 * The compiler lowers one IR to JavaScript, WebAssembly, and LLVM IR, plus the
 * IR interpreter. Each codegen target has an `azGenerate*` export. Execution
 * (`azInterpret` / `azRunTests`) runs the suspend interpreter
 * ([IrInterpreter.interpretSuspend]). Wasm/JS cannot `runBlocking`, and exporting `suspend`
 * functions directly exposes Kotlin's continuation ABI, so the public bridge returns JavaScript
 * promises that resolve to plain JS strings.
 */
private const val AZORA_VERSION = "0.0.4"

private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

private fun withCompiledLibrary(
    source: String,
    libraryPath: String,
    librarySource: String,
    onSuccess: (CompilationResult.Success) -> String,
): String {
    val result = Compiler(listOf(LibrarySource(libraryPath, librarySource))).compile(source, release = false)
    return when (result) {
        is CompilationResult.Success -> json(true, onSuccess(result), "")
        is CompilationResult.Failure -> json(false, "", result.errors.joinToString("\n"))
    }
}

/** Decodes a concatenation of `<pathLength>:<path><sourceLength>:<source>` entries. */
private fun decodeLibraries(bundle: String): List<LibrarySource> {
    var cursor = 0
    fun readPart(): String {
        val colon = bundle.indexOf(':', cursor)
        require(colon >= cursor) { "invalid library bundle at offset $cursor" }
        val length = bundle.substring(cursor, colon).toIntOrNull()
            ?: error("invalid library length at offset $cursor")
        val start = colon + 1
        val end = start + length
        require(end <= bundle.length) { "truncated library bundle at offset $cursor" }
        cursor = end
        return bundle.substring(start, end)
    }

    val libraries = mutableListOf<LibrarySource>()
    while (cursor < bundle.length) libraries += LibrarySource(readPart(), readPart())
    return libraries
}

private fun withCompiledLibraries(
    source: String,
    libraryBundle: String,
    onSuccess: (CompilationResult.Success) -> String,
): String {
    val result = try {
        Compiler(decodeLibraries(libraryBundle)).compile(source, release = false)
    } catch (error: IllegalArgumentException) {
        return json(false, "", error.message ?: "invalid library bundle")
    }
    return when (result) {
        is CompilationResult.Success -> json(true, onSuccess(result), "")
        is CompilationResult.Failure -> json(false, "", result.errors.joinToString("\n"))
    }
}

/** [withCompiled] for `suspend` success callbacks (used by the interpreter entry points). */
private suspend fun withCompiledSuspend(source: String, onSuccess: suspend (CompilationResult.Success) -> String): String {
    val result = Compiler().compile(source, release = false)
    return when (result) {
        is CompilationResult.Success -> json(true, onSuccess(result), "")
        is CompilationResult.Failure -> json(false, "", result.errors.joinToString("\n"))
    }
}

private suspend fun withCompiledLibrarySuspend(
    source: String,
    libraryPath: String,
    librarySource: String,
    onSuccess: suspend (CompilationResult.Success) -> String,
): String {
    val result = Compiler(listOf(LibrarySource(libraryPath, librarySource))).compile(source, release = false)
    return when (result) {
        is CompilationResult.Success -> json(true, onSuccess(result), "")
        is CompilationResult.Failure -> json(false, "", result.errors.joinToString("\n"))
    }
}

private suspend fun withCompiledLibrariesSuspend(
    source: String,
    libraryBundle: String,
    onSuccess: suspend (CompilationResult.Success) -> String,
): String {
    val result = try {
        Compiler(decodeLibraries(libraryBundle)).compile(source, release = false)
    } catch (error: IllegalArgumentException) {
        return json(false, "", error.message ?: "invalid library bundle")
    }
    return when (result) {
        is CompilationResult.Success -> json(true, onSuccess(result), "")
        is CompilationResult.Failure -> json(false, "", result.errors.joinToString("\n"))
    }
}

private fun promisedJson(block: suspend () -> String): Promise<JsString> =
    Promise { resolve, _ ->
        bridgeScope.launch {
            val result = try {
                block()
            } catch (error: Throwable) {
                json(false, "", error.message ?: error.toString())
            }
            resolve(result.toJsString())
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
fun azInterpret(source: String): Promise<JsString> =
    promisedJson { withCompiledSuspend(source) {
        try { IrInterpreter().interpretSuspend(it.ir) } catch (e: Throwable) { "Runtime error: ${e.message ?: e.toString()}" }
    } }

/** Runs the program's `test` blocks (same path as [azInterpret], which runs tests after main). */
@JsExport
fun azRunTests(source: String): Promise<JsString> =
    promisedJson { withCompiledSuspend(source) {
        try { IrInterpreter().interpretSuspend(it.ir) } catch (e: Throwable) { "Runtime error: ${e.message ?: e.toString()}" }
    } }

/** Generates JavaScript source. */
@JsExport
fun azGenerateJavaScript(source: String): String =
    withCompiled(source) { it.javascript }

/** Generates LLVM IR text. */
@JsExport
fun azGenerateLlvmIr(source: String): String =
    withCompiled(source) { it.llvm }

/** Generates WebAssembly text (WAT). */
@JsExport
fun azGenerateWasm(source: String): String =
    withCompiled(source) { it.wasm }

/** Compiles Azora IR with one external library module available for imports. */
@JsExport
fun azPreprocessWithLibrary(source: String, libraryPath: String, librarySource: String): String =
    withCompiledLibrary(source, libraryPath, librarySource) { it.ir.prettyPrint() }

/** Interprets source with one external library module available for imports. */
@JsExport
fun azInterpretWithLibrary(source: String, libraryPath: String, librarySource: String): Promise<JsString> =
    promisedJson { withCompiledLibrarySuspend(source, libraryPath, librarySource) {
        try { IrInterpreter().interpretSuspend(it.ir) } catch (e: Throwable) { "Runtime error: ${e.message ?: e.toString()}" }
    } }

/** Runs tests with one external library module available for imports. */
@JsExport
fun azRunTestsWithLibrary(source: String, libraryPath: String, librarySource: String): Promise<JsString> =
    promisedJson { withCompiledLibrarySuspend(source, libraryPath, librarySource) {
        try { IrInterpreter().interpretSuspend(it.ir) } catch (e: Throwable) { "Runtime error: ${e.message ?: e.toString()}" }
    } }

/** Generates JavaScript with one external library module available for imports. */
@JsExport
fun azGenerateJavaScriptWithLibrary(source: String, libraryPath: String, librarySource: String): String =
    withCompiledLibrary(source, libraryPath, librarySource) { it.javascript }

/** Generates LLVM IR with one external library module available for imports. */
@JsExport
fun azGenerateLlvmIrWithLibrary(source: String, libraryPath: String, librarySource: String): String =
    withCompiledLibrary(source, libraryPath, librarySource) { it.llvm }

/** Generates WebAssembly text with one external library module available for imports. */
@JsExport
fun azGenerateWasmWithLibrary(source: String, libraryPath: String, librarySource: String): String =
    withCompiledLibrary(source, libraryPath, librarySource) { it.wasm }

/** Compiles Azora IR with an arbitrary encoded set of external library modules. */
@JsExport
fun azPreprocessWithLibraries(source: String, libraryBundle: String): String =
    withCompiledLibraries(source, libraryBundle) { it.ir.prettyPrint() }

/** Interprets source with an arbitrary encoded set of external library modules. */
@JsExport
fun azInterpretWithLibraries(source: String, libraryBundle: String): Promise<JsString> =
    promisedJson { withCompiledLibrariesSuspend(source, libraryBundle) {
        try { IrInterpreter().interpretSuspend(it.ir) } catch (e: Throwable) { "Runtime error: ${e.message ?: e.toString()}" }
    } }

/** Runs tests with an arbitrary encoded set of external library modules. */
@JsExport
fun azRunTestsWithLibraries(source: String, libraryBundle: String): Promise<JsString> =
    promisedJson { withCompiledLibrariesSuspend(source, libraryBundle) {
        try { IrInterpreter().interpretSuspend(it.ir) } catch (e: Throwable) { "Runtime error: ${e.message ?: e.toString()}" }
    } }

/** Generates JavaScript with an arbitrary encoded set of external library modules. */
@JsExport
fun azGenerateJavaScriptWithLibraries(source: String, libraryBundle: String): String =
    withCompiledLibraries(source, libraryBundle) { it.javascript }

/** Generates LLVM IR with an arbitrary encoded set of external library modules. */
@JsExport
fun azGenerateLlvmIrWithLibraries(source: String, libraryBundle: String): String =
    withCompiledLibraries(source, libraryBundle) { it.llvm }

/** Generates WebAssembly with an arbitrary encoded set of external library modules. */
@JsExport
fun azGenerateWasmWithLibraries(source: String, libraryBundle: String): String =
    withCompiledLibraries(source, libraryBundle) { it.wasm }
