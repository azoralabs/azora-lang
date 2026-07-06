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
 * Backend — lowers [IrProgram] to Kotlin source code.
 */
class KotlinCodegen {

    private val out = StringBuilder()
    private var indent = 0
    private var usesPointers = false

    /** Runtime helpers emitted as a preamble when pointer/isolated ops are used. */
    private val POINTER_RUNTIME = setOf("__alloc", "__deref", "__derefAssign", "__isolated")

    private val pointerPreamble: String = """
        private class AzoraPtr<T>(var value: T)
        private fun <T> __alloc(v: T): AzoraPtr<T> = AzoraPtr(v)
        private fun <T> __deref(p: AzoraPtr<T>): T = p.value
        private fun <T> __derefAssign(p: AzoraPtr<T>, v: T) { p.value = v }
        // NOTE: __isolated is a shallow copy in the Kotlin backend (no deep-copy for arbitrary types).
        private fun <T> __isolated(v: T): T = v
    """.trimIndent()

    /**
     * Generates Kotlin source code from the given IR program.
     *
     * @param program the optimized IR program to lower to Kotlin
     * @return the generated Kotlin source code as a string
     */
    fun generate(program: IrProgram): String {
        out.clear()
        indent = 0
        usesPointers = false

        if (program.packageName != null) {
            line("package ${program.packageName}")
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
                    val fields = item.fields.joinToString(", ") { f ->
                        val kw = if (f.mutable) "var" else "val"
                        "$kw ${f.name}: ${mapType(f.type)}"
                    }
                    line("class ${item.name}($fields)")
                    if (i < program.items.lastIndex) line("")
                }
                is IrTopLevel.Extern -> {
                    val params = item.params.joinToString(", ") { (n, t) -> "$n: ${mapType(t)}" }
                    line("external fun ${item.name}($params): ${mapType(item.returnType)}")
                    if (i < program.items.lastIndex) line("")
                }
            }
        }

        val body = out.toString().trimEnd()
        return if (usesPointers) pointerPreamble + "\n\n" + body else body
    }

    private fun emitTest(test: IrTopLevel.Test) {
        val safeName = test.name.replace(" ", "_").replace("\"", "")
        line("@org.junit.Test")
        line("fun `test $safeName`() {")
        indent++
        for (stmt in test.body) emitStmt(stmt)
        indent--
        line("}")
    }

    private fun emitFunction(func: IrFunction) {
        val params = func.params.joinToString(", ") { (name, type) ->
            "$name: ${mapType(type)}"
        }
        // Kotlin requires main() to return Unit — wrap non-Unit main
        if (func.name == "main" && func.returnType != IrType.Unit) {
            line("fun __azora_main($params): ${mapType(func.returnType)} {")
            indent++
            for (stmt in func.body) emitStmt(stmt)
            indent--
            line("}")
            line("")
            line("fun main(): Unit {")
            indent++
            line("__azora_main()")
            indent--
            line("}")
        } else {
            line("fun ${func.name}($params): ${mapType(func.returnType)} {")
            indent++
            for (stmt in func.body) emitStmt(stmt)
            indent--
            line("}")
        }
    }

    private fun emitStmt(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> line("var ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)}")
            is IrStmt.FinDecl -> line("val ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)}")
            is IrStmt.LetDecl -> line("val ${stmt.name}: ${mapType(stmt.type)} = ${emitExpr(stmt.initializer)}")
            is IrStmt.Assignment -> line("${stmt.name} = ${emitExpr(stmt.value)}")
            is IrStmt.IndexAssign -> line("${emitExpr(stmt.target)}[${emitExpr(stmt.index)}] = ${emitExpr(stmt.value)}")
            is IrStmt.MemberAssign -> line("${emitExpr(stmt.target)}.${stmt.name} = ${emitExpr(stmt.value)}")
            is IrStmt.When -> {
                line("when (${emitExpr(stmt.scrutinee)}) {")
                indent++
                for (b in stmt.branches) {
                    val pats = b.patterns.joinToString(", ") { emitExpr(it) }
                    line("$pats -> {")
                    indent++
                    for (s in b.body) emitStmt(s)
                    indent--
                    line("}")
                }
                if (stmt.elseBranch != null) {
                    line("else -> {")
                    indent++
                    for (s in stmt.elseBranch) emitStmt(s)
                    indent--
                    line("}")
                }
                indent--
                line("}")
            }
            is IrStmt.Return -> {
                if (stmt.value != null) line("return ${emitExpr(stmt.value)}")
                else line("return")
            }
            is IrStmt.ExprStmt -> line(emitExpr(stmt.expr))
            is IrStmt.Zone -> {
                line("run {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Assert -> {
                line("require(${emitExpr(stmt.condition)}) { ${emitExpr(stmt.message)} }")
            }
            is IrStmt.Trace -> {
                line("println(\"[TRACE] \" + ${emitExpr(stmt.message)})")
            }
            is IrStmt.If -> {
                line("if (${emitExpr(stmt.condition)}) {")
                indent++
                for (s in stmt.thenBranch) emitStmt(s)
                indent--
                if (stmt.elseBranch != null) {
                    // Check if else branch is a single If (else if chain)
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
                val lbl = if (stmt.label != null) "${stmt.label}@ " else ""
                line("${lbl}while (${emitExpr(stmt.condition)}) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.For -> {
                val lbl = if (stmt.label != null) "${stmt.label}@ " else ""
                val bounds = when {
                    stmt.reverse -> "${emitExpr(stmt.end)} downTo ${emitExpr(stmt.start)}"
                    stmt.inclusive -> "${emitExpr(stmt.start)}..${emitExpr(stmt.end)}"
                    else -> "${emitExpr(stmt.start)} until ${emitExpr(stmt.end)}"
                }
                val stepPart = if (stmt.step != null) " step ${emitExpr(stmt.step)}" else ""
                line("${lbl}for (${stmt.counter} in $bounds$stepPart) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.ForEach -> {
                line("for (${stmt.elem} in ${emitExpr(stmt.iterable)}) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Loop -> {
                val lbl = if (stmt.label != null) "${stmt.label}@ " else ""
                line("${lbl}while (true) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Throw -> line("throw RuntimeException(\"\" + ${emitExpr(stmt.value)})")
            is IrStmt.Try -> {
                line("try {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                if (stmt.catchBody != null) {
                    line("} catch (_e: RuntimeException) {")
                    indent++
                    if (stmt.catchName != null) line("val ${stmt.catchName} = _e.message")
                    for (s in stmt.catchBody) emitStmt(s)
                    indent--
                }
                line("}")
            }
            is IrStmt.Defer -> {}
            is IrStmt.Yield -> {}
            is IrStmt.Break -> line(if (stmt.label != null) "break@${stmt.label}" else "break")
            is IrStmt.Continue -> line(if (stmt.label != null) "continue@${stmt.label}" else "continue")
        }
    }

    private fun emitExpr(expr: IrExpr): String = when (expr) {
        is IrExpr.IntLiteral -> when (expr.type) {
            IrType.Long, IrType.Cent -> "${expr.value}L"
            IrType.ULong, IrType.UCent -> "${expr.value}uL"
            IrType.Byte -> "${expr.value}.toByte()"
            IrType.UByte -> "${expr.value}.toUByte()"
            IrType.Short -> "${expr.value}.toShort()"
            IrType.UShort -> "${expr.value}.toUShort()"
            IrType.UInt -> "${expr.value}u"
            else -> "${expr.value}"
        }
        is IrExpr.RealLiteral -> when (expr.type) {
            IrType.Float -> "${expr.value}f"
            else -> "${expr.value}"
        }
        is IrExpr.StringLiteral -> "\"${escapeString(expr.value)}\""
        is IrExpr.BoolLiteral -> "${expr.value}"
        is IrExpr.CharLiteral -> "'${escapeChar(expr.value)}'"
        is IrExpr.Var -> expr.name
        is IrExpr.Unary -> {
            val op = when (expr.op) {
                IrUnaryOp.NEG -> "-"
                IrUnaryOp.NOT -> "!"
                IrUnaryOp.BIT_NOT -> "notValidKotlin"
            }
            "($op${emitExpr(expr.operand)})"
        }
        is IrExpr.Binary -> {
            // String * Int -> "str".repeat(n)
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
                    IrBinaryOp.EQ -> "=="
                    IrBinaryOp.NEQ -> "!="
                    IrBinaryOp.LT -> "<"
                    IrBinaryOp.LTE -> "<="
                    IrBinaryOp.GT -> ">"
                    IrBinaryOp.GTE -> ">="
                    IrBinaryOp.AND -> "&&"
                    IrBinaryOp.OR -> "||"
                IrBinaryOp.BIT_AND -> "&"; IrBinaryOp.BIT_OR -> "|"; IrBinaryOp.BIT_XOR -> "^"
                IrBinaryOp.SHL -> "shl"; IrBinaryOp.SHR -> "shr"
                }
                "(${emitExpr(expr.left)} $op ${emitExpr(expr.right)})"
            }
        }
        is IrExpr.Call -> {
            if (expr.name in POINTER_RUNTIME) usesPointers = true
            "${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        }
        is IrExpr.Await -> emitExpr(expr.value) // no coroutine runtime: emit the task inline
        is IrExpr.Spread -> "*${emitExpr(expr.array)}" // Kotlin spread operator
        is IrExpr.ArrayLiteral -> "mutableListOf(${expr.elements.joinToString(", ") { emitExpr(it) }})"
        is IrExpr.MapLit -> "mutableMapOf(${expr.entries.joinToString(", ") { "${emitExpr(it.first)} to ${emitExpr(it.second)}" }})"
        is IrExpr.Index -> "${emitExpr(expr.target)}[${emitExpr(expr.index)}]"
        is IrExpr.Member -> {
            val prop = when (expr.name) {
                "length" -> if (expr.target.type == IrType.String) "length" else "size"
                "isEmpty" -> "isEmpty()"
                "isNotEmpty" -> "isNotEmpty()"
                else -> expr.name
            }
            "${emitExpr(expr.target)}.$prop"
        }
        is IrExpr.MethodCall -> {
            val call = when (expr.name) {
                "isEmpty" -> "isEmpty()"
                "isNotEmpty" -> "isNotEmpty()"
                else -> "${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
            }
            "${emitExpr(expr.target)}.$call"
        }
        is IrExpr.StructCtor -> "${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        is IrExpr.TupleLit -> "listOf(${expr.elements.joinToString(", ") { emitExpr(it) }})"
        is IrExpr.TupleAccess -> "${emitExpr(expr.target)}[${expr.index}]"
        is IrExpr.CatchExpr -> "runCatching { ${emitExpr(expr.expr)} }.getOrDefault(${emitExpr(expr.fallback)})"
        is IrExpr.NumCast -> {
            val sourceNumeric = expr.value.type in IrType.integerTypes ||
                expr.value.type in IrType.floatTypes || expr.value.type == IrType.Char
            val conv = if (!sourceNumeric) "" else when (expr.type) {
                IrType.Int -> ".toInt()"
                IrType.UInt -> ".toUInt()"
                IrType.Byte -> ".toByte()"
                IrType.UByte -> ".toUByte()"
                IrType.Short -> ".toShort()"
                IrType.UShort -> ".toUShort()"
                IrType.Long, IrType.Cent -> ".toLong()"
                IrType.ULong, IrType.UCent -> ".toULong()"
                IrType.Float -> ".toFloat()"
                IrType.Real, IrType.Decimal -> ".toDouble()"
                IrType.Char -> ".toInt().toChar()"
                else -> ""
            }
            "(${emitExpr(expr.value)})$conv"
        }
        is IrExpr.SlotPattern -> "" /* handled by when lowering */
        is IrExpr.Lambda -> {
            val ps = expr.params.joinToString(", ") { (n, t) -> "$n: ${mapType(t)}" }
            val ret = expr.body.singleOrNull() as? IrStmt.Return
            if (ret != null) "{ $ps -> ${if (ret.value != null) emitExpr(ret.value) else ""} }"
            else "{ $ps -> Unit }"
        }
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

    private fun mapType(type: IrType): String = when (type) {
        IrType.Int -> "Int"
        IrType.UInt -> "UInt"
        IrType.Real -> "Double"
        IrType.String -> "String"
        IrType.Bool -> "Boolean"
        IrType.Unit -> "Unit"
        IrType.Char -> "Char"
        IrType.Byte -> "Byte"
        IrType.UByte -> "UByte"
        IrType.Short -> "Short"
        IrType.UShort -> "UShort"
        IrType.Long -> "Long"
        IrType.ULong -> "ULong"
        IrType.Cent -> "Long"       // best available JVM type
        IrType.UCent -> "ULong"      // best available JVM type
        IrType.Float -> "Float"
        IrType.Decimal -> "Double"   // best available JVM type
        is IrType.Array -> "MutableList<${mapType(type.element)}>"
        is IrType.Map -> "MutableMap<${mapType(type.key)}, ${mapType(type.value)}>"
        is IrType.Pointer -> "AzoraPtr<${mapType(type.inner)}>"
        is IrType.Function -> "(${type.params.joinToString(", ") { mapType(it) }}) -> ${mapType(type.ret)}"
        is IrType.Tuple -> when (type.elements.size) {
            2 -> "Pair<${type.elements.joinToString(", ") { mapType(it) }}>"
            3 -> "Triple<${type.elements.joinToString(", ") { mapType(it) }}>"
            else -> "List<Any>" // N-ary tuples fall back until a dedicated tuple type exists
        }
        is IrType.Nullable -> "${mapType(type.inner)}?"
        is IrType.Named -> type.name
        IrType.Any -> "Any"
        is IrType.Tuple -> "List<Any>"
        is IrType.Function -> "(${type.params.joinToString(", ") { mapType(it) }}) -> ${mapType(type.ret)}"
    }

    private fun escapeString(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")

    private fun escapeChar(c: Char): String = when (c) {
        '\n' -> "\\n"
        '\t' -> "\\t"
        '\r' -> "\\r"
        '\\' -> "\\\\"
        '\'' -> "\\'"
        '\u0000' -> "\\u0000"
        else -> if (c.code in 32..126) "$c" else "\\u${c.code.toString(16).padStart(4, '0')}"
    }

    private fun line(text: String) {
        repeat(indent) { out.append("    ") }
        out.appendLine(text)
    }
}
