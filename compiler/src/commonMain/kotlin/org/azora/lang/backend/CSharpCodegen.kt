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
 * Backend — lowers [IrProgram] to C# / .NET source code.
 *
 * Type mapping:
 *   Int    → int          Real    → double        String → string
 *   Bool   → bool         Unit    → void          Char   → char
 *   Byte   → sbyte        UByte   → byte           Short  → short
 *   Long   → long         Cent    → System.Int128  Float  → float
 *   [T]    → List<T>      ![T]    → HashSet<T>     [K:V]  → Dictionary<K, V>
 *   (A,B)  → (A, B)       T?      → T?             Task<T>→ Task<T>
 *
 * All declarations are emitted as static members of a `Program` class (packs
 * become top-level classes), with a generated `Main` entry point that invokes
 * the Azora `main`. `println` maps to `Console.WriteLine`.
 *
 * NOTE: Azora packs are reference types, so they lower to C# `class` (mutation
 * through an immutable binding stays legal); collections lower to the mutable
 * reference types `List`/`HashSet`/`Dictionary`. Booleans are printed lower-case
 * (`true`/`false`) to match the interpreter, unlike `bool.ToString()`.
 */
class CSharpCodegen {

    private val out = StringBuilder()
    private var indent = 0
    private var usesPointers = false
    private var usesTasks = false
    private var usesError = false

    private val POINTER_RUNTIME = setOf("__alloc", "__deref", "__derefAssign", "__isolated")

    /** C# reserved keywords — identifiers matching these are emitted verbatim (`@name`). */
    private val keywords = setOf(
        "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
        "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
        "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for",
        "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
        "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
        "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed", "short",
        "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true",
        "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual",
        "void", "volatile", "while"
    )

    private fun sanitize(name: String): String = if (name in keywords) "@$name" else name

    /**
     * Generates C# source code from the given IR program.
     *
     * @param program the optimized IR program to lower to C#
     * @return the generated C# source code as a string
     */
    fun generate(program: IrProgram): String {
        out.clear()
        indent = 0
        usesPointers = false
        usesTasks = program.functions.any { it.isTask }
        usesError = false

        val structs = program.items.filterIsInstance<IrTopLevel.Struct>()
        val funcs = program.items.filterIsInstance<IrTopLevel.Func>().map { it.function }
        val tests = program.items.filterIsInstance<IrTopLevel.Test>()
        val externs = program.items.filterIsInstance<IrTopLevel.Extern>()
        val globals = program.items.filterIsInstance<IrTopLevel.Global>().map { it.stmt }
        val globalFields = globals.filter { it is IrStmt.VarDecl || it is IrStmt.FinDecl || it is IrStmt.LetDecl }
        val globalStmts = globals - globalFields.toSet()

        // Static members of the Program class (discovers the pointer/error flags).
        val members = capture {
            indent = 1
            for (g in globalFields) {
                emitFieldDecl(g)
            }
            for ((i, f) in funcs.withIndex()) {
                emitFunction(f)
                if (i < funcs.lastIndex || tests.isNotEmpty() || externs.isNotEmpty()) line("")
            }
            for (t in tests) {
                emitTest(t)
                line("")
            }
            for (e in externs) {
                val params = e.params.joinToString(", ") { (n, t) -> "${mapType(t)} ${sanitize(n)}" }
                line("// extern ${mapType(e.returnType)} ${e.name}($params)")
            }
            emitMain(funcs, globalStmts)
        }

        // Runtime helper members (must follow member emission so flags are set).
        val runtime = capture {
            indent = 1
            if (usesPointers) for (l in pointerRuntime.lines()) line(l)
            if (usesTasks) for (l in taskRuntime.lines()) line(l)
        }

        // Top-level type declarations (packs + runtime classes).
        val types = capture {
            indent = 0
            for (s in structs) {
                emitStruct(s)
                line("")
            }
            if (usesError) {
                line("public class AzoraException : System.Exception { public object Value; public AzoraException(object v) { Value = v; } }")
                line("")
            }
            if (usesPointers) {
                line("public class AzoraPtr<T> { public T Value; public AzoraPtr(T v) { Value = v; } }")
                line("")
            }
        }

        val sb = StringBuilder()
        sb.appendLine("using System;")
        sb.appendLine("using System.Collections.Generic;")
        sb.appendLine("using System.Linq;")
        sb.appendLine("using System.Threading.Tasks;")
        sb.appendLine()
        if (program.packageName != null) {
            sb.appendLine("// package: ${program.packageName}")
            sb.appendLine()
        }
        sb.appendLine("public static class Program {")
        if (runtime.isNotBlank()) sb.append(runtime)
        sb.append(members)
        sb.appendLine("}")
        if (types.isNotBlank()) {
            sb.appendLine()
            sb.append(types)
        }
        return sb.toString().trimEnd()
    }

    private val pointerRuntime: String = """
        static AzoraPtr<T> __alloc<T>(T v) => new AzoraPtr<T>(v);
        static T __deref<T>(AzoraPtr<T> p) => p.Value;
        static void __derefAssign<T>(AzoraPtr<T> p, T v) { p.Value = v; }
        // NOTE: __isolated is a shallow copy in the C# backend.
        static T __isolated<T>(T v) => v;
    """.trimIndent()

    private val taskRuntime: String = """
        static readonly List<Task> __azoraChildren = new List<Task>();
        static Task<T> __azoraSpawn<T>(Func<Task<T>> body) {
            var task = body();
            __azoraChildren.Add(task);
            return task;
        }
        static async Task __azoraDrainChildren() {
            for (int __i = 0; __i < __azoraChildren.Count; __i++) { await __azoraChildren[__i]; }
            __azoraChildren.Clear();
        }
        static void cancel<T>(Task<T> task) {}
    """.trimIndent()

    private fun emitMain(funcs: List<IrFunction>, globalStmts: List<IrStmt>) {
        val main = funcs.firstOrNull { it.name == "main" }
        if (main != null && main.isTask) {
            line("public static async Task Main() {")
            indent++
            for (s in globalStmts) emitStmt(s)
            line("await main();")
            line("await __azoraDrainChildren();")
            indent--
            line("}")
        } else {
            line("public static void Main() {")
            indent++
            for (s in globalStmts) emitStmt(s)
            if (main != null) line("main();")
            indent--
            line("}")
        }
    }

    private fun emitFieldDecl(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> line("static ${mapType(stmt.type)} ${sanitize(stmt.name)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.FinDecl -> line("static ${mapType(stmt.type)} ${sanitize(stmt.name)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.LetDecl -> line("static ${mapType(stmt.type)} ${sanitize(stmt.name)} = ${emitExpr(stmt.initializer)};")
            else -> {}
        }
    }

    private fun emitTest(test: IrTopLevel.Test) {
        val safeName = test.name.replace(" ", "_").replace("\"", "")
        line("static void __test_$safeName() {")
        indent++
        for (stmt in test.body) emitStmt(stmt)
        indent--
        line("}")
    }

    private fun emitStruct(struct: IrTopLevel.Struct) {
        line("public class ${sanitize(struct.name)} {")
        indent++
        for (f in struct.fields) {
            val kw = if (f.mutable) "public" else "public readonly"
            line("$kw ${mapType(f.type)} ${sanitize(f.name)};")
        }
        val params = struct.fields.joinToString(", ") { "${mapType(it.type)} ${sanitize(it.name)}" }
        line("public ${sanitize(struct.name)}($params) {")
        indent++
        for (f in struct.fields) line("this.${sanitize(f.name)} = ${sanitize(f.name)};")
        indent--
        line("}")
        indent--
        line("}")
    }

    private fun emitFunction(func: IrFunction) {
        val params = func.params.joinToString(", ") { (name, type) ->
            "${mapType(type)} ${sanitize(name)}"
        }
        val retType = when {
            func.isTask && func.returnType == IrType.Unit -> "Task"
            func.isTask -> "Task<${mapType(func.returnType)}>"
            else -> mapType(func.returnType)
        }
        val async = if (func.isTask) "async " else ""
        line("static $async$retType ${sanitize(func.name)}($params) {")
        indent++
        for (stmt in func.body) emitStmt(stmt)
        indent--
        line("}")
    }

    private fun emitStmt(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> line("${mapType(stmt.type)} ${sanitize(stmt.name)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.FinDecl -> line("${mapType(stmt.type)} ${sanitize(stmt.name)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.LetDecl -> line("${mapType(stmt.type)} ${sanitize(stmt.name)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.Assignment -> line("${sanitize(stmt.name)} = ${emitExpr(stmt.value)};")
            is IrStmt.IndexAssign -> line("${emitExpr(stmt.target)}[${emitExpr(stmt.index)}] = ${emitExpr(stmt.value)};")
            is IrStmt.MemberAssign -> line("${emitExpr(stmt.target)}.${sanitize(stmt.name)} = ${emitExpr(stmt.value)};")
            is IrStmt.When -> {
                line("switch (${emitExpr(stmt.scrutinee)}) {")
                indent++
                for (b in stmt.branches) {
                    for (p in b.patterns) line("case ${emitExpr(p)}:")
                    // Block-wrap so `var` declarations in different branches don't collide.
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
                    line("break;")
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
            is IrStmt.Assert -> line("if (!(${emitExpr(stmt.condition)})) throw new Exception(${emitExpr(stmt.message)});")
            is IrStmt.Trace -> line("Console.WriteLine(\"[TRACE] \" + (${emitExpr(stmt.message)}));")
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
                line("while (${emitExpr(stmt.condition)}) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                if (stmt.label != null) line("__cont_${stmt.label}:;")
                indent--
                line("}")
                if (stmt.label != null) line("__brk_${stmt.label}:;")
            }
            is IrStmt.For -> {
                val counter = sanitize(stmt.counter)
                val inc = when {
                    stmt.reverse && stmt.step != null -> "$counter -= ${emitExpr(stmt.step)}"
                    stmt.reverse -> "$counter--"
                    stmt.step != null -> "$counter += ${emitExpr(stmt.step)}"
                    else -> "$counter++"
                }
                val header = if (stmt.reverse) {
                    "for (var $counter = ${emitExpr(stmt.end)}; $counter >= ${emitExpr(stmt.start)}; $inc)"
                } else {
                    val op = if (stmt.inclusive) "<=" else "<"
                    "for (var $counter = ${emitExpr(stmt.start)}; $counter $op ${emitExpr(stmt.end)}; $inc)"
                }
                line("$header {")
                indent++
                for (s in stmt.body) emitStmt(s)
                if (stmt.label != null) line("__cont_${stmt.label}:;")
                indent--
                line("}")
                if (stmt.label != null) line("__brk_${stmt.label}:;")
            }
            is IrStmt.ForEach -> {
                line("foreach (var ${sanitize(stmt.elem)} in ${emitExpr(stmt.iterable)}) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Loop -> {
                line("while (true) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                if (stmt.label != null) line("__cont_${stmt.label}:;")
                indent--
                line("}")
                if (stmt.label != null) line("__brk_${stmt.label}:;")
            }
            is IrStmt.Throw -> {
                usesError = true
                line("throw new AzoraException(${emitExpr(stmt.value)});")
            }
            is IrStmt.Try -> {
                usesError = true
                line("try {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                if (stmt.catchBody != null) {
                    line("} catch (Exception __azerr) {")
                    indent++
                    if (stmt.catchName != null) {
                        line("var ${sanitize(stmt.catchName)} = (__azerr is AzoraException __ae) ? __ae.Value : (object)__azerr.Message;")
                    }
                    for (s in stmt.catchBody) emitStmt(s)
                    indent--
                    line("}")
                } else {
                    line("} catch (Exception) {")
                    line("}")
                }
            }
            is IrStmt.Defer -> {}
            is IrStmt.Yield -> {}
            is IrStmt.Break -> line(if (stmt.label != null) "goto __brk_${stmt.label};" else "break;")
            is IrStmt.Continue -> line(if (stmt.label != null) "goto __cont_${stmt.label};" else "continue;")
        }
    }

    private fun emitExpr(expr: IrExpr): String = when (expr) {
        is IrExpr.IntLiteral -> when (expr.type) {
            IrType.UInt -> "${expr.value}u"
            IrType.Long -> "${expr.value}L"
            IrType.ULong -> "${expr.value}UL"
            IrType.Byte -> "(sbyte)${expr.value}"
            IrType.UByte -> "(byte)${expr.value}"
            IrType.Short -> "(short)${expr.value}"
            IrType.UShort -> "(ushort)${expr.value}"
            IrType.Cent -> "(System.Int128)${expr.value}"
            IrType.UCent -> "(System.UInt128)${expr.value}"
            else -> "${expr.value}"
        }
        is IrExpr.RealLiteral -> when (expr.type) {
            IrType.Float -> "${expr.value}f"
            IrType.Decimal -> "${expr.value}m"
            else -> "${expr.value}"
        }
        is IrExpr.StringLiteral -> "\"${escapeString(expr.value)}\""
        is IrExpr.BoolLiteral -> "${expr.value}"
        is IrExpr.CharLiteral -> "'${escapeChar(expr.value)}'"
        is IrExpr.Var -> sanitize(expr.name)
        is IrExpr.Unary -> when (expr.op) {
            IrUnaryOp.NEG -> "(-${emitExpr(expr.operand)})"
            IrUnaryOp.NOT -> "(!${emitExpr(expr.operand)})"
            IrUnaryOp.BIT_NOT -> "(~${emitExpr(expr.operand)})"
        }
        is IrExpr.Binary -> {
            val l = expr.left
            val r = expr.right
            when {
                // String * Int → repeat via LINQ (C# has no string-repeat operator).
                expr.op == IrBinaryOp.MUL && l.type == IrType.String && r.type == IrType.Int ->
                    "string.Concat(System.Linq.Enumerable.Repeat(${emitExpr(l)}, ${emitExpr(r)}))"
                expr.op == IrBinaryOp.MUL && l.type == IrType.Int && r.type == IrType.String ->
                    "string.Concat(System.Linq.Enumerable.Repeat(${emitExpr(r)}, ${emitExpr(l)}))"
                else -> {
                    val op = when (expr.op) {
                        IrBinaryOp.ADD -> "+"
                        IrBinaryOp.SUB -> "-"
                        IrBinaryOp.MUL -> "*"
                        IrBinaryOp.DIV -> "/" // C# `/` truncates for integer operands
                        IrBinaryOp.MOD -> "%" // C# `%` follows the dividend's sign
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
                    "__azoraSpawn(() => ${sanitize(expr.name)}(${expr.args.joinToString(", ") { emitExpr(it) }}))"
                expr.name == "println" && expr.args.size == 1 ->
                    "Console.WriteLine(${emitPrintArg(expr.args.single())})"
                expr.name == "print" && expr.args.size == 1 ->
                    "Console.Write(${emitPrintArg(expr.args.single())})"
                else ->
                    "${sanitize(expr.name)}(${expr.args.joinToString(", ") { emitExpr(it) }})"
            }
        }
        is IrExpr.Await -> "(await ${emitExpr(expr.value)})"
        is IrExpr.Spread -> emitExpr(expr.array) // C# has no argument-splat operator
        is IrExpr.ArrayLiteral -> {
            val elem = (expr.type as? IrType.Array)?.let { mapType(it.element) } ?: "dynamic"
            if (expr.elements.isEmpty()) "new List<$elem>()"
            else "new List<$elem>{${expr.elements.joinToString(", ") { emitExpr(it) }}}"
        }
        is IrExpr.SetLit -> {
            val elem = (expr.type as? IrType.Set)?.let { mapType(it.element) } ?: "dynamic"
            if (expr.elements.isEmpty()) "new HashSet<$elem>()"
            else "new HashSet<$elem>{${expr.elements.joinToString(", ") { emitExpr(it) }}}"
        }
        is IrExpr.MapLit -> {
            val t = expr.type as? IrType.Map
            val kt = t?.let { mapType(it.key) } ?: "dynamic"
            val vt = t?.let { mapType(it.value) } ?: "dynamic"
            if (expr.entries.isEmpty()) "new Dictionary<$kt, $vt>()"
            else "new Dictionary<$kt, $vt>{${expr.entries.joinToString(", ") { "{${emitExpr(it.first)}, ${emitExpr(it.second)}}" }}}"
        }
        is IrExpr.Index -> "${emitExpr(expr.target)}[${emitExpr(expr.index)}]"
        is IrExpr.Member -> when (expr.name) {
            "length" -> "${emitExpr(expr.target)}.${if (expr.target.type == IrType.String) "Length" else "Count"}"
            "isEmpty" -> "(${emitExpr(expr.target)}.${lenProp(expr.target)} == 0)"
            "isNotEmpty" -> "(${emitExpr(expr.target)}.${lenProp(expr.target)} != 0)"
            else -> "${emitExpr(expr.target)}.${sanitize(expr.name)}"
        }
        is IrExpr.MethodCall -> when (expr.name) {
            "add" -> "${emitExpr(expr.target)}.Add(${expr.args.joinToString(", ") { emitExpr(it) }})"
            "isEmpty" -> "(${emitExpr(expr.target)}.${lenProp(expr.target)} == 0)"
            "isNotEmpty" -> "(${emitExpr(expr.target)}.${lenProp(expr.target)} != 0)"
            else -> "${emitExpr(expr.target)}.${sanitize(expr.name)}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        }
        is IrExpr.StructCtor -> "new ${sanitize(expr.name)}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        is IrExpr.TupleLit -> "(${expr.elements.joinToString(", ") { emitExpr(it) }})"
        is IrExpr.TupleAccess -> "${emitExpr(expr.target)}.Item${expr.index + 1}"
        is IrExpr.CatchExpr ->
            "new Func<${mapType(expr.type)}>(() => { try { return ${emitExpr(expr.expr)}; } catch { return ${emitExpr(expr.fallback)}; } })()"
        is IrExpr.IfExpr -> "(${emitExpr(expr.condition)} ? ${emitExpr(expr.thenExpr)} : ${emitExpr(expr.elseExpr)})"
        is IrExpr.NumCast -> {
            val src = expr.value.type
            val sourceNumeric = src in IrType.integerTypes || src in IrType.floatTypes || src == IrType.Char
            // C# casts handle char↔int↔float and narrowing (unchecked wraps) directly.
            if (!sourceNumeric) emitExpr(expr.value)
            else "(${csharpType(expr.type)})(${emitExpr(expr.value)})"
        }
        is IrExpr.SlotPattern -> "" /* handled by when lowering */
        is IrExpr.Lambda -> emitLambda(expr, async = false)
        is IrExpr.StringTemplate -> {
            val sb = StringBuilder("$\"")
            for (part in expr.parts) {
                when (part) {
                    is IrExpr.IrTemplatePart.Literal -> sb.append(escapeInterp(part.text))
                    is IrExpr.IrTemplatePart.Expr -> sb.append("{").append(emitPrintArg(part.expr)).append("}")
                }
            }
            sb.append("\"").toString()
        }
    }

    /** Renders an argument for printing/interpolation, lower-casing booleans to match the interpreter. */
    private fun emitPrintArg(expr: IrExpr): String =
        if (expr.type == IrType.Bool) "(${emitExpr(expr)} ? \"true\" : \"false\")" else emitExpr(expr)

    /** The `.Length`/`.Count` property for the given receiver. */
    private fun lenProp(target: IrExpr): String = if (target.type == IrType.String) "Length" else "Count"

    /** Emits a lambda/closure. When [async] the closure is marked `async`. */
    private fun emitLambda(expr: IrExpr.Lambda, async: Boolean): String {
        val ps = expr.params.joinToString(", ") { (n, t) -> "${mapType(t)} ${sanitize(n)}" }
        if (!async) {
            val ret = expr.body.singleOrNull() as? IrStmt.Return
            if (ret?.value != null) return "($ps) => ${emitExpr(ret.value)}"
        }
        val body = capture {
            indent++
            for (s in expr.body) emitStmt(s)
            indent--
        }
        val pad = "    ".repeat(indent)
        val asyncKw = if (async) "async " else ""
        return "$asyncKw($ps) => {\n$body$pad}"
    }

    private fun csharpType(type: IrType): String = when (type) {
        IrType.Int -> "int"
        IrType.UInt -> "uint"
        IrType.Byte -> "sbyte"
        IrType.UByte -> "byte"
        IrType.Short -> "short"
        IrType.UShort -> "ushort"
        IrType.Long -> "long"
        IrType.ULong -> "ulong"
        IrType.Cent -> "System.Int128"
        IrType.UCent -> "System.UInt128"
        IrType.Float -> "float"
        IrType.Real -> "double"
        IrType.Decimal -> "decimal"
        IrType.Char -> "char"
        else -> "int"
    }

    private fun mapType(type: IrType): String = when (type) {
        IrType.Int -> "int"
        IrType.UInt -> "uint"
        IrType.Real -> "double"
        IrType.String -> "string"
        IrType.Bool -> "bool"
        IrType.Unit -> "void"
        IrType.Char -> "char"
        IrType.Byte -> "sbyte"
        IrType.UByte -> "byte"
        IrType.Short -> "short"
        IrType.UShort -> "ushort"
        IrType.Long -> "long"
        IrType.ULong -> "ulong"
        IrType.Cent -> "System.Int128"
        IrType.UCent -> "System.UInt128"
        IrType.Float -> "float"
        IrType.Decimal -> "decimal"
        is IrType.Array -> "List<${mapType(type.element)}>"
        is IrType.Set -> "HashSet<${mapType(type.element)}>"
        is IrType.Map -> "Dictionary<${mapType(type.key)}, ${mapType(type.value)}>"
        is IrType.Pointer -> "AzoraPtr<${mapType(type.inner)}>"
        is IrType.Function ->
            if (type.ret == IrType.Unit) {
                if (type.params.isEmpty()) "Action"
                else "Action<${type.params.joinToString(", ") { mapType(it) }}>"
            } else {
                val args = (type.params.map { mapType(it) } + mapType(type.ret)).joinToString(", ")
                "Func<$args>"
            }
        is IrType.Task -> if (type.result == IrType.Unit) "Task" else "Task<${mapType(type.result)}>"
        is IrType.Tuple -> "(${type.elements.joinToString(", ") { mapType(it) }})"
        is IrType.Nullable -> "${mapType(type.inner)}?"
        is IrType.Named -> sanitize(type.name)
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
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            .replace("\t", "\\t").replace("\r", "\\r")

    /** Escapes a literal chunk inside an interpolated (`$"..."`) string — braces are doubled. */
    private fun escapeInterp(s: String): String =
        escapeString(s).replace("{", "{{").replace("}", "}}")

    private fun escapeChar(c: Char): String = when (c) {
        '\n' -> "\\n"
        '\t' -> "\\t"
        '\r' -> "\\r"
        '\\' -> "\\\\"
        '\'' -> "\\'"
        else -> if (c.code in 32..126) "$c" else "\\u${c.code.toString(16).padStart(4, '0')}"
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
