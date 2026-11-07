/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.packages

import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.backend.IrInterpreter
import org.azora.lang.codegen.LlvmExec
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Resolution, the lockfile, workspaces and build plans (PACKAGES_DIP §3-§5) on
 * real directories, and git dependencies on real repositories made for the
 * test. The cache is a fresh directory per test: a clean machine.
 */
class PackageResolverTest {
    private val root: File = File.createTempFile("azora-packages", "").let { it.delete(); it.mkdirs(); it.canonicalFile }
    private val cache = File(root, "cache")

    /** The machine, with its cache moved into the test's directory. */
    private val host = object : PackageHost by JvmPackageHost() {
        override fun environment(name: String): String? = if (name == "AZORA_HOME") cache.path else System.getenv(name)
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(path: String, text: String): File =
        File(root, path).apply { parentFile.mkdirs(); writeText(text.trimIndent() + "\n") }

    private fun dir(path: String) = File(root, path).path

    private fun resolver() = PackageResolver(host)

    /** A package `name` whose one module `module` declares `func <function>(): String = "<value>"`. */
    private fun library(at: String, name: String, module: String, function: String, value: String, extra: String = "") {
        write("$at/azora.azon", "package: { name: \"$name\" version: \"1.0.0\" module: \"$module\" }\n$extra")
        write("$at/src/${module.substringAfterLast('.')}.az", "module $module\n\nfunc $function(): String = \"$value\"")
    }

    private fun project(at: String, dependencies: String, main: String) {
        write("$at/azora.azon", "project: { name: \"app\" version: \"0.1.0\" }\ndependencies: {\n$dependencies\n}")
        write("$at/src/main.az", main)
    }

    /** Compiles the plan's entry and runs it in the interpreter, and on LLVM when `lli` is here; both must agree. */
    private fun runPlan(plan: BuildPlan): String {
        val entry = plan.entryPath ?: fail("not a project")
        val result = Compiler(plan.librariesFor(entry)).compile(plan.ownSources.getValue(entry).source)
        assertIs<CompilationResult.Success>(result, (result as? CompilationResult.Failure)?.errors?.joinToString("\n"))
        val interpreted = IrInterpreter().interpret(result.ir).trim()
        if (LlvmExec.available) assertEquals(interpreted, LlvmExec.runIr(result.llvm).trim(), "LLVM and the interpreter disagree")
        return interpreted
    }

    // ── path dependencies ───────────────────────────────────────────

    @Test
    fun pathDependenciesResolveTransitivelyAndBuild() {
        library("libs/words", "words", "lib.words", "word", "world")
        write(
            "libs/greet/azora.azon",
            """
            package: { name: "greet" version: "2.0.0" module: "lib.greet" }
            dependencies: { words: { path: "../words" } }
            """,
        )
        write("libs/greet/src/greet.az", "module lib.greet\n\nimport lib.words\n\nfunc greeting(): String = \"Hello, \" + word()")
        project("app", "    greet: { path: \"../libs/greet\" }", "import std.io\nimport lib.greet\n\nfunc main() {\n    println(greeting())\n}")

        val resolution = resolver().resolve(dir("app"))
        assertEquals(listOf("words", "greet"), resolution.packages.map { it.name }, "dependencies come before what depends on them")
        assertEquals(
            listOf(
                Lockfile.Locked("words", "1.0.0", "path", "../libs/words"),
                Lockfile.Locked("greet", "2.0.0", "path", "../libs/greet"),
            ),
            resolution.lock.packages,
        )
        val plan = BuildPlan.of(host, resolution)
        assertEquals(setOf("lib/words/words.az", "lib/greet/greet.az"), plan.dependencySources.map { it.path }.toSet())
        assertEquals("Hello, world", runPlan(plan))
    }

    @Test
    fun aCycleIsAnError() {
        write("a/azora.azon", "package: { name: \"a\" module: \"a\" }\ndependencies: { b: { path: \"../b\" } }")
        write("b/azora.azon", "package: { name: \"b\" module: \"b\" }\ndependencies: { a: { path: \"../a\" } }")
        project("app", "    a: { path: \"../a\" }", "func main() {}")
        val error = assertFailsWith<PackageError> { resolver().resolve(dir("app")) }
        assertTrue(error.message!!.contains("dependency cycle: app -> a -> b -> a"), error.message)
    }

    @Test
    fun twoPackagesOfOneNameAreAnError() {
        library("one/util", "util", "one.util", "f", "1")
        library("two/util", "util", "two.util", "f", "2")
        write("mid/azora.azon", "package: { name: \"mid\" module: \"mid\" }\ndependencies: { util: { path: \"../two/util\" } }")
        project("app", "    util: { path: \"../one/util\" }\n    mid: { path: \"../mid\" }", "func main() {}")
        val error = assertFailsWith<PackageError> { resolver().resolve(dir("app")) }
        assertTrue(error.message!!.contains("two packages are named 'util'"), error.message)
    }

    @Test
    fun aDependencyMustBeThePackageItNames() {
        library("libs/words", "words", "lib.words", "word", "w")
        project("app", "    letters: { path: \"../libs/words\" }", "func main() {}")
        val error = assertFailsWith<PackageError> { resolver().resolve(dir("app")) }
        assertTrue(error.message!!.contains("dependency 'letters' is the package 'words'"), error.message)
    }

    @Test
    fun aMissingEntryIsNamed() {
        write("app/azora.azon", "project: { name: \"app\" entry: \"start.az\" }")
        write("app/src/main.az", "func main() {}")
        val error = assertFailsWith<PackageError> { BuildPlan.of(host, resolver().resolve(dir("app"))) }
        assertTrue(error.message!!.contains("src/start.az does not exist"), error.message)
    }

    @Test
    fun aPlanLinksWhatEveryPackageLinks() {
        library("libs/gfx", "gfx", "lib.gfx", "f", "x", extra = "native: { frameworks: [ \"Metal\" ] libs: [ \"objc\" ] sources: [ \"shim/gfx.c\" ] }")
        write("libs/gfx/shim/gfx.c", "int gfx_shim(void) { return 0; }")
        write("app/azora.azon", "project: { name: \"app\" }\ndependencies: { gfx: { path: \"../libs/gfx\" } }\nnative: { frameworks: [ \"Metal\" \"AppKit\" ] }")
        write("app/src/main.az", "func main() {}")
        val plan = BuildPlan.of(host, resolver().resolve(dir("app")))
        assertEquals(listOf("Metal", "AppKit"), plan.frameworks)
        assertEquals(listOf("objc"), plan.libs)
        assertEquals(listOf(File(root, "libs/gfx/shim/gfx.c").canonicalPath), plan.cSources)
    }

    @Test
    fun aPackagePlanChecksEachSourceWithTheOthers() {
        write("libs/two/azora.azon", "package: { name: \"two\" module: \"lib.two\" }")
        write("libs/two/src/two.az", "module lib.two\n\nexposed import lib.two.part\n\nfunc both(): String = part() + part()")
        write("libs/two/src/part.az", "module lib.two.part\n\nfunc part(): String = \"p\"")
        val plan = BuildPlan.of(host, resolver().resolve(dir("libs/two")))
        assertTrue(plan.isPackage)
        assertNull(plan.entryPath)
        for (file in plan.ownSources.keys) {
            val libraries = plan.librariesFor(file)
            assertTrue(libraries.none { it.path == plan.ownSources.getValue(file).path }, "a source is not its own library")
            val result = Compiler(libraries).compile(plan.ownSources.getValue(file).source)
            assertIs<CompilationResult.Success>(result, "$file: " + (result as? CompilationResult.Failure)?.errors?.joinToString("\n"))
        }
    }

    // ── workspaces ──────────────────────────────────────────────────

    private fun workspace() {
        write("ws/workspace.azon", "workspace: { name: \"ws\" }\nmembers: [ \"packages/core\" \"packages/ui\" ]\npackage: { license: \"Apache-2.0\" }")
        library("ws/packages/core", "core", "ws.core", "core", "c")
        write("ws/packages/ui/package.azon", "package: { name: \"ui\" module: \"ws.ui\" }\ndependencies: { core: { path: \"../core\" } }")
        write("ws/packages/ui/src/ui.az", "module ws.ui\n\nimport ws.core\n\nfunc ui(): String = core() + \"u\"")
    }

    @Test
    fun aWorkspaceResolvesItsMembersAgainstOneLockfile() {
        workspace()
        val workspace = resolver().resolveWorkspace(dir("ws"))
        assertEquals(listOf("core", "ui"), workspace.members.map { it.project.unit!!.name })
        assertEquals(listOf(Lockfile.Locked("core", "1.0.0", "path", "packages/core")), workspace.lock.packages)
    }

    @Test
    fun aMemberFindsItsWorkspace() {
        workspace()
        assertEquals(File(root, "ws").canonicalPath, resolver().enclosingWorkspace(dir("ws/packages/ui")))
        library("elsewhere", "elsewhere", "elsewhere", "f", "x")
        assertNull(resolver().enclosingWorkspace(dir("elsewhere")))
    }

    @Test
    fun membersMustAgreeOnASharedPackage() {
        write("ws/workspace.azon", "workspace: { name: \"ws\" }\nmembers: [ \"a\" \"b\" ]")
        library("one/util", "util", "one.util", "f", "1")
        library("two/util", "util", "two.util", "f", "2")
        write("ws/a/azora.azon", "package: { name: \"a\" module: \"a\" }\ndependencies: { util: { path: \"../../one/util\" } }")
        write("ws/b/azora.azon", "package: { name: \"b\" module: \"b\" }\ndependencies: { util: { path: \"../../two/util\" } }")
        val error = assertFailsWith<PackageError> { resolver().resolveWorkspace(dir("ws")) }
        assertTrue(error.message!!.contains("workspace members resolve 'util' differently"), error.message)
    }

    // ── git dependencies ────────────────────────────────────────────

    /** A git that runs here; null skips the git tests unless native qualification is required. */
    private val git: String? by lazy {
        listOfNotNull(System.getenv("AZORA_GIT"), "git", "/usr/bin/git").firstOrNull { candidate ->
            runCatching { ProcessBuilder(candidate, "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
        }.also {
            check(it != null || System.getenv("AZORA_REQUIRE_NATIVE_TESTS") != "1") { "the package manager's git tests require git" }
        }
    }

    /** Runs git in [at] for the test's own repositories, away from the user's configuration. */
    private fun gitIn(at: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf(git!!, "-c", "user.name=azora-test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false") + arguments)
            .directory(at)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    private fun commitAll(repo: File, message: String): String {
        gitIn(repo, "add", "-A")
        gitIn(repo, "commit", "--quiet", "-m", message)
        return gitIn(repo, "rev-parse", "HEAD")
    }

    /** A repository holding the package `words` whose `word()` says [value]; answers its file URL. */
    private fun wordsRepository(value: String): Pair<File, String> {
        library("origin/words", "words", "lib.words", "word", value)
        val repo = File(root, "origin/words")
        gitIn(repo, "init", "--quiet", "-b", "main")
        commitAll(repo, "words $value")
        return repo to "file://${repo.path}"
    }

    private fun setWord(repo: File, value: String): String {
        File(repo, "src/words.az").writeText("module lib.words\n\nfunc word(): String = \"$value\"\n")
        return commitAll(repo, "words $value")
    }

    private val printWord = "import std.io\nimport lib.words\n\nfunc main() {\n    println(word())\n}"

    @Test
    fun aGitDependencyBuildsOnACleanMachine() {
        if (git == null) return
        val (repo, url) = wordsRepository("first")
        val first = gitIn(repo, "rev-parse", "HEAD")
        gitIn(repo, "tag", "v1")
        setWord(repo, "second")
        project("app", "    words: { git: \"$url\" tag: \"v1\" }", printWord)

        assertTrue(!cache.exists(), "the cache starts empty")
        val resolution = resolver().resolve(dir("app"))
        assertEquals(Lockfile.Locked("words", "1.0.0", "git", "", url = url, reference = "tag:v1", rev = first), resolution.lock.packages.single())
        assertEquals("first", runPlan(BuildPlan.of(host, resolution)))
    }

    @Test
    fun theLockfileHoldsABranchUntilUpdate() {
        if (git == null) return
        val (repo, url) = wordsRepository("first")
        project("app", "    words: { git: \"$url\" branch: \"main\" }", printWord)
        val pinned = resolver().resolve(dir("app"))
        File(root, "app/azora.lock").writeText(pinned.lock.render())

        val second = setWord(repo, "second")
        val again = resolver().resolve(dir("app"))
        assertEquals(pinned.lock, again.lock, "a locked commit is used while the manifest asks for the same thing")
        assertEquals("first", runPlan(BuildPlan.of(host, again)))

        val updated = resolver().resolve(dir("app"), update = true)
        assertEquals(second, updated.lock.packages.single().rev)
        assertEquals("second", runPlan(BuildPlan.of(host, updated)))
    }

    @Test
    fun changingTheReferenceResolvesAnew() {
        if (git == null) return
        val (repo, url) = wordsRepository("first")
        val first = gitIn(repo, "rev-parse", "HEAD")
        project("app", "    words: { git: \"$url\" }", printWord)
        File(root, "app/azora.lock").writeText(resolver().resolve(dir("app")).lock.render())
        setWord(repo, "second")
        write("app/azora.azon", "project: { name: \"app\" }\ndependencies: {\n    words: { git: \"$url\" rev: \"$first\" }\n}")
        val resolution = resolver().resolve(dir("app"))
        assertEquals("rev:$first", resolution.lock.packages.single().reference)
        assertEquals(first, resolution.lock.packages.single().rev)
    }

    @Test
    fun aGitDependencyCanBeAWorkspaceMember() {
        if (git == null) return
        write("origin/engine/workspace.azon", "workspace: { name: \"engine\" }\nmembers: [ \"packages/core\" \"packages/ui\" ]")
        library("origin/engine/packages/core", "core", "eng.core", "core", "c")
        write("origin/engine/packages/ui/package.azon", "package: { name: \"ui\" module: \"eng.ui\" }\ndependencies: { core: { path: \"../core\" } }")
        write("origin/engine/packages/ui/src/ui.az", "module eng.ui\n\nimport eng.core\n\nfunc ui(): String = core() + \"-ui\"")
        val repo = File(root, "origin/engine")
        gitIn(repo, "init", "--quiet", "-b", "main")
        val rev = commitAll(repo, "engine")
        val url = "file://${repo.path}"
        project("app", "    ui: { git: \"$url\" }\n    core: { git: \"$url\" }", "import std.io\nimport eng.ui\n\nfunc main() {\n    println(ui())\n}")

        val resolution = resolver().resolve(dir("app"))
        assertEquals(
            listOf(
                Lockfile.Locked("core", "1.0.0", "git", "packages/core", url = url, reference = "default", rev = rev),
                Lockfile.Locked("ui", "0.0.0", "git", "packages/ui", url = url, reference = "default", rev = rev),
            ),
            resolution.lock.packages.sortedBy { it.name },
        )
        assertEquals(1, File(cache, "git/checkouts").listFiles()!!.size, "two packages of one repository share one checkout")
        assertEquals("c-ui", runPlan(BuildPlan.of(host, resolution)))
    }

    @Test
    fun aGitPackageMayNotReachOutOfItsRepository() {
        if (git == null) return
        library("outside", "outside", "outside", "f", "x")
        write("origin/leaky/azora.azon", "package: { name: \"leaky\" module: \"leaky\" }\ndependencies: { outside: { path: \"../../outside\" } }")
        write("origin/leaky/src/leaky.az", "module leaky\n\nfunc g(): String = \"g\"")
        val repo = File(root, "origin/leaky")
        gitIn(repo, "init", "--quiet", "-b", "main")
        commitAll(repo, "leaky")
        project("app", "    leaky: { git: \"file://${repo.path}\" }", "func main() {}")
        val error = assertFailsWith<PackageError> { resolver().resolve(dir("app")) }
        assertTrue(error.message!!.contains("'outside' leaves the repository"), error.message)
    }

    @Test
    fun gitFailuresAreNamed() {
        if (git == null) return
        val (_, url) = wordsRepository("first")
        project("app", "    words: { git: \"$url\" tag: \"v9\" }", printWord)
        val missingTag = assertFailsWith<PackageError> { resolver().resolve(dir("app")) }
        assertTrue(missingTag.message!!.startsWith("git rev-parse failed for words (tag:v9) in $url"), missingTag.message)

        project("app", "    letters: { git: \"$url\" }", printWord)
        val missingPackage = assertFailsWith<PackageError> { resolver().resolve(dir("app")) }
        assertTrue(missingPackage.message!!.contains("has no package named 'letters'"), missingPackage.message)

        project("app", "    words: { git: \"file://${File(root, "nowhere").path}\" }", printWord)
        assertFailsWith<PackageError> { resolver().resolve(dir("app")) }
        assertNotEquals(0, File(cache, "git/db").listFiles()!!.size)
    }
}
