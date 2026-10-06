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

import org.azora.lang.ir.symbolDenotes
import org.azora.lang.ir.IrBinaryOp
import org.azora.lang.ir.IrExpr
import org.azora.lang.ir.IrField
import org.azora.lang.ir.IrFunction
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrSpecMethod
import org.azora.lang.ir.IrSpecTable
import org.azora.lang.ir.IrStmt
import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.Intrinsics
import org.azora.lang.ir.IrType
import org.azora.lang.ir.shown
import org.azora.lang.ir.IrUnaryOp

/**
 * Backend - lowers [IrProgram] to WebAssembly text format (WAT), using the
 * folded S-expression syntax.
 *
 * Value representation (single WASM value per Azora value):
 *  - `Int`/`Bool`/`Char`/sized ints ≤ 32-bit → `i32`
 *  - `Long`/`ULong`/`Cent`/`UCent` → `i64`     `Double`/`Quad` → `f64`   `Float` → `f32`
 *  - an erased generic value (`Any`) → `i64` holding the value's bits
 *  - `String`/`arr[T]`/pack → `i32` pointer into linear memory. Strings and arrays
 *    are laid out as `[len: i32][payload…]` with elements at their own width;
 *    a pack's fields each sit at an offset aligned to their width.
 *
 * Printing and string handling go through host imports (`print_i32`, `print_str`,
 * …) and a small linear-memory runtime (`__alloc`/`__free`, `__str_concat`, `__str_eq`,
 * `__str_repeat`, `__int_to_str`). Structured control flow lowers to
 * `block`/`loop`/`br_if`.
 *
 * NOTE: this is an MVP-level target - exceptions lower to `unreachable`; tasks
 * are synchronous.
 */
class WasmCodegen {

    private data class ClosureCapture(
        val name: String,
        val type: IrType,
        val offset: Int,
        val byRef: Boolean,
    )
    private data class ClosureFunction(
        val index: Int,
        val lambda: IrExpr.Lambda,
        val captures: List<ClosureCapture>,
        val typeName: String,
    )

    private val out = StringBuilder()
    private var indent = 0

    // Per-function state.
    private val locals = LinkedHashMap<String, String>() // name -> wasm type
    private val localIrTypes = LinkedHashMap<String, IrType>()
    /** Source binding -> local containing its shared closure-cell address. */
    private val boxedLocals = LinkedHashMap<String, String>()
    private var params = emptySet<String>()
    private var tempCounter = 0
    private var blockCounter = 0
    private val loopStack = ArrayDeque<Pair<String, String>>() // (breakLabel, continueLabel)
    private val labelTargets = HashMap<String, Pair<String, String>>()
    private val activeReactiveEffects = mutableListOf<IrStmt.Effect>()
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
    private val reactiveAliases = mutableMapOf<String, ReactiveStorage>()
    private var currentFunctionName = ""
    /** Source-level return type; Unit/Nothing both erase from the Wasm ABI. */
    private var currentReturnType: IrType = IrType.Unit

    // Module state.
    private val structs = HashMap<String, List<IrField>>()
    private val ownershipDrops = linkedMapOf<String, Pair<String, IrType>>()
    private val structDefinitions = mutableMapOf<String, IrTopLevel.Struct>()
    private val layouts = HashMap<String, PackLayout>()
    /** Names of the `union` types: every member of one sits at offset 0. */
    private val unions = HashSet<String>()
    private val globalTypes = LinkedHashMap<String, IrType>()
    private val stringConsts = LinkedHashMap<String, Int>() // literal -> offset
    private var constCursor = STRING_BASE

    private var usesAlloc = false
    private var usesConcat = false
    private var usesStrEq = false
    private var usesStrHash = false
    private var usesRepeat = false
    private var usesIntToStr = false
    private var usesLongToStr = false
    private var usesDoubleToStr = false
    private var usesTrig = false
    private var usesExpLog = false
    private var usesInvTrig = false
    private var usesVhaTrig = false
    private var usesIsCheck = false
    private val neededIntrinsics = mutableSetOf<String>()
    private val externs = LinkedHashMap<String, IrTopLevel.Extern>()
    private val functionParams = HashMap<String, List<IrType>>()
    private val functionResults = HashMap<String, IrType>()
    private val specTables = HashMap<String, IrSpecTable>()
    /** Runtime type id of each spec implementer; 0 is never assigned. */
    private val specTypeIds = HashMap<String, Int>()
    private val neededDispatchers = LinkedHashMap<String, Pair<IrSpecTable, IrSpecMethod>>()
    private val neededExterns = LinkedHashSet<String>()
    private val closureTypes = LinkedHashMap<IrType.Function, String>()

    /** Names of functions that can fail; a call to one checks the failure flag. */
    private val failableFunctions = HashSet<String>()

    /**
     * Labels of the enclosing error handlers, innermost last.
     *
     * A failure branches to the top of this stack. When it is empty the error
     * propagates if the current function is itself failable, and traps if it is
     * not - which is what an error nobody can observe means.
     */
    private val errorHandlers = ArrayDeque<String>()

    /** True when the function being emitted declares `T ?! E`. */
    private var currentIsFailable = false

    /** True once anything in this module raises, checks or handles an error. */
    private var usesErrorSlot = false

    /** The `defer`s of the function being emitted, each with the local counting its registrations. */
    private val deferSlots = mutableListOf<Pair<IrStmt.Defer, String>>()

    /** True while deferred bodies are rendered, so an exit inside one cannot render them again. */
    private var renderingDefers = false
    private val closureFunctions = mutableListOf<ClosureFunction>()
    private val stringIntrinsics = setOf(
        "stringLength", "charAt", "ord", "chr", "isDigit", "isAlpha", "substring",
        "startsWith", "endsWith", "contains", "indexOf", "toUpper", "toLower", "trim",
        "replace", "split", "toChars", "fromChars",
    )

    companion object {
        private const val STRING_BASE = 1024

        // Heap allocator (see ALLOCATOR_RUNTIME). Payloads are powers of two from
        // 8 bytes up to 1 GiB; one list head per class, padded to keep the
        // first block 8-aligned.
        internal const val WASM_MIN_CLASS = 3
        internal const val WASM_MAX_CLASS = 30
        internal const val WASM_MAX_ALLOC = 1 shl WASM_MAX_CLASS
        internal const val WASM_FREE_LIST_BYTES = (WASM_MAX_CLASS + 2) * 4
        internal const val WASM_BLOCK_LIVE = 0x417A6F41 // "AzoA"
        internal const val WASM_BLOCK_FREE = 0x417A6F46 // "AzoF"

        /** A closure's captured environment: a linear-memory address. */
        private val CLOSURE_ENVIRONMENT = IrType.Pointer(IrType.Unit)

        /** The allocator's globals: the free-list table at [heapBase], then the first block. */
        internal fun heapGlobals(heapBase: Int): String =
            "  (global \$__free_lists i32 (i32.const $heapBase))\n" +
                "  (global \$__heap (mut i32) (i32.const ${heapBase + WASM_FREE_LIST_BYTES}))\n"

        // The heap is a table of free-list heads followed by blocks. A block is an
        // 8-byte header - its size class, then a live/free tag - and a payload of
        // `1 << class` bytes, so every payload is 8-aligned. `__alloc` reuses a
        // freed block of the same class (zeroing it, as fresh memory is) or takes
        // new memory, growing it when needed. `__free` accepts null, and traps on a
        // pointer whose block is not live: a double purge, an interior pointer or
        // memory the allocator never handed out. Exhaustion traps like LLVM's abort.
        internal val ALLOCATOR_RUNTIME = """
  (func ${'$'}__heap_take (param ${'$'}bytes i32) (result i32)
    (local ${'$'}p i32) (local ${'$'}end i64) (local ${'$'}have i64)
    (local.set ${'$'}p (global.get ${'$'}__heap))
    (local.set ${'$'}end (i64.add (i64.extend_i32_u (local.get ${'$'}p)) (i64.extend_i32_u (local.get ${'$'}bytes))))
    (if (i64.ge_u (local.get ${'$'}end) (i64.const 4294967296)) (then unreachable))
    (local.set ${'$'}have (i64.shl (i64.extend_i32_u (memory.size)) (i64.const 16)))
    (if (i64.gt_u (local.get ${'$'}end) (local.get ${'$'}have))
      (then
        (if (i32.eq
              (memory.grow (i32.wrap_i64 (i64.shr_u (i64.add (i64.sub (local.get ${'$'}end) (local.get ${'$'}have)) (i64.const 65535)) (i64.const 16))))
              (i32.const -1))
          (then unreachable))))
    (global.set ${'$'}__heap (i32.wrap_i64 (local.get ${'$'}end)))
    (local.get ${'$'}p))
  (func ${'$'}__alloc (param ${'$'}size i32) (result i32)
    (local ${'$'}class i32) (local ${'$'}head i32) (local ${'$'}block i32)
    (if (i32.gt_u (local.get ${'$'}size) (i32.const $WASM_MAX_ALLOC)) (then unreachable))
    (local.set ${'$'}class (i32.const $WASM_MIN_CLASS))
    (if (i32.gt_u (local.get ${'$'}size) (i32.const ${1 shl WASM_MIN_CLASS}))
      (then (local.set ${'$'}class (i32.sub (i32.const 32) (i32.clz (i32.sub (local.get ${'$'}size) (i32.const 1)))))))
    (local.set ${'$'}head (i32.add (global.get ${'$'}__free_lists) (i32.shl (local.get ${'$'}class) (i32.const 2))))
    (local.set ${'$'}block (i32.load (local.get ${'$'}head)))
    (if (local.get ${'$'}block)
      (then
        (i32.store (local.get ${'$'}head) (i32.load offset=8 (local.get ${'$'}block)))
        (memory.fill (i32.add (local.get ${'$'}block) (i32.const 8)) (i32.const 0) (local.get ${'$'}size)))
      (else
        (local.set ${'$'}block (call ${'$'}__heap_take (i32.add (i32.const 8) (i32.shl (i32.const 1) (local.get ${'$'}class)))))))
    (i32.store (local.get ${'$'}block) (local.get ${'$'}class))
    (i32.store offset=4 (local.get ${'$'}block) (i32.const $WASM_BLOCK_LIVE))
    (i32.add (local.get ${'$'}block) (i32.const 8)))
  (func ${'$'}__free (param ${'$'}p i32)
    (local ${'$'}block i32) (local ${'$'}class i32) (local ${'$'}head i32)
    (if (i32.eqz (local.get ${'$'}p)) (then (return)))
    (if (i32.or
          (i32.or
            (i32.lt_u (local.get ${'$'}p) (i32.add (global.get ${'$'}__free_lists) (i32.const ${WASM_FREE_LIST_BYTES + 8})))
            (i32.ge_u (local.get ${'$'}p) (global.get ${'$'}__heap)))
          (i32.and (local.get ${'$'}p) (i32.const 7)))
      (then unreachable))
    (local.set ${'$'}block (i32.sub (local.get ${'$'}p) (i32.const 8)))
    (if (i32.ne (i32.load offset=4 (local.get ${'$'}block)) (i32.const $WASM_BLOCK_LIVE)) (then unreachable))
    (local.set ${'$'}class (i32.load (local.get ${'$'}block)))
    (if (i32.or (i32.lt_u (local.get ${'$'}class) (i32.const $WASM_MIN_CLASS)) (i32.gt_u (local.get ${'$'}class) (i32.const $WASM_MAX_CLASS)))
      (then unreachable))
    (i32.store offset=4 (local.get ${'$'}block) (i32.const $WASM_BLOCK_FREE))
    (local.set ${'$'}head (i32.add (global.get ${'$'}__free_lists) (i32.shl (local.get ${'$'}class) (i32.const 2))))
    (i32.store (local.get ${'$'}p) (i32.load (local.get ${'$'}head)))
    (i32.store (local.get ${'$'}head) (local.get ${'$'}block)))
  (func ${'$'}__alloc_buffer (param ${'$'}count i32) (param ${'$'}stride i32) (result i32)
    (if (i32.gt_u (local.get ${'$'}count) (i32.div_u (i32.const $WASM_MAX_ALLOC) (local.get ${'$'}stride)))
      (then unreachable))
    (call ${'$'}__alloc (i32.mul (local.get ${'$'}count) (local.get ${'$'}stride))))
"""
    }

    /**
     * Generates WebAssembly text (WAT) from the given IR program.
     *
     * @param program the optimized IR program to lower to WAT
     * @return the generated WAT module source
     */
    fun generate(program: IrProgram): String {
        out.clear(); indent = 0
        structs.clear(); structDefinitions.clear(); ownershipDrops.clear(); layouts.clear(); globalTypes.clear(); stringConsts.clear(); constCursor = STRING_BASE
        usesAlloc = false; usesConcat = false; usesStrEq = false; usesStrHash = false; usesRepeat = false; usesIntToStr = false; usesLongToStr = false; usesDoubleToStr = false; usesTrig = false; usesExpLog = false; usesInvTrig = false; usesVhaTrig = false; usesIsCheck = false
        neededIntrinsics.clear(); externs.clear(); neededExterns.clear()
        reactiveStorage.clear(); reactiveAliases.clear()
        closureTypes.clear(); closureFunctions.clear()
        failableFunctions.clear(); errorHandlers.clear(); usesErrorSlot = false

        for (item in program.items) if (item is IrTopLevel.Struct) {
            structs[item.name] = item.fields
            structDefinitions[item.name] = item
            if (item.isUnion) unions.add(item.name)
        }
        for (item in program.items) if (item is IrTopLevel.Extern) externs[item.name] = item
        for (stmt in program.globals) {
            when (stmt) {
                is IrStmt.VarDecl -> globalTypes[stmt.name] = stmt.type
                is IrStmt.FinDecl -> globalTypes[stmt.name] = stmt.type
                is IrStmt.LetDecl -> globalTypes[stmt.name] = stmt.type
                else -> Unit
            }
        }

        val funcs = program.items.filterIsInstance<IrTopLevel.Func>().map { it.function }
            .filter { it.name !in org.azora.lang.semantic.CtfeEvaluator.RUNTIME_INTRINSICS }
        functionParams.clear(); functionResults.clear()
        specTables.clear(); specTypeIds.clear(); neededDispatchers.clear()
        for (table in program.specTables) specTables[table.specName] = table
        program.specTables.flatMap { table -> table.impls.map { it.typeName } }.distinct().sorted()
            .forEachIndexed { index, name -> specTypeIds[name] = index + 1 }
        for (func in funcs) {
            functionParams[func.name] = func.params.map { it.second }
            functionResults[func.name] = func.returnType
            if (func.isFailable) failableFunctions += func.name
        }
        for ((name, extern) in externs) {
            functionParams[name] = extern.params.map { it.second }
            functionResults[name] = extern.returnType
        }
        for (func in funcs) collectReactiveStorage(func.name, func.body)
        for (storage in reactiveStorage.values.distinctBy { it.valueGlobal }) {
            globalTypes[storage.valueGlobal] = storage.type
            globalTypes[storage.initGlobal] = IrType.Bool
        }

        // Emit function bodies first (interns strings, sets runtime flags).
        val funcText = StringBuilder()
        for (f in funcs) funcText.append(emitFunction(f))
        val globalInitText = emitGlobalInitializer(program.globals)
        var closureIndex = 0
        while (closureIndex < closureFunctions.size) {
            funcText.append(emitClosureFunction(closureFunctions[closureIndex++]))
        }
        for ((name, dispatch) in neededDispatchers) funcText.append(renderDispatcher(name, dispatch.first, dispatch.second))
        var dropIndex = 0
        while (dropIndex < ownershipDrops.size) {
            val (name, type) = ownershipDrops.values.toList()[dropIndex++]
            funcText.append(renderOwnershipDrop(name, type))
        }

        val sb = StringBuilder()
        sb.appendLine("(module")
        sb.appendLine("  (import \"env\" \"print_i32\" (func \$print_i32 (param i32)))")
        sb.appendLine("  (import \"env\" \"print_i64\" (func \$print_i64 (param i64)))")
        sb.appendLine("  (import \"env\" \"print_f64\" (func \$print_f64 (param f64)))")
        sb.appendLine("  (import \"env\" \"print_f32\" (func \$print_f32 (param f32)))")
        sb.appendLine("  (import \"env\" \"print_bool\" (func \$print_bool (param i32)))")
        sb.appendLine("  (import \"env\" \"print_str\" (func \$print_str (param i32)))")
        sb.appendLine("  (import \"env\" \"write_i32\" (func \$write_i32 (param i32)))")
        sb.appendLine("  (import \"env\" \"write_i64\" (func \$write_i64 (param i64)))")
        sb.appendLine("  (import \"env\" \"write_f64\" (func \$write_f64 (param f64)))")
        sb.appendLine("  (import \"env\" \"write_f32\" (func \$write_f32 (param f32)))")
        sb.appendLine("  (import \"env\" \"write_bool\" (func \$write_bool (param i32)))")
        sb.appendLine("  (import \"env\" \"write_str\" (func \$write_str (param i32)))")
        for (name in neededExterns) {
            val extern = externs.getValue(name)
            // A `bridge func` that names a float operation WebAssembly has natively
            // is defined here rather than imported: the compiler supplies these
            // itself, so a program using `std` needs nothing from the host.
            if (wasmFloatOpFor(extern) != null) continue
            if (wasmSoftwareMathFor(extern) != null) { usesTrig = true; usesExpLog = true; usesInvTrig = true; usesVhaTrig = true; continue }
            val params = extern.params.joinToString("") { " (param ${wasmType(it.second)})" }
            val result = if (hasFunctionResult(extern.returnType)) " (result ${wasmType(extern.returnType)})" else ""
            sb.appendLine("  (import \"env\" \"$name\" (func \$$name$params$result))")
        }
        for (name in neededExterns) {
            val extern = externs.getValue(name)
            val soft = wasmSoftwareMathFor(extern)
            if (soft != null) {
                val ps = extern.params.indices.joinToString("") { " (param \$a$it f64)" }
                val args = extern.params.indices.joinToString(" ") { "(local.get \$a$it)" }
                sb.appendLine("  (func \$$name$ps (result f64)")
                sb.appendLine("    (call \$$soft $args)")
                sb.appendLine("  )")
                continue
            }
            val op = wasmFloatOpFor(extern) ?: continue
            val ps = extern.params.indices.joinToString("") { " (param \$a$it f64)" }
            sb.appendLine("  (func \$$name$ps (result f64)")
            extern.params.indices.forEach { sb.appendLine("    (local.get \$a$it)") }
            sb.appendLine("    ($op)")
            sb.appendLine("  )")
        }
        for ((callable, name) in closureTypes) {
            // The trailing parameter is the closure's environment address.
            val params = (callable.params + callable.receivers + CLOSURE_ENVIRONMENT)
                .joinToString("") { " (param ${wasmType(it)})" }
            val result = if (hasFunctionResult(callable.ret)) " (result ${wasmType(callable.ret)})" else ""
            sb.appendLine("  (type \$$name (func$params$result))")
        }
        // A module can call through a callable parameter without creating a
        // lambda itself; `call_indirect` still needs a table to name.
        if (closureTypes.isNotEmpty()) sb.appendLine("  (table ${closureFunctions.size} funcref)")
        if (closureFunctions.isNotEmpty()) {
            sb.appendLine(
                closureFunctions.joinToString(" ", "  (elem (i32.const 0) ", ")\n") {
                    "\$__closure_${it.index}"
                },
            )
        }
        sb.appendLine("  (memory (export \"memory\") 16)")
        sb.append(heapGlobals(alignTo(constCursor, 8)))
        for ((name, type) in globalTypes) {
            sb.appendLine("  (global \$$name (mut ${wasmType(type)}) (${wasmType(type)}.const 0))")
        }
        for (global in program.items.filterIsInstance<IrTopLevel.Global>()) {
            val exportName = global.exportName ?: continue
            val internalName = when (val declaration = global.stmt) {
                is IrStmt.VarDecl -> declaration.name
                is IrStmt.FinDecl -> declaration.name
                is IrStmt.LetDecl -> declaration.name
                else -> continue
            }
            val escaped = exportName.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.appendLine("  (export \"$escaped\" (global \$$internalName))")
        }
        if (usesErrorSlot) {
            // `T ?! E` keeps its success type, so a raised error travels beside
            // the return value: a flag saying one is pending, and the error itself
            // erased to eight bytes. The flag is separate so that no error value,
            // zero included, can be mistaken for the absence of one.
            sb.appendLine("  (global \$__azora_failed (mut i32) (i32.const 0))")
            sb.appendLine("  (global \$__azora_err (mut i64) (i64.const 0))")
        }
        if (usesAlloc) sb.append(ALLOCATOR_RUNTIME)
        if (usesConcat) sb.append(RT_CONCAT)
        if (usesIsCheck) usesStrEq = true
        if (usesStrEq) sb.append(RT_STR_EQ)
        if (usesStrHash) sb.append(RT_STR_HASH)
        if (usesRepeat) sb.append(RT_REPEAT)
        if (usesIntToStr) sb.append(RT_INT_TO_STR)
        if (usesLongToStr) sb.append(RT_LONG_TO_STR)
        if (usesDoubleToStr) sb.append(RT_REAL_TO_STR)
        if (usesTrig) sb.append(RT_TRIG)
        if (usesExpLog) sb.append(RT_EXPLOG)
        if (usesInvTrig) sb.append(RT_INVTRIG)
        if (usesVhaTrig) sb.append(RT_VHA_TRIG)
        if (usesIsCheck) sb.append(RT_IS_CHECK)
        sb.append(wasmStringIntrinsics())
        sb.append(globalInitText)
        sb.append(funcText)
        if (program.globals.isNotEmpty()) sb.appendLine("  (start \$__init_globals)")
        for ((literal, offset) in stringConsts) {
            sb.appendLine("  (data (i32.const $offset) \"${dataBytes(literal)}\")")
        }
        for (f in funcs) sb.appendLine("  (export \"${f.name}\" (func \$${f.name}))")
        sb.appendLine(")")
        return sb.toString().trimEnd()
    }

    private fun emitGlobalInitializer(globals: List<IrStmt>): String {
        if (globals.isEmpty()) return ""
        locals.clear(); localIrTypes.clear(); boxedLocals.clear(); tempCounter = 0; blockCounter = 0
        loopStack.clear(); labelTargets.clear(); params = emptySet()
        errorHandlers.clear(); currentIsFailable = false
        deferSlots.clear()

        out.clear(); indent = 2
        for (stmt in globals) {
            when (stmt) {
                is IrStmt.VarDecl -> line("(global.set \$${stmt.name} ${emitAs(stmt.initializer, stmt.type)})")
                is IrStmt.FinDecl -> line("(global.set \$${stmt.name} ${emitAs(stmt.initializer, stmt.type)})")
                is IrStmt.LetDecl -> line("(global.set \$${stmt.name} ${emitAs(stmt.initializer, stmt.type)})")
                else -> emitStmt(stmt)
            }
        }
        val body = out.toString()
        return buildString {
            appendLine("  (func \$__init_globals")
            for ((name, type) in locals) appendLine("    (local \$$name $type)")
            append(body)
            appendLine("  )")
        }
    }

    private fun emitFunction(func: IrFunction): String {
        locals.clear(); localIrTypes.clear(); boxedLocals.clear(); tempCounter = 0; blockCounter = 0
        loopStack.clear(); labelTargets.clear()
        params = func.params.map { it.first }.toSet()
        activeReactiveEffects.clear()
        emittingReactiveEffect = false
        lazyLocals.clear()
        reactiveAliases.clear()
        currentFunctionName = func.name
        currentReturnType = func.returnType
        currentIsFailable = func.isFailable
        errorHandlers.clear()
        localIrTypes.putAll(func.params)
        prepareDefers(func.body)

        out.clear(); indent = 2
        for (stmt in func.body) emitStmt(stmt)
        if (!endsWithTerminator(func.body)) deferredCode(failing = false).takeIf { it.isNotEmpty() }?.let(::line)
        // A function that returns from inside a `when`/`if` - every arm of a
        // `when self { … }` ending in `return` - leaves nothing on the stack
        // where Wasm expects the result of an implicit return. `unreachable`
        // types as bottom, so it satisfies any result type, and it is the
        // truth: control never arrives here. Without it such a body is rejected
        // by the validator ("type mismatch in implicit return").
        if (func.returnType != IrType.Unit && !endsWithTerminator(func.body)) {
            indent = 2
            line("unreachable")
        }
        val body = out.toString()

        val sig = StringBuilder("  (func \$${func.name}")
        for ((n, t) in func.params) sig.append(" (param \$$n ${wasmType(t)})")
        if (hasFunctionResult(func.returnType)) sig.append(" (result ${wasmType(func.returnType)})")
        sig.append("\n")
        for ((n, t) in locals) if (n !in params) sig.append("    (local \$$n $t)\n")
        sig.append(body)
        sig.append("  )\n")
        return sig.toString()
    }

    /**
     * True when [body] ends in an instruction the Wasm validator accepts as the
     * producer of a value-returning function's result.
     *
     * Only a literal `return` (or a trap) qualifies. A `when` or `if` whose arms
     * all return is *semantically* terminal but lowers to `(if …)` blocks that
     * type as `[]`, so as far as the validator is concerned control still falls
     * off the end, which is a "type mismatch in implicit return". Those cases
     * want the terminator.
     */
    private fun endsWithTerminator(body: List<IrStmt>): Boolean =
        when (body.lastOrNull()) {
            is IrStmt.Return -> true
            is IrStmt.Throw -> true
            else -> false
        }

    // ── Statements ────────────────────────────────────────────────────────

    private fun emitStmt(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> if (stmt.reactiveLifetime != null) emitReactiveDecl(stmt.name, stmt.type, stmt.initializer)
                else emitLocalDecl(stmt.name, stmt.type, stmt.initializer, stmt.lazy)
            is IrStmt.FinDecl -> if (stmt.reactiveLifetime != null) emitReactiveDecl(stmt.name, stmt.type, stmt.initializer)
                else emitLocalDecl(stmt.name, stmt.type, stmt.initializer, stmt.lazy)
            is IrStmt.LetDecl -> if (stmt.reactiveLifetime != null) emitReactiveDecl(stmt.name, stmt.type, stmt.initializer)
                else emitLocalDecl(stmt.name, stmt.type, stmt.initializer, stmt.lazy)
            is IrStmt.Assignment -> {
                val type = variableType(stmt.name) ?: stmt.value.type
                line(storeVariable(stmt.name, type, emitAs(stmt.value, type)))
                if (!emittingReactiveEffect) {
                    val changed = invalidateLazyDependents(stmt.name) + stmt.name
                    for (effect in activeReactiveEffects.filter { effect ->
                        effect.dependencies.any { it in changed }
                    }) {
                        emitReactiveEffect(effect)
                    }
                }
            }
            is IrStmt.Exchange -> emitExchange(stmt)
            is IrStmt.IndexAssign -> {
                val element = elementType(stmt.target)
                line("(${wasmStore(element)} ${elemAddr(stmt.target, stmt.index)} ${emitAs(stmt.value, element)})")
            }
            is IrStmt.MemberAssign -> {
                val field = fieldSlot(stmt.target, stmt.name)
                line("(${wasmStore(field.type)} ${fieldAddr(stmt.target, stmt.name)} ${emitAs(stmt.value, field.type)})")
            }
            is IrStmt.ExprStmt -> {
                val e = emitExpr(stmt.expr)
                // Every expression lowering yields a private runtime value;
                // Unit is encoded as zero and Nothing as an unreachable block.
                line("(drop $e)")
            }
            is IrStmt.Return -> {
                val value = stmt.value
                // The value is computed before the deferred bodies run, which is
                // what makes `return x` with a `defer` changing `x` return `x`.
                val deferred = deferredCode(failing = false)
                when {
                    value == null -> { if (deferred.isNotEmpty()) line(deferred); line("(return)") }
                    value.type == IrType.Nothing -> line("(drop ${emitExpr(value)})")
                    currentReturnType == IrType.Unit -> {
                        line("(drop ${emitExpr(value)})")
                        if (deferred.isNotEmpty()) line(deferred)
                        line("(return)")
                    }
                    deferred.isEmpty() -> line("(return ${emitAs(value, currentReturnType)})")
                    else -> {
                        val result = newTemp(wasmType(currentReturnType))
                        line("(local.set $result ${emitAs(value, currentReturnType)})")
                        line(deferred)
                        line("(return (local.get $result))")
                    }
                }
            }
            is IrStmt.If -> emitIf(stmt)
            is IrStmt.When -> emitWhen(stmt)
            is IrStmt.While -> emitWhile(stmt.label, emitExpr(stmt.condition), stmt.body, isFor = false, forInc = null)
            is IrStmt.Loop -> emitWhile(stmt.label, "(i32.const 1)", stmt.body, isFor = false, forInc = null)
            is IrStmt.For -> emitFor(stmt)
            is IrStmt.ForEach -> emitForEach(stmt)
            is IrStmt.Break -> line("(br \$${breakTarget(stmt.label)})")
            is IrStmt.Continue -> line("(br \$${continueTarget(stmt.label)})")
            is IrStmt.Scope -> for (s in stmt.body) emitStmt(s)
            is IrStmt.Assert -> line("(if (i32.eqz ${emitExpr(stmt.condition)}) (then unreachable))")
            is IrStmt.Trace -> emitTrace(stmt)
            is IrStmt.Throw -> emitThrow(stmt)
            is IrStmt.Try -> emitTry(stmt)
            is IrStmt.Defer -> {
                val counter = deferSlots.firstOrNull { it.first === stmt }?.second
                    ?: error("WebAssembly cannot lower a 'defer' outside a function body yet")
                line("(local.set \$$counter (i32.add (local.get \$$counter) (i32.const 1)))")
            }
            is IrStmt.Effect -> {
                activeReactiveEffects.add(stmt)
                emitReactiveEffect(stmt)
            }
            is IrStmt.Yield -> error("WebAssembly cannot lower 'yield' yet; generators run on the interpreter")
        }
    }

    /**
     * Raises an error: record it, then leave by [failureExit].
     *
     * The function's own return value has no room for a failure, so the error
     * waits in the module's error slot for the check after the call.
     */
    private fun emitThrow(stmt: IrStmt.Throw) {
        usesErrorSlot = true
        line("(global.set \$__azora_err ${erase(emitExpr(stmt.value), stmt.value.type)})")
        line("(global.set \$__azora_failed (i32.const 1))")
        line(failureExit())
    }

    /**
     * Where a failure goes from here: to the innermost handler, else out to the
     * caller with the function's zero value when the function can fail, else
     * nowhere - an error nothing can observe stops the program.
     */
    private fun failureExit(): String = when {
        errorHandlers.isNotEmpty() -> "(br \$${errorHandlers.last()})"
        currentIsFailable && hasFunctionResult(currentReturnType) -> {
            val type = wasmType(currentReturnType)
            "${deferredCode(failing = true)} (return ($type.const 0))".trim()
        }
        currentIsFailable && currentReturnType == IrType.Unit -> "${deferredCode(failing = true)} (return)".trim()
        else -> "unreachable"
    }

    /**
     * Gives each `defer` in [body] a local counting how often it was reached.
     * Every exit runs each that many times, the last in source order first;
     * see the LLVM backend's twin for what that leaves out.
     */
    private fun prepareDefers(body: List<IrStmt>) {
        deferSlots.clear()
        renderingDefers = false
        val found = mutableListOf<IrStmt.Defer>()
        collectDefers(body, found)
        for (stmt in found) {
            val counter = "__defer_${deferSlots.size}"
            declareLocal(counter, IrType.Int)
            deferSlots += stmt to counter
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

    /**
     * The deferred bodies an exit runs, as text: each `defer` drained by its
     * counter, last first. [failing] adds the `error defer`s, and a `rescue`
     * that runs clears the pending error, so the caller sees the zero value as
     * a success.
     */
    private fun deferredCode(failing: Boolean): String {
        if (renderingDefers || deferSlots.isEmpty()) return ""
        renderingDefers = true
        val saved = out.toString()
        val savedIndent = indent
        try {
            out.clear()
            for ((stmt, counter) in deferSlots.asReversed()) {
                if (stmt.onFail && !failing) continue
                val n = blockCounter++
                line("(block \$defer_done_$n (loop \$defer_next_$n")
                line("(br_if \$defer_done_$n (i32.eqz (local.get \$$counter)))")
                line("(local.set \$$counter (i32.sub (local.get \$$counter) (i32.const 1)))")
                for (inner in stmt.body) emitStmt(inner)
                if (failing && stmt.suppress) {
                    usesErrorSlot = true
                    line("(global.set \$__azora_failed (i32.const 0))")
                }
                line("(br \$defer_next_$n)))")
            }
            return out.toString().trim()
        } finally {
            out.clear()
            out.append(saved)
            indent = savedIndent
            renderingDefers = false
        }
    }

    /**
     * `try { … } catch { e -> … }`.
     *
     * The body sits inside the handler's block, so a failure anywhere in it
     * branches out to the catch code that follows the block. That code binds the
     * error, then clears the flag: a handled error must not still be pending
     * when the next call checks.
     */
    private fun emitTry(stmt: IrStmt.Try) {
        val catchBody = stmt.catchBody
        if (catchBody == null) {
            for (s in stmt.body) emitStmt(s)
            return
        }
        usesErrorSlot = true
        val n = blockCounter++
        val done = "try_done_$n"
        val handler = "catch_$n"
        line("(block \$$done")
        indent++
        line("(block \$$handler")
        indent++
        errorHandlers.addLast(handler)
        for (s in stmt.body) emitStmt(s)
        errorHandlers.removeLast()
        line("(br \$$done)")
        indent--
        line(")")
        stmt.catchName?.let { name ->
            declareLocal(name, IrType.Any)
            line(storeVariable(name, IrType.Any, "(global.get \$__azora_err)"))
        }
        line("(global.set \$__azora_failed (i32.const 0))")
        for (s in catchBody) emitStmt(s)
        indent--
        line(")")
    }

    /**
     * `expr catch fallback` - the expression's value, or the fallback if it failed.
     *
     * The primary runs inside the handler's block and leaves the whole
     * expression with its value; a failure in it falls out of that block onto
     * the fallback instead.
     */
    private fun emitCatchExpr(expr: IrExpr.CatchExpr): String {
        usesErrorSlot = true
        val n = blockCounter++
        val done = "catch_done_$n"
        val handler = "catch_expr_$n"
        errorHandlers.addLast(handler)
        val primary = emitAs(expr.expr, expr.type)
        errorHandlers.removeLast()
        val fallback = emitAs(expr.fallback, expr.type)
        return "(block \$$done (result ${wasmType(expr.type)}) " +
            "(block \$$handler (br \$$done $primary)) " +
            "(global.set \$__azora_failed (i32.const 0)) $fallback)"
    }

    /**
     * [value], a call to a failable function, followed by the check that sends
     * a failure to [failureExit] before anything can use the value it returned
     * in place of one.
     */
    private fun checkedCall(value: String, type: IrType): String {
        usesErrorSlot = true
        val scalar = wasmType(type)
        val result = newTemp(scalar)
        return "(block (result $scalar) (local.set $result $value) " +
            "(if (global.get \$__azora_failed) (then ${failureExit()})) (local.get $result))"
    }

    private fun emitReactiveEffect(effect: IrStmt.Effect) {
        val previous = emittingReactiveEffect
        emittingReactiveEffect = true
        try {
            for (stmt in effect.body) emitStmt(stmt)
        } finally {
            emittingReactiveEffect = previous
        }
    }

    /** Select the same storage for assignment and value-producing mutation. */
    private fun storeVariable(name: String, type: IrType, value: String): String {
        val alias = reactiveAliases[name]
        val target = alias?.valueGlobal ?: name
        val boxed = boxedLocals[target]
        if (boxed != null) {
            return "(${wasmStore(localIrTypes[target] ?: type)} (local.get \$$boxed) $value)"
        }
        val storage = if (alias != null || (target in globalTypes && target !in localIrTypes)) "global" else "local"
        return "($storage.set \$$target $value)"
    }

    /** The declared type of the storage an assignment to [name] writes. */
    private fun variableType(name: String): IrType? {
        val target = reactiveAliases[name]?.valueGlobal ?: name
        return localIrTypes[target] ?: globalTypes[target]
    }

    private fun emitLocalDecl(name: String, type: IrType, initializer: IrExpr, lazy: Boolean) {
        declareLocal(name, type)
        if (!lazy) {
            line("(local.set \$$name ${emitAs(initializer, type)})")
            return
        }
        val flagName = "__lazy_init_$name"
        declareLocal(flagName, IrType.Bool)
        line("(local.set \$$flagName (i32.const 0))")
        val refs = linkedMapOf<String, IrType>()
        collectReferencedVars(initializer, refs)
        lazyLocals[name] = LazyLocal(type, initializer, refs.keys, flagName)
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
                is IrStmt.Effect -> collectReactiveStorage(owner, stmt.body)
                else -> Unit
            }
        }
    }

    private fun registerReactiveStorage(owner: String, name: String, type: IrType) {
        fun safe(value: String) = value.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        val base = "__azora_reactive_${safe(owner)}_${safe(name)}"
        reactiveStorage[owner to name] = ReactiveStorage(base, "${base}_initialized", type)
    }

    private fun emitReactiveDecl(name: String, type: IrType, initializer: IrExpr) {
        val storage = reactiveStorage[currentFunctionName to name]
            ?: error("missing reactive storage for '$currentFunctionName::$name'")
        reactiveAliases[name] = storage
        line("(if (i32.eqz (global.get \$${storage.initGlobal}))")
        indent++
        line("(then")
        indent++
        line("(global.set \$${storage.valueGlobal} ${emitAs(initializer, type)})")
        line("(global.set \$${storage.initGlobal} (i32.const 1))")
        indent--
        line("))")
        indent--
    }

    private fun ensureLazyInitialized(name: String) {
        val lazy = lazyLocals[name] ?: return
        line("(if (i32.eqz (local.get \$${lazy.flagName}))")
        indent++
        line("(then")
        indent++
        line("(local.set \$$name ${emitAs(lazy.initializer, lazy.type)})")
        line("(local.set \$${lazy.flagName} (i32.const 1))")
        indent--
        line("))")
        indent--
    }

    private fun invalidateLazyDependents(changed: String, seen: MutableSet<String> = mutableSetOf()): Set<String> {
        if (!seen.add(changed)) return emptySet()
        val invalidated = linkedSetOf<String>()
        for ((name, lazy) in lazyLocals) {
            if (changed !in lazy.dependencies) continue
            line("(local.set \$${lazy.flagName} (i32.const 0))")
            invalidated.add(name)
            invalidated.addAll(invalidateLazyDependents(name, seen))
        }
        return invalidated
    }

    private fun emitIf(stmt: IrStmt.If) {
        line("(if ${emitExpr(stmt.condition)}")
        indent++
        line("(then")
        indent++
        for (s in stmt.thenBranch) emitStmt(s)
        indent--
        if (stmt.elseBranch != null) {
            line(")")
            line("(else")
            indent++
            for (s in stmt.elseBranch) emitStmt(s)
            indent--
            line("))")
        } else {
            line("))")
        }
        indent--
    }

    private fun emitTrace(stmt: IrStmt.Trace) {
        if (stmt.message.type != IrType.String) return
        usesStrEq = true
        val level = newTemp("i32")
        line("(local.set $level ${emitExpr(stmt.level)})")
        var displayLevel = "(local.get $level)"
        for (variant in stmt.variants.asReversed()) {
            val source = internString(variant)
            val display = internString(variant.uppercase())
            displayLevel = "(if (result i32) " +
                "(call \$__str_eq (local.get $level) (i32.const $source)) " +
                "(then (i32.const $display)) (else $displayLevel))"
        }
        line("(call \$write_str (i32.const ${internString("[")}))")
        line("(call \$write_str $displayLevel)")
        line("(call \$write_str (i32.const ${internString("] ")}))")
        line("(call \$print_str ${emitExpr(stmt.message)})")
    }

    private fun emitWhen(stmt: IrStmt.When) {
        val tmp = newTemp(wasmType(stmt.scrutinee.type))
        line("(local.set $tmp ${emitExpr(stmt.scrutinee)})")
        var depth = 0
        for ((i, b) in stmt.branches.withIndex()) {
            val cond = b.patterns.map { p ->
                if (p is IrExpr.SlotPattern) {
                    usesIsCheck = true
                    "(call \$__isCheck (local.get $tmp) (i32.const ${internString(p.variantName)}))"
                } else {
                    "(${numPrefix(stmt.scrutinee.type)}.eq (local.get $tmp) ${emitAs(p, stmt.scrutinee.type)})"
                }
            }.reduce { a, c -> "(i32.or $a $c)" }
            line("(if $cond")
            indent++
            line("(then")
            indent++
            // Bind slot payloads: cell (index+1) of the tagged block.
            (b.patterns.firstOrNull { it is IrExpr.SlotPattern } as? IrExpr.SlotPattern)?.let { sp ->
                sp.bindings.forEachIndexed { bi, name ->
                    val type = sp.bindingTypes.getOrElse(bi) { IrType.Any }
                    declareLocal(name, type)
                    val cell = "(i32.load (i32.add (local.get $tmp) (i32.const ${(bi + 1) * 4})))"
                    line("(local.set \$$name ${coerceWasm(cell, IrType.Int, type)})")
                }
            }
            for (s in b.body) emitStmt(s)
            indent--
            line(")")
            line("(else")
            indent++
            depth++
        }
        if (stmt.elseBranch != null) for (s in stmt.elseBranch) emitStmt(s)
        repeat(depth) { indent--; line("))"); indent-- }
    }

    private fun emitWhile(
        label: String?, cond: String, body: List<IrStmt>, isFor: Boolean,
        bindIteration: (() -> Unit)? = null, forInc: (() -> Unit)?,
    ) {
        val n = blockCounter++
        val brk = "brk_$n"
        val loop = "loop_$n"
        val cont = if (isFor) "cont_$n" else loop
        val previousLabelTarget = label?.let { labelTargets[it] }
        if (label != null) labelTargets[label] = brk to cont
        loopStack.addLast(brk to cont)
        line("(block \$$brk")
        indent++
        line("(loop \$$loop")
        indent++
        line("(br_if \$$brk (i32.eqz $cond))")
        if (isFor) {
            line("(block \$$cont")
            indent++
        }
        bindIteration?.invoke()
        for (s in body) emitStmt(s)
        if (isFor) {
            indent--
            line(")")
        }
        forInc?.invoke()
        line("(br \$$loop)")
        indent--
        line(")")
        indent--
        line(")")
        loopStack.removeLast()
        if (label != null) {
            if (previousLabelTarget == null) labelTargets.remove(label)
            else labelTargets[label] = previousLabelTarget
        }
    }

    private fun emitForEach(stmt: IrStmt.ForEach) {
        val array = stmt.iterable.type as? IrType.Array
            ?: error("WebAssembly for-in requires an array; iteration over ${stmt.iterable.type} is not supported")
        val raw = newTemp("i32")
        val length = newTemp("i32")
        val index = newTemp("i32")
        declareLocal(stmt.elem, array.element)
        stmt.indexName?.let { declareLocal(it, IrType.Int) }
        line("(local.set $raw ${emitExpr(stmt.iterable)})")
        line("(local.set $length (i32.load (local.get $raw)))")
        line("(local.set $index (i32.const 0))")
        val condition = "(i32.lt_u (local.get $index) (local.get $length))"
        emitWhile(stmt.label, condition, stmt.body, isFor = true, bindIteration = {
            val address = "(i32.add (local.get $raw) (i32.add (i32.const 4) " +
                "(i32.mul (local.get $index) (i32.const ${wasmSize(array.element)}))))"
            line(storeVariable(stmt.elem, array.element, "(${wasmLoad(array.element)} $address)"))
            stmt.indexName?.let { line(storeVariable(it, IrType.Int, "(local.get $index)")) }
        }) {
            line("(local.set $index (i32.add (local.get $index) (i32.const 1)))")
        }
    }

    private fun emitFor(stmt: IrStmt.For) {
        check(stmt.start.type == IrType.Int && stmt.end.type == IrType.Int)
        val n = blockCounter++
        val cursor = "__range_cursor_$n"
        val bound = "__range_bound_$n"
        val step = "__range_step_$n"
        declareLocal(stmt.counter, IrType.Int)
        declareLocal(cursor, IrType.Long)
        declareLocal(bound, IrType.Long)
        declareLocal(step, IrType.Long)
        line("(local.set \$$cursor (i64.extend_i32_s ${emitExpr(stmt.start)}))")
        line("(local.set \$$bound (i64.extend_i32_s ${emitExpr(stmt.end)}))")
        val stepExpr = stmt.step?.let { emitExpr(it) } ?: "(i32.const 1)"
        line("(local.set \$$step (i64.extend_i32_s $stepExpr))")
        line("(if (i64.le_s (local.get \$$step) (i64.const 0)) (then unreachable))")
        if (stmt.descending) {
            line("(local.set \$$cursor (i64.sub (local.get \$$cursor) (i64.const 1)))")
        }
        stmt.indexName?.let { index ->
            declareLocal(index, IrType.Int)
            line("(local.set \$$index (i32.const 0))")
        }
        val cmp = if (stmt.descending) "i64.ge_s" else if (stmt.inclusive) "i64.le_s" else "i64.lt_s"
        // Set the visible row as the condition is entered. Progression remains
        // wide even when the last step leaves the Int range.
        val cond = "(block (result i32) (local.set \$${stmt.counter} (i32.wrap_i64 (local.get \$$cursor))) " +
            "($cmp (local.get \$$cursor) (local.get \$$bound)))"
        val op = if (stmt.descending) "i64.sub" else "i64.add"
        emitWhile(stmt.label, cond, stmt.body, isFor = true) {
            line("(local.set \$$cursor ($op (local.get \$$cursor) (local.get \$$step)))")
            stmt.indexName?.let { index ->
                line("(local.set \$$index (i32.add (local.get \$$index) (i32.const 1)))")
            }
        }
    }

    // ── Expressions (return a folded S-expression) ────────────────────────

    private fun emitExpr(expr: IrExpr): String {
        val value = emitRawExpr(expr)
        val integer = expr.type as? IrType.Integer ?: return value
        if (integer.bits >= 32) return value
        return if (integer.signed) {
            val shift = 32 - integer.bits
            "(i32.shr_s (i32.shl $value (i32.const $shift)) (i32.const $shift))"
        } else "(i32.and $value (i32.const ${(1L shl integer.bits) - 1}))"
    }

    private fun emitRawExpr(expr: IrExpr): String = when (expr) {
        IrExpr.UnitLiteral -> "(i32.const 0)"
        // WebAssembly's widest integer is 64 bits, so a 128-bit literal has
        // nowhere to go and says so rather than arriving truncated.
        is IrExpr.IntLiteral -> expr.text
            ?.let { error("a ${expr.type} literal '$it' is wider than WebAssembly's integers") }
            ?: "(${wasmType(expr.type)}.const ${expr.value})"
        is IrExpr.DoubleLiteral -> "(${wasmType(expr.type)}.const ${expr.value})"
        is IrExpr.BoolLiteral -> "(i32.const ${if (expr.value) 1 else 0})"
        is IrExpr.CharLiteral -> "(i32.const ${expr.value.code})"
        is IrExpr.StringLiteral -> "(i32.const ${internString(expr.value)})"
        is IrExpr.EnumLiteral -> "(i32.const ${internString(expr.variant)})"
        is IrExpr.EnumToString -> emitExpr(expr.value)
        // The null reference. No object lives at address 0: string data and
        // the heap both start above STRING_BASE. It is typed `Any`, so each
        // destination narrows it to its own slot.
        is IrExpr.Var -> if (expr.name == "__null") "(i64.const 0)" else {
            ensureLazyInitialized(expr.name)
            val alias = reactiveAliases[expr.name]
            val target = alias?.valueGlobal ?: expr.name
            val boxed = boxedLocals[target]
            if (boxed != null) {
                "(${wasmLoad(expr.type)} (local.get \$$boxed))"
            } else {
                val operation = if (alias != null || (target in globalTypes && target !in localIrTypes)) "global.get" else "local.get"
                "($operation \$$target)"
            }
        }
        is IrExpr.Unary -> when (expr.op) {
            IrUnaryOp.NEG -> {
                val p = numPrefix(expr.type)
                if (p == "f64" || p == "f32") "($p.neg ${emitExpr(expr.operand)})"
                else "($p.sub ($p.const 0) ${emitExpr(expr.operand)})"
            }
            IrUnaryOp.NOT -> "(i32.eqz ${emitExpr(expr.operand)})"
            IrUnaryOp.BIT_NOT -> { val p = numPrefix(expr.type); "($p.xor ${emitExpr(expr.operand)} ($p.const -1))" }
        }
        is IrExpr.IncDec -> {
            val read = emitExpr(expr.target)
            val scalar = wasmType(expr.type)
            val old = newTemp(scalar)
            val updated = newTemp(scalar)
            val op = if (expr.delta > 0) "$scalar.add" else "$scalar.sub"
            val write = storeVariable(expr.target.name, expr.type, "(local.get $updated)")
            // Save the old value before writing. A textual local.get emitted
            // after the write would return the new value even for postfix.
            "(block (result $scalar) (local.set $old $read) " +
                "(local.set $updated ($op (local.get $old) ($scalar.const 1))) " +
                "$write (local.get ${if (expr.prefix) updated else old}))"
        }
        is IrExpr.Binary -> emitBinary(expr)
        is IrExpr.Call -> emitCall(expr)
        is IrExpr.Await -> emitExpr(expr.value)
        is IrExpr.Spread -> error("WebAssembly cannot expand a spread into fixed call parameters yet")
        is IrExpr.Index -> {
            val element = elementType(expr.target)
            coerceWasm("(${wasmLoad(element)} ${elemAddr(expr.target, expr.index)})", element, expr.type)
        }
        is IrExpr.Member -> when {
            isPackTarget(expr.target) -> {
                val field = fieldSlot(expr.target, expr.name)
                coerceWasm("(${wasmLoad(field.type)} ${fieldAddr(expr.target, expr.name)})", field.type, expr.type)
            }
            specMember(expr.target.type, expr.name) != null -> emitDispatch(expr.target, expr.name, emptyList(), expr.type)
            expr.name == "length" || expr.name == "size" -> "(i32.load ${emitExpr(expr.target)})"
            expr.name == "data" -> "(i32.add ${emitExpr(expr.target)} (i32.const 4))"
            expr.name == "hash" && expr.target.type !is IrType.Named -> emitBuiltinHash(expr.target)
            else -> error("no WebAssembly storage for member '${expr.name}' of ${expr.target.type}")
        }
        is IrExpr.MethodCall -> emitMethodCall(expr)
        is IrExpr.StructCtor -> emitStructCtor(expr)
        is IrExpr.ArrayLiteral -> emitArrayLiteral(expr)
        is IrExpr.NumCast -> emitNumCast(expr)
        is IrExpr.IfExpr -> {
            val t = wasmType(expr.type)
            "(if (result $t) ${emitExpr(expr.condition)} (then ${emitAs(expr.thenExpr, expr.type)}) (else ${emitAs(expr.elseExpr, expr.type)}))"
        }
        is IrExpr.StringTemplate -> emitTemplate(expr)
        is IrExpr.CatchExpr -> emitCatchExpr(expr)
        is IrExpr.Lambda -> emitClosure(expr)
        is IrExpr.TupleLit -> emitTupleLit(expr)
        is IrExpr.TupleAccess -> {
            val types = (expr.target.type as? IrType.Tuple)?.elements
                ?: error("tuple component .${expr.index} of ${expr.target.type} has no tuple layout")
            val stored = types[expr.index]
            val address = "(i32.add ${emitExpr(expr.target)} (i32.const ${tupleOffsets(types).first[expr.index]}))"
            coerceWasm("(${wasmLoad(stored)} $address)", stored, expr.type)
        }
        is IrExpr.SetLit, is IrExpr.MapLit -> error("WebAssembly cannot lower a built-in ${expr.type.shown()} literal yet")
        // A variant has no layout here yet; a match on one that always failed,
        // or a value that was always zero, would be a program that runs wrong.
        is IrExpr.VariantLit -> error("WebAssembly cannot lower an anonymous variant value yet")
        is IrExpr.SlotPattern -> error("WebAssembly cannot match variant '${expr.slotName}.${expr.variantName}' yet")
    }

    private fun emitBinary(expr: IrExpr.Binary): String {
        // An erased operand meets a concrete one as the concrete type, which is
        // what it holds; comparing `T` with `null` reads the reference width.
        if ((expr.left.type == IrType.Any) != (expr.right.type == IrType.Any)) {
            val concrete = if (expr.left.type == IrType.Any) expr.right.type else expr.left.type
            fun narrow(side: IrExpr) = if (side.type == IrType.Any) IrExpr.NumCast(side, concrete) else side
            // An erased result (`x + 1` for `x: T`) is computed as the concrete type and erased again.
            val narrowed = expr.copy(
                left = narrow(expr.left),
                right = narrow(expr.right),
                type = if (expr.type == IrType.Any) concrete else expr.type,
            )
            return coerceWasm(emitBinary(narrowed), narrowed.type, expr.type)
        }
        val l = expr.left; val r = expr.right
        // Logical operators evaluate the right operand only when needed.
        // Wasm's integer and/or instructions eagerly evaluate both operands;
        // those instructions implement only the distinct bitwise operators.
        if (expr.op == IrBinaryOp.AND) {
            return "(if (result i32) ${emitExpr(l)} (then ${emitExpr(r)}) (else (i32.const 0)))"
        }
        if (expr.op == IrBinaryOp.OR) {
            return "(if (result i32) ${emitExpr(l)} (then (i32.const 1)) (else ${emitExpr(r)}))"
        }
        if (expr.op == IrBinaryOp.ADD && expr.type == IrType.String) {
            usesAlloc = true; usesConcat = true
            return "(call \$__str_concat ${emitExpr(l)} ${emitExpr(r)})"
        }
        if (expr.op == IrBinaryOp.MUL && l.type == IrType.String && r.type == IrType.Int) {
            usesAlloc = true; usesRepeat = true
            return "(call \$__str_repeat ${emitExpr(l)} ${emitExpr(r)})"
        }
        if (expr.op == IrBinaryOp.MUL && l.type == IrType.Int && r.type == IrType.String) {
            usesAlloc = true; usesRepeat = true
            return "(call \$__str_repeat ${emitExpr(r)} ${emitExpr(l)})"
        }
        if (l.type == IrType.String && (expr.op == IrBinaryOp.EQ || expr.op == IrBinaryOp.NEQ)) {
            usesStrEq = true
            val eq = "(call \$__str_eq ${emitExpr(l)} ${emitExpr(r)})"
            return if (expr.op == IrBinaryOp.EQ) eq else "(i32.eqz $eq)"
        }
        // Mixed numeric operands widen to a common type so the machine op sees two
        // operands of one Wasm type.
        val bothNum = isNumericWasm(l.type) && isNumericWasm(r.type)
        val opType = if (bothNum) commonNumericWasm(l.type, r.type) else l.type
        val p = numPrefix(opType)
        val u = isUnsigned(opType)
        val flt = p == "f64" || p == "f32"
        val instr = when (expr.op) {
            IrBinaryOp.ADD -> "$p.add"; IrBinaryOp.SUB -> "$p.sub"; IrBinaryOp.MUL -> "$p.mul"
            IrBinaryOp.DIV -> if (flt) "$p.div" else "$p.div_${if (u) "u" else "s"}"
            IrBinaryOp.MOD -> "$p.rem_${if (u) "u" else "s"}"
            IrBinaryOp.EQ -> "$p.eq"; IrBinaryOp.NEQ -> "$p.ne"
            IrBinaryOp.LT -> if (flt) "$p.lt" else "$p.lt_${if (u) "u" else "s"}"
            IrBinaryOp.LTE -> if (flt) "$p.le" else "$p.le_${if (u) "u" else "s"}"
            IrBinaryOp.GT -> if (flt) "$p.gt" else "$p.gt_${if (u) "u" else "s"}"
            IrBinaryOp.GTE -> if (flt) "$p.ge" else "$p.ge_${if (u) "u" else "s"}"
            IrBinaryOp.AND, IrBinaryOp.OR -> error("Logical operators must use short-circuit control flow")
            IrBinaryOp.BIT_AND -> "$p.and"; IrBinaryOp.BIT_OR -> "$p.or"; IrBinaryOp.BIT_XOR -> "$p.xor"
            IrBinaryOp.SHL -> "$p.shl"; IrBinaryOp.SHR -> "$p.shr_${if (u) "u" else "s"}"
        }
        val lv = if (bothNum) coerceWasm(emitExpr(l), l.type, opType) else emitExpr(l)
        val rv = if (bothNum) coerceWasm(emitExpr(r), r.type, opType) else emitExpr(r)
        return "($instr $lv $rv)"
    }

    private fun isNumericWasm(t: IrType): Boolean =
        IrType.isInteger(t) || t in IrType.floatTypes || t == IrType.Char

    private fun commonNumericWasm(a: IrType, b: IrType): IrType {
        if (a == b) return a
        if (a in IrType.floatTypes || b in IrType.floatTypes) {
            if (a == IrType.Double || b == IrType.Double || a == IrType.Quad || b == IrType.Quad) return IrType.Double
            return IrType.Float
        }
        // Widen ints: i64 types win over i32 types.
        val aWide = wasmType(a) == "i64"
        val bWide = wasmType(b) == "i64"
        return if (aWide || !bWide) a else b
    }

    /** Emits a Wasm numeric conversion of [value] from [from] to [to] (no-op when the Wasm type is unchanged). */
    private fun coerceWasm(value: String, from: IrType, to: IrType): String {
        if (to is IrType.Named && to.name in specTables && from is IrType.Named &&
            from.name in specTypeIds && from.name !in specTables
        ) return boxForSpec(value, from.name)
        val ft = wasmType(from); val tt = wasmType(to)
        if (ft == tt) return value
        // Crossing an erased boundary keeps the value's bits rather than
        // converting its number: a Double travels as its IEEE pattern.
        if (from == IrType.Any) return unerase(value, to)
        if (to == IrType.Any) return erase(value, from)
        val su = if (isUnsigned(from)) "u" else "s"
        val tu = if (isUnsigned(to)) "u" else "s"
        val op = when ("$ft-$tt") {
            "i32-i64" -> "i64.extend_i32_$su"
            "i64-i32" -> "i32.wrap_i64"
            "i32-f64" -> "f64.convert_i32_$su"
            "i32-f32" -> "f32.convert_i32_$su"
            "i64-f64" -> "f64.convert_i64_$su"
            "i64-f32" -> "f32.convert_i64_$su"
            "f64-i32" -> "i32.trunc_f64_$tu"
            "f32-i32" -> "i32.trunc_f32_$tu"
            "f64-i64" -> "i64.trunc_f64_$tu"
            "f32-i64" -> "i64.trunc_f32_$tu"
            "f32-f64" -> "f64.promote_f32"
            "f64-f32" -> "f32.demote_f64"
            else -> return value
        }
        return "($op $value)"
    }

    /** [value] of type [from] as the eight-byte bits of an erased slot. */
    private fun erase(value: String, from: IrType): String = when (wasmType(from)) {
        "i64" -> value
        "f64" -> "(i64.reinterpret_f64 $value)"
        "f32" -> "(i64.extend_i32_u (i32.reinterpret_f32 $value))"
        else -> "(i64.extend_i32_${if (isUnsigned(from)) "u" else "s"} $value)"
    }

    /** The erased bits [value] read back as a value of [to]. */
    private fun unerase(value: String, to: IrType): String = when (wasmType(to)) {
        "i64" -> value
        "f64" -> "(f64.reinterpret_i64 $value)"
        "f32" -> "(f32.reinterpret_i32 (i32.wrap_i64 $value))"
        else -> "(i32.wrap_i64 $value)"
    }

    /**
     * A pack seen through a spec it implements: `[type id, pack pointer]`, which
     * is what the spec's dispatchers switch on. A spec value passed on as a
     * spec keeps its box.
     */
    private fun boxForSpec(value: String, implementer: String): String {
        usesAlloc = true
        val box = newTemp("i32")
        return "(block (result i32) (local.set $box (call \$__alloc (i32.const 8))) " +
            "(i32.store (local.get $box) (i32.const ${specTypeIds.getValue(implementer)})) " +
            "(i32.store offset=4 (local.get $box) $value) (local.get $box))"
    }

    private fun specMember(type: IrType, name: String): Pair<IrSpecTable, IrSpecMethod>? {
        val table = (type as? IrType.Named)?.name?.let { specTables[it] } ?: return null
        return table.methods.firstOrNull { it.name == name }?.let { table to it }
    }

    private fun emitMethodCall(expr: IrExpr.MethodCall): String {
        check(specMember(expr.target.type, expr.name) != null) {
            "method '${expr.name}' of ${expr.target.type} is not supported by the WebAssembly target"
        }
        return emitDispatch(expr.target, expr.name, expr.args, expr.type)
    }

    /** A spec member called on a boxed receiver, through that member's dispatcher. */
    private fun emitDispatch(target: IrExpr, name: String, args: List<IrExpr>, type: IrType): String {
        val (table, method) = specMember(target.type, name)!!
        val dispatcher = "__dyn_${table.specName}_$name"
        neededDispatchers.getOrPut(dispatcher) { table to method }
        val operands = listOf(emitExpr(target)) + args.withIndex().map { (i, arg) ->
            method.paramTypes.getOrNull(i)?.let { emitAs(arg, it) } ?: emitExpr(arg)
        }
        val call = "(call \$$dispatcher ${operands.joinToString(" ")})"
        return wrapCallResult(type, callResult(call, method.returnType, type))
    }

    /**
     * Calls the implementer's own function for the box's type id. An id no
     * implementer owns traps: there is no value to invent for it.
     */
    private fun renderDispatcher(name: String, table: IrSpecTable, method: IrSpecMethod): String {
        val params = method.paramTypes.withIndex().joinToString("") { (i, type) -> " (param \$a$i ${wasmType(type)})" }
        val result = if (hasFunctionResult(method.returnType)) " (result ${wasmType(method.returnType)})" else ""
        val sb = StringBuilder("  (func \$$name (param \$box i32)$params$result\n")
        for (impl in table.impls) {
            val function = impl.methodFuncs[method.name] ?: continue
            val declared = functionParams[function]
                ?: error("${table.specName}.${method.name} implementation '$function' is not in the WebAssembly module")
            val args = listOf("(i32.load offset=4 (local.get \$box))") + method.paramTypes.indices.map { i ->
                coerceWasm("(local.get \$a$i)", method.paramTypes[i], declared.getOrElse(i + 1) { method.paramTypes[i] })
            }
            val call = "(call \$$function ${args.joinToString(" ")})"
            val physical = functionResults.getValue(function)
            val body = when {
                hasFunctionResult(method.returnType) -> "(return ${coerceWasm(call, physical, method.returnType)})"
                hasFunctionResult(physical) -> "(drop $call) (return)"
                else -> "$call (return)"
            }
            sb.append("    (if (i32.eq (i32.load (local.get \$box)) (i32.const ${specTypeIds.getValue(impl.typeName)})) (then $body))\n")
        }
        sb.append("    unreachable)\n")
        return sb.toString()
    }

    /** [expr] as a value of [type], converting at an erased or numeric boundary. */
    private fun emitAs(expr: IrExpr, type: IrType): String = coerceWasm(emitExpr(expr), expr.type, type)

    private fun emitCall(expr: IrExpr.Call): String {
        check(listOf("_atomicLoad", "_atomicStore", "_atomicAdd", "_atomicCas").none { symbolDenotes(expr.name, it) }) {
            "WebAssembly synchronization requires shared memory and atomic instructions, which this target does not support yet"
        }
        if (expr.name == Intrinsics.NULL_COALESCE) return emitNullCoalesce(expr)
        check(expr.name != "__delay") {
            "WebAssembly delay requires a host clock, which this target does not support yet"
        }
        if (expr.name == "__panic") {
            // The message is evaluated even though the MVP target has no host
            // panic reporter. `unreachable` is Wasm's bottom instruction.
            return "(block (result i32) (drop ${emitExpr(expr.args.single())}) unreachable)"
        }
        // The compiler-supplied `clone` (and a `Copy` aggregate's copy). This
        // target has no copy of a pack or an array yet; saying so here is better
        // than a call to nothing that the assembler then refuses.
        if (expr.name == "__isolated") {
            val value = expr.args.single()
            val layout = (value.type as? IrType.Named)?.let { layouts[it.name] }
            if (layout != null) {
                val def = structDefinitions.getValue((value.type as IrType.Named).name)
                if (def.fields.any { it.ownsValue && (it.type is IrType.Named || it.type is IrType.Array || it.type is IrType.Function || it.type is IrType.Pointer) })
                    error("WebAssembly cannot recursively copy a ${value.type} for 'clone' yet")
                usesAlloc = true
                val original = newTemp("i32")
                val copy = newTemp("i32")
                return "(block (result i32) (local.set $original ${emitExpr(value)}) " +
                    "(local.set $copy (call \$__alloc (i32.const ${layout.size}))) " +
                    "(memory.copy (local.get $copy) (local.get $original) (i32.const ${layout.size})) " +
                    "(local.get $copy))"
            }
            if (value.type is IrType.Function) {
                usesAlloc = true
                val original = newTemp("i32")
                val copy = newTemp("i32")
                val environment = newTemp("i32")
                return "(block (result i32) (local.set $original ${emitExpr(value)}) " +
                    "(local.set $copy (call \$__alloc (i32.const 8))) " +
                    "(memory.copy (local.get $copy) (local.get $original) (i32.const 8)) " +
                    "(local.set $environment (i32.load offset=4 (local.get $copy))) " +
                    "(i32.store (local.get $environment) (i32.add (i32.load (local.get $environment)) (i32.const 1))) " +
                    "(local.get $copy))"
            }
            error("WebAssembly cannot copy a ${expr.args.single().type} for 'clone' yet")
        }
        when (expr.name) {
            "__alloc" -> return emitPointerAlloc(expr)
            "__allocBuffer" -> return emitPointerBufferAlloc(expr)
            "__deref" -> {
                val pointer = expr.args.single()
                val pointee = (pointer.type as? IrType.Pointer)?.inner ?: expr.type
                return coerceWasm("(${wasmLoad(pointee)} ${emitExpr(pointer)})", pointee, expr.type)
            }
            "__derefAssign" -> {
                val (pointer, value) = expr.args
                val pointee = (pointer.type as? IrType.Pointer)?.inner ?: value.type
                val stored = coerceWasm(emitExpr(value), value.type, pointee)
                return wrapCallResult(expr.type, "(${wasmStore(pointee)} ${emitExpr(pointer)} $stored)")
            }
            "__take" -> return emitOwnershipTake(expr.args.single())
            "__purge" -> return wrapCallResult(expr.type, emitPurge(expr.args.single()))
        }
        if (expr.receiver != null) {
            val callable = expr.receiver.type as? IrType.Function
                ?: error("indirect call receiver is not a callable type")
            val closure = newTemp("i32")
            val typeName = closureTypeName(callable)
            val declared = callable.params + callable.receivers
            val args = expr.args.withIndex().joinToString(" ") { (i, arg) ->
                declared.getOrNull(i)?.let { emitAs(arg, it) } ?: emitExpr(arg)
            }
            val environment = "(i32.load (i32.add (local.get $closure) (i32.const 4)))"
            val tableIndex = "(i32.load (local.get $closure))"
            val operands = listOf(args, environment, tableIndex).filter { it.isNotEmpty() }.joinToString(" ")
            val result = if (hasFunctionResult(callable.ret)) " (result ${wasmType(callable.ret)})" else ""
            val call = "(block$result " +
                "(local.set $closure ${emitExpr(expr.receiver)}) " +
                "(call_indirect (type \$$typeName) $operands))"
            return wrapCallResult(expr.type, callResult(call, callable.ret, expr.type))
        }
        if ((symbolDenotes(expr.name, "println") || symbolDenotes(expr.name, "print")) && expr.args.size == 1) {
            val arg = expr.args.single()
            val operation = if (symbolDenotes(expr.name, "print")) "write" else "print"
            // A float is rendered by the compiler, not the host: printing one
            // directly and interpolating it must produce the same digits, and only
            // `__double_to_str` implements the language's convention.
            if (wasmType(arg.type) == "f64" || wasmType(arg.type) == "f32") {
                usesAlloc = true
                usesDoubleToStr = true
                val v = if (wasmType(arg.type) == "f32") "(f64.promote_f32 ${emitExpr(arg)})" else emitExpr(arg)
                return wrapCallResult(expr.type, "(call \$${operation}_str (call \$__double_to_str $v))")
            }
            val fn = when {
                arg.type == IrType.String -> "${operation}_str"
                arg.type == IrType.Bool -> "${operation}_bool"
                wasmType(arg.type) == "i64" -> "${operation}_i64"
                else -> "${operation}_i32"
            }
            return wrapCallResult(expr.type, "(call \$$fn ${emitExpr(arg)})")
        }
        if (expr.name == "__isCheck") usesIsCheck = true
        // `Array::fill<T>(count)` → `[ len, T×count ]` (all cells i32 in Wasm).
        if (symbolDenotes(expr.name, "Array_fill")) {
            usesAlloc = true
            val t = newTemp("i32")
            val count = emitExpr(expr.args[0])
            return "(block (result i32)\n" +
                "  (local.set $t (call \$__alloc (i32.add (i32.const 4) (i32.mul $count (i32.const 4)))))\n" +
                "  (i32.store (local.get $t) $count)\n" +
                "  (local.get $t))"
        }
        if (expr.name in stringIntrinsics) neededIntrinsics.add(expr.name)
        if (expr.name in externs && expr.name !in stringIntrinsics) neededExterns.add(expr.name)
        // Arguments take the callee's declared parameter types and the result
        // leaves its physical return type: a generic callee's are erased.
        val declared = functionParams[expr.name]
        val args = expr.args.withIndex().joinToString(" ") { (i, arg) ->
            declared?.getOrNull(i)?.let { emitAs(arg, it) } ?: emitExpr(arg)
        }
        val call = "(call \$${expr.name}${if (args.isEmpty()) "" else " $args"})"
        val value = wrapCallResult(expr.type, callResult(call, functionResults[expr.name] ?: expr.type, expr.type))
        return if (expr.name in failableFunctions) checkedCall(value, expr.type) else value
    }

    /**
     * `a ?? b` - what `a` holds, or `b` when it holds nothing. A nullable is
     * held as its inner type with null as all-zero bits, so the test is on the
     * bits; `a` is evaluated once and `b` only when it is null.
     */
    private fun emitNullCoalesce(expr: IrExpr.Call): String {
        val (left, right) = expr.args
        if (left.type !is IrType.Nullable && left.type != IrType.Any) return emitAs(left, expr.type)
        val scalar = wasmType(left.type)
        val held = newTemp(scalar)
        val isNull = when (scalar) {
            "f64" -> "(i64.eqz (i64.reinterpret_f64 (local.get $held)))"
            "f32" -> "(i32.eqz (i32.reinterpret_f32 (local.get $held)))"
            else -> "($scalar.eqz (local.get $held))"
        }
        val resultType = wasmType(expr.type)
        return "(block (result $resultType) (local.set $held ${emitExpr(left)}) " +
            "(if (result $resultType) $isNull " +
            "(then ${emitAs(right, expr.type)}) " +
            "(else ${coerceWasm("(local.get $held)", left.type, expr.type)})))"
    }

    /** A call's result converted from the callee's [physical] return type to the call site's [expected] one. */
    private fun callResult(call: String, physical: IrType, expected: IrType): String =
        if (hasFunctionResult(physical) && hasFunctionResult(expected)) coerceWasm(call, physical, expected) else call

    /** Turns an erased Unit/Nothing call into a value-shaped Wasm expression. */
    private fun wrapCallResult(type: IrType, call: String): String = when (type) {
        IrType.Unit -> "(block (result i32) $call (i32.const 0))"
        IrType.Nothing -> "(block (result i32) $call unreachable)"
        else -> call
    }

    /**
     * `alloc value` - a fresh heap block owning the value. An array's elements
     * move into a buffer of their own rather than the pointer aliasing the
     * array's storage, so purging the pointer releases exactly that buffer.
     */
    private fun emitPointerAlloc(expr: IrExpr.Call): String {
        usesAlloc = true
        val valueExpr = expr.args.single()
        val value = emitExpr(valueExpr)
        val block = newTemp("i32")
        val array = valueExpr.type as? IrType.Array
        if (array != null) {
            val stride = wasmSize(array.element)
            val pointee = (expr.type as? IrType.Pointer)?.inner
            check(pointee == null || wasmSize(pointee) == stride) {
                "alloc of $array as $pointee changes the element slot width"
            }
            val source = newTemp("i32")
            return "(block (result i32) (local.set $source $value) " +
                "(local.set $block (call \$__alloc_buffer (i32.load (local.get $source)) (i32.const $stride))) " +
                "(memory.copy (local.get $block) (i32.add (local.get $source) (i32.const 4)) " +
                "(i32.mul (i32.load (local.get $source)) (i32.const $stride))) " +
                "(local.get $block))"
        }
        // The block holds the pointee type, which is erased behind a `T*`.
        val pointee = (expr.type as? IrType.Pointer)?.inner ?: valueExpr.type
        val slot = newTemp(wasmType(pointee))
        return "(block (result i32) (local.set $slot ${coerceWasm(value, valueExpr.type, pointee)}) " +
            "(local.set $block (call \$__alloc (i32.const ${wasmSize(pointee)}))) " +
            "(${wasmStore(pointee)} (local.get $block) (local.get $slot)) " +
            "(local.get $block))"
    }

    /** `alloc .() * count` - `count` zeroed elements; a negative or oversized count traps. */
    private fun emitPointerBufferAlloc(expr: IrExpr.Call): String {
        usesAlloc = true
        val element = (expr.type as? IrType.Pointer)?.inner
            ?: error("buffer allocation must produce a pointer, got ${expr.type}")
        val countExpr = expr.args.single()
        val count = when (wasmType(countExpr.type)) {
            "i32" -> emitExpr(countExpr)
            "i64" -> {
                val wide = newTemp("i64")
                "(block (result i32) (local.set $wide ${emitExpr(countExpr)}) " +
                    "(if (i64.gt_u (local.get $wide) (i64.const $WASM_MAX_ALLOC)) (then unreachable)) " +
                    "(i32.wrap_i64 (local.get $wide)))"
            }
            else -> error("buffer count must be an integer, got ${countExpr.type}")
        }
        return "(call \$__alloc_buffer $count (i32.const ${wasmSize(element)}))"
    }

    /**
     * `purge pointer` returns the pointer's block to the allocator. It releases
     * storage only: elements are not destroyed, which is what a container that
     * has already moved them out relies on.
     */
    private fun emitOwnershipTake(place: IrExpr): String {
        val result = newTemp(wasmType(place.type))
        val address = when (place) {
            is IrExpr.Member -> fieldAddr(place.target, place.name)
            is IrExpr.Index -> elemAddr(place.target, place.index)
            else -> null
        }
        if (address != null) {
            val slot = newTemp("i32")
            val stored = if (place is IrExpr.Member) fieldSlot(place.target, place.name).type else place.type
            val zero = "(${wasmType(stored)}.const 0)"
            return "(block (result ${wasmType(place.type)}) (local.set $slot $address) " +
                "(local.set $result ${coerceWasm("(${wasmLoad(stored)} (local.get $slot))", stored, place.type)}) " +
                "(${wasmStore(stored)} (local.get $slot) $zero) (local.get $result))"
        }
        if (place is IrExpr.Var && place.name != "__null") {
            val boxed = boxedLocals[place.name]
            if (boxed != null) return "(block (result ${wasmType(place.type)}) " +
                "(local.set $result (${wasmLoad(place.type)} (local.get \$$boxed))) " +
                "(${wasmStore(place.type)} (local.get \$$boxed) (${wasmType(place.type)}.const 0)) (local.get $result))"
            val clear = if (place.name in globalTypes) "(global.set \$${place.name} (${wasmType(place.type)}.const 0))"
                else "(local.set \$${place.name} (${wasmType(place.type)}.const 0))"
            return "(block (result ${wasmType(place.type)}) (local.set $result ${emitExpr(place)}) $clear (local.get $result))"
        }
        return emitExpr(place)
    }

    private fun emitPurge(value: IrExpr): String {
        val type = (value.type as? IrType.Nullable)?.inner ?: value.type
        usesAlloc = true
        val stored = newTemp("i32")
        if (type is IrType.Named && (type.name in structs || type.name in specTables)) {
            return "(call \$${ownershipDropName(type)} ${emitOwnershipTake(value)})"
        }
        if (type is IrType.Array) return "(call \$${ownershipDropName(type)} ${emitOwnershipTake(value)})"
        if (type is IrType.Function) return "(call \$${ownershipDropName(type)} ${emitOwnershipTake(value)})"
        check(type is IrType.Pointer) { "purge of $type is not supported by the WebAssembly target" }
        check(type.inner !is IrType.Named || (type.inner as IrType.Named).name !in structs) {
            "owned pack pointee destruction is not supported by the WebAssembly target"
        }
        return "(call \$__free ${emitOwnershipTake(value)})"
    }

    private fun ownershipDropName(type: IrType): String = ownershipDrops.getOrPut(type.shown()) {
        "__azora_drop_pack_${ownershipDrops.size}" to type
    }.first

    private fun renderOwnershipDrop(name: String, rawType: IrType): String {
        if (rawType is IrType.Function) {
            val body = StringBuilder("  (func \$$name (param \$self i32) (local \$env i32) (local \$refs i32)\n")
            body.append("    (if (local.get \$self) (then\n")
            body.append("      (local.set \$env (i32.load offset=4 (local.get \$self)))\n")
            body.append("      (local.set \$refs (i32.sub (i32.load (local.get \$env)) (i32.const 1)))\n")
            body.append("      (i32.store (local.get \$env) (local.get \$refs))\n")
            body.append("      (if (i32.eqz (local.get \$refs)) (then\n")
            for (closure in closureFunctions) {
                body.append("        (if (i32.eq (i32.load offset=4 (local.get \$env)) (i32.const ${closure.index})) (then\n")
                for (capture in closure.captures.asReversed()) {
                    if (capture.byRef) continue
                    val inner = (capture.type as? IrType.Nullable)?.inner ?: capture.type
                    val owned = inner is IrType.Function || inner is IrType.Array ||
                        (inner is IrType.Named && (inner.name in structs || inner.name in specTables))
                    if (!owned) continue
                    val drop = ownershipDropName(inner)
                    body.append("          (call \$$drop (i32.load offset=${capture.offset} (local.get \$env)))\n")
                }
                body.append("        ))\n")
            }
            body.append("        (call \$__free (local.get \$env))))\n")
            body.append("      (call \$__free (local.get \$self))))\n  )\n")
            return body.toString()
        }
        if (rawType is IrType.Array) {
            val child = (rawType.element as? IrType.Nullable)?.inner ?: rawType.element
            val owned = child is IrType.Array || child is IrType.Function || (child is IrType.Named && (child.name in structs || child.name in specTables))
            val body = StringBuilder("  (func \$$name (param \$self i32) (local \$i i32)\n")
            body.append("    (if (local.get \$self) (then\n")
            if (owned) {
                val drop = ownershipDropName(child)
                body.append("      (local.set \$i (i32.load (local.get \$self)))\n")
                body.append("      (block \$end (loop \$items (br_if \$end (i32.eqz (local.get \$i)))\n")
                body.append("        (local.set \$i (i32.sub (local.get \$i) (i32.const 1)))\n")
                body.append("        (call \$$drop (i32.load (i32.add (local.get \$self) (i32.add (i32.const 4) (i32.mul (local.get \$i) (i32.const 4))))))\n")
                body.append("        (br \$items)))\n")
            }
            body.append("      (call \$__free (local.get \$self))))\n  )\n")
            return body.toString()
        }
        val type = rawType as IrType.Named
        if (type.name in specTables) {
            val body = StringBuilder("  (func \$$name (param \$self i32)\n")
            body.append("    (if (local.get \$self) (then\n")
            for (impl in specTables.getValue(type.name).impls) {
                val drop = ownershipDropName(IrType.Named(impl.typeName, type.args))
                body.append("      (if (i32.eq (i32.load (local.get \$self)) (i32.const ${specTypeIds.getValue(impl.typeName)})) (then (call \$$drop (i32.load offset=4 (local.get \$self)))))\n")
            }
            body.append("      (call \$__free (local.get \$self))))\n  )\n")
            return body.toString()
        }
        val definition = structDefinitions.getValue(type.name)
        check(!definition.isUnion) { "owned union destruction requires an active-member tag on the WebAssembly target" }
        val dtor = "${type.name}_dtor"
        val body = StringBuilder("  (func \$$name (param \$self i32) (local \$value i32)\n")
        body.append("    (if (local.get \$self) (then\n")
        if (dtor in functionParams) body.append("      (call \$$dtor (local.get \$self))\n")
        for ((index, field) in definition.fields.withIndex().toList().asReversed()) {
            if (!field.ownsValue) continue
            val position = definition.typeParamSlots.getOrNull(index) ?: -1
            val concrete = if (position >= 0) type.args.getOrNull(position) ?: field.type else field.type
            val inner = (concrete as? IrType.Nullable)?.inner ?: concrete
            val ownedNamed = inner is IrType.Named && (inner.name in structs || inner.name in specTables)
            val ownedArray = inner is IrType.Array
            val ownedCallable = inner is IrType.Function
            val ownedPointer = inner is IrType.Pointer && dtor !in functionParams && !type.name.contains("Weak")
            if (!ownedNamed && !ownedPointer && !ownedArray && !ownedCallable) continue
            if (ownedPointer && (inner as IrType.Pointer).inner is IrType.Named) error("owned pack pointee destruction is not supported by the WebAssembly target")
            val offset = layoutOf(type.name).fields.getValue(field.name).offset
            val address = "(i32.add (local.get \$self) (i32.const $offset))"
            val read = "(${wasmLoad(field.type)} $address)"
            val value = coerceWasm(read, field.type, concrete)
            body.append("      (local.set \$value $value)\n")
            body.append("      (${wasmStore(field.type)} $address (${wasmType(field.type)}.const 0))\n")
            val drop = if (ownedNamed || ownedArray || ownedCallable) ownershipDropName(inner) else "__free"
            body.append("      (call \$$drop (local.get \$value))\n")
        }
        body.append("      (call \$__free (local.get \$self))))\n  )\n")
        return body.toString()
    }

    private fun emitStructCtor(expr: IrExpr.StructCtor): String {
        usesAlloc = true
        val t = newTemp("i32")
        val sb = StringBuilder("(block (result i32)\n")
        val pad = "  ".repeat(indent + 1)
        val layout = layoutOf(expr.name)
        val fields = structs.getValue(expr.name)
        sb.append("$pad(local.set $t (call \$__alloc (i32.const ${layout.size})))\n")
        for ((i, a) in expr.args.withIndex()) {
            // A union's single named member initializes its one shared slot.
            if (expr.name in unions && i > 0) break
            val name = expr.fieldNames.getOrNull(i) ?: fields[i].name
            val slot = layout.fields[name] ?: error("pack ${expr.name} has no field '$name'")
            sb.append("$pad(${wasmStore(slot.type)} (i32.add (local.get $t) (i32.const ${slot.offset})) ${emitAs(a, slot.type)})\n")
        }
        sb.append("$pad(local.get $t))")
        return sb.toString()
    }

    /**
     * A tuple is a heap aggregate, as a pack is: each component at an offset
     * aligned to its own width, so an erased `(K, V)` holds eight-byte slots.
     */
    private fun tupleOffsets(types: List<IrType>): Pair<List<Int>, Int> {
        var end = 0
        val offsets = types.map { type ->
            val at = alignTo(end, wasmSize(type))
            end = at + wasmSize(type)
            at
        }
        return offsets to end
    }

    private fun emitTupleLit(expr: IrExpr.TupleLit): String {
        usesAlloc = true
        val types = (expr.type as? IrType.Tuple)?.elements ?: expr.elements.map { it.type }
        val (offsets, size) = tupleOffsets(types)
        val t = newTemp("i32")
        val sb = StringBuilder("(block (result i32) (local.set $t (call \$__alloc (i32.const $size)))")
        for ((i, element) in expr.elements.withIndex()) {
            sb.append(" (${wasmStore(types[i])} (i32.add (local.get $t) (i32.const ${offsets[i]})) ${emitAs(element, types[i])})")
        }
        return sb.append(" (local.get $t))").toString()
    }

    private fun emitArrayLiteral(expr: IrExpr.ArrayLiteral): String {
        if (expr.elements.any { it is IrExpr.Spread }) return emitSpreadArrayLiteral(expr)
        usesAlloc = true
        val t = newTemp("i32")
        val n = expr.elements.size
        val element = (expr.type as IrType.Array).element
        val stride = wasmSize(element)
        val sb = StringBuilder("(block (result i32)\n")
        val pad = "  ".repeat(indent + 1)
        sb.append("$pad(local.set $t (call \$__alloc (i32.const ${4 + n * stride})))\n")
        sb.append("$pad(i32.store (local.get $t) (i32.const $n))\n")
        for ((i, e) in expr.elements.withIndex()) {
            sb.append("$pad(${wasmStore(element)} (i32.add (local.get $t) (i32.const ${4 + i * stride})) ${emitAs(e, element)})\n")
        }
        sb.append("$pad(local.get $t))")
        return sb.toString()
    }

    /** Copy each spread before later arguments can mutate its source. */
    private fun emitSpreadArrayLiteral(expr: IrExpr.ArrayLiteral): String {
        usesAlloc = true
        val element = (expr.type as IrType.Array).element
        val stride = wasmSize(element)
        val raw = newTemp("i32")
        val sb = StringBuilder("(block (result i32)\n")
        sb.append("(local.set $raw (call \$__alloc (i32.const 4)))\n")
        sb.append("(i32.store (local.get $raw) (i32.const 0))\n")
        for (part in expr.elements) {
            val spread = part as? IrExpr.Spread
            val sourceType = spread?.let { (it.array.type as IrType.Array).element }
            val value = newTemp(if (spread != null) "i32" else wasmType(part.type))
            sb.append("(local.set $value ${emitExpr(spread?.array ?: part)})\n")
            val oldLength = newTemp("i32")
            val count = newTemp("i32")
            val length = newTemp("i32")
            val grown = newTemp("i32")
            sb.append("(local.set $oldLength (i32.load (local.get $raw)))\n")
            val countExpr = if (spread == null) "(i32.const 1)" else "(i32.load (local.get $value))"
            sb.append("(local.set $count $countExpr)\n")
            sb.append("(local.set $length (i32.add (local.get $oldLength) (local.get $count)))\n")
            sb.append("(local.set $grown (call \$__alloc (i32.add (i32.const 4) (i32.mul (local.get $length) (i32.const $stride)))))\n")
            sb.append("(i32.store (local.get $grown) (local.get $length))\n")
            sb.append("(memory.copy (i32.add (local.get $grown) (i32.const 4)) " +
                "(i32.add (local.get $raw) (i32.const 4)) (i32.mul (local.get $oldLength) (i32.const $stride)))\n")
            fun address(base: String, index: String, width: Int) =
                "(i32.add (local.get $base) (i32.add (i32.const 4) (i32.mul $index (i32.const $width))))"
            if (spread == null) {
                val stored = coerceWasm("(local.get $value)", part.type, element)
                sb.append("(${wasmStore(element)} ${address(grown, "(local.get $oldLength)", stride)} $stored)\n")
            } else {
                val index = newTemp("i32")
                val done = "${index}_done"
                val loop = "${index}_copy"
                sb.append("(local.set $index (i32.const 0))\n")
                sb.append("(block $done (loop $loop\n")
                sb.append("(br_if $done (i32.ge_u (local.get $index) (local.get $count)))\n")
                val loaded = "(${wasmLoad(sourceType!!)} ${address(value, "(local.get $index)", wasmSize(sourceType))})"
                val stored = coerceWasm(loaded, sourceType, element)
                val offset = "(i32.add (local.get $oldLength) (local.get $index))"
                sb.append("(${wasmStore(element)} ${address(grown, offset, stride)} $stored)\n")
                sb.append("(local.set $index (i32.add (local.get $index) (i32.const 1)))\n")
                sb.append("(br $loop)))\n")
            }
            sb.append("(call \$__free (local.get $raw))\n")
            sb.append("(local.set $raw (local.get $grown))\n")
        }
        return sb.append("(local.get $raw))").toString()
    }

    private fun closureTypeName(type: IrType.Function): String =
        closureTypes.getOrPut(type) { "__closure_type_${closureTypes.size}" }

    private fun emitClosure(lambda: IrExpr.Lambda): String {
        val callable = lambda.type as? IrType.Function
            ?: error("lambda has non-callable IR type ${lambda.type}")
        val captures = collectCaptures(lambda)
        val index = closureFunctions.size
        closureFunctions += ClosureFunction(index, lambda, captures, closureTypeName(callable))
        usesAlloc = true

        val closure = newTemp("i32")
        val environment = newTemp("i32")
        val environmentSize = captures.lastOrNull()?.let { it.offset + if (it.byRef) 4 else wasmSize(it.type) } ?: 8
        val sb = StringBuilder("(block (result i32)\n")
        val pad = "  ".repeat(indent + 1)
        if (environmentSize == 0) {
            sb.append("$pad(local.set $environment (i32.const 0))\n")
        } else {
            sb.append("$pad(local.set $environment (call \$__alloc (i32.const $environmentSize)))\n")
            sb.append("$pad(i32.store (local.get $environment) (i32.const 1))\n")
            sb.append("$pad(i32.store offset=4 (local.get $environment) (i32.const $index))\n")
            for (capture in captures) {
                val address = wasmAddress(environment, capture.offset)
                if (capture.byRef) {
                    val existingBox = boxedLocals[capture.name]
                    val box = existingBox ?: newTemp("i32").removePrefix("\$")
                    if (existingBox == null) {
                        val currentValue = "(local.get \$${capture.name})"
                        sb.append("$pad(local.set \$$box (call \$__alloc (i32.const ${wasmSize(capture.type)})))\n")
                        sb.append("$pad(${wasmStore(capture.type)} (local.get \$$box) $currentValue)\n")
                        boxedLocals[capture.name] = box
                    }
                    sb.append("$pad(i32.store $address (local.get \$$box))\n")
                } else {
                    val boxed = boxedLocals[capture.name]
                    val value = lambda.captureInitializers[capture.name]?.let { emitAs(it, capture.type) } ?: if (boxed != null) {
                        "(${wasmLoad(capture.type)} (local.get \$$boxed))"
                    } else {
                        "(local.get \$${capture.name})"
                    }
                    sb.append("$pad(${wasmStore(capture.type)} $address $value)\n")
                }
            }
        }
        sb.append("$pad(local.set $closure (call \$__alloc (i32.const 8)))\n")
        sb.append("$pad(i32.store (local.get $closure) (i32.const $index))\n")
        sb.append("$pad(i32.store (i32.add (local.get $closure) (i32.const 4)) (local.get $environment))\n")
        sb.append("$pad(local.get $closure))")
        return sb.toString()
    }

    private fun emitClosureFunction(closure: ClosureFunction): String {
        locals.clear(); localIrTypes.clear(); boxedLocals.clear(); tempCounter = 0; blockCounter = 0
        loopStack.clear(); labelTargets.clear()
        params = closure.lambda.params.map { it.first }.toSet() + "__env"
        localIrTypes.putAll(closure.lambda.params)
        localIrTypes["__env"] = CLOSURE_ENVIRONMENT
        currentReturnType = (closure.lambda.type as IrType.Function).ret
        // A lambda body is a function of its own: a handler around the place it
        // was written does not enclose the place it runs.
        currentIsFailable = false
        errorHandlers.clear()
        prepareDefers(closure.lambda.body)

        out.clear(); indent = 2
        for (capture in closure.captures) {
            if (capture.byRef) {
                val box = "__capture_ref_${capture.name}"
                declareLocal(box, IrType.Int)
                localIrTypes[capture.name] = capture.type
                boxedLocals[capture.name] = box
                line("(local.set \$$box (i32.load ${wasmAddress("\$__env", capture.offset)}))")
            } else {
                val box = "__capture_value_${capture.name}"
                declareLocal(box, IrType.Int)
                localIrTypes[capture.name] = capture.type
                boxedLocals[capture.name] = box
                line("(local.set \$$box ${wasmAddress("\$__env", capture.offset)})")
            }
        }
        for (stmt in closure.lambda.body) emitStmt(stmt)
        if (!endsWithTerminator(closure.lambda.body)) deferredCode(failing = false).takeIf { it.isNotEmpty() }?.let(::line)
        val body = out.toString()

        val sig = StringBuilder("  (func \$__closure_${closure.index}")
        for ((name, type) in closure.lambda.params) sig.append(" (param \$$name ${wasmType(type)})")
        sig.append(" (param \$__env i32)")
        val returnType = (closure.lambda.type as IrType.Function).ret
        if (hasFunctionResult(returnType)) sig.append(" (result ${wasmType(returnType)})")
        sig.append("\n")
        for ((name, type) in locals) if (name !in params) sig.append("    (local \$$name $type)\n")
        sig.append(body)
        sig.append("  )\n")
        return sig.toString()
    }

    private fun collectCaptures(lambda: IrExpr.Lambda): List<ClosureCapture> {
        val declared = linkedSetOf<String>()
        val references = linkedMapOf<String, IrType>()
        lambda.params.forEach { declared.add(it.first) }
        collectDeclaredNames(lambda.body, declared)
        collectReferencedVars(lambda.body, references)

        var offset = 8
        return references
            .filterKeys { it !in declared && it in localIrTypes }
            .map { (name, type) ->
                val byRef = lambda.allCapturesByRef || name in lambda.byRefCaptures
                val storedType = localIrTypes.getValue(name)
                val alignment = if (byRef) 4 else wasmAlignment(type)
                offset = alignTo(offset, alignment)
                ClosureCapture(name, storedType, offset, byRef).also {
                    offset += if (it.byRef) 4 else wasmSize(it.type)
                }
            }
    }

    private fun emitTemplate(expr: IrExpr.StringTemplate): String {
        val pieces = expr.parts.map { part ->
            when (part) {
                is IrExpr.IrTemplatePart.Literal -> "(i32.const ${internString(part.text)})"
                is IrExpr.IrTemplatePart.Expr -> stringify(part.expr)
            }
        }
        if (pieces.isEmpty()) return "(i32.const ${internString("")})"
        usesAlloc = true; usesConcat = true
        return pieces.reduce { a, b -> "(call \$__str_concat $a $b)" }
    }

    /** Converts [expr] to a string pointer for interpolation. */
    private fun stringify(expr: IrExpr): String = when {
        expr.type == IrType.String -> emitExpr(expr)
        expr.type == IrType.Bool -> "(if (result i32) ${emitExpr(expr)} (then (i32.const ${internString("true")})) (else (i32.const ${internString("false")})))"
        wasmType(expr.type) == "i32" -> { usesAlloc = true; usesIntToStr = true; "(call \$__int_to_str ${emitExpr(expr)})" }
        wasmType(expr.type) == "i64" -> { usesAlloc = true; usesLongToStr = true; "(call \$__long_to_str ${emitExpr(expr)})" }
        wasmType(expr.type) == "f64" -> { usesAlloc = true; usesDoubleToStr = true; "(call \$__double_to_str ${emitExpr(expr)})" }
        wasmType(expr.type) == "f32" -> { usesAlloc = true; usesDoubleToStr = true; "(call \$__double_to_str (f64.promote_f32 ${emitExpr(expr)}))" }
        // Anything else has no WAT rendering yet. Failing here keeps a missing
        // conversion visible, rather than interpolating an empty string and
        // turning it into wrong output the other backends do not produce.
        else -> error(
            "wasm: cannot interpolate a value of type ${expr.type} - " +
                "only Int, String and Bool have a WAT string conversion so far",
        )
    }

    private fun emitNumCast(expr: IrExpr.NumCast): String {
        // Casting an erased value names the type it already holds.
        if (expr.value.type == IrType.Any || expr.type == IrType.Any) return emitAs(expr.value, expr.type)
        val from = numPrefix(expr.value.type)
        val to = numPrefix(expr.type)
        val v = emitExpr(expr.value)
        val u = isUnsigned(expr.value.type)
        val s = if (u) "u" else "s"
        if (from == to) return v
        val conv = when {
            from == "i32" && to == "i64" -> "i64.extend_i32_$s"
            from == "i64" && to == "i32" -> "i32.wrap_i64"
            from == "i32" && to == "f64" -> "f64.convert_i32_$s"
            from == "i32" && to == "f32" -> "f32.convert_i32_$s"
            from == "i64" && to == "f64" -> "f64.convert_i64_$s"
            from == "i64" && to == "f32" -> "f32.convert_i64_$s"
            from == "f64" && to == "i32" -> "i32.trunc_f64_$s"
            from == "f64" && to == "i64" -> "i64.trunc_f64_$s"
            from == "f32" && to == "i32" -> "i32.trunc_f32_$s"
            from == "f32" && to == "f64" -> "f64.promote_f32"
            from == "f64" && to == "f32" -> "f32.demote_f64"
            else -> return v
        }
        return "($conv $v)"
    }

    // ── Address helpers ───────────────────────────────────────────────────

    /** Address of `array[index]` or raw `pointer[index]`. */
    private class ExchangeLocation(val type: IrType, val read: String, val write: (String) -> String)

    private fun emitExchange(stmt: IrStmt.Exchange) {
        fun location(place: IrExpr): ExchangeLocation = when (place) {
            is IrExpr.Var -> {
                check(place.name !in lazyLocals && place.name !in reactiveAliases) { "exchange of reactive/lazy storage is not supported" }
                ExchangeLocation(place.type, emitExpr(place)) { storeVariable(place.name, place.type, it) }
            }
            is IrExpr.Member, is IrExpr.Index -> {
                // The stored type, which is erased for a generic field or element.
                val stored = if (place is IrExpr.Member) fieldSlot(place.target, place.name).type
                    else elementType((place as IrExpr.Index).target)
                val address = newTemp("i32")
                if (place is IrExpr.Member) {
                    line("(local.set $address ${fieldAddr(place.target, place.name)})")
                } else {
                    place as IrExpr.Index
                    check(place.target.type is IrType.Array) { "exchange requires built-in array storage" }
                    val base = newTemp("i32")
                    val index = newTemp("i32")
                    line("(local.set $base ${emitExpr(place.target)})")
                    check(place.index.type == IrType.Int) { "WASM exchange index must be Int" }
                    line("(local.set $index ${emitExpr(place.index)})")
                    line("(if (i32.ge_u (local.get $index) (i32.load (local.get $base))) (then unreachable))")
                    line("(local.set $address (i32.add (i32.add (local.get $base) (i32.const 4)) (i32.mul (local.get $index) (i32.const ${wasmSize(stored)}))))")
                }
                ExchangeLocation(stored, "(${wasmLoad(stored)} (local.get $address))") {
                    "(${wasmStore(stored)} (local.get $address) $it)"
                }
            }
            else -> error("unsupported exchange location")
        }
        val left = location(stmt.left)
        val right = location(stmt.right)
        val a = newTemp(wasmType(left.type))
        val b = newTemp(wasmType(right.type))
        line("(local.set $a ${left.read})")
        line("(local.set $b ${right.read})")
        line(left.write(coerceWasm("(local.get $b)", right.type, left.type)))
        line(right.write(coerceWasm("(local.get $a)", left.type, right.type)))
    }

    private fun elemAddr(target: IrExpr, index: IrExpr): String {
        val size = wasmSize(elementType(target))
        // A raw pointer is unsafe and carries no bounds to check.
        if (target.type is IrType.Pointer) {
            return "(i32.add ${emitExpr(target)} (i32.mul ${emitExpr(index)} (i32.const $size)))"
        }
        // A safe index names one of the array's own elements or traps, as on
        // every other target. The check is one expression, so the array and the
        // index are still evaluated where the source has them, and once; a
        // negative index fails the same unsigned comparison as one past the end.
        val base = newTemp("i32")
        val at = newTemp("i32")
        return "(block (result i32) " +
            "(local.set $base ${emitExpr(target)}) " +
            "(local.set $at ${emitExpr(index)}) " +
            "(if (i32.ge_u (local.get $at) (i32.load (local.get $base))) (then unreachable)) " +
            "(i32.add (i32.add (local.get $base) (i32.const 4)) (i32.mul (local.get $at) (i32.const $size))))"
    }

    /** The stored element type of an array or raw buffer - erased for a generic one. */
    private fun elementType(target: IrExpr): IrType = when (val type = target.type) {
        is IrType.Array -> type.element
        is IrType.Pointer -> type.inner
        else -> error("indexed storage must have an element type")
    }

    /** Address of `target.field`: the pack pointer plus the field's offset. */
    private fun fieldAddr(target: IrExpr, field: String): String =
        "(i32.add ${emitExpr(target)} (i32.const ${fieldSlot(target, field).offset}))"

    private class FieldSlot(val offset: Int, val type: IrType)
    private class PackLayout(val fields: Map<String, FieldSlot>, val size: Int)

    private fun isPackTarget(target: IrExpr): Boolean =
        (target.type as? IrType.Named)?.name in structs

    private fun fieldSlot(target: IrExpr, field: String): FieldSlot {
        val pack = (target.type as? IrType.Named)?.name
            ?: error("field '$field' of ${target.type} has no WebAssembly pack layout")
        return layoutOf(pack).fields[field] ?: error("pack $pack has no field '$field'")
    }

    /**
     * Each field sits at the next offset aligned to its own width, so erased
     * and 64-bit fields take eight bytes and the rest four. A union's members
     * share offset 0 and its size is its widest member.
     */
    private fun layoutOf(pack: String): PackLayout = layouts.getOrPut(pack) {
        val fields = structs[pack] ?: error("no WebAssembly layout for pack '$pack'")
        val slots = LinkedHashMap<String, FieldSlot>()
        var end = 0
        var alignment = 4
        for (field in fields) {
            val width = wasmSize(field.type)
            val offset = if (pack in unions) 0 else alignTo(end, width)
            slots[field.name] = FieldSlot(offset, field.type)
            end = maxOf(end, offset + width)
            alignment = maxOf(alignment, width)
        }
        PackLayout(slots, alignTo(end, alignment))
    }

    private fun wasmSize(type: IrType): Int = when (wasmType(type)) {
        "i64", "f64" -> 8
        else -> 4
    }

    private fun wasmAlignment(type: IrType): Int = wasmSize(type)

    private fun alignTo(value: Int, alignment: Int): Int =
        (value + alignment - 1) and (alignment - 1).inv()

    private fun wasmLoad(type: IrType): String = "${wasmType(type)}.load"
    private fun wasmStore(type: IrType): String = "${wasmType(type)}.store"

    private fun wasmAddress(base: String, offset: Int): String =
        if (offset == 0) "(local.get $base)"
        else "(i32.add (local.get $base) (i32.const $offset))"

    // ── Locals / temps ────────────────────────────────────────────────────

    private fun declareLocal(name: String, type: IrType) {
        localIrTypes[name] = type
        if (name !in params) locals[name] = wasmType(type)
    }
    private fun newTemp(type: String): String { val n = "\$__t${tempCounter++}"; locals[n.substring(1)] = type; return n }

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
                else -> Unit
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
                is IrStmt.Break, is IrStmt.Continue -> Unit
            }
        }
    }

    private fun collectReferencedVars(expr: IrExpr, refs: MutableMap<String, IrType>) {
        when (expr) {
            is IrExpr.Var -> if (expr.name !in refs) refs[expr.name] = expr.type
            is IrExpr.Unary -> collectReferencedVars(expr.operand, refs)
            is IrExpr.IncDec -> collectReferencedVars(expr.target, refs)
            is IrExpr.Binary -> {
                collectReferencedVars(expr.left, refs)
                collectReferencedVars(expr.right, refs)
            }
            is IrExpr.Call -> {
                expr.receiver?.let { collectReferencedVars(it, refs) }
                expr.args.forEach { collectReferencedVars(it, refs) }
            }
            is IrExpr.ArrayLiteral -> expr.elements.forEach { collectReferencedVars(it, refs) }
            is IrExpr.MapLit -> expr.entries.forEach { (key, value) ->
                collectReferencedVars(key, refs)
                collectReferencedVars(value, refs)
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
            is IrExpr.Lambda -> Unit
            is IrExpr.Await -> collectReferencedVars(expr.value, refs)
            is IrExpr.Spread -> collectReferencedVars(expr.array, refs)
            is IrExpr.IntLiteral,
            is IrExpr.DoubleLiteral,
            is IrExpr.StringLiteral,
            is IrExpr.EnumLiteral,
            is IrExpr.BoolLiteral,
            is IrExpr.CharLiteral,
            IrExpr.UnitLiteral,
            is IrExpr.SlotPattern -> Unit
        }
    }

    private fun breakTarget(label: String?): String =
        if (label != null) labelTargets[label]!!.first else loopStack.last().first
    private fun continueTarget(label: String?): String =
        if (label != null) labelTargets[label]!!.second else loopStack.last().second

    // ── Strings / data ────────────────────────────────────────────────────

    private fun internString(s: String): Int = stringConsts.getOrPut(s) {
        val offset = align4(constCursor)
        constCursor = offset + 4 + s.encodeToByteArray().size
        offset
    }

    /** Encodes a length-prefixed string as a WAT data string (`\HH` escapes). */
    private fun dataBytes(s: String): String {
        val bytes = s.encodeToByteArray()
        val sb = StringBuilder()
        val len = bytes.size
        for (i in 0 until 4) sb.append("\\").append(((len ushr (i * 8)) and 0xFF).toString(16).padStart(2, '0'))
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v in 0x20..0x7E && v != '"'.code && v != '\\'.code) sb.append(v.toChar())
            else sb.append("\\").append(v.toString(16).padStart(2, '0'))
        }
        return sb.toString()
    }

    private fun align4(n: Int): Int = (n + 3) and 3.inv()

    // ── Types ─────────────────────────────────────────────────────────────

    /**
     * The WebAssembly float instruction a `bridge func` stands for, or null when
     * it has no native equivalent.
     *
     * Only the operations Wasm implements directly are listed; the
     * transcendentals have no opcode and still come from the host.
     */
    /**
     * The software implementation a `bridge func` maps to, for the functions
     * WebAssembly has no opcode for.
     *
     * Only the ones actually implemented in [RT_TRIG] are listed; everything else
     * still comes from the host until its approximation is written.
     */
    private fun wasmSoftwareMathFor(extern: IrTopLevel.Extern): String? {
        // `scope vha` mangles to `__vha_sin`; the extra accuracy is what the
        // scope exists for, so it maps to the longer series where one is implemented.
        // The runtime's own routines are named `rt.…`: a `.` cannot appear in an
        // Azora name, so no canonical symbol - `__vha_sin` included - can be
        // defined twice by sharing a name with the routine that implements it.
        val vha = "_vha_" in extern.name
        val name = when (extern.name.substringAfterLast('_')) {
            "sin" -> if (vha) "rt.vha_sin" else "rt.soft_sin"
            "cos" -> if (vha) "rt.vha_cos" else "rt.soft_cos"
            "tan" -> if (vha) "rt.vha_tan" else "rt.soft_tan"
            "log" -> "rt.soft_log"
            "log2" -> "rt.soft_log2"
            "log10" -> "rt.soft_log10"
            "exp" -> "rt.soft_exp"
            "exp2" -> "rt.soft_exp2"
            "sinh" -> "rt.soft_sinh"
            "cosh" -> "rt.soft_cosh"
            "tanh" -> "rt.soft_tanh"
            "cbrt" -> "rt.soft_cbrt"
            "asin" -> "rt.soft_asin"
            "acos" -> "rt.soft_acos"
            "atan" -> "rt.soft_atan"
            "atan2" -> "rt.soft_atan2"
            "powr" -> "rt.soft_pow"
            "hypot" -> "rt.soft_hypot"
            else -> return null
        }
        val expectedArity = if (name in setOf("rt.soft_atan2", "rt.soft_hypot", "rt.soft_pow")) 2 else 1
        if (extern.params.size != expectedArity) return null
        if (extern.returnType != IrType.Double) return null
        if (extern.params.any { it.second != IrType.Double }) return null
        return name
    }

    private fun wasmFloatOpFor(extern: IrTopLevel.Extern): String? {
        val op = when (extern.name.substringAfterLast('_')) {
            "sqrt" -> "f64.sqrt"
            "fabs", "abs" -> "f64.abs"
            "floor" -> "f64.floor"
            "ceil" -> "f64.ceil"
            "trunc" -> "f64.trunc"
            "round" -> "f64.nearest"
            "fmin" -> "f64.min"
            "fmax" -> "f64.max"
            else -> return null
        }
        val arity = if (op == "f64.min" || op == "f64.max") 2 else 1
        if (extern.params.size != arity) return null
        if (extern.returnType != IrType.Double) return null
        if (extern.params.any { it.second != IrType.Double }) return null
        return op
    }

    /** Unit is ABI-erased on return; Nothing has no return edge at all. */
    private fun hasFunctionResult(type: IrType): Boolean =
        type != IrType.Unit && type != IrType.Nothing

    private fun wasmType(type: IrType): String = when (type) {
        IrType.Long, IrType.ULong, IrType.Cent, IrType.UCent, IrType.ISize, IrType.USize -> "i64"
        // An erased generic value is eight bytes, like LLVM's pointer-sized slot,
        // so any value's bits fit; see [coerceWasm] for crossing into and out of it.
        IrType.Any -> "i64"
        IrType.Double, IrType.Quad -> "f64"
        IrType.Float -> "f32"
        is IrType.Task -> wasmType(type.result)
        // A nullable value is held as its type is, null being all zero bits:
        // a pointer's null is address 0, and an erased `V?` stays eight bytes
        // wide rather than losing the top half of a Long or a Double.
        is IrType.Nullable -> wasmType(type.inner)
        else -> "i32"
    }

    /** Numeric instruction prefix for arithmetic on values of [type]. */
    private fun numPrefix(type: IrType): String = wasmType(type)

    private fun isUnsigned(type: IrType): Boolean =
        type == IrType.UInt || type == IrType.UByte || type == IrType.UShort ||
            type == IrType.ULong || type == IrType.UCent || type == IrType.USize || type == IrType.Char

    private fun line(text: String) {
        repeat(indent) { out.append("  ") }
        out.append(text).append("\n")
    }

    // ── Linear-memory runtime (folded WAT) ────────────────────────────────

    private val RT_CONCAT = """
  (func ${'$'}__str_concat (param ${'$'}a i32) (param ${'$'}b i32) (result i32)
    (local ${'$'}la i32) (local ${'$'}lb i32) (local ${'$'}p i32)
    (local.set ${'$'}la (i32.load (local.get ${'$'}a)))
    (local.set ${'$'}lb (i32.load (local.get ${'$'}b)))
    (local.set ${'$'}p (call ${'$'}__alloc (i32.add (i32.const 4) (i32.add (local.get ${'$'}la) (local.get ${'$'}lb)))))
    (i32.store (local.get ${'$'}p) (i32.add (local.get ${'$'}la) (local.get ${'$'}lb)))
    (memory.copy (i32.add (local.get ${'$'}p) (i32.const 4)) (i32.add (local.get ${'$'}a) (i32.const 4)) (local.get ${'$'}la))
    (memory.copy (i32.add (local.get ${'$'}p) (i32.add (i32.const 4) (local.get ${'$'}la))) (i32.add (local.get ${'$'}b) (i32.const 4)) (local.get ${'$'}lb))
    (local.get ${'$'}p))
"""

    /**
     * `Hash`'s member on a value that carries no user-written one, as an i64:
     * an integer, char or bool is its value, a float its bit pattern, a string
     * its content, and an erased slot its eight bytes. LLVM and the interpreter
     * give the same numbers.
     */
    private fun emitBuiltinHash(target: IrExpr): String {
        val type = target.type
        val value = emitExpr(target)
        return when {
            type == IrType.String -> { usesStrHash = true; "(call \$__str_hash $value)" }
            type == IrType.Any -> value
            type == IrType.Double || type == IrType.Quad -> "(i64.reinterpret_f64 $value)"
            type == IrType.Float -> "(i64.extend_i32_u (i32.reinterpret_f32 $value))"
            wasmType(type) == "i64" -> value
            IrType.isInteger(type) && !isUnsigned(type) -> "(i64.extend_i32_s $value)"
            else -> "(i64.extend_i32_u $value)"
        }
    }

    /** 64-bit FNV-1a over a string's bytes; the interpreter and LLVM use the same. */
    private val RT_STR_HASH = """
  (func ${'$'}__str_hash (param ${'$'}s i32) (result i64)
    (local ${'$'}h i64) (local ${'$'}i i32) (local ${'$'}n i32)
    (local.set ${'$'}h (i64.const -3750763034362895579))
    (local.set ${'$'}n (i32.load (local.get ${'$'}s)))
    (local.set ${'$'}i (i32.const 0))
    (block ${'$'}d (loop ${'$'}l
      (br_if ${'$'}d (i32.ge_u (local.get ${'$'}i) (local.get ${'$'}n)))
      (local.set ${'$'}h (i64.mul
        (i64.xor (local.get ${'$'}h) (i64.extend_i32_u (i32.load8_u (i32.add (i32.add (local.get ${'$'}s) (i32.const 4)) (local.get ${'$'}i)))))
        (i64.const 1099511628211)))
      (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
      (br ${'$'}l)))
    (local.get ${'$'}h))
"""

    private val RT_STR_EQ = """
  (func ${'$'}__str_eq (param ${'$'}a i32) (param ${'$'}b i32) (result i32)
    (local ${'$'}la i32) (local ${'$'}i i32)
    (local.set ${'$'}la (i32.load (local.get ${'$'}a)))
    (if (i32.ne (local.get ${'$'}la) (i32.load (local.get ${'$'}b))) (then (return (i32.const 0))))
    (local.set ${'$'}i (i32.const 0))
    (block ${'$'}c (loop ${'$'}l
      (br_if ${'$'}c (i32.ge_s (local.get ${'$'}i) (local.get ${'$'}la)))
      (if (i32.ne (i32.load8_u (i32.add (i32.add (local.get ${'$'}a) (i32.const 4)) (local.get ${'$'}i)))
                  (i32.load8_u (i32.add (i32.add (local.get ${'$'}b) (i32.const 4)) (local.get ${'$'}i))))
        (then (return (i32.const 0))))
      (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
      (br ${'$'}l)))
    (i32.const 1))
"""

    /**
     * Native Wasm definitions for referenced string bridge intrinsics. A Wasm
     * string is `[ i32 len, bytes… ]`; chars are i32. Simple ops are exact; the
     * unsupported text transforms fail code generation with a target diagnostic.
     */
    private fun wasmStringIntrinsics(): String {
        val unsupported = neededIntrinsics.intersect(setOf("substring", "startsWith", "endsWith", "contains", "indexOf", "toUpper", "toLower", "trim", "replace", "split", "toChars", "fromChars"))
        check(unsupported.isEmpty()) {
            "WebAssembly text operation(s) ${unsupported.joinToString()} are not implemented; use the interpreter until WASM text support is available"
        }
        val sb = StringBuilder()
        fun def(name: String, sig: String, body: String) {
            if (name in neededIntrinsics) sb.append("  (func \$$name $sig\n    $body)\n")
        }
        def("stringLength", "(param \$s i32) (result i32)", "(i32.load (local.get \$s))")
        def("charAt", "(param \$s i32) (param \$i i32) (result i32)",
            "(i32.load8_u (i32.add (i32.add (local.get \$s) (i32.const 4)) (local.get \$i)))")
        def("ord", "(param \$c i32) (result i32)", "(local.get \$c)")
        def("chr", "(param \$i i32) (result i32)", "(local.get \$i)")
        def("isDigit", "(param \$c i32) (result i32)",
            "(i32.and (i32.ge_s (local.get \$c) (i32.const 48)) (i32.le_s (local.get \$c) (i32.const 57)))")
        def("isAlpha", "(param \$c i32) (result i32)",
            "(i32.or (i32.and (i32.ge_s (local.get \$c) (i32.const 65)) (i32.le_s (local.get \$c) (i32.const 90)))" +
                " (i32.and (i32.ge_s (local.get \$c) (i32.const 97)) (i32.le_s (local.get \$c) (i32.const 122))))")
        return sb.toString()
    }

    private val RT_IS_CHECK = """
  (func ${'$'}__isCheck (param ${'$'}slot i32) (param ${'$'}tag i32) (result i32)
    (if (result i32) (i32.eqz (local.get ${'$'}slot))
      (then (i32.const 0))
      (else (call ${'$'}__str_eq (i32.load (local.get ${'$'}slot)) (local.get ${'$'}tag)))))
"""

    private val RT_REPEAT = """
  (func ${'$'}__str_repeat (param ${'$'}s i32) (param ${'$'}n i32) (result i32)
    (local ${'$'}ls i32) (local ${'$'}p i32) (local ${'$'}i i32)
    (local.set ${'$'}ls (i32.load (local.get ${'$'}s)))
    (local.set ${'$'}p (call ${'$'}__alloc (i32.add (i32.const 4) (i32.mul (local.get ${'$'}ls) (local.get ${'$'}n)))))
    (i32.store (local.get ${'$'}p) (i32.mul (local.get ${'$'}ls) (local.get ${'$'}n)))
    (local.set ${'$'}i (i32.const 0))
    (block ${'$'}c (loop ${'$'}l
      (br_if ${'$'}c (i32.ge_s (local.get ${'$'}i) (local.get ${'$'}n)))
      (memory.copy (i32.add (i32.add (local.get ${'$'}p) (i32.const 4)) (i32.mul (local.get ${'$'}i) (local.get ${'$'}ls)))
                   (i32.add (local.get ${'$'}s) (i32.const 4)) (local.get ${'$'}ls))
      (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
      (br ${'$'}l)))
    (local.get ${'$'}p))
"""

    private val RT_INT_TO_STR = """
  (func ${'$'}__int_to_str (param ${'$'}n i32) (result i32)
    (local ${'$'}neg i32) (local ${'$'}len i32) (local ${'$'}x i32) (local ${'$'}p i32) (local ${'$'}i i32)
    (if (i32.eqz (local.get ${'$'}n))
      (then
        (local.set ${'$'}p (call ${'$'}__alloc (i32.const 5)))
        (i32.store (local.get ${'$'}p) (i32.const 1))
        (i32.store8 (i32.add (local.get ${'$'}p) (i32.const 4)) (i32.const 48))
        (return (local.get ${'$'}p))))
    (local.set ${'$'}neg (i32.lt_s (local.get ${'$'}n) (i32.const 0)))
    (local.set ${'$'}x (if (result i32) (local.get ${'$'}neg) (then (i32.sub (i32.const 0) (local.get ${'$'}n))) (else (local.get ${'$'}n))))
    (local.set ${'$'}len (i32.const 0))
    (local.set ${'$'}i (local.get ${'$'}x))
    (block ${'$'}c (loop ${'$'}l
      (br_if ${'$'}c (i32.eqz (local.get ${'$'}i)))
      (local.set ${'$'}len (i32.add (local.get ${'$'}len) (i32.const 1)))
      (local.set ${'$'}i (i32.div_u (local.get ${'$'}i) (i32.const 10)))
      (br ${'$'}l)))
    (local.set ${'$'}len (i32.add (local.get ${'$'}len) (local.get ${'$'}neg)))
    (local.set ${'$'}p (call ${'$'}__alloc (i32.add (i32.const 4) (local.get ${'$'}len))))
    (i32.store (local.get ${'$'}p) (local.get ${'$'}len))
    (local.set ${'$'}i (i32.sub (local.get ${'$'}len) (i32.const 1)))
    (block ${'$'}c2 (loop ${'$'}l2
      (br_if ${'$'}c2 (i32.eqz (local.get ${'$'}x)))
      (i32.store8 (i32.add (i32.add (local.get ${'$'}p) (i32.const 4)) (local.get ${'$'}i))
                  (i32.add (i32.rem_u (local.get ${'$'}x) (i32.const 10)) (i32.const 48)))
      (local.set ${'$'}x (i32.div_u (local.get ${'$'}x) (i32.const 10)))
      (local.set ${'$'}i (i32.sub (local.get ${'$'}i) (i32.const 1)))
      (br ${'$'}l2)))
    (if (local.get ${'$'}neg) (then (i32.store8 (i32.add (local.get ${'$'}p) (i32.const 4)) (i32.const 45))))
    (local.get ${'$'}p))
"""

    /**
     * `__long_to_str` - the same decimal conversion as [RT_INT_TO_STR] in 64-bit
     * arithmetic, so a `Long` interpolates to its full value rather than being
     * truncated through i32.
     */
    private val RT_LONG_TO_STR = """
  (func ${'$'}__long_to_str (param ${'$'}n i64) (result i32)
    (local ${'$'}neg i32) (local ${'$'}len i32) (local ${'$'}x i64) (local ${'$'}p i32) (local ${'$'}i i32)
    (if (i64.eqz (local.get ${'$'}n))
      (then
        (local.set ${'$'}p (call ${'$'}__alloc (i32.const 5)))
        (i32.store (local.get ${'$'}p) (i32.const 1))
        (i32.store8 (i32.add (local.get ${'$'}p) (i32.const 4)) (i32.const 48))
        (return (local.get ${'$'}p))))
    (local.set ${'$'}neg (i64.lt_s (local.get ${'$'}n) (i64.const 0)))
    (local.set ${'$'}x (if (result i64) (local.get ${'$'}neg) (then (i64.sub (i64.const 0) (local.get ${'$'}n))) (else (local.get ${'$'}n))))
    (local.set ${'$'}len (i32.const 0))
    (local.set ${'$'}p (i32.const 0))
    (block ${'$'}c (loop ${'$'}l
      (br_if ${'$'}c (i64.eqz (local.get ${'$'}x)))
      (local.set ${'$'}len (i32.add (local.get ${'$'}len) (i32.const 1)))
      (local.set ${'$'}x (i64.div_u (local.get ${'$'}x) (i64.const 10)))
      (br ${'$'}l)))
    (local.set ${'$'}x (if (result i64) (local.get ${'$'}neg) (then (i64.sub (i64.const 0) (local.get ${'$'}n))) (else (local.get ${'$'}n))))
    (local.set ${'$'}len (i32.add (local.get ${'$'}len) (local.get ${'$'}neg)))
    (local.set ${'$'}p (call ${'$'}__alloc (i32.add (i32.const 4) (local.get ${'$'}len))))
    (i32.store (local.get ${'$'}p) (local.get ${'$'}len))
    (local.set ${'$'}i (i32.sub (local.get ${'$'}len) (i32.const 1)))
    (block ${'$'}c2 (loop ${'$'}l2
      (br_if ${'$'}c2 (i64.eqz (local.get ${'$'}x)))
      (i32.store8 (i32.add (i32.add (local.get ${'$'}p) (i32.const 4)) (local.get ${'$'}i))
                  (i32.wrap_i64 (i64.add (i64.rem_u (local.get ${'$'}x) (i64.const 10)) (i64.const 48))))
      (local.set ${'$'}x (i64.div_u (local.get ${'$'}x) (i64.const 10)))
      (local.set ${'$'}i (i32.sub (local.get ${'$'}i) (i32.const 1)))
      (br ${'$'}l2)))
    (if (local.get ${'$'}neg) (then (i32.store8 (i32.add (local.get ${'$'}p) (i32.const 4)) (i32.const 45))))
    (local.get ${'$'}p))
"""


    /**
     * `__double_to_str` - decimal rendering of an f64, matching the interpreter and
     * LLVM: an integral value keeps its `.0`, anything else prints its fractional
     * digits.
     *
     * The integer part is emitted with the same itoa as [RT_LONG_TO_STR]; the
     * fraction is taken to a fixed number of digits with trailing zeros trimmed,
     * which covers the values a program prints without needing a shortest
     * round-trip algorithm in WAT.
     */
    private val RT_REAL_TO_STR = """
  (func ${'$'}__double_to_str (param ${'$'}v f64) (result i32)
    (local ${'$'}neg i32) (local ${'$'}ip i64) (local ${'$'}frac f64) (local ${'$'}fd i64)
    (local ${'$'}p i32) (local ${'$'}q i32) (local ${'$'}len i32) (local ${'$'}i i32) (local ${'$'}x i64)
    (local.set ${'$'}neg (f64.lt (local.get ${'$'}v) (f64.const 0)))
    (local.set ${'$'}v (f64.abs (local.get ${'$'}v)))
    (local.set ${'$'}ip (i64.trunc_f64_u (local.get ${'$'}v)))
    (local.set ${'$'}frac (f64.sub (local.get ${'$'}v) (f64.convert_i64_u (local.get ${'$'}ip))))
    ;; fifteen fractional digits, rounded, then trailing zeros trimmed below -
    ;; enough to show the difference between the default and vha math tiers
    (local.set ${'$'}fd (i64.trunc_f64_u (f64.nearest (f64.mul (local.get ${'$'}frac) (f64.const 1000000000000000)))))
    (if (i64.ge_u (local.get ${'$'}fd) (i64.const 1000000000000000))
      (then
        (local.set ${'$'}fd (i64.const 0))
        (local.set ${'$'}ip (i64.add (local.get ${'$'}ip) (i64.const 1)))))
    ;; integer part digit count
    (local.set ${'$'}len (i32.const 0))
    (local.set ${'$'}x (local.get ${'$'}ip))
    (if (i64.eqz (local.get ${'$'}x)) (then (local.set ${'$'}len (i32.const 1))))
    (block ${'$'}c (loop ${'$'}l
      (br_if ${'$'}c (i64.eqz (local.get ${'$'}x)))
      (local.set ${'$'}len (i32.add (local.get ${'$'}len) (i32.const 1)))
      (local.set ${'$'}x (i64.div_u (local.get ${'$'}x) (i64.const 10)))
      (br ${'$'}l)))
    ;; 20 integer digits + '.' + 15 fraction digits + sign, plus the length word
    (local.set ${'$'}p (call ${'$'}__alloc (i32.const 44)))
    (local.set ${'$'}q (i32.add (local.get ${'$'}p) (i32.const 4)))
    (local.set ${'$'}i (i32.const 0))
    (if (local.get ${'$'}neg)
      (then
        (i32.store8 (local.get ${'$'}q) (i32.const 45))
        (local.set ${'$'}q (i32.add (local.get ${'$'}q) (i32.const 1)))
        (local.set ${'$'}i (i32.const 1))))
    ;; integer digits, written back to front
    (local.set ${'$'}x (local.get ${'$'}ip))
    (local.set ${'$'}i (i32.add (local.get ${'$'}i) (local.get ${'$'}len)))
    (local.set ${'$'}len (i32.sub (local.get ${'$'}len) (i32.const 1)))
    (block ${'$'}c2 (loop ${'$'}l2
      (i32.store8 (i32.add (local.get ${'$'}q) (local.get ${'$'}len))
                  (i32.wrap_i64 (i64.add (i64.rem_u (local.get ${'$'}x) (i64.const 10)) (i64.const 48))))
      (local.set ${'$'}x (i64.div_u (local.get ${'$'}x) (i64.const 10)))
      (local.set ${'$'}len (i32.sub (local.get ${'$'}len) (i32.const 1)))
      (br_if ${'$'}l2 (i32.ge_s (local.get ${'$'}len) (i32.const 0)))
      (br ${'$'}c2)))
    (local.set ${'$'}q (i32.add (local.get ${'$'}p) (i32.add (i32.const 4) (local.get ${'$'}i))))
    (i32.store8 (local.get ${'$'}q) (i32.const 46))
    (local.set ${'$'}q (i32.add (local.get ${'$'}q) (i32.const 1)))
    (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
    ;; trim trailing zeros, but always keep one fractional digit
    (block ${'$'}c3 (loop ${'$'}l3
      (br_if ${'$'}c3 (i64.eqz (local.get ${'$'}fd)))
      (br_if ${'$'}c3 (i64.ne (i64.rem_u (local.get ${'$'}fd) (i64.const 10)) (i64.const 0)))
      (local.set ${'$'}fd (i64.div_u (local.get ${'$'}fd) (i64.const 10)))
      (br ${'$'}l3)))
    (local.set ${'$'}len (i32.const 0))
    (local.set ${'$'}x (local.get ${'$'}fd))
    (if (i64.eqz (local.get ${'$'}x)) (then (local.set ${'$'}len (i32.const 1))))
    (block ${'$'}c4 (loop ${'$'}l4
      (br_if ${'$'}c4 (i64.eqz (local.get ${'$'}x)))
      (local.set ${'$'}len (i32.add (local.get ${'$'}len) (i32.const 1)))
      (local.set ${'$'}x (i64.div_u (local.get ${'$'}x) (i64.const 10)))
      (br ${'$'}l4)))
    (local.set ${'$'}x (local.get ${'$'}fd))
    (local.set ${'$'}i (i32.add (local.get ${'$'}i) (local.get ${'$'}len)))
    (local.set ${'$'}len (i32.sub (local.get ${'$'}len) (i32.const 1)))
    (block ${'$'}c5 (loop ${'$'}l5
      (i32.store8 (i32.add (local.get ${'$'}q) (local.get ${'$'}len))
                  (i32.wrap_i64 (i64.add (i64.rem_u (local.get ${'$'}x) (i64.const 10)) (i64.const 48))))
      (local.set ${'$'}x (i64.div_u (local.get ${'$'}x) (i64.const 10)))
      (local.set ${'$'}len (i32.sub (local.get ${'$'}len) (i32.const 1)))
      (br_if ${'$'}l5 (i32.ge_s (local.get ${'$'}len) (i32.const 0)))
      (br ${'$'}c5)))
    (i32.store (local.get ${'$'}p) (local.get ${'$'}i))
    (local.get ${'$'}p))
"""


    /**
     * Software `sin`/`cos` for WebAssembly, which has no opcode for either.
     *
     * `x` is reduced to `r` in `[-pi/4, pi/4]` by subtracting a whole multiple of
     * `pi/2`, split Cody-Waite style into a high and low part so the subtraction
     * stays exact for the arguments a program realistically passes. The quadrant
     * `n mod 4` then selects between the sine and cosine polynomials and their
     * signs. Both polynomials are the odd/even Taylor series to the term beyond
     * which `f64` cannot represent a difference over this interval.
     */
    private val RT_TRIG = """
  (func ${'$'}rt.sin_poly (param ${'$'}r f64) (result f64)
    (local ${'$'}z f64)
    (local.set ${'$'}z (f64.mul (local.get ${'$'}r) (local.get ${'$'}r)))
    (f64.mul (local.get ${'$'}r)
      (f64.add (f64.const 1)
        (f64.mul (local.get ${'$'}z)
          (f64.add (f64.const -0.16666666666666666)
            (f64.mul (local.get ${'$'}z)
              (f64.add (f64.const 0.008333333333333333)
                (f64.mul (local.get ${'$'}z)
                  (f64.add (f64.const -0.0001984126984126984)
                    (f64.mul (local.get ${'$'}z)
                      (f64.add (f64.const 0.0000027557319223985893)
                        (f64.mul (local.get ${'$'}z) (f64.const -0.000000025052108385441718)))))))))))))
  (func ${'$'}rt.cos_poly (param ${'$'}r f64) (result f64)
    (local ${'$'}z f64)
    (local.set ${'$'}z (f64.mul (local.get ${'$'}r) (local.get ${'$'}r)))
    (f64.add (f64.const 1)
      (f64.mul (local.get ${'$'}z)
        (f64.add (f64.const -0.5)
          (f64.mul (local.get ${'$'}z)
            (f64.add (f64.const 0.041666666666666664)
              (f64.mul (local.get ${'$'}z)
                (f64.add (f64.const -0.001388888888888889)
                  (f64.mul (local.get ${'$'}z)
                    (f64.add (f64.const 0.0000248015873015873)
                      (f64.mul (local.get ${'$'}z)
                        (f64.add (f64.const -0.00000027557319223985893)
                          (f64.mul (local.get ${'$'}z) (f64.const 0.0000000020876756987868098))))))))))))))
  ;; quadrant dispatch shared by sin and cos; ${'$'}k offsets the quadrant (cos = sin + 1)
  (func ${'$'}rt.trig (param ${'$'}x f64) (param ${'$'}k i32) (result f64)
    (local ${'$'}n f64) (local ${'$'}r f64) (local ${'$'}q i32)
    (local.set ${'$'}n (f64.nearest (f64.mul (local.get ${'$'}x) (f64.const 0.6366197723675814))))
    ;; three-part pi/2 (fdlibm's split): the parts must SUM to pi/2 to within the
    ;; final precision - a low part that is merely small leaves exactly that much
    ;; error in every result, which is far larger than any polynomial truncation.
    (local.set ${'$'}r (f64.sub (local.get ${'$'}x) (f64.mul (local.get ${'$'}n) (f64.const 1.5707963267341256))))
    (local.set ${'$'}r (f64.sub (local.get ${'$'}r) (f64.mul (local.get ${'$'}n) (f64.const 0.0000000000607710050650619224932))))
    (local.set ${'$'}r (f64.sub (local.get ${'$'}r) (f64.mul (local.get ${'$'}n) (f64.const 0.00000000000000000000202226624879595063154))))
    (local.set ${'$'}q (i32.and (i32.add (i32.trunc_f64_s (local.get ${'$'}n)) (local.get ${'$'}k)) (i32.const 3)))
    (if (result f64) (i32.eq (local.get ${'$'}q) (i32.const 0))
      (then (call ${'$'}rt.sin_poly (local.get ${'$'}r)))
      (else (if (result f64) (i32.eq (local.get ${'$'}q) (i32.const 1))
        (then (call ${'$'}rt.cos_poly (local.get ${'$'}r)))
        (else (if (result f64) (i32.eq (local.get ${'$'}q) (i32.const 2))
          (then (f64.neg (call ${'$'}rt.sin_poly (local.get ${'$'}r))))
          (else (f64.neg (call ${'$'}rt.cos_poly (local.get ${'$'}r)))))))))) 
  (func ${'$'}rt.soft_sin (param ${'$'}x f64) (result f64)
    (call ${'$'}rt.trig (local.get ${'$'}x) (i32.const 0)))
  (func ${'$'}rt.soft_cos (param ${'$'}x f64) (result f64)
    (call ${'$'}rt.trig (local.get ${'$'}x) (i32.const 1)))
"""


    /**
     * Software `exp`/`log` for WebAssembly, and the functions built from them.
     *
     * `log` splits the operand into `2^k * m` by reading the f64 exponent field
     * directly, then evaluates `log(m)` on the narrow interval `[sqrt(1/2), sqrt(2)]`
     * with the atanh series, which converges fast enough there to stay well inside
     * this tier's accuracy. `exp` reduces by `k = round(x/ln2)` and evaluates the
     * remainder's Taylor series, reassembling `2^k` by constructing the exponent
     * bits. `pow` is `exp(y*log(x))`; the rest are one identity each.
     */
    private val RT_EXPLOG = """
  (func ${'$'}rt.soft_log (param ${'$'}x f64) (result f64)
    (local ${'$'}bits i64) (local ${'$'}k i32) (local ${'$'}m f64) (local ${'$'}s f64) (local ${'$'}z f64)
    (if (f64.le (local.get ${'$'}x) (f64.const 0))
      (then (return (f64.div (f64.const -1) (f64.const 0)))))
    (local.set ${'$'}bits (i64.reinterpret_f64 (local.get ${'$'}x)))
    (local.set ${'$'}k (i32.sub (i32.wrap_i64 (i64.and (i64.shr_u (local.get ${'$'}bits) (i64.const 52)) (i64.const 2047))) (i32.const 1023)))
    ;; mantissa back into [1, 2)
    (local.set ${'$'}m (f64.reinterpret_i64
      (i64.or (i64.and (local.get ${'$'}bits) (i64.const 4503599627370495))
              (i64.const 4607182418800017408))))
    ;; shift to [sqrt(1/2), sqrt(2)) so the series converges quickly
    (if (f64.gt (local.get ${'$'}m) (f64.const 1.4142135623730951))
      (then
        (local.set ${'$'}m (f64.mul (local.get ${'$'}m) (f64.const 0.5)))
        (local.set ${'$'}k (i32.add (local.get ${'$'}k) (i32.const 1)))))
    (local.set ${'$'}s (f64.div (f64.sub (local.get ${'$'}m) (f64.const 1)) (f64.add (local.get ${'$'}m) (f64.const 1))))
    (local.set ${'$'}z (f64.mul (local.get ${'$'}s) (local.get ${'$'}s)))
    (f64.add
      (f64.mul (f64.convert_i32_s (local.get ${'$'}k)) (f64.const 0.6931471805599453))
      (f64.mul (f64.const 2)
        (f64.mul (local.get ${'$'}s)
          (f64.add (f64.const 1)
            (f64.mul (local.get ${'$'}z)
              (f64.add (f64.const 0.3333333333333333)
                (f64.mul (local.get ${'$'}z)
                  (f64.add (f64.const 0.2)
                    (f64.mul (local.get ${'$'}z)
                      (f64.add (f64.const 0.14285714285714285)
                        (f64.mul (local.get ${'$'}z)
                          (f64.add (f64.const 0.1111111111111111)
                            (f64.mul (local.get ${'$'}z)
                              (f64.add (f64.const 0.09090909090909091)
                                (f64.mul (local.get ${'$'}z) (f64.const 0.07692307692307693)))))))))))))))))
  (func ${'$'}rt.soft_exp (param ${'$'}x f64) (result f64)
    (local ${'$'}k f64) (local ${'$'}r f64) (local ${'$'}sum f64) (local ${'$'}term f64) (local ${'$'}i i32) (local ${'$'}ki i32)
    (if (f64.gt (local.get ${'$'}x) (f64.const 709.78))
      (then (return (f64.div (f64.const 1) (f64.const 0)))))
    (if (f64.lt (local.get ${'$'}x) (f64.const -745.2))
      (then (return (f64.const 0))))
    (local.set ${'$'}k (f64.nearest (f64.mul (local.get ${'$'}x) (f64.const 1.4426950408889634))))
    (local.set ${'$'}r (f64.sub (local.get ${'$'}x) (f64.mul (local.get ${'$'}k) (f64.const 0.6931471805599453))))
    (local.set ${'$'}sum (f64.const 1))
    (local.set ${'$'}term (f64.const 1))
    (local.set ${'$'}i (i32.const 1))
    (block ${'$'}c (loop ${'$'}l
      (br_if ${'$'}c (i32.gt_s (local.get ${'$'}i) (i32.const 16)))
      (local.set ${'$'}term (f64.div (f64.mul (local.get ${'$'}term) (local.get ${'$'}r)) (f64.convert_i32_s (local.get ${'$'}i))))
      (local.set ${'$'}sum (f64.add (local.get ${'$'}sum) (local.get ${'$'}term)))
      (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
      (br ${'$'}l)))
    ;; multiply by 2^k by building the exponent field directly
    (local.set ${'$'}ki (i32.trunc_f64_s (local.get ${'$'}k)))
    (f64.mul (local.get ${'$'}sum)
      (f64.reinterpret_i64 (i64.shl (i64.extend_i32_s (i32.add (local.get ${'$'}ki) (i32.const 1023))) (i64.const 52)))))
  (func ${'$'}rt.soft_log2 (param ${'$'}x f64) (result f64)
    (f64.mul (call ${'$'}rt.soft_log (local.get ${'$'}x)) (f64.const 1.4426950408889634)))
  (func ${'$'}rt.soft_log10 (param ${'$'}x f64) (result f64)
    (f64.mul (call ${'$'}rt.soft_log (local.get ${'$'}x)) (f64.const 0.4342944819032518)))
  (func ${'$'}rt.soft_exp2 (param ${'$'}x f64) (result f64)
    (call ${'$'}rt.soft_exp (f64.mul (local.get ${'$'}x) (f64.const 0.6931471805599453))))
  (func ${'$'}rt.soft_tan (param ${'$'}x f64) (result f64)
    (f64.div (call ${'$'}rt.soft_sin (local.get ${'$'}x)) (call ${'$'}rt.soft_cos (local.get ${'$'}x))))
  (func ${'$'}rt.soft_sinh (param ${'$'}x f64) (result f64)
    (f64.mul (f64.const 0.5) (f64.sub (call ${'$'}rt.soft_exp (local.get ${'$'}x)) (call ${'$'}rt.soft_exp (f64.neg (local.get ${'$'}x))))))
  (func ${'$'}rt.soft_cosh (param ${'$'}x f64) (result f64)
    (f64.mul (f64.const 0.5) (f64.add (call ${'$'}rt.soft_exp (local.get ${'$'}x)) (call ${'$'}rt.soft_exp (f64.neg (local.get ${'$'}x))))))
  (func ${'$'}rt.soft_tanh (param ${'$'}x f64) (result f64)
    (f64.div (call ${'$'}rt.soft_sinh (local.get ${'$'}x)) (call ${'$'}rt.soft_cosh (local.get ${'$'}x))))
  (func ${'$'}rt.soft_cbrt (param ${'$'}x f64) (result f64)
    (local ${'$'}neg i32) (local ${'$'}y f64) (local ${'$'}i i32)
    (if (f64.eq (local.get ${'$'}x) (f64.const 0)) (then (return (f64.const 0))))
    (local.set ${'$'}neg (f64.lt (local.get ${'$'}x) (f64.const 0)))
    (local.set ${'$'}x (f64.abs (local.get ${'$'}x)))
    (local.set ${'$'}y (call ${'$'}rt.soft_exp (f64.mul (call ${'$'}rt.soft_log (local.get ${'$'}x)) (f64.const 0.3333333333333333))))
    ;; two Newton steps clean up the exp/log round trip
    (local.set ${'$'}i (i32.const 0))
    (block ${'$'}c (loop ${'$'}l
      (br_if ${'$'}c (i32.ge_s (local.get ${'$'}i) (i32.const 2)))
      (local.set ${'$'}y (f64.div
        (f64.add (f64.mul (f64.const 2) (local.get ${'$'}y)) (f64.div (local.get ${'$'}x) (f64.mul (local.get ${'$'}y) (local.get ${'$'}y))))
        (f64.const 3)))
      (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
      (br ${'$'}l)))
    (if (result f64) (local.get ${'$'}neg) (then (f64.neg (local.get ${'$'}y))) (else (local.get ${'$'}y))))
"""


    /**
     * The remaining Wasm software math: inverse trigonometry, `pow` and `hypot`.
     *
     * `atan` reduces its argument twice - reciprocal for `|x| > 1`, then the
     * half-angle identity - so the series only ever runs on `[0, tan(pi/8)]`, where
     * it converges quickly. `asin`/`acos` come from `atan` by the standard
     * identities, `pow` is `exp(y*log x)` with the integer-exponent sign cases
     * handled directly, and `hypot` scales by the larger operand so `x*x` cannot
     * overflow for values whose hypotenuse is representable.
     */
    private val RT_INVTRIG = """
  (func ${'$'}rt.atan_core (param ${'$'}x f64) (result f64)
    (local ${'$'}z f64) (local ${'$'}s f64)
    (local.set ${'$'}z (f64.mul (local.get ${'$'}x) (local.get ${'$'}x)))
    (f64.mul (local.get ${'$'}x)
      (f64.add (f64.const 1)
        (f64.mul (local.get ${'$'}z)
          (f64.add (f64.const -0.3333333333333333)
            (f64.mul (local.get ${'$'}z)
              (f64.add (f64.const 0.2)
                (f64.mul (local.get ${'$'}z)
                  (f64.add (f64.const -0.14285714285714285)
                    (f64.mul (local.get ${'$'}z)
                      (f64.add (f64.const 0.1111111111111111)
                        (f64.mul (local.get ${'$'}z)
                          (f64.add (f64.const -0.09090909090909091)
                            (f64.mul (local.get ${'$'}z)
                              (f64.add (f64.const 0.07692307692307693)
                                (f64.mul (local.get ${'$'}z) (f64.const -0.06666666666666667)))))))))))))))))
  (func ${'$'}rt.soft_atan (param ${'$'}x f64) (result f64)
    (local ${'$'}neg i32) (local ${'$'}inv i32) (local ${'$'}half i32) (local ${'$'}r f64)
    (local.set ${'$'}neg (f64.lt (local.get ${'$'}x) (f64.const 0)))
    (local.set ${'$'}x (f64.abs (local.get ${'$'}x)))
    (local.set ${'$'}inv (f64.gt (local.get ${'$'}x) (f64.const 1)))
    (if (local.get ${'$'}inv) (then (local.set ${'$'}x (f64.div (f64.const 1) (local.get ${'$'}x)))))
    ;; half-angle once more: atan(x) = 2*atan(x / (1 + sqrt(1+x^2)))
    (local.set ${'$'}half (f64.gt (local.get ${'$'}x) (f64.const 0.41421356237309503)))
    (if (local.get ${'$'}half)
      (then (local.set ${'$'}x (f64.div (local.get ${'$'}x)
              (f64.add (f64.const 1) (f64.sqrt (f64.add (f64.const 1) (f64.mul (local.get ${'$'}x) (local.get ${'$'}x))))))))) 
    (local.set ${'$'}r (call ${'$'}rt.atan_core (local.get ${'$'}x)))
    (if (local.get ${'$'}half) (then (local.set ${'$'}r (f64.mul (local.get ${'$'}r) (f64.const 2)))))
    (if (local.get ${'$'}inv) (then (local.set ${'$'}r (f64.sub (f64.const 1.5707963267948966) (local.get ${'$'}r)))))
    (if (result f64) (local.get ${'$'}neg) (then (f64.neg (local.get ${'$'}r))) (else (local.get ${'$'}r))))
  (func ${'$'}rt.soft_asin (param ${'$'}x f64) (result f64)
    (if (f64.ge (f64.abs (local.get ${'$'}x)) (f64.const 1))
      (then (return (f64.mul (f64.const 1.5707963267948966)
        (if (result f64) (f64.lt (local.get ${'$'}x) (f64.const 0)) (then (f64.const -1)) (else (f64.const 1)))))))
    (call ${'$'}rt.soft_atan (f64.div (local.get ${'$'}x)
      (f64.sqrt (f64.sub (f64.const 1) (f64.mul (local.get ${'$'}x) (local.get ${'$'}x)))))))
  (func ${'$'}rt.soft_acos (param ${'$'}x f64) (result f64)
    (f64.sub (f64.const 1.5707963267948966) (call ${'$'}rt.soft_asin (local.get ${'$'}x))))
  (func ${'$'}rt.soft_atan2 (param ${'$'}y f64) (param ${'$'}x f64) (result f64)
    (if (f64.gt (local.get ${'$'}x) (f64.const 0))
      (then (return (call ${'$'}rt.soft_atan (f64.div (local.get ${'$'}y) (local.get ${'$'}x))))))
    (if (f64.lt (local.get ${'$'}x) (f64.const 0))
      (then
        (if (f64.ge (local.get ${'$'}y) (f64.const 0))
          (then (return (f64.add (call ${'$'}rt.soft_atan (f64.div (local.get ${'$'}y) (local.get ${'$'}x))) (f64.const 3.141592653589793))))
          (else (return (f64.sub (call ${'$'}rt.soft_atan (f64.div (local.get ${'$'}y) (local.get ${'$'}x))) (f64.const 3.141592653589793)))))))
    ;; x == 0
    (if (f64.gt (local.get ${'$'}y) (f64.const 0)) (then (return (f64.const 1.5707963267948966))))
    (if (f64.lt (local.get ${'$'}y) (f64.const 0)) (then (return (f64.const -1.5707963267948966))))
    (f64.const 0))
  (func ${'$'}rt.soft_hypot (param ${'$'}x f64) (param ${'$'}y f64) (result f64)
    (local ${'$'}m f64) (local ${'$'}r f64)
    (local.set ${'$'}x (f64.abs (local.get ${'$'}x)))
    (local.set ${'$'}y (f64.abs (local.get ${'$'}y)))
    (local.set ${'$'}m (f64.max (local.get ${'$'}x) (local.get ${'$'}y)))
    (if (f64.eq (local.get ${'$'}m) (f64.const 0)) (then (return (f64.const 0))))
    (local.set ${'$'}r (f64.div (f64.min (local.get ${'$'}x) (local.get ${'$'}y)) (local.get ${'$'}m)))
    (f64.mul (local.get ${'$'}m) (f64.sqrt (f64.add (f64.const 1) (f64.mul (local.get ${'$'}r) (local.get ${'$'}r))))))
  (func ${'$'}rt.soft_pow (param ${'$'}x f64) (param ${'$'}y f64) (result f64)
    (local ${'$'}n f64) (local ${'$'}odd i32)
    (if (f64.eq (local.get ${'$'}y) (f64.const 0)) (then (return (f64.const 1))))
    (if (f64.eq (local.get ${'$'}x) (f64.const 0)) (then (return (f64.const 0))))
    (if (f64.gt (local.get ${'$'}x) (f64.const 0))
      (then (return (call ${'$'}rt.soft_exp (f64.mul (local.get ${'$'}y) (call ${'$'}rt.soft_log (local.get ${'$'}x)))))))
    ;; negative base is real only for an integer exponent; the sign follows its parity
    (local.set ${'$'}n (f64.nearest (local.get ${'$'}y)))
    (if (f64.ne (local.get ${'$'}n) (local.get ${'$'}y))
      (then (return (f64.div (f64.const 0) (f64.const 0)))))
    (local.set ${'$'}odd (i32.and (i32.trunc_f64_s (local.get ${'$'}n)) (i32.const 1)))
    (local.set ${'$'}n (call ${'$'}rt.soft_exp (f64.mul (local.get ${'$'}y) (call ${'$'}rt.soft_log (f64.neg (local.get ${'$'}x))))))
    (if (result f64) (local.get ${'$'}odd) (then (f64.neg (local.get ${'$'}n))) (else (local.get ${'$'}n))))
"""


    /**
     * The `vha` trigonometry: the same reduction as [RT_TRIG] with the series
     * carried two terms further, to where an `f64` can no longer tell the
     * difference over the reduced interval.
     *
     * `sin` stops earlier because the terms it drops are invisible at float
     * precision and cost real time in a loop; `vha::sin` pays for them.
     */
    private val RT_VHA_TRIG = """
  (func ${'$'}rt.vha_sin_poly (param ${'$'}r f64) (result f64)
    (local ${'$'}z f64)
    (local.set ${'$'}z (f64.mul (local.get ${'$'}r) (local.get ${'$'}r)))
    (f64.mul (local.get ${'$'}r) (f64.add (f64.const 1) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.16666666666666666) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.008333333333333333) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.0001984126984126984) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.0000027557319223985893) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.000000025052108385441718) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.00000000016059043836821613) (f64.mul (local.get ${'$'}z) (f64.const -0.0000000000007647163731819816)))))))))))))))))
  (func ${'$'}rt.vha_cos_poly (param ${'$'}r f64) (result f64)
    (local ${'$'}z f64)
    (local.set ${'$'}z (f64.mul (local.get ${'$'}r) (local.get ${'$'}r)))
    (f64.add (f64.const 1) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.5) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.041666666666666664) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.001388888888888889) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.0000248015873015873) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.00000027557319223985893) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.0000000020876756987868098) (f64.mul (local.get ${'$'}z) (f64.const -0.000000000011470745597729725))))))))))))))))
  (func ${'$'}rt.vha_trig (param ${'$'}x f64) (param ${'$'}k i32) (result f64)
    (local ${'$'}n f64) (local ${'$'}r f64) (local ${'$'}q i32)
    (local.set ${'$'}n (f64.nearest (f64.mul (local.get ${'$'}x) (f64.const 0.6366197723675814))))
    (local.set ${'$'}r (f64.sub (local.get ${'$'}x) (f64.mul (local.get ${'$'}n) (f64.const 1.5707963267341256))))
    (local.set ${'$'}r (f64.sub (local.get ${'$'}r) (f64.mul (local.get ${'$'}n) (f64.const 0.0000000000607710050650619224932))))
    (local.set ${'$'}r (f64.sub (local.get ${'$'}r) (f64.mul (local.get ${'$'}n) (f64.const 0.00000000000000000000202226624879595063154))))
    (local.set ${'$'}q (i32.and (i32.add (i32.trunc_f64_s (local.get ${'$'}n)) (local.get ${'$'}k)) (i32.const 3)))
    (if (result f64) (i32.eq (local.get ${'$'}q) (i32.const 0))
      (then (call ${'$'}rt.vha_sin_poly (local.get ${'$'}r)))
      (else (if (result f64) (i32.eq (local.get ${'$'}q) (i32.const 1))
        (then (call ${'$'}rt.vha_cos_poly (local.get ${'$'}r)))
        (else (if (result f64) (i32.eq (local.get ${'$'}q) (i32.const 2))
          (then (f64.neg (call ${'$'}rt.vha_sin_poly (local.get ${'$'}r))))
          (else (f64.neg (call ${'$'}rt.vha_cos_poly (local.get ${'$'}r))))))))))
  (func ${'$'}rt.vha_sin (param ${'$'}x f64) (result f64)
    (call ${'$'}rt.vha_trig (local.get ${'$'}x) (i32.const 0)))
  (func ${'$'}rt.vha_cos (param ${'$'}x f64) (result f64)
    (call ${'$'}rt.vha_trig (local.get ${'$'}x) (i32.const 1)))
  (func ${'$'}rt.vha_tan (param ${'$'}x f64) (result f64)
    (f64.div (call ${'$'}rt.vha_sin (local.get ${'$'}x)) (call ${'$'}rt.vha_cos (local.get ${'$'}x))))
"""

}
