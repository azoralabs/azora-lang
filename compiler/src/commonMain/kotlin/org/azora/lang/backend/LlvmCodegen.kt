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

import org.azora.lang.putIfAbsentCompat
import org.azora.lang.ir.IrBinaryOp
import org.azora.lang.ir.symbolDenotes
import org.azora.lang.ir.IrExpr
import org.azora.lang.ir.IrFunction
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrSpecImpl
import org.azora.lang.ir.IrSpecMethod
import org.azora.lang.ir.IrSpecTable
import org.azora.lang.ir.IrStmt
import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.Intrinsics
import org.azora.lang.ir.IrType
import org.azora.lang.ir.shown
import org.azora.lang.ir.IrUnaryOp

/**
 * Backend - lowers [IrProgram] to LLVM IR text (`.ll` format).
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
 *   Double            → double
 *   Float           → float
 *   Quad         → fp128
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
     * (name, type) is reused across iterations/branches - IR names are
     * pre-mangled for shadowing, and disjoint scopes reusing a name always
     * store before reading.
     */
    private val allocaSlots = mutableMapOf<Pair<String, String>, String>()
    private var allocaCounter = 0

    /** Struct (pack/solo/node) definitions by name, for field-index lookup. */
    private val structDefs = mutableMapOf<String, IrTopLevel.Struct>()

    /**
     * Dynamic dispatch for spec-typed values (Rust-style `dyn Trait`). A spec
     * type has no native struct; a spec-typed value is a heap `{ i32 typeId,
     * i8* data }` fat pointer. [specDispatch] maps a spec name to its dispatch
     * table; [specTypeIds] assigns each concrete implementer a stable non-zero id.
     */
    private val specDispatch = mutableMapOf<String, IrSpecTable>()
    private val specTypeIds = mutableMapOf<String, Int>()
    private val stdlibListNames = setOf("List", "MutableList")
    private val stdlibSetNames = setOf("Set", "MutableSet")
    private val stdlibMapNames = setOf("Map", "MutableMap")

    /** Declared parameter types per function (user functions + bridge externs), for call-site coercion. */
    private val funcReturnTypes = mutableMapOf<String, IrType>()
    private val funcParamTypes = mutableMapOf<String, List<IrType>>()

    /**
     * Function → indices of `x!` parameters passed as the address of the
     * caller's storage. A value copy would lose an assignment, a scalar
     * update or an array's reallocation; a pack is already a pointer, so
     * writes through it reach the caller without this.
     */
    private val slotParams = mutableMapOf<String, Set<Int>>()

    private fun passesBySlot(type: IrType): Boolean = when (type) {
        is IrType.Integer, IrType.Bool, IrType.Char, IrType.Double, IrType.Float, IrType.String -> true
        is IrType.Array, is IrType.Map, is IrType.Set -> true
        else -> false
    }
    private val nativeExterns = mutableMapOf<String, IrTopLevel.Extern>()

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

    /** The closure representation: a function pointer plus its captured environment. */
    private val CLOSURE_TYPE_DEFINITION = "%azora.closure = type { i8*, i8* }"

    /** Outlined async/task helper functions emitted after source functions. */
    private val deferredFunctions = mutableListOf<String>()

    /** Function-local structured task scopes; spawned tasks attach to the innermost scope. */
    private val taskScopeStack = ArrayDeque<String>()


    private var taskContextCounter = 0

    /** True once the current basic block has a terminator. */
    private var terminated = false

    /** Declared return type of the function currently being emitted (null for `main`/tests). */
    private var currentReturnType: IrType? = null

    /** True while emitting `main` (which is always lowered as `i32 @main`). */
    private var currentIsMain = false

    /** True when the function currently being emitted declares `T ?! E`. */
    private var currentIsFailable = false

    /** Effects visible at the current point in the function, for static native lowering. */
    private val activeReactiveEffects = mutableListOf<IrStmt.Effect>()

    /** Globals holding each conditional effect's last answer; see [emitReactiveEffect]. */
    private val reactiveEffectEdges = linkedSetOf<String>()
    private var emittingReactiveEffect = false
    private data class LazyLocal(
        val type: IrType,
        val initializer: IrExpr,
        val dependencies: Set<String>,
        val flagName: String,
    )
    private val lazyLocals = mutableMapOf<String, LazyLocal>()
    private data class ReactiveStorage(val valueGlobal: String, val initGlobal: String, val type: IrType)
    private val reactiveStorage = mutableMapOf<Pair<String, String>, ReactiveStorage>()
    private var currentFunctionName = ""

    /** Names of functions that can fail; a call to one needs an error check. */
    private val failableFunctions = mutableSetOf<String>()

    /**
     * Labels of the enclosing error handlers, innermost last.
     *
     * A failable call branches to the top of this stack. When it is empty the
     * error either propagates (the caller is itself failable) or aborts, which
     * is what an unhandled error means.
     */
    private val errorHandlers = mutableListOf<String>()

    /** True once anything in this module reads or writes the error slot. */
    private var usesErrorSlot = false

    // Which libc declarations / runtime helpers are referenced.
    private var usesPuts = false
    private var usesPrintf = false
    private var usesAbort = false
    private var usesMalloc = false
    private var usesStrlen = false
    private var usesUsleep = false
    private var usesStrcpy = false
    private var usesStrcat = false
    private var usesStrcmp = false
    private var usesStrncmp = false
    private var usesStrstr = false
    private var usesIsCheck = false
    private var usesMemcpy = false
    private var usesStackSave = false
    // std.os / std.filesystem native support.
    private var usesGetenv = false
    private var usesAccess = false
    private var usesStdio = false
    private var usesSystem = false
    private var usesGetpid = false
    /** Referenced compiler string/array bridge intrinsics; defined as runtime helpers. */
    /** Bare intrinsic name -> the mangled symbol the program called it by. */
    private val neededIntrinsics = linkedMapOf<String, String>()
    private val stringIntrinsics = setOf(
        "stringLength", "charAt", "ord", "chr", "isDigit", "isAlpha", "substring",
        "startsWith", "endsWith", "contains", "indexOf", "toUpper", "toLower", "trim",
        "replace", "split", "toChars", "fromChars",
    )

    /**
     * The string intrinsic [symbol] denotes, or null.
     *
     * These are declared in `scope std`, so the symbol reaching IR is
     * `__std_substring` while the table above - and the bodies emitted by
     * [buildStringIntrinsics] - are keyed by the bare name. Comparing the two
     * verbatim silently matched nothing: every call was declared and none was
     * defined, so any native build touching a string failed at link time.
     */
    private fun stringIntrinsicOf(symbol: String): String? =
        stringIntrinsics.firstOrNull { symbolDenotes(symbol, it) }
    private var usesSnprintf = false
    private var usesStrConcat = false
    private var usesStrRepeat = false
    private var usesStrHash = false
    private var usesArrayGrow = false
    private var usesMapGrow = false
    private var usesIntToStr = false
    private var usesUintToStr = false
    private var usesDoubleToStr = false
    private var usesTrunc = false
    private var usesCharToStr = false
    private var usesFree = false
    private var usesCalloc = false
    /** `alloc .() * n` reached codegen: emit the zeroing buffer allocator. */
    private var usesZeroedAlloc = false
    private var usesAllocatorRuntime = false
    private var usesTaskRuntime = false
    private var usesIndexFail = false


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
        usesUsleep = false
        usesStrncmp = false
        usesStrstr = false
        neededIntrinsics.clear()
        usesStrcpy = false
        usesStrcat = false
        usesStrcmp = false
        usesMemcpy = false
        usesStackSave = false
        usesGetenv = false
        usesAccess = false
        usesStdio = false
        usesSystem = false
        usesGetpid = false
        usesSnprintf = false
        usesStrConcat = false
        usesStrRepeat = false
        usesStrHash = false
        usesArrayGrow = false
        usesMapGrow = false
        usesIntToStr = false
        usesUintToStr = false
        usesDoubleToStr = false
        usesTrunc = false
        usesCharToStr = false
        usesFree = false
        usesAllocatorRuntime = false
        usesCalloc = false
        usesZeroedAlloc = false
        usesTaskRuntime = false
        usesIndexFail = false
        loopStack.clear()
        taskScopeStack.clear()
        taskContextCounter = 0

        structDefs.clear()
        funcParamTypes.clear()
        funcReturnTypes.clear()
        nativeExterns.clear()
        failableFunctions.clear()
        errorHandlers.clear()
        usesErrorSlot = false
        globalVars.clear()
        dynamicGlobalInitializers.clear()
        reactiveStorage.clear()
        reactiveEffectEdges.clear()
        lateTypeDefinitions.clear()
        deferredFunctions.clear()
        ownershipDrops.clear()
        ownedSlots = program.ownedSlots
        constructionOwners.clear()
        usesMemset = false
        for (item in program.items.filterIsInstance<IrTopLevel.Struct>()) {
            structDefs[item.name] = item
        }

        // Spec dynamic dispatch: index the tables and assign each concrete
        // implementer a stable non-zero type id (used as the fat-pointer tag).
        specDispatch.clear()
        specTypeIds.clear()
        for (t in program.specTables) specDispatch[t.specName] = t
        var nextTypeId = 1
        for (t in program.specTables) {
            for (impl in t.impls) {
                if (impl.typeName !in specTypeIds) specTypeIds[impl.typeName] = nextTypeId++
            }
        }
        for (t in specDispatch.values) {
            for (m in t.methods) deferredFunctions += renderSpecDispatcher(t, m)
        }
        // Spec implementations are also called through dispatch tables, which
        // forward plain values, so they keep the value convention.
        val dispatched = specDispatch.values.flatMap { table -> table.impls.flatMap { it.methodFuncs.values } }.toSet()
        for (item in program.items) {
            when (item) {
                is IrTopLevel.Func -> {
                    funcParamTypes[item.function.name] = item.function.params.map { it.second }
                    if (!item.function.isTask && item.function.name !in dispatched && item.function.name != "main") {
                        item.function.exclusiveParams
                            .filter { it < item.function.params.size && passesBySlot(item.function.params[it].second) }
                            .toSet()
                            .takeIf { it.isNotEmpty() }
                            ?.let { slotParams[item.function.name] = it }
                    }
                    // A task's public symbol is its spawner, not its payload body.
                    funcReturnTypes[item.function.name] =
                        if (item.function.isTask && item.function.name != "main") IrType.Task(item.function.returnType)
                        else item.function.returnType
                    if (item.function.isFailable) failableFunctions += item.function.name
                    if (item.function.isTask && item.function.name != "main") {
                        usesTaskRuntime = true
                    }
                }
                is IrTopLevel.Extern -> {
                    funcParamTypes[item.name] = item.params.map { it.second }
                    funcReturnTypes[item.name] = item.returnType
                    nativeExterns[item.name] = item
                }
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

        for (item in program.items.filterIsInstance<IrTopLevel.Func>()) {
            collectReactiveStorage(item.function.name, item.function.body)
        }

        // Struct type definitions. Structs are heap-allocated and passed by
        // pointer (`%struct.T*`); construction, member reads and member writes
        // lower to malloc + getelementptr below.
        val structs = program.items.filterIsInstance<IrTopLevel.Struct>()
        if (structs.isNotEmpty()) {
            body.appendLine("; Struct types")
            for (s in structs) {
                // A union is one slot as wide as its widest member; every member
                // addresses that same slot through a bitcast (see emitFieldPtr).
                val fieldTypes = if (s.isUnion) {
                    mapType(widestMember(s))
                } else {
                    s.fields.joinToString(", ") { mapType(it.type) }
                }
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

        if (reactiveStorage.isNotEmpty()) {
            body.appendLine("; Reactive owner storage")
            for (storage in reactiveStorage.values.distinctBy { it.valueGlobal }) {
                body.appendLine("@${storage.valueGlobal} = internal global ${mapType(storage.type)} zeroinitializer")
                body.appendLine("@${storage.initGlobal} = internal global i1 false")
            }
            body.appendLine()
        }

        if (reactiveEffectEdges.isNotEmpty()) {
            // False to begin with, so a condition that is already true the first
            // time anyone looks still counts as having become true.
            body.appendLine("; Conditional effect edges")
            for (edge in reactiveEffectEdges) {
                body.appendLine("${edge} = internal global i1 false")
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

        // Functions (skip runtime intrinsics - their stdlib bodies are dead
        // placeholders; each call is intercepted and lowered to native code).
        for (item in program.items.filterIsInstance<IrTopLevel.Func>()) {
            if (item.function.name in org.azora.lang.semantic.CtfeEvaluator.RUNTIME_INTRINSICS) continue
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

        // Extern (`bridge`) function declarations (intrinsics we define ourselves
        // are emitted as runtime helpers below, so skip their `declare`).
        val externs = program.items.filterIsInstance<IrTopLevel.Extern>()
        val mathIntrinsicsUsed = externs.mapNotNullTo(linkedSetOf()) { mathIntrinsicOf(it) }
        for (libm in mathIntrinsicsUsed) {
            val params = List(LIBM_INTRINSICS.getValue(libm)) { "double" }.joinToString(", ")
            body.appendLine("declare double @$libm($params)")
        }
        for (item in externs) {
            if (stringIntrinsicOf(item.name) != null) continue
            // `scope std { bridge func sqrt(…) }` mangles to `__std_math_sqrt`,
            // a symbol nothing provides. The compiler supplies these itself rather
            // than linking a hand-written C shim: declare the libm function and
            // define the mangled name as a call to it.
            val libm = mathIntrinsicOf(item)
            if (libm != null) {
                // An unmangled bridge already names the external symbol. A
                // wrapper with that same name both redefines and calls itself.
                if (item.name == libm) continue
                val ps = item.params.mapIndexed { i, (_, t) -> "${mapType(t)} %a$i" }
                val args = item.params.mapIndexed { i, (_, t) -> "${mapType(t)} %a$i" }
                val ret = abiReturnType(item.returnType)
                body.appendLine("define $ret @${item.name}(${ps.joinToString(", ")}) {")
                body.appendLine("entry:")
                body.appendLine("  %r = call $ret @$libm(${args.joinToString(", ")})")
                body.appendLine("  ret $ret %r")
                body.appendLine("}")
                continue
            }
            // `std.os` / `std.filesystem` bridges: defined here for the same
            // reason as the libm and string intrinsics above.
            val osBody = osIntrinsicBody(item)
            if (osBody != null) {
                body.append(osBody)
                continue
            }
            // A bridge the compiler lowers where it is called - `println` becomes
            // `puts` - is never called by its own name, and a declaration for it
            // would name a symbol nothing provides.
            if (!referencesSymbol(body, item.name)) continue
            NativeAbi.checkSignature(item)
            val params = item.params.joinToString(", ") { (_, t) -> nativeParameterType(t) }
            body.appendLine("declare ${nativeReturnType(item.returnType)} @${item.name}($params)")
        }


        // Tests
        for (item in program.items.filterIsInstance<IrTopLevel.Test>()) {
            out.clear()
            emitTestFunction(item)
            body.append(out)
            body.appendLine()
        }

        // A test body can contain a lambda too, and emitting it queues the
        // closure's body the same way a function's does. Draining only before
        // the tests would leave those queued bodies unwritten, and the module
        // would reference a `@__azora_lambda_body_N` that is never defined.
        while (deferredIndex < deferredFunctions.size) {
            body.append(deferredFunctions[deferredIndex])
            body.appendLine()
            deferredIndex++
        }

        // Runtime helpers (appended after the body so string-constant ids are stable).
        if (usesTaskRuntime) usesAllocatorRuntime = true
        val helpers = buildRuntimeHelpers()

        // The error slot for `T ?! E`. One module-level pointer: null when no
        // error is pending, otherwise the raised variant's name.
        val errSlot = StringBuilder()
        if (usesErrorSlot) {
            errSlot.appendLine("; Error transport")
            errSlot.appendLine("@__azora_err = global i8* null")
        }

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
        if (usesTrunc) line("declare double @trunc(double)")
        if (usesAbort) {
            line("declare void @abort() noreturn")
            line("declare i32 @fflush(i8*)")
        }
        if (usesMalloc) line("declare i8* @malloc(i64)")
        if (usesFree) line("declare void @free(i8*)")
        if (usesCalloc) line("declare i8* @calloc(i64, i64)")
        if (usesStrlen) line("declare i64 @strlen(i8*)")
        if (usesUsleep) line("declare i32 @usleep(i32)")
        if (usesStrcpy) line("declare i8* @strcpy(i8*, i8*)")
        if (usesStrcat) line("declare i8* @strcat(i8*, i8*)")
        if (usesStrcmp) line("declare i32 @strcmp(i8*, i8*)")
        if (usesStrncmp) line("declare i32 @strncmp(i8*, i8*, i64)")
        if (usesStrstr) line("declare i8* @strstr(i8*, i8*)")
        if (usesMemcpy) line("declare i8* @memcpy(i8*, i8*, i64)")
        if (usesStackSave) {
            line("declare i8* @llvm.stacksave()")
            line("declare void @llvm.stackrestore(i8*)")
        }
        if (usesMemset) line("declare i8* @memset(i8*, i32, i64)")
        if (usesGetenv) line("declare i8* @getenv(i8*)")
        if (usesAccess) line("declare i32 @access(i8*, i32)")
        if (usesSystem) line("declare i32 @system(i8*)")
        if (usesGetpid) line("declare i32 @getpid()")
        if (usesStdio) {
            line("declare i8* @fopen(i8*, i8*)")
            line("declare i32 @fclose(i8*)")
            line("declare i32 @fseek(i8*, i64, i32)")
            line("declare i64 @ftell(i8*)")
            line("declare i64 @fread(i8*, i64, i64, i8*)")
            line("declare i32 @fputs(i8*, i8*)")
        }
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
        for (typeDef in lateTypeDefinitions) line(typeDef)
        if (usesTaskRuntime || lateTypeDefinitions.isNotEmpty()) line("")
        out.append(body)
        if (helpers.isNotEmpty()) out.append(helpers)
        out.append(errSlot)
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
            IrExpr.UnitLiteral -> "0"
            // A 128-bit literal is wider than a Long, so its digits travel
            // with it and are what LLVM is given: `i128` holds them.
            is IrExpr.IntLiteral -> initializer.text ?: "${initializer.value}"
            is IrExpr.DoubleLiteral -> floatConst(initializer.value, type, initializer.text)
            is IrExpr.CharLiteral -> "${initializer.value.code}"
            is IrExpr.BoolLiteral -> if (initializer.value) "1" else "0"
            is IrExpr.StringLiteral -> {
                val ref = addStringConstant(initializer.value)
                "getelementptr ([${ref.byteLen} x i8], [${ref.byteLen} x i8]* ${ref.name}, i64 0, i64 0)"
            }
            is IrExpr.EnumLiteral -> {
                val ref = addStringConstant(initializer.variant)
                "getelementptr ([${ref.byteLen} x i8], [${ref.byteLen} x i8]* ${ref.name}, i64 0, i64 0)"
            }
            else -> {
                dynamicGlobalInitializers += DynamicGlobalInitializer(
                    name = name,
                    type = type,
                    initializer = initializer,
                    threadLocal = name.startsWith("__tl_"),
                )
                "zeroinitializer"
            }
        }
        val storage = if (name.startsWith("__tl_")) "thread_local global" else "global"
        line("@$name = $storage $llvmType $value")
    }

    private fun emitGlobalInitializerFunction(name: String, initializers: List<DynamicGlobalInitializer>) {
        localVars.clear()
        allocaSlots.clear()
        loopStack.clear()
        taskScopeStack.clear()
        terminated = false
        currentBlock = "entry"
        currentReturnType = IrType.Unit
        currentIsMain = false

        line("define void @$name() {")
        line("entry:")
        for ((globalName, type, initializer) in initializers) {
            val value = emitInitializerForDeclaredType(type, initializer)
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
        prepareDefers(test.body)
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
                is IrStmt.VarDecl -> {
                    if (stmt.type != IrType.Nothing) slots.add(stmt.name to mapType(stmt.type))
                    if (stmt.lazy) slots.add(lazyFlagName(stmt.name) to "i1")
                }
                is IrStmt.FinDecl -> {
                    if (stmt.type != IrType.Nothing) slots.add(stmt.name to mapType(stmt.type))
                    if (stmt.lazy) slots.add(lazyFlagName(stmt.name) to "i1")
                }
                is IrStmt.LetDecl -> {
                    if (stmt.type != IrType.Nothing) slots.add(stmt.name to mapType(stmt.type))
                    if (stmt.lazy) slots.add(lazyFlagName(stmt.name) to "i1")
                }
                is IrStmt.If -> {
                    collectLocalSlots(stmt.thenBranch, slots)
                    stmt.elseBranch?.let { collectLocalSlots(it, slots) }
                }
                is IrStmt.Scope -> collectLocalSlots(stmt.body, slots)
                is IrStmt.While -> collectLocalSlots(stmt.body, slots)
                is IrStmt.For -> {
                    slots.add(stmt.counter to mapType(stmt.start.type))
                    stmt.indexName?.let { slots.add(it to mapType(IrType.Int)) }
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
                    stmt.indexName?.let { slots.add(it to mapType(IrType.Int)) }
                    collectLocalSlots(stmt.body, slots)
                }
                is IrStmt.Loop -> collectLocalSlots(stmt.body, slots)
                is IrStmt.When -> {
                    stmt.branches.forEach { branch ->
                        // Slot-pattern payload bindings become locals holding the
                        // unboxed payloads (`when r { Result.Ok(v) -> … }`).
                        branch.patterns.filterIsInstance<IrExpr.SlotPattern>().forEach { sp ->
                            sp.bindings.forEachIndexed { i, name ->
                                slots.add(name to mapType(sp.bindingTypes.getOrElse(i) { IrType.Any }))
                            }
                        }
                        collectLocalSlots(branch.body, slots)
                    }
                    stmt.elseBranch?.let { collectLocalSlots(it, slots) }
                }
                is IrStmt.Defer -> collectLocalSlots(stmt.body, slots)
                is IrStmt.Try -> {
                    collectLocalSlots(stmt.body, slots)
                    // The caught error is held as the slot holds it: erased.
                    stmt.catchName?.let { slots.add(it to mapType(IrType.Any)) }
                    stmt.catchBody?.let { collectLocalSlots(it, slots) }
                }
                is IrStmt.Effect -> collectLocalSlots(stmt.body, slots)
                else -> {}
            }
        }
    }

    private fun emitFunction(func: IrFunction, reactiveOwnerName: String = func.name) {
        localVars.clear()
        loopStack.clear()
        taskScopeStack.clear()
        tmpCounter = 0
        labelCounter = 0
        terminated = false
        currentBlock = "entry"
        currentFunctionName = reactiveOwnerName
        activeReactiveEffects.clear()
        emittingReactiveEffect = false
        lazyLocals.clear()

        // The program entry point is always emitted as `i32 @main` returning 0,
        // so the produced executable / `lli` run yields a clean exit code.
        val isMain = func.name == "main"
        currentIsMain = isMain
        currentIsFailable = func.isFailable
        errorHandlers.clear()
        currentReturnType = if (isMain) null else func.returnType
        val retType = if (isMain) "i32" else abiReturnType(func.returnType)

        val bySlot = slotParams[func.name].orEmpty()
        val params = func.params.withIndex().joinToString(", ") { (index, param) ->
            val (name, type) = param
            if (index in bySlot) "${mapType(type)}* %arg.$name" else "${mapType(type)} %arg.$name"
        }

        line("define $retType @${func.name}($params) {")
        line("entry:")

        // Spill parameters to the stack so they can be referenced (and reassigned).
        // A parameter passed by slot already is the caller's storage.
        for ((index, param) in func.params.withIndex()) {
            val (name, type) = param
            val t = mapType(type)
            if (index in bySlot) {
                localVars[name] = "%arg.$name" to t
                continue
            }
            val alloca = nextTmp()
            emit("  $alloca = alloca $t")
            emit("  store $t %arg.$name, $t* $alloca")
            localVars[name] = alloca to t
        }

        // All local allocas live in the entry block (never inside loops).
        emitEntryAllocas(func.body)
        prepareDefers(func.body)
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
            func.returnType == IrType.Nothing -> {
                emitFunctionExitCleanup()
                emitTerminator("  unreachable")
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
        emitFunction(func.copy(name = bodyName, isTask = false), reactiveOwnerName = func.name)
        line("")
        emitTaskEntryWrapper(func, bodyName)
        line("")
        emitTaskPublicSpawner(func)
    }

    private fun emitTaskEntryWrapper(func: IrFunction, bodyName: String) {
        localVars.clear()
        loopStack.clear()
        taskScopeStack.clear()
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
        } else if (func.returnType == IrType.Nothing) {
            emit("  call void @$bodyName($args)")
            emitTerminator("  unreachable")
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
            // The task entry releases its context explicitly. Keep runtime-owned
            // bookkeeping outside a caller's active scope to avoid double frees.
            emit("  $raw = call i8* @__azora_alloc_raw(i64 $size)")
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

    /**
     * What every exit of the function runs before it returns: its `defer`s,
     * then its task scopes. [failing] is an exit by error, which also runs the
     * `error defer`s and lets a `rescue` swallow the error.
     */
    private fun emitFunctionExitCleanup(failing: Boolean = false) {
        if (terminated) return
        emitAllTaskScopeCleanups()
        emitDeferred(failing)
    }

    /** A `defer` of the function being emitted, and the counter its registrations raise. */
    private class DeferSlot(val stmt: IrStmt.Defer, val counter: String)

    /** The `defer`s of the function being emitted, in source order. */
    private val deferSlots = mutableListOf<DeferSlot>()

    /**
     * True while deferred bodies are emitted at an exit. A failable call inside
     * one has an exit of its own, which must not emit the deferred bodies again.
     */
    private var emittingDefers = false

    /**
     * Gives each `defer` in [body] a counter, zeroed on entry.
     *
     * A `defer` runs when its function exits, once for every time it was
     * reached, the last reached first: reaching one raises its counter, and
     * every exit drains the counters in reverse source order. That is exact
     * unless two `defer`s inside one loop interleave, which drains each in turn.
     */
    private fun prepareDefers(body: List<IrStmt>) {
        deferSlots.clear()
        emittingDefers = false
        val found = mutableListOf<IrStmt.Defer>()
        collectDefers(body, found)
        for (stmt in found) {
            val counter = "%defer.${deferSlots.size}"
            emit("  $counter = alloca i32")
            emit("  store i32 0, i32* $counter")
            deferSlots += DeferSlot(stmt, counter)
        }
    }

    private fun collectDefers(stmts: List<IrStmt>, into: MutableList<IrStmt.Defer>) {
        for (stmt in stmts) when (stmt) {
            is IrStmt.Defer -> into += stmt
            is IrStmt.If -> { collectDefers(stmt.thenBranch, into); stmt.elseBranch?.let { collectDefers(it, into) } }
            is IrStmt.Scope -> collectDefers(stmt.body, into)
            is IrStmt.While -> collectDefers(stmt.body, into)
            is IrStmt.For -> collectDefers(stmt.body, into)
            is IrStmt.ForEach -> collectDefers(stmt.body, into)
            is IrStmt.Loop -> collectDefers(stmt.body, into)
            is IrStmt.When -> {
                stmt.branches.forEach { collectDefers(it.body, into) }
                stmt.elseBranch?.let { collectDefers(it, into) }
            }
            is IrStmt.Try -> { collectDefers(stmt.body, into); stmt.catchBody?.let { collectDefers(it, into) } }
            else -> {}
        }
    }

    /** Reaching a `defer` registers it once more. */
    private fun emitDeferRegistration(stmt: IrStmt.Defer) {
        val slot = deferSlots.firstOrNull { it.stmt === stmt }
            ?: error("LLVM cannot lower a 'defer' outside a function body yet")
        val count = nextTmp()
        emit("  $count = load i32, i32* ${slot.counter}")
        val raised = nextTmp()
        emit("  $raised = add i32 $count, 1")
        emit("  store i32 $raised, i32* ${slot.counter}")
    }

    private fun emitDeferred(failing: Boolean) {
        if (emittingDefers || deferSlots.isEmpty()) return
        val exitLocals = localVars.toMap()
        // Every defer is emitted at every exit, including exits preceding its
        // registration. Its guard prevents execution there, but the body still
        // needs valid addresses for locals declared later in source order.
        for ((key, address) in allocaSlots) {
            if (key.first !in localVars) localVars[key.first] = address to key.second
        }
        emittingDefers = true
        try {
            for (slot in deferSlots.asReversed()) {
                if (slot.stmt.onFail && !failing) continue
                val check = nextLabel("defer.check")
                val run = nextLabel("defer.run")
                val done = nextLabel("defer.done")
                emitTerminator("  br label %$check")
                startBlock(check)
                val count = nextTmp()
                emit("  $count = load i32, i32* ${slot.counter}")
                val pending = nextTmp()
                emit("  $pending = icmp sgt i32 $count, 0")
                emitTerminator("  br i1 $pending, label %$run, label %$done")
                startBlock(run)
                val drained = nextTmp()
                emit("  $drained = sub i32 $count, 1")
                emit("  store i32 $drained, i32* ${slot.counter}")
                emitStmts(slot.stmt.body)
                // `rescue`: the error it ran for is handled, so the caller's
                // check sees none and takes the function's zero value.
                if (failing && slot.stmt.suppress) {
                    usesErrorSlot = true
                    emit("  store i8* null, i8** @__azora_err")
                }
                if (!terminated) emitTerminator("  br label %$check")
                startBlock(done)
            }
        } finally {
            emittingDefers = false
            localVars.clear()
            localVars.putAll(exitLocals)
        }
    }

    private fun emitAllTaskScopeCleanups() {
        if (!usesTaskRuntime) return
        for (scope in taskScopeStack.asReversed()) {
            emit("  call void @__azora_scope_join_all(%azora.scope* $scope)")
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
            is IrStmt.VarDecl -> if (stmt.reactiveLifetime != null) emitReactiveDecl(stmt.name, stmt.type, stmt.initializer)
                else emitLocalDecl(stmt.name, stmt.type, stmt.initializer, stmt.lazy)
            is IrStmt.FinDecl -> if (stmt.reactiveLifetime != null) emitReactiveDecl(stmt.name, stmt.type, stmt.initializer)
                else emitLocalDecl(stmt.name, stmt.type, stmt.initializer, stmt.lazy)
            is IrStmt.LetDecl -> if (stmt.reactiveLifetime != null) emitReactiveDecl(stmt.name, stmt.type, stmt.initializer)
                else emitLocalDecl(stmt.name, stmt.type, stmt.initializer, stmt.lazy)
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
                if (!emittingReactiveEffect) {
                    val changed = invalidateLazyDependents(stmt.name) + stmt.name
                    for (effect in activeReactiveEffects.filter { effect ->
                        effect.dependencies.any { it in changed }
                    }) {
                        emitReactiveEffect(effect)
                    }
                }
            }
            is IrStmt.Return -> {
                if (stmt.value != null) {
                    val declared = currentReturnType
                    val raw = emitExpr(stmt.value)
                    emitFunctionExitCleanup()
                    if (declared == IrType.Nothing) {
                        // A genuine Nothing expression has already terminated
                        // this block; never manufacture a return value for it.
                        emitTerminator("  unreachable")
                    } else if (declared != null && declared != IrType.Unit) {
                        val value = coerceNumeric(raw, stmt.value.type, declared)
                        emitTerminator("  ret ${mapType(declared)} $value")
                    } else if (stmt.value.type == IrType.Unit) {
                        // `void` carries no value, so naming one produces
                        // `ret void void` - which LLVM rejects. The expression is
                        // still emitted above for its effect; only the return of
                        // it is nothing.
                        emitTerminator("  ret void")
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
            is IrStmt.Scope -> emitScope(stmt)
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
            is IrStmt.Exchange -> emitExchange(stmt)
            is IrStmt.IndexAssign -> emitIndexAssign(stmt)
            is IrStmt.MemberAssign -> emitMemberAssign(stmt)
            is IrStmt.Defer -> emitDeferRegistration(stmt)
            is IrStmt.Effect -> {
                activeReactiveEffects.add(stmt)
                emitReactiveEffect(stmt)
            }
            is IrStmt.Yield -> error("LLVM cannot lower 'yield' yet; generators run on the interpreter")
            is IrStmt.ForEach -> emitForEach(stmt)
            is IrStmt.Throw -> emitThrow(stmt)
            is IrStmt.Try -> emitTry(stmt)
        }
    }

    private fun emitReactiveEffect(effect: IrStmt.Effect) {
        val previous = emittingReactiveEffect
        emittingReactiveEffect = true
        try {
            val condition = effect.condition
            if (condition == null) {
                emitStmts(effect.body)
                return
            }

            // A conditional effect runs on the transition to true, so the
            // previous answer has to outlive the call. It lives in a module
            // global for the same reason a `remember` cell does - the owner's
            // state is not the invocation's.
            val edge = "@__azora_effect_edge_${effect.id}"
            val now = emitExpr(condition)
            val was = nextTmp()
            emit("  $was = load i1, i1* $edge")
            emit("  store i1 $now, i1* $edge")
            val notWas = nextTmp()
            emit("  $notWas = xor i1 $was, true")
            val rising = nextTmp()
            emit("  $rising = and i1 $now, $notWas")

            val bodyLabel = nextLabel("effect_fire")
            val endLabel = nextLabel("effect_done")
            emitTerminator("  br i1 $rising, label %$bodyLabel, label %$endLabel")
            startBlock(bodyLabel)
            emitStmts(effect.body)
            emitTerminator("  br label %$endLabel")
            startBlock(endLabel)
        } finally {
            emittingReactiveEffect = previous
        }
    }

    /**
     * Raises an error: record it, then leave the function by the normal path.
     *
     * `T ?! E` keeps the success type, so there is no room in the return value
     * for a failure. The error travels in a module-level slot instead and the
     * function returns its zero value - a caller that can observe the error
     * checks the slot immediately after the call and never looks at that value.
     */
    private fun emitThrow(stmt: IrStmt.Throw) {
        usesErrorSlot = true
        val value = emitExpr(stmt.value)
        val slot = nextTmp()
        emit("  $slot = bitcast ${mapType(stmt.value.type)} $value to i8*")
        emit("  store i8* $slot, i8** @__azora_err")
        // A `throw` inside a `try` is caught here rather than unwinding.
        if (errorHandlers.isNotEmpty()) {
            emitTerminator("  br label %${errorHandlers.last()}")
            return
        }
        emitFunctionExitCleanup(failing = true)
        emitReturnZero()
    }

    /**
     * `a ?? b` - what `a` holds, or `b` when it holds nothing.
     *
     * `a` is evaluated once and `b` only on the null path; both arms meet in a
     * phi. A left side that cannot be null (its slot is not a pointer) is its
     * own answer, and `b` is never evaluated.
     */
    private fun emitNullCoalesce(expr: IrExpr.Call): String {
        val (left, right) = expr.args
        val held = emitExpr(left)
        val slot = mapType(left.type)
        if (!slot.endsWith("*")) return coerceNumeric(held, left.type, expr.type)
        val isNull = nextTmp()
        emit("  $isNull = icmp eq $slot $held, null")
        val present = nextLabel("coalesce.value")
        val absent = nextLabel("coalesce.fallback")
        val done = nextLabel("coalesce.done")
        emitTerminator("  br i1 $isNull, label %$absent, label %$present")

        startBlock(present)
        val value = coerceNumeric(held, left.type, expr.type)
        val valueBlock = currentBlock
        emitTerminator("  br label %$done")

        startBlock(absent)
        val fallback = coerceNumeric(emitExpr(right), right.type, expr.type)
        val fallbackBlock = currentBlock
        emitTerminator("  br label %$done")

        startBlock(done)
        if (expr.type == IrType.Unit) return "0"
        val result = nextTmp()
        emit("  $result = phi ${mapType(expr.type)} [ $value, %$valueBlock ], [ $fallback, %$fallbackBlock ]")
        return result
    }

    /** True when the IR text in [module] uses the global symbol `@[symbol]`. */
    private fun referencesSymbol(module: CharSequence, symbol: String): Boolean {
        val spelled = "@$symbol"
        var at = module.indexOf(spelled)
        while (at >= 0) {
            val next = module.getOrNull(at + spelled.length)
            if (next == null || !(next.isLetterOrDigit() || next in "._$-")) return true
            at = module.indexOf(spelled, at + 1)
        }
        return false
    }

    /** Returns the current function's zero value, for a failure exit. */
    private fun emitReturnZero() {
        if (currentIsMain) {
            emitTerminator("  ret i32 0")
            return
        }
        val declared = currentReturnType
        if (declared == null || declared == IrType.Unit) {
            emitTerminator("  ret void")
            return
        }
        if (declared == IrType.Nothing) {
            emitTerminator("  unreachable")
            return
        }
        emitTerminator("  ret ${mapType(declared)} ${defaultValue(declared)}")
    }

    /**
     * `try { … } catch { … }`.
     *
     * The body runs with a handler pushed, so every failable call and every
     * `throw` inside it branches to the catch block. The handler binds the
     * caught error, then clears the slot: an error that has been handled must
     * not still be pending when the next call checks.
     */
    private fun emitTry(stmt: IrStmt.Try) {
        val catchBody = stmt.catchBody
        if (catchBody == null) {
            emitStmts(stmt.body)
            return
        }
        usesErrorSlot = true
        val handler = nextLabel("catch")
        val done = nextLabel("try.done")

        errorHandlers.add(handler)
        emitStmts(stmt.body)
        errorHandlers.removeAt(errorHandlers.size - 1)
        emitTerminator("  br label %$done")

        startBlock(handler)
        stmt.catchName?.let { name ->
            val type = mapType(IrType.Any)
            val slot = allocaSlots[name to type] ?: error("the caught error '$name' was not allocated")
            val raised = nextTmp()
            emit("  $raised = load i8*, i8** @__azora_err")
            emit("  store $type $raised, $type* $slot")
            localVars[name] = slot to type
        }
        emit("  store i8* null, i8** @__azora_err")
        emitStmts(catchBody)
        emitTerminator("  br label %$done")

        startBlock(done)
    }

    /**
     * Checks the error slot after a call that could have failed.
     *
     * With a handler in scope the error is caught; otherwise it propagates when
     * the current function is itself failable, and aborts when it is not -
     * which is what an error nobody can observe means.
     */
    private fun emitErrorCheck() {
        usesErrorSlot = true
        val raised = nextTmp()
        emit("  $raised = load i8*, i8** @__azora_err")
        val failed = nextTmp()
        emit("  $failed = icmp ne i8* $raised, null")
        val onError = nextLabel("err")
        val onOk = nextLabel("ok")
        emitTerminator("  br i1 $failed, label %$onError, label %$onOk")

        startBlock(onError)
        emitConstructionCleanup()
        if (errorHandlers.isNotEmpty()) {
            emitTerminator("  br label %${errorHandlers.last()}")
        } else if (currentIsFailable) {
            // Leave the slot set: the caller's own check sees the same error.
            emitFunctionExitCleanup(failing = true)
            emitReturnZero()
        } else {
            usesAbort = true
            emit("  call void @__azora_abort()")
            emitTerminator("  unreachable")
        }

        startBlock(onOk)
    }

    /**
     * `expr catch fallback` - the expression's value, or the fallback if it failed.
     *
     * The primary is emitted under its own handler so a failure inside it lands
     * on the fallback rather than escaping. Both arms meet in a phi, so the
     * result is a single value whichever way it went.
     */
    private fun emitCatchExpr(expr: IrExpr.CatchExpr): String {
        usesErrorSlot = true
        val handler = nextLabel("catch.expr")
        val done = nextLabel("catch.done")
        val type = mapType(expr.type)

        errorHandlers.add(handler)
        val primary = emitExpr(expr.expr)
        errorHandlers.removeAt(errorHandlers.size - 1)
        val primaryValue = coerceNumeric(primary, expr.expr.type, expr.type)
        val primaryBlock = currentBlock
        emitTerminator("  br label %$done")

        startBlock(handler)
        emit("  store i8* null, i8** @__azora_err")
        val fallback = emitExpr(expr.fallback)
        val fallbackValue = coerceNumeric(fallback, expr.fallback.type, expr.type)
        val fallbackBlock = currentBlock
        emitTerminator("  br label %$done")

        startBlock(done)
        if (expr.type == IrType.Unit) return "0"
        val result = nextTmp()
        emit("  $result = phi $type [ $primaryValue, %$primaryBlock ], [ $fallbackValue, %$fallbackBlock ]")
        return result
    }

    private fun emitScope(stmt: IrStmt.Scope) {
        emitStmts(stmt.body)
    }

    private fun emitLocalDecl(name: String, type: IrType, initializer: IrExpr, lazy: Boolean = false) {
        if (type == IrType.Nothing) {
            // There can be no slot: evaluating the initializer must terminate.
            emitExpr(initializer)
            return
        }
        val t = mapType(type)
        // The slot was hoisted to the entry block (see emitEntryAllocas).
        val alloca = allocaSlots[name to t] ?: run {
            // Fallback for slots the collector didn't see (should not happen).
            val reg = "%loc${allocaCounter++}.${sanitizeName(name)}"
            emit("  $reg = alloca $t")
            allocaSlots[name to t] = reg
            reg
        }
        localVars[name] = alloca to t
        if (lazy) {
            val flagName = lazyFlagName(name)
            val flag = allocaSlots[flagName to "i1"]
                ?: error("lazy initialization flag for '$name' was not allocated")
            localVars[flagName] = flag to "i1"
            emit("  store i1 0, i1* $flag")
            val refs = linkedMapOf<String, IrType>()
            collectReferencedVars(initializer, refs)
            lazyLocals[name] = LazyLocal(type, initializer, refs.keys, flagName)
        } else {
            val value = emitInitializerForDeclaredType(type, initializer)
            emit("  store $t $value, $t* $alloca")
        }
    }

    private fun collectReactiveStorage(owner: String, stmts: List<IrStmt>) {
        for (stmt in stmts) {
            when (stmt) {
                is IrStmt.VarDecl -> if (stmt.reactiveLifetime != null) registerReactiveStorage(owner, stmt.name, stmt.type)
                is IrStmt.FinDecl -> if (stmt.reactiveLifetime != null) registerReactiveStorage(owner, stmt.name, stmt.type)
                is IrStmt.LetDecl -> if (stmt.reactiveLifetime != null) registerReactiveStorage(owner, stmt.name, stmt.type)
                is IrStmt.If -> {
                    collectReactiveStorage(owner, stmt.thenBranch)
                    stmt.elseBranch?.let { collectReactiveStorage(owner, it) }
                }
                is IrStmt.Scope -> collectReactiveStorage(owner, stmt.body)
                is IrStmt.While -> collectReactiveStorage(owner, stmt.body)
                is IrStmt.For -> collectReactiveStorage(owner, stmt.body)
                is IrStmt.ForEach -> collectReactiveStorage(owner, stmt.body)
                is IrStmt.Loop -> collectReactiveStorage(owner, stmt.body)
                is IrStmt.When -> {
                    stmt.branches.forEach { collectReactiveStorage(owner, it.body) }
                    stmt.elseBranch?.let { collectReactiveStorage(owner, it) }
                }
                is IrStmt.Try -> {
                    collectReactiveStorage(owner, stmt.body)
                    stmt.catchBody?.let { collectReactiveStorage(owner, it) }
                }
                is IrStmt.Defer -> collectReactiveStorage(owner, stmt.body)
                is IrStmt.Effect -> {
                    // A conditional effect needs a global to remember its last
                    // answer, and globals are written before any body - so the
                    // edge has to be discovered here, not while emitting.
                    if (stmt.condition != null) {
                        reactiveEffectEdges.add("@__azora_effect_edge_${stmt.id}")
                    }
                    collectReactiveStorage(owner, stmt.body)
                }
                else -> Unit
            }
            // A `remember` inside a block belongs to the owner that runs the
            // block, not to the closure: the closure is rebuilt every call, and
            // the state has to outlive it. Statements alone never reach one, so
            // the expressions are walked for the lambdas they carry.
            forEachStatementExpr(stmt) { collectReactiveStorageInExpr(owner, it) }
        }
    }

    /** Walks [expr] for lambda bodies, registering their reactive declarations. */
    private fun collectReactiveStorageInExpr(owner: String, expr: IrExpr) {
        when (expr) {
            is IrExpr.Lambda -> collectReactiveStorage(owner, expr.body)
            is IrExpr.Call -> {
                expr.args.forEach { collectReactiveStorageInExpr(owner, it) }
                expr.receiver?.let { collectReactiveStorageInExpr(owner, it) }
            }
            is IrExpr.MethodCall -> {
                collectReactiveStorageInExpr(owner, expr.target)
                expr.args.forEach { collectReactiveStorageInExpr(owner, it) }
            }
            is IrExpr.Binary -> {
                collectReactiveStorageInExpr(owner, expr.left)
                collectReactiveStorageInExpr(owner, expr.right)
            }
            is IrExpr.Unary -> collectReactiveStorageInExpr(owner, expr.operand)
            is IrExpr.IncDec -> collectReactiveStorageInExpr(owner, expr.target)
            is IrExpr.Member -> collectReactiveStorageInExpr(owner, expr.target)
            is IrExpr.Index -> {
                collectReactiveStorageInExpr(owner, expr.target)
                collectReactiveStorageInExpr(owner, expr.index)
            }
            is IrExpr.ArrayLiteral -> expr.elements.forEach { collectReactiveStorageInExpr(owner, it) }
            is IrExpr.StructCtor -> expr.args.forEach { collectReactiveStorageInExpr(owner, it) }
            is IrExpr.IfExpr -> {
                collectReactiveStorageInExpr(owner, expr.condition)
                collectReactiveStorageInExpr(owner, expr.thenExpr)
                collectReactiveStorageInExpr(owner, expr.elseExpr)
            }
            else -> Unit
        }
    }

    /** Applies [visit] to each expression a statement holds directly. */
    private fun forEachStatementExpr(stmt: IrStmt, visit: (IrExpr) -> Unit) {
        when (stmt) {
            is IrStmt.VarDecl -> visit(stmt.initializer)
            is IrStmt.FinDecl -> visit(stmt.initializer)
            is IrStmt.LetDecl -> visit(stmt.initializer)
            is IrStmt.ExprStmt -> visit(stmt.expr)
            is IrStmt.Return -> stmt.value?.let(visit)
            is IrStmt.Assignment -> visit(stmt.value)
            else -> Unit
        }
    }

    private fun registerReactiveStorage(owner: String, name: String, type: IrType) {
        val base = "__azora_reactive_${sanitizeName(owner)}_${sanitizeName(name)}"
        reactiveStorage[owner to name] = ReactiveStorage(base, "${base}_initialized", type)
    }

    private fun emitReactiveDecl(name: String, type: IrType, initializer: IrExpr) {
        val storage = reactiveStorage[currentFunctionName to name]
            ?: error("missing reactive storage for '$currentFunctionName::$name'")
        val llvmType = mapType(type)
        localVars[name] = "@${storage.valueGlobal}" to llvmType
        val initialized = nextTmp()
        val initialize = nextLabel("reactive_init")
        val ready = nextLabel("reactive_ready")
        emit("  $initialized = load i1, i1* @${storage.initGlobal}")
        emitTerminator("  br i1 $initialized, label %$ready, label %$initialize")
        startBlock(initialize)
        val value = emitInitializerForDeclaredType(type, initializer)
        emit("  store $llvmType $value, $llvmType* @${storage.valueGlobal}")
        emit("  store i1 1, i1* @${storage.initGlobal}")
        emitTerminator("  br label %$ready")
        startBlock(ready)
    }

    private fun lazyFlagName(name: String): String = "__lazy_init_$name"

    private fun ensureLazyInitialized(name: String) {
        val lazy = lazyLocals[name] ?: return
        val flag = localVars.getValue(lazy.flagName).first
        val loaded = nextTmp()
        val initialize = nextLabel("lazy_init")
        val ready = nextLabel("lazy_ready")
        emit("  $loaded = load i1, i1* $flag")
        emitTerminator("  br i1 $loaded, label %$ready, label %$initialize")
        startBlock(initialize)
        val value = emitInitializerForDeclaredType(lazy.type, lazy.initializer)
        val (slot, type) = localVars.getValue(name)
        emit("  store $type $value, $type* $slot")
        emit("  store i1 1, i1* $flag")
        emitTerminator("  br label %$ready")
        startBlock(ready)
    }

    private fun invalidateLazyDependents(changed: String, seen: MutableSet<String> = mutableSetOf()): Set<String> {
        if (!seen.add(changed)) return emptySet()
        val invalidated = linkedSetOf<String>()
        for ((name, lazy) in lazyLocals) {
            if (changed !in lazy.dependencies) continue
            val flag = localVars[lazy.flagName]?.first ?: continue
            emit("  store i1 0, i1* $flag")
            invalidated.add(name)
            invalidated.addAll(invalidateLazyDependents(name, seen))
        }
        return invalidated
    }

    private fun emitInitializerForDeclaredType(type: IrType, initializer: IrExpr): String {
        val stdlibCollection = emitStdlibCollectionInitializer(type, initializer)
        if (stdlibCollection != null) return stdlibCollection
        val raw = emitExpr(initializer)
        return coerceNumeric(raw, initializer.type, type)
    }

    private fun emitStdlibCollectionInitializer(type: IrType, initializer: IrExpr): String? {
        val named = type as? IrType.Named ?: return null
        if (named.name !in structDefs) return null
        return when {
            named.name in stdlibListNames && initializer is IrExpr.ArrayLiteral -> {
                val arrayType = initializer.type as? IrType.Array ?: IrType.Array(IrType.Any)
                val packed = emitArrayLiteral(initializer)
                emitStdlibSequenceFromPacked(named.name, packed, arrayType.element)
            }
            named.name in stdlibSetNames && initializer is IrExpr.SetLit -> {
                val setType = initializer.type as? IrType.Set ?: IrType.Set(IrType.Any)
                val packed = emitSetLiteral(initializer)
                emitStdlibSequenceFromPacked(named.name, packed, setType.element)
            }
            named.name in stdlibMapNames && initializer is IrExpr.MapLit -> {
                val mapType = initializer.type as? IrType.Map ?: IrType.Map(IrType.Any, IrType.Any)
                val packed = emitMapLiteral(initializer)
                emitStdlibMapFromPacked(named.name, packed, mapType)
            }
            else -> null
        }
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
        // Semantic range bounds and steps are Int. Keep progression in i64:
        // the final increment/decrement may lie beyond Int's representable range.
        check(stmt.start.type == IrType.Int && stmt.end.type == IrType.Int)
        val start = emitExpr(stmt.start)
        val end = emitExpr(stmt.end)
        val step = stmt.step?.let { emitExpr(it) } ?: "1"
        val startWide = nextTmp()
        emit("  $startWide = sext i32 $start to i64")
        val endWide = nextTmp()
        emit("  $endWide = sext i32 $end to i64")
        val stepWide = nextTmp()
        emit("  $stepWide = sext i32 $step to i64")
        val validStep = nextTmp()
        emit("  $validStep = icmp sgt i64 $stepWide, 0")
        val validLabel = nextLabel("for_step_valid")
        val invalidLabel = nextLabel("for_step_invalid")
        emitTerminator("  br i1 $validStep, label %$validLabel, label %$invalidLabel")
        startBlock(invalidLabel)
        usesAbort = true
        emit("  call void @__azora_abort()")
        emitTerminator("  unreachable")
        startBlock(validLabel)

        val counterAlloca = allocaSlots[stmt.counter to "i32"] ?: run {
            val reg = "%loc${allocaCounter++}.${sanitizeName(stmt.counter)}"
            emit("  $reg = alloca i32")
            allocaSlots[stmt.counter to "i32"] = reg
            reg
        }
        localVars[stmt.counter] = counterAlloca to "i32"
        val initial = if (stmt.descending) nextTmp().also {
            emit("  $it = sub i64 $startWide, 1")
        } else startWide
        val indexAlloca = stmt.indexName?.let { name ->
            val slot = allocaSlots[name to "i32"] ?: run {
                val reg = "%loc${allocaCounter++}.${sanitizeName(name)}"
                emit("  $reg = alloca i32")
                allocaSlots[name to "i32"] = reg
                reg
            }
            emit("  store i32 0, i32* $slot")
            localVars[name] = slot to "i32"
            slot
        }
        val condLabel = nextLabel("for_cond")
        val bodyLabel = nextLabel("for_body")
        val incLabel = nextLabel("for_inc")
        val endLabel = nextLabel("for_end")
        // A phi holds progression without allocating a new stack slot each
        // time an enclosing loop reaches this loop.
        val entryLabel = nextLabel("for_entry")
        emitTerminator("  br label %$entryLabel")
        startBlock(entryLabel)
        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val current = nextTmp()
        val next = "%${nextLabel("for_next")}"
        emit("  $current = phi i64 [ $initial, %$entryLabel ], [ $next, %$incLabel ]")
        val pred = if (stmt.descending) "sge" else if (stmt.inclusive) "sle" else "slt"
        val cmp = nextTmp()
        emit("  $cmp = icmp $pred i64 $current, $endWide")
        emitTerminator("  br i1 $cmp, label %$bodyLabel, label %$endLabel")

        startBlock(bodyLabel)
        val row = nextTmp()
        emit("  $row = trunc i64 $current to i32")
        emit("  store i32 $row, i32* $counterAlloca")
        loopStack.addLast(LoopTarget(incLabel, endLabel, stmt.label))
        emitStmts(stmt.body)
        loopStack.removeLast()
        emitTerminator("  br label %$incLabel")

        startBlock(incLabel)
        val op = if (stmt.descending) "sub" else "add"
        emit("  $next = $op i64 $current, $stepWide")
        if (indexAlloca != null) {
            val ordinal = nextTmp()
            emit("  $ordinal = load i32, i32* $indexAlloca")
            val nextOrdinal = nextTmp()
            emit("  $nextOrdinal = add i32 $ordinal, 1")
            emit("  store i32 $nextOrdinal, i32* $indexAlloca")
        }
        emitTerminator("  br label %$condLabel")
        startBlock(endLabel)
    }

    private fun emitForEach(stmt: IrStmt.ForEach) {
        val elemType = when (val type = stmt.iterable.type) {
            is IrType.Array -> type.element
            is IrType.Set -> type.element
            else -> null
        } ?: error("LLVM cannot walk a ${stmt.iterable.type.shown()} with 'for … in' yet")
        val et = mapType(elemType)

        val elemAlloca = allocaSlots[stmt.elem to et] ?: run {
            val reg = "%loc${allocaCounter++}.${sanitizeName(stmt.elem)}"
            emit("  $reg = alloca $et")
            allocaSlots[stmt.elem to et] = reg
            reg
        }
        val iterationName = foreachIndexName(stmt.elem)
        val iterationAlloca = allocaSlots[iterationName to "i64"] ?: run {
            val reg = "%loc${allocaCounter++}.${sanitizeName(iterationName)}"
            emit("  $reg = alloca i64")
            allocaSlots[iterationName to "i64"] = reg
            reg
        }

        val raw = emitExpr(stmt.iterable)
        val len = emitArrayLengthI64(raw)
        val dataRaw = nextTmp()
        emit("  $dataRaw = getelementptr i8, i8* $raw, i64 8")
        val data = nextTmp()
        emit("  $data = bitcast i8* $dataRaw to $et*")
        emit("  store i64 0, i64* $iterationAlloca")
        val userIndexAlloca = stmt.indexName?.let { name ->
            val indexType = mapType(IrType.Int)
            val slot = allocaSlots[name to indexType] ?: run {
                val reg = "%loc${allocaCounter++}.${sanitizeName(name)}"
                emit("  $reg = alloca $indexType")
                allocaSlots[name to indexType] = reg
                reg
            }
            emit("  store $indexType 0, $indexType* $slot")
            localVars[name] = slot to indexType
            slot
        }

        val condLabel = nextLabel("foreach_cond")
        val bodyLabel = nextLabel("foreach_body")
        val incLabel = nextLabel("foreach_inc")
        val endLabel = nextLabel("foreach_end")

        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val idx = nextTmp()
        emit("  $idx = load i64, i64* $iterationAlloca")
        val cmp = nextTmp()
        emit("  $cmp = icmp ult i64 $idx, $len")
        emitTerminator("  br i1 $cmp, label %$bodyLabel, label %$endLabel")

        startBlock(bodyLabel)
        val ep = nextTmp()
        emit("  $ep = getelementptr $et, $et* $data, i64 $idx")
        val value = nextTmp()
        emit("  $value = load $et, $et* $ep, align 1")
        emit("  store $et $value, $et* $elemAlloca")
        if (userIndexAlloca != null) {
            val ordinal32 = nextTmp()
            emit("  $ordinal32 = trunc i64 $idx to i32")
            emit("  store i32 $ordinal32, i32* $userIndexAlloca")
        }
        localVars[stmt.elem] = elemAlloca to et
        loopStack.addLast(LoopTarget(incLabel, endLabel, stmt.label))
        emitStmts(stmt.body)
        loopStack.removeLast()
        emitTerminator("  br label %$incLabel")

        startBlock(incLabel)
        val incLoaded = nextTmp()
        emit("  $incLoaded = load i64, i64* $iterationAlloca")
        val next = nextTmp()
        emit("  $next = add i64 $incLoaded, 1")
        emit("  store i64 $next, i64* $iterationAlloca")
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
            else -> {
                // Pointer scrutinees (slots, structs, erased/nullable values) compare
                // against `null`, not the integer `0` - LLVM rejects an integer
                // constant as a pointer operand.
                val p = if (scrutType.endsWith("*") && pv == "0") "null" else pv
                emit("  $cmp = icmp eq $scrutType $scrut, $p")
            }
        }
        return cmp
    }

    /**
     * Native definitions for the referenced compiler string bridge intrinsics.
     * The simple ones map to libc; a few text transforms and array-returning ones
     * fail code generation with a target diagnostic rather than producing programs
     * that only compare or slice strings work while richer text ops mature.
     */
    private fun buildStringIntrinsics(sb: StringBuilder) {
        val unsupported = neededIntrinsics.keys.intersect(setOf("toUpper", "toLower", "trim", "replace", "split", "toChars"))
        check(unsupported.isEmpty()) {
            "LLVM text operation(s) ${unsupported.joinToString()} are not implemented; use the interpreter until native text support is available"
        }
        fun def(name: String, body: String) {
            val symbol = neededIntrinsics[name] ?: return
            // The bodies are written against the bare name; the program calls the
            // scope-mangled one.
            sb.appendLine(body.trimIndent().replace("@$name(", "@$symbol("))
            sb.appendLine()
        }
        if ("stringLength" in neededIntrinsics || "startsWith" in neededIntrinsics ||
            "endsWith" in neededIntrinsics || "substring" in neededIntrinsics
        ) usesStrlen = true
        if ("startsWith" in neededIntrinsics || "endsWith" in neededIntrinsics) usesStrncmp = true
        if ("contains" in neededIntrinsics || "indexOf" in neededIntrinsics) usesStrstr = true
        if ("substring" in neededIntrinsics) { usesMemcpy = true; usesAllocatorRuntime = true }

        def("stringLength", """
            define i32 @stringLength(i8* %s) {
              %l = call i64 @strlen(i8* %s)
              %r = trunc i64 %l to i32
              ret i32 %r
            }""")
        def("charAt", """
            define i8 @charAt(i8* %s, i32 %i) {
              %i64 = sext i32 %i to i64
              %p = getelementptr i8, i8* %s, i64 %i64
              %c = load i8, i8* %p
              ret i8 %c
            }""")
        def("ord", """
            define i32 @ord(i8 %c) {
              %r = zext i8 %c to i32
              ret i32 %r
            }""")
        def("chr", """
            define i8 @chr(i32 %i) {
              %r = trunc i32 %i to i8
              ret i8 %r
            }""")
        def("isDigit", """
            define i1 @isDigit(i8 %c) {
              %ge = icmp uge i8 %c, 48
              %le = icmp ule i8 %c, 57
              %r = and i1 %ge, %le
              ret i1 %r
            }""")
        def("isAlpha", """
            define i1 @isAlpha(i8 %c) {
              %g1 = icmp uge i8 %c, 65
              %l1 = icmp ule i8 %c, 90
              %up = and i1 %g1, %l1
              %g2 = icmp uge i8 %c, 97
              %l2 = icmp ule i8 %c, 122
              %lo = and i1 %g2, %l2
              %r = or i1 %up, %lo
              ret i1 %r
            }""")
        def("substring", """
            define i8* @substring(i8* %s, i32 %start, i32 %end) {
              %len = sub i32 %end, %start
              %len64 = sext i32 %len to i64
              %size = add i64 %len64, 1
              %buf = call i8* @__azora_alloc(i64 %size)
              %s64 = sext i32 %start to i64
              %src = getelementptr i8, i8* %s, i64 %s64
              %cp = call i8* @memcpy(i8* %buf, i8* %src, i64 %len64)
              %tp = getelementptr i8, i8* %buf, i64 %len64
              store i8 0, i8* %tp
              ret i8* %buf
            }""")
        def("startsWith", """
            define i1 @startsWith(i8* %s, i8* %p) {
              %lp = call i64 @strlen(i8* %p)
              %c = call i32 @strncmp(i8* %s, i8* %p, i64 %lp)
              %r = icmp eq i32 %c, 0
              ret i1 %r
            }""")
        def("endsWith", """
            define i1 @endsWith(i8* %s, i8* %suf) {
            entry:
              %ls = call i64 @strlen(i8* %s)
              %lsuf = call i64 @strlen(i8* %suf)
              %short = icmp ult i64 %ls, %lsuf
              br i1 %short, label %no, label %check
            check:
              %off = sub i64 %ls, %lsuf
              %tail = getelementptr i8, i8* %s, i64 %off
              %c = call i32 @strncmp(i8* %tail, i8* %suf, i64 %lsuf)
              %r = icmp eq i32 %c, 0
              ret i1 %r
            no:
              ret i1 0
            }""")
        def("contains", """
            define i1 @contains(i8* %s, i8* %sub) {
              %p = call i8* @strstr(i8* %s, i8* %sub)
              %r = icmp ne i8* %p, null
              ret i1 %r
            }""")
        def("indexOf", """
            define i32 @indexOf(i8* %s, i8* %sub) {
            entry:
              %p = call i8* @strstr(i8* %s, i8* %sub)
              %isnull = icmp eq i8* %p, null
              br i1 %isnull, label %no, label %found
            found:
              %pi = ptrtoint i8* %p to i64
              %si = ptrtoint i8* %s to i64
              %diff = sub i64 %pi, %si
              %r = trunc i64 %diff to i32
              ret i32 %r
            no:
              ret i32 -1
            }""")
        // `Array<Char>` stores a length followed by bytes. Copy those bytes to
        // a NUL-terminated allocation for the native string representation.
        if ("fromChars" in neededIntrinsics) { usesMalloc = true; usesMemcpy = true }
        def("fromChars", """
            define i8* @fromChars(i8* %c) {
              %lenp = bitcast i8* %c to i64*
              %len = load i64, i64* %lenp
              %data = getelementptr i8, i8* %c, i64 8
              %size = add i64 %len, 1
              %buf = call i8* @malloc(i64 %size)
              %cp = call i8* @memcpy(i8* %buf, i8* %data, i64 %len)
              %end = getelementptr i8, i8* %buf, i64 %len
              store i8 0, i8* %end
              ret i8* %buf
            }""")
    }

    /** i1 result of comparing a slot's tag (its first `i8*` cell) to [variantName]. */
    private fun emitSlotTagCheck(scrut: String, variantName: String): String {
        usesStrcmp = true
        val tagPtr = gepString(addStringConstant(variantName))
        val tagpp = nextTmp(); emit("  $tagpp = bitcast i8* $scrut to i8**")
        val slottag = nextTmp(); emit("  $slottag = load i8*, i8** $tagpp")
        val c = nextTmp(); emit("  $c = call i32 @strcmp(i8* $slottag, i8* $tagPtr)")
        val eq = nextTmp(); emit("  $eq = icmp eq i32 $c, 0")
        return eq
    }

    /** Unboxes a matched slot's payloads into their pattern bindings' local slots. */
    private fun bindSlotPayloads(scrut: String, pattern: IrExpr.SlotPattern) {
        pattern.bindings.forEachIndexed { i, name ->
            val type = pattern.bindingTypes.getOrElse(i) { IrType.Any }
            val t = mapType(type)
            val value = emitSlotPayload(scrut, i, type)
            val alloca = allocaSlots[name to t] ?: run {
                val reg = "%loc${allocaCounter++}.${sanitizeName(name)}"
                emit("  $reg = alloca $t")
                allocaSlots[name to t] = reg
                reg
            }
            emit("  store $t $value, $t* $alloca")
            localVars[name] = alloca to t
        }
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
                val cmp = if (pattern is IrExpr.SlotPattern) {
                    emitSlotTagCheck(scrut, pattern.variantName)
                } else {
                    emitWhenEq(scrutIrType, scrutType, scrut, emitExpr(pattern))
                }
                if (i == branch.patterns.lastIndex) {
                    val nextLabel = nextLabel("when_next")
                    emitTerminator("  br i1 $cmp, label %$bodyLabel, label %$nextLabel")
                    startBlock(bodyLabel)
                    // Bind slot payloads before the body runs.
                    (pattern as? IrExpr.SlotPattern)?.let { bindSlotPayloads(scrut, it) }
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
        emit("  call void @__azora_abort()")
        emitTerminator("  unreachable")

        startBlock(passLabel)
    }

    private fun emitTrace(stmt: IrStmt.Trace) {
        usesPrintf = true
        usesStrcmp = true
        val rawLevel = emitExpr(stmt.level)
        var displayLevel = rawLevel
        for (variant in stmt.variants.asReversed()) {
            val source = gepString(addStringConstant(variant))
            val display = gepString(addStringConstant(variant.uppercase()))
            val comparison = nextTmp()
            emit("  $comparison = call i32 @strcmp(i8* $rawLevel, i8* $source)")
            val matches = nextTmp()
            emit("  $matches = icmp eq i32 $comparison, 0")
            val selected = nextTmp()
            emit("  $selected = select i1 $matches, i8* $display, i8* $displayLevel")
            displayLevel = selected
        }
        val msg = stringify(stmt.message)
        val fmtRef = addStringConstant("[%s] %s\n")
        val fmtPtr = gepString(fmtRef)
        val unused = nextTmp()
        emit("  $unused = call i32 (i8*, ...) @printf(i8* $fmtPtr, i8* $displayLevel, i8* $msg)")
    }

    // -----------------------------------------------------------------------
    // Expressions
    // -----------------------------------------------------------------------

    /** Emits LLVM IR for an expression and returns the register/value. */
    private fun emitExpr(expr: IrExpr): String = when (expr) {
        IrExpr.UnitLiteral -> "0"
        is IrExpr.IntLiteral -> expr.text ?: "${expr.value}"
        is IrExpr.CharLiteral -> "${expr.value.code}"
        is IrExpr.DoubleLiteral -> floatConst(expr.value, expr.type, expr.text)
        is IrExpr.BoolLiteral -> if (expr.value) "1" else "0"
        is IrExpr.StringLiteral -> {
            val ref = addStringConstant(expr.value)
            gepString(ref)
        }
        is IrExpr.EnumLiteral -> {
            val ref = addStringConstant(expr.variant)
            gepString(ref)
        }
        is IrExpr.EnumToString -> emitExpr(expr.value)
        is IrExpr.Var -> {
            // The null literal is lowered to `Var("__null", Any)`; emit LLVM's null pointer.
            if (expr.name == "__null") return "null"
            ensureLazyInitialized(expr.name)
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
        is IrExpr.IncDec -> emitIncDec(expr)
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
        is IrExpr.TupleLit -> emitTupleLit(expr)
        is IrExpr.VariantLit -> error("LLVM cannot lower an anonymous variant value yet")
        is IrExpr.TupleAccess -> emitTupleAccess(expr)
        is IrExpr.CatchExpr -> emitCatchExpr(expr)
        is IrExpr.NumCast -> coerceNumeric(emitExpr(expr.value), expr.value.type, expr.type)
        is IrExpr.IfExpr -> emitIfExpr(expr)
        is IrExpr.SlotPattern -> "0"
        is IrExpr.Await -> emitAwait(expr)
        is IrExpr.Spread -> error("LLVM cannot expand a spread into fixed call parameters yet")
        is IrExpr.Lambda -> {
            emitClosure(expr)
        }
    }

    /**
     * One entry of a closure environment.
     *
     * @property byRef the capture is a reference to the original binding, so the
     *   environment holds its address and the body reads and writes through it -
     *   which is what makes a closure's write visible outside it
     */
    private data class Capture(
        val name: String,
        val type: IrType,
        val llvmType: String,
        val byRef: Boolean = false,
    ) {
        /** The type stored in the environment struct. */
        val fieldType: String get() = if (byRef) "$llvmType*" else llvmType
    }

    private fun emitClosure(lambda: IrExpr.Lambda): String {
        val callableType = lambda.type as IrType.Function
        usesAllocatorRuntime = true
        lateTypeDefinitions.add(CLOSURE_TYPE_DEFINITION)
        val id = taskContextCounter++
        val bodyName = "__azora_lambda_body_$id"
        val ctxType = "%azora.lambda.ctx.$id"
        val captures = collectCaptures(lambda)
        if (captures.isNotEmpty()) {
            lateTypeDefinitions.add("$ctxType = type { ${captures.joinToString(", ") { it.fieldType }} }")
        }

        val environment = if (captures.isEmpty()) {
            "null"
        } else {
            val sizePtr = nextTmp()
            val size = nextTmp()
            emit("  $sizePtr = getelementptr $ctxType, $ctxType* null, i32 1")
            emit("  $size = ptrtoint $ctxType* $sizePtr to i64")
            val raw = emitHeapAlloc(size)
            val context = nextTmp()
            emit("  $context = bitcast i8* $raw to $ctxType*")
            captures.forEachIndexed { index, capture ->
                val storage = localVars.getValue(capture.name)
                val field = nextTmp()
                emit("  $field = getelementptr $ctxType, $ctxType* $context, i32 0, i32 $index")
                if (capture.byRef) {
                    // The address of the original binding: reads and writes in the
                    // body go through it, so both sides see one value.
                    emit("  store ${capture.fieldType} ${storage.first}, ${capture.fieldType}* $field, align 1")
                } else {
                    val value = lambda.captureInitializers[capture.name]?.let(::emitExpr) ?: run {
                        val loaded = nextTmp()
                        emit("  $loaded = load ${capture.llvmType}, ${capture.llvmType}* ${storage.first}")
                        loaded
                    }
                    emit("  store ${capture.llvmType} $value, ${capture.llvmType}* $field, align 1")
                }
            }
            registerClosureContextDrop(raw, ctxType, captures, lambda)
            raw
        }

        deferredFunctions += renderDeferredFunction {
            emitClosureBody(bodyName, ctxType, captures, lambda)
        }

        val closureSizePtr = nextTmp()
        val closureSize = nextTmp()
        emit("  $closureSizePtr = getelementptr %azora.closure, %azora.closure* null, i32 1")
        emit("  $closureSize = ptrtoint %azora.closure* $closureSizePtr to i64")
        val rawClosure = emitHeapAlloc(closureSize)
        registerClosureDrop(rawClosure)
        val closure = nextTmp()
        emit("  $closure = bitcast i8* $rawClosure to %azora.closure*")
        val fnField = nextTmp()
        val signature = closureFunctionType(callableType)
        val erasedFn = nextTmp()
        emit("  $fnField = getelementptr %azora.closure, %azora.closure* $closure, i32 0, i32 0")
        emit("  $erasedFn = bitcast $signature* @$bodyName to i8*")
        emit("  store i8* $erasedFn, i8** $fnField")
        val envField = nextTmp()
        emit("  $envField = getelementptr %azora.closure, %azora.closure* $closure, i32 0, i32 1")
        emit("  store i8* $environment, i8** $envField")
        return closure
    }

    private fun closureLifetimeHelpers() {
        ownershipDrops.getOrPut("closure-lifetime") {
            deferredFunctions += """
define void @__azora_closure_env_retain(i8* %env) {
entry:
  %null = icmp eq i8* %env, null
  br i1 %null, label %end, label %retain
retain:
  %slot.raw = getelementptr i8, i8* %env, i64 -8
  %slot = bitcast i8* %slot.raw to i64*
  %count = atomicrmw add i64* %slot, i64 1 seq_cst
  br label %end
end:
  ret void
}
define void @__azora_closure_env_release(i8* %env) {
entry:
  %null = icmp eq i8* %env, null
  br i1 %null, label %end, label %release
release:
  %slot.raw = getelementptr i8, i8* %env, i64 -8
  %slot = bitcast i8* %slot.raw to i64*
  %count = atomicrmw sub i64* %slot, i64 1 seq_cst
  %last = icmp eq i64 %count, 1
  br i1 %last, label %destroy, label %end
destroy:
  call void @__azora_free(i8* %env)
  br label %end
end:
  ret void
}
""".trimIndent()
            "__azora_closure_env_release"
        }
    }

    private fun registerClosureContextDrop(raw: String, contextType: String, captures: List<Capture>, lambda: IrExpr.Lambda) {
        closureLifetimeHelpers()
        val key = "closure-context:$contextType"
        val name = ownershipDrops.getOrPut(key) {
            val symbol = "__azora_drop_context_${ownershipDrops.size}"
            val body = StringBuilder("define void @$symbol(i8* %raw, i64 %count) {\nentry:\n")
            body.appendLine("  %context = bitcast i8* %raw to $contextType*")
            for (i in captures.indices.reversed()) {
                val capture = captures[i]
                if (capture.byRef) continue
                val inner = (capture.type as? IrType.Nullable)?.inner ?: capture.type
                val owned = inner is IrType.Function || inner is IrType.Array || inner is IrType.Pointer ||
                    (inner is IrType.Named && (inner.name in structDefs || inner.name in specDispatch || inner.name in ownedSlots))
                if (!owned || !capture.llvmType.endsWith("*")) continue
                body.appendLine("  %field.$i = getelementptr $contextType, $contextType* %context, i32 0, i32 $i")
                body.appendLine("  %value.$i = load ${capture.llvmType}, ${capture.llvmType}* %field.$i")
                body.appendLine("  store ${capture.llvmType} null, ${capture.llvmType}* %field.$i")
                body.appendLine("  %raw.$i = bitcast ${capture.llvmType} %value.$i to i8*")
                body.appendLine("  call void @__azora_free(i8* %raw.$i)")
            }
            body.appendLine("  ret void\n}")
            deferredFunctions += body.toString()
            symbol
        }
        emit("  call void @__azora_set_drop(i8* $raw, void (i8*, i64)* @$name, i64 1)")
    }

    private fun registerClosureDrop(raw: String) {
        closureLifetimeHelpers()
        val name = ownershipDrops.getOrPut("closure-wrapper") {
            val symbol = "__azora_drop_closure_${ownershipDrops.size}"
            deferredFunctions += """
define void @$symbol(i8* %raw, i64 %count) {
entry:
  %closure = bitcast i8* %raw to %azora.closure*
  %slot = getelementptr %azora.closure, %azora.closure* %closure, i32 0, i32 1
  %env = load i8*, i8** %slot
  store i8* null, i8** %slot
  call void @__azora_closure_env_release(i8* %env)
  ret void
}
""".trimIndent()
            symbol
        }
        emit("  call void @__azora_set_drop(i8* $raw, void (i8*, i64)* @$name, i64 1)")
    }

    private fun isolatedClosureCopy(value: String): String {
        usesAllocatorRuntime = true
        lateTypeDefinitions.add(CLOSURE_TYPE_DEFINITION)
        closureLifetimeHelpers()
        val raw = emitHeapAlloc("16")
        registerClosureDrop(raw)
        val closure = nextTmp()
        emit("  $closure = bitcast i8* $raw to %azora.closure*")
        for (i in 0..1) {
            val source = nextTmp()
            val field = nextTmp()
            val item = nextTmp()
            emit("  $source = getelementptr %azora.closure, %azora.closure* $value, i32 0, i32 $i")
            emit("  $item = load i8*, i8** $source")
            emit("  $field = getelementptr %azora.closure, %azora.closure* $closure, i32 0, i32 $i")
            emit("  store i8* $item, i8** $field")
            if (i == 1) emit("  call void @__azora_closure_env_retain(i8* $item)")
        }
        return closure
    }

    private fun closureFunctionType(type: IrType.Function): String {
        val params = listOf("i8*") + (type.params + type.receivers).map(::mapType)
        return "${abiReturnType(type.ret)} (${params.joinToString(", ")})"
    }

    private fun emitClosureBody(
        name: String,
        contextType: String,
        captures: List<Capture>,
        lambda: IrExpr.Lambda,
    ) {
        val callableType = lambda.type as IrType.Function
        localVars.clear()
        allocaSlots.clear()
        loopStack.clear()
        taskScopeStack.clear()
        tmpCounter = 0
        labelCounter = 0
        allocaCounter = 0
        terminated = false
        currentBlock = "entry"
        currentReturnType = callableType.ret
        currentIsMain = false

        val parameters = lambda.params.joinToString(", ") { (paramName, type) ->
            "${mapType(type)} %arg.$paramName"
        }
        val suffix = if (parameters.isEmpty()) "" else ", $parameters"
        line("define ${abiReturnType(callableType.ret)} @$name(i8* %env.raw$suffix) {")
        line("entry:")

        if (captures.isNotEmpty()) {
            emit("  %env = bitcast i8* %env.raw to $contextType*")
            captures.forEachIndexed { index, capture ->
                val field = nextTmp()
                emit("  $field = getelementptr $contextType, $contextType* %env, i32 0, i32 $index")
                if (capture.byRef) {
                    // The environment holds the original binding's address, so the
                    // name is bound to it directly - no copy, and therefore no
                    // second value to fall out of step.
                    val pointer = nextTmp()
                    emit("  $pointer = load ${capture.fieldType}, ${capture.fieldType}* $field, align 1")
                    localVars[capture.name] = pointer to capture.llvmType
                } else {
                    // Owned captures reside in the environment. Moving a capture
                    // empties that slot, and subsequent calls see its new value.
                    localVars[capture.name] = field to capture.llvmType
                }
            }
        }
        lambda.params.forEach { (paramName, type) ->
            val llvmType = mapType(type)
            val slot = nextTmp()
            emit("  $slot = alloca $llvmType")
            emit("  store $llvmType %arg.$paramName, $llvmType* $slot")
            localVars[paramName] = slot to llvmType
        }
        emitEntryAllocas(lambda.body)
        prepareDefers(lambda.body)
        emitStmts(lambda.body)
        emitFunctionExitCleanup()
        when (callableType.ret) {
            IrType.Unit -> emitTerminator("  ret void")
            IrType.Nothing -> emitTerminator("  unreachable")
            else -> emitTerminator("  ret ${mapType(callableType.ret)} ${defaultValue(callableType.ret)}")
        }
        line("}")
    }

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
        // Joined task results are owned and released by the task runtime. They
        // must not also be registered in an enclosing allocation scope.
        emit("  $raw = call i8* @__azora_alloc_raw(i64 ${sizeOfScalar(type)})")
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

    /**
     * `__isolated(v)` - the copy a `Copy` value gets when it is used by value,
     * and the body of a `clone` no type wrote for itself.
     *
     * An aggregate is a pointer here, so without this the two values the
     * language promises are independent would be one. The copy is one level
     * deep: the fields are duplicated, and a field that is itself a pointer
     * goes on referring to what it referred to. That is exactly what a derived
     * `clone` copies - a type wanting more declares its own `clone`, which is
     * called instead of this and never reaches here.
     *
     * A scalar is already a value and passes through untouched.
     */
    private fun emitIsolatedCopy(arg: IrExpr): String = isolatedCopy(emitExpr(arg), arg.type)

    /**
     * [value], of [type], duplicated as `__isolated` promises. A pack is
     * copied one level deep, as above. An array owns its buffer, so the buffer is
     * copied whole and each element that is itself an array or a pack is copied
     * in turn: `[1, 2, 3].clone()` must not share the slots it was copied from
     * (OWNERSHIP_BORROWING_DIP: both values "own independent state").
     */
    private fun isolatedCopy(value: String, type: IrType): String {
        if (type is IrType.Function) return isolatedClosureCopy(value)
        if (type is IrType.Array) return isolatedArrayCopy(value, type.element)
        val named = type as? IrType.Named ?: return value
        if (named.name !in structDefs) return value
        val st = "%struct.${sanitizeName(named.name)}"
        // sizeof via the getelementptr-on-null idiom, as struct construction does.
        val sizeGep = nextTmp()
        emit("  $sizeGep = getelementptr $st, $st* null, i32 1")
        val size = nextTmp()
        emit("  $size = ptrtoint $st* $sizeGep to i64")
        val raw = emitHeapAlloc(size)
        val src = nextTmp()
        emit("  $src = bitcast $st* $value to i8*")
        usesMemcpy = true
        val copied = nextTmp()
        emit("  $copied = call i8* @memcpy(i8* $raw, i8* $src, i64 $size)")
        val ptr = nextTmp()
        emit("  $ptr = bitcast i8* $raw to $st*")
        registerNamedDrop(raw, named)
        for ((i, field) in structDefs.getValue(named.name).fields.withIndex()) {
            val concrete = concreteFieldType(structDefs.getValue(named.name), i, named)
            if (field.ownsValue && concrete is IrType.Named && concrete.name in structDefs) {
                val address = nextTmp()
                val old = nextTmp()
                emit("  $address = getelementptr $st, $st* $ptr, i32 0, i32 $i")
                emit("  $old = load ${mapType(field.type)}, ${mapType(field.type)}* $address")
                val typed = coerceNumeric(old, field.type, concrete)
                val duplicate = isolatedCopy(typed, concrete)
                val stored = coerceNumeric(duplicate, concrete, field.type)
                emit("  store ${mapType(field.type)} $stored, ${mapType(field.type)}* $address")
            }
        }
        return ptr
    }

    private fun copiesOnIsolation(type: IrType): Boolean =
        type is IrType.Array || (type is IrType.Named && type.name in structDefs)

    /** A copy of the array at [raw]: its length, its slots, and copies of what they own. */
    private fun isolatedArrayCopy(raw: String, element: IrType): String {
        val len = emitArrayLengthI64(raw)
        val bytes = nextTmp()
        emit("  $bytes = mul i64 $len, ${sizeOfScalar(element)}")
        val size = nextTmp()
        emit("  $size = add i64 $bytes, 8")
        val copy = emitHeapAlloc(size)
        registerArrayDrop(copy, element)
        usesMemcpy = true
        val copied = nextTmp()
        emit("  $copied = call i8* @memcpy(i8* $copy, i8* $raw, i64 $size)")
        if (!copiesOnIsolation(element)) return copy

        val et = mapType(element)
        val dataRaw = nextTmp()
        emit("  $dataRaw = getelementptr i8, i8* $copy, i64 8")
        val data = nextTmp()
        emit("  $data = bitcast i8* $dataRaw to $et*")
        val condLabel = nextLabel("isolate_cond")
        val bodyLabel = nextLabel("isolate_body")
        val doneLabel = nextLabel("isolate_done")
        val nextIndex = "%isolate_next_${labelCounter++}"
        // The body may copy nested arrays, which opens blocks of its own, so the
        // back edge comes from whichever block the body ends in; it is written
        // into the phi once that is known.
        val backEdge = "__isolate_back_edge_${labelCounter++}__"
        val preheader = currentBlock
        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val idx = nextTmp()
        emit("  $idx = phi i64 [ 0, %$preheader ], [ $nextIndex, %$backEdge ]")
        val inRange = nextTmp()
        emit("  $inRange = icmp ult i64 $idx, $len")
        emitTerminator("  br i1 $inRange, label %$bodyLabel, label %$doneLabel")
        startBlock(bodyLabel)
        val slot = nextTmp()
        emit("  $slot = getelementptr $et, $et* $data, i64 $idx")
        val item = nextTmp()
        emit("  $item = load $et, $et* $slot, align 1")
        val itemCopy = isolatedCopy(item, element)
        emit("  store $et $itemCopy, $et* $slot, align 1")
        emit("  $nextIndex = add i64 $idx, 1")
        val at = out.indexOf(backEdge)
        // setRange, not replace: the latter is JVM-only and this is commonMain.
        out.setRange(at, at + backEdge.length, currentBlock)
        emitTerminator("  br label %$condLabel")
        startBlock(doneLabel)
        return copy
    }

    /** Loads and empties an owned place exactly once. Clearing fields before
     * destruction lets a hand-written dtor coexist with recursive cleanup. */
    private fun emitOwnershipTake(place: IrExpr): String {
        val location: Pair<String, String>? = when (place) {
            is IrExpr.Var -> localVars[place.name]
                ?: allocaSlots[place.name to mapType(place.type)]?.let { it to mapType(place.type) }
                ?: globalVars[place.name]?.let { "@${place.name}" to mapType(place.type) }
            is IrExpr.Member -> emitFieldPtr(place.target, place.name)?.let { it.first to it.third }
            is IrExpr.Index -> {
                val raw = emitExpr(place.target)
                val index = indexToI64(emitExpr(place.index), place.index.type)
                val array = place.target.type is IrType.Array
                if (array) emitBoundsCheck(raw, index)
                val data = if (array) nextTmp().also { emit("  $it = getelementptr i8, i8* $raw, i64 8") } else raw
                val type = mapType(place.type)
                val typed = nextTmp()
                val address = nextTmp()
                emit("  $typed = bitcast i8* $data to $type*")
                emit("  $address = getelementptr $type, $type* $typed, i64 $index")
                address to type
            }
            else -> null
        }
        if (location == null) return emitExpr(place)
        val (address, storedType) = location
        check(storedType.endsWith("*")) { "cannot empty owned storage of $storedType" }
        val loaded = nextTmp()
        emit("  $loaded = load $storedType, $storedType* $address, align 1")
        emit("  store $storedType null, $storedType* $address, align 1")
        val type = mapType(place.type)
        return if (type == storedType) loaded else nextTmp().also {
            emit("  $it = bitcast $storedType $loaded to $type")
        }
    }

    private fun registerSpecDrop(raw: String) {
        val name = ownershipDrops.getOrPut("spec-box") {
            val symbol = "__azora_drop_spec_${ownershipDrops.size}"
            deferredFunctions += """
define void @$symbol(i8* %raw, i64 %count) {
entry:
  %slot.raw = getelementptr i8, i8* %raw, i64 8
  %slot = bitcast i8* %slot.raw to i8**
  %value = load i8*, i8** %slot
  store i8* null, i8** %slot
  call void @__azora_free(i8* %value)
  ret void
}
""".trimIndent()
            symbol
        }
        emit("  call void @__azora_set_drop(i8* $raw, void (i8*, i64)* @$name, i64 1)")
    }

    private fun registerNamedDrop(raw: String, type: IrType.Named) {
        val key = "pack:${type.shown()}"
        val name = ownershipDrops.getOrPut(key) {
            val symbol = "__azora_drop_pack_${ownershipDrops.size}"
            val def = structDefs.getValue(type.name)
            val st = "%struct.${sanitizeName(type.name)}"
            val dtor = "${type.name}_dtor"
            val fields = def.fields.indices.filter { i ->
                if (!def.fields[i].ownsValue) return@filter false
                val field = ownershipFieldType(def, i, type)
                val inner = (field as? IrType.Nullable)?.inner ?: field
                // Pointer fields managed by a custom dtor include Shared's
                // common control block and container capacities, not per-pack owners.
                inner is IrType.Array || inner is IrType.Function || (inner is IrType.Named && (inner.name in structDefs || inner.name in specDispatch || inner.name in ownedSlots)) ||
                    (inner is IrType.Pointer && dtor !in funcParamTypes && !type.name.contains("Weak"))
            }
            val body = StringBuilder()
            body.appendLine("define void @$symbol(i8* %raw, i64 %count) {")
            body.appendLine("entry:")
            body.appendLine("  %self = bitcast i8* %raw to $st*")
            if (dtor in funcParamTypes) body.appendLine("  call void @$dtor($st* %self)")
            for (i in fields.asReversed()) {
                val stored = mapType(def.fields[i].type)
                body.appendLine("  %field.$i = getelementptr $st, $st* %self, i32 0, i32 $i")
                body.appendLine("  %value.$i = load $stored, $stored* %field.$i")
                body.appendLine("  store $stored null, $stored* %field.$i")
                if (stored == "i8*") body.appendLine("  call void @__azora_free(i8* %value.$i)")
                else {
                    body.appendLine("  %raw.$i = bitcast $stored %value.$i to i8*")
                    body.appendLine("  call void @__azora_free(i8* %raw.$i)")
                }
            }
            body.appendLine("  ret void")
            body.appendLine("}")
            deferredFunctions += body.toString()
            symbol
        }
        emit("  call void @__azora_set_drop(i8* $raw, void (i8*, i64)* @$name, i64 1)")
    }

    private fun registerPointerDrop(raw: String, element: IrType, count: String) {
        val inner = (element as? IrType.Nullable)?.inner ?: element
        if (inner !is IrType.Function && inner !is IrType.Array && (inner !is IrType.Named || (inner.name !in structDefs && inner.name !in ownedSlots && inner.name !in specDispatch))) {
            emit("  call void @__azora_set_drop(i8* $raw, void (i8*, i64)* null, i64 $count)")
            return
        }
        val key = "buffer:${element.shown()}"
        val name = ownershipDrops.getOrPut(key) {
            val symbol = "__azora_drop_buffer_${ownershipDrops.size}"
            val type = mapType(element)
            deferredFunctions += """
define void @$symbol(i8* %raw, i64 %count) {
entry:
  %data = bitcast i8* %raw to $type*
  br label %test
test:
  %i = phi i64 [ 0, %entry ], [ %next, %body ]
  %more = icmp ult i64 %i, %count
  br i1 %more, label %body, label %end
body:
  %slot = getelementptr $type, $type* %data, i64 %i
  %value = load $type, $type* %slot, align 1
  store $type null, $type* %slot, align 1
  %value.raw = bitcast $type %value to i8*
  call void @__azora_free(i8* %value.raw)
  %next = add i64 %i, 1
  br label %test
end:
  ret void
}
""".trimIndent()
            symbol
        }
        emit("  call void @__azora_set_drop(i8* $raw, void (i8*, i64)* @$name, i64 $count)")
    }

    /** Arrays retain their active length; removed tail slots must never drop twice. */
    private fun registerArrayDrop(raw: String, element: IrType) {
        val inner = (element as? IrType.Nullable)?.inner ?: element
        if (inner !is IrType.Array && inner !is IrType.Function && !(inner is IrType.Named && (inner.name in structDefs || inner.name in specDispatch || inner.name in ownedSlots))) return
        val name = ownershipDrops.getOrPut("owned-array") {
            val symbol = "__azora_drop_array_${ownershipDrops.size}"
            deferredFunctions += """
define void @$symbol(i8* %raw, i64 %unused) {
entry:
  %len.ptr = bitcast i8* %raw to i64*
  %len = load i64, i64* %len.ptr
  store i64 0, i64* %len.ptr
  %data.raw = getelementptr i8, i8* %raw, i64 8
  %data = bitcast i8* %data.raw to i8**
  br label %test
test:
  %i = phi i64 [ %len, %entry ], [ %next, %body ]
  %more = icmp ne i64 %i, 0
  br i1 %more, label %body, label %end
body:
  %next = sub i64 %i, 1
  %slot = getelementptr i8*, i8** %data, i64 %next
  %value = load i8*, i8** %slot, align 1
  store i8* null, i8** %slot, align 1
  call void @__azora_free(i8* %value)
  br label %test
end:
  ret void
}
""".trimIndent()
            symbol
        }
        emit("  call void @__azora_set_drop(i8* $raw, void (i8*, i64)* @$name, i64 1)")
    }

    private fun ownershipFieldType(def: IrTopLevel.Struct, index: Int, referring: IrType.Named): IrType {
        fun substitute(type: IrType): IrType = when (type) {
            is IrType.Named -> def.typeParams.indexOf(type.name).takeIf { it >= 0 }?.let {
                referring.args.getOrNull(it)
            } ?: type.copy(args = type.args.map(::substitute))
            is IrType.Array -> type.copy(element = substitute(type.element))
            is IrType.Nullable -> type.copy(inner = substitute(type.inner))
            is IrType.Pointer -> type.copy(inner = substitute(type.inner))
            else -> type
        }
        return substitute(def.fields[index].ownershipType ?: concreteFieldType(def, index, referring))
    }

    private fun qualifyConcreteOwnership(value: String, type: IrType, depth: Int = 0) {
        if (depth > 8) return
        if (type is IrType.Array) { registerArrayDrop(value, type.element); return }
        val named = type as? IrType.Named ?: return
        val def = structDefs[named.name] ?: return
        if (named.args.isEmpty() || named.args.any { it == IrType.Any || (it is IrType.Named && it.name !in structDefs && it.name !in ownedSlots && it.name !in specDispatch) }) return
        val nonnull = nextTmp()
        emit("  $nonnull = icmp ne ${mapType(named)} $value, null")
        val qualify = nextLabel("owner.qualify")
        val done = nextLabel("owner.qualified")
        emitTerminator("  br i1 $nonnull, label %$qualify, label %$done")
        startBlock(qualify)
        val raw = nextTmp()
        emit("  $raw = bitcast ${mapType(named)} $value to i8*")
        registerNamedDrop(raw, named)
        for (i in def.fields.indices) {
            val field = def.fields[i]
            if (!field.ownsValue) continue
            val concrete = ownershipFieldType(def, i, named)
            if (concrete !is IrType.Array && concrete !is IrType.Pointer && concrete !is IrType.Named) continue
            val stored = mapType(field.type)
            if (!stored.endsWith("*")) continue
            val address = nextTmp()
            val child = nextTmp()
            emit("  $address = getelementptr ${mapType(named).removeSuffix("*")}, ${mapType(named)} $value, i32 0, i32 $i")
            emit("  $child = load $stored, $stored* $address")
            val live = nextTmp()
            emit("  $live = icmp ne $stored $child, null")
            val visit = nextLabel("owner.field")
            val visited = nextLabel("owner.field.done")
            emitTerminator("  br i1 $live, label %$visit, label %$visited")
            startBlock(visit)
            val childRaw = if (stored == "i8*") child else nextTmp().also { emit("  $it = bitcast $stored $child to i8*") }
            when (concrete) {
                is IrType.Array -> registerArrayDrop(childRaw, concrete.element)
                is IrType.Pointer -> {
                    if (named.name.contains("Weak")) {
                        emitTerminator("  br label %$visited")
                        startBlock(visited)
                        continue
                    }
                    val countRaw = nextTmp(); val countSlot = nextTmp(); val count = nextTmp()
                    emit("  $countRaw = getelementptr i8, i8* $childRaw, i64 -8")
                    emit("  $countSlot = bitcast i8* $countRaw to i64*")
                    emit("  $count = load i64, i64* $countSlot")
                    registerPointerDrop(childRaw, concrete.inner, count)
                }
                is IrType.Named -> {
                    val typed = if (mapType(concrete) == stored) child else nextTmp().also { emit("  $it = bitcast $stored $child to ${mapType(concrete)}") }
                    qualifyConcreteOwnership(typed, concrete, depth + 1)
                }
                else -> Unit
            }
            emitTerminator("  br label %$visited")
            startBlock(visited)
        }
        emitTerminator("  br label %$done")
        startBlock(done)
    }

    private fun inheritPointerDrop(value: String, previous: String) {
        val live = nextTmp()
        emit("  $live = icmp ne i8* $previous, null")
        val inherit = nextLabel("buffer.inherit")
        val done = nextLabel("buffer.inherited")
        emitTerminator("  br i1 $live, label %$inherit, label %$done")
        startBlock(inherit)
        val header = nextTmp(); val slot = nextTmp(); val callback = nextTmp()
        emit("  $header = getelementptr i8, i8* $previous, i64 -16")
        emit("  $slot = bitcast i8* $header to void (i8*, i64)**")
        emit("  $callback = load void (i8*, i64)*, void (i8*, i64)** $slot")
        val newHeader = nextTmp(); val newSlot = nextTmp()
        emit("  $newHeader = getelementptr i8, i8* $value, i64 -16")
        emit("  $newSlot = bitcast i8* $newHeader to void (i8*, i64)**")
        emit("  store void (i8*, i64)* $callback, void (i8*, i64)** $newSlot")
        emitTerminator("  br label %$done")
        startBlock(done)
    }

    private val constructionOwners = mutableListOf<Pair<String, IrType>>()

    private fun emitConstructionCleanup() {
        for ((value, type) in constructionOwners.asReversed()) {
            val raw = if (mapType(type) == "i8*") value else nextTmp().also {
                emit("  $it = bitcast ${mapType(type)} $value to i8*")
            }
            usesAllocatorRuntime = true
            emit("  call void @__azora_free(i8* $raw)")
        }
    }

    private var usesMemset = false
    private val ownershipDrops = mutableMapOf<String, String>()
    private var ownedSlots = emptySet<String>()

    private fun emitHeapAlloc(size: String): String {
        usesAllocatorRuntime = true
        val raw = nextTmp()
        emit("  $raw = call i8* @__azora_alloc(i64 $size)")
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
            lateTypeDefinitions.add("$ctxType = type { ${captures.joinToString(", ") { it.fieldType }} }")
        }

        val ctxRaw = if (captures.isEmpty()) {
            "null"
        } else {
            val sizeGep = nextTmp()
            val size = nextTmp()
            emit("  $sizeGep = getelementptr $ctxType, $ctxType* null, i32 1")
            emit("  $size = ptrtoint $ctxType* $sizeGep to i64")
            val raw = nextTmp()
            // The task entry releases its capture context explicitly.
            emit("  $raw = call i8* @__azora_alloc_raw(i64 $size)")
            val ctx = nextTmp()
            emit("  $ctx = bitcast i8* $raw to $ctxType*")
            for ((i, capture) in captures.withIndex()) {
                val storage = localVars[capture.name] ?: continue
                val field = nextTmp()
                emit("  $field = getelementptr $ctxType, $ctxType* $ctx, i32 0, i32 $i")
                if (capture.byRef) {
                    emit("  store ${capture.fieldType} ${storage.first}, ${capture.fieldType}* $field, align 1")
                } else {
                    val value = lambda.captureInitializers[capture.name]?.let(::emitExpr) ?: run {
                        val loaded = nextTmp()
                        emit("  $loaded = load ${storage.second}, ${storage.second}* ${storage.first}")
                        loaded
                    }
                    emit("  store ${capture.fieldType} $value, ${capture.fieldType}* $field, align 1")
                }
            }
            raw
        }

        deferredFunctions += renderDeferredFunction {
            emitLambdaTaskBody(bodyName, captures, resultType, lambda.body)
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
        tmpCounter = 0
        labelCounter = 0
        terminated = false
        currentBlock = "entry"
        line("define i8* @$entryName(i8* %ctx.raw) {")
        line("entry:")
        if (dynamicGlobalInitializers.any { it.threadLocal }) {
            emit("  call void @__azora_init_threadlocals()")
        }
        val captureValues = mutableListOf<Pair<String, Capture>>()
        if (captures.isNotEmpty()) {
            emit("  %ctx = bitcast i8* %ctx.raw to $ctxType*")
            for ((i, capture) in captures.withIndex()) {
                val ptr = "%capture.ptr.$i"
                val value = "%capture.val.$i"
                emit("  $ptr = getelementptr $ctxType, $ctxType* %ctx, i32 0, i32 $i")
                emit("  $value = load ${capture.fieldType}, ${capture.fieldType}* $ptr, align 1")
                captureValues += value to capture
            }
            emit("  call void @__azora_free(i8* %ctx.raw)")
        }
        val args = captureValues.joinToString(", ") { (value, capture) -> "${capture.fieldType} $value" }
        if (resultType == IrType.Unit) {
            emit("  call void @$bodyName($args)")
            emitTerminator("  ret i8* null")
        } else if (resultType == IrType.Nothing) {
            emit("  call void @$bodyName($args)")
            emitTerminator("  unreachable")
        } else {
            val result = "%task.result"
            emit("  $result = call ${mapType(resultType)} @$bodyName($args)")
            val boxed = emitResultBox(result, resultType)
            emitTerminator("  ret i8* $boxed")
        }
        line("}")
    }

    /** Emits a spawned lambda body while preserving borrowed captures as pointers. */
    private fun emitLambdaTaskBody(
        name: String,
        captures: List<Capture>,
        resultType: IrType,
        body: List<IrStmt>,
    ) {
        localVars.clear()
        loopStack.clear()
        taskScopeStack.clear()
        tmpCounter = 0
        labelCounter = 0
        terminated = false
        currentBlock = "entry"
        currentReturnType = resultType
        currentIsMain = false
        currentIsFailable = false

        val params = captures.joinToString(", ") { "${it.fieldType} %arg.${it.name}" }
        line("define ${abiReturnType(resultType)} @$name($params) {")
        line("entry:")
        for (capture in captures) {
            if (capture.byRef) {
                localVars[capture.name] = "%arg.${capture.name}" to capture.llvmType
            } else {
                val slot = nextTmp()
                emit("  $slot = alloca ${capture.llvmType}")
                emit("  store ${capture.llvmType} %arg.${capture.name}, ${capture.llvmType}* $slot")
                localVars[capture.name] = slot to capture.llvmType
            }
        }
        emitEntryAllocas(body)
        prepareDefers(body)
        emitStmts(body)
        emitFunctionExitCleanup()
        when (resultType) {
            IrType.Unit -> emitTerminator("  ret void")
            IrType.Nothing -> emitTerminator("  unreachable")
            else -> emitTerminator("  ret ${mapType(resultType)} ${defaultValue(resultType)}")
        }
        line("}")
    }

    private fun renderDeferredFunction(block: () -> Unit): String {
        val savedOut = out
        val savedLocalVars = localVars.toMap()
        val savedAllocaSlots = allocaSlots.toMap()
        val savedLoopStack = ArrayDeque(loopStack)
        val savedTaskScopes = ArrayDeque(taskScopeStack)
        val savedTmp = tmpCounter
        val savedLabel = labelCounter
        val savedAlloca = allocaCounter
        val savedTerminated = terminated
        val savedBlock = currentBlock
        val savedReturnType = currentReturnType
        val savedIsMain = currentIsMain
        val savedIsFailable = currentIsFailable
        val savedDeferSlots = deferSlots.toList()
        val savedEmittingDefers = emittingDefers
        val savedConstructionOwners = constructionOwners.toList()
        constructionOwners.clear()

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
            tmpCounter = savedTmp
            labelCounter = savedLabel
            allocaCounter = savedAlloca
            terminated = savedTerminated
            currentBlock = savedBlock
            currentReturnType = savedReturnType
            currentIsMain = savedIsMain
            currentIsFailable = savedIsFailable
            deferSlots.clear(); deferSlots.addAll(savedDeferSlots)
            emittingDefers = savedEmittingDefers
            constructionOwners.clear(); constructionOwners.addAll(savedConstructionOwners)
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
                Capture(name, type, llvmType, byRef = lambda.allCapturesByRef || name in lambda.byRefCaptures)
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
                is IrStmt.Scope -> collectDeclaredNames(stmt.body, names)
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
                is IrStmt.Effect -> collectDeclaredNames(stmt.body, names)
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
                is IrStmt.Exchange -> { collectReferencedVars(stmt.left, refs); collectReferencedVars(stmt.right, refs) }
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
                is IrStmt.Scope -> collectReferencedVars(stmt.body, refs)
                is IrStmt.Assert -> {
                    collectReferencedVars(stmt.condition, refs)
                    collectReferencedVars(stmt.message, refs)
                }
                is IrStmt.Trace -> {
                    collectReferencedVars(stmt.level, refs)
                    collectReferencedVars(stmt.message, refs)
                }
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
                is IrStmt.Effect -> collectReferencedVars(stmt.body, refs)
                is IrStmt.Yield -> collectReferencedVars(stmt.value, refs)
                is IrStmt.Break, is IrStmt.Continue -> {}
            }
        }
    }

    private fun collectReferencedVars(expr: IrExpr, refs: MutableMap<String, IrType>) {
        when (expr) {
            is IrExpr.Var -> refs.putIfAbsentCompat(expr.name, expr.type)
            is IrExpr.Unary -> collectReferencedVars(expr.operand, refs)
            is IrExpr.IncDec -> collectReferencedVars(expr.target, refs)
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
            is IrExpr.VariantLit -> expr.elements.forEach { collectReferencedVars(it, refs) }
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
            is IrExpr.EnumToString -> collectReferencedVars(expr.value, refs)
            is IrExpr.Lambda -> {
                // A nested closure's free variables are free in this one too: a
                // closure can only hand on what it captured itself. Skipping
                // them left the inner environment naming a binding the outer
                // frame never took, and the read lowered to an undefined module
                // global - a link failure, or silently nothing.
                //
                // Anything the inner lambda binds itself is not free, so its own
                // parameters and declarations are subtracted first.
                val bound = linkedSetOf<String>()
                expr.params.forEach { bound.add(it.first) }
                collectDeclaredNames(expr.body, bound)
                val inner = linkedMapOf<String, IrType>()
                collectReferencedVars(expr.body, inner)
                inner.forEach { (name, type) ->
                    if (name !in bound && name !in refs) refs[name] = type
                }
            }
            is IrExpr.Await -> collectReferencedVars(expr.value, refs)
            is IrExpr.Spread -> collectReferencedVars(expr.array, refs)
            is IrExpr.IntLiteral, is IrExpr.DoubleLiteral, is IrExpr.StringLiteral, is IrExpr.EnumLiteral,
            is IrExpr.BoolLiteral, is IrExpr.CharLiteral, IrExpr.UnitLiteral, is IrExpr.SlotPattern -> {}
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
     * floating-point, e.g. `Vec3(1, 2, 3)` with `Double` fields).
     */
    private fun isNumericLike(t: IrType): Boolean =
        IrType.isInteger(t) || t in IrType.floatTypes || t == IrType.Char

    /** The common type two numeric operands widen to (wider float wins, else wider int). */
    private fun commonNumeric(a: IrType, b: IrType): IrType {
        if (a == b) return a
        val aFloat = a in IrType.floatTypes
        val bFloat = b in IrType.floatTypes
        if (aFloat || bFloat) {
            if (a == IrType.Quad || b == IrType.Quad) return IrType.Quad
            if (a == IrType.Double || b == IrType.Double) return IrType.Double
            return IrType.Float
        }
        return if (integerBitWidth(a) >= integerBitWidth(b)) a else b
    }

    private fun integerBitWidth(type: IrType): Int =
        (type as? IrType.Integer)?.bits ?: mapType(type).removePrefix("i").toInt()

    private fun coerceNumeric(value: String, from: IrType, to: IrType): String {
        if (from == to) return value
        // Upcast a concrete `pack` to a spec it implements: box into a fat pointer
        // `{ i32 typeId, i8* data }` so the value carries its runtime type for
        // dynamic dispatch. (A spec→spec pass-through keeps the existing box.)
        if (to is IrType.Named && to.name in specDispatch &&
            from is IrType.Named && from.name in specTypeIds
        ) {
            val box = emitHeapAlloc("16")
            val tidPtr = nextTmp(); emit("  $tidPtr = bitcast i8* $box to i32*")
            emit("  store i32 ${specTypeIds.getValue(from.name)}, i32* $tidPtr")
            val slotRaw = nextTmp(); emit("  $slotRaw = getelementptr i8, i8* $box, i64 8")
            val slot = nextTmp(); emit("  $slot = bitcast i8* $slotRaw to i8**")
            val dataI8 = nextTmp(); emit("  $dataI8 = bitcast ${mapType(from)} $value to i8*")
            emit("  store i8* $dataI8, i8** $slot")
            registerSpecDrop(box)
            return box
        }
        val ft = mapType(from)
        val tt = mapType(to)
        if (ft == tt) return value
        val fromInt = IrType.isInteger(from) || from == IrType.Char || from == IrType.Bool
        val toInt = IrType.isInteger(to) || to == IrType.Char || to == IrType.Bool
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
        // Float ↔ pointer. An erased generic slot is a pointer, so a `Double` has
        // to travel as its own bit pattern: reinterpreting a double *as* an
        // address is not well-formed IR. Integers already take the `inttoptr`
        // path above; floats need the bitcast first.
        if (fromFloat && toPtr) {
            val width = sizeOfScalar(from) * 8
            val bits = nextTmp(); emit("  $bits = bitcast $ft $value to i$width")
            val wide = if (width == 64) bits else {
                val w = nextTmp(); emit("  $w = zext i$width $bits to i64"); w
            }
            val t = nextTmp(); emit("  $t = inttoptr i64 $wide to $tt"); return t
        }
        if (fromPtr && toFloat) {
            val width = sizeOfScalar(to) * 8
            val wide = nextTmp(); emit("  $wide = ptrtoint $ft $value to i64")
            val bits = if (width == 64) wide else {
                val b = nextTmp(); emit("  $b = trunc i64 $wide to i$width"); b
            }
            val t = nextTmp(); emit("  $t = bitcast i$width $bits to $tt"); return t
        }
        val inst = when {
            fromInt && toFloat -> if (isUnsigned(from)) "uitofp" else "sitofp"
            fromFloat && toInt -> if (isUnsigned(to)) "fptoui" else "fptosi"
            fromInt && toInt -> {
                val fw = integerBitWidth(from); val tw = integerBitWidth(to)
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
            else -> return value // non-numeric - leave as-is
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
        // Slot (tagged-union) instance: `[ i8* tag, i64 payload… ]` on the heap.
        // The tag is the variant name; payloads are boxed to i64.
        if (expr.fieldNames.firstOrNull() == "__tag") return emitSlotCtor(expr)
        val def = structDefs[expr.name] ?: error("LLVM has no layout for pack '${expr.name}'")
        val st = "%struct.${sanitizeName(expr.name)}"

        // Evaluate constructor arguments first (source order).
        val start = constructionOwners.size
        val argVals = try {
            expr.args.map { argument ->
                val value = emitExpr(argument)
                val type = (argument.type as? IrType.Nullable)?.inner ?: argument.type
                if ((type is IrType.Named && type.name in structDefs) ||
                    (type is IrType.Pointer && argument is IrExpr.Call && argument.name in setOf("__alloc", "__allocBuffer", "__take"))) {
                    constructionOwners += value to argument.type
                }
                value to argument.type
            }
        } finally {
            while (constructionOwners.size > start) constructionOwners.removeAt(constructionOwners.lastIndex)
        }

        // sizeof via the getelementptr-on-null idiom (target independent).
        val sizeGep = nextTmp()
        emit("  $sizeGep = getelementptr $st, $st* null, i32 1")
        val size = nextTmp()
        emit("  $size = ptrtoint $st* $sizeGep to i64")
        val raw = emitHeapAlloc(size)
        val ptr = nextTmp()
        emit("  $ptr = bitcast i8* $raw to $st*")
        if (!def.isUnion) registerNamedDrop(raw, expr.type as? IrType.Named ?: IrType.Named(expr.name))

        if (def.isUnion) {
            // Exactly one member is initialized; the rest of the slot is whatever
            // that write leaves behind, as in C.
            val fieldName = expr.fieldNames.firstOrNull()
            val fi = def.fields.indexOfFirst { it.name == fieldName }
            if (fi >= 0 && argVals.isNotEmpty()) {
                val field = def.fields[fi]
                val ft = mapType(field.type)
                val (rawVal, argType) = argVals[0]
                val slot = nextTmp()
                emit("  $slot = getelementptr $st, $st* $ptr, i32 0, i32 0")
                val cast = nextTmp()
                emit("  $cast = bitcast ${mapType(widestMember(def))}* $slot to $ft*")
                val stored = coerceNumeric(rawVal, argType, field.type)
                emit("  store $ft $stored, $ft* $cast")
            }
            return ptr
        }

        for ((i, fieldName) in expr.fieldNames.withIndex()) {
            if (i >= argVals.size) break
            val fi = def.fields.indexOfFirst { it.name == fieldName }
            if (fi < 0) continue // metadata field (e.g. node __type/__chain) not in the layout
            val field = def.fields[fi]
            val (rawVal, argType) = argVals[i]
            val ft = mapType(field.type)
            // The constructed type's own arguments say what a generic field
            // really holds; the slot itself stays erased.
            val concrete = concreteFieldType(def, fi, expr.type as? IrType.Named ?: IrType.Named(expr.name))
            val value = coerceToField(rawVal, argType, concrete, ft)
            val owningType = ownershipFieldType(def, fi, expr.type as? IrType.Named ?: IrType.Named(expr.name))
            if (field.ownsValue && owningType is IrType.Array) registerArrayDrop(value, owningType.element)
            val fp = nextTmp()
            emit("  $fp = getelementptr $st, $st* $ptr, i32 0, i32 $fi")
            emit("  store $ft $value, $ft* $fp")
        }
        return ptr
    }

    /**
     * A slot instance: heap block `[ i8* tag, i64 p0, i64 p1, … ]`. The tag holds
     * the variant name (for `is`/`when` checks); each payload is boxed to i64.
     */
    private fun emitSlotCtor(expr: IrExpr.StructCtor): String {
        val argVals = expr.args.map { emitExpr(it) to it.type }
        val n = expr.fieldNames.size // 1 tag + payload count
        val raw = emitHeapAlloc("${8L * n}")
        registerSlotDrop(raw, expr.args.drop(1).map { it.type })
        val tagSlot = nextTmp()
        emit("  $tagSlot = bitcast i8* $raw to i8**")
        emit("  store i8* ${argVals[0].first}, i8** $tagSlot")
        for (i in 1 until n) {
            val (v, t) = argVals[i]
            val boxed = boxToI64(v, t)
            val p8 = nextTmp()
            emit("  $p8 = getelementptr i8, i8* $raw, i64 ${8L * i}")
            val p = nextTmp()
            emit("  $p = bitcast i8* $p8 to i64*")
            emit("  store i64 $boxed, i64* $p")
        }
        return raw
    }

    private fun registerSlotDrop(raw: String, payloads: List<IrType>) {
        val owned = payloads.indices.filter { index ->
            val type = (payloads[index] as? IrType.Nullable)?.inner ?: payloads[index]
            type is IrType.Array || (type is IrType.Named &&
                (type.name in structDefs || type.name in specDispatch || type.name in ownedSlots))
        }
        val name = ownershipDrops.getOrPut("variant:${owned.joinToString(",")}") {
            val symbol = "__azora_drop_variant_${ownershipDrops.size}"
            val body = StringBuilder("define void @$symbol(i8* %raw, i64 %unused) {\nentry:\n")
            for (index in owned.asReversed()) {
                body.appendLine("  %slot.raw.$index = getelementptr i8, i8* %raw, i64 ${8L * (index + 1)}")
                body.appendLine("  %slot.$index = bitcast i8* %slot.raw.$index to i8**")
                body.appendLine("  %value.$index = load i8*, i8** %slot.$index")
                body.appendLine("  store i8* null, i8** %slot.$index")
                body.appendLine("  call void @__azora_free(i8* %value.$index)")
            }
            body.appendLine("  ret void\n}")
            deferredFunctions += body.toString()
            symbol
        }
        emit("  call void @__azora_set_drop(i8* $raw, void (i8*, i64)* @$name, i64 1)")
    }

    /** Widens/reinterprets a payload value to the i64 slot cell it is stored in. */
    private fun boxToI64(value: String, type: IrType): String {
        // Each temporary is taken as it is emitted: LLVM numbers them in order.
        return when {
            IrType.isInteger(type) || type == IrType.Char -> coerceNumeric(value, type, IrType.Long)
            type == IrType.Bool -> nextTmp().also { emit("  $it = zext i1 $value to i64") }
            type == IrType.Double || type == IrType.Quad -> nextTmp().also { emit("  $it = bitcast double $value to i64") }
            type == IrType.Float -> {
                val b = nextTmp(); emit("  $b = bitcast float $value to i32")
                nextTmp().also { emit("  $it = zext i32 $b to i64") }
            }
            // pointers (String, structs, slots)
            else -> nextTmp().also { emit("  $it = ptrtoint ${mapType(type)} $value to i64") }
        }
    }

    /** Reinterprets an i64 slot cell back to a payload value of [type]. */
    private fun unboxFromI64(value: String, type: IrType): String {
        val t = nextTmp()
        return when {
            IrType.isInteger(type) || type == IrType.Char -> coerceNumeric(value, IrType.Long, type)
            type == IrType.Bool -> { emit("  $t = trunc i64 $value to i1"); t }
            type == IrType.Double || type == IrType.Quad -> { emit("  $t = bitcast i64 $value to double"); t }
            type == IrType.Float -> {
                val tr = nextTmp(); emit("  $tr = trunc i64 $value to i32")
                emit("  $t = bitcast i32 $tr to float"); t
            }
            else -> { emit("  $t = inttoptr i64 $value to ${mapType(type)}"); t }
        }
    }

    /** Loads and unboxes payload [index] of a slot instance [slotPtr] (an i8*). */
    private fun emitSlotPayload(slotPtr: String, index: Int, type: IrType): String {
        val p8 = nextTmp()
        emit("  $p8 = getelementptr i8, i8* $slotPtr, i64 ${8L * (index + 1)}")
        val p = nextTmp()
        emit("  $p = bitcast i8* $p8 to i64*")
        val raw = nextTmp()
        emit("  $raw = load i64, i64* $p")
        return unboxFromI64(raw, type)
    }

    /** Emits a pointer to field [name] of struct value [expr] (or null if unknown). */
    /**
     * The member a union's storage is shaped after: its widest one.
     *
     * Width is measured in the machine sizes LLVM gives these types; a pointer
     * (string, array, struct, spec box) is the widest thing a member can be, so
     * a union containing one is pointer-shaped.
     */
    private fun widestMember(def: IrTopLevel.Struct): IrType =
        def.fields.maxByOrNull { byteWidth(it.type) }?.type ?: IrType.Int

    private fun byteWidth(type: IrType): Int = when (type) {
        IrType.Bool, IrType.Byte, IrType.UByte -> 1
        IrType.Short, IrType.UShort -> 2
        IrType.Int, IrType.UInt, IrType.Float, IrType.Char -> 4
        IrType.Long, IrType.ULong, IrType.ISize, IrType.USize, IrType.Double, IrType.Quad -> 8
        IrType.Cent, IrType.UCent -> 16
        // Everything else is passed as a pointer.
        else -> 8
    }

    private fun emitFieldPtr(target: IrExpr, name: String): Triple<String, IrType, String>? {
        val tt = target.type as? IrType.Named ?: return null
        val def = structDefs[tt.name] ?: return null
        val fi = def.fields.indexOfFirst { it.name == name }
        if (fi < 0) return null
        val st = "%struct.${sanitizeName(tt.name)}"
        val ptr = emitExpr(target)
        if (def.isUnion) {
            // Every member starts at offset 0, so the address is the same for all
            // of them and only its type differs - which is exactly what makes
            // writing one member and reading another reinterpret the bytes.
            val slot = nextTmp()
            emit("  $slot = getelementptr $st, $st* $ptr, i32 0, i32 0")
            val ft = mapType(def.fields[fi].type)
            val cast = nextTmp()
            emit("  $cast = bitcast ${mapType(widestMember(def))}* $slot to $ft*")
            return Triple(cast, def.fields[fi].type, ft)
        }
        val fp = nextTmp()
        emit("  $fp = getelementptr $st, $st* $ptr, i32 0, i32 $fi")
        // The declared field type is what the *slot* holds; for a generic pack
        // that is an erased pointer. The concrete type comes from the referring
        // type's arguments, and is what the value has to be converted to.
        return Triple(fp, concreteFieldType(def, fi, tt), mapType(def.fields[fi].type))
    }

    /**
     * The type field [index] of [def] actually holds, given the arguments on the
     * referring type.
     *
     * A field declared as a type parameter is stored erased, so `Box<Double>.value`
     * is a pointer slot that really contains a double. Substituting the argument
     * here is what lets the read and the write convert in opposite directions
     * instead of handing out an address.
     */
    private fun concreteFieldType(def: IrTopLevel.Struct, index: Int, referring: IrType.Named): IrType {
        val declared = def.fields[index].type
        if (referring.args.isEmpty()) return declared
        // Only a field declared *as* a parameter is substituted; a nested
        // generic (`List<T>` inside the pack) keeps its erased element type,
        // which the collection paths already handle.
        val position = def.typeParamSlots.getOrNull(index) ?: -1
        if (position < 0 || position >= referring.args.size) return declared
        return referring.args[position]
    }

    /**
     * Converts [raw] for storage in a field whose slot is [slotLlvm].
     *
     * For an ordinary field the slot and the field agree and this is the usual
     * numeric coercion. For a substituted generic field the slot is an erased
     * pointer, so the value is converted to its declared type first and then
     * into the pointer slot - the exact inverse of what the read does.
     */
    private fun coerceToField(raw: String, from: IrType, fieldType: IrType, slotLlvm: String): String {
        val typed = coerceNumeric(raw, from, fieldType)
        if (mapType(fieldType) == slotLlvm) return typed
        return coerceNumeric(typed, fieldType, IrType.Any)
    }

    /**
     * The libm function a `bridge func` stands for, or null when it is an
     * ordinary extern.
     *
     * Matching is on the declaration's final name segment so both the bare
     * spelling and the `scope std` mangling resolve, and only when the
     * signature is all-`Double` - an unrelated user extern that happens to be
     * called `log` keeps its own linkage.
     */
    /**
     * A native definition for one of `std.os` / `std.filesystem`'s bridges.
     *
     * These have no Azora body and no C shim, so a native build linked against
     * nothing. They are defined here for the same reason the libm and string
     * intrinsics are: the standard library should not need a hand-written
     * runtime shipped and kept in step with it.
     *
     * Each is deliberately loop-free. Reading a file uses seek/tell/read rather
     * than a read loop, and running a command redirects into a temporary file
     * instead of draining a pipe - so each is a straight line of libc calls
     * rather than hand-written control flow in IR.
     */
    private fun osIntrinsicBody(item: IrTopLevel.Extern): String? {
        val local = item.name.substringAfterLast('_')
        val symbol = item.name

        /** A pointer to a constant, using the shared string-constant pool. */
        fun constant(register: String, value: String): String {
            val ref = addStringConstant(value)
            return "$register = getelementptr [${ref.byteLen} x i8], " +
                "[${ref.byteLen} x i8]* ${ref.name}, i64 0, i64 0"
        }
        fun define(signature: String, vararg lines: String): String = buildString {
            appendLine("define $signature {")
            appendLine("entry:")
            lines.forEach { appendLine("  $it") }
            appendLine("}")
            appendLine()
        }
        /** Reads a whole file into a fresh buffer, or "" when it cannot be opened. */
        fun readFileBody(name: String): String = define(
            "i8* @$name(i8* %a0)",
            constant("%m", "rb"),
            "%f = call i8* @fopen(i8* %a0, i8* %m)",
            "%bad = icmp eq i8* %f, null",
            "br i1 %bad, label %none, label %open",
            "none:",
            constant("  %e", ""),
            "  ret i8* %e",
            "open:",
            "  %s1 = call i32 @fseek(i8* %f, i64 0, i32 2)",
            "  %len = call i64 @ftell(i8* %f)",
            "  %s2 = call i32 @fseek(i8* %f, i64 0, i32 0)",
            "  %size = add i64 %len, 1",
            "  %buf = call i8* @malloc(i64 %size)",
            "  %got = call i64 @fread(i8* %buf, i64 1, i64 %len, i8* %f)",
            "  %endp = getelementptr i8, i8* %buf, i64 %got",
            "  store i8 0, i8* %endp",
            "  %cl = call i32 @fclose(i8* %f)",
            "  ret i8* %buf",
        )

        return when {
            // getenv, with "" for unset - `envVar` documents that shape, and
            // `hasEnvVar` is what tells unset from empty.
            local == "envVar" && item.params.size == 1 -> {
                usesGetenv = true
                define(
                    "i8* @$symbol(i8* %a0)",
                    "%v = call i8* @getenv(i8* %a0)",
                    "%missing = icmp eq i8* %v, null",
                    constant("%e", ""),
                    "%r = select i1 %missing, i8* %e, i8* %v",
                    "ret i8* %r",
                )
            }

            local == "hasEnvVar" && item.params.size == 1 -> {
                usesGetenv = true
                define(
                    "i1 @$symbol(i8* %a0)",
                    "%v = call i8* @getenv(i8* %a0)",
                    "%r = icmp ne i8* %v, null",
                    "ret i1 %r",
                )
            }

            local == "fsExists" && item.params.size == 1 -> {
                usesAccess = true
                define(
                    "i1 @$symbol(i8* %a0)",
                    "%c = call i32 @access(i8* %a0, i32 0)",
                    "%r = icmp eq i32 %c, 0",
                    "ret i1 %r",
                )
            }

            // Error name, newline, contents - the encoding `readText` reads.
            local == "fsRead" && item.params.size == 1 -> {
                usesStdio = true
                usesMalloc = true
                usesStrConcat = true
                usesAccess = true
                define(
                    "i8* @$symbol(i8* %a0)",
                    "%c = call i32 @access(i8* %a0, i32 0)",
                    "%bad = icmp ne i32 %c, 0",
                    "br i1 %bad, label %missing, label %present",
                    "missing:",
                    constant("  %nf", "NotFound\n"),
                    "  ret i8* %nf",
                    "present:",
                    "  %body = call i8* @$symbol.read(i8* %a0)",
                    constant("  %nl", "\n"),
                    "  %r = call i8* @__azora_str_concat(i8* %nl, i8* %body)",
                    "  ret i8* %r",
                ) + readFileBody("$symbol.read")
            }

            local == "fsWrite" && item.params.size == 3 -> {
                usesStdio = true
                define(
                    "i8* @$symbol(i8* %a0, i8* %a1, i1 %a2)",
                    constant("%wm", "wb"),
                    constant("%am", "ab"),
                    "%mode = select i1 %a2, i8* %am, i8* %wm",
                    "%f = call i8* @fopen(i8* %a0, i8* %mode)",
                    "%bad = icmp eq i8* %f, null",
                    "br i1 %bad, label %failed, label %write",
                    "failed:",
                    constant("  %wf", "WriteFailed"),
                    "  ret i8* %wf",
                    "write:",
                    "  %p = call i32 @fputs(i8* %a1, i8* %f)",
                    "  %cl = call i32 @fclose(i8* %f)",
                    constant("  %e", ""),
                    "  ret i8* %e",
                )
            }

            // `system` into a temporary file, then read it back. A pipe would
            // need a drain loop; this stays straight-line. The file is named
            // after the process so two programs cannot collide on it.
            local == "commandParts" && item.params.size == 1 -> {
                usesStdio = true
                usesSystem = true
                usesStrConcat = true
                usesIntToStr = true
                usesGetpid = true
                usesMalloc = true
                define(
                    "i8* @$symbol(i8* %a0)",
                    "%pid = call i32 @getpid()",
                    "%pid64 = sext i32 %pid to i64",
                    "%pidtxt = call i8* @__azora_int_to_str(i64 %pid64)",
                    constant("%tmpbase", "/tmp/azora-command-"),
                    "%tmp = call i8* @__azora_str_concat(i8* %tmpbase, i8* %pidtxt)",
                    constant("%redir", " >"),
                    "%c1 = call i8* @__azora_str_concat(i8* %a0, i8* %redir)",
                    "%c2 = call i8* @__azora_str_concat(i8* %c1, i8* %tmp)",
                    constant("%err2out", " 2>&1"),
                    "%cmd = call i8* @__azora_str_concat(i8* %c2, i8* %err2out)",
                    "%status = call i32 @system(i8* %cmd)",
                    "%shifted = ashr i32 %status, 8",
                    "%code = and i32 %shifted, 255",
                    "%code64 = sext i32 %code to i64",
                    "%codetxt = call i8* @__azora_int_to_str(i64 %code64)",
                    constant("%started", "\ntrue\n"),
                    "%h1 = call i8* @__azora_str_concat(i8* %codetxt, i8* %started)",
                    "%body = call i8* @$symbol.read(i8* %tmp)",
                    "%r = call i8* @__azora_str_concat(i8* %h1, i8* %body)",
                    "ret i8* %r",
                ) + readFileBody("$symbol.read")
            }

            else -> null
        }
    }

    private fun mathIntrinsicOf(item: IrTopLevel.Extern): String? {
        // `powr` is Azora's real-exponent power; libm spells it `pow`.
        val name = item.name.substringAfterLast('_').let { if (it == "powr") "pow" else it }
        val arity = LIBM_INTRINSICS[name] ?: return null
        if (item.params.size != arity) return null
        if (item.returnType != IrType.Double) return null
        if (item.params.any { it.second != IrType.Double }) return null
        return name
    }

    private fun emitMemberRead(expr: IrExpr.Member): String {
        val targetType = expr.target.type
        // `Hash`'s member on a value that carries no user-written one: an
        // integer, a char and a bool hash to themselves, which is the same rule
        // the interpreter applies. A pack with its own `hash` never arrives
        // here - it resolves to that member instead.
        if (expr.name == "hash" && targetType !is IrType.Named) {
            // Widened into an i64 the same way a slot payload is: an integer
            // sign-extends, a float takes its bit pattern, and anything behind a
            // pointer - including an erased element - takes its address.
            //
            // A `Named` type is excluded because it supplies its own `hash`,
            // derived or written, and that member is what resolves.
            //
            // A string hashes its content, so equal strings built apart agree.
            if (targetType == IrType.String) {
                usesStrHash = true
                val text = emitExpr(expr.target)
                val h = nextTmp()
                emit("  $h = call i64 @__azora_str_hash(i8* $text)")
                return h
            }
            return boxToI64(emitExpr(expr.target), targetType)
        }
        // Array/string length.
        if (expr.name in setOf("length", "size") && (targetType is IrType.Array || targetType is IrType.Map || targetType is IrType.Set)) {
            val raw = emitExpr(expr.target)
            val len = emitArrayLengthI64(raw)
            val t = nextTmp()
            emit("  $t = trunc i64 $len to i32")
            return t
        }
        if (expr.name == "data" && targetType is IrType.Array) {
            val raw = emitExpr(expr.target)
            val data = nextTmp()
            emit("  $data = getelementptr i8, i8* $raw, i64 8")
            return data
        }
        if ((expr.name == "isEmpty" || expr.name == "isNotEmpty") && (targetType is IrType.Array || targetType is IrType.Map || targetType is IrType.Set)) {
            return emitArrayEmptyCheck(expr.target, notEmpty = expr.name == "isNotEmpty")
        }
        if (expr.name in setOf("length", "size") && targetType == IrType.String) {
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
            val (fp, fieldType, ft) = fieldPtr
            val tmp = nextTmp()
            emit("  $tmp = load $ft, $ft* $fp")
            // A substituted generic field is stored erased, so the slot's type
            // and the field's real type disagree; convert back. An ordinary
            // field's types already agree and is left exactly as it was.
            if (mapType(fieldType) != ft) {
                return coerceNumeric(tmp, IrType.Any, fieldType)
            }
            return tmp
        }
        // Spec-typed receiver → the property's dynamic-dispatch stub. `list.size`
        // where `list: List<T>` has no field to address; the concrete getter is
        // chosen at runtime from the fat pointer's type id.
        if (targetType is IrType.Named && targetType.name in specDispatch) {
            val table = specDispatch.getValue(targetType.name)
            val property = table.methods.firstOrNull { it.name == expr.name && it.paramTypes.isEmpty() }
            if (property != null) {
                val box = emitExpr(expr.target)
                if (property.returnType == IrType.Unit || property.returnType == IrType.Nothing) {
                    emit("  call void @${specDispatcherName(targetType.name, expr.name)}(i8* $box)")
                    if (property.returnType == IrType.Nothing) emitTerminator("  unreachable")
                    return "0"
                }
                val r = nextTmp()
                emit("  $r = call ${mapType(property.returnType)} @${specDispatcherName(targetType.name, expr.name)}(i8* $box)")
                return coerceNumeric(r, property.returnType, expr.type)
            }
        }
        error("LLVM cannot read member '.${expr.name}' of ${expr.target.type.shown()} yet")
    }

    private fun emitMemberAssign(stmt: IrStmt.MemberAssign) {
        val fieldPtr = emitFieldPtr(stmt.target, stmt.name)
        if (fieldPtr != null) {
            val (fp, fieldType, ft) = fieldPtr
            val raw = emitExpr(stmt.value)
            val value = coerceToField(raw, stmt.value.type, fieldType, ft)
            if (fieldType is IrType.Pointer && stmt.value is IrExpr.Call &&
                (stmt.value as IrExpr.Call).name in setOf("__take", "__alloc", "__allocBuffer") && ft == "i8*") {
                val previous = nextTmp()
                emit("  $previous = load i8*, i8** $fp")
                inheritPointerDrop(value, previous)
            }
            emit("  store $ft $value, $ft* $fp")
            return
        }
        error("LLVM cannot assign member '.${stmt.name}' of ${stmt.target.type.shown()} yet")
    }

    /** The LLVM symbol name of a spec method's dynamic-dispatch stub. */
    private fun specDispatcherName(spec: String, method: String) =
        "__dyn_${sanitizeName(spec)}_${sanitizeName(method)}"

    /**
     * Emits a dynamic-dispatch stub for one spec method. The receiver is a fat
     * pointer `{ i32 typeId, i8* data }`; the stub loads the id, switches to the
     * matching implementer, unpacks the concrete `self`, and tail-calls its
     * `impl` body. Unknown ids return a zero value (unreachable in practice).
     */
    private fun renderSpecDispatcher(t: IrSpecTable, m: IrSpecMethod): String {
        val ret = abiReturnType(m.returnType)
        val paramTypes = m.paramTypes.map { mapType(it) }
        val sigParams = (listOf("i8* %box") + paramTypes.mapIndexed { i, ty -> "$ty %a$i" }).joinToString(", ")
        val forwardArgs = paramTypes.mapIndexed { i, ty -> "$ty %a$i" }
        val cases = t.impls.mapNotNull { impl ->
            val id = specTypeIds[impl.typeName] ?: return@mapNotNull null
            val fn = impl.methodFuncs[m.name] ?: return@mapNotNull null
            Triple(id, impl.typeName, fn)
        }
        val sb = StringBuilder()
        sb.appendLine("define $ret @${specDispatcherName(t.specName, m.name)}($sigParams) {")
        sb.appendLine("entry:")
        sb.appendLine("  %tidp = bitcast i8* %box to i32*")
        sb.appendLine("  %tid = load i32, i32* %tidp")
        sb.appendLine("  %datap = getelementptr i8, i8* %box, i64 8")
        sb.appendLine("  %datapp = bitcast i8* %datap to i8**")
        sb.appendLine("  %data = load i8*, i8** %datapp")
        sb.appendLine("  switch i32 %tid, label %default [ ${cases.joinToString(" ") { (id, _, _) -> "i32 $id, label %case_$id" }} ]")
        for ((id, typeName, fn) in cases) {
            val st = "%struct.${sanitizeName(typeName)}*"
            sb.appendLine("case_$id:")
            sb.appendLine("  %self_$id = bitcast i8* %data to $st")
            val callArgs = (listOf("$st %self_$id") + forwardArgs).joinToString(", ")
            if (m.returnType == IrType.Unit || m.returnType == IrType.Nothing) {
                sb.appendLine("  call void @$fn($callArgs)")
                if (m.returnType == IrType.Nothing) sb.appendLine("  unreachable")
                else sb.appendLine("  ret void")
            } else {
                sb.appendLine("  %r_$id = call $ret @$fn($callArgs)")
                sb.appendLine("  ret $ret %r_$id")
            }
        }
        sb.appendLine("default:")
        when (m.returnType) {
            IrType.Unit -> sb.appendLine("  ret void")
            IrType.Nothing -> sb.appendLine("  unreachable")
            else -> sb.appendLine("  ret $ret ${defaultValue(m.returnType)}")
        }
        sb.appendLine("}")
        return sb.toString()
    }

    /**
     * Lowers a method call, checking the error slot when the method can fail.
     *
     * Methods are lowered to free functions named `Type_method`, so a failable
     * one is recognised by that mangled name - a call through the receiver has
     * to check exactly as a plain call does.
     */
    private fun emitMethodCall(expr: IrExpr.MethodCall): String {
        val result = emitMethodCallValue(expr)
        val owner = (expr.target.type as? IrType.Named)?.name
        if (owner != null && "${owner}_${expr.name}" in failableFunctions) emitErrorCheck()
        return when (expr.type) {
            IrType.Unit -> "0"
            IrType.Nothing -> {
                emitTerminator("  unreachable")
                "0"
            }
            else -> result
        }
    }

    private fun emitMethodCallValue(expr: IrExpr.MethodCall): String {
        // Spec-typed receiver → dynamic dispatch through the fat pointer.
        val recvType = expr.target.type
        if (recvType is IrType.Named && recvType.name in specDispatch) {
            val table = specDispatch.getValue(recvType.name)
            val method = table.methods.firstOrNull { it.name == expr.name }
            if (method != null) {
                val box = emitExpr(expr.target)
                val args = expr.args.mapIndexed { i, a ->
                    val pt = method.paramTypes.getOrNull(i) ?: a.type
                    "${mapType(pt)} ${coerceNumeric(emitExpr(a), a.type, pt)}"
                }
                val argList = (listOf("i8* $box") + args).joinToString(", ")
                val disp = specDispatcherName(recvType.name, expr.name)
                if (method.returnType == IrType.Unit || method.returnType == IrType.Nothing) {
                    emit("  call void @$disp($argList)")
                    return "void"
                }
                val r = nextTmp()
                emit("  $r = call ${mapType(method.returnType)} @$disp($argList)")
                // The dispatcher returns the spec's erased slot; the call site
                // may know the type argument (`Stack<Double>.top()`).
                return coerceNumeric(r, method.returnType, expr.type)
            }
        }

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
                "remove" -> {
                    if (expr.args.size == 1) return emitArrayRemoveAt(expr.target, expr.args[0], arrayType.element, transfer = false)
                }
                "removeAt" -> if (expr.args.size == 1) return emitArrayRemoveAt(expr.target, expr.args[0], arrayType.element, transfer = true)
                "removeFirst" -> return emitArrayRemoveAt(expr.target, IrExpr.IntLiteral(0), arrayType.element, transfer = true)
                "removeLast", "pop" -> return emitArrayRemoveAt(expr.target,
                    IrExpr.Binary(IrExpr.Member(expr.target, "size", IrType.Int), IrBinaryOp.SUB, IrExpr.IntLiteral(1), IrType.Int), arrayType.element, transfer = true)
                "clear" -> {
                    val raw = emitExpr(expr.target)
                    emitArrayClear(raw)
                    return "void"
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

        error("LLVM cannot call '.${expr.name}' on ${expr.target.type.shown()} yet")
    }

    /** `[a, b, c]` → malloc(8 + n*elemSize), i64 length header, packed elements. */
    private fun emitArrayLiteral(expr: IrExpr.ArrayLiteral): String {
        if (expr.elements.any { it is IrExpr.Spread }) return emitSpreadArrayLiteral(expr)
        val elemType = (expr.type as? IrType.Array)?.element ?: IrType.Any
        val et = mapType(elemType)
        val elemSize = sizeOfScalar(elemType)
        val total = 8 + expr.elements.size * elemSize

        val vals = expr.elements.map { emitExpr(it) to it.type }

        val raw = emitHeapAlloc("$total")
        registerArrayDrop(raw, elemType)
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

    /** Append each segment before evaluating the next, preserving spread snapshots. */
    private fun emitSpreadArrayLiteral(expr: IrExpr.ArrayLiteral): String {
        val element = (expr.type as IrType.Array).element
        val et = mapType(element)
        val stride = sizeOfScalar(element)
        var raw = emitHeapAlloc("8")
        val initialLength = nextTmp()
        emit("  $initialLength = bitcast i8* $raw to i64*")
        emit("  store i64 0, i64* $initialLength")
        usesMemcpy = true
        for (part in expr.elements) {
            val spread = part as? IrExpr.Spread
            val sourceType = spread?.let { (it.array.type as IrType.Array).element }
            val value = emitExpr(spread?.array ?: part)
            val count = if (spread != null) emitArrayLengthI64(value) else "1"
            val oldLength = emitArrayLengthI64(raw)
            val length = nextTmp()
            emit("  $length = add i64 $oldLength, $count")
            val bytes = nextTmp()
            emit("  $bytes = mul i64 $length, $stride")
            val size = nextTmp()
            emit("  $size = add i64 $bytes, 8")
            val grown = emitHeapAlloc(size)
            val lengthPtr = nextTmp()
            emit("  $lengthPtr = bitcast i8* $grown to i64*")
            emit("  store i64 $length, i64* $lengthPtr")
            val oldData = nextTmp()
            emit("  $oldData = getelementptr i8, i8* $raw, i64 8")
            val newData = nextTmp()
            emit("  $newData = getelementptr i8, i8* $grown, i64 8")
            val oldBytes = nextTmp()
            emit("  $oldBytes = mul i64 $oldLength, $stride")
            val copied = nextTmp()
            emit("  $copied = call i8* @memcpy(i8* $newData, i8* $oldData, i64 $oldBytes)")
            val destination = nextTmp()
            emit("  $destination = bitcast i8* $newData to $et*")
            if (spread == null) {
                val converted = coerceNumeric(value, part.type, element)
                val slot = nextTmp()
                emit("  $slot = getelementptr $et, $et* $destination, i64 $oldLength")
                emit("  store $et $converted, $et* $slot, align 1")
            } else {
                val st = mapType(sourceType!!)
                val sourceData = nextTmp()
                emit("  $sourceData = getelementptr i8, i8* $value, i64 8")
                val source = nextTmp()
                emit("  $source = bitcast i8* $sourceData to $st*")
                val condition = nextLabel("spread_cond")
                val body = nextLabel("spread_body")
                val end = nextLabel("spread_end")
                val before = currentBlock
                val next = "%spread_next_${labelCounter++}"
                emitTerminator("  br label %$condition")
                startBlock(condition)
                val index = nextTmp()
                emit("  $index = phi i64 [ 0, %$before ], [ $next, %$body ]")
                val done = nextTmp()
                emit("  $done = icmp uge i64 $index, $count")
                emitTerminator("  br i1 $done, label %$end, label %$body")
                startBlock(body)
                val sourceSlot = nextTmp()
                emit("  $sourceSlot = getelementptr $st, $st* $source, i64 $index")
                val loaded = nextTmp()
                emit("  $loaded = load $st, $st* $sourceSlot, align 1")
                val converted = coerceNumeric(loaded, sourceType, element)
                val offset = nextTmp()
                emit("  $offset = add i64 $oldLength, $index")
                val slot = nextTmp()
                emit("  $slot = getelementptr $et, $et* $destination, i64 $offset")
                emit("  store $et $converted, $et* $slot, align 1")
                emit("  $next = add i64 $index, 1")
                emitTerminator("  br label %$condition")
                startBlock(end)
            }
            // Only the builder's old buffer is freed; sources remain owned by callers.
            emit("  call void @__azora_free(i8* $raw)")
            raw = grown
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
        } ?: error("LLVM cannot grow a set that is not held in a variable or a field yet")
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

    /**
     * `array.remove(index)` - drops the element at that position, shifting the
     * rest down.
     *
     * By *position*, not by value: on an array `remove` takes an index, while
     * on a set it takes the element. That is the split the interpreter makes,
     * and the two backends have to agree on it or the same source means two
     * things. An index outside the array does nothing, so a caller need not
     * guard what it is about to drop.
     */
    private fun emitArrayRemoveAt(target: IrExpr, indexExpr: IrExpr, elementType: IrType, transfer: Boolean): String {
        val et = mapType(elementType)
        val raw = emitExpr(target)
        val length = emitArrayLengthI64(raw)
        val index = indexToI64(emitExpr(indexExpr), indexExpr.type)
        val lenPtr = nextTmp()
        emit("  $lenPtr = bitcast i8* $raw to i64*")
        val dataRaw = nextTmp()
        emit("  $dataRaw = getelementptr i8, i8* $raw, i64 8")
        val data = nextTmp()
        emit("  $data = bitcast i8* $dataRaw to $et*")

        val shiftCondLabel = nextLabel("arr_remove_shift_cond")
        val validLabel = nextLabel("arr_remove_valid")
        val shiftBodyLabel = nextLabel("arr_remove_shift_body")
        val shrinkLabel = nextLabel("arr_remove_shrink")
        val skipLabel = nextLabel("arr_remove_skip")
        val endLabel = nextLabel("arr_remove_end")
        val shiftedIndex = "%arr_remove_shift_${labelCounter++}"

        val nonNegative = nextTmp()
        emit("  $nonNegative = icmp sge i64 $index, 0")
        val inRange = nextTmp()
        emit("  $inRange = icmp slt i64 $index, $length")
        val valid = nextTmp()
        emit("  $valid = and i1 $nonNegative, $inRange")
        emitTerminator("  br i1 $valid, label %$validLabel, label %$skipLabel")

        startBlock(validLabel)
        val removedPtr = nextTmp()
        emit("  $removedPtr = getelementptr $et, $et* $data, i64 $index")
        val removed = nextTmp()
        emit("  $removed = load $et, $et* $removedPtr, align 1")
        if (!transfer) emitArrayElementDrop(raw, removed, elementType)
        val preheader = currentBlock
        emitTerminator("  br label %$shiftCondLabel")

        startBlock(shiftCondLabel)
        val shiftIndex = nextTmp()
        emit("  $shiftIndex = phi i64 [ $index, %$preheader ], [ $shiftedIndex, %$shiftBodyLabel ]")
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
        val tail = nextTmp()
        emit("  $tail = getelementptr $et, $et* $data, i64 $newLength")
        emit("  store $et ${defaultValue(elementType)}, $et* $tail, align 1")
        val completedBlock = currentBlock
        emitTerminator("  br label %$endLabel")

        startBlock(skipLabel)
        emitTerminator("  br label %$endLabel")

        startBlock(endLabel)
        if (!transfer) return "void"
        val result = nextTmp()
        emit("  $result = phi $et [ $removed, %$completedBlock ], [ ${defaultValue(elementType)}, %$skipLabel ]")
        return result
    }

    private fun emitArrayElementDrop(array: String, value: String, type: IrType, replacement: String? = null) {
        if (!mapType(type).endsWith("*")) return
        val header = nextTmp()
        val descriptor = nextTmp()
        val callback = nextTmp()
        val owned = nextTmp()
        emit("  $header = getelementptr i8, i8* $array, i64 -16")
        emit("  $descriptor = bitcast i8* $header to void (i8*, i64)**")
        emit("  $callback = load void (i8*, i64)*, void (i8*, i64)** $descriptor")
        if (replacement == null) emit("  $owned = icmp ne void (i8*, i64)* $callback, null") else {
            val hasDrop = nextTmp(); val changed = nextTmp()
            emit("  $hasDrop = icmp ne void (i8*, i64)* $callback, null")
            emit("  $changed = icmp ne ${mapType(type)} $value, $replacement")
            emit("  $owned = and i1 $hasDrop, $changed")
        }
        val drop = nextLabel("array.owner.drop")
        val done = nextLabel("array.owner.done")
        emitTerminator("  br i1 $owned, label %$drop, label %$done")
        startBlock(drop)
        val raw = if (mapType(type) == "i8*") value else nextTmp().also {
            emit("  $it = bitcast ${mapType(type)} $value to i8*")
        }
        emit("  call void @__azora_free(i8* $raw)")
        emitTerminator("  br label %$done")
        startBlock(done)
    }

    private fun emitArrayClear(raw: String) {
        val header = nextTmp()
        val descriptor = nextTmp()
        val callback = nextTmp()
        val owned = nextTmp()
        emit("  $header = getelementptr i8, i8* $raw, i64 -16")
        emit("  $descriptor = bitcast i8* $header to void (i8*, i64)**")
        emit("  $callback = load void (i8*, i64)*, void (i8*, i64)** $descriptor")
        emit("  $owned = icmp ne void (i8*, i64)* $callback, null")
        val drop = nextLabel("array.clear.drop")
        val done = nextLabel("array.clear.done")
        emitTerminator("  br i1 $owned, label %$drop, label %$done")
        startBlock(drop)
        emit("  call void $callback(i8* $raw, i64 1)")
        emitTerminator("  br label %$done")
        startBlock(done)
        val length = nextTmp()
        emit("  $length = bitcast i8* $raw to i64*")
        emit("  store i64 0, i64* $length")
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
        } ?: error("LLVM cannot grow a map that is not held in a variable or a field yet")
        emitTerminator("  br label %$endLabel")

        startBlock(endLabel)
    }

    private fun emitStdlibSequenceFromPacked(name: String, packed: String, elementType: IrType): String {
        val length = emitArrayLengthI64(packed)
        val dataRaw = nextTmp()
        emit("  $dataRaw = getelementptr i8, i8* $packed, i64 8")
        val (count32, capacity64, capacity32) = emitStdlibBufferSizing(length)
        val data = emitCopiedBufferFromPacked(dataRaw, length, elementType, capacity64)
        val ptr = emitStructObject(name) ?: return defaultValue(IrType.Named(name))
        emitStoreStructField(name, ptr, "data", data, IrType.Pointer(elementType))
        emitStoreStructField(name, ptr, "size", count32, IrType.Int)
        emitStoreStructField(name, ptr, "capacity", capacity32, IrType.Int)
        return ptr
    }

    private fun emitStdlibMapFromPacked(name: String, packed: String, map: IrType.Map): String {
        val length = emitArrayLengthI64(packed)
        val keysRaw = nextTmp()
        emit("  $keysRaw = getelementptr i8, i8* $packed, i64 8")
        val keyBytes = nextTmp()
        emit("  $keyBytes = mul i64 $length, ${sizeOfScalar(map.key)}")
        val valuesRaw = nextTmp()
        emit("  $valuesRaw = getelementptr i8, i8* $keysRaw, i64 $keyBytes")
        val (count32, capacity64, capacity32) = emitStdlibBufferSizing(length)
        val keys = emitCopiedBufferFromPacked(keysRaw, length, map.key, capacity64)
        val values = emitCopiedBufferFromPacked(valuesRaw, length, map.value, capacity64)
        val ptr = emitStructObject(name) ?: return defaultValue(IrType.Named(name))
        emitStoreStructField(name, ptr, "keys", keys, IrType.Pointer(map.key))
        emitStoreStructField(name, ptr, "values", values, IrType.Pointer(map.value))
        emitStoreStructField(name, ptr, "size", count32, IrType.Int)
        emitStoreStructField(name, ptr, "capacity", capacity32, IrType.Int)
        return ptr
    }

    private fun emitStdlibBufferSizing(length: String): Triple<String, String, String> {
        val count32 = nextTmp()
        emit("  $count32 = trunc i64 $length to i32")
        val needsMin = nextTmp()
        emit("  $needsMin = icmp ult i64 $length, 8")
        val capacity64 = nextTmp()
        emit("  $capacity64 = select i1 $needsMin, i64 8, i64 $length")
        val capacity32 = nextTmp()
        emit("  $capacity32 = trunc i64 $capacity64 to i32")
        return Triple(count32, capacity64, capacity32)
    }

    private fun emitCopiedBufferFromPacked(sourceRaw: String, length: String, elementType: IrType, capacity64: String): String {
        val elemSize = sizeOfScalar(elementType)
        val bytes = nextTmp()
        emit("  $bytes = mul i64 $capacity64, $elemSize")
        val destinationRaw = emitHeapAlloc(bytes)
        emitCopyPackedBuffer(sourceRaw, destinationRaw, length, elementType)
        return destinationRaw
    }

    private fun emitCopyPackedBuffer(sourceRaw: String, destinationRaw: String, length: String, elementType: IrType) {
        val et = mapType(elementType)
        val source = nextTmp()
        emit("  $source = bitcast i8* $sourceRaw to $et*")
        val destination = nextTmp()
        emit("  $destination = bitcast i8* $destinationRaw to $et*")

        val condLabel = nextLabel("stdlib_copy_cond")
        val bodyLabel = nextLabel("stdlib_copy_body")
        val endLabel = nextLabel("stdlib_copy_end")
        val nextIndex = "%stdlib_copy_next_${labelCounter++}"
        val preheader = currentBlock

        emitTerminator("  br label %$condLabel")
        startBlock(condLabel)
        val index = nextTmp()
        emit("  $index = phi i64 [ 0, %$preheader ], [ $nextIndex, %$bodyLabel ]")
        val inRange = nextTmp()
        emit("  $inRange = icmp ult i64 $index, $length")
        emitTerminator("  br i1 $inRange, label %$bodyLabel, label %$endLabel")

        startBlock(bodyLabel)
        val sourcePtr = nextTmp()
        emit("  $sourcePtr = getelementptr $et, $et* $source, i64 $index")
        val value = nextTmp()
        emit("  $value = load $et, $et* $sourcePtr, align 1")
        val destinationPtr = nextTmp()
        emit("  $destinationPtr = getelementptr $et, $et* $destination, i64 $index")
        emit("  store $et $value, $et* $destinationPtr, align 1")
        emit("  $nextIndex = add i64 $index, 1")
        emitTerminator("  br label %$condLabel")

        startBlock(endLabel)
    }

    private fun emitStructObject(name: String): String? {
        if (name !in structDefs) return null
        val st = "%struct.${sanitizeName(name)}"
        val sizeGep = nextTmp()
        emit("  $sizeGep = getelementptr $st, $st* null, i32 1")
        val size = nextTmp()
        emit("  $size = ptrtoint $st* $sizeGep to i64")
        val raw = emitHeapAlloc(size)
        val ptr = nextTmp()
        emit("  $ptr = bitcast i8* $raw to $st*")
        return ptr
    }

    private fun emitStoreStructField(structName: String, ptr: String, fieldName: String, value: String, sourceType: IrType) {
        val def = structDefs[structName] ?: return
        val fieldIndex = def.fields.indexOfFirst { it.name == fieldName }
        if (fieldIndex < 0) return
        val field = def.fields[fieldIndex]
        val st = "%struct.${sanitizeName(structName)}"
        val fp = nextTmp()
        emit("  $fp = getelementptr $st, $st* $ptr, i32 0, i32 $fieldIndex")
        val stored = coerceNumeric(value, sourceType, field.type)
        val ft = mapType(field.type)
        emit("  store $ft $stored, $ft* $fp")
    }

    /**
     * Stops the program unless [index] names an element of the array [raw]:
     * a safe index reads and writes only the array's own elements, as on every
     * other target. One unsigned comparison covers a negative index too, which
     * is a very large one once its sign is ignored.
     */
    private fun emitBoundsCheck(raw: String, index: String) {
        val size = emitArrayLengthI64(raw)
        val valid = nextTmp()
        emit("  $valid = icmp ult i64 $index, $size")
        val ok = nextLabel("index_ok")
        val bad = nextLabel("index_fail")
        emitTerminator("  br i1 $valid, label %$ok, label %$bad")
        startBlock(bad)
        usesIndexFail = true
        emit("  call void @__azora_index_fail(i64 $index, i64 $size)")
        emitTerminator("  unreachable")
        startBlock(ok)
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
        return when (target) {
            is IrExpr.Var -> {
                val local = localVars[target.name]
                local ?: ("@${target.name}" to mapType(target.type))
            }
            is IrExpr.Member -> emitFieldPtr(target.target, target.name)?.let { (pointer, _, llvmType) ->
                pointer to llvmType
            }
            else -> null
        }
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
        registerArrayDrop(grown, elemType)
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
            // Growing reallocates: storing the new buffer nowhere would leave
            // whatever held the array pointing at freed memory.
            error("LLVM cannot grow an array that is not held in a variable or a field yet")
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
        emitBoundsCheck(raw, idx)
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
        if (tt is IrType.Pointer) {
            val et = mapType(tt.inner)
            val raw = emitExpr(expr.target)
            val data = nextTmp()
            emit("  $data = bitcast i8* $raw to $et*")
            val idxRaw = emitExpr(expr.index)
            val idx = indexToI64(idxRaw, expr.index.type)
            val ep = nextTmp()
            emit("  $ep = getelementptr $et, $et* $data, i64 $idx")
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
        error("LLVM cannot index ${expr.target.type.shown()} yet")
    }

    private fun emitExchange(stmt: IrStmt.Exchange) {
        fun location(place: IrExpr): Pair<String, String> = when (place) {
            is IrExpr.Var -> {
                check(place.name !in lazyLocals && (currentFunctionName to place.name) !in reactiveStorage) {
                    "exchange of reactive/lazy storage is not supported"
                }
                localVars[place.name] ?: ("@${place.name}" to mapType(place.type))
            }
            is IrExpr.Member -> {
                val (address, _, storageType) = emitFieldPtr(place.target, place.name)
                    ?: error("exchange requires a stored pack field")
                check(storageType == mapType(place.type)) { "exchange of erased generic fields requires typed storage lowering" }
                address to storageType
            }
            is IrExpr.Index -> {
                check(place.target.type is IrType.Array) { "exchange requires built-in array storage" }
                val raw = emitExpr(place.target)
                val index = indexToI64(emitExpr(place.index), place.index.type)
                emitBoundsCheck(raw, index)
                val type = mapType(place.type)
                val data = nextTmp()
                emit("  $data = getelementptr i8, i8* $raw, i64 8")
                val typed = nextTmp()
                emit("  $typed = bitcast i8* $data to $type*")
                val element = nextTmp()
                emit("  $element = getelementptr $type, $type* $typed, i64 $index")
                element to type
            }
            else -> error("unsupported exchange location")
        }
        val (left, type) = location(stmt.left)
        val (right, rightType) = location(stmt.right)
        check(type == rightType) { "exchange storage types differ" }
        val a = nextTmp()
        val b = nextTmp()
        emit("  $a = load $type, $type* $left, align 1")
        emit("  $b = load $type, $type* $right, align 1")
        emit("  store $type $b, $type* $left, align 1")
        emit("  store $type $a, $type* $right, align 1")
    }

    private fun emitIndexAssign(stmt: IrStmt.IndexAssign) {
        val tt = stmt.target.type
        if (tt is IrType.Array) {
            val et = mapType(tt.element)
            val ep = emitArrayElemPtr(stmt.target, stmt.index, tt.element)
            val raw = emitExpr(stmt.value)
            val value = coerceNumeric(raw, stmt.value.type, tt.element)
            val old = nextTmp()
            emit("  $old = load $et, $et* $ep, align 1")
            val array = emitExpr(stmt.target)
            emitArrayElementDrop(array, old, tt.element, value)
            emit("  store $et $value, $et* $ep, align 1")
            // The array can outlive the current `scope alloc`; root the element.
            return
        }
        if (tt is IrType.Pointer) {
            val et = mapType(tt.inner)
            val raw = emitExpr(stmt.target)
            val data = nextTmp()
            emit("  $data = bitcast i8* $raw to $et*")
            val idxRaw = emitExpr(stmt.index)
            val idx = indexToI64(idxRaw, stmt.index.type)
            val ep = nextTmp()
            emit("  $ep = getelementptr $et, $et* $data, i64 $idx")
            val rawValue = emitExpr(stmt.value)
            val value = coerceNumeric(rawValue, stmt.value.type, tt.inner)
            val old = nextTmp()
            emit("  $old = load $et, $et* $ep, align 1")
            emitArrayElementDrop(raw, old, tt.inner, value)
            emit("  store $et $value, $et* $ep, align 1")
            return
        }
        if (tt is IrType.Map) {
            emitMapIndexAssign(stmt, tt)
            return
        }
        error("LLVM cannot assign through an index of ${stmt.target.type.shown()} yet")
    }

    private fun emitUnary(expr: IrExpr.Unary): String {
        val operand = emitExpr(expr.operand)
        val tmp = nextTmp()
        when (expr.op) {
            IrUnaryOp.NEG -> {
                val llvmType = mapType(expr.type)
                when {
                    IrType.isInteger(expr.type) -> emit("  $tmp = sub $llvmType 0, $operand")
                    expr.type in IrType.floatTypes -> emit("  $tmp = fneg $llvmType $operand")
                    else -> {
                        error("LLVM cannot negate an erased ${expr.type.shown()} yet")
                    }
                }
            }
            IrUnaryOp.NOT -> emit("  $tmp = xor i1 $operand, 1")
            IrUnaryOp.BIT_NOT -> emit("  $tmp = xor ${mapType(expr.type)} $operand, -1")
        }
        return tmp
    }

    private fun emitIncDec(expr: IrExpr.IncDec): String {
        val type = mapType(expr.type)
        val (slot, slotType) = localVars[expr.target.name]
            ?: ("@${expr.target.name}" to type)
        val old = nextTmp()
        emit("  $old = load $slotType, $slotType* $slot")
        val amount = if (expr.type in IrType.floatTypes) floatConst(1.0, expr.type, null) else "1"
        val updated = nextTmp()
        val op = if (expr.type in IrType.floatTypes) {
            if (expr.delta > 0) "fadd" else "fsub"
        } else {
            if (expr.delta > 0) "add" else "sub"
        }
        emit("  $updated = $op $type $old, $amount")
        emit("  store $type $updated, $type* $slot")
        return if (expr.prefix) updated else old
    }

    private fun emitBinary(expr: IrExpr.Binary): String {
        // String-typed operations are routed to runtime helpers.
        if (expr.left.type == IrType.String || expr.right.type == IrType.String) {
            return emitStringBinary(expr)
        }

        val leftType = expr.left.type
        val rightType = expr.right.type

        // Short-circuit boolean && / || via control flow.
        if (leftType == IrType.Bool && (expr.op == IrBinaryOp.AND || expr.op == IrBinaryOp.OR)) {
            return emitShortCircuit(expr)
        }

        // Mixed numeric operands (`Int + Double`, `Byte < Long`, …) are widened to a
        // common type first, so the machine op sees two operands of one LLVM type.
        val bothNumeric = isNumericLike(leftType) && isNumericLike(rightType)
        val opType = if (bothNumeric) commonNumeric(leftType, rightType) else leftType
        val llvmType = mapType(opType)

        val left = emitExpr(expr.left).let { if (bothNumeric) coerceNumeric(it, leftType, opType) else it }
        val right = emitExpr(expr.right).let { if (bothNumeric) coerceNumeric(it, rightType, opType) else it }
        val guardedInteger = (IrType.isInteger(opType) || opType == IrType.Char) &&
            expr.op in setOf(IrBinaryOp.DIV, IrBinaryOp.MOD, IrBinaryOp.SHL, IrBinaryOp.SHR)
        // A named result can be reserved before the guard's temporaries. LLVM
        // numbered registers, in contrast, must be defined in increasing order.
        val tmp = if (guardedInteger) "%arithmetic.${labelCounter++}" else nextTmp()

        when {
            // Pointer-typed operands (nullable, erased `Any`, raw pointers, and
            // structs/slots - all lowered to `i8*`): only equality against null or
            // another pointer is meaningful. A raw `0` in a pointer comparison is the
            // null sentinel - LLVM requires `null`, not an integer, for pointer icmp.
            (opType is IrType.Nullable || opType == IrType.Any ||
                opType is IrType.Pointer || opType is IrType.Named) &&
                (expr.op == IrBinaryOp.EQ || expr.op == IrBinaryOp.NEQ) -> {
                val pred = if (expr.op == IrBinaryOp.EQ) "eq" else "ne"
                val l = if (left == "0") "null" else left
                val r = if (right == "0") "null" else right
                emit("  $tmp = icmp $pred i8* $l, $r")
            }
            IrType.isInteger(opType) || opType == IrType.Char -> {
                val u = isUnsigned(opType)
                val width = (opType as? IrType.Integer)?.bits ?: if (opType == IrType.Char) 8 else 64
                var divisor = right
                var overflow: String? = null
                if (expr.op == IrBinaryOp.DIV || expr.op == IrBinaryOp.MOD) {
                    val zero = nextTmp()
                    emit("  $zero = icmp eq $llvmType $right, 0")
                    emitArithmeticFailure(zero, "panic: integer division by zero")
                    if (!u) {
                        val minimum = nextTmp()
                        val atMinimum = nextTmp()
                        val negativeOne = nextTmp()
                        val overflowing = nextTmp()
                        val safeDivisor = nextTmp()
                        emit("  $minimum = shl $llvmType 1, ${width - 1}")
                        emit("  $atMinimum = icmp eq $llvmType $left, $minimum")
                        emit("  $negativeOne = icmp eq $llvmType $right, -1")
                        emit("  $overflowing = and i1 $atMinimum, $negativeOne")
                        emit("  $safeDivisor = select i1 $overflowing, $llvmType 1, $llvmType $right")
                        divisor = safeDivisor
                        overflow = overflowing
                    }
                }
                if (expr.op == IrBinaryOp.SHL || expr.op == IrBinaryOp.SHR) {
                    val invalid = nextTmp()
                    // Unsigned comparison also rejects negative shift counts.
                    emit("  $invalid = icmp uge $llvmType $right, $width")
                    emitArithmeticFailure(invalid, "panic: integer shift count is outside the operand width")
                }
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
                if (overflow != null) {
                    val raw = nextTmp()
                    emit("  $raw = $inst $left, $divisor")
                    emit("  $tmp = select i1 $overflow, $llvmType ${if (expr.op == IrBinaryOp.MOD) "0" else left}, $llvmType $raw")
                } else emit("  $tmp = $inst $left, $right")
            }
            opType in IrType.floatTypes -> {
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
            leftType == IrType.Unit -> {
                // Unit has one value, encoded as i8 zero. Equality is therefore
                // always true and inequality always false, but an icmp keeps the
                // ordinary expression shape and validates both operands.
                when (expr.op) {
                    IrBinaryOp.EQ -> emit("  $tmp = icmp eq i8 $left, $right")
                    IrBinaryOp.NEQ -> emit("  $tmp = icmp ne i8 $left, $right")
                    else -> error("Unsupported Unit op: ${expr.op}")
                }
            }
            else -> {
                error("LLVM cannot apply ${expr.op} to ${expr.left.type.shown()} yet")
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

        val incoming = mutableListOf<Pair<String, String>>()
        startBlock(thenLabel)
        val thenRaw = emitExpr(expr.thenExpr)
        val thenBlock = currentBlock
        if (!terminated) {
            val thenValue = coerceNumeric(thenRaw, expr.thenExpr.type, expr.type)
            incoming += thenValue to thenBlock
            emitTerminator("  br label %$endLabel")
        }

        startBlock(elseLabel)
        val elseRaw = emitExpr(expr.elseExpr)
        val elseBlock = currentBlock
        if (!terminated) {
            val elseValue = coerceNumeric(elseRaw, expr.elseExpr.type, expr.type)
            incoming += elseValue to elseBlock
            emitTerminator("  br label %$endLabel")
        }

        startBlock(endLabel)
        if (incoming.isEmpty()) {
            emitTerminator("  unreachable")
            return defaultValue(expr.type)
        }
        val tmp = nextTmp()
        val arms = incoming.joinToString(", ") { (value, block) -> "[ $value, %$block ]" }
        emit("  $tmp = phi ${mapType(expr.type)} $arms")
        return tmp
    }

    /** Lowers `&&` / `||` with short-circuit evaluation via phi. */
    private fun emitArithmeticFailure(invalid: String, message: String) {
        usesPuts = true
        usesAbort = true
        val failure = nextLabel("arithmetic_fail")
        val valid = nextLabel("arithmetic_valid")
        emitTerminator("  br i1 $invalid, label %$failure, label %$valid")
        startBlock(failure)
        val text = gepString(addStringConstant(message))
        val ignored = nextTmp()
        emit("  $ignored = call i32 @puts(i8* $text)")
        emit("  call void @__azora_abort()")
        emitTerminator("  unreachable")
        startBlock(valid)
    }

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

    /**
     * Lowers a call, checking the error slot when the callee can fail.
     *
     * The check goes here rather than at every use of the result so that a
     * failed call never reaches the code that would consume its value.
     */
    private fun emitCall(expr: IrExpr.Call): String {
        if (expr.name == Intrinsics.NULL_COALESCE) return emitNullCoalesce(expr)
        val result = emitCallValue(expr)
        if (expr.name in failableFunctions) emitErrorCheck()
        return when (expr.type) {
            // Unit remains a first-class singleton even where the ABI erases
            // the return slot. Zero is its private native representation.
            IrType.Unit -> "0"
            // A Nothing call cannot continue. This terminator also gives a
            // bottom-typed branch the exact control-flow shape LLVM expects.
            IrType.Nothing -> {
                emitTerminator("  unreachable")
                "0"
            }
            else -> result
        }
    }

    private fun emitCallValue(expr: IrExpr.Call): String {
        if (expr.receiver != null) {
            val functionType = expr.receiver.type as? IrType.Function
                ?: error("indirect call receiver is not a callable type")
            val closure = emitExpr(expr.receiver)
            val fnField = nextTmp()
            val envField = nextTmp()
            val erasedFn = nextTmp()
            val environment = nextTmp()
            val functionPointer = nextTmp()
            emit("  $fnField = getelementptr %azora.closure, %azora.closure* $closure, i32 0, i32 0")
            emit("  $envField = getelementptr %azora.closure, %azora.closure* $closure, i32 0, i32 1")
            emit("  $erasedFn = load i8*, i8** $fnField")
            emit("  $environment = load i8*, i8** $envField")
            val signature = closureFunctionType(functionType)
            emit("  $functionPointer = bitcast i8* $erasedFn to $signature*")
            val expected = functionType.params + functionType.receivers
            val args = expr.args.mapIndexed { index, argument ->
                val type = expected.getOrNull(index) ?: argument.type
                val value = coerceNumeric(emitExpr(argument), argument.type, type)
                "${mapType(type)} $value"
            }
            val callArgs = (listOf("i8* $environment") + args).joinToString(", ")
            return if (expr.type == IrType.Unit || expr.type == IrType.Nothing) {
                emit("  call void $functionPointer($callArgs)")
                "void"
            } else {
                val result = nextTmp()
                emit("  $result = call ${mapType(expr.type)} $functionPointer($callArgs)")
                result
            }
        }
        // `x is Variant` lowers to `@__isCheck(slot, "Variant")`; ensure the helper
        // (and strcmp) is emitted. The general call path emits the call itself.
        if (expr.name == "__isCheck") { usesIsCheck = true; usesStrcmp = true }
        // Compiler string/array intrinsics are defined as runtime helpers.
        // Keyed by the bare name, remembering the mangled symbol the call used,
        // so the definition is emitted under the name the caller asks for.
        stringIntrinsicOf(expr.name)?.let { neededIntrinsics[it] = expr.name }
        val atomic = listOf("_atomicLoad", "_atomicStore", "_atomicAdd", "_atomicCas")
            .firstOrNull { symbolDenotes(expr.name, it) }
        if (atomic != null) {
            val raw = emitExpr(expr.args[0])
            val word = nextTmp()
            emit("  $word = bitcast i8* $raw to i32*")
            if (atomic == "_atomicStore") {
                val value = emitExpr(expr.args[1])
                emit("  store atomic i32 $value, i32* $word seq_cst, align 4")
                return "void"
            }
            return when (atomic) {
                "_atomicLoad" -> nextTmp().also {
                    emit("  $it = load atomic i32, i32* $word seq_cst, align 4")
                }
                "_atomicAdd" -> {
                    val value = emitExpr(expr.args[1])
                    nextTmp().also { emit("  $it = atomicrmw add i32* $word, i32 $value seq_cst") }
                }
                else -> {
                    val expected = emitExpr(expr.args[1])
                    val replacement = emitExpr(expr.args[2])
                    val pair = nextTmp()
                    emit("  $pair = cmpxchg i32* $word, i32 $expected, i32 $replacement seq_cst seq_cst")
                    nextTmp().also { emit("  $it = extractvalue { i32, i1 } $pair, 1") }
                }
            }
        }
        // `Array::fill<T>(count)` allocates `[ i64 length, T×count ]` (the array
        // layout used by `emitArrayLiteral`); handled inline since element size
        // varies per instantiation.
        if (symbolDenotes(expr.name, "Array_fill")) {
            val elemType = (expr.type as? IrType.Array)?.element ?: IrType.Any
            val elemSize = sizeOfScalar(elemType)
            val count = emitExpr(expr.args[0])
            val count64 = nextTmp(); emit("  $count64 = sext i32 $count to i64")
            val bytes = nextTmp(); emit("  $bytes = mul i64 $count64, $elemSize")
            val total = nextTmp(); emit("  $total = add i64 $bytes, 8")
            val raw = emitHeapAlloc(total)
            val lenPtr = nextTmp(); emit("  $lenPtr = bitcast i8* $raw to i64*")
            emit("  store i64 $count64, i64* $lenPtr")
            return raw
        }
        if (symbolDenotes(expr.name, "println")) return emitPrintln(expr)
        if (symbolDenotes(expr.name, "print")) return emitPrintln(expr, newline = false)
        if (expr.name == "async") {
            val lambda = expr.args.singleOrNull() as? IrExpr.Lambda
                ?: error("LLVM async lowering requires a task block")
            val resultType = (expr.type as? IrType.Task)?.result ?: IrType.Any
            return emitLambdaTaskSpawn(lambda, resultType, "async")
        }
        if (expr.name == "__delay") {
            // `delay ms` - libc sleeps in microseconds, so scale the operand.
            usesUsleep = true
            val ms = coerceNumeric(emitExpr(expr.args.single()), expr.args.single().type, IrType.Int)
            val micros = nextTmp()
            emit("  $micros = mul i32 $ms, 1000")
            // `usleep` returns a status this has no use for, but LLVM numbers
            // every unnamed result, so it still has to be bound.
            val status = nextTmp()
            emit("  $status = call i32 @usleep(i32 $micros)")
            return "void"
        }
        if (expr.name == "__launch") {
            val lambda = expr.args.singleOrNull() as? IrExpr.Lambda
                ?: error("LLVM launch lowering requires a task block")
            emitLambdaTaskSpawn(lambda, IrType.Unit, "launch")
            return "void"
        }
        if (expr.name == "__isolated") {
            return emitIsolatedCopy(expr.args.single())
        }
        if (expr.name == "__panic") {
            // Unrecoverable: report and stop. The message is printed before the
            // abort so the process leaves a reason behind rather than only a
            // signal. `abort` is noreturn, so whatever the block ends with
            // after this is unreachable - which is what makes it correct for a
            // `panic` to stand where a value was expected.
            usesPuts = true
            usesAbort = true
            val message = emitExpr(expr.args.single())
            val printed = nextTmp()
            emit("  $printed = call i32 @puts(i8* $message)")
            emit("  call void @__azora_abort()")
            return "void"
        }
        if (expr.name == "__alloc") {
            return emitPointerAlloc(expr.args.single(), expr.type)
        }
        if (expr.name == "__allocBuffer") {
            return emitPointerBufferAlloc(expr)
        }
        if (expr.name == "__deref") {
            return emitPointerDeref(expr.args.single(), expr.type)
        }
        if (expr.name == "__derefAssign") {
            emitPointerAssign(expr.args[0], expr.args[1])
            return "void"
        }
        val owningFactory = listOf("sharedOf", "syncSharedOf", "uniqueOf")
            .firstOrNull { symbolDenotes(expr.name, it) }
        if (owningFactory != null && expr.args.size == 1 && expr.type is IrType.Named) {
            // These library factories retain T only at their call site. Allocate
            // there so the drop descriptor survives their otherwise erased ABI.
            val named = expr.type as IrType.Named
            val value = expr.args.single()
            val pointer = IrExpr.Call("__alloc", listOf(value), IrType.Pointer(value.type, mutable = true))
            val args = if (owningFactory == "uniqueOf") listOf(pointer, IrExpr.BoolLiteral(true))
                else listOf(pointer, IrExpr.Call("__alloc", listOf(IrExpr.IntLiteral(1)), IrType.Pointer(IrType.Int, mutable = true)), IrExpr.IntLiteral(1))
            val fields = structDefs.getValue(named.name).fields.take(args.size).map { it.name }
            return emitStructCtor(IrExpr.StructCtor(named.name, fields, args, named))
        }
        if (expr.name == "__take") return emitOwnershipTake(expr.args.single())
        if (expr.name == "__purge") {
            val value = expr.args.single()
            val type = value.type
            val inner = (type as? IrType.Nullable)?.inner ?: type
            check(inner is IrType.Pointer || inner is IrType.Array || inner is IrType.Function || (inner is IrType.Named && (inner.name in structDefs || inner.name in specDispatch || inner.name in ownedSlots))) {
                "purge of $type is not supported by the LLVM target"
            }
            val emitted = emitOwnershipTake(value)
            val raw = if (mapType(type) == "i8*") emitted else nextTmp().also {
                emit("  $it = bitcast ${mapType(type)} $emitted to i8*")
            }
            usesAllocatorRuntime = true
            emit("  call void @__azora_free(i8* $raw)")
            return "void"
        }
        if (symbolDenotes(expr.name, "concurrency_cancel")) {
            // pthread cancellation bypasses Azora destructors and can return a
            // PTHREAD_CANCELED sentinel where the runtime expects an allocation.
            error("LLVM task cancellation is not supported until cancellation-safe ownership cleanup is implemented")
        }

        // Coerce arguments to the callee's declared parameter types (numeric
        // widening such as an Int literal passed to a Double/Long parameter).
        val declared = funcParamTypes[expr.name]
        // `toString(x)` is `"${'$'}{x}"`: the rendering interpolation uses. An
        // aggregate was rendered in IR already; an erased value carries no type
        // to render it by, and printing its bits as a number would be wrong.
        if (symbolDenotes(expr.name, "toString") && expr.args.size == 1) {
            val value = expr.args.single()
            if (value.type == IrType.Any) error("LLVM cannot convert an erased value to a String yet")
            return stringify(value)
        }
        if (declared == null && expr.name in localVars) {
            error("LLVM cannot call the function value '${expr.name}' this way yet")
        }
        // Compiler-provided bridges retain their internal aggregate ABI; C
        // adapters are checked only for externally linked calls.
        val native = nativeExterns[expr.name]?.takeIf {
            stringIntrinsicOf(it.name) == null && mathIntrinsicOf(it) == null && osIntrinsicBody(it) == null
        }
        if (native != null) NativeAbi.checkSignature(native)
        val bySlot = if (native == null) slotParams[expr.name].orEmpty() else emptySet()
        val writeBacks = mutableListOf<() -> Unit>()
        val args = expr.args.mapIndexed { i, arg ->
            val paramType = declared?.getOrNull(i) ?: arg.type
            if (native != null && paramType is IrType.Function) {
                return@mapIndexed "${nativeType(paramType)} ${emitNativeCallback(arg, paramType)}"
            }
            if (i in bySlot) {
                val type = mapType(paramType)
                return@mapIndexed "$type* ${emitArgumentSlot(arg, paramType, writeBacks)}"
            }
            val emitted = emitExpr(arg)
            if (native == null) qualifyConcreteOwnership(emitted, arg.type)
            val value = coerceNumeric(emitted, arg.type, paramType)
            "${if (native == null) mapType(paramType) else nativeParameterType(paramType)} $value"
        }.joinToString(", ")
        val physicalReturn = funcReturnTypes[expr.name] ?: expr.type
        val retType = if (native == null) mapType(physicalReturn) else nativeReturnType(physicalReturn)
        return if (expr.type == IrType.Unit || expr.type == IrType.Nothing) {
            emit("  call void @${expr.name}($args)")
            writeBacks.asReversed().forEach { it() }
            "void"
        } else {
            val tmp = nextTmp()
            emit("  $tmp = call $retType @${expr.name}($args)")
            writeBacks.asReversed().forEach { it() }
            if (expr.type is IrType.Task) emitTaskScopeAttach(tmp)
            val result = coerceNumeric(tmp, physicalReturn, expr.type)
            if (native == null) qualifyConcreteOwnership(result, expr.type)
            result
        }
    }

    /**
     * The address a `x!` argument is passed by: the variable's own slot, a
     * field or an array element in place. Storage held in another width (an
     * erased field), a lazy or reactive binding, or a temporary is copied into
     * a fresh slot that is written back after the call, so the callee's
     * writes still reach the place the caller named.
     */
    private fun emitArgumentSlot(arg: IrExpr, paramType: IrType, writeBacks: MutableList<() -> Unit>): String {
        val type = mapType(paramType)
        val direct: Pair<String, String>? = when (arg) {
            is IrExpr.Var -> if (arg.name in lazyLocals || (currentFunctionName to arg.name) in reactiveStorage) null
                else localVars[arg.name] ?: globalVars[arg.name]?.let { "@${arg.name}" to mapType(arg.type) }
            is IrExpr.Member -> emitFieldPtr(arg.target, arg.name)?.let { it.first to it.third }
            is IrExpr.Index -> if (arg.target.type !is IrType.Array) null else {
                val raw = emitExpr(arg.target)
                val index = indexToI64(emitExpr(arg.index), arg.index.type)
                emitBoundsCheck(raw, index)
                val element = mapType(arg.type)
                val data = nextTmp()
                emit("  $data = getelementptr i8, i8* $raw, i64 8")
                val typed = nextTmp()
                emit("  $typed = bitcast i8* $data to $element*")
                val address = nextTmp()
                emit("  $address = getelementptr $element, $element* $typed, i64 $index")
                address to element
            }
            else -> null
        }
        if (direct != null && direct.second == type) return direct.first
        // Copy in, call, copy out. The slot is released after the call, so a
        // call in a loop does not grow the stack.
        val value = coerceNumeric(emitExpr(arg), arg.type, paramType)
        usesStackSave = true
        val mark = nextTmp()
        emit("  $mark = call i8* @llvm.stacksave()")
        val spill = nextTmp()
        emit("  $spill = alloca $type")
        emit("  store $type $value, $type* $spill")
        writeBacks += {
            if (direct != null) {
                val updated = nextTmp()
                emit("  $updated = load $type, $type* $spill")
                val stored = if (direct.second == type) updated else coerceNumeric(updated, paramType, arg.type)
                emit("  store ${direct.second} $stored, ${direct.second}* ${direct.first}")
            }
            emit("  call void @llvm.stackrestore(i8* $mark)")
        }
        return spill
    }

    private fun nativeType(type: IrType): String = if (type is IrType.Function) {
        "${abiReturnType(type.ret)} (${type.params.joinToString(", ") { nativeType(it) }})*"
    } else mapType(type)

    private fun nativeParameterType(type: IrType): String = nativeType(type) +
        NativeAbi.extension(type).takeIf { it.isNotEmpty() }?.let { " ${it.trimEnd()}" }.orEmpty()

    private fun nativeReturnType(type: IrType): String = NativeAbi.extension(type) + abiReturnType(type)

    /** A stateless lambda has static lifetime and needs no environment pointer in C. */
    private fun emitNativeCallback(argument: IrExpr, type: IrType.Function): String {
        NativeAbi.checkCallback(type)
        val lambda = argument as? IrExpr.Lambda
            ?: error("LLVM C callbacks require a direct lambda without captures; stored Azora closures need an explicit context adapter")
        check(collectCaptures(lambda).isEmpty()) {
            "LLVM C callbacks cannot capture values; use an explicit context pointer and a lifetime-managed adapter"
        }
        val id = taskContextCounter++
        val bodyName = "__azora_c_callback_body_$id"
        val name = "__azora_c_callback_$id"
        deferredFunctions += renderDeferredFunction {
            emitClosureBody(bodyName, "%azora.unused.ctx", emptyList(), lambda)
        }
        val params = type.params.mapIndexed { index, t -> "${nativeParameterType(t)} %a$index" }
        val args = type.params.mapIndexed { index, t -> "${mapType(t)} %a$index" }
        deferredFunctions += buildString {
            appendLine("define ${nativeReturnType(type.ret)} @$name(${params.joinToString(", ")}) {")
            appendLine("entry:")
            val arguments = (listOf("i8* null") + args).joinToString(", ")
            if (type.ret == IrType.Unit) {
                appendLine("  call void @$bodyName($arguments)")
                appendLine("  ret void")
            } else {
                appendLine("  %result = call ${mapType(type.ret)} @$bodyName($arguments)")
                appendLine("  ret ${mapType(type.ret)} %result")
            }
            appendLine("}")
        }
        return "@$name"
    }

    /**
     * A tuple is a heap aggregate, as a pack is: each component at an offset
     * aligned to its own width. The tuple's type decides the layout, so an
     * erased `(K, V)` holds eight-byte slots whatever the components were.
     */
    private fun tupleOffsets(types: List<IrType>): Pair<List<Int>, Int> {
        var end = 0
        val offsets = types.map { type ->
            val width = sizeOfScalar(type)
            val at = (end + width - 1) / width * width
            end = at + width
            at
        }
        return offsets to end
    }

    private fun emitTupleLit(expr: IrExpr.TupleLit): String {
        val types = (expr.type as? IrType.Tuple)?.elements ?: expr.elements.map { it.type }
        val (offsets, size) = tupleOffsets(types)
        // Components are evaluated left to right before the storage exists.
        val values = expr.elements.mapIndexed { i, element -> coerceNumeric(emitExpr(element), element.type, types[i]) }
        val raw = emitHeapAlloc("${maxOf(size, 1)}")
        for ((i, value) in values.withIndex()) {
            val slot = nextTmp()
            emit("  $slot = getelementptr i8, i8* $raw, i64 ${offsets[i]}")
            val typed = nextTmp()
            emit("  $typed = bitcast i8* $slot to ${mapType(types[i])}*")
            emit("  store ${mapType(types[i])} $value, ${mapType(types[i])}* $typed, align 1")
        }
        return raw
    }

    private fun emitTupleAccess(expr: IrExpr.TupleAccess): String {
        val types = (expr.target.type as? IrType.Tuple)?.elements
            ?: error("tuple component .${expr.index} of ${expr.target.type} has no tuple layout")
        val stored = types[expr.index]
        val raw = emitExpr(expr.target)
        val slot = nextTmp()
        emit("  $slot = getelementptr i8, i8* $raw, i64 ${tupleOffsets(types).first[expr.index]}")
        val typed = nextTmp()
        emit("  $typed = bitcast i8* $slot to ${mapType(stored)}*")
        val loaded = nextTmp()
        emit("  $loaded = load ${mapType(stored)}, ${mapType(stored)}* $typed, align 1")
        return coerceNumeric(loaded, stored, expr.type)
    }

    private fun emitPointerAlloc(valueExpr: IrExpr, pointerType: IrType): String {
        val value = emitExpr(valueExpr)
        val arrayType = valueExpr.type as? IrType.Array
        if (arrayType != null) {
            // The elements move into a buffer of their own: the pointer must be
            // one `purge` can release, not an address inside the array.
            val width = sizeOfScalar(arrayType.element)
            val pointee = (pointerType as? IrType.Pointer)?.inner
            check(pointee == null || sizeOfScalar(pointee) == width) {
                "alloc of $arrayType as $pointee changes the element slot width"
            }
            val lengthSlot = nextTmp()
            emit("  $lengthSlot = bitcast i8* $value to i64*")
            val length = nextTmp()
            emit("  $length = load i64, i64* $lengthSlot")
            val bytes = nextTmp()
            emit("  $bytes = mul i64 $length, $width")
            val buffer = emitHeapAlloc(bytes)
            val data = nextTmp()
            emit("  $data = getelementptr i8, i8* $value, i64 8")
            usesMemcpy = true
            val copied = nextTmp()
            emit("  $copied = call i8* @memcpy(i8* $buffer, i8* $data, i64 $bytes)")
            registerPointerDrop(buffer, arrayType.element, length)
            return buffer
        }
        val raw = emitHeapAlloc("${sizeOfScalar(valueExpr.type)}")
        val typed = nextTmp()
        val ptrType = "${mapType(valueExpr.type)}*"
        emit("  $typed = bitcast i8* $raw to $ptrType")
        emit("  store ${mapType(valueExpr.type)} $value, $ptrType $typed, align 1")
        registerPointerDrop(raw, valueExpr.type, "1")
        return raw
    }

    private fun emitPointerBufferAlloc(expr: IrExpr.Call): String {
        val countExpr = expr.args.single()
        val count = emitExpr(countExpr)
        val countType = mapType(countExpr.type)
        val count64 = if (countType == "i64") {
            count
        } else {
            val widened = nextTmp()
            emit("  $widened = sext $countType $count to i64")
            widened
        }
        val elementType = (expr.type as? IrType.Pointer)?.inner ?: IrType.Any
        // Zeroed, as `.()` is each element's default; memory a purge released
        // would otherwise come back holding old values.
        usesAllocatorRuntime = true
        usesZeroedAlloc = true
        val raw = nextTmp()
        emit("  $raw = call i8* @__azora_alloc_zeroed(i64 $count64, i64 ${sizeOfScalar(elementType)})")
        registerPointerDrop(raw, elementType, count64)
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
            IrType.Double -> {
                val v = emitExpr(arg)
                printDouble(v, nl)
            }
            IrType.Float -> {
                val v = emitExpr(arg)
                val ext = nextTmp()
                emit("  $ext = fpext float $v to double")
                printDouble(ext, nl)
            }
            IrType.Quad -> {
                val v = emitExpr(arg)
                val d = nextTmp()
                emit("  $d = fptrunc fp128 $v to double")
                printDouble(d, nl)
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
            // A nullable prints what it holds, or `null`: a map's `get` answers
            // one, and its value is the answer the program asked for.
            is IrType.Nullable -> {
                val inner = (arg.type as IrType.Nullable).inner
                val pointer = emitExpr(arg)
                val isNull = nextTmp()
                emit("  $isNull = icmp eq i8* $pointer, null")
                val absent = "print.null.${labelCounter++}"
                val present = "print.value.${labelCounter++}"
                val printed = "print.done.${labelCounter++}"
                emit("  br i1 $isNull, label %$absent, label %$present")
                emit("$absent:")
                val nullText = gepString(addStringConstant("null"))
                if (newline) {
                    usesPuts = true
                    val unused = nextTmp()
                    emit("  $unused = call i32 @puts(i8* $nullText)")
                } else {
                    printfFmt("%s", listOf("i8* $nullText"))
                }
                emit("  br label %$printed")
                emit("$present:")
                emitPrintHeld(pointer, inner, nl, newline)
                emit("  br label %$printed")
                emit("$printed:")
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


    /**
     * Prints a floating-point value as a `Double`.
     *
     * `%g` alone renders an integral value as `4`, which reads as an `Int`. An
     * integral, finite value goes through `%.1f` so it keeps its `.0`; everything
     * else keeps `%g`'s shortest form.
     */
    private fun printDouble(value: String, nl: String) {
        usesPrintf = true
        usesTrunc = true
        val whole = nextTmp()
        emit("  $whole = call double @trunc(double $value)")
        val isIntegral = nextTmp()
        emit("  $isIntegral = fcmp oeq double $value, $whole")
        val intFmt = gepString(addStringConstant("%.1f$nl"))
        val genFmt = gepString(addStringConstant("%g$nl"))
        val fmt = nextTmp()
        emit("  $fmt = select i1 $isIntegral, i8* $intFmt, i8* $genFmt")
        val unused = nextTmp()
        emit("  $unused = call i32 (i8*, ...) @printf(i8* $fmt, double $value)")
    }

    /** What a non-null nullable holds, printed as its own type would be. */
    private fun emitPrintHeld(pointer: String, inner: IrType, nl: String, newline: Boolean) {
        when {
            inner == IrType.String || inner is IrType.Named -> {
                if (newline) {
                    usesPuts = true
                    val unused = nextTmp()
                    emit("  $unused = call i32 @puts(i8* $pointer)")
                } else {
                    printfFmt("%s", listOf("i8* $pointer"))
                }
            }
            inner == IrType.Bool -> {
                val bits = coerceNumeric(pointer, IrType.Nullable(inner), IrType.Int)
                val flag = nextTmp()
                emit("  $flag = icmp ne i32 $bits, 0")
                val text = boolToStr(flag)
                if (newline) {
                    usesPuts = true
                    val unused = nextTmp()
                    emit("  $unused = call i32 @puts(i8* $text)")
                } else {
                    printfFmt("%s", listOf("i8* $text"))
                }
            }
            inner in IrType.floatTypes -> {
                val value = coerceNumeric(pointer, IrType.Nullable(inner), IrType.Double)
                printDouble(value, nl)
            }
            IrType.isInteger(inner) || inner == IrType.Char -> {
                val value = coerceNumeric(pointer, IrType.Nullable(inner), IrType.Long)
                val format = when {
                    inner == IrType.Char -> "%c$nl"
                    isUnsigned(inner) -> "%llu$nl"
                    else -> "%lld$nl"
                }
                if (inner == IrType.Char) {
                    val narrow = nextTmp()
                    emit("  $narrow = trunc i64 $value to i32")
                    printfFmt(format, listOf("i32 $narrow"))
                } else {
                    printfFmt(format, listOf("i64 $value"))
                }
            }
            else -> {
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
    }

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
        IrType.Long, IrType.ULong, IrType.ISize, IrType.USize -> {
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
            IrType.Double, IrType.Float, IrType.Quad -> {
                usesDoubleToStr = true; usesSnprintf = true; usesMalloc = true; usesTrunc = true
                val raw = emitExpr(expr)
                val d = when (expr.type) {
                    IrType.Double -> raw
                    IrType.Float -> { val t = nextTmp(); emit("  $t = fpext float $raw to double"); t }
                    else -> { val t = nextTmp(); emit("  $t = fptrunc fp128 $raw to double"); t }
                }
                val tmp = nextTmp()
                emit("  $tmp = call i8* @__azora_double_to_str(double $d)")
                tmp
            }
            // An aggregate is a pointer, and a pointer is not a number. A pack
            // that says how it prints is routed through its `Display` before it
            // reaches here, so what is left is one that says nothing - and its
            // type name is the most it can honestly be rendered as.
            is IrType.Named if structDefs.containsKey((expr.type as IrType.Named).name) -> {
                emitExpr(expr)
                val ref = addStringConstant((expr.type as IrType.Named).name)
                val tmp = nextTmp()
                emit("  $tmp = getelementptr [${ref.byteLen} x i8], [${ref.byteLen} x i8]* ${ref.name}, i64 0, i64 0")
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

    private fun widenToI64(value: String, type: IrType): String {
        val llvmType = mapType(type)
        // Erased generics and all aggregate/reference values use a pointer ABI.
        // LLVM does not permit an integer extension (`sext`/`zext`) from a
        // pointer; preserving its opaque bits requires `ptrtoint`.
        if (llvmType.endsWith("*")) {
            val tmp = nextTmp()
            emit("  $tmp = ptrtoint $llvmType $value to i64")
            return tmp
        }
        return when (type) {
            IrType.Long, IrType.ULong, IrType.ISize, IrType.USize -> value
            IrType.Cent, IrType.UCent -> {
                val tmp = nextTmp(); emit("  $tmp = trunc i128 $value to i64"); tmp
            }
            IrType.UInt, IrType.UByte, IrType.UShort, IrType.Char -> {
                val tmp = nextTmp(); emit("  $tmp = zext $llvmType $value to i64"); tmp
            }
            else -> {
                val tmp = nextTmp(); emit("  $tmp = sext $llvmType $value to i64"); tmp
            }
        }
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

        buildStringIntrinsics(sb)

        // Values created by language helpers follow the active allocation scope.
        // Runtime bookkeeping is handled separately with __azora_alloc_raw.
        if (usesStrConcat || usesStrRepeat || usesArrayGrow || usesMapGrow ||
            usesIntToStr || usesUintToStr || usesDoubleToStr || usesCharToStr
        ) {
            usesAllocatorRuntime = true
        }

        if (usesIndexFail) {
            usesPrintf = true
            usesAbort = true
            val fmt = addStringConstant("panic: index %lld out of bounds for size %lld\n")
            sb.appendLine("; runtime: an index outside its array")
            sb.appendLine("define void @__azora_index_fail(i64 %index, i64 %size) noreturn {")
            sb.appendLine("entry:")
            sb.appendLine("  %fmt = getelementptr [${fmt.byteLen} x i8], [${fmt.byteLen} x i8]* ${fmt.name}, i64 0, i64 0")
            sb.appendLine("  %r = call i32 (i8*, ...) @printf(i8* %fmt, i64 %index, i64 %size)")
            sb.appendLine("  call void @__azora_abort()")
            sb.appendLine("  unreachable")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesIsCheck) {
            // `x is Variant`: a slot carries its variant name as its first (`i8*`)
            // field; compare it to the requested tag. Null-safe (a null slot never
            // matches), which also covers the not-yet-modelled slot payloads.
            sb.appendLine("; runtime: slot variant tag check")
            sb.appendLine("define i1 @__isCheck(i8* %slot, i8* %tag) {")
            sb.appendLine("entry:")
            sb.appendLine("  %isnull = icmp eq i8* %slot, null")
            sb.appendLine("  br i1 %isnull, label %nomatch, label %check")
            sb.appendLine("check:")
            sb.appendLine("  %tagpp = bitcast i8* %slot to i8**")
            sb.appendLine("  %slottag = load i8*, i8** %tagpp")
            sb.appendLine("  %c = call i32 @strcmp(i8* %slottag, i8* %tag)")
            sb.appendLine("  %eq = icmp eq i32 %c, 0")
            sb.appendLine("  ret i1 %eq")
            sb.appendLine("nomatch:")
            sb.appendLine("  ret i1 0")
            sb.appendLine("}")
            sb.appendLine("")
        }

        if (usesAllocatorRuntime) {
            usesMalloc = true
            usesFree = true
            usesAbort = true
            sb.append("""
; runtime: allocation headers retain the concrete destructor across erased generic calls.
; Header alignment is sixteen bytes and the payload keeps malloc alignment.
define i8* @__azora_alloc_raw(i64 %size) {
entry:
  %total = add i64 %size, 16
  %overflow = icmp ult i64 %total, %size
  br i1 %overflow, label %oom, label %allocate
allocate:
  %base = call i8* @malloc(i64 %total)
  %isnull = icmp eq i8* %base, null
  br i1 %isnull, label %oom, label %ok
oom:
  call void @__azora_abort()
  unreachable
ok:
  %drop = bitcast i8* %base to void (i8*, i64)**
  store void (i8*, i64)* null, void (i8*, i64)** %drop
  %count.raw = getelementptr i8, i8* %base, i64 8
  %count = bitcast i8* %count.raw to i64*
  store i64 0, i64* %count
  %payload = getelementptr i8, i8* %base, i64 16
  ret i8* %payload
}
define i8* @__azora_alloc(i64 %size) {
entry:
  %p = call i8* @__azora_alloc_raw(i64 %size)
  ret i8* %p
}
define void @__azora_set_drop(i8* %ptr, void (i8*, i64)* %destroy, i64 %count) {
entry:
  %isnull = icmp eq i8* %ptr, null
  br i1 %isnull, label %end, label %set
set:
  %base = getelementptr i8, i8* %ptr, i64 -16
  %slot = bitcast i8* %base to void (i8*, i64)**
  store void (i8*, i64)* %destroy, void (i8*, i64)** %slot
  %count.raw = getelementptr i8, i8* %base, i64 8
  %count.slot = bitcast i8* %count.raw to i64*
  store i64 %count, i64* %count.slot
  br label %end
end:
  ret void
}
define void @__azora_free(i8* %ptr) {
entry:
  %isnull = icmp eq i8* %ptr, null
  br i1 %isnull, label %end, label %dropcheck
dropcheck:
  %base = getelementptr i8, i8* %ptr, i64 -16
  %slot = bitcast i8* %base to void (i8*, i64)**
  %destroy = load void (i8*, i64)*, void (i8*, i64)** %slot
  store void (i8*, i64)* null, void (i8*, i64)** %slot
  %hasdrop = icmp ne void (i8*, i64)* %destroy, null
  br i1 %hasdrop, label %destroyvalue, label %release
destroyvalue:
  %count.raw = getelementptr i8, i8* %base, i64 8
  %count.slot = bitcast i8* %count.raw to i64*
  %count = load i64, i64* %count.slot
  call void %destroy(i8* %ptr, i64 %count)
  br label %release
release:
  call void @free(i8* %base)
  br label %end
end:
  ret void
}
""".trimIndent()).appendLine()
            if (usesZeroedAlloc) {
                usesMemset = true
                sb.append("""
define i8* @__azora_alloc_zeroed(i64 %count, i64 %size) {
entry:
  %negative = icmp slt i64 %count, 0
  %bytes = mul i64 %count, %size
  %checked = udiv i64 %bytes, %size
  %overflow = icmp ne i64 %checked, %count
  %failed = or i1 %negative, %overflow
  br i1 %failed, label %oom, label %allocate
oom:
  call void @__azora_abort()
  unreachable
allocate:
  %p = call i8* @__azora_alloc_raw(i64 %bytes)
  %zeroed = call i8* @memset(i8* %p, i32 0, i64 %bytes)
  ret i8* %p
}
""".trimIndent()).appendLine()
            }
        }

        if (globalVars.keys.any { it.startsWith("__tl_") }) {
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


        if (usesTaskRuntime) {
            usesAllocatorRuntime = true
            usesFree = true
            sb.appendLine("; runtime: pthread-backed structured tasks")
            sb.appendLine("define %azora.task* @__azora_task_spawn(i8* (i8*)* %fn, i8* %ctx) {")
            sb.appendLine("entry:")
            sb.appendLine("  %task.raw = call i8* @__azora_alloc_raw(i64 24)")
            sb.appendLine("  %task = bitcast i8* %task.raw to %azora.task*")
            sb.appendLine("  %thread.slot.raw = call i8* @__azora_alloc_raw(i64 8)")
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
            sb.appendLine("  %newRaw = call i8* @__azora_alloc_raw(i64 %bytes)")
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
            usesStrlen = true; usesStrcpy = true; usesStrcat = true
            sb.appendLine("; runtime: string concatenation")
            sb.appendLine("define i8* @__azora_str_concat(i8* %a, i8* %b) {")
            sb.appendLine("entry:")
            sb.appendLine("  %la = call i64 @strlen(i8* %a)")
            sb.appendLine("  %lb = call i64 @strlen(i8* %b)")
            sb.appendLine("  %sum = add i64 %la, %lb")
            sb.appendLine("  %size = add i64 %sum, 1")
            sb.appendLine("  %buf = call i8* @__azora_alloc(i64 %size)")
            sb.appendLine("  %c1 = call i8* @strcpy(i8* %buf, i8* %a)")
            sb.appendLine("  %c2 = call i8* @strcat(i8* %buf, i8* %b)")
            sb.appendLine("  ret i8* %buf")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesStrHash) {
            // 64-bit FNV-1a over the bytes; the interpreter and Wasm use the same.
            sb.appendLine("; runtime: string content hash")
            sb.appendLine("define i64 @__azora_str_hash(i8* %s) {")
            sb.appendLine("entry:")
            sb.appendLine("  br label %loop")
            sb.appendLine("loop:")
            sb.appendLine("  %h = phi i64 [ -3750763034362895579, %entry ], [ %next, %body ]")
            sb.appendLine("  %p = phi i8* [ %s, %entry ], [ %p2, %body ]")
            sb.appendLine("  %c = load i8, i8* %p")
            sb.appendLine("  %end = icmp eq i8 %c, 0")
            sb.appendLine("  br i1 %end, label %done, label %body")
            sb.appendLine("body:")
            sb.appendLine("  %byte = zext i8 %c to i64")
            sb.appendLine("  %mixed = xor i64 %h, %byte")
            sb.appendLine("  %next = mul i64 %mixed, 1099511628211")
            sb.appendLine("  %p2 = getelementptr i8, i8* %p, i64 1")
            sb.appendLine("  br label %loop")
            sb.appendLine("done:")
            sb.appendLine("  ret i64 %h")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesStrRepeat) {
            usesStrlen = true
            sb.appendLine("; runtime: string repetition")
            sb.appendLine("define i8* @__azora_str_repeat(i8* %s, i32 %n) {")
            sb.appendLine("entry:")
            sb.appendLine("  %len = call i64 @strlen(i8* %s)")
            sb.appendLine("  %n64 = sext i32 %n to i64")
            sb.appendLine("  %total = mul i64 %len, %n64")
            sb.appendLine("  %size = add i64 %total, 1")
            sb.appendLine("  %buf = call i8* @__azora_alloc(i64 %size)")
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
            sb.appendLine("; runtime: append one element to a packed array buffer")
            sb.appendLine("define i8* @__azora_array_grow(i8* %old, i64 %elemSize) {")
            sb.appendLine("entry:")
            sb.appendLine("  %lenPtr = bitcast i8* %old to i64*")
            sb.appendLine("  %len = load i64, i64* %lenPtr")
            sb.appendLine("  %newLen = add i64 %len, 1")
            sb.appendLine("  %oldBytes = mul i64 %len, %elemSize")
            sb.appendLine("  %newBytes = mul i64 %newLen, %elemSize")
            sb.appendLine("  %newSize = add i64 %newBytes, 8")
            sb.appendLine("  %newRaw = call i8* @__azora_alloc(i64 %newSize)")
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
            sb.appendLine("  %oldHeader = getelementptr i8, i8* %old, i64 -16")
            sb.appendLine("  %oldDropSlot = bitcast i8* %oldHeader to void (i8*, i64)**")
            sb.appendLine("  %drop = load void (i8*, i64)*, void (i8*, i64)** %oldDropSlot")
            sb.appendLine("  call void @__azora_set_drop(i8* %newRaw, void (i8*, i64)* %drop, i64 1)")
            sb.appendLine("  store void (i8*, i64)* null, void (i8*, i64)** %oldDropSlot")
            sb.appendLine("  call void @__azora_free(i8* %old)")
            sb.appendLine("  ret i8* %newRaw")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesMapGrow) {
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
            sb.appendLine("  %newRaw = call i8* @__azora_alloc(i64 %totalBytes)")
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
            usesSnprintf = true
            val fmt = addStringConstant("%lld")
            sb.appendLine("; runtime: integer to string")
            sb.appendLine("define i8* @__azora_int_to_str(i64 %v) {")
            sb.appendLine("entry:")
            sb.appendLine("  %buf = call i8* @__azora_alloc(i64 24)")
            sb.appendLine("  %fmt = getelementptr [${fmt.byteLen} x i8], [${fmt.byteLen} x i8]* ${fmt.name}, i64 0, i64 0")
            sb.appendLine("  %r = call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 24, i8* %fmt, i64 %v)")
            sb.appendLine("  ret i8* %buf")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesUintToStr) {
            usesSnprintf = true
            val fmt = addStringConstant("%llu")
            sb.appendLine("; runtime: unsigned integer to string")
            sb.appendLine("define i8* @__azora_uint_to_str(i64 %v) {")
            sb.appendLine("entry:")
            sb.appendLine("  %buf = call i8* @__azora_alloc(i64 24)")
            sb.appendLine("  %fmt = getelementptr [${fmt.byteLen} x i8], [${fmt.byteLen} x i8]* ${fmt.name}, i64 0, i64 0")
            sb.appendLine("  %r = call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 24, i8* %fmt, i64 %v)")
            sb.appendLine("  ret i8* %buf")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesDoubleToStr) {
            usesSnprintf = true
            // %g alone keeps only six significant digits, silently truncating an
            // interpolated Double; %.17g round-trips an f64 exactly.
            val fmt = addStringConstant("%.17g")
            sb.appendLine("; runtime: real to string")
            sb.appendLine("define i8* @__azora_double_to_str(double %v) {")
            sb.appendLine("entry:")
            sb.appendLine("  %buf = call i8* @__azora_alloc(i64 32)")
            val intFmt = addStringConstant("%.1f")
            sb.appendLine("  %gfmt = getelementptr [${fmt.byteLen} x i8], [${fmt.byteLen} x i8]* ${fmt.name}, i64 0, i64 0")
            sb.appendLine("  %ifmt = getelementptr [${intFmt.byteLen} x i8], [${intFmt.byteLen} x i8]* ${intFmt.name}, i64 0, i64 0")
            // A `Double` prints as a `Double`: an integral value keeps its `.0` so the
            // output says which type it came from. Anything else uses %g's shortest form.
            sb.appendLine("  %whole = call double @trunc(double %v)")
            sb.appendLine("  %isint = fcmp oeq double %v, %whole")
            sb.appendLine("  %fmt = select i1 %isint, i8* %ifmt, i8* %gfmt")
            sb.appendLine("  %r = call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 32, i8* %fmt, double %v)")
            sb.appendLine("  ret i8* %buf")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesCharToStr) {
            usesSnprintf = true
            val fmt = addStringConstant("%c")
            sb.appendLine("; runtime: char to string")
            sb.appendLine("define i8* @__azora_char_to_str(i32 %v) {")
            sb.appendLine("entry:")
            sb.appendLine("  %buf = call i8* @__azora_alloc(i64 2)")
            sb.appendLine("  %fmt = getelementptr [${fmt.byteLen} x i8], [${fmt.byteLen} x i8]* ${fmt.name}, i64 0, i64 0")
            sb.appendLine("  %r = call i32 (i8*, i64, i8*, ...) @snprintf(i8* %buf, i64 2, i8* %fmt, i32 %v)")
            sb.appendLine("  ret i8* %buf")
            sb.appendLine("}")
            sb.appendLine()
        }

        if (usesAbort) {
            // Every stop goes through here. `abort` does not flush stdio, so
            // without this a program whose output is a pipe or a file loses
            // everything it printed - its last message, the reason, included.
            sb.appendLine("; runtime: stop the program, keeping what it printed")
            sb.appendLine("define void @__azora_abort() noreturn {")
            sb.appendLine("entry:")
            sb.appendLine("  %flushed = call i32 @fflush(i8* null)")
            sb.appendLine("  call void @abort()")
            sb.appendLine("  unreachable")
            sb.appendLine("}")
            sb.appendLine()
        }

        // Post-process: fix pointer comparisons against integer `0` - LLVM requires `null`.
        return sb.toString().lineSequence().map { line ->
            if (line.contains("icmp") && line.contains("*") && line.contains(", 0"))
                line.replace(", 0", ", null")
            else line
        }.joinToString("\n")
    }

    // -----------------------------------------------------------------------
    // Low-level helpers
    // -----------------------------------------------------------------------

    /** Function ABI result; Unit is erased and Nothing has no return edge. */
    private fun abiReturnType(type: IrType): String =
        if (type == IrType.Unit || type == IrType.Nothing) "void" else mapType(type)

    private fun mapType(type: IrType): String = when (type) {
        // An integer's width *is* its LLVM type: `Int<7>` is `i7` and `Byte` is
        // `i8`, because `Byte` is `Int<8>`. Signedness is not part of the type
        // in LLVM - it belongs to the instructions - so both read the same.
        is IrType.Integer -> "i${type.bits}"
        IrType.Half -> "half"
        IrType.Quad -> "fp128"
        IrType.Double -> "double"
        IrType.Bool -> "i1"
        IrType.String -> "i8*"
        // Unit has one first-class value. It is an i8 zero in storage and
        // parameters, while [abiReturnType] erases it from function results.
        IrType.Unit -> "i8"
        // No value has type `Nothing`, so nothing is ever loaded or stored at
        // it; `void` is the only lowering a value-less type can have.
        IrType.Nothing -> "void"
        IrType.Char -> "i8"
        IrType.Byte -> "i8"
        IrType.UByte -> "i8"
        IrType.Short -> "i16"
        IrType.UShort -> "i16"
        IrType.Long -> "i64"
        IrType.ULong -> "i64"
        IrType.ISize, IrType.USize -> "i64"   // pointer-width on every supported target
        IrType.Cent -> "i128"
        IrType.UCent -> "i128"
        IrType.Float -> "float"
        IrType.Any -> "i8*"
        is IrType.Array -> "i8*"
        is IrType.Map, is IrType.Set -> "i8*"
        is IrType.Function -> {
            // Declaring the type here, rather than only where a lambda is
            // emitted, is what keeps a *struct field* of function type valid:
            // the field spells `%azora.closure*` without any closure ever being
            // constructed, and LLVM rejects a pointer to an undefined type.
            lateTypeDefinitions.add(CLOSURE_TYPE_DEFINITION)
            "%azora.closure*"
        }
        is IrType.Task -> {
            usesTaskRuntime = true
            "%azora.task*"
        }
        is IrType.Tuple -> "i8*"
        is IrType.Variant -> "i8*"
        is IrType.Nullable -> "i8*"
        is IrType.Pointer -> "i8*"
        is IrType.Named -> if (type.name in structDefs) "%struct.${sanitizeName(type.name)}*" else "i8*"
    }

    private fun isUnsigned(type: IrType): Boolean =
        (type is IrType.Integer && !type.signed) || type == IrType.USize

    /** A type-appropriate default/zero value, used for unreachable returns. */
    private fun defaultValue(type: IrType): String = when (type) {
        in IrType.floatTypes -> floatConst(0.0, type)
        IrType.Bool -> "false"
        IrType.String -> "null"
        IrType.Unit -> "0"
        // Every pointer-shaped ABI value must use LLVM's `null` token.  The
        // integer spelling `0` is not a polymorphic zero in LLVM IR: writing
        // `i8* 0` makes the whole module invalid before it can run.
        is IrType.Array, is IrType.Map, is IrType.Set, is IrType.Function,
        is IrType.Tuple, is IrType.Variant, is IrType.Nullable, is IrType.Pointer,
        is IrType.Named, IrType.Any -> "null"
        is IrType.Task -> "null"
        else -> "0"
    }

    /** Formats a floating-point constant in the exact LLVM hex form. */
    /**
     * A float constant in LLVM's text form.
     *
     * A `Quad` is converted from [text] when there is one: 113 significand bits
     * is more than the `Double` in hand ever held, and past `Double`'s range
     * the value in hand is already infinity. Narrower types are exactly the
     * `Double`, which is what parsing produced and what they store.
     */
    private fun floatConst(value: Double, type: IrType, text: String? = null): String {
        if (type == IrType.Quad) {
            val exact = text?.let(Binary128::encode)
            if (exact != null) return fp128Hex(exact.high, exact.low)
            return fp128Const(value)
        }
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

        return fp128Hex(sign or (quadExponent.toLong() shl 48) or quadHighFraction, quadLowFraction)
    }

    /** The two words of a binary128 as LLVM writes them: the low word first. */
    private fun fp128Hex(high: Long, low: Long): String {
        fun word(value: Long) = value.toULong().toString(16).uppercase().padStart(16, '0')
        return "0xL${word(low)}${word(high)}"
    }

    private fun gepString(ref: StringRef): String {
        val tmp = nextTmp()
        emit("  $tmp = getelementptr [${ref.byteLen} x i8], [${ref.byteLen} x i8]* ${ref.name}, i64 0, i64 0")
        return tmp
    }

    private fun sanitizeName(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

    private fun nextTmp(): String = "%t${tmpCounter++}"

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

/**
 * Floating-point functions the compiler provides itself, mapped to their arity.
 *
 * Every one is a standard libm symbol, so the backend can define the Azora
 * declaration as a direct call rather than requiring a C or WASM shim.
 */
private val LIBM_INTRINSICS = mapOf(
    "sin" to 1, "cos" to 1, "tan" to 1,
    "asin" to 1, "acos" to 1, "atan" to 1, "atan2" to 2,
    "sinh" to 1, "cosh" to 1, "tanh" to 1,
    "sqrt" to 1, "cbrt" to 1, "hypot" to 2,
    "log" to 1, "log2" to 1, "log10" to 1,
    "exp" to 1, "exp2" to 1, "pow" to 2,
    "floor" to 1, "ceil" to 1, "trunc" to 1, "round" to 1,
    "fabs" to 1, "fmod" to 2, "fmin" to 2, "fmax" to 2,
)
