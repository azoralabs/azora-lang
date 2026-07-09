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
 * Backend — lowers [IrProgram] to Rust source code.
 *
 * Type mapping:
 *   Int    → i32          Real    → f64           String → String
 *   Bool   → bool         Unit    → ()            Char   → char
 *   Byte   → i8           Long    → i64           Cent   → i128
 *   [T]    → Vec<T>       ![T]    → HashSet<T>     [K:V]  → HashMap<K, V>
 *   (A,B)  → (A, B)       T?      → Option<T>      Task<T>→ T
 *
 * Design notes:
 *  - Every local binding is `let mut` (Azora's own checks enforce immutability),
 *    with an explicit type annotation.
 *  - String concatenation/interpolation lower to `format!`, avoiding the
 *    owned/borrowed juggling of `+`.
 *  - Packs become `struct`s (value types — a documented limitation vs the
 *    reference-typed backends).
 *  - There is no async executor in `std`, so tasks are lowered synchronously:
 *    `Task<T>` erases to `T`, `await` is identity, `async { e }` is a block.
 *  - `throw`/`try` lower to `panic_any` + `catch_unwind`; loop labels use
 *    Rust's native `'label` syntax; `when` lowers to `match`.
 */
class RustCodegen {

    private val out = StringBuilder()
    private var indent = 0
    private var usesError = false

    private val POINTER_RUNTIME = setOf("__alloc", "__deref", "__derefAssign", "__isolated")

    /**
     * Generates Rust source code from the given IR program.
     *
     * @param program the optimized IR program to lower to Rust
     * @return the generated Rust source code as a string
     */
    fun generate(program: IrProgram): String {
        out.clear()
        indent = 0
        usesError = false

        val structs = program.items.filterIsInstance<IrTopLevel.Struct>()
        val funcs = program.items.filterIsInstance<IrTopLevel.Func>().map { it.function }
        val tests = program.items.filterIsInstance<IrTopLevel.Test>()
        val globals = program.items.filterIsInstance<IrTopLevel.Global>().map { it.stmt }

        val body = capture {
            indent = 0
            for (s in structs) { emitStruct(s); line("") }
            for (g in globals) emitGlobal(g)
            if (globals.isNotEmpty()) line("")
            for (f in funcs) { emitFunction(f); line("") }
            for (t in tests) { emitTest(t); line("") }
        }

        val sb = StringBuilder()
        sb.appendLine("#![allow(unused, unused_mut, non_snake_case, unused_variables, dead_code, unused_parens)]")
        sb.appendLine("use std::collections::{HashMap, HashSet};")
        sb.appendLine()
        sb.append(body)
        return sb.toString().trimEnd()
    }

    private fun emitTest(test: IrTopLevel.Test) {
        val safeName = test.name.replace(" ", "_").replace("\"", "")
        line("fn __test_$safeName() {")
        indent++
        for (stmt in test.body) emitStmt(stmt)
        indent--
        line("}")
    }

    private fun emitGlobal(stmt: IrStmt) {
        // Top-level globals become `static` items where possible.
        when (stmt) {
            is IrStmt.VarDecl -> line("static mut ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.FinDecl -> line("static ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.LetDecl -> line("static ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)};")
            else -> {}
        }
    }

    private fun emitStruct(struct: IrTopLevel.Struct) {
        line("#[derive(Clone)]")
        line("struct ${struct.name} {")
        indent++
        for (f in struct.fields) line("${f.name}: ${mapType(f.type)},")
        indent--
        line("}")
    }

    private fun emitFunction(func: IrFunction) {
        val params = func.params.joinToString(", ") { (name, type) -> "$name: ${mapType(type)}" }
        // Tasks lower synchronously; `returnType` is already the inner result type.
        val ret = if (func.returnType == IrType.Unit) "" else " -> ${mapType(func.returnType)}"
        if (func.name == "main" && func.returnType != IrType.Unit && !func.isTask) {
            line("fn __azora_main($params)$ret {")
            indent++
            for (stmt in func.body) emitStmt(stmt)
            indent--
            line("}")
            line("")
            line("fn main() {")
            indent++
            line("__azora_main();")
            indent--
            line("}")
        } else {
            line("fn ${func.name}($params)$ret {")
            indent++
            for (stmt in func.body) emitStmt(stmt)
            indent--
            line("}")
        }
    }

    private fun emitStmt(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> line("let mut ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.FinDecl -> line("let mut ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.LetDecl -> line("let mut ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.Assignment -> line("${stmt.name} = ${emitExpr(stmt.value)};")
            is IrStmt.IndexAssign -> {
                if (stmt.target.type is IrType.Map) {
                    line("${emitExpr(stmt.target)}.insert(${emitExpr(stmt.index)}, ${emitExpr(stmt.value)});")
                } else {
                    line("${emitExpr(stmt.target)}[(${emitExpr(stmt.index)}) as usize] = ${emitExpr(stmt.value)};")
                }
            }
            is IrStmt.MemberAssign -> line("${emitExpr(stmt.target)}.${stmt.name} = ${emitExpr(stmt.value)};")
            is IrStmt.When -> {
                val scrut = if (stmt.scrutinee.type == IrType.String) "${emitExpr(stmt.scrutinee)}.as_str()" else emitExpr(stmt.scrutinee)
                line("match $scrut {")
                indent++
                for (b in stmt.branches) {
                    val pats = b.patterns.joinToString(" | ") { emitPattern(it) }
                    line("$pats => {")
                    indent++
                    for (s in b.body) emitStmt(s)
                    indent--
                    line("}")
                }
                if (stmt.elseBranch != null) {
                    line("_ => {")
                    indent++
                    for (s in stmt.elseBranch) emitStmt(s)
                    indent--
                    line("}")
                } else {
                    line("_ => {}")
                }
                indent--
                line("}")
            }
            is IrStmt.Return -> {
                if (stmt.value != null) line("return ${emitExpr(stmt.value)};")
                else line("return;")
            }
            is IrStmt.ExprStmt -> line("${emitExpr(stmt.expr)};")
            is IrStmt.Zone -> {
                line("{")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Assert -> line("if !(${emitExpr(stmt.condition)}) { panic!(\"{}\", ${emitExpr(stmt.message)}); }")
            is IrStmt.Trace -> line("println!(\"[TRACE] {}\", ${emitExpr(stmt.message)});")
            is IrStmt.If -> {
                line("if ${emitExpr(stmt.condition)} {")
                indent++
                for (s in stmt.thenBranch) emitStmt(s)
                indent--
                if (stmt.elseBranch != null) {
                    if (stmt.elseBranch.size == 1 && stmt.elseBranch[0] is IrStmt.If) {
                        val ei = stmt.elseBranch[0] as IrStmt.If
                        line("} else if ${emitExpr(ei.condition)} {")
                        indent++
                        for (s in ei.thenBranch) emitStmt(s)
                        indent--
                        if (ei.elseBranch != null) {
                            line("} else {")
                            indent++
                            for (s in ei.elseBranch) emitStmt(s)
                            indent--
                        }
                        line("}")
                    } else {
                        line("} else {")
                        indent++
                        for (s in stmt.elseBranch) emitStmt(s)
                        indent--
                        line("}")
                    }
                } else {
                    line("}")
                }
            }
            is IrStmt.While -> {
                val lbl = if (stmt.label != null) "'${stmt.label}: " else ""
                line("${lbl}while ${emitExpr(stmt.condition)} {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.For -> {
                val lbl = if (stmt.label != null) "'${stmt.label}: " else ""
                val range = buildRange(stmt)
                line("${lbl}for ${stmt.counter} in $range {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.ForEach -> {
                line("for ${stmt.elem} in ${emitExpr(stmt.iterable)}.clone() {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Loop -> {
                val lbl = if (stmt.label != null) "'${stmt.label}: " else ""
                line("${lbl}loop {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Throw -> {
                usesError = true
                line("std::panic::panic_any(${emitExpr(stmt.value)});")
            }
            is IrStmt.Try -> {
                usesError = true
                line("let __res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}));")
                line("if let Err(__e) = __res {")
                indent++
                if (stmt.catchName != null) {
                    line("let ${stmt.catchName} = __e.downcast_ref::<String>().cloned().unwrap_or_default();")
                }
                if (stmt.catchBody != null) for (s in stmt.catchBody) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Defer -> {}
            is IrStmt.Yield -> {}
            is IrStmt.Break -> line(if (stmt.label != null) "break '${stmt.label};" else "break;")
            is IrStmt.Continue -> line(if (stmt.label != null) "continue '${stmt.label};" else "continue;")
        }
    }

    private fun buildRange(stmt: IrStmt.For): String {
        val start = emitExpr(stmt.start)
        val end = emitExpr(stmt.end)
        val step = stmt.step?.let { emitExpr(it) }
        return when {
            stmt.reverse -> {
                val base = "($start..=$end).rev()"
                if (step != null) "$base.step_by(($step) as usize)" else base
            }
            step != null -> {
                val base = if (stmt.inclusive) "($start..=$end)" else "($start..$end)"
                "$base.step_by(($step) as usize)"
            }
            stmt.inclusive -> "$start..=$end"
            else -> "$start..$end"
        }
    }

    /** A `match` pattern — string literals become raw `&str` patterns (not owned `String`s). */
    private fun emitPattern(expr: IrExpr): String = when (expr) {
        is IrExpr.StringLiteral -> "\"${escapeString(expr.value)}\""
        else -> emitExpr(expr)
    }

    private fun emitExpr(expr: IrExpr): String = when (expr) {
        is IrExpr.IntLiteral -> "${expr.value}"
        is IrExpr.RealLiteral -> "${expr.value}"
        is IrExpr.StringLiteral -> "\"${escapeString(expr.value)}\".to_string()"
        is IrExpr.BoolLiteral -> "${expr.value}"
        is IrExpr.CharLiteral -> "'${escapeChar(expr.value)}'"
        is IrExpr.Var -> expr.name
        is IrExpr.Unary -> when (expr.op) {
            IrUnaryOp.NEG -> "(-${emitExpr(expr.operand)})"
            IrUnaryOp.NOT -> "(!${emitExpr(expr.operand)})"
            IrUnaryOp.BIT_NOT -> "(!${emitExpr(expr.operand)})"
        }
        is IrExpr.Binary -> {
            val l = expr.left
            val r = expr.right
            when {
                expr.op == IrBinaryOp.ADD && expr.type == IrType.String ->
                    "format!(\"{}{}\", ${emitExpr(l)}, ${emitExpr(r)})"
                expr.op == IrBinaryOp.MUL && l.type == IrType.String && r.type == IrType.Int ->
                    "(${emitExpr(l)}).repeat((${emitExpr(r)}) as usize)"
                expr.op == IrBinaryOp.MUL && l.type == IrType.Int && r.type == IrType.String ->
                    "(${emitExpr(r)}).repeat((${emitExpr(l)}) as usize)"
                else -> {
                    val op = when (expr.op) {
                        IrBinaryOp.ADD -> "+"; IrBinaryOp.SUB -> "-"; IrBinaryOp.MUL -> "*"
                        IrBinaryOp.DIV -> "/"; IrBinaryOp.MOD -> "%"
                        IrBinaryOp.EQ -> "=="; IrBinaryOp.NEQ -> "!="
                        IrBinaryOp.LT -> "<"; IrBinaryOp.LTE -> "<="
                        IrBinaryOp.GT -> ">"; IrBinaryOp.GTE -> ">="
                        IrBinaryOp.AND -> "&&"; IrBinaryOp.OR -> "||"
                        IrBinaryOp.BIT_AND -> "&"; IrBinaryOp.BIT_OR -> "|"; IrBinaryOp.BIT_XOR -> "^"
                        IrBinaryOp.SHL -> "<<"; IrBinaryOp.SHR -> ">>"
                    }
                    "(${emitExpr(l)} $op ${emitExpr(r)})"
                }
            }
        }
        is IrExpr.Call -> when {
            expr.name == "async" && expr.args.singleOrNull() is IrExpr.Lambda ->
                emitAsyncBlock(expr.args.single() as IrExpr.Lambda)
            expr.name == "cancel" && expr.args.size == 1 -> "()"
            // Tasks are synchronous: a task-returning call just yields its value.
            expr.type is IrType.Task -> "${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
            expr.name == "println" && expr.args.size == 1 -> "println!(\"{}\", ${emitExpr(expr.args.single())})"
            expr.name == "print" && expr.args.size == 1 -> "print!(\"{}\", ${emitExpr(expr.args.single())})"
            else -> "${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        }
        is IrExpr.Await -> emitExpr(expr.value) // synchronous tasks — await is identity
        is IrExpr.Spread -> emitExpr(expr.array)
        is IrExpr.ArrayLiteral -> "vec![${expr.elements.joinToString(", ") { emitExpr(it) }}]"
        is IrExpr.SetLit ->
            if (expr.elements.isEmpty()) "HashSet::new()"
            else "HashSet::from([${expr.elements.joinToString(", ") { emitExpr(it) }}])"
        is IrExpr.MapLit ->
            if (expr.entries.isEmpty()) "HashMap::new()"
            else "HashMap::from([${expr.entries.joinToString(", ") { "(${emitExpr(it.first)}, ${emitExpr(it.second)})" }}])"
        is IrExpr.Index ->
            if (expr.target.type is IrType.Map) "${emitExpr(expr.target)}[&${emitExpr(expr.index)}]"
            else "${emitExpr(expr.target)}[(${emitExpr(expr.index)}) as usize]"
        is IrExpr.Member -> when (expr.name) {
            "length" -> "${emitExpr(expr.target)}.len()"
            "isEmpty" -> "${emitExpr(expr.target)}.is_empty()"
            "isNotEmpty" -> "(!${emitExpr(expr.target)}.is_empty())"
            else -> "${emitExpr(expr.target)}.${expr.name}"
        }
        is IrExpr.MethodCall -> when (expr.name) {
            "add" -> "${emitExpr(expr.target)}.push(${expr.args.joinToString(", ") { emitExpr(it) }})"
            "isEmpty" -> "${emitExpr(expr.target)}.is_empty()"
            "isNotEmpty" -> "(!${emitExpr(expr.target)}.is_empty())"
            else -> "${emitExpr(expr.target)}.${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        }
        is IrExpr.StructCtor -> {
            val fields = expr.fieldNames.zip(expr.args).joinToString(", ") { (n, a) -> "$n: ${emitExpr(a)}" }
            "${expr.name} { $fields }"
        }
        is IrExpr.TupleLit -> "(${expr.elements.joinToString(", ") { emitExpr(it) }}${if (expr.elements.size == 1) "," else ""})"
        is IrExpr.TupleAccess -> "${emitExpr(expr.target)}.${expr.index}"
        is IrExpr.CatchExpr -> {
            usesError = true
            "std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| ${emitExpr(expr.expr)})).unwrap_or_else(|_| ${emitExpr(expr.fallback)})"
        }
        is IrExpr.IfExpr -> "(if ${emitExpr(expr.condition)} { ${emitExpr(expr.thenExpr)} } else { ${emitExpr(expr.elseExpr)} })"
        is IrExpr.NumCast -> emitNumCast(expr)
        is IrExpr.SlotPattern -> "" /* handled by when lowering */
        is IrExpr.Lambda -> emitLambda(expr)
        is IrExpr.StringTemplate -> {
            val fmt = StringBuilder()
            val args = mutableListOf<String>()
            for (part in expr.parts) {
                when (part) {
                    is IrExpr.IrTemplatePart.Literal -> fmt.append(escapeString(part.text).replace("{", "{{").replace("}", "}}"))
                    is IrExpr.IrTemplatePart.Expr -> { fmt.append("{}"); args.add(emitExpr(part.expr)) }
                }
            }
            val argList = if (args.isEmpty()) "" else ", ${args.joinToString(", ")}"
            "format!(\"$fmt\"$argList)"
        }
    }

    /** `async { body }` lowers to a plain block expression (tasks are synchronous). */
    private fun emitAsyncBlock(expr: IrExpr.Lambda): String {
        val ret = expr.body.singleOrNull() as? IrStmt.Return
        if (ret?.value != null) return "(${emitExpr(ret.value)})"
        val body = capture {
            indent++
            for (s in expr.body) emitStmt(s)
            indent--
        }
        val pad = "    ".repeat(indent)
        return "{\n$body$pad}"
    }

    private fun emitLambda(expr: IrExpr.Lambda): String {
        val ps = expr.params.joinToString(", ") { (n, _) -> n }
        val ret = expr.body.singleOrNull() as? IrStmt.Return
        if (ret?.value != null) return "Box::new(|$ps| ${emitExpr(ret.value)})"
        val body = capture {
            indent++
            for (s in expr.body) emitStmt(s)
            indent--
        }
        val pad = "    ".repeat(indent)
        return "Box::new(|$ps| {\n$body$pad})"
    }

    private fun emitNumCast(expr: IrExpr.NumCast): String {
        val src = expr.value.type
        val sourceNumeric = src in IrType.integerTypes || src in IrType.floatTypes || src == IrType.Char
        if (!sourceNumeric) return emitExpr(expr.value)
        val v = emitExpr(expr.value)
        if (expr.type == IrType.Char) {
            return if (src == IrType.Char) v else "char::from_u32(($v) as u32).unwrap()"
        }
        if (src == IrType.Char) return "(($v) as u32) as ${mapType(expr.type)}"
        return "(($v) as ${mapType(expr.type)})"
    }

    private fun mapType(type: IrType): String = when (type) {
        IrType.Int -> "i32"
        IrType.UInt -> "u32"
        IrType.Real -> "f64"
        IrType.String -> "String"
        IrType.Bool -> "bool"
        IrType.Unit -> "()"
        IrType.Char -> "char"
        IrType.Byte -> "i8"
        IrType.UByte -> "u8"
        IrType.Short -> "i16"
        IrType.UShort -> "u16"
        IrType.Long -> "i64"
        IrType.ULong -> "u64"
        IrType.Cent -> "i128"
        IrType.UCent -> "u128"
        IrType.Float -> "f32"
        IrType.Decimal -> "f64"
        is IrType.Array -> "Vec<${mapType(type.element)}>"
        is IrType.Set -> "HashSet<${mapType(type.element)}>"
        is IrType.Map -> "HashMap<${mapType(type.key)}, ${mapType(type.value)}>"
        is IrType.Pointer -> "std::rc::Rc<std::cell::RefCell<${mapType(type.inner)}>>"
        is IrType.Function -> "Box<dyn Fn(${type.params.joinToString(", ") { mapType(it) }}) -> ${mapType(type.ret)}>"
        is IrType.Task -> mapType(type.result) // synchronous tasks erase the wrapper
        is IrType.Tuple -> "(${type.elements.joinToString(", ") { mapType(it) }})"
        is IrType.Nullable -> "Option<${mapType(type.inner)}>"
        is IrType.Named -> type.name
        IrType.Any -> "Box<dyn std::any::Any>"
    }

    private fun escapeString(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            .replace("\t", "\\t").replace("\r", "\\r")

    private fun escapeChar(c: Char): String = when (c) {
        '\n' -> "\\n"; '\t' -> "\\t"; '\r' -> "\\r"; '\\' -> "\\\\"; '\'' -> "\\'"
        else -> if (c.code in 32..126) "$c" else "\\u{${c.code.toString(16)}}"
    }

    /** Emits [block] into a scratch region of [out] and returns (and removes) the produced text. */
    private fun capture(block: () -> Unit): String {
        val start = out.length
        block()
        val text = out.substring(start)
        out.setLength(start)
        return text
    }

    private fun line(text: String) {
        if (text.isEmpty()) {
            out.appendLine()
        } else {
            repeat(indent) { out.append("    ") }
            out.appendLine(text)
        }
    }
}
