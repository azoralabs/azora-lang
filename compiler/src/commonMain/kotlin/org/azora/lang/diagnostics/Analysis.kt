/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.diagnostics

import org.azora.lang.frontend.Program

@JvmInline value class AnalysisSnapshotId(val value: String)

enum class AnalysisMode { BUILD, IDE }
enum class AnalysisDepth { SYNTAX, SYMBOLS, TYPES, FULL_SEMANTIC, IR, BACKEND }
enum class AnalysisCompleteness { COMPLETE, RECOVERED, PARTIAL, BLOCKED }

fun interface CancellationToken {
    fun isCancelled(): Boolean

    companion object {
        val NONE = CancellationToken { false }
    }
}

data class DiagnosticPolicy(
    val warningsAsErrors: Boolean = false,
    val maximumDiagnostics: Int = 200,
)

data class AnalysisRequest(
    val sources: List<SourceUnit>,
    val roots: Set<SourceId>,
    val mode: AnalysisMode = AnalysisMode.IDE,
    val depth: AnalysisDepth = AnalysisDepth.FULL_SEMANTIC,
    val target: String? = null,
    val defines: Map<String, String> = emptyMap(),
    val policy: DiagnosticPolicy = DiagnosticPolicy(),
    val cancellation: CancellationToken = CancellationToken.NONE,
)

data class SourceSetSnapshot(val units: Map<SourceId, SourceUnit>)

data class AnalysisSnapshot(
    val id: AnalysisSnapshotId,
    val sources: SourceSetSnapshot,
    val program: Program?,
    val diagnostics: List<AzoraDiagnostic>,
    val completeness: AnalysisCompleteness,
)

interface DiagnosticSink {
    fun report(diagnostic: AzoraDiagnostic)
    fun isCancelled(): Boolean
    fun checkpoint() {
        if (isCancelled()) throw AnalysisCancelledException()
    }
}

class AnalysisCancelledException : RuntimeException("analysis cancelled")

class DiagnosticBag(
    private val policy: DiagnosticPolicy,
    private val cancellation: CancellationToken = CancellationToken.NONE,
) : DiagnosticSink {
    private val collected = mutableListOf<AzoraDiagnostic>()
    val diagnostics: List<AzoraDiagnostic> get() = collected.toList()
    val hasFatalErrors: Boolean get() = collected.any {
        it.severity == DiagnosticSeverity.ERROR ||
            (policy.warningsAsErrors && it.severity == DiagnosticSeverity.WARNING)
    }

    override fun report(diagnostic: AzoraDiagnostic) {
        checkpoint()
        if (collected.size < policy.maximumDiagnostics) collected += diagnostic
    }

    override fun isCancelled(): Boolean = cancellation.isCancelled()
}
