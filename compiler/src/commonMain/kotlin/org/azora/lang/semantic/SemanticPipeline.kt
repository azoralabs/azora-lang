/*
 * Copyright 2026 AzoraTech
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

package org.azora.lang.semantic

import org.azora.lang.frontend.Program

/**
 * Result of the complete semantic analysis pipeline.
 *
 * @property program the CTFE-stabilized, type-checked AST
 * @property symbolTable the fully populated symbol table
 * @property effects the per-function effect classifications from [EffectChecker]
 * @property errors all errors and warnings collected across all semantic passes
 */
data class SemanticResult(
    val program: Program,
    val symbolTable: SymbolTable,
    val effects: List<EffectChecker.EffectInfo>,
    val errors: List<String>
)

/**
 * Multi-pass semantic analysis pipeline.
 *
 * Orchestrates the following passes in order:
 *
 *   Pass 1 — [SymbolCollector]: Walk all declarations, register function
 *            signatures in the symbol table. No type resolution yet — just names.
 *
 *   Pass 2 — [ImportResolver]: Resolve cross-module references. Build dependency
 *            graph. Merge imported symbols into the symbol table.
 *
 *   Pass 3 — Fixed-point stabilization loop:
 *            ```
 *            repeat:
 *              run TypeResolver (type resolution + inference)
 *              run CtfeEvaluator (compile-time function execution)
 *              fold CTFE results back into AST
 *            until AST is stable (no changes) or max iterations reached
 *            ```
 *
 *   Pass 4 — [AllocDropAnalyzer]: With types fully resolved, analyze alloc/drop
 *            pairs, ownership, and liveness. Post-CTFE because generated code
 *            may introduce new allocations.
 *
 *   Pass 5 — [EffectChecker]: Validate effect annotations, purity, side-effect
 *            propagation. Post-CTFE because generated functions carry effects too.
 *
 * Key design decisions:
 *  - Separate "declaration semantic" (Pass 1) from "body semantic" (Pass 3).
 *    A function's signature is resolved before its body, so other code can
 *    depend on it without waiting for the body to be analyzed.
 *  - CTFE shares the compiler's type system — no separate evaluator types.
 *  - Fixed-point loop with max iterations prevents infinite metaprogramming.
 */
/**
 * @param maxCtfeIterations maximum number of CTFE fixed-point iterations before
 *   reporting a convergence failure (default: 100)
 */
class SemanticPipeline(
    private val maxCtfeIterations: Int = 100
) {

    /**
     * Runs the complete multi-pass semantic analysis on the given program.
     *
     * Executes all passes in order: top-level CTFE, symbol collection, import
     * resolution, CTFE fixed-point loop with type resolution, alloc/drop analysis,
     * and effect checking.
     *
     * @param program the parsed AST (output of [Parser] and [AstValidator])
     * @return a [SemanticResult] containing the stabilized AST, symbol table,
     *   effect info, and any errors or warnings
     */
    fun analyze(program: Program): SemanticResult {
        val table = SymbolTable()
        val allErrors = mutableListOf<String>()

        // ---------------------------------------------------------------
        // Pass 0: Top-Level CTFE
        // Resolve top-level inline constructs (inline if, deepinline, etc.)
        // BEFORE symbol collection. This flattens conditional function
        // declarations so SymbolCollector can see them.
        // ---------------------------------------------------------------
        var currentProgram = program
        val topLevelCtfe = CtfeEvaluator(table)
        val topResult = topLevelCtfe.evaluateTopLevel(currentProgram)
        allErrors.addAll(topResult.errors)
        val topRealErrors = topResult.errors.filter { !it.startsWith("warning:") }
        if (topRealErrors.isNotEmpty()) {
            return SemanticResult(currentProgram, table, emptyList(), allErrors)
        }
        currentProgram = topResult.program

        // ---------------------------------------------------------------
        // Pass 1: Symbol Collection
        // Walk all declarations, register names. Don't resolve types yet.
        // This lets forward references work.
        // ---------------------------------------------------------------
        val collectErrors = SymbolCollector().collect(currentProgram, table)
        allErrors.addAll(collectErrors)
        if (collectErrors.isNotEmpty()) {
            return SemanticResult(currentProgram, table, emptyList(), allErrors)
        }

        // ---------------------------------------------------------------
        // Pass 2: Import / Dependency Resolution
        // Resolve cross-module references. In single-file mode this is
        // a no-op, but the pass slot exists for when modules are added.
        // ---------------------------------------------------------------
        val importErrors = ImportResolver().resolve(currentProgram.packageName, table)
        allErrors.addAll(importErrors)
        if (importErrors.isNotEmpty()) {
            return SemanticResult(currentProgram, table, emptyList(), allErrors)
        }

        // ---------------------------------------------------------------
        // Pass 3: Fixed-Point CTFE Loop
        //
        //   repeat:
        //     run CTFE on anything that's ready
        //     fold results back into AST
        //     run type resolution on the cleaned AST
        //   until AST is stable (no new changes)
        //
        // CTFE runs first so that `inline fin` and `inline if` are
        // resolved before TypeResolver sees them. This matches the
        // approach: compile-time constructs are evaluated first, then
        // the resulting code is type-checked.
        //
        // Max iterations prevent infinite metaprogramming loops.
        // ---------------------------------------------------------------
        var iteration = 0

        while (iteration < maxCtfeIterations) {
            iteration++

            // CTFE — evaluate compile-time expressions and fold into AST
            val ctfeResult = CtfeEvaluator(table).evaluate(currentProgram)
            allErrors.addAll(ctfeResult.errors)
            val ctfeRealErrors = ctfeResult.errors.filter { !it.startsWith("warning:") }
            if (ctfeRealErrors.isNotEmpty()) {
                return SemanticResult(currentProgram, table, emptyList(), allErrors)
            }

            currentProgram = ctfeResult.program

            // If AST didn't change, we've reached a fixed point — done
            if (!ctfeResult.changed) break
        }

        // Type Resolution + Inference (on the CTFE-stabilized AST)
        val typeErrors = TypeResolver(table).resolve(currentProgram)
        if (typeErrors.isNotEmpty()) {
            allErrors.addAll(typeErrors)
            return SemanticResult(currentProgram, table, emptyList(), allErrors)
        }

        if (iteration >= maxCtfeIterations) {
            allErrors.add("CTFE stabilization did not converge after $maxCtfeIterations iterations")
            return SemanticResult(currentProgram, table, emptyList(), allErrors)
        }

        // ---------------------------------------------------------------
        // Pass 4: Alloc / Drop Analysis
        // With types fully resolved (post-CTFE), analyze ownership and
        // liveness. CTFE-generated code may introduce new allocations.
        // ---------------------------------------------------------------
        val allocErrors = AllocDropAnalyzer().analyze(currentProgram)
        allErrors.addAll(allocErrors)

        // ---------------------------------------------------------------
        // Pass 5: Effect Checking
        // Validate effect annotations and purity. Post-CTFE because
        // generated functions carry effects too.
        // ---------------------------------------------------------------
        val effectResult = EffectChecker().check(currentProgram)
        allErrors.addAll(effectResult.errors)

        return SemanticResult(currentProgram, table, effectResult.effects, allErrors)
    }
}
