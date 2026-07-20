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

import org.azora.lang.backend.JavaScriptCodegen
import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.WasmCodegen
import org.azora.lang.frontend.AstValidator
import org.azora.lang.frontend.CallbackImplNormalizer
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.DebugInstrumenter
import org.azora.lang.frontend.MacroExpander
import org.azora.lang.frontend.Parser
import org.azora.lang.stdlib.StdlibInjector
import org.azora.lang.frontend.Program
import org.azora.lang.ir.IrGenerator
import org.azora.lang.ir.IrOptimizer
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrType
import org.azora.lang.semantic.EffectChecker
import org.azora.lang.semantic.SemanticPipeline
import org.azora.lang.semantic.SerializationDeriver
import org.azora.lang.semantic.VariadicMonomorphizer

/**
 * Full compiler pipeline:
 *
 *   Phase 1 — Frontend
 *     1. Lexer:          source → tokens
 *     2. Parser:         tokens → raw AST
 *     3. AST Validation: catch structural errors
 *
 *   Phase 2 — Semantic Analysis (multi-pass)
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
 *   Phase 3 — IR Generation
 *     9.  AST → typed IR
 *     10. IR optimization (constant folding, constant propagation, DCE)
 *
 *   Phase 4 — Backend (one optimized IR → three codegen targets)
 *     11. IR → JavaScript, Wasm, LLVM
 */
/**
 * The result of compiling Azora source code through the full pipeline.
 */
sealed class CompilationResult {
    /**
     * Successful compilation result containing all generated outputs and metadata.
     *
     * @property javascript the generated JavaScript source code
     * @property wasm the generated WebAssembly text (WAT)
     * @property llvm the generated LLVM IR text
     * @property ast the CTCE-stabilized AST after semantic analysis
     * @property ir the typed IR before optimization
     * @property optimizedIr the typed IR after optimization passes
     * @property effects the per-function effect classifications
     * @property warnings any non-fatal warnings collected during compilation
     */
    data class Success(
        val javascript: String,
        val wasm: String,
        val llvm: String,
        val ast: Program,
        val ir: IrProgram,
        val optimizedIr: IrProgram,
        val effects: List<EffectChecker.EffectInfo>,
        val warnings: List<String> = emptyList()
    ) : CompilationResult()

    /**
     * Failed compilation result.
     *
     * @property errors the list of error messages that prevented compilation
     */
    data class Failure(val errors: List<String>) : CompilationResult()
}

/**
 * Full compiler pipeline orchestrator.
 *
 * Drives all four phases: frontend (lexer, parser, AST validation),
 * semantic analysis (multi-pass with CTCE), IR generation with optimization,
 * and backend code generation to three targets — JavaScript, WebAssembly, and
 * LLVM IR — all from one optimized IR.
 */
class Compiler {

    /**
     * Compiles Azora source code through the full pipeline.
     *
     * @param source the Azora source code to compile
     * @param warningsAsErrors if `true`, warnings are treated as errors and
     *   cause compilation to fail
     * @return a [CompilationResult.Success] with all generated outputs, or a
     *   [CompilationResult.Failure] with error messages
     */
    /** Points unknown symbols at the loaded library module that provides them. */
    private fun withLibraryHint(message: String): String {
        val match = Regex("(?:undefined function|undefined variable) '([A-Za-z_][A-Za-z0-9_]*)'").find(message)
            ?: return message
        val module = StdlibInjector.moduleOf(match.groupValues[1]) ?: return message
        return "$message — '${match.groupValues[1]}' is provided by '$module': add 'import $module'"
    }

    fun compile(source: String, warningsAsErrors: Boolean = false, release: Boolean = true, debug: Boolean = false, defines: Map<String, String> = emptyMap()): CompilationResult {

        // Clear per-compilation state
        IrType.aliases.clear()

        // CLI `-D NAME=VAL` / named flags drive `config.az` constants and
        // `export if COND` conditions. Publish them before stdlib injection.
        StdlibInjector.configOverrides = defines

        // ===============================================================
        // Phase 1 — Frontend
        // ===============================================================

        // 1-2. Lexer and parser: malformed source is a compilation failure, never
        // an exception that can crash an editor, build tool, or playground host.
        val rawAst = try {
            Parser(Lexer(source).tokenize()).parse()
        } catch (error: IllegalStateException) {
            return CompilationResult.Failure(listOf(error.message ?: "frontend parsing failed"))
        } catch (error: IllegalArgumentException) {
            return CompilationResult.Failure(listOf(error.message ?: "frontend parsing failed"))
        }

        // 2a. Debug builds: instrument statements with `__dbg(line)` markers so a
        // debugger can pause at breakpoints (stdlib, injected below, stays clean).
        val parsed = if (debug) DebugInstrumenter.instrument(rawAst) else rawAst

        // 2b. Standard library: append the stdlib declarations the program
        // actually references (transitively); user definitions shadow stdlib.
        val initiallyInjected = CallbackImplNormalizer.normalize(StdlibInjector.inject(parsed))

        // Decorator derives produce ordinary checked AST methods. Run injection
        // once more afterwards so helper functions referenced by generated
        // methods are loaded transitively from their defining library module.
        val serialization = SerializationDeriver.derive(initiallyInjected)
        if (serialization.errors.isNotEmpty()) {
            return CompilationResult.Failure(serialization.errors)
        }
        val injected = CallbackImplNormalizer.normalize(StdlibInjector.inject(serialization.program))

        // 2c. Expand `meta` macros: rewrite every `Expr.MetaInvoke` into its
        // matched arm's template (splice-substituting `$captures`) and remove
        // the `TopLevel.Meta` declarations. Runs after stdlib injection so both
        // user-defined and library macros are available, and before variadic
        // monomorphization so macro-generated variadic calls (e.g. std::listOf)
        // monomorphize normally. The result is plain expressions — no IR/backend
        // awareness of macros is needed.
        val macroExpanded = try {
            MacroExpander.expand(injected)
        } catch (e: IllegalStateException) {
            return CompilationResult.Failure(listOf(e.message ?: "macro expansion failed"))
        }

        // 2c-bis. Re-inject: macro expansion turns `name!` sites into concrete
        // expressions (e.g. vec![1,2,3] → std__listOf(1,2,3)), which may reference
        // stdlib symbols not pulled in by the pre-expansion injection (the macro
        // template's own dependencies). A second injection pass over the expanded
        // program picks those up transitively.
        val macroReInjected = CallbackImplNormalizer.normalize(StdlibInjector.inject(macroExpanded))

        // 2d. Monomorphize variadic generics (e.g. `Tuple<T…>` / `tupleOf(…)`)
        // into concrete per-instantiation declarations before semantic analysis.
        val ast = try {
            VariadicMonomorphizer.monomorphize(macroReInjected)
        } catch (e: IllegalStateException) {
            return CompilationResult.Failure(listOf(e.message ?: "variadic monomorphization failed"))
        }

        // 3. AST Validation: structural checks
        val validationErrors = AstValidator().validate(ast)
        if (validationErrors.isNotEmpty()) {
            return CompilationResult.Failure(validationErrors)
        }

        // ===============================================================
        // Phase 2 — Semantic Analysis (multi-pass with CTCE loop)
        // ===============================================================

        // Passes 4-8: symbol collection → imports → type resolution ⇄ CTCE → alloc/drop → effects
        val semantic = SemanticPipeline().analyze(ast, defines = defines)

        val warnings = semantic.errors.filter { it.startsWith("warning:") }
        val errors = semantic.errors.filter { !it.startsWith("warning:") }

        if (errors.isNotEmpty()) {
            return CompilationResult.Failure(semantic.errors.map { withLibraryHint(it) })
        }
        if (warningsAsErrors && warnings.isNotEmpty()) {
            return CompilationResult.Failure(semantic.errors)
        }

        // ===============================================================
        // Phase 3 — IR Generation + Optimization
        // ===============================================================

        // 9. AST → typed IR (uses the CTCE-stabilized program)
        val ir = IrGenerator(semantic.symbolTable).generate(semantic.program)

        // 10. IR optimization passes (release mode only)
        val optimizedIr = if (release) IrOptimizer().optimize(ir) else ir

        // ===============================================================
        // Phase 4 — Backend (uses optimized IR in release, raw IR in debug)
        // ===============================================================

        val backendIr = optimizedIr

        // 11. IR → JavaScript
        val javascript = JavaScriptCodegen().generate(backendIr)

        // 12. IR → WebAssembly text (WAT)
        val wasm = WasmCodegen().generate(backendIr)

        // 13. IR → LLVM IR
        val llvm = LlvmCodegen().generate(backendIr)

        return CompilationResult.Success(javascript, wasm, llvm, semantic.program, ir, optimizedIr, semantic.effects, warnings)
    }
}
