/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.diagnostics

/**
 * The one permitted reverse adapter while compiler passes are migrated away
 * from strings. Tooling consumes its structured result and never parses text.
 */
object LegacyDiagnosticAdapter {
    fun convert(
        rawMessage: String,
        source: SourceUnit,
        defaultSeverity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
        defaultStage: DiagnosticStage = DiagnosticStage.SEMANTIC,
    ): AzoraDiagnostic {
        val warning = rawMessage.startsWith("warning:")
        val message = rawMessage.removePrefix("warning:").trim()
        val severity = if (warning) DiagnosticSeverity.WARNING else defaultSeverity
        val line = LINE_PATTERNS.firstNotNullOfOrNull { it.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val symbol = SYMBOL_PATTERNS.firstNotNullOfOrNull { it.find(message)?.groupValues?.getOrNull(1) }
        val primary = primarySpan(source, line, symbol)

        USE_AFTER_TAKE.find(message)?.let { match ->
            val name = match.groupValues[1]
            val takenLine = match.groupValues[2].toIntOrNull()
            val taken = primarySpan(source, takenLine, name).span
            return UseAfterTake(
                place = name,
                primary = LabeledSpan(primary.span, "used here after transfer"),
                takenAt = taken,
                suggestions = listOf(
                    DiagnosticSuggestion("use 'lend' when the callee returns ownership"),
                    DiagnosticSuggestion("clone explicitly when two independent values are intended"),
                ),
            )
        }

        TYPE_MISMATCH.find(message)?.let { match ->
            return TypeMismatch(match.groupValues[1].trim(), match.groupValues[2].trim(), primary)
        }

        UNUSED_VARIABLE.find(message)?.let { match ->
            val name = match.groupValues[1]
            val declaration = primarySpan(source, line, name)
            return UnusedDeclaration(
                name = name,
                declarationKind = "Variable",
                primary = declaration,
                fixes = listOf(
                    DiagnosticFix(
                        id = FixId("rename-unused-$name"),
                        title = "Rename unused '$name' to '_'",
                        applicability = FixApplicability.MACHINE_APPLICABLE,
                        preferred = true,
                        edits = listOf(
                            SourceEdit(
                                source = source.id,
                                range = declaration.span,
                                replacement = "_",
                                expectedText = name,
                                requiredVersion = source.version,
                            ),
                        ),
                    ),
                ),
            )
        }

        val expected = EXPECTED_TOKEN.find(message)?.groupValues?.getOrNull(1)
        if (expected != null && expected in INSERTABLE_TOKENS) {
            val insertion = insertionSpan(source, line)
            return MissingToken(
                expected,
                LabeledSpan(insertion, "insert '$expected' here"),
                listOf(
                    DiagnosticFix(
                        id = FixId("insert-${expected.encodeForId()}"),
                        title = "Insert '$expected'",
                        applicability = FixApplicability.MACHINE_APPLICABLE,
                        preferred = true,
                        edits = listOf(
                            SourceEdit(
                                source.id,
                                insertion,
                                expected,
                                expectedText = "",
                                requiredVersion = source.version,
                            ),
                        ),
                    ),
                ),
            )
        }

        return LegacyUnstructuredDiagnostic(
            message = message.replace(Regex("^line \\d+:\\s*"), ""),
            stage = inferStage(message, defaultStage),
            severity = severity,
            primary = primary,
            suppression = when {
                "unused" in message.lowercase() -> SuppressionKey("Unused")
                "deprecated" in message.lowercase() -> SuppressionKey("Deprecated")
                else -> null
            },
        )
    }

    private fun primarySpan(source: SourceUnit, oneBasedLine: Int?, symbol: String?): LabeledSpan {
        val bounds = lineBounds(source.text, oneBasedLine)
        val lineText = source.text.substring(bounds.first, bounds.second)
        val local = symbol?.let { lineText.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val length = symbol?.length?.takeIf { local + it <= lineText.length } ?: (lineText.length - local).coerceAtLeast(0)
        return LabeledSpan(
            SourceSpan(source.id, TextOffset(bounds.first + local), TextOffset(bounds.first + local + length)),
        )
    }

    private fun insertionSpan(source: SourceUnit, oneBasedLine: Int?): SourceSpan {
        val bounds = lineBounds(source.text, oneBasedLine)
        return SourceSpan(source.id, TextOffset(bounds.second), TextOffset(bounds.second))
    }

    private fun lineBounds(text: String, oneBasedLine: Int?): Pair<Int, Int> {
        val target = (oneBasedLine ?: 1).coerceAtLeast(1)
        var line = 1
        var start = 0
        var index = 0
        while (index < text.length && line < target) {
            if (text[index] == '\n') {
                line++
                start = index + 1
            }
            index++
        }
        var end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        if (end > start && text[end - 1] == '\r') end--
        return start to end
    }

    private fun inferStage(message: String, fallback: DiagnosticStage): DiagnosticStage = when {
        "standard library" in message.lowercase() || "configuration" in message.lowercase() -> DiagnosticStage.CONFIGURATION
        "import" in message.lowercase() || "module" in message.lowercase() -> DiagnosticStage.MODULE_LOADING
        message.startsWith("Expected") || "expected token" in message.lowercase() -> DiagnosticStage.PARSER
        "undefined" in message.lowercase() || "duplicate" in message.lowercase() -> DiagnosticStage.SYMBOLS
        "type mismatch" in message.lowercase() || "cannot assign" in message.lowercase() -> DiagnosticStage.TYPES
        "borrow" in message.lowercase() -> DiagnosticStage.BORROWING
        "ownership" in message.lowercase() || "taken value" in message.lowercase() -> DiagnosticStage.OWNERSHIP
        else -> fallback
    }

    private fun String.encodeForId(): String = map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

    private val LINE_PATTERNS = listOf(Regex("(?:^|\\b)line (\\d+)"), Regex("at line (\\d+)"))
    private val SYMBOL_PATTERNS = listOf(
        Regex("'(?:[A-Za-z_][A-Za-z0-9_]*::)*([A-Za-z_][A-Za-z0-9_]*)'"),
    )
    private val TYPE_MISMATCH = Regex("(?:type mismatch(?::| in '[^']+': declared)|return type mismatch: expected)\\s*([^;]+?)(?: but (?:initializer is|got)|, found)\\s*(.+?)(?:$|;)")
    private val USE_AFTER_TAKE = Regex("use of taken value '([^']+)' - its ownership transferred at line (\\d+)")
    private val UNUSED_VARIABLE = Regex("variable '([^']+)' in function '([^']+)' is never used")
    private val EXPECTED_TOKEN = Regex("Expected '([^']+)'", RegexOption.IGNORE_CASE)
    private val INSERTABLE_TOKENS = setOf(")", "]", "}", ">", ":", ",")
}
