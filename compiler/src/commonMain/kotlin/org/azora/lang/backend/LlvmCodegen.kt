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

package org.azora.lang.backend

import org.azora.lang.ir.IrBinaryOp
import org.azora.lang.ir.IrExpr
import org.azora.lang.ir.IrFunction
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrStmt
import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.IrType
import org.azora.lang.ir.IrUnaryOp

/**
 * Backend — lowers [IrProgram] to LLVM IR text (`.ll` format).
 *
 * The emitted IR is self-contained and directly executable with `lli` or
 * compilable with `clang`/`llc`. No target triple is pinned, so the module
 * adopts the host target.
 *
 * Type mapping:
 *   Int/UInt        → i32
 *   Byte/UByte      → i8
 *   Short/UShort    → i16
 *   Long/ULong      → i64
 *   Cent/UCent      → i128
 *   Real            → double
 *   Float           → float
 *   Decimal         → fp128
 *   Bool            → i1
 *   Char            → i8
 *   String          → i8* (null-terminated C string)
 *   Unit            → void
 *
 * String literals are emitted as private globals. Output goes through `printf`
 * (numeric values) and `puts` (strings/booleans). String concatenation,
 * repetition, comparison and interpolation are lowered to small runtime
 * helpers that call libc (`malloc`, `strlen`, `strcpy`, `strcat`, `strcmp`,
 * `snprintf`).
 *
 * ## Correctness invariants
 * - Every basic block ends with exactly one terminator. The [terminated] flag
 *   tracks whether the current block already has one; [emitStmts] stops
 *   emitting once a block is terminated so no dead (and invalid) instructions
 *   or duplicate terminators are produced.
 * - Unnamed temporaries are numbered consecutively (`%0`, `%1`, …) as LLVM
 *   requires; the terminator-aware emission guarantees no gaps.
 */
class LlvmCodegen {

    private val out = StringBuilder()
    private var tmpCounter = 0
    private var labelCounter = 0
    private var stringCounter = 0
    private val stringConstants = mutableListOf<Pair<String, String>>() // @name -> value

    /** name -> (alloca register, llvm element type). */
    private val localVars = mutableMapOf<String, Pair<String, String>>()

    /** True once the current basic block has a terminator. */
    private var terminated = false

    // Which libc declarations / runtime helpers are referenced.
    private var usesPuts = false
    private var usesPrintf = false
    private var usesAbort = false
    private var usesMalloc = false
    private var usesStrlen = false
    private var usesStrcpy = false
    private var usesStrcat = false
    private var usesStrcmp = false
    private var usesSnprintf = false
    private var usesStrConcat = false
    private var usesStrRepeat = false
    private var usesIntToStr = false
    private var usesRealToStr = false
    private var usesCharToStr = false

    /** Tracks the continue/end labels of enclosing loops for `break`/`continue`. */
    private data class LoopTarget(val continueLabel: String, val endLabel: String)
    private val loopStack = ArrayDeque<LoopTarget>()

    /**
     * Generates LLVM IR text (`.ll` format) from the given IR program.
     */
    fun generate(program: IrProgram): String {
        out.clear()
        tmpCounter = 0
        labelCounter = 0
        stringCounter = 0
        stringConstants.clear()
        usesPuts = false
        usesPrintf = false
        usesAbort = false
        usesMalloc = false
        usesStrlen = false
        usesStrcpy = false
        usesStrcat = false
        usesStrcmp = false
        usesSnprintf = false
        usesStrConcat = false
        usesStrRepeat = false
        usesIntToStr = false
        usesRealToStr = false
        usesCharToStr = false
        loopStack.clear()

        val body = StringBuilder()

        // Struct type definitions (declared for reference; aggregate lowering is
        // not yet implemented, but the type must exist for pointer mapping).
        val structs = program.items.filterIsInstance<IrTopLevel.Struct>()
        if (structs.isNotEmpty()) {
            body.appendLine("; Struct types")
            for (s in structs) {
                val fieldTypes = s.fields.joinToString(", ") { mapType(it.type) }
                body.appendLine("%struct.${sanitizeName(s.name)} = type { $fieldTypes }")
            }
            body.appendLine()
        }

        // Global variables
        val globals = program.items.filterIsInstance<IrTopLevel.Global>()
        if (globals.isNotEmpty()) {
            body.appendLine("; Global variables")
            for (global in globals) {
                out.clear()
                emitGlobal(global.stmt)
                body.append(out)
            }
            body.appendLine()
        }

        // Functions
        for (item in program.items.filterIsInstance<IrTopLevel.Func>()) {
            out.clear()
            emitFunction(item.function)
            body.append(out)
            body.appendLine()
        }

        // Tests
        for (item in program.items.filterIsInstance<IrTopLevel.Test>()) {
            out.clear()
            emitTestFunction(item)
            body.append(out)
            body.appendLine()
        }

        // Runtime helpers (appended after the body so string-constant ids are stable).
        val helpers = buildRuntimeHelpers()

        // String constants
        val strConsts = StringBuilder()
        if (stringConstants.isNotEmpty()) {
            strConsts.appendLine("; String constants")
            for ((name, value) in stringConstants) {
                val escaped = escapeForLlvm(value)
                val len = value.length + 1
                strConsts.appendLine("$name = private unnamed_addr constant [$len x i8] c\"$escaped\\00\"")
            }
        }

        // Assemble final output.
        out.clear()
        line("; LLVM IR generated by Azora compiler")
        line("")
        // External declarations.
        if (usesPuts) line("declare i32 @puts(i8*)")
        if (usesPrintf) line("declare i32 @printf(i8*, ...)")
        if (usesSnprintf) line("declare i32 @snprintf(i8*, i64, i8*, ...)")
        if (usesAbort) line("declare void @abort() noreturn")
        if (usesMalloc) line("declare i8* @malloc(i64)")
        if (usesStrlen) line("declare i64 @strlen(i8*)")
        if (usesStrcpy) line("declare i8* @strcpy(i8*, i8*)")
        if (usesStrcat) line("declare i8* @strcat(i8*, i8*)")
        if (usesStrcmp) line("declare i32 @strcmp(i8*, i8*)")
        line("")
        out.append(body)
        if (helpers.isNotEmpty()) out.append(helpers)
        out.append(strConsts)

        return out.toString().trimEnd()
    }

    // -----------------------------------------------------------------------
    // Globals
    // -----------------------------------------------------------------------

    private fun emitGlobal(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> emitGlobalVar(stmt.name, stmt.type, stmt.initializer)
            is IrStmt.FinDecl -> emitGlobalVar(stmt.name, stmt.type, stmt.initializer)
            is IrStmt.LetDecl -> emitGlobalVar(stmt.name, stmt.type, stmt.initializer)
            else -> line("; unsupported global statement: $stmt")
        }
    }

    private fun emitGlobalVar(name: String, type: IrType, initializer: IrExpr) {
        val llvmType = mapType(type)
        val value = when (initializer) {
            is IrExpr.IntLiteral -> "${initializer.value}"
            is IrExpr.RealLiteral -> floatConst(initializer.value, type)
            is IrExpr.CharLiteral -> "${initializer.value.code}"
            is IrExpr.BoolLiteral -> if (initializer.value) "1" else "0"
            is IrExpr.StringLiteral -> {
                val ref = addStringConstant(initializer.value)
                "getelementptr ([${ref.byteLen} x i8], [${ref.byteLen} x i8]* ${ref.name}, i64 0, i64 0)"
            }
            else -> {
                line("; TODO: non-constant global initializer for $name")
                "zeroinitializer"
            }
        }
        line("@$name = global $llvmType $value")
    }

    // -----------------------------------------------------------------------
    // Functions
    // -----------------------------------------------------------------------

    private fun emitTestFunction(test: IrTopLevel.Test) {
        localVars.clear()
        loopStack.clear()
        tmpCounter = 0
        labelCounter = 0
        terminated = false
        currentBlock = "entry"

        val safeName = sanitizeName(test.name.replace(" ", "_"))
        line("define void @test_$safeName() {")
        line("entry:")
        emitStmts(test.body)
        emitTerminator("  ret void")
        line("}")
    }

    private fun emitFunction(func: IrFunction) {
        localVars.clear()
        loopStack.clear()
        tmpCounter = 0
        labelCounter = 0
        terminated = false
        currentBlock = "entry"

        // The program entry point is always emitted as `i32 @main` returning 0,
        // so the produced executable / `lli` run yields a clean exit code.
        val isMain = func.name == "main"
        val retType = if (isMain) "i32" else mapType(func.returnType)

        val params = func.params.joinToString(", ") { (name, type) ->
            "${mapType(type)} %arg.$name"
        }

        line("define $retType @${func.name}($params) {")
        line("entry:")

        // Spill parameters to the stack so they can be referenced (and reassigned).
        for ((name, type) in func.params) {
            val t = mapType(type)
            val alloca = nextTmp()
            emit("  $alloca = alloca $t")
            emit("  store $t %arg.$name, $t* $alloca")
            localVars[name] = alloca to t
        }

        emitStmts(func.body)

        // Guarantee the final block has a terminator.
        when {
            isMain -> emitTerminator("  ret i32 0")
            func.returnType == IrType.Unit -> emitTerminator("  ret void")
            else -> emitTerminator("  ret ${mapType(func.returnType)} ${defaultValue(func.returnType)}")
        }

        line("}")
    }

    // -----------------------------------------------------------------------
    // Statements
    // -----------------------------------------------------------------------

    /** Emits a list of statements, stopping as soon as a terminator is reached. */
    private fun emitStmts(stmts: List<IrStmt>) {
        for (s in stmts) {
            emitStmt(s)
            if (terminated) break
        }
    }

    private fun emitStmt(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> emitLocalDecl(stmt.name, stmt.type, stmt.initializer)
            is IrStmt.FinDecl -> emitLocalDecl(stmt.name, stmt.type, stmt.initializer)
            is IrStmt.LetDecl -> emitLocalDecl(stmt.name, stmt.type, stmt.initializer)
            is IrStmt.Assignment -> {
                val (alloca, type) = localVars[stmt.name]
                    ?: error("Undefined variable: ${stmt.name}")
                val value = emitExpr(stmt.value)
                emit("  store $type $value, $type* $alloca")
            }
            is IrStmt.Return -> {
                if (stmt.value != null) {
                    val value = emitExpr(stmt.value)
                    val type = mapType(stmt.value.type)
                    emitTerminator("  ret $type $value")
                } else {
                    emitTerminator("  ret void")
                }
            }
            is IrStmt.ExprStmt -> emitExpr(stmt.expr)
            is IrStmt.Zone -> emitStmts(stmt.body)
            is IrStmt.If -> emitIf(stmt)
            is IrStmt.Assert -> emitAssert(stmt)
            is IrStmt.Trace -> emitTrace(stmt)
            is IrStmt.While -> emitWhile(stmt)
            is IrStmt.For -> emitFor(stmt)
            is IrStmt.Loop -> emitLoop(stmt)
            is IrStmt.When -> emitWhen(stmt)
            is IrStmt.Break -> {
                val target = loopStack.lastOrNull() ?: error("break outside of loop")
                emitTerminator("  br label %${target.endLabel}")
            }
            is IrStmt.Continue -> {
                val target = loopStack.lastOrNull() ?: error("continue outside of loop")
                emitTerminator("  br label %${target.continueLabel}")
            }
            is IrStmt.IndexAssign -> {
                emitExpr(stmt.target)
                emitExpr(stmt.index)
                emitExpr(stmt.value)
                emit("  ; index assign — aggregate lowering not yet implemented")
            }
            is IrStmt.MemberAssign -> {
                emitExpr(stmt.target)
                emitExpr(stmt.value)
                emit("  ; member assign .${stmt.name} — aggregate lowering not yet implemented")
            }
            is IrStmt.Defer -> emit("  ; defer — not lowered")
            is IrStmt.Throw -> {
                emitExpr(stmt.value)
                emit("  ; throw — lowered to abort")
                usesAbort = true
                emit("  call void @abort()")
                emitTerminator("  unreachable")
            }
            is IrStmt.Try -> {
                emit("  ; try { ... } — body emitted, exception handling not lowered")
                emitStmts(stmt.body)
            }
        }
    }

    private fun emitLocalDecl(name: String, type: IrType, initializer: IrExpr) {
        val t = mapType(type)
        val alloca = nextTmp()
        emit("  $alloca = alloca $t")
        val value = emitExpr(initializer)
        emit("  store $t $value, $t* $alloca")
        localVars[name] = alloca to t
    }

    private fun emitIf(stmt: IrStmt.If) {
        val cond = emitExpr(stmt.condition)
        val thenLabel = nextLabel("then")
        val elseLabel = nextLabel("else")
        val mergeLabel = nextLabel("merge")

        if (stmt.elseBranch != null) {
            emitTerminator("  br i1 $cond, label %$thenLabel, label %$elseLabel")
        } else {
            emitTerminator("  br i1 $cond, label %$thenLabel, label %$mergeLabel")
        }

        startBlock(thenLabel)
        emitStmts(stmt.thenBranch)
        emitTerminator("  br label %$mergeLabel")

        if (stmt.elseBranch != null) {
            startBlock(elseLabel)
            emitStmts(stmt.elseBranch)
            emitTerminator("  br label %$mergeLabel")
        }

        startBlock(mergeLabel)
    }

    private fun emitWhile(stmt: IrStmt.While) {
        val condLabel = nextLabel("while_cond")
        val bodyLabel = nextLabel("while_body")
        val endLabel = nextLabel("while_end")

        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val cond = emitExpr(stmt.condition)
        emitTerminator("  br i1 $cond, label %$bodyLabel, label %$endLabel")

        startBlock(bodyLabel)
        loopStack.addLast(LoopTarget(condLabel, endLabel))
        emitStmts(stmt.body)
        loopStack.removeLast()
        emitTerminator("  br label %$condLabel")

        startBlock(endLabel)
    }

    private fun emitFor(stmt: IrStmt.For) {
        val t = mapType(stmt.start.type)
        val unsigned = isUnsigned(stmt.start.type)
        val counterAlloca = nextTmp()
        emit("  $counterAlloca = alloca $t")
        val startVal = emitExpr(stmt.start)
        emit("  store $t $startVal, $t* $counterAlloca")
        localVars[stmt.counter] = counterAlloca to t

        val condLabel = nextLabel("for_cond")
        val bodyLabel = nextLabel("for_body")
        val incLabel = nextLabel("for_inc")
        val endLabel = nextLabel("for_end")

        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val loaded = nextTmp()
        emit("  $loaded = load $t, $t* $counterAlloca")
        val endVal = emitExpr(stmt.end)
        val cmp = nextTmp()
        val pred = when {
            stmt.inclusive && unsigned -> "ule"
            stmt.inclusive -> "sle"
            unsigned -> "ult"
            else -> "slt"
        }
        emit("  $cmp = icmp $pred $t $loaded, $endVal")
        emitTerminator("  br i1 $cmp, label %$bodyLabel, label %$endLabel")

        startBlock(bodyLabel)
        loopStack.addLast(LoopTarget(incLabel, endLabel))
        emitStmts(stmt.body)
        loopStack.removeLast()
        emitTerminator("  br label %$incLabel")

        startBlock(incLabel)
        val incLoaded = nextTmp()
        emit("  $incLoaded = load $t, $t* $counterAlloca")
        val incd = nextTmp()
        emit("  $incd = add $t $incLoaded, 1")
        emit("  store $t $incd, $t* $counterAlloca")
        emitTerminator("  br label %$condLabel")

        startBlock(endLabel)
    }

    private fun emitLoop(stmt: IrStmt.Loop) {
        val bodyLabel = nextLabel("loop_body")
        val endLabel = nextLabel("loop_end")

        emitTerminator("  br label %$bodyLabel")
        startBlock(bodyLabel)
        loopStack.addLast(LoopTarget(bodyLabel, endLabel))
        emitStmts(stmt.body)
        loopStack.removeLast()
        emitTerminator("  br label %$bodyLabel")

        startBlock(endLabel)
    }

    private fun emitWhen(stmt: IrStmt.When) {
        val scrutType = mapType(stmt.scrutinee.type)
        val scrut = emitExpr(stmt.scrutinee)
        val endLabel = nextLabel("when_end")

        for (branch in stmt.branches) {
            val bodyLabel = nextLabel("when_body")
            // Test each pattern; any match jumps to the body, otherwise fall
            // through to the next pattern test (and finally the next branch).
            for ((i, pattern) in branch.patterns.withIndex()) {
                val pv = emitExpr(pattern)
                val cmp = nextTmp()
                emit("  $cmp = icmp eq $scrutType $scrut, $pv")
                if (i == branch.patterns.lastIndex) {
                    val nextLabel = nextLabel("when_next")
                    emitTerminator("  br i1 $cmp, label %$bodyLabel, label %$nextLabel")
                    startBlock(bodyLabel)
                    emitStmts(branch.body)
                    emitTerminator("  br label %$endLabel")
                    startBlock(nextLabel)
                } else {
                    val moreLabel = nextLabel("when_or")
                    emitTerminator("  br i1 $cmp, label %$bodyLabel, label %$moreLabel")
                    startBlock(moreLabel)
                }
            }
        }

        if (stmt.elseBranch != null) {
            emitStmts(stmt.elseBranch)
        }
        emitTerminator("  br label %$endLabel")
        startBlock(endLabel)
    }

    private fun emitAssert(stmt: IrStmt.Assert) {
        usesAbort = true
        usesPuts = true
        val cond = emitExpr(stmt.condition)
        val failLabel = nextLabel("assert_fail")
        val passLabel = nextLabel("assert_pass")
        emitTerminator("  br i1 $cond, label %$passLabel, label %$failLabel")

        startBlock(failLabel)
        val msg = emitExpr(stmt.message)
        val unused = nextTmp()
        emit("  $unused = call i32 @puts(i8* $msg)")
        emit("  call void @abort()")
        emitTerminator("  unreachable")

        startBlock(passLabel)
    }

    private fun emitTrace(stmt: IrStmt.Trace) {
        usesPrintf = true
        val msg = stringify(stmt.message)
        val fmtRef = addStringConstant("[TRACE] %s\n")
        val fmtPtr = gepString(fmtRef)
        val unused = nextTmp()
        emit("  $unused = call i32 (i8*, ...) @printf(i8* $fmtPtr, i8* $msg)")
    }

    // -----------------------------------------------------------------------
    // Expressions
    // -----------------------------------------------------------------------

    /** Emits LLVM IR for an expression and returns the register/value. */
    private fun emitExpr(expr: IrExpr): String = when (expr) {
        is IrExpr.IntLiteral -> "${expr.value}"
        is IrExpr.CharLiteral -> "${expr.value.code}"
        is IrExpr.RealLiteral -> floatConst(expr.value, expr.type)
        is IrExpr.BoolLiteral -> if (expr.value) "1" else "0"
        is IrExpr.StringLiteral -> {
            val ref = addStringConstant(expr.value)
            gepString(ref)
        }
        is IrExpr.Var -> {
            val local = localVars[expr.name]
            val tmp = nextTmp()
            if (local != null) {
                val (alloca, type) = local
                emit("  $tmp = load $type, $type* $alloca")
            } else {
                val type = mapType(expr.type)
                emit("  $tmp = load $type, $type* @${expr.name}")
            }
            tmp
        }
        is IrExpr.Unary -> emitUnary(expr)
        is IrExpr.Binary -> emitBinary(expr)
        is IrExpr.Call -> emitCall(expr)
        is IrExpr.StringTemplate -> emitStringTemplate(expr)
        is IrExpr.ArrayLiteral -> {
            for (e in expr.elements) emitExpr(e)
            emit("  ; array literal — aggregate lowering not yet implemented")
            "null"
        }
        is IrExpr.Index -> {
            emitExpr(expr.target)
            emitExpr(expr.index)
            emit("  ; index — aggregate lowering not yet implemented")
            defaultValue(expr.type)
        }
        is IrExpr.Member -> {
            emitExpr(expr.target)
            emit("  ; member .${expr.name} — aggregate lowering not yet implemented")
            defaultValue(expr.type)
        }
        is IrExpr.MethodCall -> {
            emitExpr(expr.target)
            for (a in expr.args) emitExpr(a)
            emit("  ; method .${expr.name} — not lowered")
            defaultValue(expr.type)
        }
        is IrExpr.StructCtor -> {
            for (a in expr.args) emitExpr(a)
            emit("  ; struct ${expr.name} construction — aggregate lowering not yet implemented")
            "null"
        }
        is IrExpr.TupleLit -> {
            for (e in expr.elements) emitExpr(e)
            emit("  ; tuple literal — aggregate lowering not yet implemented")
            "null"
        }
        is IrExpr.TupleAccess -> {
            emitExpr(expr.target)
            emit("  ; tuple access .${expr.index} — not lowered")
            defaultValue(expr.type)
        }
        is IrExpr.CatchExpr -> {
            // The fallback path is not lowered; evaluate the primary expression.
            emitExpr(expr.expr)
        }
        is IrExpr.SlotPattern -> "0"
        is IrExpr.Lambda -> {
            emit("  ; lambda/closure — not lowered")
            "null"
        }
    }

    private fun emitUnary(expr: IrExpr.Unary): String {
        val operand = emitExpr(expr.operand)
        val tmp = nextTmp()
        when (expr.op) {
            IrUnaryOp.NEG -> {
                val llvmType = mapType(expr.type)
                when {
                    expr.type in IrType.integerTypes -> emit("  $tmp = sub $llvmType 0, $operand")
                    expr.type in IrType.floatTypes -> emit("  $tmp = fneg $llvmType $operand")
                    else -> error("Cannot negate type: ${expr.type}")
                }
            }
            IrUnaryOp.NOT -> emit("  $tmp = xor i1 $operand, 1")
            IrUnaryOp.BIT_NOT -> emit("  $tmp = xor ${mapType(expr.type)} $operand, -1")
        }
        return tmp
    }

    private fun emitBinary(expr: IrExpr.Binary): String {
        // String-typed operations are routed to runtime helpers.
        if (expr.left.type == IrType.String || expr.right.type == IrType.String) {
            return emitStringBinary(expr)
        }

        val leftType = expr.left.type
        val llvmType = mapType(leftType)

        // Short-circuit boolean && / || via control flow.
        if (leftType == IrType.Bool && (expr.op == IrBinaryOp.AND || expr.op == IrBinaryOp.OR)) {
            return emitShortCircuit(expr)
        }

        val left = emitExpr(expr.left)
        val right = emitExpr(expr.right)
        val tmp = nextTmp()

        when {
            leftType in IrType.integerTypes || leftType == IrType.Char -> {
                val u = isUnsigned(leftType)
                val inst = when (expr.op) {
                    IrBinaryOp.ADD -> "add $llvmType"
                    IrBinaryOp.SUB -> "sub $llvmType"
                    IrBinaryOp.MUL -> "mul $llvmType"
                    IrBinaryOp.DIV -> if (u) "udiv $llvmType" else "sdiv $llvmType"
                    IrBinaryOp.MOD -> if (u) "urem $llvmType" else "srem $llvmType"
                    IrBinaryOp.EQ -> "icmp eq $llvmType"
                    IrBinaryOp.NEQ -> "icmp ne $llvmType"
                    IrBinaryOp.LT -> if (u) "icmp ult $llvmType" else "icmp slt $llvmType"
                    IrBinaryOp.LTE -> if (u) "icmp ule $llvmType" else "icmp sle $llvmType"
                    IrBinaryOp.GT -> if (u) "icmp ugt $llvmType" else "icmp sgt $llvmType"
                    IrBinaryOp.GTE -> if (u) "icmp uge $llvmType" else "icmp sge $llvmType"
                    IrBinaryOp.BIT_AND -> "and $llvmType"
                    IrBinaryOp.BIT_OR -> "or $llvmType"
                    IrBinaryOp.BIT_XOR -> "xor $llvmType"
                    IrBinaryOp.SHL -> "shl $llvmType"
                    IrBinaryOp.SHR -> if (u) "lshr $llvmType" else "ashr $llvmType"
                    else -> error("Unsupported int op: ${expr.op}")
                }
                emit("  $tmp = $inst $left, $right")
            }
            leftType in IrType.floatTypes -> {
                val inst = when (expr.op) {
                    IrBinaryOp.ADD -> "fadd $llvmType"
                    IrBinaryOp.SUB -> "fsub $llvmType"
                    IrBinaryOp.MUL -> "fmul $llvmType"
                    IrBinaryOp.DIV -> "fdiv $llvmType"
                    IrBinaryOp.MOD -> "frem $llvmType"
                    IrBinaryOp.EQ -> "fcmp oeq $llvmType"
                    IrBinaryOp.NEQ -> "fcmp one $llvmType"
                    IrBinaryOp.LT -> "fcmp olt $llvmType"
                    IrBinaryOp.LTE -> "fcmp ole $llvmType"
                    IrBinaryOp.GT -> "fcmp ogt $llvmType"
                    IrBinaryOp.GTE -> "fcmp oge $llvmType"
                    else -> error("Unsupported float op: ${expr.op}")
                }
                emit("  $tmp = $inst $left, $right")
            }
            leftType == IrType.Bool -> {
                when (expr.op) {
                    IrBinaryOp.EQ -> emit("  $tmp = icmp eq i1 $left, $right")
                    IrBinaryOp.NEQ -> emit("  $tmp = icmp ne i1 $left, $right")
                    IrBinaryOp.BIT_AND -> emit("  $tmp = and i1 $left, $right")
                    IrBinaryOp.BIT_OR -> emit("  $tmp = or i1 $left, $right")
                    IrBinaryOp.BIT_XOR -> emit("  $tmp = xor i1 $left, $right")
                    else -> error("Unsupported bool op: ${expr.op}")
                }
            }
            else -> error("Unsupported binary ${expr.op} on ${expr.left.type}")
        }
        return tmp
    }

    /** Lowers `&&` / `||` with short-circuit evaluation via phi. */
    private fun emitShortCircuit(expr: IrExpr.Binary): String {
        val isAnd = expr.op == IrBinaryOp.AND
        val left = emitExpr(expr.left)
        val rhsLabel = nextLabel(if (isAnd) "and_rhs" else "or_rhs")
        val endLabel = nextLabel(if (isAnd) "and_end" else "or_end")
        // Capture the predecessor block that holds the left value.
        val leftBlock = currentBlock
        if (isAnd) {
            emitTerminator("  br i1 $left, label %$rhsLabel, label %$endLabel")
        } else {
            emitTerminator("  br i1 $left, label %$endLabel, label %$rhsLabel")
        }
        startBlock(rhsLabel)
        val right = emitExpr(expr.right)
        val rhsBlock = currentBlock
        emitTerminator("  br label %$endLabel")
        startBlock(endLabel)
        val tmp = nextTmp()
        // On the short-circuit edge the result is the left value (false for &&,
        // true for ||); otherwise it is the right value.
        val shortVal = if (isAnd) "false" else "true"
        emit("  $tmp = phi i1 [ $shortVal, %$leftBlock ], [ $right, %$rhsBlock ]")
        return tmp
    }

    private fun emitStringBinary(expr: IrExpr.Binary): String {
        return when (expr.op) {
            IrBinaryOp.ADD -> {
                usesStrConcat = true
                val left = stringify(expr.left)
                val right = stringify(expr.right)
                val tmp = nextTmp()
                emit("  $tmp = call i8* @__azora_str_concat(i8* $left, i8* $right)")
                tmp
            }
            IrBinaryOp.MUL -> {
                usesStrRepeat = true
                val (strExpr, intExpr) = if (expr.left.type == IrType.String)
                    expr.left to expr.right else expr.right to expr.left
                val str = emitExpr(strExpr)
                val countRaw = emitExpr(intExpr)
                val count = coerceToI32(countRaw, intExpr.type)
                val tmp = nextTmp()
                emit("  $tmp = call i8* @__azora_str_repeat(i8* $str, i32 $count)")
                tmp
            }
            IrBinaryOp.EQ, IrBinaryOp.NEQ -> {
                usesStrcmp = true
                val left = emitExpr(expr.left)
                val right = emitExpr(expr.right)
                val cmp = nextTmp()
                emit("  $cmp = call i32 @strcmp(i8* $left, i8* $right)")
                val tmp = nextTmp()
                val pred = if (expr.op == IrBinaryOp.EQ) "eq" else "ne"
                emit("  $tmp = icmp $pred i32 $cmp, 0")
                tmp
            }
            else -> error("Unsupported string op: ${expr.op}")
        }
    }

    private fun emitStringTemplate(expr: IrExpr.StringTemplate): String {
        usesStrConcat = true
        // Fold the parts left-to-right with the concat helper.
        var acc: String? = null
        for (part in expr.parts) {
            val piece = when (part) {
                is IrExpr.IrTemplatePart.Literal -> gepString(addStringConstant(part.text))
                is IrExpr.IrTemplatePart.Expr -> stringify(part.expr)
            }
            acc = if (acc == null) piece else {
                val tmp = nextTmp()
                emit("  $tmp = call i8* @__azora_str_concat(i8* $acc, i8* $piece)")
                tmp
            }
        }
        return acc ?: gepString(addStringConstant(""))
    }

    private fun emitCall(expr: IrExpr.Call): String {
        if (expr.name == "println") return emitPrintln(expr)
        if (expr.name == "print") return emitPrintln(expr, newline = false)

        val args = expr.args.joinToString(", ") { arg ->
            "${mapType(arg.type)} ${emitExpr(arg)}"
        }
        val retType = mapType(expr.type)
        return if (expr.type == IrType.Unit) {
            emit("  call void @${expr.name}($args)")
            "void"
        } else {
            val tmp = nextTmp()
            emit("  $tmp = call $retType @${expr.name}($args)")
            tmp
        }
    }

    private fun emitPrintln(expr: IrExpr.Call, newline: Boolean = true): String {
        val nl = if (newline) "\n" else ""
        if (expr.args.isEmpty()) {
            printfFmt("", emptyList())
            if (newline) printNewline()
            return "void"
        }

        val arg = expr.args[0]
        when (arg.type) {
            IrType.String -> {
                if (newline) {
                    usesPuts = true
                    val v = emitExpr(arg)
                    val unused = nextTmp()
                    emit("  $unused = call i32 @puts(i8* $v)")
                } else {
                    val v = emitExpr(arg)
                    printfFmt("%s", listOf("i8* $v"))
                }
            }
            IrType.Char -> {
                val v = coerceToI32(emitExpr(arg), arg.type)
                printfFmt("%c$nl", listOf("i32 $v"))
            }
            IrType.Int, IrType.UInt, IrType.Byte, IrType.UByte, IrType.Short, IrType.UShort -> {
                val v = coerceToI32(emitExpr(arg), arg.type)
                printfFmt("%d$nl", listOf("i32 $v"))
            }
            IrType.Long, IrType.ULong -> {
                val v = emitExpr(arg)
                printfFmt("%lld$nl", listOf("i64 $v"))
            }
            IrType.Cent, IrType.UCent -> {
                val v = emitExpr(arg)
                // No portable printf length modifier for i128; truncate to i64.
                val t = nextTmp()
                emit("  $t = trunc i128 $v to i64")
                printfFmt("%lld$nl", listOf("i64 $t"))
            }
            IrType.Real -> {
                val v = emitExpr(arg)
                printfFmt("%g$nl", listOf("double $v"))
            }
            IrType.Float -> {
                val v = emitExpr(arg)
                val ext = nextTmp()
                emit("  $ext = fpext float $v to double")
                printfFmt("%g$nl", listOf("double $ext"))
            }
            IrType.Decimal -> {
                val v = emitExpr(arg)
                val d = nextTmp()
                emit("  $d = fptrunc fp128 $v to double")
                printfFmt("%g$nl", listOf("double $d"))
            }
            IrType.Bool -> {
                val v = emitExpr(arg)
                val s = boolToStr(v)
                if (newline) {
                    usesPuts = true
                    val unused = nextTmp()
                    emit("  $unused = call i32 @puts(i8* $s)")
                } else {
                    printfFmt("%s", listOf("i8* $s"))
                }
            }
            else -> {
                emitExpr(arg)
                val placeholder = gepString(addStringConstant("<value>"))
                if (newline) {
                    usesPuts = true
                    val unused = nextTmp()
                    emit("  $unused = call i32 @puts(i8* $placeholder)")
                } else {
                    printfFmt("%s", listOf("i8* $placeholder"))
                }
            }
        }
        return "void"
    }

    /** Emits a `printf` call with the given format and already-typed arguments. */
    private fun printfFmt(fmt: String, args: List<String>) {
        usesPrintf = true
        val ref = addStringConstant(fmt)
        val ptr = gepString(ref)
        val all = (listOf("i8* $ptr") + args).joinToString(", ")
        val unused = nextTmp()
        emit("  $unused = call i32 (i8*, ...) @printf($all)")
    }

    private fun printNewline() {
        usesPrintf = true
        printfFmt("\n", emptyList())
    }

    // -----------------------------------------------------------------------
    // Value conversion helpers (used for printf varargs / string building)
    // -----------------------------------------------------------------------

    /** Sign/zero-extends a sub-i32 integer value to i32 for printf varargs. */
    private fun coerceToI32(value: String, type: IrType): String = when (type) {
        IrType.Int, IrType.UInt -> value
        IrType.Byte, IrType.Short -> {
            val t = nextTmp(); emit("  $t = sext ${mapType(type)} $value to i32"); t
        }
        IrType.Char, IrType.UByte, IrType.UShort -> {
            val t = nextTmp(); emit("  $t = zext ${mapType(type)} $value to i32"); t
        }
        IrType.Long, IrType.ULong -> {
            val t = nextTmp(); emit("  $t = trunc i64 $value to i32"); t
        }
        else -> value
    }

    /** Produces an `i8*` C string for any scalar value (used by interpolation). */
    private fun stringify(expr: IrExpr): String {
        return when (expr.type) {
            IrType.String -> emitExpr(expr)
            IrType.Bool -> boolToStr(emitExpr(expr))
            IrType.Char -> {
                usesCharToStr = true; usesSnprintf = true; usesMalloc = true
                val v = coerceToI32(emitExpr(expr), expr.type)
                val tmp = nextTmp()
                emit("  $tmp = call i8* @__azora_char_to_str(i32 $v)")
                tmp
            }
            IrType.Real, IrType.Float, IrType.Decimal -> {
                usesRealToStr = true; usesSnprintf = true; usesMalloc = true
                val raw = emitExpr(expr)
                val d = when (expr.type) {
                    IrType.Real -> raw
                    IrType.Float -> { val t = nextTmp(); emit("  $t = fpext float $raw to double"); t }
                    else -> { val t = nextTmp(); emit("  $t = fptrunc fp128 $raw to double"); t }
                }
                val tmp = nextTmp()
                emit("  $tmp = call i8* @__azora_real_to_str(double $d)")
                tmp
            }
            else -> {
                // Integer / char types → 64-bit then format.
                usesIntToStr = true; usesSnprintf = true; usesMalloc = true
                val raw = emitExpr(expr)
                val v = widenToI64(raw, expr.type)
                val tmp = nextTmp()
                emit("  $tmp = call i8* @__azora_int_to_str(i64 $v)")
                tmp
            }
        }
    }

    private fun widenToI64(value: String, type: IrType): String = when (type) {
        IrType.Long, IrType.ULong -> value
        IrType.Cent, IrType.UCent -> { val t = nextTmp(); emit("  $t = trunc i128 $value to i64"); t }
        IrType.UInt, IrType.UByte, IrType.UShort, IrType.Char ->
            { val t = nextTmp(); emit("  $t = zext ${mapType(type)} $value to i64"); t }
        else -> { val t = nextTmp(); emit("  $t = sext ${mapType(type)} $value to i64"); t }
    }

    /** Selects the `"true"`/`"false"` C string for a boolean value. */
    private fun boolToStr(value: String): String {
        val trueRef = addStringConstant("true")
        val falseRef = addStringConstant("false")
        val truePtr = gepString(trueRef)
        val falsePtr = gepString(falseRef)
        val sel = nextTmp()
        emit("  $sel = select i1 $value, i8* $truePtr, i8* $falsePtr")
        return sel
    }

    // -----------------------------------------------------------------------
    // Runtime helper definitions
    // -----------------------------------------------------------------------

    private fun buildRuntimeHelpers(): String {
        val sb = StringBuilder()

        if (usesStrConcat) {
            usesMalloc = true; usesStrlen = true; usesStrcpy = true; usesStrcat = true
            sb.appendLine("; runtime: string concatenation")
            sb.appendLine("define i8* @__azora_str_concat(i8* %a, i8* %b) {")
            sb.appendLine("entry:")
            sb.appendLine("  %la = call i64 @strlen(i8* %a)")
            sb.appendLine("  %lb = call i64 @strlen(i8* %b)")
            sb.appendLine("  %sum = add i64 %la, %lb")
            sb.appendLine("  %size = add i64 %sum, 1")
            sb.appendLine("  %buf = call i8* @malloc(i64 %size)")
            sb.appendLine("  %c1 = call i8* @strcpy(i8* %buf, i8* %a)")
            sb.appendLine("  %c2 = call i8* @strcat(i8* %buf, i8* %b)")
            sb.appendLine("  ret i8* %buf")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesStrRepeat) {
            usesMalloc = true; usesStrlen = true
            sb.appendLine("; runtime: string repetition")
            sb.appendLine("define i8* @__azora_str_repeat(i8* %s, i32 %n) {")
            sb.appendLine("entry:")
            sb.appendLine("  %len = call i64 @strlen(i8* %s)")
            sb.appendLine("  %n64 = sext i32 %n to i64")
            sb.appendLine("  %total = mul i64 %len, %n64")
            sb.appendLine("  %size = add i64 %total, 1")
            sb.appendLine("  %buf = call i8* @malloc(i64 %size)")
            sb.appendLine("  store i8 0, i8* %buf")
            sb.appendLine("  br label %cond")
            sb.appendLine("cond:")
            sb.appendLine("  %i = phi i32 [ 0, %entry ], [ %inext, %body ]")
            sb.appendLine("  %dst = phi i8* [ %buf, %entry ], [ %dst2, %body ]")
            sb.appendLine("  %done = icmp sge i32 %i, %n")
            sb.appendLine("  br i1 %done, label %end, label %body")
            sb.appendLine("body:")
            sb.appendLine("  %cpy = call i8* @strcpy(i8* %dst, i8* %s)")
            sb.appendLine("  %dst2 = getelementptr i8, i8* %dst, i64 %len")
            sb.appendLine("  %inext = add i32 %i, 1")
            sb.appendLine("  br label %cond")
            sb.appendLine("end:")
            sb.appendLine("  ret i8* %buf")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesIntToStr) {
            usesMalloc = true; usesSnprintf = true
            val fmt = addStringConstant("%lld")
            sb.appendLine("; runtime: integer to string")
            sb.appendLine("define i8* @__azora_int_to_str(i64 %v) {")
            sb.appendLine("entry:")
            sb.appendLine("  %buf = call i8* @malloc(i64 24)")
            sb.appendLine("  %fmt = getelementptr [${fmt.byteLen} x i8], [${fmt.byteLen} x i8]* ${fmt.name}, i64 0, i64 0")
            sb.appendLine("  %r = call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 24, i8* %fmt, i64 %v)")
            sb.appendLine("  ret i8* %buf")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesRealToStr) {
            usesMalloc = true; usesSnprintf = true
            val fmt = addStringConstant("%g")
            sb.appendLine("; runtime: real to string")
            sb.appendLine("define i8* @__azora_real_to_str(double %v) {")
            sb.appendLine("entry:")
            sb.appendLine("  %buf = call i8* @malloc(i64 32)")
            sb.appendLine("  %fmt = getelementptr [${fmt.byteLen} x i8], [${fmt.byteLen} x i8]* ${fmt.name}, i64 0, i64 0")
            sb.appendLine("  %r = call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 32, i8* %fmt, double %v)")
            sb.appendLine("  ret i8* %buf")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesCharToStr) {
            usesMalloc = true; usesSnprintf = true
            val fmt = addStringConstant("%c")
            sb.appendLine("; runtime: char to string")
            sb.appendLine("define i8* @__azora_char_to_str(i32 %v) {")
            sb.appendLine("entry:")
            sb.appendLine("  %buf = call i8* @malloc(i64 2)")
            sb.appendLine("  %fmt = getelementptr [${fmt.byteLen} x i8], [${fmt.byteLen} x i8]* ${fmt.name}, i64 0, i64 0")
            sb.appendLine("  %r = call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 2, i8* %fmt, i32 %v)")
            sb.appendLine("  ret i8* %buf")
            sb.appendLine("}")
            sb.appendLine()
        }

        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // Low-level helpers
    // -----------------------------------------------------------------------

    private fun mapType(type: IrType): String = when (type) {
        IrType.Int -> "i32"
        IrType.UInt -> "i32"
        IrType.Real -> "double"
        IrType.Bool -> "i1"
        IrType.String -> "i8*"
        IrType.Unit -> "void"
        IrType.Char -> "i8"
        IrType.Byte -> "i8"
        IrType.UByte -> "i8"
        IrType.Short -> "i16"
        IrType.UShort -> "i16"
        IrType.Long -> "i64"
        IrType.ULong -> "i64"
        IrType.Cent -> "i128"
        IrType.UCent -> "i128"
        IrType.Float -> "float"
        IrType.Decimal -> "fp128"
        IrType.Any -> "i8*"
        is IrType.Array -> "i8*"
        is IrType.Map -> "i8*"
        is IrType.Set -> "i8*"
        is IrType.Function -> "i8*"
        is IrType.Tuple -> "i8*"
        is IrType.Nullable -> "i8*"
        is IrType.Named -> "%struct.${sanitizeName(type.name)}*"
    }

    private fun isUnsigned(type: IrType): Boolean = when (type) {
        IrType.UInt, IrType.UByte, IrType.UShort, IrType.ULong, IrType.UCent -> true
        else -> false
    }

    /** A type-appropriate default/zero value, used for unreachable returns. */
    private fun defaultValue(type: IrType): String = when (type) {
        in IrType.floatTypes -> floatConst(0.0, type)
        IrType.Bool -> "false"
        IrType.String -> "null"
        IrType.Unit -> ""
        is IrType.Array, is IrType.Map, is IrType.Set, is IrType.Function,
        is IrType.Tuple, is IrType.Nullable, is IrType.Named, IrType.Any -> "null"
        else -> "0"
    }

    /** Formats a floating-point constant in the exact LLVM hex form. */
    private fun floatConst(value: Double, type: IrType): String {
        // LLVM accepts the 64-bit IEEE-754 hex for both float and double
        // constants (for float it must be exactly representable, which holds
        // because the value originated from a float).
        val bits = if (type == IrType.Float)
            value.toFloat().toDouble().toRawBits()
        else
            value.toRawBits()
        return "0x" + bits.toULong().toString(16).uppercase().padStart(16, '0')
    }

    private fun gepString(ref: StringRef): String {
        val tmp = nextTmp()
        emit("  $tmp = getelementptr [${ref.byteLen} x i8], [${ref.byteLen} x i8]* ${ref.name}, i64 0, i64 0")
        return tmp
    }

    private fun sanitizeName(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

    private fun nextTmp(): String = "%${tmpCounter++}"

    private fun nextLabel(prefix: String): String = "$prefix.${labelCounter++}"

    data class StringRef(val name: String, val byteLen: Int)

    private fun addStringConstant(value: String): StringRef {
        for ((name, v) in stringConstants) {
            if (v == value) return StringRef(name, value.length + 1)
        }
        val name = "@.str.$stringCounter"
        stringCounter++
        stringConstants.add(name to value)
        return StringRef(name, value.length + 1)
    }

    private fun escapeForLlvm(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\5C")
                '"' -> sb.append("\\22")
                '\n' -> sb.append("\\0A")
                '\t' -> sb.append("\\09")
                '\r' -> sb.append("\\0D")
                else -> if (c.code in 32..126) sb.append(c)
                        else sb.append("\\${c.code.toString(16).uppercase().padStart(2, '0')}")
            }
        }
        return sb.toString()
    }

    /** The label of the basic block currently being emitted (for phi nodes). */
    private var currentBlock: String = "entry"

    private fun startBlock(label: String) {
        line("$label:")
        currentBlock = label
        terminated = false
    }

    private fun emitTerminator(text: String) {
        if (!terminated) {
            out.appendLine(text)
            terminated = true
        }
    }

    private fun emit(text: String) {
        out.appendLine(text)
    }

    private fun line(text: String) {
        out.appendLine(text)
    }
}
