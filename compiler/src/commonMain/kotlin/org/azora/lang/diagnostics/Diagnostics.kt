/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.diagnostics

import org.azora.lang.frontend.TokenType

@JvmInline value class DiagnosticCode(val value: String)
@JvmInline value class FixId(val value: String)
@JvmInline value class SuppressionKey(val value: String)
@JvmInline value class FixResolverKey(val value: String)

enum class DiagnosticStage {
    CONFIGURATION, MODULE_LOADING, LEXER, PARSER, MACRO_EXPANSION, AST_VALIDATION,
    SYMBOLS, TYPES, SEMANTIC, EFFECTS, OWNERSHIP, BORROWING, CTCE, IR, BACKEND, COMPILER,
}

enum class DiagnosticSeverity { ERROR, WARNING, INFORMATION, HINT }
enum class DiagnosticTag { UNNECESSARY, DEPRECATED, UNSAFE, EXPERIMENTAL, GENERATED }
enum class FixKind { QUICK_FIX, REFACTOR_REWRITE, SOURCE_FIX_ALL }
enum class FixApplicability { MACHINE_APPLICABLE, MAYBE_INCORRECT, HAS_PLACEHOLDERS, UNSPECIFIED }

data class LabeledSpan(val span: SourceSpan, val label: String? = null)
data class RelatedLocation(val span: SourceSpan, val message: String)
data class DiagnosticNote(val message: String)
data class DiagnosticSuggestion(val message: String)

data class SourceEdit(
    val source: SourceId,
    val range: SourceSpan,
    val replacement: String,
    val expectedText: String? = null,
    val requiredVersion: DocumentVersion? = null,
)

data class DiagnosticFix(
    val id: FixId,
    val title: String,
    val kind: FixKind = FixKind.QUICK_FIX,
    val applicability: FixApplicability,
    val preferred: Boolean = false,
    val edits: List<SourceEdit> = emptyList(),
    val resolver: FixResolverKey? = null,
)

sealed interface AzoraDiagnostic {
    val code: DiagnosticCode
    val stage: DiagnosticStage
    val severity: DiagnosticSeverity
    val primary: LabeledSpan
    val related: List<RelatedLocation> get() = emptyList()
    val notes: List<DiagnosticNote> get() = emptyList()
    val suggestions: List<DiagnosticSuggestion> get() = emptyList()
    val fixes: List<DiagnosticFix> get() = emptyList()
    val tags: Set<DiagnosticTag> get() = emptySet()
    val suppression: SuppressionKey? get() = null
}

sealed interface LexerDiagnostic : AzoraDiagnostic
sealed interface ParserDiagnostic : AzoraDiagnostic
sealed interface ModuleDiagnostic : AzoraDiagnostic
sealed interface SymbolDiagnostic : AzoraDiagnostic
sealed interface TypeDiagnostic : AzoraDiagnostic
sealed interface SemanticDiagnostic : AzoraDiagnostic
sealed interface OwnershipDiagnostic : AzoraDiagnostic
sealed interface BorrowDiagnostic : AzoraDiagnostic
sealed interface CompilerDiagnostic : AzoraDiagnostic

data class UnexpectedCharacter(val character: String, override val primary: LabeledSpan) : LexerDiagnostic {
    override val code = DiagnosticCode("AZ-LEX-0001")
    override val stage = DiagnosticStage.LEXER
    override val severity = DiagnosticSeverity.ERROR
}

data class UnexpectedToken(
    val found: TokenType,
    val expected: Set<String>,
    val context: String,
    override val primary: LabeledSpan,
    override val fixes: List<DiagnosticFix> = emptyList(),
) : ParserDiagnostic {
    override val code = DiagnosticCode("AZ-PAR-0003")
    override val stage = DiagnosticStage.PARSER
    override val severity = DiagnosticSeverity.ERROR
}

data class MissingToken(
    val expected: String,
    override val primary: LabeledSpan,
    override val fixes: List<DiagnosticFix>,
) : ParserDiagnostic {
    override val code = DiagnosticCode("AZ-PAR-0004")
    override val stage = DiagnosticStage.PARSER
    override val severity = DiagnosticSeverity.ERROR
}

enum class SymbolNamespace {
    VALUE,
    TYPE,
    FUNCTION,
    MODULE,
    MEMBER,

    /** A conformance name accepted by `derives` and `impl … for …`. */
    SPEC_OR_DECORATOR,
}

data class SymbolCandidate(val name: String, val declaration: SourceSpan? = null)

data class UndefinedSymbol(
    val symbol: String,
    val namespace: SymbolNamespace,
    val candidates: List<SymbolCandidate>,
    val providerModule: String?,
    override val primary: LabeledSpan,
    override val fixes: List<DiagnosticFix> = emptyList(),
) : SymbolDiagnostic {
    override val code = DiagnosticCode("AZ-SYM-0001")
    override val stage = DiagnosticStage.SYMBOLS
    override val severity = DiagnosticSeverity.ERROR
}

data class TypeMismatch(
    val expected: String,
    val actual: String,
    override val primary: LabeledSpan,
) : TypeDiagnostic {
    override val code = DiagnosticCode("AZ-TYP-0001")
    override val stage = DiagnosticStage.TYPES
    override val severity = DiagnosticSeverity.ERROR
}

data class UnusedDeclaration(
    val name: String,
    val declarationKind: String,
    override val primary: LabeledSpan,
    override val fixes: List<DiagnosticFix> = emptyList(),
) : SemanticDiagnostic {
    override val code = DiagnosticCode("AZ-SEM-0001")
    override val stage = DiagnosticStage.SEMANTIC
    override val severity = DiagnosticSeverity.WARNING
    override val tags = setOf(DiagnosticTag.UNNECESSARY)
    override val suppression = SuppressionKey("Unused")
}

/**
 * A variant owner repeated where the surrounding expression already fixes the
 * value's type: `kind == FileKind.File` can be written `kind == .File`.
 *
 * This is semantic rather than textual. The compiler reports it only after it
 * has proved that [qualifier] names the same enum/slot as the expected type and
 * that [variant] is one of that declaration's cases.
 */
data class RedundantVariantQualifier(
    val qualifier: String,
    val variant: String,
    override val primary: LabeledSpan,
    override val fixes: List<DiagnosticFix>,
) : SemanticDiagnostic {
    override val code = DiagnosticCode("AZ-SEM-0002")
    override val stage = DiagnosticStage.SEMANTIC
    override val severity = DiagnosticSeverity.HINT
    override val tags = setOf(DiagnosticTag.UNNECESSARY)
    override val suppression = SuppressionKey("RedundantQualifier")
}

data class UseAfterTake(
    val place: String,
    override val primary: LabeledSpan,
    val takenAt: SourceSpan,
    override val suggestions: List<DiagnosticSuggestion> = emptyList(),
) : OwnershipDiagnostic {
    override val code = DiagnosticCode("AZ-OWN-0002")
    override val stage = DiagnosticStage.OWNERSHIP
    override val severity = DiagnosticSeverity.ERROR
    override val related = listOf(RelatedLocation(takenAt, "ownership was transferred here"))
}

data class InternalCompilerFailure(
    val crashId: String,
    val phase: String,
    override val primary: LabeledSpan,
) : CompilerDiagnostic {
    override val code = DiagnosticCode("AZ-CMP-9999")
    override val stage = DiagnosticStage.COMPILER
    override val severity = DiagnosticSeverity.ERROR
}

/** Explicit migration wrapper for a producer that has not yet become a typed variant. */
data class LegacyUnstructuredDiagnostic(
    val message: String,
    override val stage: DiagnosticStage,
    override val severity: DiagnosticSeverity,
    override val primary: LabeledSpan,
    override val code: DiagnosticCode = DiagnosticCode("AZ-CMP-9000"),
    override val fixes: List<DiagnosticFix> = emptyList(),
    override val suppression: SuppressionKey? = null,
) : CompilerDiagnostic

object DiagnosticRenderer {
    fun summary(diagnostic: AzoraDiagnostic): String = when (diagnostic) {
        is UnexpectedCharacter -> "unexpected character '${diagnostic.character}'"
        is UnexpectedToken -> "unexpected ${diagnostic.found}; expected ${diagnostic.expected.joinToString(" or ")}"
        is MissingToken -> "expected '${diagnostic.expected}'"
        is UndefinedSymbol -> buildString {
            val kind = when (diagnostic.namespace) {
                SymbolNamespace.SPEC_OR_DECORATOR -> "spec or decorator"
                else -> diagnostic.namespace.name.lowercase()
            }
            append("undefined $kind '${diagnostic.symbol}'")
            diagnostic.providerModule?.let { module ->
                append(" - '${diagnostic.symbol}' is provided by '$module': add 'import $module::${diagnostic.symbol}'")
            }
        }
        is TypeMismatch -> "expected ${diagnostic.expected}, found ${diagnostic.actual}"
        is UnusedDeclaration -> "${diagnostic.declarationKind} '${diagnostic.name}' is never used"
        is RedundantVariantQualifier ->
            "the expected type is already known; write '.${diagnostic.variant}' instead of " +
                "'${diagnostic.qualifier}.${diagnostic.variant}'"
        is UseAfterTake -> "'${diagnostic.place}' is unavailable after ownership was transferred"
        is InternalCompilerFailure -> "the compiler failed internally during ${diagnostic.phase} (crash ${diagnostic.crashId})"
        is LegacyUnstructuredDiagnostic -> diagnostic.message
    }
}

data class DiagnosticDescriptor(
    val code: DiagnosticCode,
    val title: String,
    val defaultSeverity: DiagnosticSeverity,
    val suppression: SuppressionKey? = null,
    val documentationPath: String? = null,
)

object DiagnosticRegistry {
    val descriptors = listOf(
        DiagnosticDescriptor(DiagnosticCode("AZ-LEX-0001"), "Unexpected character", DiagnosticSeverity.ERROR),
        DiagnosticDescriptor(DiagnosticCode("AZ-PAR-0003"), "Unexpected token", DiagnosticSeverity.ERROR),
        DiagnosticDescriptor(DiagnosticCode("AZ-PAR-0004"), "Missing token", DiagnosticSeverity.ERROR),
        DiagnosticDescriptor(DiagnosticCode("AZ-SYM-0001"), "Undefined symbol", DiagnosticSeverity.ERROR),
        DiagnosticDescriptor(DiagnosticCode("AZ-TYP-0001"), "Type mismatch", DiagnosticSeverity.ERROR),
        DiagnosticDescriptor(
            DiagnosticCode("AZ-SEM-0001"), "Unused declaration", DiagnosticSeverity.WARNING, SuppressionKey("Unused"),
        ),
        DiagnosticDescriptor(
            DiagnosticCode("AZ-SEM-0002"),
            "Redundant variant qualifier",
            DiagnosticSeverity.HINT,
            SuppressionKey("RedundantQualifier"),
        ),
        DiagnosticDescriptor(DiagnosticCode("AZ-OWN-0002"), "Use after take", DiagnosticSeverity.ERROR),
        DiagnosticDescriptor(DiagnosticCode("AZ-CMP-9000"), "Legacy compiler diagnostic", DiagnosticSeverity.ERROR),
        DiagnosticDescriptor(DiagnosticCode("AZ-CMP-9999"), "Internal compiler failure", DiagnosticSeverity.ERROR),
    )

    init {
        require(descriptors.map { it.code }.distinct().size == descriptors.size) { "duplicate diagnostic code" }
    }
}
