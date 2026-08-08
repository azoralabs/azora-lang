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
import org.azora.lang.ir.IrField
import org.azora.lang.ir.IrFunction
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrStmt
import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.IrType
import org.azora.lang.ir.IrUnaryOp

/**
 * Backend - lowers [IrProgram] to WebAssembly text format (WAT), using the
 * folded S-expression syntax.
 *
 * Value representation (single WASM value per Azora value):
 *  - `Int`/`Bool`/`Char`/sized ints ≤ 32-bit → `i32`
 *  - `Long`/`ULong`/`Cent`/`UCent` → `i64`     `Double`/`Decimal` → `f64`   `Float` → `f32`
 *  - `String`/`arr[T]`/pack → `i32` pointer into linear memory. Strings and arrays
 *    are laid out as `[len: i32][payload…]`; packs as packed `i32` fields.
 *
 * Printing and string handling go through host imports (`print_i32`, `print_str`,
 * …) and a small linear-memory runtime (`__alloc`, `__str_concat`, `__str_eq`,
 * `__str_repeat`, `__int_to_str`). Structured control flow lowers to
 * `block`/`loop`/`br_if`.
 *
 * NOTE: this is an MVP-level target - packs/arrays assume 4-byte (`i32`) fields
 * and elements; exceptions lower to `unreachable`; tasks are synchronous.
 */
class WasmCodegen {

    private data class ClosureCapture(val name: String, val type: IrType, val offset: Int)
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

    // Module state.
    private val structs = HashMap<String, List<IrField>>()
    /** Names of the `union` types: every member of one sits at offset 0. */
    private val unions = HashSet<String>()
    private val globalTypes = LinkedHashMap<String, IrType>()
    private val stringConsts = LinkedHashMap<String, Int>() // literal -> offset
    private var constCursor = STRING_BASE

    private var usesAlloc = false
    private var usesConcat = false
    private var usesStrEq = false
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
    private val neededExterns = LinkedHashSet<String>()
    private val closureTypes = LinkedHashMap<IrType.Function, String>()
    private val closureFunctions = mutableListOf<ClosureFunction>()
    private val stringIntrinsics = setOf(
        "stringLength", "charAt", "ord", "chr", "isDigit", "isAlpha", "substring",
        "startsWith", "endsWith", "contains", "indexOf", "toUpper", "toLower", "trim",
        "replace", "split", "toChars", "fromChars",
    )

    companion object {
        private const val STRING_BASE = 1024
    }

    /**
     * Generates WebAssembly text (WAT) from the given IR program.
     *
     * @param program the optimized IR program to lower to WAT
     * @return the generated WAT module source
     */
    fun generate(program: IrProgram): String {
        out.clear(); indent = 0
        structs.clear(); globalTypes.clear(); stringConsts.clear(); constCursor = STRING_BASE
        usesAlloc = false; usesConcat = false; usesStrEq = false; usesRepeat = false; usesIntToStr = false; usesLongToStr = false; usesDoubleToStr = false; usesTrig = false; usesExpLog = false; usesInvTrig = false; usesVhaTrig = false; usesIsCheck = false
        neededIntrinsics.clear(); externs.clear(); neededExterns.clear()
        reactiveStorage.clear(); reactiveAliases.clear()
        closureTypes.clear(); closureFunctions.clear()

        for (item in program.items) if (item is IrTopLevel.Struct) {
            structs[item.name] = item.fields
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
            val result = if (extern.returnType == IrType.Unit) "" else " (result ${wasmType(extern.returnType)})"
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
            val params = (callable.params + callable.receivers + IrType.Any)
                .joinToString("") { " (param ${wasmType(it)})" }
            val result = if (callable.ret == IrType.Unit) "" else " (result ${wasmType(callable.ret)})"
            sb.appendLine("  (type \$$name (func$params$result))")
        }
        if (closureFunctions.isNotEmpty()) {
            sb.appendLine("  (table ${closureFunctions.size} funcref)")
            sb.appendLine(
                closureFunctions.joinToString(" ", "  (elem (i32.const 0) ", ")\n") {
                    "\$__closure_${it.index}"
                },
            )
        }
        sb.appendLine("  (memory (export \"memory\") 16)")
        sb.appendLine("  (global \$__heap (mut i32) (i32.const ${align4(constCursor)}))")
        for ((name, type) in globalTypes) {
            sb.appendLine("  (global \$$name (mut ${wasmType(type)}) (${wasmType(type)}.const 0))")
        }
        if (usesAlloc) sb.append(RT_ALLOC)
        if (usesConcat) sb.append(RT_CONCAT)
        if (usesIsCheck) usesStrEq = true
        if (usesStrEq) sb.append(RT_STR_EQ)
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
        locals.clear(); localIrTypes.clear(); tempCounter = 0; blockCounter = 0
        loopStack.clear(); labelTargets.clear(); params = emptySet()

        out.clear(); indent = 2
        for (stmt in globals) {
            when (stmt) {
                is IrStmt.VarDecl -> line("(global.set \$${stmt.name} ${emitExpr(stmt.initializer)})")
                is IrStmt.FinDecl -> line("(global.set \$${stmt.name} ${emitExpr(stmt.initializer)})")
                is IrStmt.LetDecl -> line("(global.set \$${stmt.name} ${emitExpr(stmt.initializer)})")
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
        locals.clear(); localIrTypes.clear(); tempCounter = 0; blockCounter = 0
        loopStack.clear(); labelTargets.clear()
        params = func.params.map { it.first }.toSet()
        activeReactiveEffects.clear()
        emittingReactiveEffect = false
        lazyLocals.clear()
        reactiveAliases.clear()
        currentFunctionName = func.name
        localIrTypes.putAll(func.params)

        out.clear(); indent = 2
        for (stmt in func.body) emitStmt(stmt)
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
        if (func.returnType != IrType.Unit) sig.append(" (result ${wasmType(func.returnType)})")
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
     * off the end - which is exactly the "type mismatch in implicit return"
     * a `when self { … }` used to produce. Those cases want the terminator.
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
                val alias = reactiveAliases[stmt.name]
                val target = alias?.valueGlobal ?: stmt.name
                val operation = if (alias != null || (target in globalTypes && target !in localIrTypes)) "global.set" else "local.set"
                line("($operation \$$target ${emitExpr(stmt.value)})")
                if (!emittingReactiveEffect) {
                    val changed = invalidateLazyDependents(stmt.name) + stmt.name
                    for (effect in activeReactiveEffects.filter { effect ->
                        effect.dependencies.any { it in changed }
                    }) {
                        emitReactiveEffect(effect)
                    }
                }
            }
            is IrStmt.IndexAssign -> line("(i32.store ${elemAddr(stmt.target, stmt.index)} ${emitExpr(stmt.value)})")
            is IrStmt.MemberAssign -> line("(i32.store ${fieldAddr(stmt.target, stmt.name)} ${emitExpr(stmt.value)})")
            is IrStmt.ExprStmt -> {
                val e = emitExpr(stmt.expr)
                if (stmt.expr.type == IrType.Unit) line(e) else line("(drop $e)")
            }
            is IrStmt.Return -> line(if (stmt.value != null) "(return ${emitExpr(stmt.value)})" else "(return)")
            is IrStmt.If -> emitIf(stmt)
            is IrStmt.When -> emitWhen(stmt)
            is IrStmt.While -> emitWhile(stmt.label, emitExpr(stmt.condition), stmt.body, isFor = false, forInc = null)
            is IrStmt.Loop -> emitWhile(stmt.label, "(i32.const 1)", stmt.body, isFor = false, forInc = null)
            is IrStmt.For -> emitFor(stmt)
            is IrStmt.ForEach -> {} // not supported by the WASM MVP target
            is IrStmt.Break -> line("(br \$${breakTarget(stmt.label)})")
            is IrStmt.Continue -> line("(br \$${continueTarget(stmt.label)})")
            is IrStmt.Scope -> for (s in stmt.body) emitStmt(s)
            is IrStmt.Assert -> line("(if (i32.eqz ${emitExpr(stmt.condition)}) (then unreachable))")
            is IrStmt.Trace -> emitTrace(stmt)
            is IrStmt.Throw -> line("unreachable")
            is IrStmt.Try -> for (s in stmt.body) emitStmt(s) // no exception support - run the body
            is IrStmt.Defer -> {}
            is IrStmt.Effect -> {
                activeReactiveEffects.add(stmt)
                emitReactiveEffect(stmt)
            }
            is IrStmt.Yield -> {}
        }
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

    private fun emitLocalDecl(name: String, type: IrType, initializer: IrExpr, lazy: Boolean) {
        declareLocal(name, type)
        if (!lazy) {
            line("(local.set \$$name ${emitExpr(initializer)})")
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
        line("(global.set \$${storage.valueGlobal} ${emitExpr(initializer)})")
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
        line("(local.set \$$name ${emitExpr(lazy.initializer)})")
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
                    "(i32.eq (local.get $tmp) ${emitExpr(p)})"
                }
            }.reduce { a, c -> "(i32.or $a $c)" }
            line("(if $cond")
            indent++
            line("(then")
            indent++
            // Bind slot payloads: cell (index+1) of the tagged block.
            (b.patterns.firstOrNull { it is IrExpr.SlotPattern } as? IrExpr.SlotPattern)?.let { sp ->
                sp.bindings.forEachIndexed { bi, name ->
                    declareLocal(name, sp.bindingTypes.getOrElse(bi) { IrType.Any })
                    line("(local.set \$$name (i32.load (i32.add (local.get $tmp) (i32.const ${(bi + 1) * 4}))))")
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

    private fun emitWhile(label: String?, cond: String, body: List<IrStmt>, isFor: Boolean, forInc: (() -> Unit)?) {
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

    private fun emitFor(stmt: IrStmt.For) {
        declareLocal(stmt.counter, IrType.Int)
        line("(local.set \$${stmt.counter} ${emitExpr(stmt.start)})")
        val cmp = if (stmt.reverse) (if (stmt.inclusive) "i32.ge_s" else "i32.gt_s")
        else (if (stmt.inclusive) "i32.le_s" else "i32.lt_s")
        val cond = "($cmp (local.get \$${stmt.counter}) ${emitExpr(stmt.end)})"
        val step = stmt.step?.let { emitExpr(it) } ?: "(i32.const 1)"
        val op = if (stmt.reverse) "i32.sub" else "i32.add"
        emitWhile(stmt.label, cond, stmt.body, isFor = true) {
            line("(local.set \$${stmt.counter} ($op (local.get \$${stmt.counter}) $step))")
        }
    }

    // ── Expressions (return a folded S-expression) ────────────────────────

    private fun emitExpr(expr: IrExpr): String = when (expr) {
        is IrExpr.IntLiteral -> "(${wasmType(expr.type)}.const ${expr.value})"
        is IrExpr.DoubleLiteral -> "(${wasmType(expr.type)}.const ${expr.value})"
        is IrExpr.BoolLiteral -> "(i32.const ${if (expr.value) 1 else 0})"
        is IrExpr.CharLiteral -> "(i32.const ${expr.value.code})"
        is IrExpr.StringLiteral -> "(i32.const ${internString(expr.value)})"
        is IrExpr.EnumLiteral -> "(i32.const ${internString(expr.variant)})"
        is IrExpr.EnumToString -> emitExpr(expr.value)
        is IrExpr.Var -> {
            ensureLazyInitialized(expr.name)
            val alias = reactiveAliases[expr.name]
            val target = alias?.valueGlobal ?: expr.name
            val operation = if (alias != null || (target in globalTypes && target !in localIrTypes)) "global.get" else "local.get"
            "($operation \$$target)"
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
        is IrExpr.Binary -> emitBinary(expr)
        is IrExpr.Call -> emitCall(expr)
        is IrExpr.Await -> emitExpr(expr.value)
        is IrExpr.Spread -> emitExpr(expr.array)
        is IrExpr.Index -> "(i32.load ${elemAddr(expr.target, expr.index)})"
        is IrExpr.Member -> when (expr.name) {
            "length", "size" -> "(i32.load ${emitExpr(expr.target)})"
            "data" -> "(i32.add ${emitExpr(expr.target)} (i32.const 4))"
            "isEmpty" -> "(i32.eqz (i32.load ${emitExpr(expr.target)}))"
            "isNotEmpty" -> "(i32.ne (i32.load ${emitExpr(expr.target)}) (i32.const 0))"
            else -> "(i32.load ${fieldAddr(expr.target, expr.name)})"
        }
        is IrExpr.MethodCall -> when (expr.name) {
            "isEmpty" -> "(i32.eqz (i32.load ${emitExpr(expr.target)}))"
            "isNotEmpty" -> "(i32.ne (i32.load ${emitExpr(expr.target)}) (i32.const 0))"
            else -> emitExpr(expr.target) // unsupported methods degrade to the receiver
        }
        is IrExpr.StructCtor -> emitStructCtor(expr)
        is IrExpr.ArrayLiteral -> emitArrayLiteral(expr)
        is IrExpr.NumCast -> emitNumCast(expr)
        is IrExpr.IfExpr -> {
            val t = wasmType(expr.type)
            "(if (result $t) ${emitExpr(expr.condition)} (then ${emitExpr(expr.thenExpr)}) (else ${emitExpr(expr.elseExpr)}))"
        }
        is IrExpr.StringTemplate -> emitTemplate(expr)
        is IrExpr.CatchExpr -> emitExpr(expr.expr) // no exception support - evaluate the primary expression
        is IrExpr.Lambda -> emitClosure(expr)
        is IrExpr.SetLit, is IrExpr.MapLit, is IrExpr.TupleLit, is IrExpr.TupleAccess,
        is IrExpr.VariantLit, is IrExpr.SlotPattern -> "(i32.const 0)" // unsupported by the MVP target
    }

    private fun emitBinary(expr: IrExpr.Binary): String {
        val l = expr.left; val r = expr.right
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
            IrBinaryOp.AND -> "i32.and"; IrBinaryOp.OR -> "i32.or"
            IrBinaryOp.BIT_AND -> "$p.and"; IrBinaryOp.BIT_OR -> "$p.or"; IrBinaryOp.BIT_XOR -> "$p.xor"
            IrBinaryOp.SHL -> "$p.shl"; IrBinaryOp.SHR -> "$p.shr_${if (u) "u" else "s"}"
        }
        val lv = if (bothNum) coerceWasm(emitExpr(l), l.type, opType) else emitExpr(l)
        val rv = if (bothNum) coerceWasm(emitExpr(r), r.type, opType) else emitExpr(r)
        return "($instr $lv $rv)"
    }

    private fun isNumericWasm(t: IrType): Boolean =
        t in IrType.integerTypes || t in IrType.floatTypes || t == IrType.Char

    private fun commonNumericWasm(a: IrType, b: IrType): IrType {
        if (a == b) return a
        if (a in IrType.floatTypes || b in IrType.floatTypes) {
            if (a == IrType.Double || b == IrType.Double || a == IrType.Decimal || b == IrType.Decimal) return IrType.Double
            return IrType.Float
        }
        // Widen ints: i64 types win over i32 types.
        val aWide = wasmType(a) == "i64"
        val bWide = wasmType(b) == "i64"
        return if (aWide || !bWide) a else b
    }

    /** Emits a Wasm numeric conversion of [value] from [from] to [to] (no-op when the Wasm type is unchanged). */
    private fun coerceWasm(value: String, from: IrType, to: IrType): String {
        val ft = wasmType(from); val tt = wasmType(to)
        if (ft == tt) return value
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

    private fun emitCall(expr: IrExpr.Call): String {
        // The MVP target has no host clock to sleep against, so `delay` degrades
        // to a no-op here rather than a call to a function that does not exist.
        // Its operand is still evaluated, so any effect in it still happens.
        if (expr.name == "__delay") {
            return "(drop ${emitExpr(expr.args.single())})"
        }
        if (expr.receiver != null) {
            val callable = expr.receiver.type as? IrType.Function
                ?: error("indirect call receiver is not a callable type")
            val closure = newTemp("i32")
            val typeName = closureTypeName(callable)
            val args = expr.args.joinToString(" ") { emitExpr(it) }
            val environment = "(i32.load (i32.add (local.get $closure) (i32.const 4)))"
            val tableIndex = "(i32.load (local.get $closure))"
            val operands = listOf(args, environment, tableIndex).filter { it.isNotEmpty() }.joinToString(" ")
            val result = if (expr.type == IrType.Unit) "" else " (result ${wasmType(expr.type)})"
            return "(block$result " +
                "(local.set $closure ${emitExpr(expr.receiver)}) " +
                "(call_indirect (type \$$typeName) $operands))"
        }
        if ((expr.name == "__std_println" || expr.name == "__std_print") && expr.args.size == 1) {
            val arg = expr.args.single()
            val operation = if (expr.name == "__std_print") "write" else "print"
            // A float is rendered by the compiler, not the host: printing one
            // directly and interpolating it must produce the same digits, and only
            // `__double_to_str` implements the language's convention.
            if (wasmType(arg.type) == "f64" || wasmType(arg.type) == "f32") {
                usesAlloc = true
                usesDoubleToStr = true
                val v = if (wasmType(arg.type) == "f32") "(f64.promote_f32 ${emitExpr(arg)})" else emitExpr(arg)
                return "(call \$${operation}_str (call \$__double_to_str $v))"
            }
            val fn = when {
                arg.type == IrType.String -> "${operation}_str"
                arg.type == IrType.Bool -> "${operation}_bool"
                wasmType(arg.type) == "i64" -> "${operation}_i64"
                else -> "${operation}_i32"
            }
            return "(call \$$fn ${emitExpr(arg)})"
        }
        if (expr.name == "__isCheck") usesIsCheck = true
        // `Array::fill<T>(count)` → `[ len, T×count ]` (all cells i32 in Wasm).
        if (expr.name == "__std_Array_fill") {
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
        val args = expr.args.joinToString(" ") { emitExpr(it) }
        return "(call \$${expr.name}${if (args.isEmpty()) "" else " $args"})"
    }

    private fun emitStructCtor(expr: IrExpr.StructCtor): String {
        usesAlloc = true
        val t = newTemp("i32")
        val sb = StringBuilder("(block (result i32)\n")
        val pad = "  ".repeat(indent + 1)
        // A union is one slot wide and its single named member initializes it.
        val isUnion = expr.name in unions
        val slots = if (isUnion) 1 else expr.args.size
        sb.append("$pad(local.set $t (call \$__alloc (i32.const ${slots * 4})))\n")
        for ((i, a) in expr.args.withIndex()) {
            if (isUnion && i > 0) break
            sb.append("$pad(i32.store (i32.add (local.get $t) (i32.const ${i * 4})) ${emitExpr(a)})\n")
        }
        sb.append("$pad(local.get $t))")
        return sb.toString()
    }

    private fun emitArrayLiteral(expr: IrExpr.ArrayLiteral): String {
        usesAlloc = true
        val t = newTemp("i32")
        val n = expr.elements.size
        val sb = StringBuilder("(block (result i32)\n")
        val pad = "  ".repeat(indent + 1)
        sb.append("$pad(local.set $t (call \$__alloc (i32.const ${4 + n * 4})))\n")
        sb.append("$pad(i32.store (local.get $t) (i32.const $n))\n")
        for ((i, e) in expr.elements.withIndex()) {
            sb.append("$pad(i32.store (i32.add (local.get $t) (i32.const ${4 + i * 4})) ${emitExpr(e)})\n")
        }
        sb.append("$pad(local.get $t))")
        return sb.toString()
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
        val environmentSize = captures.lastOrNull()?.let { it.offset + wasmSize(it.type) } ?: 0
        val sb = StringBuilder("(block (result i32)\n")
        val pad = "  ".repeat(indent + 1)
        if (environmentSize == 0) {
            sb.append("$pad(local.set $environment (i32.const 0))\n")
        } else {
            sb.append("$pad(local.set $environment (call \$__alloc (i32.const $environmentSize)))\n")
            for (capture in captures) {
                val address = wasmAddress(environment, capture.offset)
                sb.append("$pad(${wasmStore(capture.type)} $address (local.get \$${capture.name}))\n")
            }
        }
        sb.append("$pad(local.set $closure (call \$__alloc (i32.const 8)))\n")
        sb.append("$pad(i32.store (local.get $closure) (i32.const $index))\n")
        sb.append("$pad(i32.store (i32.add (local.get $closure) (i32.const 4)) (local.get $environment))\n")
        sb.append("$pad(local.get $closure))")
        return sb.toString()
    }

    private fun emitClosureFunction(closure: ClosureFunction): String {
        locals.clear(); localIrTypes.clear(); tempCounter = 0; blockCounter = 0
        loopStack.clear(); labelTargets.clear()
        params = closure.lambda.params.map { it.first }.toSet() + "__env"
        localIrTypes.putAll(closure.lambda.params)
        localIrTypes["__env"] = IrType.Any

        out.clear(); indent = 2
        for (capture in closure.captures) {
            declareLocal(capture.name, capture.type)
            line(
                "(local.set \$${capture.name} " +
                    "(${wasmLoad(capture.type)} ${wasmAddress("\$__env", capture.offset)}))",
            )
        }
        for (stmt in closure.lambda.body) emitStmt(stmt)
        val body = out.toString()

        val sig = StringBuilder("  (func \$__closure_${closure.index}")
        for ((name, type) in closure.lambda.params) sig.append(" (param \$$name ${wasmType(type)})")
        sig.append(" (param \$__env i32)")
        val returnType = (closure.lambda.type as IrType.Function).ret
        if (returnType != IrType.Unit) sig.append(" (result ${wasmType(returnType)})")
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

        var offset = 0
        return references
            .filterKeys { it !in declared && it in localIrTypes }
            .map { (name, type) ->
                offset = alignTo(offset, wasmAlignment(type))
                ClosureCapture(name, localIrTypes.getValue(name), offset).also {
                    offset += wasmSize(it.type)
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
        // Anything else has no WAT rendering yet. Interpolating it used to yield an
        // empty string, so a program printing a `Double` silently lost it while the
        // interpreter and LLVM printed the value; failing here keeps a missing
        // conversion visible instead of turning it into wrong output.
        else -> error(
            "wasm: cannot interpolate a value of type ${expr.type} - " +
                "only Int, String and Bool have a WAT string conversion so far",
        )
    }

    private fun emitNumCast(expr: IrExpr.NumCast): String {
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
    private fun elemAddr(target: IrExpr, index: IrExpr): String {
        val base = if (target.type is IrType.Pointer) emitExpr(target)
            else "(i32.add ${emitExpr(target)} (i32.const 4))"
        return "(i32.add $base (i32.mul ${emitExpr(index)} (i32.const 4)))"
    }

    /** Address of `target.field` - `ptr + fieldIndex*4`, or `ptr` for a union. */
    private fun fieldAddr(target: IrExpr, field: String): String {
        val structName = (target.type as? IrType.Named)?.name
        // A union's members share one slot, so every one of them is at offset 0.
        val idx = when {
            structName in unions -> 0
            else -> structName?.let { structs[it]?.indexOfFirst { f -> f.name == field } } ?: 0
        }
        return "(i32.add ${emitExpr(target)} (i32.const ${idx * 4}))"
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
        // `realm std::vha` mangles to `__std_vha_sin`; the extra accuracy is what the
        // realm exists for, so it maps to the longer series where one is implemented.
        val vha = "_vha_" in extern.name
        val name = when (extern.name.substringAfterLast('_')) {
            "sin" -> if (vha) "__vha_sin" else "__soft_sin"
            "cos" -> if (vha) "__vha_cos" else "__soft_cos"
            "tan" -> if (vha) "__vha_tan" else "__soft_tan"
            "log" -> "__soft_log"
            "log2" -> "__soft_log2"
            "log10" -> "__soft_log10"
            "exp" -> "__soft_exp"
            "exp2" -> "__soft_exp2"
            "sinh" -> "__soft_sinh"
            "cosh" -> "__soft_cosh"
            "tanh" -> "__soft_tanh"
            "cbrt" -> "__soft_cbrt"
            "asin" -> "__soft_asin"
            "acos" -> "__soft_acos"
            "atan" -> "__soft_atan"
            "atan2" -> "__soft_atan2"
            "powr" -> "__soft_pow"
            "hypot" -> "__soft_hypot"
            else -> return null
        }
        val expectedArity = if (name in setOf("__soft_atan2", "__soft_hypot", "__soft_pow")) 2 else 1
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

    private fun wasmType(type: IrType): String = when (type) {
        IrType.Long, IrType.ULong, IrType.Cent, IrType.UCent, IrType.ISize, IrType.USize -> "i64"
        IrType.Double, IrType.Decimal -> "f64"
        IrType.Float -> "f32"
        is IrType.Task -> wasmType(type.result)
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

    private val RT_ALLOC = """
  (func ${'$'}__alloc (param ${'$'}size i32) (result i32)
    (local ${'$'}p i32)
    (local.set ${'$'}p (global.get ${'$'}__heap))
    (global.set ${'$'}__heap (i32.and (i32.add (i32.add (global.get ${'$'}__heap) (local.get ${'$'}size)) (i32.const 3)) (i32.const -4)))
    (local.get ${'$'}p))
"""

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
     * loop/allocation-heavy text transforms are best-effort placeholders (return
     * their input / empty) so string comparison and indexing work.
     */
    private fun wasmStringIntrinsics(): String {
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
        // Best-effort placeholders (full text/collection ops pending).
        def("substring", "(param \$s i32) (param \$a i32) (param \$b i32) (result i32)", "(local.get \$s)")
        def("startsWith", "(param \$s i32) (param \$p i32) (result i32)", "(i32.const 0)")
        def("endsWith", "(param \$s i32) (param \$p i32) (result i32)", "(i32.const 0)")
        def("contains", "(param \$s i32) (param \$p i32) (result i32)", "(i32.const 0)")
        def("indexOf", "(param \$s i32) (param \$p i32) (result i32)", "(i32.const -1)")
        def("toUpper", "(param \$s i32) (result i32)", "(local.get \$s)")
        def("toLower", "(param \$s i32) (result i32)", "(local.get \$s)")
        def("trim", "(param \$s i32) (result i32)", "(local.get \$s)")
        def("replace", "(param \$s i32) (param \$a i32) (param \$b i32) (result i32)", "(local.get \$s)")
        def("split", "(param \$s i32) (param \$d i32) (result i32)", "(i32.const 0)")
        def("toChars", "(param \$s i32) (result i32)", "(i32.const 0)")
        def("fromChars", "(param \$c i32) (result i32)", "(i32.const 0)")
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
  (func ${'$'}__sin_poly (param ${'$'}r f64) (result f64)
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
  (func ${'$'}__cos_poly (param ${'$'}r f64) (result f64)
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
  (func ${'$'}__trig (param ${'$'}x f64) (param ${'$'}k i32) (result f64)
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
      (then (call ${'$'}__sin_poly (local.get ${'$'}r)))
      (else (if (result f64) (i32.eq (local.get ${'$'}q) (i32.const 1))
        (then (call ${'$'}__cos_poly (local.get ${'$'}r)))
        (else (if (result f64) (i32.eq (local.get ${'$'}q) (i32.const 2))
          (then (f64.neg (call ${'$'}__sin_poly (local.get ${'$'}r))))
          (else (f64.neg (call ${'$'}__cos_poly (local.get ${'$'}r)))))))))) 
  (func ${'$'}__soft_sin (param ${'$'}x f64) (result f64)
    (call ${'$'}__trig (local.get ${'$'}x) (i32.const 0)))
  (func ${'$'}__soft_cos (param ${'$'}x f64) (result f64)
    (call ${'$'}__trig (local.get ${'$'}x) (i32.const 1)))
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
  (func ${'$'}__soft_log (param ${'$'}x f64) (result f64)
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
  (func ${'$'}__soft_exp (param ${'$'}x f64) (result f64)
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
  (func ${'$'}__soft_log2 (param ${'$'}x f64) (result f64)
    (f64.mul (call ${'$'}__soft_log (local.get ${'$'}x)) (f64.const 1.4426950408889634)))
  (func ${'$'}__soft_log10 (param ${'$'}x f64) (result f64)
    (f64.mul (call ${'$'}__soft_log (local.get ${'$'}x)) (f64.const 0.4342944819032518)))
  (func ${'$'}__soft_exp2 (param ${'$'}x f64) (result f64)
    (call ${'$'}__soft_exp (f64.mul (local.get ${'$'}x) (f64.const 0.6931471805599453))))
  (func ${'$'}__soft_tan (param ${'$'}x f64) (result f64)
    (f64.div (call ${'$'}__soft_sin (local.get ${'$'}x)) (call ${'$'}__soft_cos (local.get ${'$'}x))))
  (func ${'$'}__soft_sinh (param ${'$'}x f64) (result f64)
    (f64.mul (f64.const 0.5) (f64.sub (call ${'$'}__soft_exp (local.get ${'$'}x)) (call ${'$'}__soft_exp (f64.neg (local.get ${'$'}x))))))
  (func ${'$'}__soft_cosh (param ${'$'}x f64) (result f64)
    (f64.mul (f64.const 0.5) (f64.add (call ${'$'}__soft_exp (local.get ${'$'}x)) (call ${'$'}__soft_exp (f64.neg (local.get ${'$'}x))))))
  (func ${'$'}__soft_tanh (param ${'$'}x f64) (result f64)
    (f64.div (call ${'$'}__soft_sinh (local.get ${'$'}x)) (call ${'$'}__soft_cosh (local.get ${'$'}x))))
  (func ${'$'}__soft_cbrt (param ${'$'}x f64) (result f64)
    (local ${'$'}neg i32) (local ${'$'}y f64) (local ${'$'}i i32)
    (if (f64.eq (local.get ${'$'}x) (f64.const 0)) (then (return (f64.const 0))))
    (local.set ${'$'}neg (f64.lt (local.get ${'$'}x) (f64.const 0)))
    (local.set ${'$'}x (f64.abs (local.get ${'$'}x)))
    (local.set ${'$'}y (call ${'$'}__soft_exp (f64.mul (call ${'$'}__soft_log (local.get ${'$'}x)) (f64.const 0.3333333333333333))))
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
  (func ${'$'}__atan_core (param ${'$'}x f64) (result f64)
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
  (func ${'$'}__soft_atan (param ${'$'}x f64) (result f64)
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
    (local.set ${'$'}r (call ${'$'}__atan_core (local.get ${'$'}x)))
    (if (local.get ${'$'}half) (then (local.set ${'$'}r (f64.mul (local.get ${'$'}r) (f64.const 2)))))
    (if (local.get ${'$'}inv) (then (local.set ${'$'}r (f64.sub (f64.const 1.5707963267948966) (local.get ${'$'}r)))))
    (if (result f64) (local.get ${'$'}neg) (then (f64.neg (local.get ${'$'}r))) (else (local.get ${'$'}r))))
  (func ${'$'}__soft_asin (param ${'$'}x f64) (result f64)
    (if (f64.ge (f64.abs (local.get ${'$'}x)) (f64.const 1))
      (then (return (f64.mul (f64.const 1.5707963267948966)
        (if (result f64) (f64.lt (local.get ${'$'}x) (f64.const 0)) (then (f64.const -1)) (else (f64.const 1)))))))
    (call ${'$'}__soft_atan (f64.div (local.get ${'$'}x)
      (f64.sqrt (f64.sub (f64.const 1) (f64.mul (local.get ${'$'}x) (local.get ${'$'}x)))))))
  (func ${'$'}__soft_acos (param ${'$'}x f64) (result f64)
    (f64.sub (f64.const 1.5707963267948966) (call ${'$'}__soft_asin (local.get ${'$'}x))))
  (func ${'$'}__soft_atan2 (param ${'$'}y f64) (param ${'$'}x f64) (result f64)
    (if (f64.gt (local.get ${'$'}x) (f64.const 0))
      (then (return (call ${'$'}__soft_atan (f64.div (local.get ${'$'}y) (local.get ${'$'}x))))))
    (if (f64.lt (local.get ${'$'}x) (f64.const 0))
      (then
        (if (f64.ge (local.get ${'$'}y) (f64.const 0))
          (then (return (f64.add (call ${'$'}__soft_atan (f64.div (local.get ${'$'}y) (local.get ${'$'}x))) (f64.const 3.141592653589793))))
          (else (return (f64.sub (call ${'$'}__soft_atan (f64.div (local.get ${'$'}y) (local.get ${'$'}x))) (f64.const 3.141592653589793)))))))
    ;; x == 0
    (if (f64.gt (local.get ${'$'}y) (f64.const 0)) (then (return (f64.const 1.5707963267948966))))
    (if (f64.lt (local.get ${'$'}y) (f64.const 0)) (then (return (f64.const -1.5707963267948966))))
    (f64.const 0))
  (func ${'$'}__soft_hypot (param ${'$'}x f64) (param ${'$'}y f64) (result f64)
    (local ${'$'}m f64) (local ${'$'}r f64)
    (local.set ${'$'}x (f64.abs (local.get ${'$'}x)))
    (local.set ${'$'}y (f64.abs (local.get ${'$'}y)))
    (local.set ${'$'}m (f64.max (local.get ${'$'}x) (local.get ${'$'}y)))
    (if (f64.eq (local.get ${'$'}m) (f64.const 0)) (then (return (f64.const 0))))
    (local.set ${'$'}r (f64.div (f64.min (local.get ${'$'}x) (local.get ${'$'}y)) (local.get ${'$'}m)))
    (f64.mul (local.get ${'$'}m) (f64.sqrt (f64.add (f64.const 1) (f64.mul (local.get ${'$'}r) (local.get ${'$'}r))))))
  (func ${'$'}__soft_pow (param ${'$'}x f64) (param ${'$'}y f64) (result f64)
    (local ${'$'}n f64) (local ${'$'}odd i32)
    (if (f64.eq (local.get ${'$'}y) (f64.const 0)) (then (return (f64.const 1))))
    (if (f64.eq (local.get ${'$'}x) (f64.const 0)) (then (return (f64.const 0))))
    (if (f64.gt (local.get ${'$'}x) (f64.const 0))
      (then (return (call ${'$'}__soft_exp (f64.mul (local.get ${'$'}y) (call ${'$'}__soft_log (local.get ${'$'}x)))))))
    ;; negative base is real only for an integer exponent; the sign follows its parity
    (local.set ${'$'}n (f64.nearest (local.get ${'$'}y)))
    (if (f64.ne (local.get ${'$'}n) (local.get ${'$'}y))
      (then (return (f64.div (f64.const 0) (f64.const 0)))))
    (local.set ${'$'}odd (i32.and (i32.trunc_f64_s (local.get ${'$'}n)) (i32.const 1)))
    (local.set ${'$'}n (call ${'$'}__soft_exp (f64.mul (local.get ${'$'}y) (call ${'$'}__soft_log (f64.neg (local.get ${'$'}x))))))
    (if (result f64) (local.get ${'$'}odd) (then (f64.neg (local.get ${'$'}n))) (else (local.get ${'$'}n))))
"""


    /**
     * The `std::vha` trigonometry: the same reduction as [RT_TRIG] with the series
     * carried two terms further, to where an `f64` can no longer tell the
     * difference over the reduced interval.
     *
     * `std::sin` stops earlier because the terms it drops are invisible at float
     * precision and cost real time in a loop; `std::vha::sin` pays for them.
     */
    private val RT_VHA_TRIG = """
  (func ${'$'}__vha_sin_poly (param ${'$'}r f64) (result f64)
    (local ${'$'}z f64)
    (local.set ${'$'}z (f64.mul (local.get ${'$'}r) (local.get ${'$'}r)))
    (f64.mul (local.get ${'$'}r) (f64.add (f64.const 1) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.16666666666666666) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.008333333333333333) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.0001984126984126984) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.0000027557319223985893) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.000000025052108385441718) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.00000000016059043836821613) (f64.mul (local.get ${'$'}z) (f64.const -0.0000000000007647163731819816)))))))))))))))))
  (func ${'$'}__vha_cos_poly (param ${'$'}r f64) (result f64)
    (local ${'$'}z f64)
    (local.set ${'$'}z (f64.mul (local.get ${'$'}r) (local.get ${'$'}r)))
    (f64.add (f64.const 1) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.5) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.041666666666666664) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.001388888888888889) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.0000248015873015873) (f64.mul (local.get ${'$'}z) (f64.add (f64.const -0.00000027557319223985893) (f64.mul (local.get ${'$'}z) (f64.add (f64.const 0.0000000020876756987868098) (f64.mul (local.get ${'$'}z) (f64.const -0.000000000011470745597729725))))))))))))))))
  (func ${'$'}__vha_trig (param ${'$'}x f64) (param ${'$'}k i32) (result f64)
    (local ${'$'}n f64) (local ${'$'}r f64) (local ${'$'}q i32)
    (local.set ${'$'}n (f64.nearest (f64.mul (local.get ${'$'}x) (f64.const 0.6366197723675814))))
    (local.set ${'$'}r (f64.sub (local.get ${'$'}x) (f64.mul (local.get ${'$'}n) (f64.const 1.5707963267341256))))
    (local.set ${'$'}r (f64.sub (local.get ${'$'}r) (f64.mul (local.get ${'$'}n) (f64.const 0.0000000000607710050650619224932))))
    (local.set ${'$'}r (f64.sub (local.get ${'$'}r) (f64.mul (local.get ${'$'}n) (f64.const 0.00000000000000000000202226624879595063154))))
    (local.set ${'$'}q (i32.and (i32.add (i32.trunc_f64_s (local.get ${'$'}n)) (local.get ${'$'}k)) (i32.const 3)))
    (if (result f64) (i32.eq (local.get ${'$'}q) (i32.const 0))
      (then (call ${'$'}__vha_sin_poly (local.get ${'$'}r)))
      (else (if (result f64) (i32.eq (local.get ${'$'}q) (i32.const 1))
        (then (call ${'$'}__vha_cos_poly (local.get ${'$'}r)))
        (else (if (result f64) (i32.eq (local.get ${'$'}q) (i32.const 2))
          (then (f64.neg (call ${'$'}__vha_sin_poly (local.get ${'$'}r))))
          (else (f64.neg (call ${'$'}__vha_cos_poly (local.get ${'$'}r))))))))))
  (func ${'$'}__vha_sin (param ${'$'}x f64) (result f64)
    (call ${'$'}__vha_trig (local.get ${'$'}x) (i32.const 0)))
  (func ${'$'}__vha_cos (param ${'$'}x f64) (result f64)
    (call ${'$'}__vha_trig (local.get ${'$'}x) (i32.const 1)))
  (func ${'$'}__vha_tan (param ${'$'}x f64) (result f64)
    (f64.div (call ${'$'}__vha_sin (local.get ${'$'}x)) (call ${'$'}__vha_cos (local.get ${'$'}x))))
"""

}
