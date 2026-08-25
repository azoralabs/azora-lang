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

package org.azora.lang.stdlib

import java.io.File

/** Files that mark the root of a project, and so the place a vendored `std/` would sit. */
private val WORKSPACE_MARKERS = listOf("workspace.azon", "azora.toml", "package.azon")

/**
 * JVM implementation of the resolution order documented on [AzStdlib].
 *
 * Every candidate is offered whether or not it exists; [readStdlibTree] decides,
 * so "not a directory" and "empty directory" are handled in one place.
 */
internal actual fun stdlibDiskRoots(): List<StdlibRoot> = buildList {
    System.getenv("AZORA_STDLIB")?.takeIf { it.isNotBlank() }?.let {
        add(StdlibRoot(it, "AZORA_STDLIB", explicit = true))
    }
    projectStdlib()?.let { add(StdlibRoot(it, "project-local std/")) }
    System.getenv("AZORA_HOME")?.takeIf { it.isNotBlank() }?.let {
        add(StdlibRoot(File(it, "std").path, "AZORA_HOME"))
    }
    for (root in installPrefixes()) add(StdlibRoot(root, "install prefix"))
}

/**
 * The `std/` of the project the working directory belongs to.
 *
 * Walking up to a workspace marker rather than testing the working directory
 * alone means the answer does not change with which subdirectory a command was
 * run from.
 */
private fun projectStdlib(): String? {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (dir != null) {
        if (WORKSPACE_MARKERS.any { File(dir, it).isFile }) {
            val std = File(dir, "std")
            return if (std.isDirectory) std.path else null
        }
        dir = dir.parentFile
    }
    return null
}

/**
 * Places a `std/` sits relative to an installed compiler.
 *
 * The compiler is launched from a jar under `<prefix>/lib` (or a script under
 * `<prefix>/bin`), so the tree is one or two levels above whatever holds the
 * running code. Both are offered because a source checkout and an installed
 * tree nest differently.
 */
private fun installPrefixes(): List<String> {
    val location = runCatching {
        AzStdlib::class.java.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
    }.getOrNull() ?: return emptyList()
    val base = if (location.isFile) location.parentFile else location
    return listOfNotNull(base?.parentFile, base?.parentFile?.parentFile)
        .map { File(it, "std").path }
}

internal actual fun readStdlibTree(root: String): List<StdlibFile>? {
    val dir = File(root)
    if (!dir.isDirectory) return null
    return dir.walkTopDown()
        .filter { it.isFile && it.extension == "az" }
        .map { StdlibFile(it.relativeTo(dir).path.replace('\\', '/'), it.readText()) }
        .sortedBy { it.path }
        .toList()
}

internal actual fun readStdlibPackageManifest(root: String): String? =
    File(File(root).absoluteFile.parentFile, STDLIB_PACKAGE_MANIFEST)
        .takeIf { it.isFile }
        ?.readText()
