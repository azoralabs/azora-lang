/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.diagnostics

/** A source-only edit which adds one import without changing unrelated text. */
data class PlannedImportEdit(val start: Int, val endExclusive: Int, val replacement: String)

/**
 * Canonical import placement shared by compiler fixes and language-server
 * completion.  It deliberately works on source text rather than an IDE model,
 * so every LSP client receives the same edit.
 */
object ImportEditPlanner {
    fun plan(source: String, module: String, symbol: String? = null): PlannedImportEdit? {
        val imports = IMPORT_LINE.findAll(source).toList()
        if (imports.any { reaches(it.groupValues[2].trim(), module, symbol) }) return null

        if (symbol != null) {
            imports.firstNotNullOfOrNull { joinSelection(it, module, symbol) }?.let { return it }
        }

        val path = if (symbol == null) module else "$module::$symbol"
        val line = "import $path"
        if (imports.isEmpty()) {
            val at = headerEnd(source)
            val leading = if (at == 0 || source.substring(0, at).endsWith("\n\n")) "" else "\n"
            val trailing = if (at >= source.length || source.substring(at).startsWith("\n")) "" else "\n"
            return PlannedImportEdit(at, at, "$leading$line\n$trailing")
        }

        val bases = imports.map { baseOf(it.groupValues[2]) }
        if (bases == bases.sorted()) {
            imports.zip(bases).firstOrNull { (_, base) -> base > module }?.let { (match, _) ->
                return PlannedImportEdit(match.range.first, match.range.first, "${match.groupValues[1]}$line\n")
            }
        }
        val last = imports.last()
        val lineEnd = source.indexOf('\n', last.range.last + 1).let { if (it < 0) source.length else it + 1 }
        val prefix = if (lineEnd == source.length && !source.endsWith('\n')) "\n" else ""
        return PlannedImportEdit(lineEnd, lineEnd, "$prefix${last.groupValues[1]}$line\n")
    }

    private fun reaches(clauseWithComment: String, module: String, symbol: String?): Boolean {
        val clause = clauseWithComment.substringBefore("//").trim()
        if (clause == module || clause == "$module::*") return true
        if (symbol == null) return false
        if (clause == "$module::$symbol" || clause == "$module.$symbol") return true
        val selected = SELECTION.matchEntire(clause) ?: return false
        if (selected.groupValues[1] != module) return false
        return splitSelection(selected.groupValues[2]).any { it == symbol || it == "*" }
    }

    private fun joinSelection(match: MatchResult, module: String, symbol: String): PlannedImportEdit? {
        val clause = match.groupValues[2].substringBefore("//").trim()
        val singlePrefix = "$module::"
        if (clause.startsWith(singlePrefix) && !clause.startsWith("$singlePrefix{") &&
            " as " !in clause && " without " !in clause
        ) {
            val current = clause.removePrefix(singlePrefix).trim()
            if (current.matches(IDENTIFIER)) {
                return PlannedImportEdit(
                    match.range.first,
                    match.range.last + 1,
                    "${match.groupValues[1]}import $module::{${current}, $symbol}",
                )
            }
        }
        val selected = SELECTION.matchEntire(clause) ?: return null
        if (selected.groupValues[1] != module || "without " in selected.groupValues[2]) return null
        val names = splitSelection(selected.groupValues[2])
        if (names.any { !it.matches(IDENTIFIER) }) return null
        return PlannedImportEdit(
            match.range.first,
            match.range.last + 1,
            "${match.groupValues[1]}import $module::{${(names + symbol).joinToString(", ")}}",
        )
    }

    private fun splitSelection(value: String): List<String> =
        value.split(',').map(String::trim).filter(String::isNotEmpty)

    private fun baseOf(clause: String): String = clause.substringBefore("//").trim()
        .substringBefore("::").trimEnd('.', ':')

    private fun headerEnd(source: String): Int {
        val header = MODULE_LINE.find(source) ?: return 0
        val lineEnd = source.indexOf('\n', header.range.last + 1).let { if (it < 0) source.length else it + 1 }
        var at = lineEnd
        while (at < source.length && source[at] == '\n') at++
        return at
    }

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val IMPORT_LINE = Regex("(?m)^([ \\t]*)import[ \\t]+([^\\n\\r]+)")
    private val MODULE_LINE = Regex("(?m)^[ \\t]*(?:(?:export|exposed)[ \\t]+)?module[ \\t]+[^\\n\\r]+$")
    private val SELECTION = Regex("([^:]+)::\\{([^}]*)}")
}
