/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.packages

/**
 * A package manifest, `azora.azon` (PACKAGES_DIP §2).
 *
 * ```azon
 * project: { name: "my-game" version: "0.1.0" entry: "main.az" src: "src" }
 * dependencies: {
 *     azora-engine: { git: "https://example.org/azora-engine.git" tag: "v0.1.0" }
 *     my-lib: { path: "../my-lib" }
 * }
 * ```
 *
 * A directory holds a project (`project:`, something that is built and run),
 * a package (`package:`, something depended on; `module:` names the module
 * prefix its sources declare), or a workspace (`workspace:` with `members:`,
 * the directories of its packages). `native:` lists what a package links:
 * system `frameworks` and `libs`, and C `sources` compiled into the program.
 * `package.azon` and `workspace.azon` (the Engine's names) are read as
 * `azora.azon` is; see [NAMES].
 */
data class Manifest(
    val dir: String,
    val file: String,
    val source: String,
    val document: AzonValue.Obj,
    val project: Identity?,
    val pkg: Identity?,
    val dependencies: List<Dependency>,
    val native: Native,
    val members: List<String>,
) {
    /** The project or package this manifest declares, if it declares one. */
    val unit: Identity? get() = project ?: pkg
    val isWorkspace: Boolean get() = document["workspace"] != null || members.isNotEmpty()

    /** A project or package: what it is called and where its sources are. */
    data class Identity(val name: String, val version: String, val module: String?, val entry: String, val src: String)

    /** A dependency as written: a local `path`, or a `git` repository at a `rev`, `tag` or `branch`. */
    data class Dependency(
        val name: String,
        val path: String? = null,
        val git: String? = null,
        val rev: String? = null,
        val tag: String? = null,
        val branch: String? = null,
    ) {
        /** What the manifest asks git for, as the lockfile records it. */
        val reference: String get() = rev?.let { "rev:$it" } ?: tag?.let { "tag:$it" } ?: branch?.let { "branch:$it" } ?: "default"

        fun render(): String = buildString {
            append("{ ")
            path?.let { append("path: ${Azon.quote(it)} ") }
            git?.let { append("git: ${Azon.quote(it)} ") }
            rev?.let { append("rev: ${Azon.quote(it)} ") }
            tag?.let { append("tag: ${Azon.quote(it)} ") }
            branch?.let { append("branch: ${Azon.quote(it)} ") }
            append("}")
        }
    }

    data class Native(val frameworks: List<String> = emptyList(), val libs: List<String> = emptyList(), val sources: List<String> = emptyList())

    /** This manifest's text with [dependency] added, or replacing one of the same name. */
    fun withDependency(dependency: Dependency): String {
        val text = withoutDependency(dependency.name)
        val reread = parse(dir, file, text)
        val member = "${Azon.key(dependency.name)}: ${dependency.render()}"
        val block = reread.document["dependencies"] as? AzonValue.Obj
        if (block != null) {
            val indent = block.members.firstOrNull()?.let { indentation(text, it.start) } ?: "    "
            val close = block.end - 1
            val lineStart = text.lastIndexOf('\n', close - 1) + 1
            // Before the closing brace's own line when it stands alone, else just before it.
            return if (text.substring(lineStart, close).isBlank()) {
                text.substring(0, lineStart) + indent + member + "\n" + text.substring(lineStart)
            } else {
                text.substring(0, close) + " " + member + " " + text.substring(close)
            }
        }
        val addition = "dependencies: {\n    $member\n}\n"
        val doc = reread.document
        // An explicit `{ … }` document takes the member inside its braces.
        if (text.trimStart().startsWith("{")) {
            val close = doc.end - 1
            return text.substring(0, close) + "\n" + addition + text.substring(close)
        }
        val body = text.trimEnd()
        return (if (body.isEmpty()) "" else body + "\n\n") + addition
    }

    /** This manifest's text without the dependency named [name]; unchanged when there is none. */
    fun withoutDependency(name: String): String {
        val block = document["dependencies"] as? AzonValue.Obj ?: return source
        val member = block.members.lastOrNull { it.key == name } ?: return source
        var from = member.start
        var to = member.value.end
        val lineStart = source.lastIndexOf('\n', from - 1) + 1
        val lineEnd = source.indexOf('\n', to).let { if (it < 0) source.length else it }
        // A member alone on its lines takes those lines with it.
        if (source.substring(lineStart, from).isBlank() && source.substring(to, lineEnd).trim().trimStart(',').isEmpty()) {
            from = lineStart
            to = if (lineEnd < source.length) lineEnd + 1 else lineEnd
        } else if (source.getOrNull(to) == ',') {
            to++
        }
        return source.substring(0, from) + source.substring(to)
    }

    private fun indentation(text: String, at: Int): String {
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        return text.substring(lineStart, at).takeWhile { it == ' ' || it == '\t' }
    }

    companion object {
        /** The manifest a project or package is created with and edited in. */
        const val FILE = "azora.azon"

        /**
         * Every name a manifest may have, in the order a directory's manifest
         * is chosen when it has more than one - the order AZLS uses too. The
         * Engine's manifests are `package.azon` and `workspace.azon`.
         */
        val NAMES = listOf("package.azon", "workspace.azon", FILE)

        fun parse(dir: String, file: String, source: String): Manifest {
            val doc = try {
                Azon.parse(source)
            } catch (e: AzonError) {
                throw PackageError("$file: ${e.message}")
            }
            val workspace = doc["workspace"] != null || doc["members"] != null
            fun unit(key: String): Identity? {
                val obj = doc[key] as? AzonValue.Obj ?: return null
                // A workspace's nameless `package:` is metadata its members
                // inherit (authors, license), not a package of its own.
                if (workspace && obj["name"] == null) return null
                val name = obj["name"]?.text ?: throw PackageError("$file: '$key' has no name")
                return Identity(
                    name = name,
                    version = obj["version"]?.text ?: obj["version"]?.let { (it as? AzonValue.Num)?.raw } ?: "0.0.0",
                    module = obj["module"]?.text,
                    entry = obj["entry"]?.text ?: "main.az",
                    src = obj["src"]?.text ?: "src",
                )
            }
            val dependencies = (doc["dependencies"] as? AzonValue.Obj)?.members.orEmpty().map { member ->
                val spec = member.value as? AzonValue.Obj
                    ?: throw PackageError("$file: dependency '${member.key}' must be an object such as { path: \"…\" }")
                val dependency = Dependency(
                    name = member.key,
                    path = spec["path"]?.text,
                    git = spec["git"]?.text,
                    rev = spec["rev"]?.text,
                    tag = spec["tag"]?.text,
                    branch = spec["branch"]?.text,
                )
                if ((dependency.path == null) == (dependency.git == null)) {
                    throw PackageError("$file: dependency '${member.key}' needs exactly one of 'path' and 'git'")
                }
                if (listOfNotNull(dependency.rev, dependency.tag, dependency.branch).size > 1) {
                    throw PackageError("$file: dependency '${member.key}' names more than one of 'rev', 'tag' and 'branch'")
                }
                dependency
            }
            fun strings(value: AzonValue?): List<String> = (value as? AzonValue.Arr)?.elements.orEmpty().mapNotNull { it.text }
            val native = (doc["native"] as? AzonValue.Obj)?.let { Native(strings(it["frameworks"]), strings(it["libs"]), strings(it["sources"])) } ?: Native()
            val members = strings(doc["members"]).ifEmpty { strings((doc["workspace"] as? AzonValue.Obj)?.get("members")) }
            val project = unit("project")
            val pkg = unit("package")
            if (project != null && pkg != null) throw PackageError("$file: declares both 'project' and 'package'")
            return Manifest(dir, file, source, doc, project, pkg, dependencies, native, members)
        }
    }
}

/** A manifest, lockfile or dependency the package manager cannot use, said in a sentence. */
class PackageError(message: String) : RuntimeException(message)
