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

    private var out = StringBuilder()
    private var tmpCounter = 0
    private var labelCounter = 0
    private var stringCounter = 0
    private val stringConstants = mutableListOf<Pair<String, String>>() // @name -> value

    /** name -> (alloca register, llvm element type). */
    private val localVars = mutableMapOf<String, Pair<String, String>>()

    /**
     * (variable name, llvm type) -> entry-block alloca register.
     *
     * All local allocas are hoisted into the function's entry block: an
     * `alloca` emitted inside a loop body allocates NEW stack space on every
     * iteration (stack unwinds only on return), so a render loop would leak
     * stack each frame and eventually fault on the guard page. One slot per
     * (name, type) is reused across iterations/branches — IR names are
     * pre-mangled for shadowing, and disjoint scopes reusing a name always
     * store before reading.
     */
    private val allocaSlots = mutableMapOf<Pair<String, String>, String>()
    private var allocaCounter = 0

    /** Struct (pack/solo/node) definitions by name, for field-index lookup. */
    private val structDefs = mutableMapOf<String, IrTopLevel.Struct>()

    /** Declared parameter types per function (user functions + bridge externs), for call-site coercion. */
    private val funcParamTypes = mutableMapOf<String, List<IrType>>()

    /** LLVM element types for top-level and thread-local variables. */
    private val globalVars = mutableMapOf<String, String>()

    private data class DynamicGlobalInitializer(
        val name: String,
        val type: IrType,
        val initializer: IrExpr,
        val threadLocal: Boolean,
    )
    private val dynamicGlobalInitializers = mutableListOf<DynamicGlobalInitializer>()

    /** Extra named context structs discovered while outlining async blocks. */
    private val lateTypeDefinitions = linkedSetOf<String>()

    /** Outlined async/task helper functions emitted after source functions. */
    private val deferredFunctions = mutableListOf<String>()

    /** Function-local structured task scopes; spawned tasks attach to the innermost scope. */
    private val taskScopeStack = ArrayDeque<String>()

    /** Active zone alloc arenas; returns clean them from inner to outer. */
    private val arenaStack = ArrayDeque<String>()

    private var taskContextCounter = 0

    /** True once the current basic block has a terminator. */
    private var terminated = false

    /** Declared return type of the function currently being emitted (null for `main`/tests). */
    private var currentReturnType: IrType? = null

    /** True while emitting `main` (which is always lowered as `i32 @main`). */
    private var currentIsMain = false

    // Which libc declarations / runtime helpers are referenced.
    private var usesPuts = false
    private var usesPrintf = false
    private var usesAbort = false
    private var usesMalloc = false
    private var usesStrlen = false
    private var usesStrcpy = false
    private var usesStrcat = false
    private var usesStrcmp = false
    private var usesMemcpy = false
    private var usesSnprintf = false
    private var usesStrConcat = false
    private var usesStrRepeat = false
    private var usesArrayGrow = false
    private var usesMapGrow = false
    private var usesIntToStr = false
    private var usesUintToStr = false
    private var usesRealToStr = false
    private var usesCharToStr = false
    private var usesFree = false
    private var usesAllocatorRuntime = false
    private var usesArenaRuntime = false
    private var usesTaskRuntime = false

    /** Tracks the continue/end labels of enclosing loops for `break`/`continue`. */
    private data class LoopTarget(val continueLabel: String, val endLabel: String, val label: String? = null)
    private val loopStack = ArrayDeque<LoopTarget>()

    /** Finds the loop target for a `break`/`continue`: innermost if [label] is null, else the nearest loop tagged with [label]. */
    private fun findLoopTarget(label: String?): LoopTarget? {
        if (label == null) return loopStack.lastOrNull()
        for (i in loopStack.indices.reversed()) {
            if (loopStack[i].label == label) return loopStack[i]
        }
        return null
    }

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
        usesMemcpy = false
        usesSnprintf = false
        usesStrConcat = false
        usesStrRepeat = false
        usesArrayGrow = false
        usesMapGrow = false
        usesIntToStr = false
        usesUintToStr = false
        usesRealToStr = false
        usesCharToStr = false
        usesFree = false
        usesAllocatorRuntime = false
        usesArenaRuntime = false
        usesTaskRuntime = false
        loopStack.clear()
        taskScopeStack.clear()
        arenaStack.clear()
        taskContextCounter = 0

        structDefs.clear()
        funcParamTypes.clear()
        globalVars.clear()
        dynamicGlobalInitializers.clear()
        lateTypeDefinitions.clear()
        deferredFunctions.clear()
        for (item in program.items) {
            when (item) {
                is IrTopLevel.Func -> {
                    funcParamTypes[item.function.name] = item.function.params.map { it.second }
                    if (item.function.isTask && item.function.name != "main") {
                        usesTaskRuntime = true
                    }
                }
                is IrTopLevel.Extern -> funcParamTypes[item.name] = item.params.map { it.second }
                is IrTopLevel.Global -> when (val stmt = item.stmt) {
                    is IrStmt.VarDecl -> globalVars[stmt.name] = mapType(stmt.type)
                    is IrStmt.FinDecl -> globalVars[stmt.name] = mapType(stmt.type)
                    is IrStmt.LetDecl -> globalVars[stmt.name] = mapType(stmt.type)
                    else -> {}
                }
                else -> {}
            }
        }

        val body = StringBuilder()

        // Struct type definitions. Structs are heap-allocated and passed by
        // pointer (`%struct.T*`); construction, member reads and member writes
        // lower to malloc + getelementptr below.
        val structs = program.items.filterIsInstance<IrTopLevel.Struct>()
        if (structs.isNotEmpty()) {
            body.appendLine("; Struct types")
            for (s in structs) {
                structDefs[s.name] = s
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

        val normalGlobalInitializers = dynamicGlobalInitializers.filterNot { it.threadLocal }
        val threadLocalInitializers = dynamicGlobalInitializers.filter { it.threadLocal }
        if (normalGlobalInitializers.isNotEmpty()) {
            out.clear()
            emitGlobalInitializerFunction("__azora_init_globals", normalGlobalInitializers)
            body.append(out)
            body.appendLine()
        }
        if (threadLocalInitializers.isNotEmpty()) {
            out.clear()
            emitGlobalInitializerFunction("__azora_init_threadlocals", threadLocalInitializers)
            body.append(out)
            body.appendLine()
        }

        // Functions
        for (item in program.items.filterIsInstance<IrTopLevel.Func>()) {
            out.clear()
            if (item.function.isTask && item.function.name != "main") {
                emitTaskFunction(item.function)
            } else {
                emitFunction(item.function)
            }
            body.append(out)
            body.appendLine()
        }
        var deferredIndex = 0
        while (deferredIndex < deferredFunctions.size) {
            body.append(deferredFunctions[deferredIndex])
            body.appendLine()
            deferredIndex++
        }

        // Extern (`bridge`) function declarations
        for (item in program.items.filterIsInstance<IrTopLevel.Extern>()) {
            val params = item.params.joinToString(", ") { (_, t) -> mapType(t) }
            body.appendLine("declare ${mapType(item.returnType)} @${item.name}($params)")
        }

        // Tests
        for (item in program.items.filterIsInstance<IrTopLevel.Test>()) {
            out.clear()
            emitTestFunction(item)
            body.append(out)
            body.appendLine()
        }

        // Runtime helpers (appended after the body so string-constant ids are stable).
        if (usesTaskRuntime || usesArenaRuntime) usesAllocatorRuntime = true
        val helpers = buildRuntimeHelpers()

        // String constants
        val strConsts = StringBuilder()
        if (stringConstants.isNotEmpty()) {
            strConsts.appendLine("; String constants")
            for ((name, value) in stringConstants) {
                val escaped = escapeForLlvm(value)
                val len = value.encodeToByteArray().size + 1
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
        if (usesFree) line("declare void @free(i8*)")
        if (usesStrlen) line("declare i64 @strlen(i8*)")
        if (usesStrcpy) line("declare i8* @strcpy(i8*, i8*)")
        if (usesStrcat) line("declare i8* @strcat(i8*, i8*)")
        if (usesStrcmp) line("declare i32 @strcmp(i8*, i8*)")
        if (usesMemcpy) line("declare i8* @memcpy(i8*, i8*, i64)")
        if (usesTaskRuntime) {
            line("declare i32 @pthread_create(i8**, i8*, i8* (i8*)*, i8*)")
            line("declare i32 @pthread_join(i8*, i8**)")
            line("declare i32 @pthread_cancel(i8*)")
        }
        line("")
        if (usesTaskRuntime || lateTypeDefinitions.isNotEmpty()) {
            line("%azora.task = type { i8*, i8*, i1, i1 }")
            line("%azora.scope = type { i64, i64, %azora.task** }")
        }
        if (usesArenaRuntime) {
            line("%azora.arena = type { i64, i64, i8** }")
        }
        for (typeDef in lateTypeDefinitions) line(typeDef)
        if (usesTaskRuntime || usesArenaRuntime || lateTypeDefinitions.isNotEmpty()) line("")
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
                dynamicGlobalInitializers += DynamicGlobalInitializer(
                    name = name,
                    type = type,
                    initializer = initializer,
                    threadLocal = name.startsWith("__tl__"),
                )
                "zeroinitializer"
            }
        }
        val storage = if (name.startsWith("__tl__")) "thread_local global" else "global"
        line("@$name = $storage $llvmType $value")
    }

    private fun emitGlobalInitializerFunction(name: String, initializers: List<DynamicGlobalInitializer>) {
        localVars.clear()
        allocaSlots.clear()
        loopStack.clear()
        taskScopeStack.clear()
        arenaStack.clear()
        terminated = false
        currentBlock = "entry"
        currentReturnType = IrType.Unit
        currentIsMain = false

        line("define void @$name() {")
        line("entry:")
        for ((globalName, type, initializer) in initializers) {
            val raw = emitExpr(initializer)
            val value = coerceNumeric(raw, initializer.type, type)
            val llvmType = mapType(type)
            emit("  store $llvmType $value, $llvmType* @$globalName")
        }
        emitTerminator("  ret void")
        line("}")
    }

    // -----------------------------------------------------------------------
    // Functions
    // -----------------------------------------------------------------------

    private fun emitTestFunction(test: IrTopLevel.Test) {
        localVars.clear()
        loopStack.clear()
        taskScopeStack.clear()
        arenaStack.clear()
        tmpCounter = 0
        labelCounter = 0
        terminated = false
        currentReturnType = null
        currentIsMain = false
        currentBlock = "entry"

        val safeName = sanitizeName(test.name.replace(" ", "_"))
        line("define void @test_$safeName() {")
        line("entry:")
        emitEntryAllocas(test.body)
        emitRootTaskScopeIfNeeded()
        emitStmts(test.body)
        emitFunctionExitCleanup()
        emitTerminator("  ret void")
        line("}")
    }

    /** Emits one entry-block alloca per local declared anywhere in [body] (see [allocaSlots]). */
    private fun emitEntryAllocas(body: List<IrStmt>) {
        allocaSlots.clear()
        allocaCounter = 0
        val slots = LinkedHashSet<Pair<String, String>>()
        collectLocalSlots(body, slots)
        for ((name, type) in slots) {
            val reg = "%loc${allocaCounter++}.${sanitizeName(name)}"
            emit("  $reg = alloca $type")
            allocaSlots[name to type] = reg
        }
    }

    /** Collects every (name, llvm type) local slot declared in [stmts], recursively. */
    private fun collectLocalSlots(stmts: List<IrStmt>, slots: MutableSet<Pair<String, String>>) {
        for (stmt in stmts) {
            when (stmt) {
                is IrStmt.VarDecl -> slots.add(stmt.name to mapType(stmt.type))
                is IrStmt.FinDecl -> slots.add(stmt.name to mapType(stmt.type))
                is IrStmt.LetDecl -> slots.add(stmt.name to mapType(stmt.type))
                is IrStmt.If -> {
                    collectLocalSlots(stmt.thenBranch, slots)
                    stmt.elseBranch?.let { collectLocalSlots(it, slots) }
                }
                is IrStmt.Zone -> collectLocalSlots(stmt.body, slots)
                is IrStmt.While -> collectLocalSlots(stmt.body, slots)
                is IrStmt.For -> {
                    slots.add(stmt.counter to mapType(stmt.start.type))
                    collectLocalSlots(stmt.body, slots)
                }
                is IrStmt.ForEach -> {
                    val elemType = when (val type = stmt.iterable.type) {
                        is IrType.Array -> type.element
                        is IrType.Set -> type.element
                        else -> IrType.Any
                    }
                    slots.add(stmt.elem to mapType(elemType))
                    slots.add(foreachIndexName(stmt.elem) to "i64")
                    collectLocalSlots(stmt.body, slots)
                }
                is IrStmt.Loop -> collectLocalSlots(stmt.body, slots)
                is IrStmt.When -> {
                    stmt.branches.forEach { collectLocalSlots(it.body, slots) }
                    stmt.elseBranch?.let { collectLocalSlots(it, slots) }
                }
                is IrStmt.Try -> {
                    collectLocalSlots(stmt.body, slots)
                    stmt.catchBody?.let { collectLocalSlots(it, slots) }
                }
                else -> {}
            }
        }
    }

    private fun emitFunction(func: IrFunction) {
        localVars.clear()
        loopStack.clear()
        taskScopeStack.clear()
        arenaStack.clear()
        tmpCounter = 0
        labelCounter = 0
        terminated = false
        currentBlock = "entry"

        // The program entry point is always emitted as `i32 @main` returning 0,
        // so the produced executable / `lli` run yields a clean exit code.
        val isMain = func.name == "main"
        currentIsMain = isMain
        currentReturnType = if (isMain) null else func.returnType
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

        // All local allocas live in the entry block (never inside loops).
        emitEntryAllocas(func.body)
        emitRootTaskScopeIfNeeded()

        if (isMain && dynamicGlobalInitializers.any { !it.threadLocal }) {
            emit("  call void @__azora_init_globals()")
        }
        if (isMain && dynamicGlobalInitializers.any { it.threadLocal }) {
            emit("  call void @__azora_init_threadlocals()")
        }

        emitStmts(func.body)

        // Guarantee the final block has a terminator.
        when {
            isMain -> {
                emitFunctionExitCleanup()
                emitTerminator("  ret i32 0")
            }
            func.returnType == IrType.Unit -> {
                emitFunctionExitCleanup()
                emitTerminator("  ret void")
            }
            else -> {
                emitFunctionExitCleanup()
                emitTerminator("  ret ${mapType(func.returnType)} ${defaultValue(func.returnType)}")
            }
        }

        line("}")
    }

    private fun emitTaskFunction(func: IrFunction) {
        usesTaskRuntime = true
        usesAllocatorRuntime = true

        val bodyName = taskBodyName(func.name)
        emitFunction(func.copy(name = bodyName, isTask = false))
        line("")
        emitTaskEntryWrapper(func, bodyName)
        line("")
        emitTaskPublicSpawner(func)
    }

    private fun emitTaskEntryWrapper(func: IrFunction, bodyName: String) {
        localVars.clear()
        loopStack.clear()
        taskScopeStack.clear()
        arenaStack.clear()
        tmpCounter = 0
        labelCounter = 0
        terminated = false
        currentBlock = "entry"
        val entryName = taskEntryName(func.name)
        line("define i8* @$entryName(i8* %ctx.raw) {")
        line("entry:")
        if (dynamicGlobalInitializers.any { it.threadLocal }) {
            emit("  call void @__azora_init_threadlocals()")
        }
        val argRegs = mutableListOf<Pair<String, IrType>>()
        val ctxType = taskContextType(func.name)
        if (func.params.isNotEmpty()) {
            emit("  %ctx = bitcast i8* %ctx.raw to $ctxType*")
            for ((i, param) in func.params.withIndex()) {
                val (_, type) = param
                val llvmType = mapType(type)
                val ptr = "%argptr.$i"
                val value = "%argval.$i"
                emit("  $ptr = getelementptr $ctxType, $ctxType* %ctx, i32 0, i32 $i")
                emit("  $value = load $llvmType, $llvmType* $ptr, align 1")
                argRegs += value to type
            }
            emit("  call void @__azora_free(i8* %ctx.raw)")
        }
        val args = argRegs.joinToString(", ") { (value, type) -> "${mapType(type)} $value" }
        if (func.returnType == IrType.Unit) {
            emit("  call void @$bodyName($args)")
            emitTerminator("  ret i8* null")
        } else {
            val retType = mapType(func.returnType)
            val result = "%task.result"
            emit("  $result = call $retType @$bodyName($args)")
            val raw = emitResultBox(result, func.returnType)
            emitTerminator("  ret i8* $raw")
        }
        line("}")
    }

    private fun emitTaskPublicSpawner(func: IrFunction) {
        localVars.clear()
        loopStack.clear()
        taskScopeStack.clear()
        arenaStack.clear()
        tmpCounter = 0
        labelCounter = 0
        terminated = false
        currentBlock = "entry"
        val params = func.params.joinToString(", ") { (name, type) ->
            "${mapType(type)} %arg.$name"
        }
        line("define %azora.task* @${func.name}($params) {")
        line("entry:")
        val ctxRaw = if (func.params.isEmpty()) {
            "null"
        } else {
            val ctxType = taskContextType(func.name)
            lateTypeDefinitions.add("$ctxType = type { ${func.params.joinToString(", ") { mapType(it.second) }} }")
            val sizeGep = "%ctx.size.gep"
            val size = "%ctx.size"
            val raw = "%ctx.raw"
            val ctx = "%ctx"
            emit("  $sizeGep = getelementptr $ctxType, $ctxType* null, i32 1")
            emit("  $size = ptrtoint $ctxType* $sizeGep to i64")
            emit("  $raw = call i8* @__azora_alloc(i64 $size)")
            emit("  $ctx = bitcast i8* $raw to $ctxType*")
            for ((i, param) in func.params.withIndex()) {
                val (name, type) = param
                val llvmType = mapType(type)
                val ptr = "%ctx.arg.$i"
                emit("  $ptr = getelementptr $ctxType, $ctxType* $ctx, i32 0, i32 $i")
                emit("  store $llvmType %arg.$name, $llvmType* $ptr, align 1")
            }
            raw
        }
        val entry = taskEntryName(func.name)
        emit("  %task = call %azora.task* @__azora_task_spawn(i8* (i8*)* @$entry, i8* $ctxRaw)")
        emitTerminator("  ret %azora.task* %task")
        line("}")
    }

    private fun taskBodyName(name: String): String = "__azora_task_body_${sanitizeName(name)}"
    private fun taskEntryName(name: String): String = "__azora_task_entry_${sanitizeName(name)}"
    private fun taskContextType(name: String): String = "%azora.ctx.${sanitizeName(name)}"

    private fun emitRootTaskScopeIfNeeded() {
        if (!usesTaskRuntime) return
        val scope = nextTmp()
        emit("  $scope = alloca %azora.scope")
        emit("  call void @__azora_scope_init(%azora.scope* $scope)")
        taskScopeStack.addLast(scope)
    }

    private fun emitFunctionExitCleanup() {
        if (terminated) return
        emitAllTaskScopeCleanups()
        emitAllArenaCleanups()
    }

    private fun emitAllTaskScopeCleanups() {
        if (!usesTaskRuntime) return
        for (scope in taskScopeStack.asReversed()) {
            emit("  call void @__azora_scope_join_all(%azora.scope* $scope)")
        }
    }

    private fun emitAllArenaCleanups() {
        if (!usesArenaRuntime) return
        for (arena in arenaStack.asReversed()) {
            emit("  call void @__azora_arena_free_all(%azora.arena* $arena)")
        }
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
                val entry = localVars[stmt.name]
                if (entry != null) {
                    val (alloca, type) = entry
                    val value = emitExpr(stmt.value)
                    emit("  store $type $value, $type* $alloca")
                } else {
                    val type = globalVars[stmt.name]
                        ?: error("Assignment target '${stmt.name}' has no local or global storage")
                    val value = emitExpr(stmt.value)
                    emit("  store $type $value, $type* @${stmt.name}")
                }
            }
            is IrStmt.Return -> {
                if (stmt.value != null) {
                    val declared = currentReturnType
                    val raw = emitExpr(stmt.value)
                    emitFunctionExitCleanup()
                    if (declared != null && declared != IrType.Unit) {
                        val value = coerceNumeric(raw, stmt.value.type, declared)
                        emitTerminator("  ret ${mapType(declared)} $value")
                    } else {
                        emitTerminator("  ret ${mapType(stmt.value.type)} $raw")
                    }
                } else {
                    // `main` is always lowered as `i32 @main`, so a bare
                    // `return` there yields exit code 0.
                    emitFunctionExitCleanup()
                    emitTerminator(if (currentIsMain) "  ret i32 0" else "  ret void")
                }
            }
            is IrStmt.ExprStmt -> emitExpr(stmt.expr)
            is IrStmt.Zone -> emitZone(stmt)
            is IrStmt.If -> emitIf(stmt)
            is IrStmt.Assert -> emitAssert(stmt)
            is IrStmt.Trace -> emitTrace(stmt)
            is IrStmt.While -> emitWhile(stmt)
            is IrStmt.For -> emitFor(stmt)
            is IrStmt.Loop -> emitLoop(stmt)
            is IrStmt.When -> emitWhen(stmt)
            is IrStmt.Break -> {
                val target = findLoopTarget(stmt.label) ?: error("break outside of loop")
                emitTerminator("  br label %${target.endLabel}")
            }
            is IrStmt.Continue -> {
                val target = findLoopTarget(stmt.label) ?: error("continue outside of loop")
                emitTerminator("  br label %${target.continueLabel}")
            }
            is IrStmt.IndexAssign -> emitIndexAssign(stmt)
            is IrStmt.MemberAssign -> emitMemberAssign(stmt)
            is IrStmt.Defer -> emit("  ; defer — not lowered")
            is IrStmt.Yield -> emit("  ; yield — not lowered (interpreter-only)")
            is IrStmt.ForEach -> emitForEach(stmt)
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

    private fun emitZone(stmt: IrStmt.Zone) {
        if (!stmt.alloc) {
            emitStmts(stmt.body)
            return
        }
        usesArenaRuntime = true
        usesAllocatorRuntime = true
        val arena = nextTmp()
        emit("  $arena = alloca %azora.arena")
        emit("  call void @__azora_arena_begin(%azora.arena* $arena)")
        arenaStack.addLast(arena)

        var nestedScope: String? = null
        if (usesTaskRuntime) {
            nestedScope = nextTmp()
            emit("  $nestedScope = alloca %azora.scope")
            emit("  call void @__azora_scope_init(%azora.scope* $nestedScope)")
            taskScopeStack.addLast(nestedScope)
        }

        emitStmts(stmt.body)

        if (!terminated) {
            if (nestedScope != null) emit("  call void @__azora_scope_join_all(%azora.scope* $nestedScope)")
            emit("  call void @__azora_arena_free_all(%azora.arena* $arena)")
        }
        if (nestedScope != null) taskScopeStack.removeLast()
        arenaStack.removeLast()
    }

    private fun emitLocalDecl(name: String, type: IrType, initializer: IrExpr) {
        val t = mapType(type)
        // The slot was hoisted to the entry block (see emitEntryAllocas).
        val alloca = allocaSlots[name to t] ?: run {
            // Fallback for slots the collector didn't see (should not happen).
            val reg = "%loc${allocaCounter++}.${sanitizeName(name)}"
            emit("  $reg = alloca $t")
            allocaSlots[name to t] = reg
            reg
        }
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
        loopStack.addLast(LoopTarget(condLabel, endLabel, stmt.label))
        emitStmts(stmt.body)
        loopStack.removeLast()
        emitTerminator("  br label %$condLabel")

        startBlock(endLabel)
    }

    private fun emitFor(stmt: IrStmt.For) {
        val t = mapType(stmt.start.type)
        val unsigned = isUnsigned(stmt.start.type)
        // The counter slot was hoisted to the entry block (see emitEntryAllocas).
        val counterAlloca = allocaSlots[stmt.counter to t] ?: run {
            val reg = "%loc${allocaCounter++}.${sanitizeName(stmt.counter)}"
            emit("  $reg = alloca $t")
            allocaSlots[stmt.counter to t] = reg
            reg
        }
        val initVal = emitExpr(if (stmt.reverse) stmt.end else stmt.start)
        emit("  store $t $initVal, $t* $counterAlloca")
        localVars[stmt.counter] = counterAlloca to t

        val condLabel = nextLabel("for_cond")
        val bodyLabel = nextLabel("for_body")
        val incLabel = nextLabel("for_inc")
        val endLabel = nextLabel("for_end")

        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val loaded = nextTmp()
        emit("  $loaded = load $t, $t* $counterAlloca")
        // Emit the bound expression first so register numbering stays increasing.
        val (bound, pred) = if (stmt.reverse) {
            emitExpr(stmt.start) to if (unsigned) "uge" else "sge"
        } else {
            val endVal = emitExpr(stmt.end)
            val p = when {
                stmt.inclusive && unsigned -> "ule"
                stmt.inclusive -> "sle"
                unsigned -> "ult"
                else -> "slt"
            }
            endVal to p
        }
        val cmp = nextTmp()
        emit("  $cmp = icmp $pred $t $loaded, $bound")
        emitTerminator("  br i1 $cmp, label %$bodyLabel, label %$endLabel")

        startBlock(bodyLabel)
        loopStack.addLast(LoopTarget(incLabel, endLabel, stmt.label))
        emitStmts(stmt.body)
        loopStack.removeLast()
        emitTerminator("  br label %$incLabel")

        startBlock(incLabel)
        val stepVal = stmt.step?.let { emitExpr(it) } ?: "1"
        val incLoaded = nextTmp()
        emit("  $incLoaded = load $t, $t* $counterAlloca")
        val incd = nextTmp()
        if (stmt.reverse) emit("  $incd = sub $t $incLoaded, $stepVal")
        else emit("  $incd = add $t $incLoaded, $stepVal")
        emit("  store $t $incd, $t* $counterAlloca")
        emitTerminator("  br label %$condLabel")

        startBlock(endLabel)
    }

    private fun emitForEach(stmt: IrStmt.ForEach) {
        val elemType = when (val type = stmt.iterable.type) {
            is IrType.Array -> type.element
            is IrType.Set -> type.element
            else -> null
        } ?: run {
            emitExpr(stmt.iterable)
            emit("  ; for-each over ${stmt.elem} — not lowered for ${stmt.iterable.type}")
            return
        }
        val et = mapType(elemType)

        val elemAlloca = allocaSlots[stmt.elem to et] ?: run {
            val reg = "%loc${allocaCounter++}.${sanitizeName(stmt.elem)}"
            emit("  $reg = alloca $et")
            allocaSlots[stmt.elem to et] = reg
            reg
        }
        val indexName = foreachIndexName(stmt.elem)
        val indexAlloca = allocaSlots[indexName to "i64"] ?: run {
            val reg = "%loc${allocaCounter++}.${sanitizeName(indexName)}"
            emit("  $reg = alloca i64")
            allocaSlots[indexName to "i64"] = reg
            reg
        }

        val raw = emitExpr(stmt.iterable)
        val len = emitArrayLengthI64(raw)
        val dataRaw = nextTmp()
        emit("  $dataRaw = getelementptr i8, i8* $raw, i64 8")
        val data = nextTmp()
        emit("  $data = bitcast i8* $dataRaw to $et*")
        emit("  store i64 0, i64* $indexAlloca")

        val condLabel = nextLabel("foreach_cond")
        val bodyLabel = nextLabel("foreach_body")
        val incLabel = nextLabel("foreach_inc")
        val endLabel = nextLabel("foreach_end")

        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val idx = nextTmp()
        emit("  $idx = load i64, i64* $indexAlloca")
        val cmp = nextTmp()
        emit("  $cmp = icmp ult i64 $idx, $len")
        emitTerminator("  br i1 $cmp, label %$bodyLabel, label %$endLabel")

        startBlock(bodyLabel)
        val ep = nextTmp()
        emit("  $ep = getelementptr $et, $et* $data, i64 $idx")
        val value = nextTmp()
        emit("  $value = load $et, $et* $ep, align 1")
        emit("  store $et $value, $et* $elemAlloca")
        localVars[stmt.elem] = elemAlloca to et
        loopStack.addLast(LoopTarget(incLabel, endLabel))
        emitStmts(stmt.body)
        loopStack.removeLast()
        emitTerminator("  br label %$incLabel")

        startBlock(incLabel)
        val incLoaded = nextTmp()
        emit("  $incLoaded = load i64, i64* $indexAlloca")
        val next = nextTmp()
        emit("  $next = add i64 $incLoaded, 1")
        emit("  store i64 $next, i64* $indexAlloca")
        emitTerminator("  br label %$condLabel")

        startBlock(endLabel)
    }

    private fun emitLoop(stmt: IrStmt.Loop) {
        val bodyLabel = nextLabel("loop_body")
        val endLabel = nextLabel("loop_end")

        emitTerminator("  br label %$bodyLabel")
        startBlock(bodyLabel)
        loopStack.addLast(LoopTarget(bodyLabel, endLabel, stmt.label))
        emitStmts(stmt.body)
        loopStack.removeLast()
        emitTerminator("  br label %$bodyLabel")

        startBlock(endLabel)
    }

    /** Emits an equality test between a `when` scrutinee and one pattern value. */
    private fun emitWhenEq(scrutIrType: IrType, scrutType: String, scrut: String, pv: String): String {
        val cmp = nextTmp()
        when {
            // Strings compare by content, not pointer identity.
            scrutIrType == IrType.String -> {
                usesStrcmp = true
                emit("  $cmp = call i32 @strcmp(i8* $scrut, i8* $pv)")
                val eq = nextTmp()
                emit("  $eq = icmp eq i32 $cmp, 0")
                return eq
            }
            // icmp is invalid on floating-point operands.
            scrutIrType in IrType.floatTypes -> emit("  $cmp = fcmp oeq $scrutType $scrut, $pv")
            else -> emit("  $cmp = icmp eq $scrutType $scrut, $pv")
        }
        return cmp
    }

    private fun emitWhen(stmt: IrStmt.When) {
        val scrutIrType = stmt.scrutinee.type
        val scrutType = mapType(scrutIrType)
        val scrut = emitExpr(stmt.scrutinee)
        val endLabel = nextLabel("when_end")

        for (branch in stmt.branches) {
            val bodyLabel = nextLabel("when_body")
            // Test each pattern; any match jumps to the body, otherwise fall
            // through to the next pattern test (and finally the next branch).
            for ((i, pattern) in branch.patterns.withIndex()) {
                val pv = emitExpr(pattern)
                val cmp = emitWhenEq(scrutIrType, scrutType, scrut, pv)
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
        val msg = stringify(stmt.message)
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
            // The null literal is lowered to `Var("__null", Any)`; emit LLVM's null pointer.
            if (expr.name == "__null") return "null"
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
        is IrExpr.ArrayLiteral -> emitArrayLiteral(expr)
        is IrExpr.SetLit -> emitSetLiteral(expr)
        is IrExpr.MapLit -> emitMapLiteral(expr)
        is IrExpr.Index -> emitIndexRead(expr)
        is IrExpr.Member -> emitMemberRead(expr)
        is IrExpr.MethodCall -> emitMethodCall(expr)
        is IrExpr.StructCtor -> emitStructCtor(expr)
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
        is IrExpr.NumCast -> coerceNumeric(emitExpr(expr.value), expr.value.type, expr.type)
        is IrExpr.IfExpr -> emitIfExpr(expr)
        is IrExpr.SlotPattern -> "0"
        is IrExpr.Await -> emitAwait(expr)
        is IrExpr.Spread -> {
            emitExpr(expr.array)
            emit("  ; spread — interpreter-only")
            "null"
        }
        is IrExpr.Lambda -> {
            emit("  ; lambda/closure — not lowered")
            "null"
        }
    }

    private data class Capture(val name: String, val type: IrType, val llvmType: String)

    private fun emitAwait(expr: IrExpr.Await): String {
        usesTaskRuntime = true
        val handle = if (expr.value is IrExpr.Lambda) {
            emitLambdaTaskSpawn(expr.value, expr.type, "legacy_task")
        } else {
            emitExpr(expr.value)
        }
        return emitTaskJoin(handle, expr.type)
    }

    private fun emitTaskJoin(handle: String, resultType: IrType): String {
        usesTaskRuntime = true
        if (resultType == IrType.Unit) {
            val ignored = nextTmp()
            emit("  $ignored = call i8* @__azora_task_join(%azora.task* $handle)")
            return "void"
        }
        val raw = nextTmp()
        emit("  $raw = call i8* @__azora_task_join(%azora.task* $handle)")
        val ptrType = "${mapType(resultType)}*"
        val typed = nextTmp()
        emit("  $typed = bitcast i8* $raw to $ptrType")
        val value = nextTmp()
        emit("  $value = load ${mapType(resultType)}, $ptrType $typed, align 1")
        return value
    }

    private fun emitResultBox(value: String, type: IrType): String {
        usesAllocatorRuntime = true
        val raw = nextTmp()
        emit("  $raw = call i8* @__azora_alloc(i64 ${sizeOfScalar(type)})")
        val ptrType = "${mapType(type)}*"
        val typed = nextTmp()
        emit("  $typed = bitcast i8* $raw to $ptrType")
        emit("  store ${mapType(type)} $value, $ptrType $typed, align 1")
        return raw
    }

    private fun emitTaskScopeAttach(handle: String) {
        val scope = taskScopeStack.lastOrNull() ?: return
        usesTaskRuntime = true
        emit("  call void @__azora_scope_attach(%azora.scope* $scope, %azora.task* $handle)")
    }

    private fun emitHeapAlloc(size: String): String {
        usesAllocatorRuntime = true
        val arena = arenaStack.lastOrNull()
        val raw = nextTmp()
        if (arena != null) {
            usesArenaRuntime = true
            emit("  $raw = call i8* @__azora_arena_alloc(%azora.arena* $arena, i64 $size)")
        } else {
            emit("  $raw = call i8* @__azora_alloc(i64 $size)")
        }
        return raw
    }

    private fun emitLambdaTaskSpawn(lambda: IrExpr.Lambda, resultType: IrType, prefix: String): String {
        usesTaskRuntime = true
        usesAllocatorRuntime = true
        val id = taskContextCounter++
        val safePrefix = sanitizeName(prefix)
        val bodyName = "__azora_${safePrefix}_body_$id"
        val entryName = "__azora_${safePrefix}_entry_$id"
        val ctxType = "%azora.ctx.${safePrefix}.$id"
        val captures = collectCaptures(lambda)
        if (captures.isNotEmpty()) {
            lateTypeDefinitions.add("$ctxType = type { ${captures.joinToString(", ") { it.llvmType }} }")
        }

        val ctxRaw = if (captures.isEmpty()) {
            "null"
        } else {
            val sizeGep = nextTmp()
            val size = nextTmp()
            emit("  $sizeGep = getelementptr $ctxType, $ctxType* null, i32 1")
            emit("  $size = ptrtoint $ctxType* $sizeGep to i64")
            val raw = nextTmp()
            emit("  $raw = call i8* @__azora_alloc(i64 $size)")
            val ctx = nextTmp()
            emit("  $ctx = bitcast i8* $raw to $ctxType*")
            for ((i, capture) in captures.withIndex()) {
                val storage = localVars[capture.name] ?: continue
                val field = nextTmp()
                val value = nextTmp()
                emit("  $field = getelementptr $ctxType, $ctxType* $ctx, i32 0, i32 $i")
                emit("  $value = load ${storage.second}, ${storage.second}* ${storage.first}")
                emit("  store ${capture.llvmType} $value, ${capture.llvmType}* $field, align 1")
            }
            raw
        }

        deferredFunctions += renderDeferredFunction {
            emitFunction(IrFunction(bodyName, captures.map { it.name to it.type }, resultType, lambda.body))
        }
        deferredFunctions += renderDeferredFunction {
            emitLambdaTaskEntry(entryName, bodyName, ctxType, captures, resultType)
        }

        val handle = nextTmp()
        emit("  $handle = call %azora.task* @__azora_task_spawn(i8* (i8*)* @$entryName, i8* $ctxRaw)")
        emitTaskScopeAttach(handle)
        return handle
    }

    private fun emitLambdaTaskEntry(
        entryName: String,
        bodyName: String,
        ctxType: String,
        captures: List<Capture>,
        resultType: IrType,
    ) {
        localVars.clear()
        loopStack.clear()
        taskScopeStack.clear()
        arenaStack.clear()
        tmpCounter = 0
        labelCounter = 0
        terminated = false
        currentBlock = "entry"
        line("define i8* @$entryName(i8* %ctx.raw) {")
        line("entry:")
        if (dynamicGlobalInitializers.any { it.threadLocal }) {
            emit("  call void @__azora_init_threadlocals()")
        }
        val captureValues = mutableListOf<Pair<String, IrType>>()
        if (captures.isNotEmpty()) {
            emit("  %ctx = bitcast i8* %ctx.raw to $ctxType*")
            for ((i, capture) in captures.withIndex()) {
                val ptr = "%capture.ptr.$i"
                val value = "%capture.val.$i"
                emit("  $ptr = getelementptr $ctxType, $ctxType* %ctx, i32 0, i32 $i")
                emit("  $value = load ${capture.llvmType}, ${capture.llvmType}* $ptr, align 1")
                captureValues += value to capture.type
            }
            emit("  call void @__azora_free(i8* %ctx.raw)")
        }
        val args = captureValues.joinToString(", ") { (value, type) -> "${mapType(type)} $value" }
        if (resultType == IrType.Unit) {
            emit("  call void @$bodyName($args)")
            emitTerminator("  ret i8* null")
        } else {
            val result = "%task.result"
            emit("  $result = call ${mapType(resultType)} @$bodyName($args)")
            val boxed = emitResultBox(result, resultType)
            emitTerminator("  ret i8* $boxed")
        }
        line("}")
    }

    private fun renderDeferredFunction(block: () -> Unit): String {
        val savedOut = out
        val savedLocalVars = localVars.toMap()
        val savedAllocaSlots = allocaSlots.toMap()
        val savedLoopStack = ArrayDeque(loopStack)
        val savedTaskScopes = ArrayDeque(taskScopeStack)
        val savedArenas = ArrayDeque(arenaStack)
        val savedTmp = tmpCounter
        val savedLabel = labelCounter
        val savedAlloca = allocaCounter
        val savedTerminated = terminated
        val savedBlock = currentBlock
        val savedReturnType = currentReturnType
        val savedIsMain = currentIsMain

        out = StringBuilder()
        try {
            block()
            return out.toString()
        } finally {
            out = savedOut
            localVars.clear(); localVars.putAll(savedLocalVars)
            allocaSlots.clear(); allocaSlots.putAll(savedAllocaSlots)
            loopStack.clear(); loopStack.addAll(savedLoopStack)
            taskScopeStack.clear(); taskScopeStack.addAll(savedTaskScopes)
            arenaStack.clear(); arenaStack.addAll(savedArenas)
            tmpCounter = savedTmp
            labelCounter = savedLabel
            allocaCounter = savedAlloca
            terminated = savedTerminated
            currentBlock = savedBlock
            currentReturnType = savedReturnType
            currentIsMain = savedIsMain
        }
    }

    private fun collectCaptures(lambda: IrExpr.Lambda): List<Capture> {
        val declared = linkedSetOf<String>()
        val refs = linkedMapOf<String, IrType>()
        lambda.params.forEach { declared.add(it.first) }
        collectDeclaredNames(lambda.body, declared)
        collectReferencedVars(lambda.body, refs)
        return refs
            .filterKeys { it !in declared && it in localVars }
            .map { (name, type) ->
                val llvmType = localVars[name]?.second ?: mapType(type)
                Capture(name, type, llvmType)
            }
    }

    private fun collectDeclaredNames(stmts: List<IrStmt>, names: MutableSet<String>) {
        for (stmt in stmts) {
            when (stmt) {
                is IrStmt.VarDecl -> names.add(stmt.name)
                is IrStmt.FinDecl -> names.add(stmt.name)
                is IrStmt.LetDecl -> names.add(stmt.name)
                is IrStmt.For -> {
                    names.add(stmt.counter)
                    collectDeclaredNames(stmt.body, names)
                }
                is IrStmt.ForEach -> {
                    names.add(stmt.elem)
                    collectDeclaredNames(stmt.body, names)
                }
                is IrStmt.If -> {
                    collectDeclaredNames(stmt.thenBranch, names)
                    stmt.elseBranch?.let { collectDeclaredNames(it, names) }
                }
                is IrStmt.Zone -> collectDeclaredNames(stmt.body, names)
                is IrStmt.While -> collectDeclaredNames(stmt.body, names)
                is IrStmt.Loop -> collectDeclaredNames(stmt.body, names)
                is IrStmt.When -> {
                    stmt.branches.forEach { collectDeclaredNames(it.body, names) }
                    stmt.elseBranch?.let { collectDeclaredNames(it, names) }
                }
                is IrStmt.Try -> {
                    collectDeclaredNames(stmt.body, names)
                    stmt.catchName?.let { names.add(it) }
                    stmt.catchBody?.let { collectDeclaredNames(it, names) }
                }
                is IrStmt.Defer -> collectDeclaredNames(stmt.body, names)
                else -> {}
            }
        }
    }

    private fun collectReferencedVars(stmts: List<IrStmt>, refs: MutableMap<String, IrType>) {
        for (stmt in stmts) {
            when (stmt) {
                is IrStmt.VarDecl -> collectReferencedVars(stmt.initializer, refs)
                is IrStmt.FinDecl -> collectReferencedVars(stmt.initializer, refs)
                is IrStmt.LetDecl -> collectReferencedVars(stmt.initializer, refs)
                is IrStmt.Assignment -> collectReferencedVars(stmt.value, refs)
                is IrStmt.IndexAssign -> {
                    collectReferencedVars(stmt.target, refs)
                    collectReferencedVars(stmt.index, refs)
                    collectReferencedVars(stmt.value, refs)
                }
                is IrStmt.MemberAssign -> {
                    collectReferencedVars(stmt.target, refs)
                    collectReferencedVars(stmt.value, refs)
                }
                is IrStmt.Return -> stmt.value?.let { collectReferencedVars(it, refs) }
                is IrStmt.ExprStmt -> collectReferencedVars(stmt.expr, refs)
                is IrStmt.If -> {
                    collectReferencedVars(stmt.condition, refs)
                    collectReferencedVars(stmt.thenBranch, refs)
                    stmt.elseBranch?.let { collectReferencedVars(it, refs) }
                }
                is IrStmt.Zone -> collectReferencedVars(stmt.body, refs)
                is IrStmt.Assert -> {
                    collectReferencedVars(stmt.condition, refs)
                    collectReferencedVars(stmt.message, refs)
                }
                is IrStmt.Trace -> collectReferencedVars(stmt.message, refs)
                is IrStmt.While -> {
                    collectReferencedVars(stmt.condition, refs)
                    collectReferencedVars(stmt.body, refs)
                }
                is IrStmt.For -> {
                    collectReferencedVars(stmt.start, refs)
                    collectReferencedVars(stmt.end, refs)
                    stmt.step?.let { collectReferencedVars(it, refs) }
                    collectReferencedVars(stmt.body, refs)
                }
                is IrStmt.ForEach -> {
                    collectReferencedVars(stmt.iterable, refs)
                    collectReferencedVars(stmt.body, refs)
                }
                is IrStmt.Loop -> collectReferencedVars(stmt.body, refs)
                is IrStmt.When -> {
                    collectReferencedVars(stmt.scrutinee, refs)
                    stmt.branches.forEach { branch ->
                        branch.patterns.forEach { collectReferencedVars(it, refs) }
                        collectReferencedVars(branch.body, refs)
                    }
                    stmt.elseBranch?.let { collectReferencedVars(it, refs) }
                }
                is IrStmt.Throw -> collectReferencedVars(stmt.value, refs)
                is IrStmt.Try -> {
                    collectReferencedVars(stmt.body, refs)
                    stmt.catchBody?.let { collectReferencedVars(it, refs) }
                }
                is IrStmt.Defer -> collectReferencedVars(stmt.body, refs)
                is IrStmt.Yield -> collectReferencedVars(stmt.value, refs)
                is IrStmt.Break, is IrStmt.Continue -> {}
            }
        }
    }

    private fun collectReferencedVars(expr: IrExpr, refs: MutableMap<String, IrType>) {
        when (expr) {
            is IrExpr.Var -> refs.putIfAbsent(expr.name, expr.type)
            is IrExpr.Unary -> collectReferencedVars(expr.operand, refs)
            is IrExpr.Binary -> {
                collectReferencedVars(expr.left, refs)
                collectReferencedVars(expr.right, refs)
            }
            is IrExpr.Call -> expr.args.forEach { collectReferencedVars(it, refs) }
            is IrExpr.ArrayLiteral -> expr.elements.forEach { collectReferencedVars(it, refs) }
            is IrExpr.MapLit -> expr.entries.forEach {
                collectReferencedVars(it.first, refs)
                collectReferencedVars(it.second, refs)
            }
            is IrExpr.SetLit -> expr.elements.forEach { collectReferencedVars(it, refs) }
            is IrExpr.Index -> {
                collectReferencedVars(expr.target, refs)
                collectReferencedVars(expr.index, refs)
            }
            is IrExpr.Member -> collectReferencedVars(expr.target, refs)
            is IrExpr.MethodCall -> {
                collectReferencedVars(expr.target, refs)
                expr.args.forEach { collectReferencedVars(it, refs) }
            }
            is IrExpr.StructCtor -> expr.args.forEach { collectReferencedVars(it, refs) }
            is IrExpr.StringTemplate -> expr.parts.forEach { part ->
                if (part is IrExpr.IrTemplatePart.Expr) collectReferencedVars(part.expr, refs)
            }
            is IrExpr.TupleLit -> expr.elements.forEach { collectReferencedVars(it, refs) }
            is IrExpr.TupleAccess -> collectReferencedVars(expr.target, refs)
            is IrExpr.CatchExpr -> {
                collectReferencedVars(expr.expr, refs)
                collectReferencedVars(expr.fallback, refs)
            }
            is IrExpr.IfExpr -> {
                collectReferencedVars(expr.condition, refs)
                collectReferencedVars(expr.thenExpr, refs)
                collectReferencedVars(expr.elseExpr, refs)
            }
            is IrExpr.NumCast -> collectReferencedVars(expr.value, refs)
            is IrExpr.Lambda -> {}
            is IrExpr.Await -> collectReferencedVars(expr.value, refs)
            is IrExpr.Spread -> collectReferencedVars(expr.array, refs)
            is IrExpr.IntLiteral, is IrExpr.RealLiteral, is IrExpr.StringLiteral,
            is IrExpr.BoolLiteral, is IrExpr.CharLiteral, is IrExpr.SlotPattern -> {}
        }
    }

    // -----------------------------------------------------------------------
    // Aggregate lowering (structs & arrays)
    //
    // Structs are heap-allocated with malloc and passed around as `%struct.T*`.
    // Arrays are heap buffers with an i64 length header followed by the packed
    // elements: [ i64 len | elem0 | elem1 | … ], carried as `i8*`.
    // -----------------------------------------------------------------------

    /** The byte size of a scalar/pointer LLVM value of IR type [type]. */
    private fun sizeOfScalar(type: IrType): Int = when (mapType(type)) {
        "i1", "i8" -> 1
        "i16" -> 2
        "i32", "float" -> 4
        "i128", "fp128" -> 16
        else -> 8 // i64, double, and all pointers
    }

    /**
     * Coerces a numeric [value] of IR type [from] to IR type [to] (for stores
     * into struct fields / array elements whose declared type is wider or
     * floating-point, e.g. `Vec3(1, 2, 3)` with `Real` fields).
     */
    private fun coerceNumeric(value: String, from: IrType, to: IrType): String {
        if (from == to) return value
        val ft = mapType(from)
        val tt = mapType(to)
        if (ft == tt) return value
        val fromInt = from in IrType.integerTypes || from == IrType.Char
        val toInt = to in IrType.integerTypes || to == IrType.Char
        val fromFloat = from in IrType.floatTypes
        val toFloat = to in IrType.floatTypes
        val fromPtr = ft.endsWith("*")
        val toPtr = tt.endsWith("*")

        // Pointer ↔ integer (FFI): `window as Long`, `az_sym(...) as String`, …
        if (fromPtr && toInt) {
            val t = nextTmp(); emit("  $t = ptrtoint $ft $value to $tt"); return t
        }
        if (fromInt && toPtr) {
            val t = nextTmp(); emit("  $t = inttoptr $ft $value to $tt"); return t
        }
        if (fromPtr && toPtr) {
            val t = nextTmp(); emit("  $t = bitcast $ft $value to $tt"); return t
        }
        val inst = when {
            fromInt && toFloat -> if (isUnsigned(from)) "uitofp" else "sitofp"
            fromFloat && toInt -> if (isUnsigned(to)) "fptoui" else "fptosi"
            fromInt && toInt -> {
                val fw = sizeOfScalar(from); val tw = sizeOfScalar(to)
                when {
                    fw < tw -> if (isUnsigned(from)) "zext" else "sext"
                    fw > tw -> "trunc"
                    else -> return value
                }
            }
            fromFloat && toFloat -> {
                val fw = sizeOfScalar(from); val tw = sizeOfScalar(to)
                when {
                    fw < tw -> "fpext"
                    fw > tw -> "fptrunc"
                    else -> return value
                }
            }
            else -> return value // non-numeric — leave as-is
        }
        val tmp = nextTmp()
        emit("  $tmp = $inst $ft $value to $tt")
        return tmp
    }

    private fun foreachIndexName(elem: String): String = "__foreach_idx_$elem"

    /** Widens an index value to i64 for getelementptr. */
    private fun indexToI64(value: String, type: IrType): String = when (mapType(type)) {
        "i64" -> value
        else -> {
            val t = nextTmp()
            val inst = if (isUnsigned(type) || type == IrType.Char) "zext" else "sext"
            emit("  $t = $inst ${mapType(type)} $value to i64")
            t
        }
    }

    /** `Name(args)` → malloc + field stores; the value is the typed pointer. */
    private fun emitStructCtor(expr: IrExpr.StructCtor): String {
        val def = structDefs[expr.name] ?: run {
            emit("  ; struct ${expr.name} has no definition — emitting null")
            return "null"
        }
        val st = "%struct.${sanitizeName(expr.name)}"

        // Evaluate constructor arguments first (source order).
        val argVals = expr.args.map { emitExpr(it) to it.type }

        // sizeof via the getelementptr-on-null idiom (target independent).
        val sizeGep = nextTmp()
        emit("  $sizeGep = getelementptr $st, $st* null, i32 1")
        val size = nextTmp()
        emit("  $size = ptrtoint $st* $sizeGep to i64")
        val raw = emitHeapAlloc(size)
        val ptr = nextTmp()
        emit("  $ptr = bitcast i8* $raw to $st*")

        for ((i, fieldName) in expr.fieldNames.withIndex()) {
            if (i >= argVals.size) break
            val fi = def.fields.indexOfFirst { it.name == fieldName }
            if (fi < 0) continue // metadata field (e.g. node __type/__chain) not in the layout
            val field = def.fields[fi]
            val (rawVal, argType) = argVals[i]
            val value = coerceNumeric(rawVal, argType, field.type)
            val ft = mapType(field.type)
            val fp = nextTmp()
            emit("  $fp = getelementptr $st, $st* $ptr, i32 0, i32 $fi")
            emit("  store $ft $value, $ft* $fp")
        }
        return ptr
    }

    /** Emits a pointer to field [name] of struct value [expr] (or null if unknown). */
    private fun emitFieldPtr(target: IrExpr, name: String): Triple<String, IrType, String>? {
        val tt = target.type as? IrType.Named ?: return null
        val def = structDefs[tt.name] ?: return null
        val fi = def.fields.indexOfFirst { it.name == name }
        if (fi < 0) return null
        val st = "%struct.${sanitizeName(tt.name)}"
        val ptr = emitExpr(target)
        val fp = nextTmp()
        emit("  $fp = getelementptr $st, $st* $ptr, i32 0, i32 $fi")
        return Triple(fp, def.fields[fi].type, mapType(def.fields[fi].type))
    }

    private fun emitMemberRead(expr: IrExpr.Member): String {
        val targetType = expr.target.type
        // Array/string length.
        if (expr.name == "length" && (targetType is IrType.Array || targetType is IrType.Map || targetType is IrType.Set)) {
            val raw = emitExpr(expr.target)
            val len = emitArrayLengthI64(raw)
            val t = nextTmp()
            emit("  $t = trunc i64 $len to i32")
            return t
        }
        if ((expr.name == "isEmpty" || expr.name == "isNotEmpty") && (targetType is IrType.Array || targetType is IrType.Map || targetType is IrType.Set)) {
            return emitArrayEmptyCheck(expr.target, notEmpty = expr.name == "isNotEmpty")
        }
        if (expr.name == "length" && targetType == IrType.String) {
            usesStrlen = true
            val s = emitExpr(expr.target)
            val len = nextTmp()
            emit("  $len = call i64 @strlen(i8* $s)")
            val t = nextTmp()
            emit("  $t = trunc i64 $len to i32")
            return t
        }
        // Struct field read.
        val fieldPtr = emitFieldPtr(expr.target, expr.name)
        if (fieldPtr != null) {
            val (fp, _, ft) = fieldPtr
            val tmp = nextTmp()
            emit("  $tmp = load $ft, $ft* $fp")
            return tmp
        }
        emitExpr(expr.target)
        emit("  ; member .${expr.name} on ${expr.target.type} — not lowered")
        return defaultValue(expr.type)
    }

    private fun emitMemberAssign(stmt: IrStmt.MemberAssign) {
        val fieldPtr = emitFieldPtr(stmt.target, stmt.name)
        if (fieldPtr != null) {
            val (fp, fieldType, ft) = fieldPtr
            val raw = emitExpr(stmt.value)
            val value = coerceNumeric(raw, stmt.value.type, fieldType)
            emit("  store $ft $value, $ft* $fp")
            return
        }
        emitExpr(stmt.target)
        emitExpr(stmt.value)
        emit("  ; member assign .${stmt.name} on ${stmt.target.type} — not lowered")
    }

    private fun emitMethodCall(expr: IrExpr.MethodCall): String {
        val arrayType = expr.target.type as? IrType.Array
        if (arrayType != null) {
            when (expr.name) {
                "add" -> {
                    if (expr.args.size == 1) {
                        emitArrayAdd(expr.target, expr.args[0], arrayType.element)
                        return "void"
                    }
                }
                "isEmpty" -> return emitArrayEmptyCheck(expr.target, notEmpty = false)
                "isNotEmpty" -> return emitArrayEmptyCheck(expr.target, notEmpty = true)
                "contains" -> {
                    if (expr.args.size == 1) return emitArrayContains(expr.target, expr.args[0], arrayType.element)
                }
            }
        }

        val setType = expr.target.type as? IrType.Set
        if (setType != null) {
            when (expr.name) {
                "add" -> if (expr.args.size == 1) return emitSetAdd(expr.target, expr.args[0], setType.element)
                "contains" -> if (expr.args.size == 1) return emitArrayContains(expr.target, expr.args[0], setType.element)
                "remove" -> if (expr.args.size == 1) return emitSetRemove(expr.target, expr.args[0], setType.element)
                "clear" -> {
                    val raw = emitExpr(expr.target)
                    val lenPtr = nextTmp()
                    emit("  $lenPtr = bitcast i8* $raw to i64*")
                    emit("  store i64 0, i64* $lenPtr")
                    return "void"
                }
                "isEmpty" -> return emitArrayEmptyCheck(expr.target, notEmpty = false)
                "isNotEmpty" -> return emitArrayEmptyCheck(expr.target, notEmpty = true)
            }
        }

        val map = expr.target.type as? IrType.Map
        if (map != null) {
            when (expr.name) {
                "get" -> if (expr.args.size == 1) {
                    return emitMapIndexRead(IrExpr.Index(expr.target, expr.args[0], map.value), map)
                }
                "put" -> if (expr.args.size == 2) {
                    emitMapIndexAssign(IrStmt.IndexAssign(expr.target, expr.args[0], expr.args[1]), map)
                    return "void"
                }
                "containsKey" -> if (expr.args.size == 1) {
                    return emitArrayContains(expr.target, expr.args[0], map.key)
                }
                "clear" -> {
                    val raw = emitExpr(expr.target)
                    val lenPtr = nextTmp()
                    emit("  $lenPtr = bitcast i8* $raw to i64*")
                    emit("  store i64 0, i64* $lenPtr")
                    return "void"
                }
                "isEmpty" -> return emitArrayEmptyCheck(expr.target, notEmpty = false)
                "isNotEmpty" -> return emitArrayEmptyCheck(expr.target, notEmpty = true)
            }
        }

        emitExpr(expr.target)
        for (a in expr.args) emitExpr(a)
        emit("  ; method .${expr.name} — not lowered")
        return defaultValue(expr.type)
    }

    /** `[a, b, c]` → malloc(8 + n*elemSize), i64 length header, packed elements. */
    private fun emitArrayLiteral(expr: IrExpr.ArrayLiteral): String {
        val elemType = (expr.type as? IrType.Array)?.element ?: IrType.Any
        val et = mapType(elemType)
        val elemSize = sizeOfScalar(elemType)
        val total = 8 + expr.elements.size * elemSize

        val vals = expr.elements.map { emitExpr(it) to it.type }

        val raw = emitHeapAlloc("$total")
        val lenPtr = nextTmp()
        emit("  $lenPtr = bitcast i8* $raw to i64*")
        emit("  store i64 ${expr.elements.size}, i64* $lenPtr")
        if (vals.isNotEmpty()) {
            val dataRaw = nextTmp()
            emit("  $dataRaw = getelementptr i8, i8* $raw, i64 8")
            val data = nextTmp()
            emit("  $data = bitcast i8* $dataRaw to $et*")
            for ((i, pair) in vals.withIndex()) {
                val (rawVal, argType) = pair
                val value = coerceNumeric(rawVal, argType, elemType)
                val ep = nextTmp()
                emit("  $ep = getelementptr $et, $et* $data, i64 $i")
                emit("  store $et $value, $et* $ep, align 1")
            }
        }
        return raw
    }

    private fun emitSetLiteral(expr: IrExpr.SetLit): String {
        val setType = expr.type as? IrType.Set ?: return "null"
        var raw = emitHeapAlloc("8")
        val lenPtr = nextTmp()
        emit("  $lenPtr = bitcast i8* $raw to i64*")
        emit("  store i64 0, i64* $lenPtr")
        for (element in expr.elements) {
            val valueRaw = emitExpr(element)
            val value = coerceNumeric(valueRaw, element.type, setType.element)
            raw = emitSetInsertRaw(raw, value, setType.element).first
        }
        return raw
    }

    private fun emitSetAdd(target: IrExpr, valueExpr: IrExpr, elementType: IrType): String {
        val raw = emitExpr(target)
        val valueRaw = emitExpr(valueExpr)
        val value = coerceNumeric(valueRaw, valueExpr.type, elementType)
        val (updated, added) = emitSetInsertRaw(raw, value, elementType)
        variableStorage(target)?.let { (address, type) ->
            emit("  store $type $updated, $type* $address")
        } ?: emit("  ; set add receiver is not assignable — grown buffer not stored")
        return added
    }

    private fun emitSetInsertRaw(raw: String, value: String, elementType: IrType): Pair<String, String> {
        usesArrayGrow = true
        val et = mapType(elementType)
        val length = emitArrayLengthI64(raw)
        val dataRaw = nextTmp()
        emit("  $dataRaw = getelementptr i8, i8* $raw, i64 8")
        val data = nextTmp()
        emit("  $data = bitcast i8* $dataRaw to $et*")

        val condLabel = nextLabel("set_add_cond")
        val cmpLabel = nextLabel("set_add_cmp")
        val nextLabel = nextLabel("set_add_next")
        val foundLabel = nextLabel("set_add_found")
        val insertLabel = nextLabel("set_add_insert")
        val endLabel = nextLabel("set_add_end")
        val nextIndex = "%set_add_next_${labelCounter++}"
        val grownValue = "%set_add_grown_${labelCounter++}"
        val preheader = currentBlock

        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val idx = nextTmp()
        emit("  $idx = phi i64 [ 0, %$preheader ], [ $nextIndex, %$nextLabel ]")
        val inRange = nextTmp()
        emit("  $inRange = icmp ult i64 $idx, $length")
        emitTerminator("  br i1 $inRange, label %$cmpLabel, label %$insertLabel")

        startBlock(cmpLabel)
        val ep = nextTmp()
        emit("  $ep = getelementptr $et, $et* $data, i64 $idx")
        val candidate = nextTmp()
        emit("  $candidate = load $et, $et* $ep, align 1")
        val equal = emitArrayElementEq(elementType, et, candidate, value)
        emitTerminator("  br i1 $equal, label %$foundLabel, label %$nextLabel")

        startBlock(nextLabel)
        emit("  $nextIndex = add i64 $idx, 1")
        emitTerminator("  br label %$condLabel")

        startBlock(foundLabel)
        emitTerminator("  br label %$endLabel")

        startBlock(insertLabel)
        emit("  $grownValue = call i8* @__azora_array_grow(i8* $raw, i64 ${sizeOfScalar(elementType)})")
        val grownDataRaw = nextTmp()
        emit("  $grownDataRaw = getelementptr i8, i8* $grownValue, i64 8")
        val grownData = nextTmp()
        emit("  $grownData = bitcast i8* $grownDataRaw to $et*")
        val addedPtr = nextTmp()
        emit("  $addedPtr = getelementptr $et, $et* $grownData, i64 $length")
        emit("  store $et $value, $et* $addedPtr, align 1")
        emitTerminator("  br label %$endLabel")

        startBlock(endLabel)
        val updated = nextTmp()
        emit("  $updated = phi i8* [ $raw, %$foundLabel ], [ $grownValue, %$insertLabel ]")
        val added = nextTmp()
        emit("  $added = phi i1 [ false, %$foundLabel ], [ true, %$insertLabel ]")
        return updated to added
    }

    private fun emitSetRemove(target: IrExpr, valueExpr: IrExpr, elementType: IrType): String {
        val et = mapType(elementType)
        val raw = emitExpr(target)
        val valueRaw = emitExpr(valueExpr)
        val value = coerceNumeric(valueRaw, valueExpr.type, elementType)
        val lenPtr = nextTmp()
        emit("  $lenPtr = bitcast i8* $raw to i64*")
        val length = nextTmp()
        emit("  $length = load i64, i64* $lenPtr")
        val dataRaw = nextTmp()
        emit("  $dataRaw = getelementptr i8, i8* $raw, i64 8")
        val data = nextTmp()
        emit("  $data = bitcast i8* $dataRaw to $et*")

        val condLabel = nextLabel("set_remove_cond")
        val cmpLabel = nextLabel("set_remove_cmp")
        val nextLabel = nextLabel("set_remove_next")
        val foundLabel = nextLabel("set_remove_found")
        val shiftCondLabel = nextLabel("set_remove_shift_cond")
        val shiftBodyLabel = nextLabel("set_remove_shift_body")
        val shrinkLabel = nextLabel("set_remove_shrink")
        val missLabel = nextLabel("set_remove_miss")
        val endLabel = nextLabel("set_remove_end")
        val nextIndex = "%set_remove_next_${labelCounter++}"
        val shiftedIndex = "%set_remove_shift_${labelCounter++}"
        val preheader = currentBlock

        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val idx = nextTmp()
        emit("  $idx = phi i64 [ 0, %$preheader ], [ $nextIndex, %$nextLabel ]")
        val inRange = nextTmp()
        emit("  $inRange = icmp ult i64 $idx, $length")
        emitTerminator("  br i1 $inRange, label %$cmpLabel, label %$missLabel")

        startBlock(cmpLabel)
        val ep = nextTmp()
        emit("  $ep = getelementptr $et, $et* $data, i64 $idx")
        val candidate = nextTmp()
        emit("  $candidate = load $et, $et* $ep, align 1")
        val equal = emitArrayElementEq(elementType, et, candidate, value)
        emitTerminator("  br i1 $equal, label %$foundLabel, label %$nextLabel")

        startBlock(nextLabel)
        emit("  $nextIndex = add i64 $idx, 1")
        emitTerminator("  br label %$condLabel")

        startBlock(foundLabel)
        emitTerminator("  br label %$shiftCondLabel")

        startBlock(shiftCondLabel)
        val shiftIndex = nextTmp()
        emit("  $shiftIndex = phi i64 [ $idx, %$foundLabel ], [ $shiftedIndex, %$shiftBodyLabel ]")
        val lastIndex = nextTmp()
        emit("  $lastIndex = sub i64 $length, 1")
        val needsShift = nextTmp()
        emit("  $needsShift = icmp ult i64 $shiftIndex, $lastIndex")
        emitTerminator("  br i1 $needsShift, label %$shiftBodyLabel, label %$shrinkLabel")

        startBlock(shiftBodyLabel)
        val sourceIndex = nextTmp()
        emit("  $sourceIndex = add i64 $shiftIndex, 1")
        val sourcePtr = nextTmp()
        emit("  $sourcePtr = getelementptr $et, $et* $data, i64 $sourceIndex")
        val shiftedValue = nextTmp()
        emit("  $shiftedValue = load $et, $et* $sourcePtr, align 1")
        val destinationPtr = nextTmp()
        emit("  $destinationPtr = getelementptr $et, $et* $data, i64 $shiftIndex")
        emit("  store $et $shiftedValue, $et* $destinationPtr, align 1")
        emit("  $shiftedIndex = add i64 $shiftIndex, 1")
        emitTerminator("  br label %$shiftCondLabel")

        startBlock(shrinkLabel)
        val newLength = nextTmp()
        emit("  $newLength = sub i64 $length, 1")
        emit("  store i64 $newLength, i64* $lenPtr")
        emitTerminator("  br label %$endLabel")

        startBlock(missLabel)
        emitTerminator("  br label %$endLabel")

        startBlock(endLabel)
        val removed = nextTmp()
        emit("  $removed = phi i1 [ true, %$shrinkLabel ], [ false, %$missLabel ]")
        return removed
    }

    /** Map layout: `[i64 length | packed keys | packed values]`. */
    private fun emitMapLiteral(expr: IrExpr.MapLit): String {
        val mapType = expr.type as? IrType.Map ?: return "null"
        val keyType = mapType.key
        val valueType = mapType.value
        val kt = mapType(keyType)
        val vt = mapType(valueType)
        val keySize = sizeOfScalar(keyType)
        val valueSize = sizeOfScalar(valueType)
        val count = expr.entries.size
        val total = 8 + count * keySize + count * valueSize
        val entries = expr.entries.map { (key, value) ->
            (emitExpr(key) to key.type) to (emitExpr(value) to value.type)
        }

        val raw = emitHeapAlloc("$total")
        val lenPtr = nextTmp()
        emit("  $lenPtr = bitcast i8* $raw to i64*")
        emit("  store i64 $count, i64* $lenPtr")
        if (entries.isNotEmpty()) {
            val keysRaw = nextTmp()
            emit("  $keysRaw = getelementptr i8, i8* $raw, i64 8")
            val keys = nextTmp()
            emit("  $keys = bitcast i8* $keysRaw to $kt*")
            val valuesRaw = nextTmp()
            emit("  $valuesRaw = getelementptr i8, i8* $keysRaw, i64 ${count * keySize}")
            val values = nextTmp()
            emit("  $values = bitcast i8* $valuesRaw to $vt*")
            for (i in entries.indices) {
                val (keyPair, valuePair) = entries[i]
                val key = coerceNumeric(keyPair.first, keyPair.second, keyType)
                val value = coerceNumeric(valuePair.first, valuePair.second, valueType)
                val kp = nextTmp()
                emit("  $kp = getelementptr $kt, $kt* $keys, i64 $i")
                emit("  store $kt $key, $kt* $kp, align 1")
                val vp = nextTmp()
                emit("  $vp = getelementptr $vt, $vt* $values, i64 $i")
                emit("  store $vt $value, $vt* $vp, align 1")
            }
        }
        return raw
    }

    private fun emitMapPointers(
        raw: String,
        length: String,
        keyType: IrType,
        valueType: IrType,
    ): Pair<String, String> {
        val kt = mapType(keyType)
        val vt = mapType(valueType)
        val keysRaw = nextTmp()
        emit("  $keysRaw = getelementptr i8, i8* $raw, i64 8")
        val keys = nextTmp()
        emit("  $keys = bitcast i8* $keysRaw to $kt*")
        val keyBytes = nextTmp()
        emit("  $keyBytes = mul i64 $length, ${sizeOfScalar(keyType)}")
        val valuesRaw = nextTmp()
        emit("  $valuesRaw = getelementptr i8, i8* $keysRaw, i64 $keyBytes")
        val values = nextTmp()
        emit("  $values = bitcast i8* $valuesRaw to $vt*")
        return keys to values
    }

    private fun emitMapIndexRead(expr: IrExpr.Index, map: IrType.Map): String {
        val kt = mapType(map.key)
        val vt = mapType(map.value)
        val raw = emitExpr(expr.target)
        val length = emitArrayLengthI64(raw)
        val keyRaw = emitExpr(expr.index)
        val key = coerceNumeric(keyRaw, expr.index.type, map.key)
        val (keys, values) = emitMapPointers(raw, length, map.key, map.value)

        val condLabel = nextLabel("map_get_cond")
        val cmpLabel = nextLabel("map_get_cmp")
        val nextLabel = nextLabel("map_get_next")
        val foundLabel = nextLabel("map_get_found")
        val missLabel = nextLabel("map_get_miss")
        val endLabel = nextLabel("map_get_end")
        val nextIndex = "%map_get_next_${labelCounter++}"
        val foundValue = "%map_get_value_${labelCounter++}"
        val preheader = currentBlock

        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val idx = nextTmp()
        emit("  $idx = phi i64 [ 0, %$preheader ], [ $nextIndex, %$nextLabel ]")
        val inRange = nextTmp()
        emit("  $inRange = icmp ult i64 $idx, $length")
        emitTerminator("  br i1 $inRange, label %$cmpLabel, label %$missLabel")

        startBlock(cmpLabel)
        val kp = nextTmp()
        emit("  $kp = getelementptr $kt, $kt* $keys, i64 $idx")
        val candidate = nextTmp()
        emit("  $candidate = load $kt, $kt* $kp, align 1")
        val equal = emitArrayElementEq(map.key, kt, candidate, key)
        emitTerminator("  br i1 $equal, label %$foundLabel, label %$nextLabel")

        startBlock(nextLabel)
        emit("  $nextIndex = add i64 $idx, 1")
        emitTerminator("  br label %$condLabel")

        startBlock(foundLabel)
        val vp = nextTmp()
        emit("  $vp = getelementptr $vt, $vt* $values, i64 $idx")
        emit("  $foundValue = load $vt, $vt* $vp, align 1")
        emitTerminator("  br label %$endLabel")

        startBlock(missLabel)
        emitTerminator("  br label %$endLabel")

        startBlock(endLabel)
        val result = nextTmp()
        emit("  $result = phi $vt [ $foundValue, %$foundLabel ], [ ${defaultValue(map.value)}, %$missLabel ]")
        return result
    }

    private fun emitMapIndexAssign(stmt: IrStmt.IndexAssign, map: IrType.Map) {
        usesMapGrow = true
        val kt = mapType(map.key)
        val vt = mapType(map.value)
        val raw = emitExpr(stmt.target)
        val length = emitArrayLengthI64(raw)
        val keyRaw = emitExpr(stmt.index)
        val key = coerceNumeric(keyRaw, stmt.index.type, map.key)
        val valueRaw = emitExpr(stmt.value)
        val value = coerceNumeric(valueRaw, stmt.value.type, map.value)
        val (keys, values) = emitMapPointers(raw, length, map.key, map.value)

        val condLabel = nextLabel("map_set_cond")
        val cmpLabel = nextLabel("map_set_cmp")
        val nextLabel = nextLabel("map_set_next")
        val foundLabel = nextLabel("map_set_found")
        val insertLabel = nextLabel("map_set_insert")
        val endLabel = nextLabel("map_set_end")
        val nextIndex = "%map_set_next_${labelCounter++}"
        val preheader = currentBlock

        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val idx = nextTmp()
        emit("  $idx = phi i64 [ 0, %$preheader ], [ $nextIndex, %$nextLabel ]")
        val inRange = nextTmp()
        emit("  $inRange = icmp ult i64 $idx, $length")
        emitTerminator("  br i1 $inRange, label %$cmpLabel, label %$insertLabel")

        startBlock(cmpLabel)
        val kp = nextTmp()
        emit("  $kp = getelementptr $kt, $kt* $keys, i64 $idx")
        val candidate = nextTmp()
        emit("  $candidate = load $kt, $kt* $kp, align 1")
        val equal = emitArrayElementEq(map.key, kt, candidate, key)
        emitTerminator("  br i1 $equal, label %$foundLabel, label %$nextLabel")

        startBlock(nextLabel)
        emit("  $nextIndex = add i64 $idx, 1")
        emitTerminator("  br label %$condLabel")

        startBlock(foundLabel)
        val existingValue = nextTmp()
        emit("  $existingValue = getelementptr $vt, $vt* $values, i64 $idx")
        emit("  store $vt $value, $vt* $existingValue, align 1")
        emitTerminator("  br label %$endLabel")

        startBlock(insertLabel)
        val grown = nextTmp()
        emit("  $grown = call i8* @__azora_map_grow(i8* $raw, i64 ${sizeOfScalar(map.key)}, i64 ${sizeOfScalar(map.value)})")
        val newLength = nextTmp()
        emit("  $newLength = add i64 $length, 1")
        val (newKeys, newValues) = emitMapPointers(grown, newLength, map.key, map.value)
        val newKey = nextTmp()
        emit("  $newKey = getelementptr $kt, $kt* $newKeys, i64 $length")
        emit("  store $kt $key, $kt* $newKey, align 1")
        val newValue = nextTmp()
        emit("  $newValue = getelementptr $vt, $vt* $newValues, i64 $length")
        emit("  store $vt $value, $vt* $newValue, align 1")
        variableStorage(stmt.target)?.let { (address, type) ->
            emit("  store $type $grown, $type* $address")
        } ?: emit("  ; map insertion receiver is not assignable — grown buffer not stored")
        emitTerminator("  br label %$endLabel")

        startBlock(endLabel)
    }

    private fun emitArrayLengthI64(raw: String): String {
        val lenPtr = nextTmp()
        emit("  $lenPtr = bitcast i8* $raw to i64*")
        val len = nextTmp()
        emit("  $len = load i64, i64* $lenPtr")
        return len
    }

    private fun emitArrayEmptyCheck(target: IrExpr, notEmpty: Boolean): String {
        val raw = emitExpr(target)
        val len = emitArrayLengthI64(raw)
        val tmp = nextTmp()
        val pred = if (notEmpty) "ne" else "eq"
        emit("  $tmp = icmp $pred i64 $len, 0")
        return tmp
    }

    private fun variableStorage(target: IrExpr): Pair<String, String>? {
        val v = target as? IrExpr.Var ?: return null
        val local = localVars[v.name]
        if (local != null) return local
        return "@${v.name}" to mapType(v.type)
    }

    private fun emitArrayAdd(target: IrExpr, valueExpr: IrExpr, elemType: IrType) {
        usesArrayGrow = true
        val et = mapType(elemType)
        val raw = emitExpr(target)
        val oldLen = emitArrayLengthI64(raw)
        val rawValue = emitExpr(valueExpr)
        val value = coerceNumeric(rawValue, valueExpr.type, elemType)
        val grown = nextTmp()
        emit("  $grown = call i8* @__azora_array_grow(i8* $raw, i64 ${sizeOfScalar(elemType)})")
        val dataRaw = nextTmp()
        emit("  $dataRaw = getelementptr i8, i8* $grown, i64 8")
        val data = nextTmp()
        emit("  $data = bitcast i8* $dataRaw to $et*")
        val ep = nextTmp()
        emit("  $ep = getelementptr $et, $et* $data, i64 $oldLen")
        emit("  store $et $value, $et* $ep, align 1")

        val storage = variableStorage(target)
        if (storage != null) {
            val (addr, type) = storage
            emit("  store $type $grown, $type* $addr")
        } else {
            emit("  ; array add receiver is not assignable — grown buffer not stored")
        }
    }

    private fun emitArrayContains(target: IrExpr, needleExpr: IrExpr, elemType: IrType): String {
        val et = mapType(elemType)
        val raw = emitExpr(target)
        val len = emitArrayLengthI64(raw)
        val needleRaw = emitExpr(needleExpr)
        val needle = coerceNumeric(needleRaw, needleExpr.type, elemType)
        val dataRaw = nextTmp()
        emit("  $dataRaw = getelementptr i8, i8* $raw, i64 8")
        val data = nextTmp()
        emit("  $data = bitcast i8* $dataRaw to $et*")

        val condLabel = nextLabel("contains_cond")
        val cmpLabel = nextLabel("contains_cmp")
        val nextLabel = nextLabel("contains_next")
        val foundLabel = nextLabel("contains_found")
        val missLabel = nextLabel("contains_miss")
        val endLabel = nextLabel("contains_end")
        val nextIndex = "%contains_next_${labelCounter++}"
        val preheader = currentBlock

        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val idx = nextTmp()
        emit("  $idx = phi i64 [ 0, %$preheader ], [ $nextIndex, %$nextLabel ]")
        val inRange = nextTmp()
        emit("  $inRange = icmp ult i64 $idx, $len")
        emitTerminator("  br i1 $inRange, label %$cmpLabel, label %$missLabel")

        startBlock(cmpLabel)
        val ep = nextTmp()
        emit("  $ep = getelementptr $et, $et* $data, i64 $idx")
        val item = nextTmp()
        emit("  $item = load $et, $et* $ep, align 1")
        val eq = emitArrayElementEq(elemType, et, item, needle)
        emitTerminator("  br i1 $eq, label %$foundLabel, label %$nextLabel")

        startBlock(nextLabel)
        emit("  $nextIndex = add i64 $idx, 1")
        emitTerminator("  br label %$condLabel")

        startBlock(foundLabel)
        emitTerminator("  br label %$endLabel")

        startBlock(missLabel)
        emitTerminator("  br label %$endLabel")

        startBlock(endLabel)
        val result = nextTmp()
        emit("  $result = phi i1 [ true, %$foundLabel ], [ false, %$missLabel ]")
        return result
    }

    private fun emitArrayElementEq(type: IrType, llvmType: String, left: String, right: String): String {
        if (type == IrType.String) {
            usesStrcmp = true
            val cmp = nextTmp()
            emit("  $cmp = call i32 @strcmp(i8* $left, i8* $right)")
            val eq = nextTmp()
            emit("  $eq = icmp eq i32 $cmp, 0")
            return eq
        }
        val eq = nextTmp()
        if (type in IrType.floatTypes) emit("  $eq = fcmp oeq $llvmType $left, $right")
        else emit("  $eq = icmp eq $llvmType $left, $right")
        return eq
    }

    /** Emits a pointer to element [index] of array value [target]. */
    private fun emitArrayElemPtr(target: IrExpr, index: IrExpr, elemType: IrType): String {
        val et = mapType(elemType)
        val raw = emitExpr(target)
        val idxRaw = emitExpr(index)
        val idx = indexToI64(idxRaw, index.type)
        val dataRaw = nextTmp()
        emit("  $dataRaw = getelementptr i8, i8* $raw, i64 8")
        val data = nextTmp()
        emit("  $data = bitcast i8* $dataRaw to $et*")
        val ep = nextTmp()
        emit("  $ep = getelementptr $et, $et* $data, i64 $idx")
        return ep
    }

    private fun emitIndexRead(expr: IrExpr.Index): String {
        val tt = expr.target.type
        if (tt is IrType.Array) {
            val et = mapType(tt.element)
            val ep = emitArrayElemPtr(expr.target, expr.index, tt.element)
            val tmp = nextTmp()
            emit("  $tmp = load $et, $et* $ep, align 1")
            return tmp
        }
        if (tt is IrType.Map) return emitMapIndexRead(expr, tt)
        if (tt == IrType.String) {
            val s = emitExpr(expr.target)
            val idxRaw = emitExpr(expr.index)
            val idx = indexToI64(idxRaw, expr.index.type)
            val cp = nextTmp()
            emit("  $cp = getelementptr i8, i8* $s, i64 $idx")
            val tmp = nextTmp()
            emit("  $tmp = load i8, i8* $cp")
            return tmp
        }
        emitExpr(expr.target)
        emitExpr(expr.index)
        emit("  ; index on ${expr.target.type} — not lowered")
        return defaultValue(expr.type)
    }

    private fun emitIndexAssign(stmt: IrStmt.IndexAssign) {
        val tt = stmt.target.type
        if (tt is IrType.Array) {
            val et = mapType(tt.element)
            val ep = emitArrayElemPtr(stmt.target, stmt.index, tt.element)
            val raw = emitExpr(stmt.value)
            val value = coerceNumeric(raw, stmt.value.type, tt.element)
            emit("  store $et $value, $et* $ep, align 1")
            return
        }
        if (tt is IrType.Map) {
            emitMapIndexAssign(stmt, tt)
            return
        }
        emitExpr(stmt.target)
        emitExpr(stmt.index)
        emitExpr(stmt.value)
        emit("  ; index assign on ${stmt.target.type} — not lowered")
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
                    else -> {
                        // Erased generic (Any) — no native negate; stub like other
                        // unlowered aggregate operations.
                        emit("  ; negate on ${expr.type} — not lowered (erased generic)")
                        return defaultValue(expr.type)
                    }
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
            // Nullable / erased-any pointers: only equality against null (or another
            // pointer) is meaningful — lower as pointer comparison.
            (leftType is IrType.Nullable || leftType == IrType.Any) &&
                (expr.op == IrBinaryOp.EQ || expr.op == IrBinaryOp.NEQ) -> {
                val pred = if (expr.op == IrBinaryOp.EQ) "eq" else "ne"
                emit("  $tmp = icmp $pred i8* $left, $right")
            }
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
            else -> {
                // Unsupported operand type (e.g. arithmetic on a nullable/boxed
                // value). The LLVM backend has no unboxing model yet, so degrade
                // to a stub — consistent with how other aggregate ops are handled.
                emit("  ; binary ${expr.op} on ${expr.left.type} — not lowered (nullable aggregate)")
            }
        }
        return tmp
    }

    /** Lowers an if-expression to a conditional branch feeding a phi. */
    private fun emitIfExpr(expr: IrExpr.IfExpr): String {
        val cond = emitExpr(expr.condition)
        val thenLabel = nextLabel("ifx_then")
        val elseLabel = nextLabel("ifx_else")
        val endLabel = nextLabel("ifx_end")
        emitTerminator("  br i1 $cond, label %$thenLabel, label %$elseLabel")
        startBlock(thenLabel)
        val thenValue = coerceNumeric(emitExpr(expr.thenExpr), expr.thenExpr.type, expr.type)
        val thenBlock = currentBlock
        emitTerminator("  br label %$endLabel")
        startBlock(elseLabel)
        val elseValue = coerceNumeric(emitExpr(expr.elseExpr), expr.elseExpr.type, expr.type)
        val elseBlock = currentBlock
        emitTerminator("  br label %$endLabel")
        startBlock(endLabel)
        val tmp = nextTmp()
        emit("  $tmp = phi ${mapType(expr.type)} [ $thenValue, %$thenBlock ], [ $elseValue, %$elseBlock ]")
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
        if (expr.name == "async") {
            val lambda = expr.args.singleOrNull() as? IrExpr.Lambda
                ?: error("LLVM async lowering requires a task block")
            val resultType = (expr.type as? IrType.Task)?.result ?: IrType.Any
            return emitLambdaTaskSpawn(lambda, resultType, "async")
        }
        if (expr.name == "__launch") {
            val lambda = expr.args.singleOrNull() as? IrExpr.Lambda
                ?: error("LLVM launch lowering requires a task block")
            emitLambdaTaskSpawn(lambda, IrType.Unit, "launch")
            return "void"
        }
        if (expr.name == "__alloc") {
            return emitPointerAlloc(expr.args.single())
        }
        if (expr.name == "__deref") {
            return emitPointerDeref(expr.args.single(), expr.type)
        }
        if (expr.name == "__derefAssign") {
            emitPointerAssign(expr.args[0], expr.args[1])
            return "void"
        }
        if (expr.name == "__drop") {
            emitExpr(expr.args.single())
            emit("  ; drop — advisory for raw pointers; arenas/task scopes own native cleanup")
            return "void"
        }
        if (expr.name == "cancel") {
            usesTaskRuntime = true
            val handle = emitExpr(expr.args.single())
            emit("  call void @__azora_task_cancel(%azora.task* $handle)")
            return "void"
        }

        // Coerce arguments to the callee's declared parameter types (numeric
        // widening such as an Int literal passed to a Real/Long parameter).
        val declared = funcParamTypes[expr.name]
        val args = expr.args.mapIndexed { i, arg ->
            val paramType = declared?.getOrNull(i) ?: arg.type
            val value = coerceNumeric(emitExpr(arg), arg.type, paramType)
            "${mapType(paramType)} $value"
        }.joinToString(", ")
        val retType = mapType(expr.type)
        return if (expr.type == IrType.Unit) {
            emit("  call void @${expr.name}($args)")
            "void"
        } else {
            val tmp = nextTmp()
            emit("  $tmp = call $retType @${expr.name}($args)")
            if (expr.type is IrType.Task) emitTaskScopeAttach(tmp)
            tmp
        }
    }

    private fun emitPointerAlloc(valueExpr: IrExpr): String {
        val value = emitExpr(valueExpr)
        val arrayType = valueExpr.type as? IrType.Array
        if (arrayType != null) {
            val data = nextTmp()
            emit("  $data = getelementptr i8, i8* $value, i64 8")
            return data
        }
        val raw = emitHeapAlloc("${sizeOfScalar(valueExpr.type)}")
        val typed = nextTmp()
        val ptrType = "${mapType(valueExpr.type)}*"
        emit("  $typed = bitcast i8* $raw to $ptrType")
        emit("  store ${mapType(valueExpr.type)} $value, $ptrType $typed, align 1")
        return raw
    }

    private fun emitPointerDeref(ptrExpr: IrExpr, resultType: IrType): String {
        val ptr = emitExpr(ptrExpr)
        val typed = nextTmp()
        val ptrType = "${mapType(resultType)}*"
        emit("  $typed = bitcast i8* $ptr to $ptrType")
        val value = nextTmp()
        emit("  $value = load ${mapType(resultType)}, $ptrType $typed, align 1")
        return value
    }

    private fun emitPointerAssign(ptrExpr: IrExpr, valueExpr: IrExpr) {
        val ptr = emitExpr(ptrExpr)
        val pointee = (ptrExpr.type as? IrType.Pointer)?.inner ?: valueExpr.type
        val raw = emitExpr(valueExpr)
        val value = coerceNumeric(raw, valueExpr.type, pointee)
        val typed = nextTmp()
        val ptrType = "${mapType(pointee)}*"
        emit("  $typed = bitcast i8* $ptr to $ptrType")
        emit("  store ${mapType(pointee)} $value, $ptrType $typed, align 1")
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
            IrType.Int, IrType.Byte, IrType.Short -> {
                val v = coerceToI32(emitExpr(arg), arg.type)
                printfFmt("%d$nl", listOf("i32 $v"))
            }
            IrType.UInt, IrType.UByte, IrType.UShort -> {
                val v = coerceToI32(emitExpr(arg), arg.type)
                printfFmt("%u$nl", listOf("i32 $v"))
            }
            IrType.Long -> {
                val v = emitExpr(arg)
                printfFmt("%lld$nl", listOf("i64 $v"))
            }
            IrType.ULong -> {
                val v = emitExpr(arg)
                printfFmt("%llu$nl", listOf("i64 $v"))
            }
            IrType.Cent, IrType.UCent -> {
                val v = emitExpr(arg)
                // No portable printf length modifier for i128; truncate to i64.
                val t = nextTmp()
                emit("  $t = trunc i128 $v to i64")
                printfFmt(if (arg.type == IrType.UCent) "%llu$nl" else "%lld$nl", listOf("i64 $t"))
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
        IrType.Cent, IrType.UCent -> {
            val t = nextTmp(); emit("  $t = trunc i128 $value to i32"); t
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
                // Integer types → 64-bit then format (unsigned types use %llu).
                usesSnprintf = true; usesMalloc = true
                val raw = emitExpr(expr)
                val v = widenToI64(raw, expr.type)
                val tmp = nextTmp()
                if (isUnsigned(expr.type)) {
                    usesUintToStr = true
                    emit("  $tmp = call i8* @__azora_uint_to_str(i64 $v)")
                } else {
                    usesIntToStr = true
                    emit("  $tmp = call i8* @__azora_int_to_str(i64 $v)")
                }
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

        if (usesAllocatorRuntime) {
            usesMalloc = true
            usesFree = true
            usesAbort = true
            sb.appendLine("; runtime: checked native allocation")
            sb.appendLine("define i8* @__azora_alloc(i64 %size) {")
            sb.appendLine("entry:")
            sb.appendLine("  %p = call i8* @malloc(i64 %size)")
            sb.appendLine("  %isnull = icmp eq i8* %p, null")
            sb.appendLine("  br i1 %isnull, label %oom, label %ok")
            sb.appendLine("oom:")
            sb.appendLine("  call void @abort()")
            sb.appendLine("  unreachable")
            sb.appendLine("ok:")
            sb.appendLine("  ret i8* %p")
            sb.appendLine("}")
            sb.appendLine()
            sb.appendLine("define void @__azora_free(i8* %ptr) {")
            sb.appendLine("entry:")
            sb.appendLine("  %isnull = icmp eq i8* %ptr, null")
            sb.appendLine("  br i1 %isnull, label %end, label %free")
            sb.appendLine("free:")
            sb.appendLine("  call void @free(i8* %ptr)")
            sb.appendLine("  br label %end")
            sb.appendLine("end:")
            sb.appendLine("  ret void")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (globalVars.keys.any { it.startsWith("__tl__") }) {
            sb.appendLine("; runtime: lli fallback for Mach-O emulated TLS lookup")
            sb.appendLine("define i8* @__emutls_get_address(i8* %control) {")
            sb.appendLine("entry:")
            sb.appendLine("  %templ.addr.raw = getelementptr i8, i8* %control, i64 24")
            sb.appendLine("  %templ.addr = bitcast i8* %templ.addr.raw to i8**")
            sb.appendLine("  %templ = load i8*, i8** %templ.addr")
            sb.appendLine("  ret i8* %templ")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesArenaRuntime) {
            usesAllocatorRuntime = true
            sb.appendLine("; runtime: scoped arena allocation")
            sb.appendLine("define void @__azora_arena_begin(%azora.arena* %arena) {")
            sb.appendLine("entry:")
            sb.appendLine("  %count = getelementptr %azora.arena, %azora.arena* %arena, i32 0, i32 0")
            sb.appendLine("  %cap = getelementptr %azora.arena, %azora.arena* %arena, i32 0, i32 1")
            sb.appendLine("  %items = getelementptr %azora.arena, %azora.arena* %arena, i32 0, i32 2")
            sb.appendLine("  store i64 0, i64* %count")
            sb.appendLine("  store i64 0, i64* %cap")
            sb.appendLine("  store i8** null, i8*** %items")
            sb.appendLine("  ret void")
            sb.appendLine("}")
            sb.appendLine()
            sb.appendLine("define i8* @__azora_arena_alloc(%azora.arena* %arena, i64 %size) {")
            sb.appendLine("entry:")
            sb.appendLine("  %ptr = call i8* @__azora_alloc(i64 %size)")
            sb.appendLine("  %countPtr = getelementptr %azora.arena, %azora.arena* %arena, i32 0, i32 0")
            sb.appendLine("  %capPtr = getelementptr %azora.arena, %azora.arena* %arena, i32 0, i32 1")
            sb.appendLine("  %itemsPtr = getelementptr %azora.arena, %azora.arena* %arena, i32 0, i32 2")
            sb.appendLine("  %count = load i64, i64* %countPtr")
            sb.appendLine("  %cap = load i64, i64* %capPtr")
            sb.appendLine("  %full = icmp uge i64 %count, %cap")
            sb.appendLine("  br i1 %full, label %grow, label %store")
            sb.appendLine("grow:")
            sb.appendLine("  %iszero = icmp eq i64 %cap, 0")
            sb.appendLine("  %double = mul i64 %cap, 2")
            sb.appendLine("  %newCap = select i1 %iszero, i64 8, i64 %double")
            sb.appendLine("  %bytes = mul i64 %newCap, 8")
            sb.appendLine("  %newRaw = call i8* @__azora_alloc(i64 %bytes)")
            sb.appendLine("  %newItems = bitcast i8* %newRaw to i8**")
            sb.appendLine("  %oldItems = load i8**, i8*** %itemsPtr")
            sb.appendLine("  br label %copy.cond")
            sb.appendLine("copy.cond:")
            sb.appendLine("  %i = phi i64 [ 0, %grow ], [ %next, %copy.body ]")
            sb.appendLine("  %done = icmp uge i64 %i, %count")
            sb.appendLine("  br i1 %done, label %copy.end, label %copy.body")
            sb.appendLine("copy.body:")
            sb.appendLine("  %oldSlot = getelementptr i8*, i8** %oldItems, i64 %i")
            sb.appendLine("  %oldVal = load i8*, i8** %oldSlot")
            sb.appendLine("  %newSlot = getelementptr i8*, i8** %newItems, i64 %i")
            sb.appendLine("  store i8* %oldVal, i8** %newSlot")
            sb.appendLine("  %next = add i64 %i, 1")
            sb.appendLine("  br label %copy.cond")
            sb.appendLine("copy.end:")
            sb.appendLine("  %oldRaw = bitcast i8** %oldItems to i8*")
            sb.appendLine("  call void @__azora_free(i8* %oldRaw)")
            sb.appendLine("  store i8** %newItems, i8*** %itemsPtr")
            sb.appendLine("  store i64 %newCap, i64* %capPtr")
            sb.appendLine("  br label %store")
            sb.appendLine("store:")
            sb.appendLine("  %items = load i8**, i8*** %itemsPtr")
            sb.appendLine("  %slot = getelementptr i8*, i8** %items, i64 %count")
            sb.appendLine("  store i8* %ptr, i8** %slot")
            sb.appendLine("  %newCount = add i64 %count, 1")
            sb.appendLine("  store i64 %newCount, i64* %countPtr")
            sb.appendLine("  ret i8* %ptr")
            sb.appendLine("}")
            sb.appendLine()
            sb.appendLine("define void @__azora_arena_free_all(%azora.arena* %arena) {")
            sb.appendLine("entry:")
            sb.appendLine("  %countPtr = getelementptr %azora.arena, %azora.arena* %arena, i32 0, i32 0")
            sb.appendLine("  %capPtr = getelementptr %azora.arena, %azora.arena* %arena, i32 0, i32 1")
            sb.appendLine("  %itemsPtr = getelementptr %azora.arena, %azora.arena* %arena, i32 0, i32 2")
            sb.appendLine("  %count = load i64, i64* %countPtr")
            sb.appendLine("  %items = load i8**, i8*** %itemsPtr")
            sb.appendLine("  br label %loop")
            sb.appendLine("loop:")
            sb.appendLine("  %i = phi i64 [ 0, %entry ], [ %next, %body ]")
            sb.appendLine("  %done = icmp uge i64 %i, %count")
            sb.appendLine("  br i1 %done, label %end, label %body")
            sb.appendLine("body:")
            sb.appendLine("  %slot = getelementptr i8*, i8** %items, i64 %i")
            sb.appendLine("  %ptr = load i8*, i8** %slot")
            sb.appendLine("  call void @__azora_free(i8* %ptr)")
            sb.appendLine("  %next = add i64 %i, 1")
            sb.appendLine("  br label %loop")
            sb.appendLine("end:")
            sb.appendLine("  %raw = bitcast i8** %items to i8*")
            sb.appendLine("  call void @__azora_free(i8* %raw)")
            sb.appendLine("  store i64 0, i64* %countPtr")
            sb.appendLine("  store i64 0, i64* %capPtr")
            sb.appendLine("  store i8** null, i8*** %itemsPtr")
            sb.appendLine("  ret void")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesTaskRuntime) {
            usesAllocatorRuntime = true
            usesFree = true
            sb.appendLine("; runtime: pthread-backed structured tasks")
            sb.appendLine("define %azora.task* @__azora_task_spawn(i8* (i8*)* %fn, i8* %ctx) {")
            sb.appendLine("entry:")
            sb.appendLine("  %task.raw = call i8* @__azora_alloc(i64 24)")
            sb.appendLine("  %task = bitcast i8* %task.raw to %azora.task*")
            sb.appendLine("  %thread.slot.raw = call i8* @__azora_alloc(i64 8)")
            sb.appendLine("  %thread.slot = bitcast i8* %thread.slot.raw to i8**")
            sb.appendLine("  %create = call i32 @pthread_create(i8** %thread.slot, i8* null, i8* (i8*)* %fn, i8* %ctx)")
            sb.appendLine("  %thread = load i8*, i8** %thread.slot")
            sb.appendLine("  call void @__azora_free(i8* %thread.slot.raw)")
            sb.appendLine("  %thread.field = getelementptr %azora.task, %azora.task* %task, i32 0, i32 0")
            sb.appendLine("  %result.field = getelementptr %azora.task, %azora.task* %task, i32 0, i32 1")
            sb.appendLine("  %joined.field = getelementptr %azora.task, %azora.task* %task, i32 0, i32 2")
            sb.appendLine("  %cancel.field = getelementptr %azora.task, %azora.task* %task, i32 0, i32 3")
            sb.appendLine("  store i8* %thread, i8** %thread.field")
            sb.appendLine("  store i8* null, i8** %result.field")
            sb.appendLine("  store i1 false, i1* %joined.field")
            sb.appendLine("  store i1 false, i1* %cancel.field")
            sb.appendLine("  ret %azora.task* %task")
            sb.appendLine("}")
            sb.appendLine()
            sb.appendLine("define i8* @__azora_task_join(%azora.task* %task) {")
            sb.appendLine("entry:")
            sb.appendLine("  %isnull = icmp eq %azora.task* %task, null")
            sb.appendLine("  br i1 %isnull, label %null, label %check")
            sb.appendLine("null:")
            sb.appendLine("  ret i8* null")
            sb.appendLine("check:")
            sb.appendLine("  %joined.field = getelementptr %azora.task, %azora.task* %task, i32 0, i32 2")
            sb.appendLine("  %joined = load i1, i1* %joined.field")
            sb.appendLine("  br i1 %joined, label %done, label %join")
            sb.appendLine("join:")
            sb.appendLine("  %thread.field = getelementptr %azora.task, %azora.task* %task, i32 0, i32 0")
            sb.appendLine("  %thread = load i8*, i8** %thread.field")
            sb.appendLine("  %result.addr = alloca i8*")
            sb.appendLine("  store i8* null, i8** %result.addr")
            sb.appendLine("  %rc = call i32 @pthread_join(i8* %thread, i8** %result.addr)")
            sb.appendLine("  %result = load i8*, i8** %result.addr")
            sb.appendLine("  %result.field = getelementptr %azora.task, %azora.task* %task, i32 0, i32 1")
            sb.appendLine("  store i8* %result, i8** %result.field")
            sb.appendLine("  store i1 true, i1* %joined.field")
            sb.appendLine("  br label %done")
            sb.appendLine("done:")
            sb.appendLine("  %stored.field = getelementptr %azora.task, %azora.task* %task, i32 0, i32 1")
            sb.appendLine("  %stored = load i8*, i8** %stored.field")
            sb.appendLine("  ret i8* %stored")
            sb.appendLine("}")
            sb.appendLine()
            sb.appendLine("define void @__azora_task_cancel(%azora.task* %task) {")
            sb.appendLine("entry:")
            sb.appendLine("  %isnull = icmp eq %azora.task* %task, null")
            sb.appendLine("  br i1 %isnull, label %end, label %check")
            sb.appendLine("check:")
            sb.appendLine("  %cancel.field = getelementptr %azora.task, %azora.task* %task, i32 0, i32 3")
            sb.appendLine("  store i1 true, i1* %cancel.field")
            sb.appendLine("  %joined.field = getelementptr %azora.task, %azora.task* %task, i32 0, i32 2")
            sb.appendLine("  %joined = load i1, i1* %joined.field")
            sb.appendLine("  br i1 %joined, label %end, label %cancel")
            sb.appendLine("cancel:")
            sb.appendLine("  %thread.field = getelementptr %azora.task, %azora.task* %task, i32 0, i32 0")
            sb.appendLine("  %thread = load i8*, i8** %thread.field")
            sb.appendLine("  %rc = call i32 @pthread_cancel(i8* %thread)")
            sb.appendLine("  br label %end")
            sb.appendLine("end:")
            sb.appendLine("  ret void")
            sb.appendLine("}")
            sb.appendLine()
            sb.appendLine("define void @__azora_task_destroy(%azora.task* %task) {")
            sb.appendLine("entry:")
            sb.appendLine("  %isnull = icmp eq %azora.task* %task, null")
            sb.appendLine("  br i1 %isnull, label %end, label %join")
            sb.appendLine("join:")
            sb.appendLine("  %result = call i8* @__azora_task_join(%azora.task* %task)")
            sb.appendLine("  call void @__azora_free(i8* %result)")
            sb.appendLine("  %raw = bitcast %azora.task* %task to i8*")
            sb.appendLine("  call void @__azora_free(i8* %raw)")
            sb.appendLine("  br label %end")
            sb.appendLine("end:")
            sb.appendLine("  ret void")
            sb.appendLine("}")
            sb.appendLine()
            sb.appendLine("define void @__azora_scope_init(%azora.scope* %scope) {")
            sb.appendLine("entry:")
            sb.appendLine("  %count = getelementptr %azora.scope, %azora.scope* %scope, i32 0, i32 0")
            sb.appendLine("  %cap = getelementptr %azora.scope, %azora.scope* %scope, i32 0, i32 1")
            sb.appendLine("  %items = getelementptr %azora.scope, %azora.scope* %scope, i32 0, i32 2")
            sb.appendLine("  store i64 0, i64* %count")
            sb.appendLine("  store i64 0, i64* %cap")
            sb.appendLine("  store %azora.task** null, %azora.task*** %items")
            sb.appendLine("  ret void")
            sb.appendLine("}")
            sb.appendLine()
            sb.appendLine("define void @__azora_scope_attach(%azora.scope* %scope, %azora.task* %task) {")
            sb.appendLine("entry:")
            sb.appendLine("  %countPtr = getelementptr %azora.scope, %azora.scope* %scope, i32 0, i32 0")
            sb.appendLine("  %capPtr = getelementptr %azora.scope, %azora.scope* %scope, i32 0, i32 1")
            sb.appendLine("  %itemsPtr = getelementptr %azora.scope, %azora.scope* %scope, i32 0, i32 2")
            sb.appendLine("  %count = load i64, i64* %countPtr")
            sb.appendLine("  %cap = load i64, i64* %capPtr")
            sb.appendLine("  %full = icmp uge i64 %count, %cap")
            sb.appendLine("  br i1 %full, label %grow, label %store")
            sb.appendLine("grow:")
            sb.appendLine("  %iszero = icmp eq i64 %cap, 0")
            sb.appendLine("  %double = mul i64 %cap, 2")
            sb.appendLine("  %newCap = select i1 %iszero, i64 8, i64 %double")
            sb.appendLine("  %bytes = mul i64 %newCap, 8")
            sb.appendLine("  %newRaw = call i8* @__azora_alloc(i64 %bytes)")
            sb.appendLine("  %newItems = bitcast i8* %newRaw to %azora.task**")
            sb.appendLine("  %oldItems = load %azora.task**, %azora.task*** %itemsPtr")
            sb.appendLine("  br label %copy.cond")
            sb.appendLine("copy.cond:")
            sb.appendLine("  %i = phi i64 [ 0, %grow ], [ %next, %copy.body ]")
            sb.appendLine("  %done = icmp uge i64 %i, %count")
            sb.appendLine("  br i1 %done, label %copy.end, label %copy.body")
            sb.appendLine("copy.body:")
            sb.appendLine("  %oldSlot = getelementptr %azora.task*, %azora.task** %oldItems, i64 %i")
            sb.appendLine("  %oldVal = load %azora.task*, %azora.task** %oldSlot")
            sb.appendLine("  %newSlot = getelementptr %azora.task*, %azora.task** %newItems, i64 %i")
            sb.appendLine("  store %azora.task* %oldVal, %azora.task** %newSlot")
            sb.appendLine("  %next = add i64 %i, 1")
            sb.appendLine("  br label %copy.cond")
            sb.appendLine("copy.end:")
            sb.appendLine("  %oldRaw = bitcast %azora.task** %oldItems to i8*")
            sb.appendLine("  call void @__azora_free(i8* %oldRaw)")
            sb.appendLine("  store %azora.task** %newItems, %azora.task*** %itemsPtr")
            sb.appendLine("  store i64 %newCap, i64* %capPtr")
            sb.appendLine("  br label %store")
            sb.appendLine("store:")
            sb.appendLine("  %items = load %azora.task**, %azora.task*** %itemsPtr")
            sb.appendLine("  %slot = getelementptr %azora.task*, %azora.task** %items, i64 %count")
            sb.appendLine("  store %azora.task* %task, %azora.task** %slot")
            sb.appendLine("  %newCount = add i64 %count, 1")
            sb.appendLine("  store i64 %newCount, i64* %countPtr")
            sb.appendLine("  ret void")
            sb.appendLine("}")
            sb.appendLine()
            sb.appendLine("define void @__azora_scope_join_all(%azora.scope* %scope) {")
            sb.appendLine("entry:")
            sb.appendLine("  %countPtr = getelementptr %azora.scope, %azora.scope* %scope, i32 0, i32 0")
            sb.appendLine("  %capPtr = getelementptr %azora.scope, %azora.scope* %scope, i32 0, i32 1")
            sb.appendLine("  %itemsPtr = getelementptr %azora.scope, %azora.scope* %scope, i32 0, i32 2")
            sb.appendLine("  %count = load i64, i64* %countPtr")
            sb.appendLine("  %items = load %azora.task**, %azora.task*** %itemsPtr")
            sb.appendLine("  br label %loop")
            sb.appendLine("loop:")
            sb.appendLine("  %i = phi i64 [ 0, %entry ], [ %next, %body ]")
            sb.appendLine("  %done = icmp uge i64 %i, %count")
            sb.appendLine("  br i1 %done, label %end, label %body")
            sb.appendLine("body:")
            sb.appendLine("  %slot = getelementptr %azora.task*, %azora.task** %items, i64 %i")
            sb.appendLine("  %task = load %azora.task*, %azora.task** %slot")
            sb.appendLine("  call void @__azora_task_destroy(%azora.task* %task)")
            sb.appendLine("  %next = add i64 %i, 1")
            sb.appendLine("  br label %loop")
            sb.appendLine("end:")
            sb.appendLine("  %raw = bitcast %azora.task** %items to i8*")
            sb.appendLine("  call void @__azora_free(i8* %raw)")
            sb.appendLine("  store i64 0, i64* %countPtr")
            sb.appendLine("  store i64 0, i64* %capPtr")
            sb.appendLine("  store %azora.task** null, %azora.task*** %itemsPtr")
            sb.appendLine("  ret void")
            sb.appendLine("}")
            sb.appendLine()
        }

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

        if (usesArrayGrow) {
            usesMalloc = true
            sb.appendLine("; runtime: append one element to a packed array buffer")
            sb.appendLine("define i8* @__azora_array_grow(i8* %old, i64 %elemSize) {")
            sb.appendLine("entry:")
            sb.appendLine("  %lenPtr = bitcast i8* %old to i64*")
            sb.appendLine("  %len = load i64, i64* %lenPtr")
            sb.appendLine("  %newLen = add i64 %len, 1")
            sb.appendLine("  %oldBytes = mul i64 %len, %elemSize")
            sb.appendLine("  %newBytes = mul i64 %newLen, %elemSize")
            sb.appendLine("  %newSize = add i64 %newBytes, 8")
            sb.appendLine("  %newRaw = call i8* @malloc(i64 %newSize)")
            sb.appendLine("  %newLenPtr = bitcast i8* %newRaw to i64*")
            sb.appendLine("  store i64 %newLen, i64* %newLenPtr")
            sb.appendLine("  %oldData = getelementptr i8, i8* %old, i64 8")
            sb.appendLine("  %newData = getelementptr i8, i8* %newRaw, i64 8")
            sb.appendLine("  br label %copy.cond")
            sb.appendLine("copy.cond:")
            sb.appendLine("  %i = phi i64 [ 0, %entry ], [ %next, %copy.body ]")
            sb.appendLine("  %done = icmp uge i64 %i, %oldBytes")
            sb.appendLine("  br i1 %done, label %copy.end, label %copy.body")
            sb.appendLine("copy.body:")
            sb.appendLine("  %src = getelementptr i8, i8* %oldData, i64 %i")
            sb.appendLine("  %byte = load i8, i8* %src")
            sb.appendLine("  %dst = getelementptr i8, i8* %newData, i64 %i")
            sb.appendLine("  store i8 %byte, i8* %dst")
            sb.appendLine("  %next = add i64 %i, 1")
            sb.appendLine("  br label %copy.cond")
            sb.appendLine("copy.end:")
            sb.appendLine("  ret i8* %newRaw")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesMapGrow) {
            usesMalloc = true
            usesMemcpy = true
            sb.appendLine("; runtime: append storage for one packed map entry")
            sb.appendLine("define i8* @__azora_map_grow(i8* %old, i64 %keySize, i64 %valueSize) {")
            sb.appendLine("entry:")
            sb.appendLine("  %lenPtr = bitcast i8* %old to i64*")
            sb.appendLine("  %len = load i64, i64* %lenPtr")
            sb.appendLine("  %newLen = add i64 %len, 1")
            sb.appendLine("  %oldKeyBytes = mul i64 %len, %keySize")
            sb.appendLine("  %oldValueBytes = mul i64 %len, %valueSize")
            sb.appendLine("  %newKeyBytes = mul i64 %newLen, %keySize")
            sb.appendLine("  %newValueBytes = mul i64 %newLen, %valueSize")
            sb.appendLine("  %payloadBytes = add i64 %newKeyBytes, %newValueBytes")
            sb.appendLine("  %totalBytes = add i64 %payloadBytes, 8")
            sb.appendLine("  %newRaw = call i8* @malloc(i64 %totalBytes)")
            sb.appendLine("  %newLenPtr = bitcast i8* %newRaw to i64*")
            sb.appendLine("  store i64 %newLen, i64* %newLenPtr")
            sb.appendLine("  %oldKeys = getelementptr i8, i8* %old, i64 8")
            sb.appendLine("  %oldValues = getelementptr i8, i8* %oldKeys, i64 %oldKeyBytes")
            sb.appendLine("  %newKeys = getelementptr i8, i8* %newRaw, i64 8")
            sb.appendLine("  %newValues = getelementptr i8, i8* %newKeys, i64 %newKeyBytes")
            sb.appendLine("  %copyKeys = call i8* @memcpy(i8* %newKeys, i8* %oldKeys, i64 %oldKeyBytes)")
            sb.appendLine("  %copyValues = call i8* @memcpy(i8* %newValues, i8* %oldValues, i64 %oldValueBytes)")
            sb.appendLine("  ret i8* %newRaw")
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

        if (usesUintToStr) {
            usesMalloc = true; usesSnprintf = true
            val fmt = addStringConstant("%llu")
            sb.appendLine("; runtime: unsigned integer to string")
            sb.appendLine("define i8* @__azora_uint_to_str(i64 %v) {")
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
        is IrType.Map, is IrType.Set -> "i8*"
        is IrType.Function -> "i8*"
        is IrType.Task -> {
            usesTaskRuntime = true
            "%azora.task*"
        }
        is IrType.Tuple -> "i8*"
        is IrType.Nullable -> "i8*"
        is IrType.Pointer -> "i8*"
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
        is IrType.Task -> "null"
        else -> "0"
    }

    /** Formats a floating-point constant in the exact LLVM hex form. */
    private fun floatConst(value: Double, type: IrType): String {
        if (type == IrType.Decimal) return fp128Const(value)
        // LLVM accepts the 64-bit IEEE-754 hex for both float and double
        // constants (for float it must be exactly representable, which holds
        // because the value originated from a float).
        val bits = if (type == IrType.Float)
            value.toFloat().toDouble().toRawBits()
        else
            value.toRawBits()
        return "0x" + bits.toULong().toString(16).uppercase().padStart(16, '0')
    }

    /** Converts a binary64 value exactly into LLVM's 128-bit `0xL...` encoding. */
    private fun fp128Const(value: Double): String {
        val bits = value.toRawBits()
        val sign = bits and Long.MIN_VALUE
        val exponent = ((bits ushr 52) and 0x7ff).toInt()
        val fraction = bits and 0x000f_ffff_ffff_ffffL

        val quadExponent: Int
        val quadHighFraction: Long
        val quadLowFraction: Long
        when {
            exponent == 0 && fraction == 0L -> {
                quadExponent = 0
                quadHighFraction = 0
                quadLowFraction = 0
            }
            exponent == 0 -> {
                val leadingBit = 63 - fraction.countLeadingZeroBits()
                quadExponent = leadingBit + 15309 // leadingBit - 1074 + binary128 bias
                val remainder = fraction xor (1L shl leadingBit)
                val shift = 112 - leadingBit
                if (shift >= 64) {
                    quadHighFraction = remainder shl (shift - 64)
                    quadLowFraction = 0
                } else {
                    quadHighFraction = remainder ushr (64 - shift)
                    quadLowFraction = remainder shl shift
                }
            }
            exponent == 0x7ff -> {
                quadExponent = 0x7fff
                quadHighFraction = fraction ushr 4
                quadLowFraction = fraction shl 60
            }
            else -> {
                quadExponent = exponent - 1023 + 16383
                quadHighFraction = fraction ushr 4
                quadLowFraction = fraction shl 60
            }
        }

        val high = sign or (quadExponent.toLong() shl 48) or quadHighFraction
        val highHex = high.toULong().toString(16).uppercase().padStart(16, '0')
        val lowHex = quadLowFraction.toULong().toString(16).uppercase().padStart(16, '0')
        // LLVM's fp128 text form writes the low 64-bit word before the high word.
        return "0xL$lowHex$highHex"
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
        // Constants are emitted as UTF-8 bytes, so lengths are byte lengths.
        val byteLen = value.encodeToByteArray().size + 1
        for ((name, v) in stringConstants) {
            if (v == value) return StringRef(name, byteLen)
        }
        val name = "@.str.$stringCounter"
        stringCounter++
        stringConstants.add(name to value)
        return StringRef(name, byteLen)
    }

    private fun escapeForLlvm(s: String): String {
        val sb = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val v = b.toInt() and 0xFF
            when {
                v == '\\'.code || v == '"'.code || v < 32 || v > 126 ->
                    sb.append("\\").append(v.toString(16).uppercase().padStart(2, '0'))
                else -> sb.append(v.toChar())
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
