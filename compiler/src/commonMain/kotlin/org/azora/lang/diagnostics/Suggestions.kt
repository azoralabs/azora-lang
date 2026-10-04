/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.diagnostics

/**
 * The name among [candidates] that [name] most plausibly misspells, or null.
 *
 * A candidate qualifies when it is within a third of the name's length in
 * edits - insertions, deletions, substitutions and swapped neighbours - so
 * `Experiemntal` finds `Experimental` while `Foo` finds nothing in a list of
 * unrelated words. Letter case counts as half an edit: `experimental` is one
 * keystroke from `Experimental`, not a stranger. Ties go to the earliest
 * candidate, so the answer does not depend on hashing.
 */
fun closestName(name: String, candidates: Iterable<String>): String? {
    val budget = maxOf(1, name.length / 3) * 2
    var best: String? = null
    var bestCost = Int.MAX_VALUE
    for (candidate in candidates) {
        if (candidate == name) continue
        val cost = editCost(name, candidate)
        if (cost <= budget && cost < bestCost) {
            best = candidate
            bestCost = cost
        }
    }
    return best
}

/** `" - did you mean 'X'?"` for the closest of [candidates], or nothing. */
fun didYouMean(name: String, candidates: Iterable<String>, spell: (String) -> String = { it }): String =
    closestName(name, candidates)?.let { " - did you mean '${spell(it)}'?" } ?: ""

/**
 * Optimal string alignment distance, doubled so that a change of letter case
 * alone can cost one half-edit.
 */
private fun editCost(a: String, b: String): Int {
    fun step(x: Char, y: Char): Int = when {
        x == y -> 0
        x.equals(y, ignoreCase = true) -> 1
        else -> 2
    }
    val rows = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) rows[i][0] = i * 2
    for (j in 0..b.length) rows[0][j] = j * 2
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            var cost = minOf(
                rows[i - 1][j] + 2,
                rows[i][j - 1] + 2,
                rows[i - 1][j - 1] + step(a[i - 1], b[j - 1]),
            )
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                cost = minOf(cost, rows[i - 2][j - 2] + 2)
            }
            rows[i][j] = cost
        }
    }
    return rows[a.length][b.length]
}
