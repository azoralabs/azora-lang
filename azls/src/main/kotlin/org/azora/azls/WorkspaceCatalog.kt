/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.azls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.azora.lang.diagnostics.SourceId
import org.azora.lang.diagnostics.SourceKind
import org.azora.lang.diagnostics.SourceUnit
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Manifest-backed view of the Azora sources in an editor workspace. */
internal class WorkspaceCatalog private constructor(
    val roots: List<Path>,
    private val packages: List<WorkspacePackage>,
    val sources: List<WorkspaceDiskSource>,
) {
    fun ownerOf(path: Path?): WorkspacePackage? {
        val normalized = path?.toAbsolutePath()?.normalize() ?: return null
        return packages
            .filter { candidate -> candidate.sourceRoots.any(normalized::startsWith) }
            .maxByOrNull { it.root.nameCount }
    }

    /** Sources which may affect compilation of [target]. */
    fun analysisSources(target: Path?): List<WorkspaceDiskSource> {
        val normalized = target?.toAbsolutePath()?.normalize() ?: return emptyList()
        val owner = ownerOf(normalized)
        return if (owner != null) {
            sources.filter { it.owner == owner }
        } else if (packages.isEmpty()) {
            // A manifest is optional for a small project. With no package
            // boundaries anywhere in the workspace, the workspace folder is
            // the source root and cross-directory modules remain visible.
            sources
        } else {
            // A standalone self-hosting/compiler fixture must not accidentally
            // acquire every package in a monorepo. Its physical directory is
            // the only source root known for certain.
            sources.filter { it.owner == null && it.path.parent == normalized.parent }
        }
    }

    /**
     * Sources used for completion/navigation. The current package comes first,
     * followed by the workspace's local `std` package. This ordering makes a
     * checked-out standard library win over any SDK/bundled representation.
     */
    fun indexSources(target: Path?): List<WorkspaceDiskSource> {
        val normalized = target?.toAbsolutePath()?.normalize()
        val owner = ownerOf(normalized)
        val selected = LinkedHashMap<Path, WorkspaceDiskSource>()
        analysisSources(normalized).forEach { selected[it.path] = it }
        packages.filter { it.name == "std" || it.module == "std" }
            .sortedByDescending { it.root.nameCount }
            .forEach { standard ->
                sources.filter { it.owner == standard }.forEach { selected.putIfAbsent(it.path, it) }
            }
        // Package dependencies declared by path are part of the same index.
        owner?.dependencyRoots?.forEach { dependencyRoot ->
            sources.filter { it.path.startsWith(dependencyRoot) }.forEach { selected.putIfAbsent(it.path, it) }
        }
        return selected.values.toList()
    }

    fun analysisContains(target: Path?, candidate: Path): Boolean =
        analysisSources(target).any { it.path == candidate.toAbsolutePath().normalize() }

    fun indexContains(target: Path?, candidate: Path): Boolean =
        indexSources(target).any { it.path == candidate.toAbsolutePath().normalize() }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val manifestNames = setOf("package.azon", "workspace.azon", "azora.azon", "azora.toml")
        private val ignoredDirectories = setOf(
            ".git", ".gradle", ".idea", ".kotlin", ".azora", "build", "out", "target",
            "node_modules", "dist", "generated", ".cache",
        )

        fun scan(roots: List<Path>, limit: Int = 2_000, log: (String) -> Unit = {}): WorkspaceCatalog {
            val normalizedRoots = roots.map { it.toAbsolutePath().normalize() }.distinct()
            val manifests = mutableListOf<Path>()
            val azoraFiles = mutableListOf<Path>()
            for (root in normalizedRoots) {
                if (!Files.isDirectory(root)) continue
                runCatching {
                    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (dir != root && dir.fileName?.toString() in ignoredDirectories) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            val name = file.fileName.toString()
                            if (name in manifestNames) manifests.add(file.toAbsolutePath().normalize())
                            if (name.endsWith(".az") && azoraFiles.size < limit) {
                                azoraFiles.add(file.toAbsolutePath().normalize())
                            }
                            return if (azoraFiles.size >= limit) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                        }
                    })
                }.onFailure { log("workspace scan failed for $root: ${it.message}") }
            }

            // One manifest owns a directory. Prefer the canonical package file
            // over compatibility manifests when both exist.
            val manifestPriority = mapOf("package.azon" to 0, "workspace.azon" to 1, "azora.azon" to 2, "azora.toml" to 3)
            val packageConfigs = manifests
                .groupBy { it.parent }
                .mapNotNull { (_, candidates) ->
                    val manifest = candidates.minByOrNull { manifestPriority[it.fileName.toString()] ?: Int.MAX_VALUE }
                        ?: return@mapNotNull null
                    readPackage(manifest, log)
                }
                .sortedByDescending { it.root.nameCount }

            val diskSources = azoraFiles.sortedBy(Path::toString).map { path ->
                val owner = packageConfigs.firstOrNull { pkg -> pkg.sourceRoots.any(path::startsWith) }
                val displayPath = owner?.root?.let { root ->
                    runCatching { root.relativize(path).toString() }.getOrDefault(path.toString())
                } ?: normalizedRoots.firstOrNull(path::startsWith)?.let { root ->
                    runCatching { root.relativize(path).toString() }.getOrDefault(path.toString())
                } ?: path.toString()
                val uri = path.toUri().toString()
                WorkspaceDiskSource(
                    path,
                    SourceUnit(
                        SourceId(uri), uri, displayPath, Files.readString(path), null, SourceKind.WORKSPACE_LIBRARY,
                    ),
                    owner,
                )
            }
            return WorkspaceCatalog(normalizedRoots, packageConfigs, diskSources)
        }

        private fun readPackage(manifest: Path, log: (String) -> Unit): WorkspacePackage? {
            val text = runCatching { Files.readString(manifest) }
                .onFailure { log("cannot read ${manifest.fileName}: ${it.message}") }
                .getOrNull() ?: return null
            val root = manifest.parent.toAbsolutePath().normalize()
            val values = if (manifest.fileName.toString() == "azora.toml") {
                parseToml(text)
            } else {
                parseAzon(text)
            }
            val name = values.name
            val module = values.module.ifBlank { name.replace('-', '.') }
            val sourceRoots = LinkedHashSet<Path>()
            values.sourceDirs.forEach { sourceRoots.add(root.resolve(it).normalize()) }
            if (sourceRoots.isEmpty()) {
                val named = name.takeIf(String::isNotBlank)?.let(root::resolve)
                when {
                    named != null && Files.isDirectory(named) -> sourceRoots.add(named.normalize())
                    Files.isDirectory(root.resolve("src")) -> sourceRoots.add(root.resolve("src").normalize())
                    else -> sourceRoots.add(root)
                }
            }
            val dependencyRoots = values.dependencyPaths.map { root.resolve(it).normalize() }
            return WorkspacePackage(manifest, root, name, values.version, module, sourceRoots.toList(), dependencyRoots)
        }

        private fun parseAzon(text: String): ManifestValues {
            runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { document ->
                val body = (document["package"] ?: document["project"] ?: document["workspace"]) as? JsonObject
                    ?: document
                val name = body.string("name")
                val version = body.string("version")
                val module = body.string("module")
                val sourceDirs = buildList {
                    body.string("src").takeIf(String::isNotBlank)?.let(::add)
                    (document["test"] as? JsonObject)?.string("src")?.takeIf(String::isNotBlank)?.let(::add)
                }
                val dependencyPaths = (document["dependencies"] as? JsonObject).orEmpty().values.mapNotNull { value ->
                    when (value) {
                        is kotlinx.serialization.json.JsonPrimitive -> value.contentOrNull
                        is JsonObject -> value.string("path").takeIf(String::isNotBlank)
                        else -> null
                    }
                }
                return ManifestValues(name, version, module, sourceDirs, dependencyPaths)
            }
            val packageBody = objectBody(text, "package") ?: objectBody(text, "project") ?: text
            val name = stringValue(packageBody, "name")
            val version = stringValue(packageBody, "version")
            val module = stringValue(packageBody, "module")
            val sourceDirs = listOfNotNull(stringValue(packageBody, "src").takeIf(String::isNotBlank))
            val dependencyPaths = Regex("""(?m)\bpath\s*:\s*"([^"]+)"""")
                .findAll(objectBody(text, "dependencies").orEmpty())
                .map { it.groupValues[1] }
                .toList()
            return ManifestValues(name, version, module, sourceDirs, dependencyPaths)
        }

        private fun parseToml(text: String): ManifestValues {
            var inProject = false
            var name = ""
            var version = ""
            var module = ""
            var src = ""
            text.lineSequence().forEach { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.startsWith('[') && line.endsWith(']')) {
                    inProject = line == "[project]" || line == "[package]"
                } else if (inProject && '=' in line) {
                    val key = line.substringBefore('=').trim()
                    val value = line.substringAfter('=').trim().removeSurrounding("\"")
                    when (key) {
                        "name" -> name = value
                        "version" -> version = value
                        "module" -> module = value
                        "src" -> src = value
                    }
                }
            }
            return ManifestValues(name, version, module, listOfNotNull(src.takeIf(String::isNotBlank)), emptyList())
        }

        private fun objectBody(text: String, key: String): String? =
            Regex("(?s)\\b${Regex.escape(key)}\\s*:\\s*\\{(.*?)\\}").find(text)?.groupValues?.get(1)

        private fun stringValue(text: String, key: String): String =
            Regex("""(?m)\b${Regex.escape(key)}\s*:\s*"([^"]*)"""").find(text)?.groupValues?.get(1).orEmpty()

        private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    }
}

internal data class WorkspacePackage(
    val manifest: Path,
    val root: Path,
    val name: String,
    val version: String,
    val module: String,
    val sourceRoots: List<Path>,
    val dependencyRoots: List<Path>,
)

internal data class WorkspaceDiskSource(
    val path: Path,
    val unit: SourceUnit,
    val owner: WorkspacePackage?,
)

private data class ManifestValues(
    val name: String,
    val version: String,
    val module: String,
    val sourceDirs: List<String>,
    val dependencyPaths: List<String>,
)
