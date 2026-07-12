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
 * Backend — lowers [IrProgram] to plain JavaScript source code.
 *
 * JavaScript is dynamically typed, so no type annotations are emitted; the IR
 * types are only consulted internally to pick the right runtime behaviour
 * (integer division truncation, `bigint` literals, etc.).
 *
 * `println` maps to `console.log`.
 * `fin`/`let` map to `const`, `var` maps to `let`.
 * String * Int maps to `.repeat(n)`.
 */
class JavaScriptCodegen {

    private val out = StringBuilder()
    private var indent = 0
    private var usesPointers = false
    private var usesTasks = false

    private val POINTER_RUNTIME = setOf("__alloc", "__deref", "__derefAssign", "__isolated")

    private val pointerPreamble: String = """
        class AzoraPtr { constructor(value) { this.value = value; } }
        function __alloc(v) { return new AzoraPtr(v); }
        function __deref(p) { return p.value; }
        function __derefAssign(p, v) { p.value = v; }
        // NOTE: __isolated is a shallow copy in the JavaScript backend.
        function __isolated(v) { return v; }
    """.trimIndent()

    /**
     * Generates JavaScript source code from the given IR program.
     *
     * If a `main` function is present, an entry-point call (`main()`) is
     * appended at the end of the output.
     *
     * @param program the optimized IR program to lower to JavaScript
     * @return the generated JavaScript source code as a string
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
        if (usesTasks) {
            line("const __azoraChildren = new Set();")
            line("function cancel(_task) {}")
            line("function __azoraSpawn(body) {")
            indent++
            line("const task = Promise.resolve().then(body);")
            line("__azoraChildren.add(task);")
            line("void task.then(() => __azoraChildren.delete(task), () => __azoraChildren.delete(task));")
            line("return task;")
            indent--
            line("}")
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
                    line("class ${item.name} {")
                    indent++
                    val params = item.fields.joinToString(", ") { it.name }
                    line("constructor($params) {")
                    indent++
                    for (f in item.fields) line("this.${f.name} = ${f.name};")
                    indent--
                    line("}")
                    indent--
                    line("}")
                    if (i < program.items.lastIndex) line("")
                }
                is IrTopLevel.Extern -> {
                    // JavaScript has no declarations; the extern is expected to be
                    // provided by the host. Emit a documentation comment.
                    val params = item.params.joinToString(", ") { (n, _) -> n }
                    line("// extern function ${item.name}($params)")
                    if (i < program.items.lastIndex) line("")
                }
            }
        }

        // Entry point
        val main = program.functions.firstOrNull { it.name == "main" }
        if (main != null) {
            line("")
            if (main.isTask) {
                line("async function __azoraRunMain() {")
                indent++
                line("await main();")
                line("while (__azoraChildren.size > 0) await Promise.all(Array.from(__azoraChildren));")
                indent--
                line("}")
                line("void __azoraRunMain();")
            } else {
                line("main()")
            }
        }

        val body = out.toString().trimEnd()
        return if (usesPointers) pointerPreamble + "\n\n" + body else body
    }

    private fun emitTest(test: IrTopLevel.Test) {
        line("test(\"${escapeString(test.name)}\", () => {")
        indent++
        for (stmt in test.body) emitStmt(stmt)
        indent--
        line("});")
    }

    private fun emitFunction(func: IrFunction) {
        val params = func.params.joinToString(", ") { (name, _) -> name }
        val async = if (func.isTask) "async " else ""
        line("${async}function ${func.name}($params) {")
        indent++
        if (containsDefer(func.body)) {
            line("const __az_defer = [];")
            line("let __az_failed = false;")
            line("let __az_error = undefined;")
            line("try {")
            indent++
            for (stmt in func.body) emitStmt(stmt)
            indent--
            line("} catch (__az_e) {")
            indent++
            line("__az_failed = true;")
            line("__az_error = __az_e;")
            indent--
            line("} finally {")
            indent++
            line("for (let __az_i = __az_defer.length - 1; __az_i >= 0; __az_i--) {")
            indent++
            line("const __az_d = __az_defer[__az_i];")
            line("if (!__az_d.onFail || __az_failed) {")
            indent++
            line("__az_d.body();")
            line("if (__az_d.suppress) __az_error = undefined;")
            indent--
            line("}")
            indent--
            line("}")
            indent--
            line("}")
            line("if (__az_failed && __az_error !== undefined) throw __az_error;")
        } else {
            for (stmt in func.body) emitStmt(stmt)
        }
        indent--
        line("}")
    }

    private fun containsDefer(stmts: List<IrStmt>): Boolean = stmts.any { stmt ->
        when (stmt) {
            is IrStmt.Defer -> true
            is IrStmt.If -> containsDefer(stmt.thenBranch) || (stmt.elseBranch?.let(::containsDefer) == true)
            is IrStmt.While -> containsDefer(stmt.body)
            is IrStmt.For -> containsDefer(stmt.body)
            is IrStmt.ForEach -> containsDefer(stmt.body)
            is IrStmt.Loop -> containsDefer(stmt.body)
            is IrStmt.When -> stmt.branches.any { containsDefer(it.body) } || (stmt.elseBranch?.let(::containsDefer) == true)
            is IrStmt.Try -> containsDefer(stmt.body) || (stmt.catchBody?.let(::containsDefer) == true)
            is IrStmt.Zone -> containsDefer(stmt.body)
            else -> false
        }
    }

    private fun emitStmt(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> line("let ${stmt.name} = ${emitExpr(stmt.initializer)};")
            is IrStmt.FinDecl -> line("const ${stmt.name} = ${emitExpr(stmt.initializer)};")
            is IrStmt.LetDecl -> line("const ${stmt.name} = ${emitExpr(stmt.initializer)};")
            is IrStmt.Assignment -> line("${stmt.name} = ${emitExpr(stmt.value)};")
            is IrStmt.IndexAssign -> line("${emitExpr(stmt.target)}[${emitExpr(stmt.index)}] = ${emitExpr(stmt.value)};")
            is IrStmt.MemberAssign -> line("${emitExpr(stmt.target)}.${stmt.name} = ${emitExpr(stmt.value)};")
            is IrStmt.When -> {
                line("switch (${emitExpr(stmt.scrutinee)}) {")
                indent++
                // Case bodies are wrapped in blocks so `let`/`const` declarations
                // in different branches don't collide in the shared switch scope.
                for (b in stmt.branches) {
                    for ((i, p) in b.patterns.withIndex()) {
                        if (i == b.patterns.lastIndex) line("case ${emitExpr(p)}: {")
                        else line("case ${emitExpr(p)}:")
                    }
                    indent++
                    for (s in b.body) emitStmt(s)
                    line("break;")
                    indent--
                    line("}")
                }
                if (stmt.elseBranch != null) {
                    line("default: {")
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
            is IrStmt.Assert -> {
                line("if (!(${emitExpr(stmt.condition)})) { throw new Error(${emitExpr(stmt.message)}); }")
            }
            is IrStmt.Trace -> {
                line("console.log(\"[TRACE]\", ${emitExpr(stmt.message)});")
            }
            is IrStmt.Zone -> {
                line("{")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
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
                // Emit `i++` / `i--` for the default step, `i += N` / `i -= N` otherwise.
                val inc = when {
                    stmt.reverse && stmt.step != null -> "${stmt.counter} -= ${emitExpr(stmt.step)}"
                    stmt.reverse -> "${stmt.counter}--"
                    stmt.step != null -> "${stmt.counter} += ${emitExpr(stmt.step)}"
                    else -> "${stmt.counter}++"
                }
                val header = if (stmt.reverse) {
                    "for (let ${stmt.counter} = ${emitExpr(stmt.end)}; ${stmt.counter} >= ${emitExpr(stmt.start)}; $inc)"
                } else {
                    val op = if (stmt.inclusive) "<=" else "<"
                    "for (let ${stmt.counter} = ${emitExpr(stmt.start)}; ${stmt.counter} $op ${emitExpr(stmt.end)}; $inc)"
                }
                line("$lbl$header {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.ForEach -> {
                line("for (const ${stmt.elem} of ${emitExpr(stmt.iterable)}) {")
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
                    line("} catch (_e) {")
                    indent++
                    if (stmt.catchName != null) line("const ${stmt.catchName} = _e;")
                    for (s in stmt.catchBody) emitStmt(s)
                    indent--
                    line("}")
                } else {
                    // JS requires a catch or finally clause.
                    line("} finally {")
                    line("}")
                }
            }
            is IrStmt.Defer -> {
                line("__az_defer.push({ onFail: ${stmt.onFail}, suppress: ${stmt.suppress}, body: () => {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("} });")
            }
            is IrStmt.Yield -> {}
            is IrStmt.Break -> line(if (stmt.label != null) "break ${stmt.label};" else "break;")
            is IrStmt.Continue -> line(if (stmt.label != null) "continue ${stmt.label};" else "continue;")
        }
    }

    private fun emitExpr(expr: IrExpr): String = when (expr) {
        is IrExpr.IntLiteral -> when (expr.type) {
            IrType.Long, IrType.ULong, IrType.Cent, IrType.UCent -> "${expr.value}n"
            else -> "${expr.value}"
        }
        is IrExpr.RealLiteral -> "${expr.value}"
        is IrExpr.StringLiteral -> "\"${escapeString(expr.value)}\""
        is IrExpr.BoolLiteral -> "${expr.value}"
        is IrExpr.CharLiteral -> "\"${escapeString(expr.value.toString())}\""
        is IrExpr.Var -> expr.name
        is IrExpr.Unary -> {
            val op = when (expr.op) {
                IrUnaryOp.NEG -> "-"
                IrUnaryOp.NOT -> "!"
                IrUnaryOp.BIT_NOT -> "~"
            }
            "($op${emitExpr(expr.operand)})"
        }
        is IrExpr.Binary -> {
            if (expr.op == IrBinaryOp.MUL && expr.left.type == IrType.String && expr.right.type == IrType.Int) {
                "${emitExpr(expr.left)}.repeat(${emitExpr(expr.right)})"
            } else if (expr.op == IrBinaryOp.MUL && expr.left.type == IrType.Int && expr.right.type == IrType.String) {
                "${emitExpr(expr.right)}.repeat(${emitExpr(expr.left)})"
            } else if (expr.op == IrBinaryOp.DIV && expr.type in IrType.integerTypes && mapType(expr.type) == "number") {
                // Integer division truncates; JS `/` yields a float. (bigint `/` already truncates.)
                "Math.trunc(${emitExpr(expr.left)} / ${emitExpr(expr.right)})"
            } else {
                val op = when (expr.op) {
                    IrBinaryOp.ADD -> "+"
                    IrBinaryOp.SUB -> "-"
                    IrBinaryOp.MUL -> "*"
                    IrBinaryOp.DIV -> "/"
                    IrBinaryOp.MOD -> "%"
                    IrBinaryOp.EQ -> "==="
                    IrBinaryOp.NEQ -> "!=="
                    IrBinaryOp.LT -> "<"
                    IrBinaryOp.LTE -> "<="
                    IrBinaryOp.GT -> ">"
                    IrBinaryOp.GTE -> ">="
                    IrBinaryOp.AND -> "&&"
                    IrBinaryOp.OR -> "||"
                IrBinaryOp.BIT_AND -> "&"; IrBinaryOp.BIT_OR -> "|"; IrBinaryOp.BIT_XOR -> "^"
                IrBinaryOp.SHL -> "<<"; IrBinaryOp.SHR -> ">>"
                }
                "(${emitExpr(expr.left)} $op ${emitExpr(expr.right)})"
            }
        }
        is IrExpr.Call -> {
            if (expr.name in POINTER_RUNTIME) usesPointers = true
            val name = if (expr.name == "println") "console.log" else expr.name
            if (expr.name == "async" && expr.args.size == 1) {
                "__azoraSpawn(${emitExpr(expr.args.single())})"
            } else if (expr.name == "cancel" && expr.args.size == 1) {
                "cancel(${emitExpr(expr.args.single())})"
            } else if (expr.type is IrType.Task) {
                "__azoraSpawn(() => $name(${expr.args.joinToString(", ") { emitExpr(it) }}))"
            } else {
                "$name(${expr.args.joinToString(", ") { emitExpr(it) }})"
            }
        }
        is IrExpr.Await -> "await ${emitExpr(expr.value)}"
        is IrExpr.Spread -> "...${emitExpr(expr.array)}" // JS spread operator
        is IrExpr.ArrayLiteral -> "[${expr.elements.joinToString(", ") { emitExpr(it) }}]"
        is IrExpr.SetLit -> "new Set([${expr.elements.joinToString(", ") { emitExpr(it) }}])"
        is IrExpr.MapLit -> "({ ${expr.entries.joinToString(", ") { "[${emitExpr(it.first)}]: ${emitExpr(it.second)}" }} })"
        is IrExpr.Index -> "${emitExpr(expr.target)}[${emitExpr(expr.index)}]"
        is IrExpr.Member -> {
            val prop = when (expr.name) {
                "isEmpty" -> "length === 0"
                "isNotEmpty" -> "length !== 0"
                "size" -> "length"
                else -> expr.name
            }
            "${emitExpr(expr.target)}.$prop"
        }
        is IrExpr.MethodCall -> {
            val call = when (expr.name) {
                "add" -> "push(${expr.args.joinToString(", ") { emitExpr(it) }})"
                "isEmpty" -> "length === 0"
                "isNotEmpty" -> "length !== 0"
                "size" -> "length"
                else -> "${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
            }
            "${emitExpr(expr.target)}.$call"
        }
        is IrExpr.StructCtor -> "new ${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        is IrExpr.TupleLit -> "[${expr.elements.joinToString(", ") { emitExpr(it) }}]"
        is IrExpr.VariantLit -> emitExpr(expr.elements.first())
        is IrExpr.TupleAccess -> "${emitExpr(expr.target)}[${expr.index}]"
        is IrExpr.CatchExpr -> "(() => { try { return ${emitExpr(expr.expr)}; } catch { return ${emitExpr(expr.fallback)}; } })()"
        is IrExpr.IfExpr -> "((${emitExpr(expr.condition)}) ? ${emitExpr(expr.thenExpr)} : ${emitExpr(expr.elseExpr)})"
        is IrExpr.NumCast -> {
            val sourceNumeric = expr.value.type in IrType.integerTypes ||
                expr.value.type in IrType.floatTypes || expr.value.type == IrType.Char
            when {
                // Pointer-ish casts (FFI) have no JS meaning — pass through.
                !sourceNumeric -> emitExpr(expr.value)
                // bigint targets need explicit BigInt conversion; other integer
                // targets truncate; float targets are plain JS numbers.
                expr.type in listOf(IrType.Long, IrType.ULong, IrType.Cent, IrType.UCent) ->
                    "BigInt(Math.trunc(Number(${emitExpr(expr.value)})))"
                expr.type in IrType.integerTypes -> "Math.trunc(Number(${emitExpr(expr.value)}))"
                else -> "Number(${emitExpr(expr.value)})"
            }
        }
        is IrExpr.SlotPattern -> "" /* handled by when lowering */
        is IrExpr.Lambda -> {
            val ps = expr.params.joinToString(", ") { (n, _) -> n }
            val ret = expr.body.singleOrNull() as? IrStmt.Return
            if (ret != null && ret.value != null) "($ps) => ${emitExpr(ret.value)}"
            else if (expr.body.isEmpty() || ret != null) "($ps) => {}"
            else {
                // Multi-statement body: arrow functions support `return` natively.
                val body = capture {
                    indent++
                    for (s in expr.body) emitStmt(s)
                    indent--
                }
                val pad = "    ".repeat(indent)
                "($ps) => {\n$body$pad}"
            }
        }
        is IrExpr.StringTemplate -> {
            val sb = StringBuilder("`")
            for (part in expr.parts) {
                when (part) {
                    is IrExpr.IrTemplatePart.Literal -> sb.append(part.text.replace("\\", "\\\\").replace("`", "\\`").replace("\${", "\\\${"))
                    is IrExpr.IrTemplatePart.Expr -> sb.append("\${").append(emitExpr(part.expr)).append("}")
                }
            }
            sb.append("`").toString()
        }
    }

    /**
     * Maps an IR type to a JavaScript runtime category. JavaScript is untyped,
     * so this is not emitted into the output — it is only used to decide runtime
     * behaviour (e.g. integer division truncation and `bigint` handling).
     */
    private fun mapType(type: IrType): String = when (type) {
        IrType.Int -> "number"
        IrType.UInt -> "number"
        IrType.Real -> "number"
        IrType.String -> "string"
        IrType.Bool -> "boolean"
        IrType.Unit -> "void"
        IrType.Char -> "string"
        IrType.Byte, IrType.UByte -> "number"
        IrType.Short, IrType.UShort -> "number"
        IrType.Float -> "number"
        IrType.Decimal -> "number"
        IrType.Long, IrType.ULong -> "bigint"
        IrType.Cent, IrType.UCent -> "bigint"
        is IrType.Array -> "array"
        is IrType.Set -> "set"
        is IrType.Map -> "object"
        is IrType.Pointer -> "object"
        is IrType.Function -> "function"
        is IrType.Task -> "promise"
        is IrType.Tuple -> "array"
        is IrType.Variant -> "any"
        is IrType.Nullable -> mapType(type.inner)
        is IrType.Named -> type.name
        IrType.Any -> "any"
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

    private fun line(text: String) {
        repeat(indent) { out.append("    ") }
        out.appendLine(text)
    }
}
