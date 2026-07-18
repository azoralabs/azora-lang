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
 * Backend — lowers [IrProgram] to WebAssembly text format (WAT), using the
 * folded S-expression syntax.
 *
 * Value representation (single WASM value per Azora value):
 *  - `Int`/`Bool`/`Char`/sized ints ≤ 32-bit → `i32`
 *  - `Long`/`ULong`/`Cent`/`UCent` → `i64`     `Real`/`Decimal` → `f64`   `Float` → `f32`
 *  - `String`/`arr[T]`/pack → `i32` pointer into linear memory. Strings and arrays
 *    are laid out as `[len: i32][payload…]`; packs as packed `i32` fields.
 *
 * Printing and string handling go through host imports (`print_i32`, `print_str`,
 * …) and a small linear-memory runtime (`__alloc`, `__str_concat`, `__str_eq`,
 * `__str_repeat`, `__int_to_str`). Structured control flow lowers to
 * `block`/`loop`/`br_if`.
 *
 * NOTE: this is an MVP-level target — packs/arrays assume 4-byte (`i32`) fields
 * and elements; exceptions lower to `unreachable`; tasks are synchronous.
 */
class WasmCodegen {

    private val out = StringBuilder()
    private var indent = 0

    // Per-function state.
    private val locals = LinkedHashMap<String, String>() // name -> wasm type
    private var params = emptySet<String>()
    private var tempCounter = 0
    private var blockCounter = 0
    private val loopStack = ArrayDeque<Pair<String, String>>() // (breakLabel, continueLabel)
    private val labelTargets = HashMap<String, Pair<String, String>>()

    // Module state.
    private val structs = HashMap<String, List<IrField>>()
    private val stringConsts = LinkedHashMap<String, Int>() // literal -> offset
    private var constCursor = STRING_BASE

    private var usesAlloc = false
    private var usesConcat = false
    private var usesStrEq = false
    private var usesRepeat = false
    private var usesIntToStr = false

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
        structs.clear(); stringConsts.clear(); constCursor = STRING_BASE
        usesAlloc = false; usesConcat = false; usesStrEq = false; usesRepeat = false; usesIntToStr = false

        for (item in program.items) if (item is IrTopLevel.Struct) structs[item.name] = item.fields

        val funcs = program.items.filterIsInstance<IrTopLevel.Func>().map { it.function }
            .filter { it.name !in org.azora.lang.semantic.CtfeEvaluator.RUNTIME_INTRINSICS }

        // Emit function bodies first (interns strings, sets runtime flags).
        val funcText = StringBuilder()
        for (f in funcs) funcText.append(emitFunction(f))

        val sb = StringBuilder()
        sb.appendLine("(module")
        sb.appendLine("  (import \"env\" \"print_i32\" (func \$print_i32 (param i32)))")
        sb.appendLine("  (import \"env\" \"print_i64\" (func \$print_i64 (param i64)))")
        sb.appendLine("  (import \"env\" \"print_f64\" (func \$print_f64 (param f64)))")
        sb.appendLine("  (import \"env\" \"print_f32\" (func \$print_f32 (param f32)))")
        sb.appendLine("  (import \"env\" \"print_bool\" (func \$print_bool (param i32)))")
        sb.appendLine("  (import \"env\" \"print_str\" (func \$print_str (param i32)))")
        sb.appendLine("  (memory (export \"memory\") 16)")
        sb.appendLine("  (global \$__heap (mut i32) (i32.const ${align4(constCursor)}))")
        if (usesAlloc) sb.append(RT_ALLOC)
        if (usesConcat) sb.append(RT_CONCAT)
        if (usesStrEq) sb.append(RT_STR_EQ)
        if (usesRepeat) sb.append(RT_REPEAT)
        if (usesIntToStr) sb.append(RT_INT_TO_STR)
        sb.append(funcText)
        for ((literal, offset) in stringConsts) {
            sb.appendLine("  (data (i32.const $offset) \"${dataBytes(literal)}\")")
        }
        for (f in funcs) sb.appendLine("  (export \"${f.name}\" (func \$${f.name}))")
        sb.appendLine(")")
        return sb.toString().trimEnd()
    }

    private fun emitFunction(func: IrFunction): String {
        locals.clear(); tempCounter = 0; blockCounter = 0
        loopStack.clear(); labelTargets.clear()
        params = func.params.map { it.first }.toSet()

        out.clear(); indent = 2
        for (stmt in func.body) emitStmt(stmt)
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

    // ── Statements ────────────────────────────────────────────────────────

    private fun emitStmt(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> { declareLocal(stmt.name, stmt.type); line("(local.set \$${stmt.name} ${emitExpr(stmt.initializer)})") }
            is IrStmt.FinDecl -> { declareLocal(stmt.name, stmt.type); line("(local.set \$${stmt.name} ${emitExpr(stmt.initializer)})") }
            is IrStmt.LetDecl -> { declareLocal(stmt.name, stmt.type); line("(local.set \$${stmt.name} ${emitExpr(stmt.initializer)})") }
            is IrStmt.Assignment -> line("(local.set \$${stmt.name} ${emitExpr(stmt.value)})")
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
            is IrStmt.Zone -> for (s in stmt.body) emitStmt(s)
            is IrStmt.Assert -> line("(if (i32.eqz ${emitExpr(stmt.condition)}) (then unreachable))")
            is IrStmt.Trace -> if (stmt.message.type == IrType.String) line("(call \$print_str ${emitExpr(stmt.message)})")
            is IrStmt.Throw -> line("unreachable")
            is IrStmt.Try -> for (s in stmt.body) emitStmt(s) // no exception support — run the body
            is IrStmt.Defer -> {}
            is IrStmt.Yield -> {}
        }
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

    private fun emitWhen(stmt: IrStmt.When) {
        val tmp = newTemp(wasmType(stmt.scrutinee.type))
        line("(local.set $tmp ${emitExpr(stmt.scrutinee)})")
        var depth = 0
        for ((i, b) in stmt.branches.withIndex()) {
            val cond = b.patterns.map { "(i32.eq (local.get $tmp) ${emitExpr(it)})" }
                .reduce { a, c -> "(i32.or $a $c)" }
            line("(if $cond")
            indent++
            line("(then")
            indent++
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
        val brk = "brk_$n"; val cont = "cont_$n"
        if (label != null) labelTargets[label] = brk to cont
        loopStack.addLast(brk to cont)
        line("(block \$$brk")
        indent++
        line("(loop \$$cont")
        indent++
        line("(br_if \$$brk (i32.eqz $cond))")
        for (s in body) emitStmt(s)
        forInc?.invoke()
        line("(br \$$cont)")
        indent--
        line(")")
        indent--
        line(")")
        loopStack.removeLast()
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
        is IrExpr.RealLiteral -> "(${wasmType(expr.type)}.const ${expr.value})"
        is IrExpr.BoolLiteral -> "(i32.const ${if (expr.value) 1 else 0})"
        is IrExpr.CharLiteral -> "(i32.const ${expr.value.code})"
        is IrExpr.StringLiteral -> "(i32.const ${internString(expr.value)})"
        is IrExpr.Var -> "(local.get \$${expr.name})"
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
        is IrExpr.CatchExpr -> emitExpr(expr.expr) // no exception support — evaluate the primary expression
        is IrExpr.SetLit, is IrExpr.MapLit, is IrExpr.TupleLit, is IrExpr.TupleAccess,
        is IrExpr.VariantLit, is IrExpr.Lambda, is IrExpr.SlotPattern -> "(i32.const 0)" // unsupported by the MVP target
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
        val p = numPrefix(l.type)
        val u = isUnsigned(l.type)
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
        return "($instr ${emitExpr(l)} ${emitExpr(r)})"
    }

    private fun emitCall(expr: IrExpr.Call): String {
        if ((expr.name == "std__println" || expr.name == "print") && expr.args.size == 1) {
            val arg = expr.args.single()
            val fn = when {
                arg.type == IrType.String -> "print_str"
                arg.type == IrType.Bool -> "print_bool"
                wasmType(arg.type) == "i64" -> "print_i64"
                wasmType(arg.type) == "f64" -> "print_f64"
                wasmType(arg.type) == "f32" -> "print_f32"
                else -> "print_i32"
            }
            return "(call \$$fn ${emitExpr(arg)})"
        }
        val args = expr.args.joinToString(" ") { emitExpr(it) }
        return "(call \$${expr.name}${if (args.isEmpty()) "" else " $args"})"
    }

    private fun emitStructCtor(expr: IrExpr.StructCtor): String {
        usesAlloc = true
        val t = newTemp("i32")
        val sb = StringBuilder("(block (result i32)\n")
        val pad = "  ".repeat(indent + 1)
        sb.append("$pad(local.set $t (call \$__alloc (i32.const ${expr.args.size * 4})))\n")
        for ((i, a) in expr.args.withIndex()) {
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
        else -> "(i32.const ${internString("")})"
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

    /** Address of `target.field` — `ptr + fieldIndex*4`. */
    private fun fieldAddr(target: IrExpr, field: String): String {
        val structName = (target.type as? IrType.Named)?.name
        val idx = structName?.let { structs[it]?.indexOfFirst { f -> f.name == field } } ?: 0
        return "(i32.add ${emitExpr(target)} (i32.const ${idx * 4}))"
    }

    // ── Locals / temps ────────────────────────────────────────────────────

    private fun declareLocal(name: String, type: IrType) { if (name !in params) locals[name] = wasmType(type) }
    private fun newTemp(type: String): String { val n = "\$__t${tempCounter++}"; locals[n.substring(1)] = type; return n }

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

    private fun wasmType(type: IrType): String = when (type) {
        IrType.Long, IrType.ULong, IrType.Cent, IrType.UCent -> "i64"
        IrType.Real, IrType.Decimal -> "f64"
        IrType.Float -> "f32"
        is IrType.Task -> wasmType(type.result)
        else -> "i32"
    }

    /** Numeric instruction prefix for arithmetic on values of [type]. */
    private fun numPrefix(type: IrType): String = wasmType(type)

    private fun isUnsigned(type: IrType): Boolean =
        type == IrType.UInt || type == IrType.UByte || type == IrType.UShort ||
            type == IrType.ULong || type == IrType.UCent || type == IrType.Char

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
}
