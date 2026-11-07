/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.packages

import org.azora.lang.LibrarySource
import org.azora.lang.putIfAbsentCompat

/**
 * Resolves a project's dependency graph (PACKAGES_DIP §3).
 *
 * Each dependency names a package: a `path` relative to the manifest that
 * declares it, or a package in a `git` repository - the repository's root
 * package, or the workspace member of that name (the Engine is a workspace).
 * Dependencies are followed transitively; a name stands for one package in the
 * whole graph, and a second package of the same name is an error, as is a
 * cycle. A path dependency of a git package must stay inside its repository.
 *
 * Git repositories are cloned once into the cache (`$AZORA_HOME`, by default
 * `~/.azora`) and checked out per commit. The lockfile's commit is used while
 * the manifest still asks for the same thing; otherwise, and with `update`, the
 * manifest's `rev`, `tag` or `branch` (or the default branch) is resolved anew.
 */
class PackageResolver(private val host: PackageHost, private val progress: (String) -> kotlin.Unit = {}) {

    /** A package the graph resolved to: where it is, and how the lockfile records it. */
    data class Resolved(
        val name: String,
        val version: String,
        val dir: String,
        val srcDir: String,
        val module: String?,
        val native: Manifest.Native,
        val locked: Lockfile.Locked,
    )

    /** A project or package and the packages it depends on, transitively, in dependency order. */
    data class Resolution(val project: Manifest, val packages: List<Resolved>, val lock: Lockfile)

    /**
     * A workspace (PACKAGES_DIP §2.3): its members, each resolved, and the one
     * lockfile at its root that covers them all.
     */
    data class Workspace(val root: Manifest, val members: List<Resolution>, val lock: Lockfile)

    private sealed class Origin {
        data class Local(val projectDir: String) : Origin()
        data class Git(val url: String, val reference: String, val rev: String, val root: String) : Origin()
    }

    /** The manifest in [dir], under any of the names it may have; null when there is none. */
    fun readManifest(dir: String): Manifest? {
        for (name in Manifest.NAMES) {
            val file = Paths.join(dir, name)
            if (host.exists(file)) return Manifest.parse(dir, file, host.readText(file))
        }
        return null
    }

    private fun readLock(dir: String): Lockfile? {
        val lockPath = Paths.join(dir, Lockfile.FILE)
        return if (host.exists(lockPath)) Lockfile.parse(lockPath, host.readText(lockPath)) else null
    }

    /**
     * Resolves the project or package in [projectDir] on its own, against the
     * lockfile beside it. A workspace member resolves through [resolveWorkspace]
     * instead, so that it shares the workspace's lockfile.
     */
    fun resolve(projectDir: String, update: Boolean = false): Resolution {
        val dir = host.canonical(projectDir)
        val manifest = readManifest(dir)
            ?: throw PackageError("$dir has no ${Manifest.FILE}; create a project with 'azora new <name>'")
        return resolveUnit(manifest, dir, readLock(dir), update)
    }

    /**
     * The root of the workspace that lists [dir] as a member, searching the
     * directories above it; null when no workspace does.
     */
    fun enclosingWorkspace(dir: String): String? {
        val member = host.canonical(dir)
        var at = Paths.parent(member)
        while (true) {
            val manifest = try {
                readManifest(at)
            } catch (_: PackageError) {
                null
            }
            if (manifest != null && manifest.isWorkspace && manifest.members.any { host.canonical(Paths.join(at, it)) == member }) return at
            if (at == "/") return null
            at = Paths.parent(at)
        }
    }

    /**
     * Resolves every member of the workspace at [rootDir] against the one
     * lockfile at its root. Members may depend on each other by path; a
     * package the members share must resolve to the same source for all of them.
     */
    fun resolveWorkspace(rootDir: String, update: Boolean = false): Workspace {
        val root = host.canonical(rootDir)
        val manifest = readManifest(root)
            ?: throw PackageError("$root has no ${Manifest.FILE}")
        if (!manifest.isWorkspace || manifest.members.isEmpty()) throw PackageError("${manifest.file} lists no workspace members")
        val lock = readLock(root)
        val members = manifest.members.map { member ->
            val dir = host.canonical(Paths.join(root, member))
            val memberManifest = readManifest(dir)
                ?: throw PackageError("${manifest.file}: the member $member has no ${Manifest.FILE}")
            resolveUnit(memberManifest, root, lock, update)
        }
        val locked = LinkedHashMap<String, Lockfile.Locked>()
        for (resolution in members) {
            for (entry in resolution.lock.packages) {
                val earlier = locked.putIfAbsentCompat(entry.name, entry)
                if (earlier != null && earlier != entry) {
                    throw PackageError(
                        "workspace members resolve '${entry.name}' differently: ${describe(earlier)} and ${describe(entry)}",
                    )
                }
            }
        }
        return Workspace(manifest, members, Lockfile(locked.values.toList()))
    }

    private fun describe(locked: Lockfile.Locked): String =
        if (locked.source == "git") "${locked.url} at ${locked.rev?.take(12)}" else "path ${locked.path}"

    /**
     * Resolves [manifest]'s dependency graph. [lockRoot] is the directory the
     * lockfile lives in: path packages are recorded relative to it.
     */
    private fun resolveUnit(manifest: Manifest, lockRoot: String, lock: Lockfile?, update: Boolean): Resolution {
        val root = lockRoot
        val unit = manifest.unit ?: throw PackageError("${manifest.file} declares no project or package")
        val resolved = LinkedHashMap<String, Resolved>()
        val visiting = mutableListOf<String>()

        fun visit(dependency: Manifest.Dependency, from: Manifest, origin: Origin) {
            if (dependency.name in visiting) {
                throw PackageError("dependency cycle: ${(visiting + dependency.name).joinToString(" -> ")}")
            }
            val (dir, packageOrigin) = if (dependency.path != null) {
                val dir = host.canonical(Paths.join(from.dir, dependency.path))
                if (origin is Origin.Git && dir != origin.root && !dir.startsWith(origin.root + "/")) {
                    throw PackageError("'${dependency.name}' leaves the repository ${origin.url}: path ${dependency.path} from ${from.dir}")
                }
                dir to origin
            } else {
                val (checkout, git) = checkout(dependency, lock, update)
                packageIn(checkout, dependency.name, dependency.git!!) to git
            }
            val packageManifest = readManifest(dir) ?: throw PackageError("dependency '${dependency.name}': $dir has no ${Manifest.FILE}")
            val declared = packageManifest.unit ?: throw PackageError("dependency '${dependency.name}': ${packageManifest.file} declares no package")
            if (declared.name != dependency.name) {
                throw PackageError("dependency '${dependency.name}' is the package '${declared.name}' (${packageManifest.file})")
            }
            resolved[dependency.name]?.let { existing ->
                if (existing.dir != dir) throw PackageError("two packages are named '${dependency.name}': ${existing.dir} and $dir")
                return
            }
            visiting += dependency.name
            for (next in packageManifest.dependencies) visit(next, packageManifest, packageOrigin)
            visiting.removeAt(visiting.lastIndex)
            val locked = when (packageOrigin) {
                is Origin.Local -> Lockfile.Locked(declared.name, declared.version, "path", Paths.relative(root, dir))
                is Origin.Git -> Lockfile.Locked(
                    declared.name, declared.version, "git",
                    Paths.relative(packageOrigin.root, dir).let { if (it == ".") "" else it },
                    url = packageOrigin.url, reference = packageOrigin.reference, rev = packageOrigin.rev,
                )
            }
            resolved[dependency.name] = Resolved(
                declared.name, declared.version, dir, Paths.join(dir, declared.src), declared.module, packageManifest.native, locked,
            )
        }

        visiting += unit.name
        for (dependency in manifest.dependencies) visit(dependency, manifest, Origin.Local(manifest.dir))
        val packages = resolved.values.toList()
        return Resolution(manifest, packages, Lockfile(packages.map { it.locked }))
    }

    /** The package named [name] in the repository checked out at [checkout]. */
    private fun packageIn(checkout: String, name: String, url: String): String {
        val top = readManifest(checkout)
        if (top?.unit?.name == name) return checkout
        for (member in top?.members.orEmpty()) {
            val dir = host.canonical(Paths.join(checkout, member))
            if (readManifest(dir)?.unit?.name == name) return dir
        }
        throw PackageError("the repository $url has no package named '$name'")
    }

    private val cache: String get() = host.environment("AZORA_HOME") ?: Paths.join(host.home, ".azora")

    /**
     * The first git that runs: `AZORA_GIT`, then `git` from `PATH`, then the
     * system's. A `PATH` can lead to a git this machine cannot execute (an
     * x86-only build on Apple silicon without Rosetta); that one is passed over.
     */
    private val gitTool: String by lazy {
        val candidates = listOfNotNull(host.environment("AZORA_GIT"), "git", "/usr/bin/git")
        candidates.firstOrNull { host.run(listOf(it, "--version")).exitCode == 0 }
            ?: throw PackageError("git dependencies need git, and none of ${candidates.joinToString()} runs; set AZORA_GIT to a git that does")
    }

    private fun git(arguments: List<String>, what: String): String {
        val result = host.run(listOf(gitTool) + arguments)
        if (result.exitCode != 0) {
            // The subcommand: past `-C <dir>`, the first word that is not an option.
            val subcommand = arguments.drop(if (arguments.firstOrNull() == "-C") 2 else 0).first { !it.startsWith("-") }
            throw PackageError("git $subcommand failed for $what:\n${result.output.trim()}")
        }
        return result.output.trim()
    }

    private fun gitSucceeds(arguments: List<String>): Boolean = host.run(listOf(gitTool) + arguments).exitCode == 0

    /** A checkout of [dependency]'s repository at the commit the lock or the manifest names. */
    private fun checkout(dependency: Manifest.Dependency, lock: Lockfile?, update: Boolean): Pair<String, Origin.Git> {
        val url = dependency.git!!
        // One clone per repository and one checkout per commit, whichever of
        // its packages a dependency names: two packages of one repository at
        // one commit share their sibling packages rather than doubling them.
        val repository = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").filter { it.isLetterOrDigit() || it in "-_" }
        val id = repository.ifEmpty { "repository" } + "-" + fingerprint(url)
        val db = Paths.join(cache, "git/db/$id")
        var fresh = false
        if (!host.exists(db)) {
            progress("Fetching $url")
            host.createDirectories(Paths.parent(db))
            git(listOf("clone", "--bare", "--quiet", url, db), url)
            fresh = true
        }
        fun fetch() = git(listOf("-C", db, "fetch", "--quiet", "--force", "--tags", url, "+refs/heads/*:refs/heads/*"), url)
        fun has(commit: String) = gitSucceeds(listOf("-C", db, "cat-file", "-e", "$commit^{commit}"))

        val locked = lock?.find(dependency.name)?.takeIf {
            !update && it.source == "git" && it.url == url && it.reference == dependency.reference && it.rev != null
        }
        val rev = if (locked != null) {
            val commit = locked.rev!!
            if (!has(commit)) {
                fetch()
                if (!has(commit)) throw PackageError("$url has no commit $commit, which ${Lockfile.FILE} pins for '${dependency.name}'")
            }
            commit
        } else {
            if (!fresh) {
                progress("Updating $url")
                fetch()
            }
            val wanted = when {
                dependency.rev != null -> dependency.rev
                dependency.tag != null -> "refs/tags/${dependency.tag}"
                dependency.branch != null -> "refs/heads/${dependency.branch}"
                else -> "HEAD"
            }
            git(listOf("-C", db, "rev-parse", "--verify", "--quiet", "$wanted^{commit}"), "${dependency.name} (${dependency.reference}) in $url")
        }
        val checkout = Paths.join(cache, "git/checkouts/$id/${rev.take(12)}")
        if (!host.exists(Paths.join(checkout, ".git"))) {
            progress("Checking out ${dependency.name} at ${rev.take(12)}")
            host.createDirectories(Paths.parent(checkout))
            git(listOf("clone", "--quiet", "--no-checkout", db, checkout), url)
            git(listOf("-C", checkout, "checkout", "--quiet", "--detach", rev), url)
        }
        val root = host.canonical(checkout)
        return root to Origin.Git(url, dependency.reference, rev, root)
    }

    /** A short, stable name for [url] in the cache: FNV-1a, 64 bits. */
    private fun fingerprint(url: String): String {
        var hash = -0x340d631b7bdddcdbL
        for (byte in url.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}

/**
 * What compiling a resolved project or package takes: its own sources, the
 * sources of every package it depends on as library modules, and what the
 * program links (PACKAGES_DIP §5).
 *
 * A project compiles from its `entry` file. A package has no entry: each of
 * its sources is compiled in turn as the entry, with the others as library
 * modules, because the compiler checks a library module only as far as the
 * entry uses it.
 */
data class BuildPlan(
    val name: String,
    val projectDir: String,
    val isPackage: Boolean,
    /** The project's entry file; null for a package. */
    val entryPath: String?,
    /** The project's or package's own sources: absolute path to the library module it is. */
    val ownSources: Map<String, LibrarySource>,
    val dependencySources: List<LibrarySource>,
    val frameworks: List<String>,
    val libs: List<String>,
    val cSources: List<String>,
) {
    /** The libraries [file] (one of [ownSources]) compiles with as the entry: the other own sources, then the packages'. */
    fun librariesFor(file: String): List<LibrarySource> =
        ownSources.filterKeys { it != file && it != entryPath }.values.toList() + dependencySources

    companion object {
        /**
         * A package's sources are placed under its module path (`engine.ecs` →
         * `engine/ecs/…`), where their module declarations say they are; a
         * project's keep their place under its `src`.
         */
        private fun sources(host: PackageHost, srcDir: String, module: String?): Map<String, LibrarySource> {
            val prefix = module?.replace('.', '/')
            return host.azoraSources(srcDir).associateWith { file ->
                val relative = Paths.relative(srcDir, file)
                LibrarySource(if (prefix == null) relative else "$prefix/$relative", host.readText(file))
            }
        }

        fun of(host: PackageHost, resolution: PackageResolver.Resolution): BuildPlan {
            val manifest = resolution.project
            val unit = manifest.unit!!
            val srcDir = Paths.join(manifest.dir, unit.src)
            val isPackage = manifest.project == null
            val own = sources(host, srcDir, if (isPackage) unit.module else null)
            val entryPath = if (isPackage) {
                if (own.isEmpty()) throw PackageError("${manifest.file}: the package '${unit.name}' has no sources under ${unit.src}")
                null
            } else {
                Paths.join(srcDir, unit.entry).also {
                    if (it !in own) throw PackageError("${manifest.file}: the entry ${unit.src}/${unit.entry} does not exist")
                }
            }
            val dependencySources = mutableListOf<LibrarySource>()
            val frameworks = linkedSetOf<String>()
            val libs = linkedSetOf<String>()
            val cSources = linkedSetOf<String>()
            for (p in resolution.packages) {
                dependencySources += sources(host, p.srcDir, p.module).values
                frameworks += p.native.frameworks
                libs += p.native.libs
                for (source in p.native.sources) cSources += host.canonical(Paths.join(p.dir, source))
            }
            frameworks += manifest.native.frameworks
            libs += manifest.native.libs
            for (source in manifest.native.sources) cSources += host.canonical(Paths.join(manifest.dir, source))
            return BuildPlan(
                unit.name, manifest.dir, isPackage, entryPath, own, dependencySources,
                frameworks.toList(), libs.toList(), cSources.toList(),
            )
        }
    }
}
