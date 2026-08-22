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
import org.azora.lang.LibrarySource
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.frontend.dumpTree
import java.io.File
import kotlin.system.exitProcess

/**
 * Parses CLI flags into `config.az` overrides (`-D NAME=VAL`, `--define NAME=VAL`)
 * plus named flags that map to the standard config constants:
 * `--debug`/`--release` (DEBUG/RELEASE), `--test` (`testMode`),
 * `--auto-import-macros` (`autoImportMacros`). These drive `export if COND` and
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
            a == "--test" -> defines["testMode"] = "true"
            a == "--auto-import-macros" -> defines["autoImportMacros"] = "true"
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

    val unit = resolveCompilation(file)
    val programArgs = args.drop(args.indexOf(filePath) + 1)
    val result = Compiler(unit.libraries).compile(unit.source, defines = parseDefines(args))
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
 * A compilation unit keeps the entry module separate and loads every sibling
 * `.az` file as an importable library module. Module declarations and imports
 * therefore reach the same resolver used by the browser and embedded compiler;
 * source files are never concatenated or stripped.
 */
private data class SourceCompilation(
    val source: String,
    val libraries: List<LibrarySource>,
)

/**
 * The directory a module's own path is relative to.
 *
 * A file declaring `module engine.ecs` sits at `engine/ecs/…` below its source
 * root, so climbing out of the directories its module path accounts for lands
 * on the root. Taking the file's own directory instead would work only for an
 * entry point at the top: checking or testing `engine/ecs/ecs.az` on its own
 * would search below `engine/ecs`, never find `engine/core`, and report every
 * imported type as undefined.
 *
 * The file name may or may not repeat the last module segment (`engine/ecs.az`
 * and `engine/ecs/ecs.az` both declare `module engine.ecs`), so a segment is
 * credited to the file name only when the enclosing directory has not already
 * claimed it. A file with no module declaration keeps its own directory.
 */
private fun sourceRootOf(entryFile: File, source: String): File? {
    val dir = entryFile.absoluteFile.parentFile ?: return null
    val declared = Regex("""(?m)^\s*module\s+([A-Za-z_][\w.]*)""").find(source)
        ?.groupValues?.get(1)
        ?: return dir

    val segments = declared.split('.')
    var current: File = dir
    var index = segments.lastIndex
    if (current.name != segments[index] && entryFile.absoluteFile.nameWithoutExtension == segments[index]) {
        index--
    }
    while (index >= 0 && current.name == segments[index]) {
        current = current.parentFile ?: return current
        index--
    }
    return current
}

private fun resolveCompilation(entryFile: File): SourceCompilation {
    val entrySource = entryFile.readText()
    // `File("main.az").parentFile` is null, so a bare relative entry path must be
    // absolutized first. Without this, sibling discovery silently finds nothing
    // and every cross-module `import` fails with "undefined function".
    val sourceDir = sourceRootOf(entryFile, entrySource)
        ?: return SourceCompilation(entrySource, emptyList())

    // Find all `.az` files in the source directory and subdirectories (except the entry file itself)
    val siblingFiles = sourceDir.walkTopDown()
        .filter {
            it.isFile &&
                it.extension == "az" &&
                it.absolutePath != entryFile.absolutePath &&
                it.name != "main.az"
        }
        .sortedBy { it.name }
        .toList()

    val libraries = siblingFiles.map { sibling ->
        LibrarySource(sibling.relativeTo(sourceDir).path, sibling.readText())
    }
    return SourceCompilation(entrySource, libraries)
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

    val unit = resolveCompilation(file)
    val result = Compiler(unit.libraries).compile(unit.source, defines = parseDefines(args))
    when (result) {
        is CompilationResult.Success -> println("No errors found.")
        is CompilationResult.Failure -> {
            result.errors.forEach { System.err.println(it) }
            exitProcess(1)
        }
    }
}

// ── azora compile <target> <file.az> ────────────────────────────

private fun handleCompile(args: List<String>) {
    if (args.size < 2) {
        System.err.println("Usage: azora compile <wasm|llvm|ir|ast> [--debug] [--file-only] <file.az>")
        return
    }

    val target = args[0]
    val debug = args.any { it == "--debug" || it == "-O0" }
    // `--file-only` answers "what did *this file* become", which is the
    // question an editor asks. Everything else in the dump is the standard
    // library the file reached, injected because it was referenced.
    val fileOnly = args.any { it == "--file-only" }
    val filePath = args.drop(1).first { !it.startsWith("-") }
    val file = File(filePath)
    if (!file.exists()) {
        System.err.println("File not found: $filePath")
        return
    }

    val unit = resolveCompilation(file)
    val result = Compiler(unit.libraries).compile(unit.source, release = !debug, defines = parseDefines(args))
    when (result) {
        is CompilationResult.Success -> {
            // In debug mode emit code from the un-optimized IR so backend output
            // reflects the program exactly (useful for backend debugging).
            val backendIr = if (debug) result.ir else result.optimizedIr
            val output = when (target) {
                "wasm", "wat" -> if (debug) org.azora.lang.backend.WasmCodegen().generate(backendIr) else result.wasm
                "llvm", "ll" -> if (debug) org.azora.lang.backend.LlvmCodegen().generate(backendIr) else result.llvm
                "ir" -> backendIr.prettyPrint(if (fileOnly) declaredNames(result.ast, unit.source) else emptySet())
                "ast" -> result.ast.dumpTree()
                else -> {
                    System.err.println("Unknown target: $target (use wasm, llvm, ir, or ast)")
                    return
                }
            }
            println(output)
        }
        is CompilationResult.Failure -> {
            result.errors.forEach { System.err.println(it) }
            exitProcess(1)
        }
    }
}

/**
 * The IR items [source] itself declares, by the name IR gives them.
 *
 * A file is compiled together with whatever standard library it reaches, so
 * most of what reaches IR was written by nobody here. The file's own AST says
 * what it declared; a member is named for its owner, which is the one place the
 * two spellings differ.
 */
private fun declaredNames(compiled: org.azora.lang.frontend.Program, source: String): Set<String> {
    val own = runCatching {
        org.azora.lang.frontend.Parser(org.azora.lang.frontend.Lexer(source).tokenize()).parse()
    }.getOrNull() ?: compiled
    val names = linkedSetOf<String>()
    for (item in own.items) {
        when (item) {
            is org.azora.lang.frontend.TopLevel.Func -> names += item.decl.name
            is org.azora.lang.frontend.TopLevel.Pack -> names += item.name
            is org.azora.lang.frontend.TopLevel.Enum -> names += item.name
            is org.azora.lang.frontend.TopLevel.FinDecl -> names += item.name
            is org.azora.lang.frontend.TopLevel.VarDecl -> names += item.name
            is org.azora.lang.frontend.TopLevel.LetDecl -> names += item.name
            is org.azora.lang.frontend.TopLevel.Test -> names += item.name
            is org.azora.lang.frontend.TopLevel.Impl -> item.methods.forEach { method ->
                // `impl ArrayList { func any }` reaches IR as `ArrayList_any`;
                // both spellings are kept so neither form is missed.
                names += method.name
                names += "${item.typeName}_${method.name}"
                names += "__${item.typeName}_${method.name}"
            }
            else -> Unit
        }
    }
    return names
}

// ── azora test <file.az | dir> ───────────────────────────────────

/**
 * Runs a file's `test` blocks as native code, one process per test.
 *
 * The LLVM backend already emits each block as `@test_<name>`; all that is
 * missing is an entry point, so a driver `main` is appended that runs the block
 * whose index it is given and nothing else. A failed assertion aborts the
 * process - which is exactly why each test gets its own: an abort ends one test
 * rather than the run, and the message the assertion printed is that process's
 * output.
 *
 * This is the path that proves a test against the code that actually ships.
 * The interpreter agrees with it on ordinary code but cannot call into native
 * bridges at all, so anything reaching one can only be checked here.
 *
 * Returns null when the toolchain is unavailable, so the caller can say so
 * rather than report tests as passing that never ran.
 */
private fun runTestsViaLlvm(
    file: File,
    llvm: String,
    testNames: List<String>,
    linkArgs: List<String>,
): List<TestOutcome>? {
    if (testNames.isEmpty()) return emptyList()
    val clang = findClang() ?: return null

    // The emitted symbols appear in declaration order, which is the order the
    // IR lists the tests in, so position pairs a symbol with its name.
    val symbols = Regex("""(?m)^define void @(test_[A-Za-z0-9_.$]+)\(\)""")
        .findAll(llvm).map { it.groupValues[1] }.toList()
    if (symbols.size != testNames.size) return null

    // The driver supplies the entry point; a program that has its own `main`
    // keeps it as an ordinary function rather than fighting over the symbol.
    val body = llvm.replace(Regex("""(?m)^define i32 @main\("""), "define i32 @__az_user_main(")
    val needsAtoi = !body.contains("declare i32 @atoi(")
    val driver = buildString {
        appendLine()
        if (needsAtoi) appendLine("declare i32 @atoi(i8*)")
        appendLine("define i32 @main(i32 %argc, i8** %argv) {")
        appendLine("entry:")
        appendLine("  %has = icmp sgt i32 %argc, 1")
        appendLine("  br i1 %has, label %pick, label %done")
        appendLine("pick:")
        appendLine("  %slot = getelementptr i8*, i8** %argv, i64 1")
        appendLine("  %arg = load i8*, i8** %slot")
        appendLine("  %idx = call i32 @atoi(i8* %arg)")
        append("  switch i32 %idx, label %done [")
        symbols.indices.forEach { append(" i32 $it, label %case$it") }
        appendLine(" ]")
        symbols.forEachIndexed { index, symbol ->
            appendLine("case$index:")
            appendLine("  call void @$symbol()")
            appendLine("  br label %done")
        }
        appendLine("done:")
        appendLine("  ret i32 0")
        appendLine("}")
    }

    val work = createTempDir("azora-test")
    try {
        val irFile = File(work, "tests.ll").apply { writeText(body + driver) }
        val exe = File(work, "tests.bin")
        val compile = ProcessBuilder(listOf(clang, irFile.absolutePath, "-o", exe.absolutePath) + linkArgs)
            .redirectErrorStream(true)
            .start()
        val compileLog = compile.inputStream.bufferedReader().readText()
        if (compile.waitFor() != 0) {
            return testNames.map { TestOutcome(it, false, "native build failed: ${compileLog.trim().take(400)}") }
        }
        return testNames.mapIndexed { index, name ->
            val run = ProcessBuilder(exe.absolutePath, index.toString())
                .redirectErrorStream(true)
                .start()
            val output = run.inputStream.bufferedReader().readText()
            val code = run.waitFor()
            if (code == 0) TestOutcome(name, true, null)
            else TestOutcome(name, false, output.trim().ifEmpty { "aborted with exit code $code" })
        }
    } finally {
        work.deleteRecursively()
    }
}

/** One test's verdict, from whichever backend ran it. */
private data class TestOutcome(val name: String, val passed: Boolean, val message: String?)

private fun findClang(): String? {
    for (candidate in listOf("clang", "cc")) {
        val found = runCatching {
            val p = ProcessBuilder("which", candidate).redirectErrorStream(true).start()
            val path = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && path.isNotEmpty()) path else null
        }.getOrNull()
        if (found != null) return found
    }
    return null
}

private fun createTempDir(prefix: String): File =
    File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }

/**
 * Runs every `test { … }` block in the given file (or `.az` files under a
 * directory) in isolation. A failing assertion in one test does not abort the
 * others. Exits non-zero if any test fails or any file fails to compile.
 *
 * `--llvm` runs the blocks as native code instead of through the interpreter -
 * the only way to cover code that calls a native bridge. `--link <arg>` passes
 * an argument through to the linker, for tests whose bridges live in a runtime
 * library.
 */
private fun handleTest(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: azora test <file.az | dir>")
        return
    }
    val strict = args.any { it == "--strict" }
    val native = args.any { it == "--llvm" }
    val linkArgs = args.zipWithNext().filter { it.first == "--link" }.map { it.second }
    val positional = args.filterIndexed { i, a ->
        !a.startsWith("--") && !(i > 0 && args[i - 1] == "--link")
    }
    if (positional.isEmpty()) {
        System.err.println("Usage: azora test <file.az | dir> [--llvm] [--link <arg>]")
        return
    }
    val target = File(positional.first())
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
            val unit = resolveCompilation(file)
            Compiler(unit.libraries).compile(
                unit.source,
                release = false,
                defines = parseDefines(args),
            )
        } catch (e: Exception) {
            filesFailed++
            println("✗ ${file.path} - parse/compile error")
            println("    ${e.message}")
            null
        }
        when (result) {
            null -> {}
            is CompilationResult.Failure -> {
                filesFailed++
                println("✗ ${file.path} - compile error")
                result.errors.forEach { println("    $it") }
            }
            is CompilationResult.Success -> {
                val results = if (native) {
                    val names = result.ir.tests.map { it.name }
                    runTestsViaLlvm(file, result.llvm, names, linkArgs) ?: run {
                        filesFailed++
                        println("✗ ${file.path} - cannot run natively (no clang, or the IR has no test symbols)")
                        continue
                    }
                } else {
                    IrInterpreter().runTests(result.ir)
                        .map { TestOutcome(it.name, it.passed, it.message) }
                }
                if (results.isEmpty()) continue
                totalPassed += results.count { it.passed }
                totalFailed += results.count { !it.passed }
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
    // the run under `--strict` - many test files exercise not-yet-implemented features.
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
          wasm, wat       WebAssembly text (WAT)
          llvm, ll        LLVM IR text
          ir              Azora IR (pretty-printed)
          ast             AST dump (for debugging)

        Examples:
          azora run hello.az
          azora compile llvm hello.az > hello.ll
          azora check program.az
          azora repl
    """.trimIndent())
}
