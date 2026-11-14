/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.stdlib

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `std/STABLE_SURFACE.txt` lists exactly what std's sources declare stable
 * (STABILITY_DIP §2), so a change to what 0.1 promises is a visible change to
 * that file in review rather than a side effect of an edit elsewhere.
 */
class StdSurfaceTest {
    private val root = File("../std")
    private val listing = File(root, "STABLE_SURFACE.txt")

    private fun sources(): List<StdSurface.Source> = root.walkTopDown()
        .filter { it.isFile && it.extension == "az" }
        .map { StdSurface.Source(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
        .toList()
        .also { assertTrue(it.isNotEmpty(), "no std sources under ${root.absolutePath}") }

    /**
     * A stable declaration cannot hand out an experimental type: the promise
     * would hold only until the type changes. Either the type is stable too or
     * the declaration is experimental.
     */
    @Test fun noStableDeclarationNamesAnExperimentalType() {
        val contradictions = StdSurface.contradictions(sources())
        assertTrue(contradictions.isEmpty(), contradictions.joinToString("\n", "Stable std declarations naming experimental types:\n"))
    }

    @Test fun theCheckedInListingIsWhatStdDeclares() {
        val generated = StdSurface.render(sources())
        if (System.getenv("AZORA_REGENERATE_SURFACE") == "1") listing.writeText(generated)
        if (!listing.exists()) fail("std/STABLE_SURFACE.txt is missing; generate it with AZORA_REGENERATE_SURFACE=1")
        val checkedIn = listing.readText()
        if (checkedIn == generated) return
        val was = checkedIn.lines()
        val now = generated.lines()
        val removed = was - now.toSet()
        val added = now - was.toSet()
        fail(
            buildString {
                appendLine("std's stable surface differs from std/STABLE_SURFACE.txt.")
                appendLine("If the change is intended, regenerate the listing with AZORA_REGENERATE_SURFACE=1 and review it.")
                removed.take(40).forEach { appendLine("- $it") }
                if (removed.size > 40) appendLine("- … ${removed.size - 40} more")
                added.take(40).forEach { appendLine("+ $it") }
                if (added.size > 40) appendLine("+ … ${added.size - 40} more")
            },
        )
    }
}
