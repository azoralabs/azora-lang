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
 * Backend — lowers [IrProgram] to Dart source code.
 *
 * Type mapping:
 *   Int    → int          Real    → double        String → String
 *   Bool   → bool         Unit    → void          Char   → String
 *   [T]    → List<T>      ![T]    → Set<T>         [K:V]  → Map<K, V>
 *   (A,B)  → (A, B)       T?      → T?             Task<T>→ Future<T>
 *
 * Dart's `int` is a single 64-bit type, so every Azora integer width lowers to
 * `int` (a documented limitation, analogous to the other backends' widest-type
 * fallbacks). `println` maps to `print`; `fin`/`let` map to `final`.
 *
 * Integer division uses Dart's truncating `~/` (plain `/` always yields a
 * double); modulo uses `.remainder()` so the sign follows the dividend, matching
 * the interpreter. Dart's `main` is the entry point, so no trailing call is
 * appended (unlike the other script-style backends).
 */
class DartCodegen {

    private val out = StringBuilder()
    private var indent = 0
    private var usesPointers = false
    private var usesTasks = false

    private val POINTER_RUNTIME = setOf("__alloc", "__deref", "__derefAssign", "__isolated")

    private val pointerPreamble: String = """
        class AzoraPtr<T> { T value; AzoraPtr(this.value); }
        AzoraPtr<T> __alloc<T>(T v) => AzoraPtr<T>(v);
        T __deref<T>(AzoraPtr<T> p) => p.value;
        void __derefAssign<T>(AzoraPtr<T> p, T v) { p.value = v; }
        // NOTE: __isolated is a shallow copy in the Dart backend.
        T __isolated<T>(T v) => v;
    """.trimIndent()

    private val taskPreamble: String = """
        final Set<Future<dynamic>> __azoraChildren = {};
        Future<T> __azoraSpawn<T>(Future<T> Function() body) {
          // Defer to a microtask so a spawned child runs after the current
          // synchronous code (matching the interpreter's scheduling).
          final task = Future.microtask(body);
          __azoraChildren.add(task);
          task.then((_) => __azoraChildren.remove(task),
              onError: (_) => __azoraChildren.remove(task));
          return task;
        }
        Future<void> __azoraDrainChildren() async {
          while (__azoraChildren.isNotEmpty) {
            await Future.wait(__azoraChildren.toList());
          }
        }
        void cancel<T>(Future<T> task) {}
    """.trimIndent()

    /**
     * Generates Dart source code from the given IR program.
     *
     * @param program the optimized IR program to lower to Dart
     * @return the generated Dart source code as a string
     */
    fun generate(program: IrProgram): String {
        out.clear()
        indent = 0
        usesPointers = false
        usesTasks = program.functions.any { it.isTask }

        if (program.packageName != null) {
            line("// package: ${program.packageName}")
            line("")
        }

        for ((i, item) in program.items.withIndex()) {
            when (item) {
                is IrTopLevel.Global -> emitStmt(item.stmt)
                is IrTopLevel.Func -> {
                    emitFunction(item.function)
                    if (i < program.items.lastIndex) line("")
                }
                is IrTopLevel.Test -> {
                    emitTest(item)
                    if (i < program.items.lastIndex) line("")
                }
                is IrTopLevel.Struct -> {
                    emitStruct(item)
                    if (i < program.items.lastIndex) line("")
                }
                is IrTopLevel.Extern -> {
                    val params = item.params.joinToString(", ") { (n, t) -> "${mapType(t)} $n" }
                    line("external ${mapType(item.returnType)} ${item.name}($params);")
                    if (i < program.items.lastIndex) line("")
                }
            }
        }

        val body = out.toString().trimEnd()
        val preambles = buildList {
            if (usesPointers) add(pointerPreamble)
            if (usesTasks) add(taskPreamble)
        }
        return if (preambles.isEmpty()) body
        else preambles.joinToString("\n\n") + "\n\n" + body
    }

    private fun emitTest(test: IrTopLevel.Test) {
        val safeName = test.name.replace(" ", "_").replace("\"", "")
        line("void __test_$safeName() {")
        indent++
        for (stmt in test.body) emitStmt(stmt)
        indent--
        line("}")
    }

    private fun emitStruct(struct: IrTopLevel.Struct) {
        line("class ${struct.name} {")
        indent++
        for (f in struct.fields) {
            val kw = if (f.mutable) "" else "final "
            line("$kw${mapType(f.type)} ${f.name};")
        }
        val params = struct.fields.joinToString(", ") { "this.${it.name}" }
        line("${struct.name}($params);")
        indent--
        line("}")
    }

    private fun emitFunction(func: IrFunction) {
        val params = func.params.joinToString(", ") { (name, type) ->
            "${mapType(type)} $name"
        }
        if (func.isTask) {
            val ret = "Future<${mapType(func.returnType)}>"
            line("$ret ${func.name}($params) async {")
            indent++
            for (stmt in func.body) emitStmt(stmt)
            // The Dart entry point joins any unawaited spawned children before exit.
            if (func.name == "main") line("await __azoraDrainChildren();")
            indent--
            line("}")
            return
        }
        // Dart's entry point must be `void main()`. Wrap a value-returning main.
        if (func.name == "main" && func.returnType != IrType.Unit) {
            line("${mapType(func.returnType)} __azora_main($params) {")
            indent++
            for (stmt in func.body) emitStmt(stmt)
            indent--
            line("}")
            line("")
            line("void main() {")
            indent++
            line("__azora_main();")
            indent--
            line("}")
        } else {
            line("${mapType(func.returnType)} ${func.name}($params) {")
            indent++
            for (stmt in func.body) emitStmt(stmt)
            indent--
            line("}")
        }
    }

    private fun emitStmt(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> line("${mapType(stmt.type)} ${stmt.name} = ${emitExpr(stmt.initializer)};")
            is IrStmt.FinDecl -> line("final ${mapType(stmt.type)} ${stmt.name} = ${emitExpr(stmt.initializer)};")
            is IrStmt.LetDecl -> line("final ${mapType(stmt.type)} ${stmt.name} = ${emitExpr(stmt.initializer)};")
            is IrStmt.Assignment -> line("${stmt.name} = ${emitExpr(stmt.value)};")
            is IrStmt.IndexAssign -> line("${emitExpr(stmt.target)}[${emitExpr(stmt.index)}] = ${emitExpr(stmt.value)};")
            is IrStmt.MemberAssign -> line("${emitExpr(stmt.target)}.${stmt.name} = ${emitExpr(stmt.value)};")
            is IrStmt.When -> {
                line("switch (${emitExpr(stmt.scrutinee)}) {")
                indent++
                for (b in stmt.branches) {
                    for ((i, p) in b.patterns.withIndex()) {
                        if (i == b.patterns.lastIndex) line("case ${emitExpr(p)}:")
                        else line("case ${emitExpr(p)}:")
                    }
                    // Wrap the body in a block so `final` declarations in different
                    // branches don't collide in the shared switch scope.
                    line("{")
                    indent++
                    for (s in b.body) emitStmt(s)
                    indent--
                    line("}")
                    line("break;")
                }
                if (stmt.elseBranch != null) {
                    line("default:")
                    line("{")
                    indent++
                    for (s in stmt.elseBranch) emitStmt(s)
                    indent--
                    line("}")
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
            is IrStmt.Assert -> line("if (!(${emitExpr(stmt.condition)})) { throw StateError(${emitExpr(stmt.message)}); }")
            is IrStmt.Trace -> line("print(\"[TRACE] \" + (${emitExpr(stmt.message)}).toString());")
            is IrStmt.If -> {
                line("if (${emitExpr(stmt.condition)}) {")
                indent++
                for (s in stmt.thenBranch) emitStmt(s)
                indent--
                if (stmt.elseBranch != null) {
                    if (stmt.elseBranch.size == 1 && stmt.elseBranch[0] is IrStmt.If) {
                        val elseIf = stmt.elseBranch[0] as IrStmt.If
                        line("} else if (${emitExpr(elseIf.condition)}) {")
                        indent++
                        for (s in elseIf.thenBranch) emitStmt(s)
                        indent--
                        if (elseIf.elseBranch != null) {
                            line("} else {")
                            indent++
                            for (s in elseIf.elseBranch) emitStmt(s)
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
                val lbl = if (stmt.label != null) "${stmt.label}: " else ""
                line("${lbl}while (${emitExpr(stmt.condition)}) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.For -> {
                val lbl = if (stmt.label != null) "${stmt.label}: " else ""
                val inc = when {
                    stmt.reverse && stmt.step != null -> "${stmt.counter} -= ${emitExpr(stmt.step)}"
                    stmt.reverse -> "${stmt.counter}--"
                    stmt.step != null -> "${stmt.counter} += ${emitExpr(stmt.step)}"
                    else -> "${stmt.counter}++"
                }
                val header = if (stmt.reverse) {
                    "for (int ${stmt.counter} = ${emitExpr(stmt.end)}; ${stmt.counter} >= ${emitExpr(stmt.start)}; $inc)"
                } else {
                    val op = if (stmt.inclusive) "<=" else "<"
                    "for (int ${stmt.counter} = ${emitExpr(stmt.start)}; ${stmt.counter} $op ${emitExpr(stmt.end)}; $inc)"
                }
                line("$lbl$header {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.ForEach -> {
                line("for (final ${stmt.elem} in ${emitExpr(stmt.iterable)}) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Loop -> {
                val lbl = if (stmt.label != null) "${stmt.label}: " else ""
                line("${lbl}while (true) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Throw -> line("throw ${emitExpr(stmt.value)};")
            is IrStmt.Try -> {
                line("try {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                if (stmt.catchBody != null) {
                    val bind = stmt.catchName ?: "_"
                    line("} catch ($bind) {")
                    indent++
                    for (s in stmt.catchBody) emitStmt(s)
                    indent--
                    line("}")
                } else {
                    line("} catch (_) {")
                    line("}")
                }
            }
            is IrStmt.Defer -> {}
            is IrStmt.Yield -> {}
            is IrStmt.Break -> line(if (stmt.label != null) "break ${stmt.label};" else "break;")
            is IrStmt.Continue -> line(if (stmt.label != null) "continue ${stmt.label};" else "continue;")
        }
    }

    private fun emitExpr(expr: IrExpr): String = when (expr) {
        is IrExpr.IntLiteral -> "${expr.value}"
        is IrExpr.RealLiteral -> "${expr.value}"
        is IrExpr.StringLiteral -> "\"${escapeString(expr.value)}\""
        is IrExpr.BoolLiteral -> "${expr.value}"
        is IrExpr.CharLiteral -> "\"${escapeString(expr.value.toString())}\""
        is IrExpr.Var -> expr.name
        is IrExpr.Unary -> when (expr.op) {
            IrUnaryOp.NEG -> "(-${emitExpr(expr.operand)})"
            IrUnaryOp.NOT -> "(!${emitExpr(expr.operand)})"
            IrUnaryOp.BIT_NOT -> "(~${emitExpr(expr.operand)})"
        }
        is IrExpr.Binary -> {
            val l = expr.left
            val r = expr.right
            when {
                // String * Int → Dart's String.operator*(int) repeats the string.
                expr.op == IrBinaryOp.MUL && l.type == IrType.String && r.type == IrType.Int ->
                    "(${emitExpr(l)} * ${emitExpr(r)})"
                expr.op == IrBinaryOp.MUL && l.type == IrType.Int && r.type == IrType.String ->
                    "(${emitExpr(r)} * ${emitExpr(l)})"
                // Integer division truncates via `~/` (`/` always yields a double).
                expr.op == IrBinaryOp.DIV && expr.type in IrType.integerTypes ->
                    "(${emitExpr(l)} ~/ ${emitExpr(r)})"
                // `.remainder()` follows the dividend's sign (matches the interpreter).
                expr.op == IrBinaryOp.MOD ->
                    "(${emitExpr(l)}).remainder(${emitExpr(r)})"
                else -> {
                    val op = when (expr.op) {
                        IrBinaryOp.ADD -> "+"
                        IrBinaryOp.SUB -> "-"
                        IrBinaryOp.MUL -> "*"
                        IrBinaryOp.DIV -> "/"
                        IrBinaryOp.MOD -> "%"
                        IrBinaryOp.EQ -> "=="
                        IrBinaryOp.NEQ -> "!="
                        IrBinaryOp.LT -> "<"
                        IrBinaryOp.LTE -> "<="
                        IrBinaryOp.GT -> ">"
                        IrBinaryOp.GTE -> ">="
                        IrBinaryOp.AND -> "&&"
                        IrBinaryOp.OR -> "||"
                        IrBinaryOp.BIT_AND -> "&"; IrBinaryOp.BIT_OR -> "|"; IrBinaryOp.BIT_XOR -> "^"
                        IrBinaryOp.SHL -> "<<"; IrBinaryOp.SHR -> ">>"
                    }
                    "(${emitExpr(l)} $op ${emitExpr(r)})"
                }
            }
        }
        is IrExpr.Call -> {
            if (expr.name in POINTER_RUNTIME) usesPointers = true
            when {
                expr.name == "async" && expr.args.singleOrNull() is IrExpr.Lambda ->
                    "__azoraSpawn(${emitLambda(expr.args.single() as IrExpr.Lambda, async = true)})"
                expr.name == "cancel" && expr.args.size == 1 ->
                    "cancel(${emitExpr(expr.args.single())})"
                expr.type is IrType.Task ->
                    "__azoraSpawn(() => ${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }}))"
                else -> {
                    val name = if (expr.name == "println") "print" else expr.name
                    "$name(${expr.args.joinToString(", ") { emitExpr(it) }})"
                }
            }
        }
        is IrExpr.Await -> "(await ${emitExpr(expr.value)})"
        is IrExpr.Spread -> "...${emitExpr(expr.array)}"
        is IrExpr.ArrayLiteral -> "[${expr.elements.joinToString(", ") { emitExpr(it) }}]"
        is IrExpr.SetLit -> {
            if (expr.elements.isEmpty()) "<${(expr.type as? IrType.Set)?.let { mapType(it.element) } ?: "dynamic"}>{}"
            else "{${expr.elements.joinToString(", ") { emitExpr(it) }}}"
        }
        is IrExpr.MapLit -> {
            if (expr.entries.isEmpty()) "{}"
            else "{${expr.entries.joinToString(", ") { "${emitExpr(it.first)}: ${emitExpr(it.second)}" }}}"
        }
        is IrExpr.Index -> "${emitExpr(expr.target)}[${emitExpr(expr.index)}]"
        is IrExpr.Member -> when (expr.name) {
            "length" -> "${emitExpr(expr.target)}.length"
            "isEmpty" -> "${emitExpr(expr.target)}.isEmpty"
            "isNotEmpty" -> "${emitExpr(expr.target)}.isNotEmpty"
            else -> "${emitExpr(expr.target)}.${expr.name}"
        }
        is IrExpr.MethodCall -> when (expr.name) {
            "isEmpty" -> "${emitExpr(expr.target)}.isEmpty"
            "isNotEmpty" -> "${emitExpr(expr.target)}.isNotEmpty"
            else -> "${emitExpr(expr.target)}.${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        }
        is IrExpr.StructCtor -> "${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        is IrExpr.TupleLit -> "(${expr.elements.joinToString(", ") { emitExpr(it) }})"
        is IrExpr.TupleAccess -> "${emitExpr(expr.target)}.\$${expr.index + 1}"
        is IrExpr.CatchExpr ->
            "(() { try { return ${emitExpr(expr.expr)}; } catch (_) { return ${emitExpr(expr.fallback)}; } })()"
        is IrExpr.IfExpr -> "(${emitExpr(expr.condition)} ? ${emitExpr(expr.thenExpr)} : ${emitExpr(expr.elseExpr)})"
        is IrExpr.NumCast -> emitNumCast(expr)
        is IrExpr.SlotPattern -> "" /* handled by when lowering */
        is IrExpr.Lambda -> emitLambda(expr, async = false)
        is IrExpr.StringTemplate -> {
            val sb = StringBuilder("\"")
            for (part in expr.parts) {
                when (part) {
                    is IrExpr.IrTemplatePart.Literal -> sb.append(escapeString(part.text))
                    is IrExpr.IrTemplatePart.Expr -> sb.append("\${").append(emitExpr(part.expr)).append("}")
                }
            }
            sb.append("\"").toString()
        }
    }

    /** Emits a lambda/closure. When [async] the closure is marked `async`. */
    private fun emitLambda(expr: IrExpr.Lambda, async: Boolean): String {
        val ps = expr.params.joinToString(", ") { (n, t) -> "${mapType(t)} $n" }
        val asyncKw = if (async) " async" else ""
        val ret = expr.body.singleOrNull() as? IrStmt.Return
        if (!async && ret != null && ret.value != null) {
            return "($ps) => ${emitExpr(ret.value)}"
        }
        val body = capture {
            indent++
            for (s in expr.body) emitStmt(s)
            indent--
        }
        val pad = "    ".repeat(indent)
        return "($ps)$asyncKw {\n$body$pad}"
    }

    private fun emitNumCast(expr: IrExpr.NumCast): String {
        val src = expr.value.type
        val sourceNumeric = src in IrType.integerTypes || src in IrType.floatTypes || src == IrType.Char
        if (!sourceNumeric) return emitExpr(expr.value) // pointer/FFI cast — pass through
        val v = emitExpr(expr.value)
        // Char (String) target — build from a code point.
        if (expr.type == IrType.Char) {
            return if (src == IrType.Char) v else "String.fromCharCode(($v).toInt())"
        }
        // Char source — read its first code unit before converting.
        val base = if (src == IrType.Char) "($v).codeUnitAt(0)" else "($v)"
        return when (expr.type) {
            in IrType.floatTypes -> "$base.toDouble()"
            else -> "$base.toInt()"
        }
    }

    private fun mapType(type: IrType): String = when (type) {
        IrType.Int -> "int"
        IrType.UInt -> "int"
        IrType.Real -> "double"
        IrType.String -> "String"
        IrType.Bool -> "bool"
        IrType.Unit -> "void"
        IrType.Char -> "String"
        IrType.Byte, IrType.UByte -> "int"
        IrType.Short, IrType.UShort -> "int"
        IrType.Long, IrType.ULong -> "int"
        IrType.Cent, IrType.UCent -> "int"
        IrType.Float -> "double"
        IrType.Decimal -> "double"
        is IrType.Array -> "List<${mapType(type.element)}>"
        is IrType.Set -> "Set<${mapType(type.element)}>"
        is IrType.Map -> "Map<${mapType(type.key)}, ${mapType(type.value)}>"
        is IrType.Pointer -> "AzoraPtr<${mapType(type.inner)}>"
        is IrType.Function -> "${mapType(type.ret)} Function(${type.params.joinToString(", ") { mapType(it) }})"
        is IrType.Task -> "Future<${mapType(type.result)}>"
        is IrType.Tuple -> "(${type.elements.joinToString(", ") { mapType(it) }})"
        is IrType.Nullable -> "${mapType(type.inner)}?"
        is IrType.Named -> type.name
        IrType.Any -> "dynamic"
    }

    /** Emits [block] into a scratch region of [out] and returns (and removes) the produced text. */
    private fun capture(block: () -> Unit): String {
        val start = out.length
        block()
        val text = out.substring(start)
        out.setLength(start)
        return text
    }

    private fun escapeString(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
            .replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")

    private fun line(text: String) {
        repeat(indent) { out.append("    ") }
        out.appendLine(text)
    }
}
