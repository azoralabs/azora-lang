/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.packages

/**
 * `azora.lock` (PACKAGES_DIP §4): every package a project resolved to, with
 * the exact commit of each git one, so a second build - on this machine or a
 * clean one - compiles the same sources. Written by `azora build`, `add` and
 * `remove`; never edited by hand.
 */
data class Lockfile(val packages: List<Locked>) {
    /**
     * One package. A `path` package records its directory relative to the
     * project; a `git` one its repository, what the manifest asked for
     * (`reference`), the commit that answered (`rev`) and where the package
     * sits in the repository (`path`, empty for its root).
     */
    data class Locked(
        val name: String,
        val version: String,
        val source: String,
        val path: String,
        val url: String? = null,
        val reference: String? = null,
        val rev: String? = null,
    )

    fun render(): String = buildString {
        appendLine("// azora.lock - written by azora build, add and remove; do not edit.")
        appendLine("version: 1")
        appendLine("packages: [")
        for (p in packages.sortedBy { it.name }) {
            append("    { name: ${Azon.quote(p.name)} version: ${Azon.quote(p.version)} source: ${Azon.quote(p.source)} path: ${Azon.quote(p.path)}")
            p.url?.let { append(" url: ${Azon.quote(it)}") }
            p.reference?.let { append(" reference: ${Azon.quote(it)}") }
            p.rev?.let { append(" rev: ${Azon.quote(it)}") }
            appendLine(" }")
        }
        appendLine("]")
    }

    fun find(name: String): Locked? = packages.firstOrNull { it.name == name }

    companion object {
        const val FILE = "azora.lock"

        fun parse(file: String, source: String): Lockfile {
            val doc = try {
                Azon.parse(source)
            } catch (e: AzonError) {
                throw PackageError("$file: ${e.message}")
            }
            val version = (doc["version"] as? AzonValue.Num)?.raw
            if (version != "1") throw PackageError("$file: lockfile version ${version ?: "missing"} is not one this azora reads (1)")
            val entries = (doc["packages"] as? AzonValue.Arr)?.elements.orEmpty().map { value ->
                val o = value as? AzonValue.Obj ?: throw PackageError("$file: a package entry is not an object")
                fun field(key: String) = o[key]?.text
                Locked(
                    name = field("name") ?: throw PackageError("$file: a package entry has no name"),
                    version = field("version") ?: "0.0.0",
                    source = field("source") ?: throw PackageError("$file: '${field("name")}' has no source"),
                    path = field("path") ?: "",
                    url = field("url"),
                    reference = field("reference"),
                    rev = field("rev"),
                )
            }
            return Lockfile(entries)
        }
    }
}
