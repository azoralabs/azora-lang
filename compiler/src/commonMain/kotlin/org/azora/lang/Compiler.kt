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
import org.azora.lang.backend.TypeScriptCodegen
import org.azora.lang.backend.WasmCodegen
import org.azora.lang.frontend.AstValidator
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.DebugInstrumenter
import org.azora.lang.frontend.Parser
import org.azora.lang.stdlib.StdlibInjector
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
 *   Phase 4 — Backend (one optimized IR → nine codegen targets)
 *     11. IR → Kotlin, TypeScript, Swift, Dart, C#, Python, Rust, Wasm, LLVM
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
     * @property swift the generated Swift 6.3 source code
     * @property dart the generated Dart source code
     * @property csharp the generated C# / .NET source code
     * @property python the generated Python 3 source code
     * @property rust the generated Rust source code
     * @property wasm the generated WebAssembly text (WAT)
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
        val swift: String,
        val dart: String,
        val csharp: String,
        val python: String,
        val rust: String,
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
 * semantic analysis (multi-pass with CTFE), IR generation with optimization,
 * and backend code generation to nine targets — Kotlin, TypeScript, Swift,
 * Dart, C#, Python, Rust, WebAssembly, and LLVM IR — all from one optimized IR.
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
    /**
     * When an unknown-symbol error names something the standard library
     * provides, point at the missing import ("add 'use std.math'").
     */
    private fun withStdlibHint(message: String): String {
        val match = Regex("(?:undefined function|undefined variable) '([A-Za-z_][A-Za-z0-9_]*)'").find(message)
            ?: return message
        val module = StdlibInjector.moduleOf(match.groupValues[1]) ?: return message
        return "$message — '${match.groupValues[1]}' is in the standard library: add 'use $module'"
    }

    fun compile(source: String, warningsAsErrors: Boolean = false, release: Boolean = true, debug: Boolean = false): CompilationResult {

        // Clear per-compilation state
        IrType.aliases.clear()

        // ===============================================================
        // Phase 1 — Frontend
        // ===============================================================

        // 1. Lexer: source → tokens
        val tokens = Lexer(source).tokenize()

        // 2. Parser: tokens → raw AST
        val rawAst = Parser(tokens).parse()

        // 2a. Debug builds: instrument statements with `__dbg(line)` markers so a
        // debugger can pause at breakpoints (stdlib, injected below, stays clean).
        val parsed = if (debug) DebugInstrumenter.instrument(rawAst) else rawAst

        // 2b. Standard library: append the stdlib declarations the program
        // actually references (transitively); user definitions shadow stdlib.
        val ast = StdlibInjector.inject(parsed)

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
            return CompilationResult.Failure(semantic.errors.map { withStdlibHint(it) })
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

        // 13. IR → Swift 6.3
        val swift = SwiftCodegen().generate(backendIr)

        // 14. IR → Dart
        val dart = DartCodegen().generate(backendIr)

        // 15. IR → C# / .NET
        val csharp = CSharpCodegen().generate(backendIr)

        // 16. IR → Python 3
        val python = PythonCodegen().generate(backendIr)

        // 17. IR → Rust
        val rust = RustCodegen().generate(backendIr)

        // 18. IR → WebAssembly text (WAT)
        val wasm = WasmCodegen().generate(backendIr)

        // 19. IR → LLVM IR
        val llvm = LlvmCodegen().generate(backendIr)

        return CompilationResult.Success(kotlin, typescript, swift, dart, csharp, python, rust, wasm, llvm, semantic.program, ir, optimizedIr, semantic.effects, warnings)
    }
}
