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

    /**
     * Generates Kotlin source code from the given IR program.
     *
     * @param program the optimized IR program to lower to Kotlin
     * @return the generated Kotlin source code as a string
     */
    fun generate(program: IrProgram): String {
        out.clear()
        indent = 0

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
            }
        }

        return out.toString().trimEnd()
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
                line("while (${emitExpr(stmt.condition)}) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.For -> {
                val bounds = if (stmt.inclusive) {
                    "${emitExpr(stmt.start)}..${emitExpr(stmt.end)}"
                } else {
                    "${emitExpr(stmt.start)} until ${emitExpr(stmt.end)}"
                }
                line("for (${stmt.counter} in $bounds) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Loop -> {
                line("while (true) {")
                indent++
                for (s in stmt.body) emitStmt(s)
                indent--
                line("}")
            }
            is IrStmt.Break -> line("break")
            is IrStmt.Continue -> line("continue")
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
                }
                "(${emitExpr(expr.left)} $op ${emitExpr(expr.right)})"
            }
        }
        is IrExpr.Call -> "${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        is IrExpr.ArrayLiteral -> "mutableListOf(${expr.elements.joinToString(", ") { emitExpr(it) }})"
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
        is IrType.Set -> "MutableSet<${mapType(type.element)}>"
        is IrType.Function -> "(${type.params.joinToString(", ") { mapType(it) }}) -> ${mapType(type.ret)}"
        is IrType.Tuple -> when (type.elements.size) {
            2 -> "Pair<${type.elements.joinToString(", ") { mapType(it) }}>"
            3 -> "Triple<${type.elements.joinToString(", ") { mapType(it) }}>"
            else -> "List<Any>" // N-ary tuples fall back until a dedicated tuple type exists
        }
        is IrType.Nullable -> "${mapType(type.inner)}?"
        is IrType.Named -> type.name
        IrType.Any -> "Any"
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
