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

import dev.azora.lang.BuildConfig
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The standard library is a file tree the user can point at, replace and read -
 * not a blob welded into the compiler (VERSION_0_1_ROADMAP §4.3).
 *
 * Each test drives the real resolver: it writes a tree to a temporary directory,
 * points the compiler at it, and checks what actually got loaded.
 */
class StdlibResolutionTest {

    @AfterTest
    fun restoreDefaultStdlib() {
        AzStdlib.overrideRoot = null
        AzStdlib.invalidate()
    }

    /** A minimal but real stdlib root: one module and a version marker. */
    private fun writeTree(version: String = BuildConfig.VERSION, moduleBody: String = ""): File {
        val root = File.createTempFile("azstd", "").let { it.delete(); it.mkdirs(); it }
        File(root, STDLIB_VERSION_FILE).writeText("$version\n")
        File(root, "core.az").writeText("module std.core\n$moduleBody")
        return root
    }

    @Test fun readsTheTreeFromDiskRatherThanTheBundle() {
        val root = writeTree(moduleBody = "realm std {\n    func vendoredMarker(): Int { return 7 }\n}\n")
        AzStdlib.overrideRoot = root.path

        val tree = AzStdlib.tree()
        assertEquals("explicit override", tree.origin)
        assertEquals(listOf("core.az"), tree.files.map { it.path })
        assertTrue(
            tree.files.single().source.contains("vendoredMarker"),
            "the resolved tree must be the one on disk, not the bundled standard library",
        )
    }

    @Test fun bundledTreeIsUsedWhenNoDiskRootAnswers() {
        AzStdlib.overrideRoot = null
        AzStdlib.invalidate()
        val tree = AzStdlib.tree()
        assertEquals(BuildConfig.VERSION, tree.version)
        assertTrue(tree.files.size > 1, "the bundled standard library must not be empty")
    }

    @Test fun aStandardLibraryFromAnotherReleaseIsRejected() {
        AzStdlib.overrideRoot = writeTree(version = "9.9.9").path
        val failure = assertFailsWith<IllegalStateException> { AzStdlib.tree() }
        assertContains(failure.message.orEmpty(), "version mismatch")
        assertContains(failure.message.orEmpty(), "9.9.9")
    }

    @Test fun aNamedRootWithoutAVersionMarkerIsRejectedRatherThanSkipped() {
        val root = File.createTempFile("azstd", "").let { it.delete(); it.mkdirs(); it }
        File(root, "core.az").writeText("module std.core\n")
        AzStdlib.overrideRoot = root.path

        val failure = assertFailsWith<IllegalStateException> { AzStdlib.tree() }
        assertContains(failure.message.orEmpty(), STDLIB_VERSION_FILE)
    }

    @Test fun switchingRootsReparsesInsteadOfServingTheOldTree() {
        AzStdlib.overrideRoot = writeTree(moduleBody = "realm std {\n    func first(): Int { return 1 }\n}\n").path
        assertTrue(AzStdlib.sources.single().contains("first"))

        AzStdlib.overrideRoot = writeTree(moduleBody = "realm std {\n    func second(): Int { return 2 }\n}\n").path
        val sources = AzStdlib.sources
        assertTrue(sources.single().contains("second"), "a new root must replace the parsed tree")
        assertTrue(sources.none { it.contains("first") }, "the previous tree must not survive")
    }

    @Test fun theTreeIsParsedOnceAndReusedAcrossCalls() {
        AzStdlib.overrideRoot = writeTree().path
        assertTrue(
            AzStdlib.loadPrograms() === AzStdlib.loadPrograms(),
            "reading from disk must not cost a parse on every compilation",
        )
    }

    @Test fun compileTimeListsAreRebuiltFromTheResolvedTree() {
        AzStdlib.overrideRoot = writeTree(
            moduleBody = "realm std {\n    typealias Pair = [Int, Long]\n}\n",
        ).path
        AzStdlib.loadPrograms()
        assertEquals(listOf("Int", "Long"), AzStdlib.comptimeLists["Pair"])

        // A tree without the list must not inherit it from the one before.
        AzStdlib.overrideRoot = writeTree().path
        AzStdlib.loadPrograms()
        assertEquals(null, AzStdlib.comptimeLists["Pair"])
    }
}
