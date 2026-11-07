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

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.LibrarySource
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.packages.BuildPlan
import org.azora.lang.packages.JvmPackageHost
import org.azora.lang.packages.Lockfile
import org.azora.lang.packages.Manifest
import org.azora.lang.packages.PackageError
import org.azora.lang.packages.PackageResolver
import org.azora.lang.packages.Paths
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * `azora new`, `build`, `run`, `test`, `add` and `remove` for a project with an
 * `azora.azon` (PACKAGES_DIP). A command runs on the project in the current
 * directory, or the one `--project <dir>` names.
 */
internal object ProjectCommands {
    private val host = JvmPackageHost()
    private val resolver = PackageResolver(host) { message -> System.err.println("    $message") }

    /** Whether [dir] holds a manifest - a project, package or workspace - a bare `azora run` or `azora test` applies to. */
    fun isProject(dir: File): Boolean = Manifest.NAMES.any { File(dir, it).isFile }

    private fun fail(message: String, status: Int = 1): Nothing {
        System.err.println("error: $message")
        exitProcess(status)
    }

    private fun projectDir(args: List<String>): String {
        val at = args.indexOf("--project")
        val dir = if (at >= 0) args.getOrNull(at + 1) ?: fail("--project needs a directory", 2) else "."
        return File(dir).canonicalPath
    }

    private fun option(args: List<String>, name: String): String? {
        val at = args.indexOf(name)
        return if (at >= 0) args.getOrNull(at + 1) ?: fail("$name needs a value", 2) else null
    }

    private val NAME = Regex("[a-z][a-z0-9_-]*")

    // ── azora new <name> ────────────────────────────────────────────

    fun new(args: List<String>) {
        val name = args.firstOrNull { !it.startsWith("-") } ?: fail("usage: azora new <name>", 2)
        if (!NAME.matches(name)) fail("'$name' is not a package name: lowercase letters, digits, '-' and '_', starting with a letter", 2)
        val dir = File(name)
        if (dir.exists()) fail("${dir.path} already exists", 2)
        File(dir, "src").mkdirs()
        File(dir, Manifest.FILE).writeText(
            """
            |project: {
            |    name: "$name"
            |    version: "0.1.0"
            |    entry: "main.az"
            |    src: "src"
            |}
            |dependencies: {
            |}
            |""".trimMargin(),
        )
        File(dir, "src/main.az").writeText(
            """
            |import std.io
            |
            |func greeting(name: String): String = "Hello, " + name
            |
            |func main() {
            |    println(greeting("$name"))
            |}
            |
            |test "greeting names who it greets" {
            |    assert greeting("Azora") == "Hello, Azora" panic "greeting must name its argument"
            |}
            |""".trimMargin(),
        )
        println("Created project '$name' in ${dir.path}")
    }

    // ── resolution and the lockfile ─────────────────────────────────

    private fun writeLock(dir: String, lock: Lockfile) {
        val lockPath = Paths.join(dir, Lockfile.FILE)
        val text = lock.render()
        if (!host.exists(lockPath) || host.readText(lockPath) != text) host.writeText(lockPath, text)
    }

    /**
     * What a command in [dir] works on, resolved, with its lockfile written: the
     * project or package there, or every member of the workspace there. A
     * workspace member resolves with its whole workspace, whose root holds the
     * one lockfile.
     */
    private fun resolveTargets(dir: String, update: Boolean): List<PackageResolver.Resolution> {
        val manifest = resolver.readManifest(dir)
            ?: throw PackageError("$dir has no ${Manifest.FILE}; create a project with 'azora new <name>'")
        if (manifest.unit == null) {
            if (!manifest.isWorkspace) throw PackageError("${manifest.file} declares no project, package or workspace")
            val workspace = resolver.resolveWorkspace(dir, update)
            writeLock(workspace.root.dir, workspace.lock)
            return workspace.members
        }
        val root = resolver.enclosingWorkspace(dir)
        if (root != null) {
            val workspace = resolver.resolveWorkspace(root, update)
            writeLock(workspace.root.dir, workspace.lock)
            return listOf(workspace.members.first { it.project.dir == manifest.dir })
        }
        val resolution = resolver.resolve(dir, update)
        writeLock(resolution.project.dir, resolution.lock)
        return listOf(resolution)
    }

    /** Building for another machine is not supported yet (TARGETS_DIP §1); say so rather than build for this one. */
    private fun refuseCrossTarget(args: List<String>) {
        if (args.any { it == "--target" || it.startsWith("--target=") }) {
            fail("azora builds for this machine only; --target (cross-compiling) is not supported yet (TARGETS_DIP §1)", 2)
        }
    }

    private fun plans(dir: String, update: Boolean): List<BuildPlan> =
        try {
            resolveTargets(dir, update).map { BuildPlan.of(host, it) }
        } catch (e: PackageError) {
            fail(e.message ?: e.toString())
        }

    // ── azora add / remove ──────────────────────────────────────────

    fun add(args: List<String>) {
        val name = args.firstOrNull { !it.startsWith("-") && args.getOrNull(args.indexOf(it) - 1)?.startsWith("--") != true }
            ?: fail("usage: azora add <name> (--path <dir> | --git <url> [--rev <commit> | --tag <tag> | --branch <branch>])", 2)
        if (!NAME.matches(name)) fail("'$name' is not a package name", 2)
        val dependency = Manifest.Dependency(
            name = name,
            path = option(args, "--path"),
            git = option(args, "--git"),
            rev = option(args, "--rev"),
            tag = option(args, "--tag"),
            branch = option(args, "--branch"),
        )
        if ((dependency.path == null) == (dependency.git == null)) fail("give exactly one of --path and --git", 2)
        if (dependency.path != null && listOfNotNull(dependency.rev, dependency.tag, dependency.branch).isNotEmpty()) {
            fail("--rev, --tag and --branch apply to a --git dependency", 2)
        }
        if (listOfNotNull(dependency.rev, dependency.tag, dependency.branch).size > 1) fail("give at most one of --rev, --tag and --branch", 2)
        edit(projectDir(args)) { it.withDependency(dependency) }
        println("Added '$name'")
    }

    fun remove(args: List<String>) {
        val name = args.firstOrNull { !it.startsWith("-") } ?: fail("usage: azora remove <name>", 2)
        val dir = projectDir(args)
        val manifest = manifest(dir)
        if (manifest.dependencies.none { it.name == name }) fail("${manifest.file} has no dependency '$name'")
        edit(dir) { it.withoutDependency(name) }
        println("Removed '$name'")
    }

    private fun manifest(dir: String): Manifest =
        try {
            resolver.readManifest(dir)?.takeIf { it.unit != null }
                ?: fail("$dir has no project or package ${Manifest.FILE}; dependencies belong to a project, a package or a workspace member")
        } catch (e: PackageError) {
            fail(e.message ?: e.toString())
        }

    /** Rewrites the manifest with [change], keeping it only when the graph still resolves. */
    private fun edit(dir: String, change: (Manifest) -> String) {
        val manifest = manifest(dir)
        val before = manifest.source
        host.writeText(manifest.file, change(manifest))
        try {
            resolveTargets(dir, update = false)
        } catch (e: Exception) {
            host.writeText(manifest.file, before)
            fail("${e.message ?: e}\n${manifest.file} is unchanged")
        }
    }

    // ── azora build / run ───────────────────────────────────────────

    private fun clang(): String =
        System.getenv("AZORA_CLANG")?.takeIf { File(it).canExecute() }
            ?: "/usr/bin/clang".takeIf { File(it).canExecute() }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "clang") }.firstOrNull { it.canExecute() }?.path
            ?: fail("building needs clang (set AZORA_CLANG, or install the platform's C toolchain)")

    private fun linkArguments(plan: BuildPlan): List<String> =
        plan.cSources + plan.frameworks.flatMap { listOf("-framework", it) } + plan.libs.map { "-l$it" }

    /** Compiles [file] of [plan] as the entry; null, having said why, when it does not compile for the LLVM target. */
    private fun compile(plan: BuildPlan, file: String, debug: Boolean, base: String): CompilationResult.Success? {
        val result = Compiler(plan.librariesFor(file)).compile(plan.ownSources.getValue(file).source, release = !debug)
        val shown = Paths.relative(base, file)
        if (result is CompilationResult.Failure) {
            System.err.println("error: $shown does not compile")
            result.errors.forEach { System.err.println("    $it") }
            return null
        }
        result as CompilationResult.Success
        result.backendErrors["llvm"]?.let {
            System.err.println("error: the LLVM target cannot build $shown: $it")
            return null
        }
        return result
    }

    /** Checks every source of a package, each as the entry; whether all of them compile. */
    private fun check(plan: BuildPlan, debug: Boolean, base: String): Boolean {
        val broken = plan.ownSources.keys.count { compile(plan, it, debug, base) == null }
        if (broken == 0) println("Checked ${plan.name} (${plan.ownSources.size} source${if (plan.ownSources.size == 1) "" else "s"})")
        return broken == 0
    }

    /** Compiles and links a project into `build/<name>`; null, having said why, when it fails. */
    private fun link(plan: BuildPlan, debug: Boolean, quiet: Boolean): File? {
        val compiled = compile(plan, plan.entryPath!!, debug, File(".").canonicalPath) ?: return null
        val dir = File(plan.projectDir)
        val out = File(dir, "build").apply { mkdirs() }
        val ir = File(out, "${plan.name}.ll").apply { writeText(compiled.llvm) }
        val executable = File(out, plan.name)
        val link = host.run(
            listOf(clang(), ir.path, "-Wno-override-module", if (debug) "-O0" else "-O2") + linkArguments(plan) + listOf("-o", executable.path),
            dir.path,
        )
        if (link.exitCode != 0) {
            System.err.println("error: linking ${plan.name} failed:\n${link.output.trim()}")
            return null
        }
        if (!quiet) println("Built ${executable.relativeTo(File(".").canonicalFile).path}")
        return executable
    }

    /**
     * `azora build`: links a project into `build/<name>`; checks a package -
     * every module it declares, through the LLVM target - since a package has
     * no program of its own. At a workspace root, it does this for every member.
     */
    fun build(args: List<String>) {
        refuseCrossTarget(args)
        val debug = "--debug" in args
        val dir = projectDir(args)
        val plans = plans(dir, update = "--update" in args)
        var failed = 0
        for (plan in plans) {
            val ok = if (plan.isPackage) {
                check(plan, debug, dir)
            } else {
                link(plan, debug, quiet = false) != null
            }
            if (!ok) failed++
        }
        if (plans.size > 1) println("\n${plans.size - failed} of ${plans.size} members built")
        if (failed > 0) exitProcess(1)
    }

    fun run(args: List<String>) {
        val split = args.indexOf("--")
        val own = if (split >= 0) args.take(split) else args
        val programArgs = if (split >= 0) args.drop(split + 1) else emptyList()
        refuseCrossTarget(own)
        val plans = plans(projectDir(own), update = "--update" in own)
        val plan = plans.singleOrNull() ?: fail("a workspace has no program to run; run one of its projects with --project <dir>")
        if (plan.isPackage) fail("'${plan.name}' is a package, which has no program to run; 'azora test' runs its tests")
        val executable = link(plan, "--debug" in own, quiet = true) ?: exitProcess(1)
        val process = ProcessBuilder(listOf(executable.path) + programArgs).inheritIO().start()
        exitProcess(process.waitFor())
    }

    // ── azora test ──────────────────────────────────────────────────

    private val TEST_BLOCK = Regex("""(?m)^\s*test\s""")

    /**
     * Runs the `test` blocks of a project's or package's own sources, each
     * file compiled with the rest of its sources and its packages as library
     * modules; at a workspace root, every member's. `--llvm` runs them
     * natively, linked as `azora build` links.
     */
    fun test(args: List<String>) {
        refuseCrossTarget(args)
        val dir = projectDir(args)
        val native = "--llvm" in args
        var passed = 0
        var failed = 0
        var broken = 0
        for (plan in plans(dir, update = false)) {
            for ((file, own) in plan.ownSources) {
                if (!TEST_BLOCK.containsMatchIn(own.source)) continue
                val shown = Paths.relative(dir, file)
                val result = Compiler(plan.librariesFor(file)).compile(own.source, release = false)
                if (result is CompilationResult.Failure) {
                    broken++
                    println("✗ $shown - compile error")
                    result.errors.forEach { println("    $it") }
                    continue
                }
                result as CompilationResult.Success
                val outcomes = if (native) {
                    runTestsNatively(result.llvm, result.ir.tests.map { it.name }, linkArguments(plan))
                } else {
                    IrInterpreter().runTests(result.ir).map { Triple(it.name, it.passed, it.message) }
                }
                for ((name, ok, message) in outcomes) {
                    if (ok) {
                        passed++
                        println("✓ $shown :: $name")
                    } else {
                        failed++
                        println("✗ $shown :: $name")
                        message?.let { println("    $it") }
                    }
                }
            }
        }
        println("\n$passed passed, $failed failed" + if (broken > 0) ", $broken file(s) failed to compile" else "")
        if (failed > 0 || broken > 0) exitProcess(1)
    }

    /** Each test as its own process: a driver `main` calls the test whose index it is given. */
    private fun runTestsNatively(llvm: String, names: List<String>, link: List<String>): List<Triple<String, Boolean, String?>> {
        if (names.isEmpty()) return emptyList()
        val symbols = Regex("""(?m)^define void @(test_[A-Za-z0-9_.$]+)\(\)""").findAll(llvm).map { it.groupValues[1] }.toList()
        if (symbols.size != names.size) return names.map { Triple(it, false, "the native build has no symbol for this test") }
        val body = llvm.replace(Regex("""(?m)^define i32 @main\("""), "define i32 @__az_user_main(")
        val driver = buildString {
            if (!body.contains("declare i32 @atoi(")) appendLine("declare i32 @atoi(i8*)")
            appendLine("define i32 @main(i32 %argc, i8** %argv) {")
            appendLine("entry:")
            appendLine("  %slot = getelementptr i8*, i8** %argv, i64 1")
            appendLine("  %arg = load i8*, i8** %slot")
            appendLine("  %index = call i32 @atoi(i8* %arg)")
            append("  switch i32 %index, label %done [")
            symbols.indices.forEach { append(" i32 $it, label %case$it") }
            appendLine(" ]")
            symbols.forEachIndexed { i, symbol ->
                appendLine("case$i:")
                appendLine("  call void @$symbol()")
                appendLine("  br label %done")
            }
            appendLine("done:")
            appendLine("  ret i32 0")
            appendLine("}")
        }
        val work = File.createTempFile("azora-tests", "").also { it.delete(); it.mkdirs() }
        try {
            val ir = File(work, "tests.ll").apply { writeText(body + "\n" + driver) }
            val exe = File(work, "tests")
            val built = host.run(listOf(clang(), ir.path, "-Wno-override-module") + link + listOf("-o", exe.path))
            if (built.exitCode != 0) return names.map { Triple(it, false, "native build failed: ${built.output.trim().take(400)}") }
            return names.mapIndexed { i, name ->
                val process = ProcessBuilder(exe.path, i.toString()).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    Triple(name, false, "timed out")
                } else if (process.exitValue() == 0) {
                    Triple(name, true, null)
                } else {
                    Triple(name, false, output.trim().ifEmpty { "exited with ${process.exitValue()}" })
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
