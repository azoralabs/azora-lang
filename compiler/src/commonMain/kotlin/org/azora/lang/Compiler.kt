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

package org.azora.lang

import org.azora.lang.backend.KotlinCodegen
import org.azora.lang.backend.LlvmCodegen
import org.azora.lang.backend.TypeScriptCodegen
import org.azora.lang.frontend.AstValidator
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Program
import org.azora.lang.ir.IrGenerator
import org.azora.lang.ir.IrOptimizer
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrType
import org.azora.lang.semantic.EffectChecker
import org.azora.lang.semantic.SemanticPipeline

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
 *            CTFE (Pass 3):            evaluate compile-time functions
 *            fold results back into AST
 *          until stable
 *     7. Alloc/Drop Analysis:         ownership + liveness (post-CTFE)
 *     8. Effect Checking:             purity + side-effect propagation (post-CTFE)
 *
 *   Phase 3 — IR Generation
 *     9.  AST → typed IR
 *     10. IR optimization (constant folding, constant propagation, DCE)
 *
 *   Phase 4 — Backend
 *     11. IR → Kotlin source
 */
/**
 * The result of compiling Azora source code through the full pipeline.
 */
sealed class CompilationResult {
    /**
     * Successful compilation result containing all generated outputs and metadata.
     *
     * @property kotlin the generated Kotlin source code
     * @property typescript the generated TypeScript source code
     * @property llvm the generated LLVM IR text
     * @property ast the CTFE-stabilized AST after semantic analysis
     * @property ir the typed IR before optimization
     * @property optimizedIr the typed IR after optimization passes
     * @property effects the per-function effect classifications
     * @property warnings any non-fatal warnings collected during compilation
     */
    data class Success(
        val kotlin: String,
        val typescript: String,
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
 * semantic analysis (multi-pass with CTFE), IR generation with optimization,
 * and backend code generation to Kotlin, TypeScript, and LLVM IR.
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
    fun compile(source: String, warningsAsErrors: Boolean = false, release: Boolean = true): CompilationResult {

        // Clear per-compilation state
        IrType.aliases.clear()

        // ===============================================================
        // Phase 1 — Frontend
        // ===============================================================

        // 1. Lexer: source → tokens
        val tokens = Lexer(source).tokenize()

        // 2. Parser: tokens → raw AST
        val ast = Parser(tokens).parse()

        // 3. AST Validation: structural checks
        val validationErrors = AstValidator().validate(ast)
        if (validationErrors.isNotEmpty()) {
            return CompilationResult.Failure(validationErrors)
        }

        // ===============================================================
        // Phase 2 — Semantic Analysis (multi-pass with CTFE loop)
        // ===============================================================

        // Passes 4-8: symbol collection → imports → type resolution ⇄ CTFE → alloc/drop → effects
        val semantic = SemanticPipeline().analyze(ast)

        val warnings = semantic.errors.filter { it.startsWith("warning:") }
        val errors = semantic.errors.filter { !it.startsWith("warning:") }

        if (errors.isNotEmpty()) {
            return CompilationResult.Failure(semantic.errors)
        }
        if (warningsAsErrors && warnings.isNotEmpty()) {
            return CompilationResult.Failure(semantic.errors)
        }

        // ===============================================================
        // Phase 3 — IR Generation + Optimization
        // ===============================================================

        // 9. AST → typed IR (uses the CTFE-stabilized program)
        val ir = IrGenerator(semantic.symbolTable).generate(semantic.program)

        // 10. IR optimization passes (release mode only)
        val optimizedIr = if (release) IrOptimizer().optimize(ir) else ir

        // ===============================================================
        // Phase 4 — Backend (uses optimized IR in release, raw IR in debug)
        // ===============================================================

        val backendIr = optimizedIr

        // 11. IR → Kotlin source
        val kotlin = KotlinCodegen().generate(backendIr)

        // 12. IR → TypeScript
        val typescript = TypeScriptCodegen().generate(backendIr)

        // 13. IR → LLVM IR
        val llvm = LlvmCodegen().generate(backendIr)

        return CompilationResult.Success(kotlin, typescript, llvm, semantic.program, ir, optimizedIr, semantic.effects, warnings)
    }
}
