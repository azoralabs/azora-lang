/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.azls

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The editor reads manifests as `azora build` does (PACKAGES_DIP §2). */
class WorkspaceCatalogTest {
    private fun write(root: Path, path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
    }

    @Test
    fun everyPathDependencyIsIndexedNotOnlyTheFirst() {
        val root = Files.createTempDirectory("azls-catalog-").toRealPath()
        write(
            root, "app/package.azon",
            """
            // a game
            package: { name: "app" module: "app" }
            dependencies: {
                // first
                core: { path: "../core" }
                ui: { path: "../ui" }   /* second */
            }
            """,
        )
        write(root, "app/src/app.az", "module app\n\nimport lib.core\nimport lib.ui\n")
        write(root, "core/package.azon", "package: { name: \"core\" module: \"lib.core\" }")
        write(root, "core/src/core.az", "module lib.core\n\nfunc core(): Int = 1\n")
        write(root, "ui/package.azon", "package: { name: \"ui\" module: \"lib.ui\" }")
        write(root, "ui/src/ui.az", "module lib.ui\n\nfunc ui(): Int = 2\n")

        val catalog = WorkspaceCatalog.scan(listOf(root))
        val target = root.resolve("app/src/app.az")
        assertEquals(listOf(root.resolve("core"), root.resolve("ui")), catalog.ownerOf(target)!!.dependencyRoots)
        val indexed = catalog.indexSources(target).map { root.relativize(it.path).toString() }.toSet()
        assertTrue(indexed.containsAll(setOf("app/src/app.az", "core/src/core.az", "ui/src/ui.az")), indexed.toString())
    }

    @Test
    fun aManifestThatDoesNotParseStillBoundsItsPackage() {
        val root = Files.createTempDirectory("azls-catalog-").toRealPath()
        write(root, "broken/azora.azon", "project: { name: \"broken\"")
        write(root, "broken/src/main.az", "func main() {}\n")
        write(root, "other/main.az", "func other() {}\n")
        val logged = mutableListOf<String>()
        val catalog = WorkspaceCatalog.scan(listOf(root), log = { logged += it })
        assertTrue(logged.any { "azora.azon" in it }, logged.toString())
        val analysed = catalog.analysisSources(root.resolve("broken/src/main.az")).map { root.relativize(it.path).toString() }
        assertEquals(listOf("broken/src/main.az"), analysed)
    }
}
