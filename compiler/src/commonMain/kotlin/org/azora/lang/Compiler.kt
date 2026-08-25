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

package org.azora.lang

import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.azora.lang.frontend.AstValidator
import org.azora.lang.frontend.CallbackImplNormalizer
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.DebugInstrumenter
import org.azora.lang.frontend.MacroExpander
import org.azora.lang.frontend.Parser
import org.azora.lang.stdlib.AzStdlib
import org.azora.lang.stdlib.StdlibInjector
import org.azora.lang.frontend.Program
import org.azora.lang.ir.IrGenerator
import org.azora.lang.ir.IrOptimizer
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrType
import org.azora.lang.semantic.EffectChecker
import org.azora.lang.semantic.InferredTypeArgs
import org.azora.lang.semantic.InlineCallables
import org.azora.lang.semantic.SemanticPipeline
import org.azora.lang.semantic.SemanticRedundantVariantQualifier
import org.azora.lang.semantic.SemanticSymbolNamespace
import org.azora.lang.semantic.SemanticUnresolvedSymbol
import org.azora.lang.semantic.ReflectDecoExpander
import org.azora.lang.semantic.SpecDefaults
import org.azora.lang.semantic.CastDeriver
import org.azora.lang.semantic.ComparisonDeriver
import org.azora.lang.semantic.DisplayDeriver
import org.azora.lang.semantic.ScopeQualifiedImplTargets
import org.azora.lang.semantic.SerializationDeriver
import org.azora.lang.semantic.VariadicMonomorphizer
import org.azora.lang.diagnostics.AnalysisCancelledException
import org.azora.lang.diagnostics.AnalysisCompleteness
import org.azora.lang.diagnostics.AnalysisMode
import org.azora.lang.diagnostics.AnalysisRequest
import org.azora.lang.diagnostics.AnalysisSnapshot
import org.azora.lang.diagnostics.AnalysisSnapshotId
import org.azora.lang.diagnostics.AzoraDiagnostic
import org.azora.lang.diagnostics.DiagnosticSeverity
import org.azora.lang.diagnostics.DiagnosticStage
import org.azora.lang.diagnostics.DocumentVersion
import org.azora.lang.diagnostics.DiagnosticFix
import org.azora.lang.diagnostics.DiagnosticRenderer
import org.azora.lang.diagnostics.FixApplicability
import org.azora.lang.diagnostics.FixId
import org.azora.lang.diagnostics.ImportEditPlanner
import org.azora.lang.diagnostics.LabeledSpan
import org.azora.lang.diagnostics.LegacyDiagnosticAdapter
import org.azora.lang.diagnostics.RedundantVariantQualifier
import org.azora.lang.diagnostics.SourceEdit
import org.azora.lang.diagnostics.SourceId
import org.azora.lang.diagnostics.SourceKind
import org.azora.lang.diagnostics.SourcePosition
import org.azora.lang.diagnostics.SourceSetSnapshot
import org.azora.lang.diagnostics.SourceSpan
import org.azora.lang.diagnostics.SourceUnit
import org.azora.lang.diagnostics.StringLineIndex
import org.azora.lang.diagnostics.SymbolNamespace
import org.azora.lang.diagnostics.TextOffset
import org.azora.lang.diagnostics.UndefinedSymbol
import org.azora.lang.stdlib.UnresolvedContractAccess
import org.azora.lang.stdlib.UnresolvedTypeAccess

/**
 * Full compiler pipeline:
 *
 *   Phase 1 - Frontend
 *     1. Lexer:          source → tokens
 *     2. Parser:         tokens → raw AST
 *     3. AST Validation: catch structural errors
 *
 *   Phase 2 - Semantic Analysis (multi-pass)
 *     4. Symbol Collection (Pass 1):  register all function signatures
 *     5. Import Resolution:           resolve cross-module references
 *     6. Fixed-Point Loop:
 *          repeat:
 *            Type Resolution (Pass 2): resolve + infer types
 *            CTCE (Pass 3):            evaluate compile-time functions
 *            fold results back into AST
 *          until stable
 *     7. Alloc/Drop Analysis:         ownership + liveness (post-CTCE)
 *     8. Effect Checking:             purity + side-effect propagation (post-CTCE)
 *
 *   Phase 3 - IR Generation
 *     9.  AST → typed IR
 *     10. IR optimization (constant folding, constant propagation, DCE)
 *
 *   Phase 4 - Backend (one optimized IR → two codegen targets)
 *     11. IR → Wasm, LLVM
 */
/**
 * The result of compiling Azora source code through the full pipeline.
 */
sealed class CompilationResult {
    abstract val diagnostics: List<AzoraDiagnostic>
    /**
     * Successful compilation result containing all generated outputs and metadata.
     *
     * @property wasm the generated WebAssembly text (WAT)
     * @property llvm the generated LLVM IR text
     * @property ast the CTCE-stabilized AST after semantic analysis
     * @property ir the typed IR before optimization
     * @property optimizedIr the typed IR after optimization passes
     * @property effects the per-function effect classifications
     * @property warnings any non-fatal warnings collected during compilation
     */
    data class Success(
        val wasm: String,
        val llvm: String,
        val ast: Program,
        val ir: IrProgram,
        val optimizedIr: IrProgram,
        val effects: List<EffectChecker.EffectInfo>,
        val warnings: List<String> = emptyList(),
        override val diagnostics: List<AzoraDiagnostic> = emptyList(),
    ) : CompilationResult()

    /**
     * Failed compilation result.
     *
     * @property errors the list of error messages that prevented compilation
     */
    data class Failure(
        val errors: List<String>,
        override val diagnostics: List<AzoraDiagnostic> = emptyList(),
    ) : CompilationResult()
}

/**
 * An external Azora module made available to one compiler instance.
 *
 * [validateModulePath] is true for build/CLI inputs, where the build graph has
 * resolved a real source root and a mismatched path is actionable. IDE
 * snapshots deliberately set it to false: an editor may have a package open
 * through a workspace folder, a generated overlay, or a manifest-defined
 * source root, and the module declaration remains authoritative until that
 * source-root metadata reaches the compiler.
 */
data class LibrarySource(
    val path: String,
    val source: String,
    val validateModulePath: Boolean = true,
)

/**
 * Full compiler pipeline orchestrator.
 *
 * Drives all four phases: frontend (lexer, parser, AST validation),
 * semantic analysis (multi-pass with CTCE), IR generation with optimization,
 * and backend code generation to two targets - WebAssembly and LLVM IR - both
 * from one optimized IR.
 */
class Compiler(
    private val librarySources: List<LibrarySource> = emptyList(),
) {

    private fun sourceSpan(
        source: SourceUnit,
        line: Int,
        column: Int,
        length: Int,
    ): SourceSpan {
        val lineIndex = StringLineIndex(source.text)
        val start = lineIndex.offset(
            SourcePosition(
                line = (line - 1).coerceAtLeast(0),
                character = (column - 1).coerceAtLeast(0),
            ),
        ) ?: TextOffset(0)
        return SourceSpan(
            source.id,
            start,
            TextOffset((start.value + length).coerceAtMost(source.text.length)),
        )
    }

    /** Turns one resolver occurrence into a compiler-owned diagnostic and edit. */
    private fun undefinedSymbolDiagnostic(
        source: SourceUnit,
        name: String,
        line: Int,
        column: Int,
        length: Int,
        namespace: SymbolNamespace,
        providerModule: String?,
    ): UndefinedSymbol {
        val span = sourceSpan(source, line, column, length)
        val fixes = providerModule?.let { module ->
            ImportEditPlanner.plan(source.text, module, name)
        }?.let { edit ->
            val editSpan = SourceSpan(
                source.id,
                TextOffset(edit.start),
                TextOffset(edit.endExclusive),
            )
            listOf(
                DiagnosticFix(
                    id = FixId("import-$providerModule-$name"),
                    title = "Import '$name' from '$providerModule'",
                    applicability = FixApplicability.MACHINE_APPLICABLE,
                    preferred = true,
                    edits = listOf(
                        SourceEdit(
                            source = source.id,
                            range = editSpan,
                            replacement = edit.replacement,
                            expectedText = source.text.substring(edit.start, edit.endExclusive),
                            requiredVersion = source.version,
                        ),
                    ),
                ),
            )
        }.orEmpty()
        return UndefinedSymbol(
            symbol = name,
            namespace = namespace,
            candidates = emptyList(),
            providerModule = providerModule,
            primary = LabeledSpan(span, if (providerModule == null) "not found here" else "not imported here"),
            fixes = fixes,
        )
    }

    /** Compiler-owned `.Case` style hint and its version-checked source edit. */
    private fun redundantVariantQualifierDiagnostic(
        source: SourceUnit,
        occurrence: SemanticRedundantVariantQualifier,
    ): RedundantVariantQualifier {
        val span = sourceSpan(
            source,
            occurrence.line,
            occurrence.column,
            occurrence.length,
        )
        val expected = "${occurrence.qualifier}."
        return RedundantVariantQualifier(
            qualifier = occurrence.qualifier,
            variant = occurrence.variant,
            primary = LabeledSpan(span, "the surrounding context already supplies this type"),
            fixes = listOf(
                DiagnosticFix(
                    id = FixId("use-inferred-variant-${occurrence.line}-${occurrence.column}"),
                    title = "Use inferred variant '.${occurrence.variant}'",
                    applicability = FixApplicability.MACHINE_APPLICABLE,
                    preferred = true,
                    edits = listOf(
                        SourceEdit(
                            source = source.id,
                            range = span,
                            replacement = ".",
                            expectedText = expected,
                            requiredVersion = source.version,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun unresolvedContractDiagnostic(
        source: SourceUnit,
        access: UnresolvedContractAccess,
    ): UndefinedSymbol = undefinedSymbolDiagnostic(
        source = source,
        name = access.name,
        line = access.line,
        column = access.column,
        length = access.length,
        namespace = SymbolNamespace.SPEC_OR_DECORATOR,
        providerModule = access.module,
    )

    private fun unresolvedSemanticDiagnostic(
        source: SourceUnit,
        issue: SemanticUnresolvedSymbol,
        libraries: StdlibInjector,
    ): UndefinedSymbol = undefinedSymbolDiagnostic(
        source = source,
        name = issue.sourceName,
        line = issue.line,
        column = issue.column,
        length = issue.length,
        namespace = when (issue.namespace) {
            SemanticSymbolNamespace.FUNCTION -> SymbolNamespace.FUNCTION
            SemanticSymbolNamespace.VALUE -> SymbolNamespace.VALUE
        },
        providerModule = libraries.moduleOf(issue.internalName),
    )

    private fun unresolvedTypeDiagnostic(
        source: SourceUnit,
        access: UnresolvedTypeAccess,
    ): UndefinedSymbol = undefinedSymbolDiagnostic(
        source = source,
        name = access.name,
        line = access.line,
        column = access.column,
        length = access.length,
        namespace = SymbolNamespace.TYPE,
        providerModule = access.module,
    )

    /**
     * Compiles Azora source code through the full pipeline.
     *
     * @param source the Azora source code to compile
     * @param warningsAsErrors if `true`, warnings are treated as errors and
     *   cause compilation to fail
     * @return a [CompilationResult.Success] with all generated outputs, or a
     *   [CompilationResult.Failure] with error messages
     */
    fun compile(
        source: String,
        warningsAsErrors: Boolean = false,
        release: Boolean = true,
        debug: Boolean = false,
        defines: Map<String, String> = emptyMap(),
    ): CompilationResult = compileSource(
        SourceUnit(
            id = SourceId("memory-main"),
            uri = "azora-memory:/main.az",
            displayPath = "<memory>",
            text = source,
            version = null,
            kind = SourceKind.VIRTUAL,
        ),
        warningsAsErrors,
        release,
        debug,
        defines,
        generateBackends = true,
    )

    /** Analyze immutable source snapshots without running either backend. */
    fun analyze(request: AnalysisRequest): AnalysisSnapshot {
        val byId = request.sources.associateBy { it.id }
        val roots = if (request.roots.isEmpty()) byId.keys else request.roots
        val diagnostics = mutableListOf<AzoraDiagnostic>()
        var program: Program? = null
        var blocked = false
        for (rootId in roots.sortedBy { it.value }) {
            if (request.cancellation.isCancelled()) throw AnalysisCancelledException()
            val root = byId[rootId] ?: continue
            val workspaceLibraries = request.sources
                .asSequence()
                .filter { it.id != rootId }
                .map { LibrarySource(it.displayPath, it.text, validateModulePath = false) }
                .toList()
            val result = Compiler(librarySources + workspaceLibraries).compileSource(
                root,
                request.policy.warningsAsErrors,
                release = false,
                debug = false,
                defines = request.defines,
                generateBackends = false,
                reportUnreachableLibraryWarnings = request.mode != AnalysisMode.IDE,
            )
            diagnostics += result.diagnostics
            when (result) {
                is CompilationResult.Success -> if (program == null) program = result.ast
                is CompilationResult.Failure -> blocked = true
            }
            if (diagnostics.size >= request.policy.maximumDiagnostics) break
        }
        val versions = request.sources.joinToString("|") {
            "${it.id.value}:${it.version?.value ?: -1}:${it.text.hashCode()}"
        }
        return AnalysisSnapshot(
            id = AnalysisSnapshotId("a-${versions.hashCode().toUInt().toString(16)}"),
            sources = SourceSetSnapshot(byId),
            program = program,
            diagnostics = diagnostics
                .take(request.policy.maximumDiagnostics)
                .sortedWith(compareBy({ byId[it.primary.span.source]?.uri.orEmpty() }, { it.primary.span.start.value }, { it.code.value })),
            completeness = if (blocked) AnalysisCompleteness.BLOCKED else AnalysisCompleteness.COMPLETE,
        )
    }

    private fun compileSource(
        source: SourceUnit,
        warningsAsErrors: Boolean,
        release: Boolean,
        debug: Boolean,
        defines: Map<String, String>,
        generateBackends: Boolean,
        reportUnreachableLibraryWarnings: Boolean = true,
    ): CompilationResult {
        val result = compileLegacy(
            source,
            warningsAsErrors,
            release,
            debug,
            defines,
            generateBackends,
            reportUnreachableLibraryWarnings,
        )
        return when (result) {
            is CompilationResult.Success -> result.copy(
                diagnostics = result.diagnostics + result.warnings.map {
                    LegacyDiagnosticAdapter.convert(
                        it,
                        source,
                        defaultSeverity = DiagnosticSeverity.WARNING,
                        defaultStage = DiagnosticStage.SEMANTIC,
                    )
                },
            )
            is CompilationResult.Failure -> result.copy(
                diagnostics = result.diagnostics.ifEmpty {
                    result.errors.map { LegacyDiagnosticAdapter.convert(it, source) }
                },
            )
        }
    }

    private fun compileLegacy(
        sourceUnit: SourceUnit,
        warningsAsErrors: Boolean,
        release: Boolean,
        debug: Boolean,
        defines: Map<String, String>,
        generateBackends: Boolean,
        reportUnreachableLibraryWarnings: Boolean,
    ): CompilationResult {

        val source = sourceUnit.text

        // Clear per-compilation state
        org.azora.lang.frontend.Parser.resetFragmentMacros()
        IrType.aliases.clear()

        val libraries = try {
            StdlibInjector.create(
                additionalSources = librarySources,
                configOverrides = defines,
            )
        } catch (error: IllegalArgumentException) {
            return CompilationResult.Failure(listOf(error.message ?: "library loading failed"))
        } catch (error: IllegalStateException) {
            // An unusable standard library - wrong version, or a root with no
            // version marker - is something the user can fix, so it is reported
            // like any other compilation failure rather than thrown at them.
            return CompilationResult.Failure(listOf(error.message ?: "standard library unavailable"))
        }

        // ===============================================================
        // Phase 1 - Frontend
        // ===============================================================

        // 1-2. Lexer and parser: malformed source is a compilation failure, never
        // an exception that can crash an editor, build tool, or playground host.
        val rawAst = try {
            // A compile-time list bound by the standard library (`Numbers`) is
            // usable from a user module's `inline for`, so the stdlib's bindings
            // seed this parse. Reading the stdlib first is what populates them.
            AzStdlib.loadPrograms()
            Parser(
                Lexer(source).tokenize(),
                AzStdlib.comptimeLists.toMutableMap(),
                AzStdlib.declaredEnums.toMutableMap(),
                typeListScope = AzStdlib.comptimeListScopes.toMutableMap(),
            ).parse()
        } catch (error: IllegalStateException) {
            return CompilationResult.Failure(listOf(error.message ?: "frontend parsing failed"))
        } catch (error: IllegalArgumentException) {
            return CompilationResult.Failure(listOf(error.message ?: "frontend parsing failed"))
        }

        // 2a. Debug builds: instrument statements with `__dbg(line)` markers so a
        // debugger can pause at breakpoints (stdlib, injected below, stays clean).
        val parsed = if (debug) DebugInstrumenter.instrument(rawAst) else rawAst

        // 2a-0. A library source that does not parse is reported to the units
        // that import it. One unreadable file elsewhere in the tree is not a
        // reason to refuse a file that never names it.
        val libraryErrors = libraries.unusableLibraries(parsed)
        if (libraryErrors.isNotEmpty()) {
            return CompilationResult.Failure(libraryErrors)
        }

        // 2a-bis. Reject imports of namespaces that have no module file (e.g.
        // `import std` - there is no `std.az`, only modules beneath it).
        val importErrors = libraries.validateImports(parsed)
        if (importErrors.isNotEmpty()) {
            return CompilationResult.Failure(importErrors)
        }
        // 2a-quater. Modules must form a DAG - reject import cycles before any
        // pass starts depending on an order that does not exist.
        val cycleErrors = libraries.validateModuleCycles(parsed)
        if (cycleErrors.isNotEmpty()) {
            return CompilationResult.Failure(cycleErrors)
        }

        val unresolvedContractAccesses = libraries.unresolvedContracts(parsed)
        val unresolvedContracts = unresolvedContractAccesses
            .map { unresolvedContractDiagnostic(sourceUnit, it) }
        if (unresolvedContracts.isNotEmpty()) {
            return CompilationResult.Failure(
                errors = unresolvedContracts.zip(unresolvedContractAccesses).map { (diagnostic, access) ->
                    "line ${access.line}: ${DiagnosticRenderer.summary(diagnostic)}"
                },
                diagnostics = unresolvedContracts,
            )
        }

        val typeAccess = libraries.validateTypeAccess(parsed)
        if (typeAccess.unresolved.isNotEmpty() || typeAccess.errors.isNotEmpty()) {
            val unresolvedTypes = typeAccess.unresolved.map { unresolvedTypeDiagnostic(sourceUnit, it) }
            val renderedUnresolved = unresolvedTypes.zip(typeAccess.unresolved).map { (diagnostic, access) ->
                "line ${access.line}: ${DiagnosticRenderer.summary(diagnostic)}"
            }
            return CompilationResult.Failure(
                errors = renderedUnresolved + typeAccess.errors,
                diagnostics = unresolvedTypes + typeAccess.errors.map {
                    LegacyDiagnosticAdapter.convert(it, sourceUnit)
                },
            )
        }

        // 2a-ter. A leading underscore keeps a declaration inside its owning
        // module or type.
        val privateAccessErrors = libraries.validatePrivateAccess(parsed)
        if (privateAccessErrors.isNotEmpty()) {
            return CompilationResult.Failure(privateAccessErrors)
        }

        // 2b. Standard library: append the stdlib declarations the program
        // actually references (transitively); user definitions shadow stdlib.
        val initiallyInjected = ScopeQualifiedImplTargets.resolve(
            SpecDefaults.apply(CallbackImplNormalizer.normalize(libraries.inject(parsed))),
        )

        // Decorator derives produce ordinary checked AST methods. Run injection
        // once more afterwards so helper functions referenced by generated
        // methods are loaded transitively from their defining library module.
        val serialization = SerializationDeriver.derive(initiallyInjected)
        if (serialization.errors.isNotEmpty()) {
            return CompilationResult.Failure(serialization.errors)
        }
        // `Point derives (Equal, Order)` asks the compiler to write the members.
        // Generated as ordinary AST, so everything below
        // this point sees code the author could have written.
        val comparison = ComparisonDeriver.derive(serialization.program)
        if (comparison.errors.isNotEmpty()) {
            return CompilationResult.Failure(comparison.errors)
        }
        // `"${v}"` needs a String; `Display` writes into a Formatter. The step
        // between is generated per implementing type, so every backend sees an
        // ordinary member rather than an intrinsic each would have to implement.
        // A cast impl's member takes its target's name before anything reads it,
        // so two `Cast` impls for one type do not collide.
        val casts = CastDeriver.rewrite(comparison.program)
        val displayed = DisplayDeriver.derive(casts)
        // Applied again: this injection may pull in a module whose implementations
        // have not seen their specs' provided members yet.
        val injected = SpecDefaults.apply(CallbackImplNormalizer.normalize(libraries.inject(displayed)))

        // 2b-bis. Unroll `inline for X in reflect<*>.withAnnot<D>` loops now that the
        // whole program (all modules' decorated types) is visible. Generic: the
        // compiler attaches no meaning to any decorator.
        val reflected = ReflectDecoExpander.expand(injected)

        // 2c. Expand `meta` macros: rewrite every `Expr.MetaInvoke` into its
        // matched arm's template (splice-substituting `$captures`) and remove
        // the `TopLevel.Meta` declarations. Runs after stdlib injection so both
        // user-defined and library macros are available, and before variadic
        // monomorphization so macro-generated variadic calls (e.g. listOf)
        // monomorphize normally. The result is plain expressions - no IR/backend
        // awareness of macros is needed.
        val macroExpanded = try {
            MacroExpander.expand(reflected)
        } catch (e: IllegalStateException) {
            return CompilationResult.Failure(listOf(e.message ?: "macro expansion failed"))
        }

        // 2c-bis. Re-inject: macro expansion turns `name!` sites into concrete
        // expressions (e.g. vec![1,2,3] → std__listOf(1,2,3)), which may reference
        // stdlib symbols not pulled in by the pre-expansion injection (the macro
        // template's own dependencies). A second injection pass over the expanded
        // program picks those up transitively.
        var macroReInjected = CallbackImplNormalizer.normalize(libraries.inject(macroExpanded))
        // The re-injection can re-add `meta .Infix("op")` markers (encoded as
        // `__infix__op` metas) from the stdlib. Collect their operator names and
        // strip the markers so no `TopLevel.Meta` survives into semantic analysis.
        run {
            val reinjectedInfix = macroReInjected.items
                .filterIsInstance<org.azora.lang.frontend.TopLevel.Meta>()
                .mapNotNull { it.name.removePrefix("__infix__").takeIf { n -> n != it.name } }
            if (reinjectedInfix.isNotEmpty() || macroReInjected.items.any { it is org.azora.lang.frontend.TopLevel.Meta }) {
                macroReInjected = macroReInjected.copy(
                    items = macroReInjected.items.filterNot { it is org.azora.lang.frontend.TopLevel.Meta },
                    infixOperators = macroReInjected.infixOperators + reinjectedInfix,
                )
            }
        }

        // 2d. Monomorphize variadic generics (e.g. `Tuple<T…>` / `tupleOf(…)`)
        // into concrete per-instantiation declarations before semantic analysis.
        val ast = try {
            // Named type macros are library-defined and expand during the first
            // rewrite. Re-inject afterwards so declarations referenced by their
            // templates become visible, then run monomorphization once more for
            // any variadic type produced by an expansion (for example a query
            // over a heterogeneous component list).
            val typeExpanded = VariadicMonomorphizer.monomorphize(macroReInjected)
            // What this injection pulls in has never been through macro
            // expansion: a module reached for the first time here still spells
            // `@arr[…]`, and nothing downstream expands one.
            val typeReInjected = SpecDefaults.apply(
                MacroExpander.expand(
                    CallbackImplNormalizer.normalize(libraries.inject(typeExpanded)),
                ),
            )
            VariadicMonomorphizer.monomorphize(typeReInjected)
        } catch (e: IllegalStateException) {
            return CompilationResult.Failure(listOf(e.message ?: "variadic monomorphization failed"))
        }

        // 3. AST Validation: structural checks
        val validationErrors = AstValidator().validate(ast)
        if (validationErrors.isNotEmpty()) {
            return CompilationResult.Failure(validationErrors)
        }

        // ===============================================================
        // Phase 2 - Semantic Analysis (multi-pass with CTCE loop)
        // ===============================================================

        // Passes 4-8: symbol collection → imports → type resolution ⇄ CTCE → alloc/drop → effects
        // A nested call's type argument, where the call it fills already said it.
        // Before analysis, because a type argument is what an `inline` body
        // substitutes and what `T.typeName` reads.
        val semantic = SemanticPipeline().analyze(InferredTypeArgs.apply(ast), defines = defines)
        val shorthandDiagnostics = semantic.redundantVariantQualifiers.map {
            redundantVariantQualifierDiagnostic(sourceUnit, it)
        }

        // A library source nothing here imports was skipped rather than fatal;
        // it is still worth saying so, because the next file to import it will
        // not compile.
        val warnings = semantic.errors.filter { it.startsWith("warning:") } +
            if (reportUnreachableLibraryWarnings) {
                libraries.skippedLibraryNotices().map { "warning: $it" }
            } else {
                emptyList()
            }
        val errors = semantic.errors.filter { !it.startsWith("warning:") }

        if (errors.isNotEmpty()) {
            val unresolvedIssues = semantic.unresolvedSymbols
                .distinctBy { listOf(it.namespace, it.internalName, it.line, it.column) }
            val unresolvedDiagnostics = unresolvedIssues
                .map { unresolvedSemanticDiagnostic(sourceUnit, it, libraries) }
            val renderedByMessage = unresolvedIssues.zip(unresolvedDiagnostics)
                .associate { (issue, diagnostic) ->
                    issue.renderedMessage to "line ${issue.line}: ${DiagnosticRenderer.summary(diagnostic)}"
                }
            val renderedErrors = semantic.errors.map { renderedByMessage[it] ?: it }
            val structuredMessages = semantic.unresolvedSymbols.mapTo(hashSetOf()) { it.renderedMessage }
            val remainingDiagnostics = semantic.errors
                .filterNot { it in structuredMessages }
                .map { message ->
                    LegacyDiagnosticAdapter.convert(
                        message,
                        sourceUnit,
                        defaultSeverity = if (message.startsWith("warning:")) {
                            DiagnosticSeverity.WARNING
                        } else {
                            DiagnosticSeverity.ERROR
                        },
                    )
                }
            return CompilationResult.Failure(
                errors = renderedErrors,
                diagnostics = unresolvedDiagnostics + remainingDiagnostics + shorthandDiagnostics,
            )
        }
        if (warningsAsErrors && warnings.isNotEmpty()) {
            return CompilationResult.Failure(
                errors + warnings,
                diagnostics = shorthandDiagnostics + warnings.map {
                    LegacyDiagnosticAdapter.convert(
                        it,
                        sourceUnit,
                        defaultSeverity = DiagnosticSeverity.WARNING,
                        defaultStage = DiagnosticStage.SEMANTIC,
                    )
                },
            )
        }

        // ===============================================================
        // Phase 3 - IR Generation + Optimization
        // ===============================================================

        // 9. AST → typed IR (uses the CTCE-stabilized program)
        // A block passed to an `inline` callable is substituted where it was
        // written, which is what the annotation promises. Done after analysis, so
        // what is spliced has already been checked where it was written.
        val inlined = InlineCallables.apply(semantic.program)
        val ir = IrGenerator(semantic.symbolTable).generate(inlined)

        // 10. IR optimization passes (release mode only)
        val optimizedIr = if (release) IrOptimizer().optimize(ir) else ir

        // ===============================================================
        // Phase 4 - Backend (uses optimized IR in release, raw IR in debug)
        // ===============================================================

        val backendIr = optimizedIr

        // 11. IR → WebAssembly text (WAT). A feature a backend cannot yet lower
        // (e.g. indirect value calls) degrades only that target's output rather
        // than failing the whole compilation, so the interpreter and other targets
        // remain usable.
        val wasm = if (!generateBackends) "" else try { WasmCodegen().generate(backendIr) }
            catch (e: IllegalStateException) { "(; WebAssembly codegen unsupported: ${e.message} ;)" }

        // 12. IR → LLVM IR
        val llvm = if (!generateBackends) "" else try { LlvmCodegen().generate(backendIr) }
            catch (e: IllegalStateException) { "; LLVM codegen unsupported: ${e.message}" }

        return CompilationResult.Success(
            wasm,
            llvm,
            semantic.program,
            ir,
            optimizedIr,
            semantic.effects,
            warnings,
            diagnostics = shorthandDiagnostics,
        )
    }
}
