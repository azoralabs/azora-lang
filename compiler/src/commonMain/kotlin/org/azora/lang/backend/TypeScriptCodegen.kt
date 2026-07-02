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
 * Backend — lowers [IrProgram] to TypeScript source code.
 *
 * Type mapping:
 *   Int    → number
 *   Real   → number
 *   String → string
 *   Bool   → boolean
 *   Unit   → void
 *
 * `println` maps to `console.log`.
 * `fin`/`let` map to `const`, `var` maps to `let`.
 * String * Int maps to `.repeat(n)`.
 */
class TypeScriptCodegen {

    private val out = StringBuilder()
    private var indent = 0
    private var usesPointers = false

    private val POINTER_RUNTIME = setOf("__alloc", "__deref", "__derefAssign", "__isolated")

    private val pointerPreamble: String = """
        class AzoraPtr<T> { constructor(public value: T) {} }
        function __alloc<T>(v: T): AzoraPtr<T> { return new AzoraPtr(v); }
        function __deref<T>(p: AzoraPtr<T>): T { return p.value; }
        function __derefAssign<T>(p: AzoraPtr<T>, v: T): void { p.value = v; }
        // NOTE: __isolated is a shallow copy in the TypeScript backend.
        function __isolated<T>(v: T): T { return v; }
    """.trimIndent()

    /**
     * Generates TypeScript source code from the given IR program.
     *
     * If a `main` function is present, an entry-point call (`main()`) is
     * appended at the end of the output.
     *
     * @param program the optimized IR program to lower to TypeScript
     * @return the generated TypeScript source code as a string
     */
    fun generate(program: IrProgram): String {
        out.clear()
        indent = 0
        usesPointers = false

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
                    line("class ${item.name} {")
                    indent++
                    for (f in item.fields) line("${f.name}: ${mapType(f.type)};")
                    val params = item.fields.joinToString(", ") { "${it.name}: ${mapType(it.type)}" }
                    line("constructor($params) {")
                    indent++
                    for (f in item.fields) line("this.${f.name} = ${f.name};")
                    indent--
                    line("}")
                    indent--
                    line("}")
                    if (i < program.items.lastIndex) line("")
                }
            }
        }

        // Entry point
        if (program.functions.any { it.name == "main" }) {
            line("")
            line("main()")
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
        val params = func.params.joinToString(", ") { (name, type) ->
            "$name: ${mapType(type)}"
        }
        val retType = mapType(func.returnType)
        line("function ${func.name}($params): $retType {")
        indent++
        for (stmt in func.body) {
            emitStmt(stmt)
        }
        indent--
        line("}")
    }

    private fun emitStmt(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> line("let ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.FinDecl -> line("const ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.LetDecl -> line("const ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)};")
            is IrStmt.Assignment -> line("${stmt.name} = ${emitExpr(stmt.value)};")
            is IrStmt.IndexAssign -> line("${emitExpr(stmt.target)}[${emitExpr(stmt.index)}] = ${emitExpr(stmt.value)};")
            is IrStmt.MemberAssign -> line("${emitExpr(stmt.target)}.${stmt.name} = ${emitExpr(stmt.value)};")
            is IrStmt.When -> {
                line("switch (${emitExpr(stmt.scrutinee)}) {")
                indent++
                for (b in stmt.branches) {
                    for (p in b.patterns) line("case ${emitExpr(p)}:")
                    indent++
                    for (s in b.body) emitStmt(s)
                    line("break;")
                    indent--
                }
                if (stmt.elseBranch != null) {
                    line("default:")
                    indent++
                    for (s in stmt.elseBranch) emitStmt(s)
                    indent--
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
                }
                line("}")
            }
            is IrStmt.Defer -> {}
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
            "$name(${expr.args.joinToString(", ") { emitExpr(it) }})"
        }
        is IrExpr.ArrayLiteral -> "[${expr.elements.joinToString(", ") { emitExpr(it) }}]"
        is IrExpr.MapLit -> "({ ${expr.entries.joinToString(", ") { "[${emitExpr(it.first)}]: ${emitExpr(it.second)}" }} })"
        is IrExpr.Index -> "${emitExpr(expr.target)}[${emitExpr(expr.index)}]"
        is IrExpr.Member -> {
            val prop = when (expr.name) {
                "isEmpty" -> "length === 0"
                "isNotEmpty" -> "length !== 0"
                else -> expr.name
            }
            "${emitExpr(expr.target)}.$prop"
        }
        is IrExpr.MethodCall -> {
            val call = when (expr.name) {
                "add" -> "push(${expr.args.joinToString(", ") { emitExpr(it) }})"
                "isEmpty" -> "length === 0"
                "isNotEmpty" -> "length !== 0"
                else -> "${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
            }
            "${emitExpr(expr.target)}.$call"
        }
        is IrExpr.StructCtor -> "new ${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        is IrExpr.TupleLit -> "[${expr.elements.joinToString(", ") { emitExpr(it) }}]"
        is IrExpr.TupleAccess -> "${emitExpr(expr.target)}[${expr.index}]"
        is IrExpr.CatchExpr -> "(() => { try { return ${emitExpr(expr.expr)}; } catch { return ${emitExpr(expr.fallback)}; } })()"
        is IrExpr.SlotPattern -> "" /* handled by when lowering */
        is IrExpr.Lambda -> {
            val ps = expr.params.joinToString(", ") { (n, _) -> n }
            val ret = expr.body.singleOrNull() as? IrStmt.Return
            if (ret != null && ret.value != null) "($ps) => ${emitExpr(ret.value)}"
            else if (ret != null) "($ps) => {}"
            else "($ps) => {}"
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
        is IrType.Array -> "${mapType(type.element)}[]"
        is IrType.Map -> "Record<${mapType(type.key)}, ${mapType(type.value)}>"
        is IrType.Pointer -> "AzoraPtr<${mapType(type.inner)}>"
        is IrType.Function -> "(${type.params.joinToString(", ") { mapType(it) }}) => ${mapType(type.ret)}"
        is IrType.Tuple -> "[${type.elements.joinToString(", ") { mapType(it) }}]"
        is IrType.Nullable -> "${mapType(type.inner)} | null"
        is IrType.Named -> type.name
        IrType.Any -> "any"
        is IrType.Tuple -> "any[]"
        is IrType.Function -> "(${type.params.joinToString(", ") { mapType(it) }}) => ${mapType(type.ret)}"
    }

    private fun escapeString(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")

    private fun line(text: String) {
        repeat(indent) { out.append("    ") }
        out.appendLine(text)
    }
}
