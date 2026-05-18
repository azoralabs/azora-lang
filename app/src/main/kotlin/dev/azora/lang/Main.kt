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

import org.azora.lang.analyzer.SemanticAnalyzer
import org.azora.lang.interpreter.AzInterpreter
import org.azora.lang.lexer.Lexer
import org.azora.lang.parser.Parser
import org.azora.lang.preprocessor.AzPreprocessor
import org.azora.lang.stdlib.AzStdlib
import org.azora.lang.runtime.ConsoleMessageType
import org.azora.lang.runtime.ConsoleOutputManager
import dev.azora.build.*
import kotlinx.coroutines.runBlocking
import java.io.File

// ── CLI Entry Point ──────────────────────────────────────────────

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "run"     -> handleRun(args.drop(1))
        "build"   -> handleBuild(args.drop(1))
        "create"  -> handleCreate(args.drop(1))
        "check"   -> handleCheck(args.drop(1))
        "repl"    -> repl()
        "init"    -> handleInit(args.drop(1))
        "serve"   -> handleServe(args.drop(1))
        "test"    -> handleTest(args.drop(1))
        "clean"   -> handleClean()
        "version" -> println("Azora ${BuildConfig.VERSION}")
        "help", "--help", "-h" -> printUsage()
        else -> {
            // Bare .az file → interpret it
            if (args[0].endsWith(".az") || args[0].endsWith(".azora")) {
                handleRun(listOf("--script", args[0]))
            } else {
                System.err.println("Unknown command: ${args[0]}")
                printUsage()
            }
        }
    }
}

// ── Flag Parsing ─────────────────────────────────────────────────

private data class CliFlags(
    val script: Boolean = false,
    val target: String? = null,
    val platform: String? = null,  // sub-platform for kmp: desktop, android, ios, web
    val hook: String? = null,
    val file: String? = null,
    val extraArgs: List<String> = emptyList()
)

private val TARGET_FLAGS = mapOf(
    "--javascript" to "web-js",     "--js" to "web-js",
    "--kotlin"     to "kotlin-jvm", "--kt" to "kotlin-jvm",
    "--kmp"        to "kmp",
    "--csharp"     to "csharp",     "--cs" to "csharp",
    "--maui"       to "csharp",
    "--python"     to "python",     "--py" to "python",
    "--swift"      to "swift",
    "--dart"       to "dart",
    "--flutter"    to "flutter",
    "--rust"       to "rust",
    "--wasm"       to "web-wasm",
    "--llvm"       to "native",     "--ll" to "native",
)

private val PLATFORM_FLAGS = setOf("--desktop", "--android", "--ios", "--web")

private fun parseFlags(args: List<String>): CliFlags {
    var script = false
    var target: String? = null
    var platform: String? = null
    var hook: String? = null
    var file: String? = null
    val extra = mutableListOf<String>()

    for (arg in args) {
        when {
            arg == "--script"          -> script = true
            arg.startsWith("--hook:")  -> hook = arg.removePrefix("--hook:")
            arg in TARGET_FLAGS        -> target = TARGET_FLAGS[arg]
            arg in PLATFORM_FLAGS      -> platform = arg.removePrefix("--")
            arg.endsWith(".az") || arg.endsWith(".azora") -> file = arg
            else -> extra.add(arg)
        }
    }

    return CliFlags(script, target, platform, hook, file, extra)
}

// ── azora run ────────────────────────────────────────────────────

private fun handleRun(args: List<String>) {
    if (args.isEmpty()) {
        // No flags at all → build & run with default target (toml required)
        val (config, projectDir) = loadConfig()
        val target = config.project.target ?: "native"
        buildAndRun(config, projectDir, target, hook = null)
        return
    }

    val flags = parseFlags(args)

    if (flags.script) {
        // ── Interpreter mode ──
        val filePath = if (flags.file != null) {
            flags.file
        } else {
            // No file given → find entry from toml
            val (config, projectDir) = loadConfig()
            File(projectDir, config.project.src).resolve(config.project.entry).absolutePath
        }
        runScript(filePath, flags.hook)
    } else {
        // ── Compile & run mode (toml required) ──
        val (config, projectDir) = loadConfig()
        val target = flags.target ?: config.project.target ?: "native"

        // Resolve hook entry from toml [hooks] table
        val hookEntry = flags.hook?.let { name ->
            config.hooks[name]
                ?: error("Hook '$name' not defined in [hooks] section of azora.toml")
        }

        buildAndRun(config, projectDir, target, hookEntry, flags.platform)
    }
}

// ── azora build ──────────────────────────────────────────────────

private fun handleBuild(args: List<String>) {
    val flags = parseFlags(args)
    val (config, projectDir) = loadConfig()

    val targets = when {
        flags.target != null                   -> listOf(flags.target!!)
        config.project.target != null          -> listOf(config.project.target!!)
        config.project.targets.isNotEmpty()    -> config.project.targets
        else -> listOf("native")
    }

    for (target in targets) {
        buildTarget(config, projectDir, target, targets.size > 1)
    }
}

// ── Build & Run ──────────────────────────────────────────────────

private fun buildAndRun(config: AzoraConfig, projectDir: File, target: String, hook: String?, platform: String? = null) {
    if (target == "interpret") {
        val srcDir = File(projectDir, config.project.src)
        val sourceFiles = discoverSources(srcDir, config.project.entry)
        val source = concatenateSources(sourceFiles)
        compile(source, "interpret")
        return
    }

    // Web targets: delegate to serve (which builds + starts dev server)
    if (target == "web-js" || target == "web-wasm") {
        dev.azora.build.main(arrayOf("serve"))
        return
    }

    // Build first
    buildTarget(config, projectDir, target, false)

    val ext = outputExtension(target)
    val buildDir = File(projectDir, "build/$target")
    val outFile = File(buildDir, "app.$ext")

    if (!outFile.exists()) {
        System.err.println("Build produced no output.")
        return
    }

    // Run the built output
    val hookInfo = if (hook != null) " (hook: $hook)" else ""
    when (target) {
        "kmp" -> {
            val runPlatform = platform ?: "desktop"
            val gradleTask = when (runPlatform) {
                "desktop" -> ":composeApp:run"
                "android" -> ":composeApp:installDebug"
                "web"     -> ":composeApp:wasmJsBrowserRun"
                else      -> ":composeApp:run"
            }
            println("Running KMP ($runPlatform)$hookInfo...")
            val exitCode = ProcessBuilder("./gradlew", gradleTask)
                .directory(buildDir)
                .inheritIO().start().waitFor()
            if (exitCode != 0) System.err.println("Gradle exited with code $exitCode")
        }
        "native" -> {
            println("Running LLVM IR$hookInfo...")
            val exitCode = ProcessBuilder("lli", outFile.absolutePath)
                .inheritIO().start().waitFor()
            if (exitCode != 0) System.err.println("Process exited with code $exitCode")
        }
        "python" -> {
            println("Running with Python$hookInfo...")
            val exitCode = ProcessBuilder("python3", outFile.absolutePath)
                .inheritIO().start().waitFor()
            if (exitCode != 0) System.err.println("Process exited with code $exitCode")
        }
        "csharp" -> {
            println("Running MAUI$hookInfo...")
            // Use official .NET SDK (with MAUI workloads) if available
            val dotnetPath = listOf(
                System.getProperty("user.home") + "/.dotnet/dotnet",
                "dotnet"
            ).first { File(it).exists() || it == "dotnet" }
            val exitCode = ProcessBuilder(dotnetPath, "build", "-t:Run", "-f", "net9.0-maccatalyst")
                .directory(buildDir)
                .inheritIO().start().waitFor()
            if (exitCode != 0) System.err.println("dotnet exited with code $exitCode")
        }
        "swift" -> {
            println("Running Swift$hookInfo...")
            val exitCode = ProcessBuilder("swift", outFile.absolutePath)
                .inheritIO().start().waitFor()
            if (exitCode != 0) System.err.println("Process exited with code $exitCode")
        }
        "flutter" -> {
            val device = when (platform) {
                "desktop" -> "macos"
                "web" -> "chrome"
                "android" -> "emulator"
                "ios" -> "simulator"
                else -> "macos"
            }
            println("Running Flutter ($device)$hookInfo...")
            val exitCode = ProcessBuilder("flutter", "run", "-d", device)
                .directory(buildDir)
                .inheritIO().start().waitFor()
            if (exitCode != 0) System.err.println("flutter exited with code $exitCode")
        }
        "kotlin-jvm" -> {
            println("Build complete: ${outFile.relativeTo(projectDir)}")
            println("Run with: kotlinc ${outFile.name} -include-runtime -d app.jar && java -jar app.jar")
        }
        else -> {
            println("Build complete: ${outFile.relativeTo(projectDir)}")
        }
    }
}

// ── azora run --script ───────────────────────────────────────────

private fun runScript(path: String, hook: String?) {
    val file = File(path)
    if (!file.exists()) {
        System.err.println("File not found: $path")
        return
    }

    val rawSource = file.readText()
    val importResult = resolveLocalImports(rawSource, file.parentFile)
    val ppResult = AzPreprocessor().process(importResult.source, AzStdlib.sources)
    if (ppResult.errors.isNotEmpty()) {
        for (error in ppResult.errors) {
            System.err.println("[Line ${error.line}] ${error.message}")
        }
        return
    }

    val tokens = Lexer(ppResult.generatedSource).tokenize()
    val parser = Parser(tokens)
    val program = parser.parse()
    if (parser.errors.isNotEmpty()) {
        for (error in parser.errors) {
            System.err.println(error.message)
        }
        return
    }

    val console = ConsoleOutputManager()
    val interpreter = AzInterpreter(console)

    runBlocking {
        interpreter.execute(program)
    }

    for (msg in console.messages.value) {
        when (msg.type) {
            ConsoleMessageType.ERROR -> System.err.println(msg.text)
            ConsoleMessageType.WARNING -> System.err.println("warning: ${msg.text}")
            else -> println(msg.text)
        }
    }
}

// ── azora check ──────────────────────────────────────────────────

private fun handleCheck(args: List<String>) {
    val flags = parseFlags(args)
    val path = flags.file
    if (path == null) {
        System.err.println("Usage: azora check <file.az>")
        return
    }

    val file = File(path)
    if (!file.exists()) {
        System.err.println("File not found: $path")
        return
    }

    val rawSource = file.readText()
    val importResult = resolveLocalImports(rawSource, file.parentFile)
    val ppResult = AzPreprocessor().process(importResult.source, AzStdlib.sources)
    if (ppResult.errors.isNotEmpty()) {
        for (error in ppResult.errors) {
            System.err.println("[Line ${error.line}] ${error.message}")
        }
        return
    }

    val tokens = Lexer(ppResult.generatedSource).tokenize()
    val parser = Parser(tokens)
    val program = parser.parse()
    if (parser.errors.isNotEmpty()) {
        for (error in parser.errors) {
            System.err.println(error.message)
        }
        return
    }

    val stdlibPrograms = AzStdlib.loadPrograms()
    val analyzer = SemanticAnalyzer()
    analyzer.moduleRegistry = importResult.moduleRegistry
    analyzer.mainFileImports = importResult.localImports
    val result = analyzer.analyzeWithContext(stdlibPrograms, listOf(program))
    for (warning in result.warnings) {
        System.err.println("warning (line ${warning.line}): ${warning.message}")
    }
    for (error in result.errors) {
        System.err.println("error (line ${error.line}): ${error.message}")
    }

    if (!result.hasErrors) {
        println("No errors found.")
    }
}

// ── azora create ─────────────────────────────────────────────────

private fun handleCreate(args: List<String>) {
    val flags = parseFlags(args)
    val isLib = "--lib" in args

    // Extract project name (first non-flag argument)
    val projectName = args.firstOrNull { !it.startsWith("--") }
    if (projectName == null) {
        System.err.println("Usage: azora create <project_name> [--lib] [--js|--kt|--py|--cs|--swift|--wasm|--llvm]")
        return
    }

    val projectDir = File(System.getProperty("user.dir"), projectName)
    if (projectDir.exists()) {
        System.err.println("Error: Directory '$projectName' already exists.")
        return
    }

    // Determine target from flags (default: native for app, interpret for lib)
    val target = flags.target ?: if (isLib) "interpret" else "native"
    val isWeb = target == "web-js" || target == "web-wasm"

    // Create directory structure
    projectDir.mkdirs()
    File(projectDir, "src").mkdirs()
    File(projectDir, "tests").mkdirs()

    // Determine entry file name
    val entryName = if (isWeb) "App.az" else "main.az"
    val srcDir = File(projectDir, "src")

    // Generate azora.toml
    val toml = buildString {
        appendLine("[project]")
        appendLine("name = \"$projectName\"")
        appendLine("version = \"0.1.0\"")
        appendLine("target = \"$target\"")
        if (isLib) appendLine("type = \"lib\"")
        appendLine("entry = \"$entryName\"")
        appendLine("src = \"src\"")
        appendLine()
        when (target) {
            "web-js" -> {
                appendLine("[web-js]")
                appendLine("title = \"$projectName\"")
                appendLine("port = 8080")
            }
            "web-wasm" -> {
                appendLine("[web-wasm]")
                appendLine("title = \"$projectName\"")
                appendLine("port = 8080")
                appendLine("memory = 256")
            }
            "native" -> {
                appendLine("[native]")
                appendLine("renderer = \"opengl\"")
            }
            "kotlin-jvm" -> {
                appendLine("[kotlin-jvm]")
                appendLine("jvmTarget = \"21\"")
            }
            "kmp" -> {
                appendLine("[kmp]")
                appendLine("platforms = [\"desktop\"]")
                appendLine("compose = true")
            }
            "csharp" -> {
                appendLine("[csharp]")
                appendLine("framework = \"net8.0\"")
                if (isLib) appendLine("type = \"library\"") else appendLine("type = \"console\"")
            }
        }
        appendLine()
        appendLine("[test]")
        appendLine("src = \"tests\"")
    }
    File(projectDir, "azora.toml").writeText(toml)

    // Generate entry source file
    val packageName = projectName.replace("-", "_").replace(" ", "_").lowercase()
    if (isLib) {
        val libSource = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("use scope std")
            appendLine()
            appendLine("/// Adds two integers.")
            appendLine("func add(a: Int, b: Int): Int {")
            appendLine("    return a + b")
            appendLine("}")
        }
        File(srcDir, entryName).writeText(libSource)
    } else if (isWeb) {
        // Web app with a root view
        File(srcDir, "Pages").mkdirs()

        val appSource = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("use std.ui")
            appendLine("use pages.*")
            appendLine("use scope std")
            appendLine()
            appendLine("view App() {")
            appendLine("    rem page: Int = 0")
            appendLine()
            appendLine("    Column(modifier: Modifier().padding(24)) {")
            appendLine("        Text(\"Welcome to $projectName\")")
            appendLine("        Spacer(height: 16)")
            appendLine("        HomePage()")
            appendLine("    }")
            appendLine("}")
        }
        File(srcDir, entryName).writeText(appSource)

        val homePageSource = buildString {
            appendLine("package $packageName.pages")
            appendLine()
            appendLine("use std.ui")
            appendLine("use scope std")
            appendLine()
            appendLine("view HomePage() {")
            appendLine("    Column {")
            appendLine("        Text(\"Hello, World!\")")
            appendLine("    }")
            appendLine("}")
        }
        File(srcDir, "Pages/HomePage.az").writeText(homePageSource)
    } else {
        // Standard app (native, kotlin, python, etc.)
        val mainSource = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("use scope std")
            appendLine()
            appendLine("func main() {")
            appendLine("    println(\"Hello from $projectName!\")")
            appendLine("}")
        }
        File(srcDir, entryName).writeText(mainSource)
    }

    // Generate test file
    val testSource = buildString {
        appendLine("package $packageName.tests")
        appendLine()
        appendLine("use scope std")
        appendLine()
        appendLine("test \"example\" {")
        appendLine("    assert 1 + 1 == 2 { \"basic arithmetic\" }")
        appendLine("}")
    }
    File(projectDir, "tests/test_main.az").writeText(testSource)

    // Generate .gitignore
    val gitignore = buildString {
        appendLine("build/")
        appendLine(".azora/")
        appendLine(".DS_Store")
    }
    File(projectDir, ".gitignore").writeText(gitignore)

    // Print summary
    val typeLabel = if (isLib) "library" else "application"
    println("Created $typeLabel '$projectName' ($target)")
    println()
    println("  $projectName/")
    println("  ├── azora.toml")
    println("  ├── .gitignore")
    println("  ├── src/")
    if (isWeb) {
        println("  │   ├── App.az")
        println("  │   └── Pages/")
        println("  │       └── HomePage.az")
    } else {
        println("  │   └── $entryName")
    }
    println("  └── tests/")
    println("      └── test_main.az")
    println()
    println("Get started:")
    println("  cd $projectName")
    if (isLib) {
        println("  azora check src/$entryName")
    } else if (isWeb) {
        println("  azora serve")
    } else {
        println("  azora run")
    }
}

// ── azora init / serve / test / clean ────────────────────────────

private fun handleInit(args: List<String>) {
    // Delegate to build-tool
    dev.azora.build.main(arrayOf("init") + args.toTypedArray())
}

private fun handleServe(args: List<String>) {
    dev.azora.build.main(arrayOf("serve") + args.toTypedArray())
}

private fun handleTest(args: List<String>) {
    dev.azora.build.main(arrayOf("test") + args.toTypedArray())
}

private fun handleClean() {
    dev.azora.build.main(arrayOf("clean"))
}

// ── REPL ─────────────────────────────────────────────────────────

private fun repl() {
    println("Azora ${BuildConfig.VERSION} REPL")
    println("Type expressions or statements. Type 'exit' to quit.")
    println()

    val history = mutableListOf<String>()
    var shownMessageCount = 0

    while (true) {
        print("az> ")
        val line = readlnOrNull() ?: break
        if (line.trim() == "exit" || line.trim() == "quit") break
        if (line.isBlank()) continue

        try {
            // Accumulate all lines and wrap in func main() so variables persist
            val candidate = history + line
            val body = candidate.joinToString("\n")
            val wrapped = "func main() {\n$body\n}"

            val ppResult = AzPreprocessor().process(wrapped)
            if (ppResult.errors.isNotEmpty()) {
                for (error in ppResult.errors) {
                    System.err.println("[Line ${error.line}] ${error.message}")
                }
                continue
            }

            val tokens = Lexer(ppResult.generatedSource).tokenize()
            val parser = Parser(tokens)
            val program = parser.parse()
            if (parser.errors.isNotEmpty()) {
                for (error in parser.errors) {
                    System.err.println(error.message)
                }
                continue
            }

            // Re-execute the full accumulated program with a fresh interpreter
            val console = ConsoleOutputManager()
            val interpreter = AzInterpreter(console)

            runBlocking {
                interpreter.execute(program)
            }

            // Only show output from the new line (skip output already shown)
            val allMessages = console.messages.value
            for (i in shownMessageCount until allMessages.size) {
                val msg = allMessages[i]
                when (msg.type) {
                    ConsoleMessageType.ERROR -> System.err.println(msg.text)
                    ConsoleMessageType.WARNING -> System.err.println("warning: ${msg.text}")
                    else -> println(msg.text)
                }
            }

            // Commit: line succeeded, add to history and update message baseline
            history.add(line)
            shownMessageCount = allMessages.size
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

        Run (interpreter):
          azora run --script <file.az>                  Interpret a script directly
          azora run --script --hook:onStart <file.az>   Interpret with hook entry point
          azora run --script                            Interpret entry file from azora.toml

        Build (compile to target, requires azora.toml):
          azora build                                   Compile to default target (--llvm)
          azora build --js                              Compile to JavaScript
          azora build --kmp                             Compile to Kotlin Multiplatform (full project)
          azora build --cs                              Compile to C# (.NET project)

        Run (compile & run, requires azora.toml):
          azora run                                     Build & run (default: --llvm)
          azora run --js                                Build & run JavaScript (dev server)
          azora run --kmp --desktop                     Build & run KMP desktop
          azora run --kmp --android                     Build & run KMP Android
          azora run --cs                                Build & run .NET
          azora run --py                                Build & run Python
          azora run --llvm --hook:level1OnStart         Build & run with hook entry point

        Target flags:
          --javascript, --js      JavaScript / React (browser)
          --kmp                   Kotlin Multiplatform (Compose: desktop, Android, iOS, web)
          --kotlin, --kt          Kotlin JVM (plain)
          --csharp, --cs, --maui  C# / .NET / MAUI
          --python, --py          Python / Tkinter
          --swift                 Swift / SwiftUI
          --dart                  Dart / Flutter
          --rust                  Rust
          --wasm                  WebAssembly
          --llvm, --ll            LLVM IR / Native (default)

        Platform flags (for --kmp):
          --desktop               Desktop (JVM)
          --android               Android (emulator or device)
          --ios                   iOS (simulator)
          --web                   Browser (Wasm)

        Project:
          azora create <name>          Create a new Azora project (native)
          azora create <name> --lib    Create a library project
          azora create <name> --js     Create a web (React) project
          azora create <name> --kt     Create a Kotlin JVM project
          azora init                   Create azora.toml in current directory

        Other:
          azora check <file.az>   Type-check without running
          azora repl              Interactive REPL
          azora serve             Build web target & start dev server
          azora test              Build & run tests
          azora clean             Remove build/ directory
          azora version           Show version
          azora help              Show this help
    """.trimIndent())
}

// ── Local Import Resolution (unchanged) ──────────────────────────

private fun toPascalCase(segment: String): String =
    segment.split("_").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

data class ResolvedModule(
    val modulePath: String,
    val content: String,
    val declarations: Set<String>
)

private fun extractDeclarationNames(source: String): Set<String> {
    val names = mutableSetOf<String>()
    val declPattern = Regex("""^\s*(?:expose\s+)?(?:view|func|pack|enum|slot|fail|scope|solo|wrap|task|flow|hook)\s+(\w+)""")
    for (line in source.lines()) {
        val match = declPattern.find(line)
        if (match != null) {
            names.add(match.groupValues[1])
        }
    }
    return names
}

private fun extractLocalImports(source: String): Set<String> {
    val stdPrefixes = setOf("std", "engine", "scope")
    val imports = mutableSetOf<String>()
    for (line in source.lines()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("use ") || trimmed.startsWith("use scope ")) continue
        val modulePath = trimmed.removePrefix("use ").trim()
        val firstSegment = modulePath.substringBefore(".")
        if (firstSegment in stdPrefixes) continue
        if (modulePath.endsWith(".*")) continue
        imports.add(modulePath)
    }
    return imports
}

private fun extractReferencedTypes(source: String, ownDeclarations: Set<String>): Set<String> {
    val refs = mutableSetOf<String>()
    val typeRefPattern = Regex("""\b([A-Z][a-zA-Z0-9]+)\b""")
    for (line in source.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue
        if (trimmed.startsWith("package ") || trimmed.startsWith("use ")) continue
        for (match in typeRefPattern.findAll(line)) {
            val name = match.groupValues[1]
            if (name !in ownDeclarations) {
                refs.add(name)
            }
        }
    }
    return refs
}

private fun validateModuleImports(
    modules: List<ResolvedModule>,
    mainDeclarations: Set<String>,
    mainModulePath: String,
    sourceDir: File
): List<String> {
    val errors = mutableListOf<String>()
    val declOwners = mutableMapOf<String, String>()
    for (module in modules) {
        for (decl in module.declarations) {
            declOwners[decl] = module.modulePath
        }
    }
    for (decl in mainDeclarations) {
        declOwners[decl] = mainModulePath
    }
    val builtins = setOf("Int", "Real", "Bool", "String", "Char", "Unit", "Any", "Type",
        "Modifier", "Column", "Row", "Box", "Card", "Text", "Button", "Input", "Image",
        "Link", "Spacer", "Divider", "Icon", "ScrollView", "Center", "NavHost",
        "Header", "Footer", "Nav", "Section", "Aside", "Main",
        "Void", "Callback", "Float", "Decimal", "Long", "Short", "Byte",
        "UInt", "ULong", "UShort", "UByte", "Size", "USize", "Cent", "UCent")

    for (module in modules) {
        val fileImports = extractLocalImports(module.content)
        val referencedTypes = extractReferencedTypes(module.content, module.declarations)

        val accessible = mutableSetOf<String>()
        accessible.addAll(module.declarations)
        accessible.addAll(builtins)
        for (importPath in fileImports) {
            val importedModule = modules.find { it.modulePath == importPath }
            if (importedModule != null) {
                accessible.addAll(importedModule.declarations)
            }
            if (importPath == mainModulePath) {
                accessible.addAll(mainDeclarations)
            }
        }

        for (ref in referencedTypes) {
            if (ref in accessible) continue
            val owner = declOwners[ref] ?: continue
            if (owner == module.modulePath) continue
            val fileName = module.modulePath.substringAfterLast(".")
                .split("_").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
            errors.add("$fileName.az: '$ref' is declared in '$owner' but not imported. Add 'use $owner' to $fileName.az")
        }
    }

    return errors
}

data class ImportResult(
    val source: String,
    val moduleRegistry: Map<String, Set<String>>,
    val localImports: Set<String>,
    val modules: List<ResolvedModule> = emptyList(),
    val mainDeclarations: Set<String> = emptySet()
)

private fun resolveLocalImports(source: String, sourceDir: File): ImportResult {
    val stdPrefixes = setOf("std", "engine", "scope")
    val modules = mutableListOf<ResolvedModule>()
    val alreadyImported = mutableSetOf<String>()
    val localUseLines = mutableSetOf<String>()
    val localImports = mutableSetOf<String>()

    for (line in source.lines()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("use ") || trimmed.startsWith("use scope ")) continue
        val modulePath = trimmed.removePrefix("use ").trim()
        val firstSegment = modulePath.substringBefore(".")
        if (firstSegment in stdPrefixes) continue
        localUseLines.add(trimmed)

        if (modulePath.endsWith(".*")) {
            val basePath = modulePath.removeSuffix(".*")
            val folderPath = basePath.split(".").joinToString("/") { toPascalCase(it) }
            val folder = File(sourceDir, folderPath)
            if (folder.isDirectory) {
                val azFiles = folder.listFiles()?.filter { it.extension == "az" }?.sortedBy { it.name } ?: emptyList()
                for (azFile in azFiles) {
                    val key = azFile.absolutePath
                    if (key !in alreadyImported) {
                        alreadyImported.add(key)
                        val content = azFile.readText()
                        val fileModulePath = basePath + "." + azFile.nameWithoutExtension
                            .replace(Regex("([A-Z])")) { "_${it.value.lowercase()}" }
                            .trimStart('_').lowercase()
                        val decls = extractDeclarationNames(content)
                        modules.add(ResolvedModule(fileModulePath, content, decls))
                        localImports.add(fileModulePath)
                    }
                }
            } else {
                System.err.println("warning: Cannot resolve wildcard import '$modulePath' (expected directory ${folder.absolutePath})")
            }
            continue
        }

        localImports.add(modulePath)
        val segments = modulePath.split(".")
        val filePath = segments.joinToString("/") { toPascalCase(it) } + ".az"
        val resolved = File(sourceDir, filePath)
        if (resolved.exists()) {
            val key = resolved.absolutePath
            if (key !in alreadyImported) {
                alreadyImported.add(key)
                val content = resolved.readText()
                val decls = extractDeclarationNames(content)
                modules.add(ResolvedModule(modulePath, content, decls))
            }
        } else {
            System.err.println("warning: Cannot resolve local import '$modulePath' (expected ${resolved.absolutePath})")
        }
    }

    val mainDecls = extractDeclarationNames(source)

    val registry = mutableMapOf<String, Set<String>>()
    for (module in modules) {
        registry[module.modulePath] = module.declarations
    }
    registry["__main__"] = mainDecls

    if (modules.isEmpty()) {
        return ImportResult(source, registry, localImports, modules, mainDecls)
    }

    val strippedSource = source.lines().joinToString("\n") { line ->
        if (line.trim() in localUseLines) "" else line
    }

    val concatenated = modules.joinToString("\n\n") { it.content } + "\n\n" + strippedSource

    return ImportResult(concatenated, registry, localImports, modules, mainDecls)
}
