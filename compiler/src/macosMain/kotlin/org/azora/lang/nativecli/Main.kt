@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.azora.lang.nativecli

import dev.azora.lang.BuildConfig
import kotlinx.cinterop.*
import org.azora.lang.*
import org.azora.lang.backend.*
import org.azora.lang.diagnostics.*
import platform.posix.*
import org.azora.lang.nativeposix.posix_spawnp
import org.azora.lang.nativeposix.azora_native_environ
import kotlin.system.exitProcess

private val moduleHeader = Regex("(?m)^\\s*(?:(?:export|exposed|confined|protected)\\s+)*module\\s+([A-Za-z_][\\w.]*)")
private fun moduleOf(source: String): String? = moduleHeader.find(source)?.groupValues?.get(1)
private fun read(path: String): String = fsReadText(path).value ?: error("cannot read '$path'")
private fun canonical(path: String): String = fsCanonical(path).value ?: error("path does not exist: '$path'")
private fun walk(root: String): List<String> = buildList {
    fun visit(path: String) {
        for (child in fsList(path).value ?: error("cannot list '$path'")) {
            when (fsStat(child).value?.kind) {
                "Directory" -> if (!child.substringAfterLast('/').startsWith('.')) visit(child)
                "File" -> if (child.endsWith(".az")) add(child)
            }
        }
    }
    visit(root)
}.sorted()

private data class UnitSources(val entry: String, val text: String, val libraries: List<LibrarySource>)

private fun sources(path: String, engine: Boolean = false): UnitSources {
    val entry = canonical(path)
    val text = read(entry)
    var root = entry.substringBeforeLast('/')
    val module = moduleOf(text)
    if (module != null) {
        val segments = module.split('.')
        var index = segments.lastIndex
        if (root.substringAfterLast('/') != segments[index] && entry.substringAfterLast('/').removeSuffix(".az") == segments[index]) index--
        while (index >= 0 && root.substringAfterLast('/') == segments[index]) {
            root = root.substringBeforeLast('/').ifEmpty { "/" }
            index--
        }
    }
    val libraries = if (module == "std" || module?.startsWith("std.") == true) mutableListOf() else
        walk(root).filter { it != entry && it.substringAfterLast('/') != "main.az" }.mapTo(mutableListOf()) {
            LibrarySource(it.removePrefix("${root.trimEnd('/')}/"), read(it))
        }
    if (engine && Regex("(?m)^\\s*import\\s+engine\\b").containsMatchIn(text)) {
        val home = osEnvVar("AZORA_ENGINE_HOME")?.let(::canonical)
            ?: error("Engine project requires AZORA_ENGINE_HOME to select its matching native Engine bundle")
        for (file in walk("$home/packages")) {
            val source = read(file)
            val declared = moduleOf(source) ?: continue
            libraries += LibrarySource("${declared.replace('.', '/')}/${file.substringAfterLast('/')}", source)
        }
    }
    return UnitSources(entry, text, libraries)
}

private fun defines(args: List<String>): Map<String, String> = buildMap {
    if ("--debug" in args) { put("DEBUG", "true"); put("RELEASE", "false") }
    if ("--release" in args) { put("DEBUG", "false"); put("RELEASE", "true") }
    for (argument in args) if (argument.startsWith("-D") && '=' in argument) {
        val pair = argument.drop(2).split('=', limit = 2)
        put(pair[0], pair[1])
    }
}

private fun compile(unit: UnitSources, args: List<String>): CompilationResult.Success {
    return when (val result = Compiler(unit.libraries).compile(unit.text, release = "--debug" !in args, defines = defines(args))) {
        is CompilationResult.Success -> result
        is CompilationResult.Failure -> {
            for (message in result.errors) fputs("$message\n", stderr)
            exitProcess(1)
        }
    }
}

private fun llvm(unit: UnitSources, args: List<String>): String {
    val result = compile(unit, args)
    result.backendErrors["llvm"]?.let { error("llvm target is unsupported for this program: $it") }
    return if ("--debug" in args) LlvmCodegen().generate(result.ir) else result.llvm
}

private fun json(value: String): String = buildString {
    append('"')
    for (character in value) when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (character.code < 32) append("\\u${character.code.toString(16).padStart(4, '0')}") else append(character)
    }
    append('"')
}

private fun analyze(unit: UnitSources, version: Long) {
    val source = SourceUnit(SourceId(unit.entry), unit.entry, unit.entry, unit.text, DocumentVersion(version))
    val snapshot = Compiler(unit.libraries).analyze(AnalysisRequest(listOf(source), setOf(source.id)))
    val diagnostics = snapshot.diagnostics.joinToString(",") { diagnostic ->
        val span = diagnostic.primary.span
        val located = snapshot.sources.units[span.source]
        val index = located?.let { StringLineIndex(it.text) }
        val start = index?.position(span.start, PositionEncoding.UTF8)
        val end = index?.position(span.endExclusive, PositionEncoding.UTF8)
        "{\"code\":${json(diagnostic.code.value)},\"severity\":${json(diagnostic.severity.name.lowercase())}," +
            "\"message\":${json(DiagnosticRenderer.summary(diagnostic))},\"source\":${json(span.source.value)}," +
            "\"start\":${span.start.value},\"end\":${span.endExclusive.value}," +
            "\"startLine\":${start?.line ?: 0},\"startByte\":${start?.character ?: 0}," +
            "\"endLine\":${end?.line ?: 0},\"endByte\":${end?.character ?: 0}}"
    }
    println("{\"protocol\":1,\"version\":$version,\"snapshot\":${json(snapshot.id.value)},\"completeness\":${json(snapshot.completeness.name.lowercase())},\"diagnostics\":[$diagnostics]}")
}

// Spawn with an argv vector, inherited descriptors and no command shell. The
// compiler's native project actions invoke clang and the produced executable.
private fun run(program: String, arguments: List<String>): Int = memScoped {
    val argv = allocArray<CPointerVar<ByteVar>>(arguments.size + 2)
    argv[0] = program.cstr.ptr
    arguments.forEachIndexed { index, value -> argv[index + 1] = value.cstr.ptr }
    argv[arguments.size + 1] = null
    val pid = alloc<pid_tVar>()
    val result = posix_spawnp(pid.ptr, program, null, null, argv, azora_native_environ())
    if (result != 0) error("cannot start '$program': error $result")
    val status = alloc<IntVar>()
    while (waitpid(pid.value, status.ptr, 0) < 0) if (errno != EINTR) error("cannot wait for '$program'")
    if ((status.value and 127) == 0) status.value shr 8 else 128 + (status.value and 127)
}

private fun buildProject(directory: String, action: String, args: List<String>) {
    val root = canonical(directory)
    val entry = "$root/src/main.az"
    val unit = sources(entry, engine = true)
    val build = "$root/.azora-build"
    fsMutate("createDirectories", build, "")?.let { error("cannot create build directory: $it") }
    val name = root.substringAfterLast('/').filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "app" }
    val output = "$build/$name"
    fsWriteText("$output.ll", llvm(unit, args), false)?.let { error("cannot write LLVM output: $it") }
    val link = mutableListOf("$output.ll", "-Wno-override-module", "-o", output)
    if (unit.libraries.any { moduleOf(it.source)?.startsWith("engine") == true }) {
        val home = canonical(osEnvVar("AZORA_ENGINE_HOME") ?: error("AZORA_ENGINE_HOME is required"))
        val runtime = listOf("$home/native/macos", "$home/runtime/build").firstOrNull { fsExists("$it/libazora_runtime.dylib") }
            ?: error("selected Engine bundle has no native runtime")
        // The initial macOS Engine graphics boundary uses these system
        // frameworks. Package-graph-specific native dependencies are rejected
        // by the linker rather than hidden behind another language runtime.
        link += listOf("-L", runtime, "-lazora_runtime", "-Wl,-rpath,$runtime", "-framework", "Cocoa", "-framework", "Metal", "-framework", "QuartzCore", "-framework", "CoreText")
    }
    val status = run(osEnvVar("AZORA_CLANG") ?: "/usr/bin/clang", link)
    if (status != 0) exit(status)
    println("built $output")
    if (action == "play") exit(run(output, emptyList()))
}

fun main(arguments: Array<String>) {
    val args = arguments.toList()
    try {
        if (args.size >= 2 && args[1] in setOf("build", "play", "inspect")) {
            if (args[1] == "inspect") analyze(sources("${canonical(args[0])}/src/main.az", engine = true), 0)
            else buildProject(args[0], args[1], args.drop(2))
            return
        }
        when (args.firstOrNull()) {
            "version", "--version" -> println("Azora ${BuildConfig.VERSION} native macOS arm64; protocol 1")
            "check" -> { compile(sources(args.getOrNull(1) ?: error("check requires a file")), args); println("No errors found.") }
            "compile" -> {
                require(args.getOrNull(1) in setOf("llvm", "ll")) { "native CLI currently accepts the llvm target" }
                val path = args.drop(2).firstOrNull { !it.startsWith('-') } ?: error("compile requires a file")
                println(llvm(sources(path), args))
            }
            "analyze" -> analyze(sources(args.getOrNull(1) ?: error("analyze requires a file")), args.firstOrNull { it.startsWith("--version=") }?.substringAfter('=')?.toLong() ?: 0)
            null, "help", "--help" -> println("azora native: check <file> | compile llvm <file> [--debug] | analyze <file> [--version=N] | <project> build|play|inspect")
            else -> error("unknown native compiler command '${args[0]}'")
        }
    } catch (failure: Exception) {
        fputs("${failure.message ?: failure.toString()}\n", stderr)
        exit(1)
    }
}
