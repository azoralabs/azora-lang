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
import org.azora.lang.ir.IrUnaryOp

/**
 * Backend — interprets [IrProgram] directly instead of generating code.
 *
 * Uses a scope stack so that `zone { }` blocks introduce new scopes and
 * `::` / `::::` can resolve variables at the correct depth.
 */
class IrInterpreter {

    private val output = StringBuilder()
    private val functions = mutableMapOf<String, IrFunction>()

    /** Scope stack: index 0 = global, last = innermost. */
    private val scopes = ArrayDeque<MutableMap<String, Any?>>()

    fun interpret(program: IrProgram): String {
        output.clear()
        functions.clear()
        scopes.clear()

        // Global scope
        scopes.addLast(mutableMapOf())

        // Collect tests
        val tests = mutableListOf<IrTopLevel.Test>()

        // Process top-level items in source order
        for (item in program.items) {
            when (item) {
                is IrTopLevel.Global -> executeStmt(item.stmt)
                is IrTopLevel.Func -> functions[item.function.name] = item.function
                is IrTopLevel.Test -> tests.add(item)
                is IrTopLevel.Struct -> { /* struct definitions need no execution */ }
            }
        }

        // Execute main
        val main = functions["main"] ?: error("No 'main' function found")
        executeFunction(main, emptyList())

        // Execute tests after main
        for (test in tests) {
            executeTest(test)
        }

        return output.toString().trimEnd()
    }

    private fun executeTest(test: IrTopLevel.Test) {
        pushScope()
        try {
            executeBody(test.body)
        } finally {
            popScope()
        }
    }

    // -- Scope management ---------------------------------------------------

    private fun pushScope() { scopes.addLast(mutableMapOf()) }
    private fun popScope() { scopes.removeLast() }

    private fun defineVar(name: String, value: Any?) {
        scopes.last()[name] = value
    }

    private fun assignVar(name: String, value: Any?) {
        // Search from innermost to outermost for existing binding
        for (i in scopes.indices.reversed()) {
            if (name in scopes[i]) {
                scopes[i][name] = value
                return
            }
        }
        scopes.last()[name] = value
    }

    /** Look up variable from innermost scope outward. */
    private fun lookupVar(name: String): Any? {
        for (i in scopes.indices.reversed()) {
            if (name in scopes[i]) return scopes[i][name]
        }
        return null
    }

    // -- Execution ----------------------------------------------------------

    private fun executeFunction(func: IrFunction, args: List<Any?>): Any? {
        pushScope()

        // Bind parameters
        for (i in func.params.indices) {
            defineVar(func.params[i].first, args[i])
        }

        val result = executeBody(func.body)
        popScope()
        return (result as? ReturnSignal)?.value
    }

    private fun executeBody(body: List<IrStmt>): Any? {
        for (stmt in body) {
            val result = executeStmt(stmt)
            if (result is ControlSignal) return result
        }
        return null
    }

    private fun executeStmt(stmt: IrStmt): Any? {
        when (stmt) {
            is IrStmt.VarDecl -> defineVar(stmt.name, evalExpr(stmt.initializer))
            is IrStmt.FinDecl -> defineVar(stmt.name, evalExpr(stmt.initializer))
            is IrStmt.LetDecl -> defineVar(stmt.name, evalExpr(stmt.initializer))
            is IrStmt.Assignment -> assignVar(stmt.name, evalExpr(stmt.value))
            is IrStmt.Return -> {
                val value = stmt.value?.let { evalExpr(it) }
                return ReturnSignal(value)
            }
            is IrStmt.ExprStmt -> evalExpr(stmt.expr)
            is IrStmt.If -> {
                val cond = evalExpr(stmt.condition) as Boolean
                val branch = if (cond) stmt.thenBranch else stmt.elseBranch
                if (branch != null) {
                    val result = executeBody(branch)
                    if (result is ControlSignal) return result
                }
            }
            is IrStmt.Zone -> {
                pushScope()
                val result = executeBody(stmt.body)
                popScope()
                if (result is ControlSignal) return result
            }
            is IrStmt.While -> {
                while (evalExpr(stmt.condition) as Boolean) {
                    pushScope()
                    val result = executeBody(stmt.body)
                    popScope()
                    if (result is BreakSignal) break
                    if (result is ReturnSignal) return result
                    // ContinueSignal or null → loop again
                }
            }
            is IrStmt.For -> {
                val start = evalExpr(stmt.start) as Long
                val end = evalExpr(stmt.end) as Long
                var i = start
                while (if (stmt.inclusive) i <= end else i < end) {
                    pushScope()
                    defineVar(stmt.counter, i)
                    val result = executeBody(stmt.body)
                    popScope()
                    if (result is BreakSignal) break
                    if (result is ReturnSignal) return result
                    i++
                    // ContinueSignal or null falls through to the increment above
                }
            }
            is IrStmt.Loop -> {
                while (true) {
                    pushScope()
                    val result = executeBody(stmt.body)
                    popScope()
                    if (result is BreakSignal) break
                    if (result is ReturnSignal) return result
                    // ContinueSignal or null → loop again
                }
            }
            is IrStmt.Break -> return BreakSignal
            is IrStmt.Continue -> return ContinueSignal
            is IrStmt.IndexAssign -> {
                @Suppress("UNCHECKED_CAST")
                val list = evalExpr(stmt.target) as MutableList<Any?>
                val idx = (evalExpr(stmt.index) as Long).toInt()
                list[idx] = evalExpr(stmt.value)
            }
            is IrStmt.MemberAssign -> {
                @Suppress("UNCHECKED_CAST")
                val map = evalExpr(stmt.target) as MutableMap<String, Any?>
                map[stmt.name] = evalExpr(stmt.value)
            }
            is IrStmt.Assert -> {
                val cond = evalExpr(stmt.condition) as Boolean
                if (!cond) {
                    val msg = formatValue(evalExpr(stmt.message))
                    error("Assertion failed: $msg")
                }
            }
            is IrStmt.Trace -> {
                val msg = formatValue(evalExpr(stmt.message))
                output.appendLine("[TRACE] $msg")
            }
        }
        return null
    }

    private fun evalExpr(expr: IrExpr): Any? {
        return when (expr) {
            is IrExpr.IntLiteral -> expr.value
            is IrExpr.RealLiteral -> expr.value
            is IrExpr.StringLiteral -> expr.value
            is IrExpr.BoolLiteral -> expr.value
            is IrExpr.CharLiteral -> expr.value
            is IrExpr.Var -> lookupVar(expr.name)
            is IrExpr.Unary -> {
                val operand = evalExpr(expr.operand)
                when (expr.op) {
                    IrUnaryOp.NEG -> when (operand) {
                        is Long -> -operand
                        is Double -> -operand
                        else -> error("Cannot negate $operand")
                    }
                    IrUnaryOp.NOT -> !(operand as Boolean)
                }
            }
            is IrExpr.Binary -> evalBinary(expr)
            is IrExpr.Call -> evalCall(expr)
            is IrExpr.ArrayLiteral -> expr.elements.map { evalExpr(it) }.toMutableList()
            is IrExpr.Index -> {
                @Suppress("UNCHECKED_CAST")
                val list = evalExpr(expr.target) as MutableList<Any?>
                val idx = (evalExpr(expr.index) as Long).toInt()
                list[idx]
            }
            is IrExpr.Member -> {
                val receiver = evalExpr(expr.target)
                when (receiver) {
                    is MutableList<*> -> when (expr.name) {
                        "length" -> receiver.size.toLong()
                        "isEmpty" -> receiver.isEmpty()
                        "isNotEmpty" -> receiver.isNotEmpty()
                        else -> error("no member '${expr.name}' on array")
                    }
                    is String -> if (expr.name == "length") receiver.length.toLong() else error("no member '${expr.name}' on string")
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        (receiver as Map<String, Any?>)[expr.name]
                    }
                    else -> error("no member '${expr.name}' on $receiver")
                }
            }
            is IrExpr.StructCtor -> {
                val map = linkedMapOf<String, Any?>()
                for (i in expr.fieldNames.indices) {
                    map[expr.fieldNames[i]] = evalExpr(expr.args[i])
                }
                map
            }
            is IrExpr.StringTemplate -> {
                val sb = StringBuilder()
                for (part in expr.parts) {
                    when (part) {
                        is IrExpr.IrTemplatePart.Literal -> sb.append(part.text)
                        is IrExpr.IrTemplatePart.Expr -> sb.append(formatValue(evalExpr(part.expr)))
                    }
                }
                sb.toString()
            }
            is IrExpr.MethodCall -> {
                val receiver = evalExpr(expr.target)
                val args = expr.args.map { evalExpr(it) }
                when (expr.name) {
                    "add" -> {
                        @Suppress("UNCHECKED_CAST")
                        (receiver as MutableList<Any?>).add(args[0])
                        null
                    }
                    "isEmpty" -> (receiver as List<*>).isEmpty()
                    "isNotEmpty" -> (receiver as List<*>).isNotEmpty()
                    else -> error("no method '${expr.name}' on $receiver")
                }
            }
        }
    }

    private fun evalBinary(expr: IrExpr.Binary): Any {
        val left = evalExpr(expr.left)
        val right = evalExpr(expr.right)

        return when (expr.op) {
            IrBinaryOp.ADD -> when (left) {
                is Long if right is Long -> left + right
                is Double if right is Double -> left + right
                is String if right is String -> left + right
                else -> error("Cannot add $left and $right")
            }
            IrBinaryOp.SUB -> when (left) {
                is Long if right is Long -> left - right
                is Double if right is Double -> left - right
                else -> error("Cannot subtract $left and $right")
            }
            IrBinaryOp.MUL -> when (left) {
                is Long if right is Long -> left * right
                is Double if right is Double -> left * right
                else -> error("Cannot multiply $left and $right")
            }
            IrBinaryOp.DIV -> when (left) {
                is Long if right is Long -> left / right
                is Double if right is Double -> left / right
                else -> error("Cannot divide $left and $right")
            }
            IrBinaryOp.MOD -> when (left) {
                is Long if right is Long -> left % right
                is Double if right is Double -> left % right
                else -> error("Cannot modulo $left and $right")
            }
            IrBinaryOp.EQ -> left == right
            IrBinaryOp.NEQ -> left != right
            IrBinaryOp.LT -> compare(left, right) < 0
            IrBinaryOp.LTE -> compare(left, right) <= 0
            IrBinaryOp.GT -> compare(left, right) > 0
            IrBinaryOp.GTE -> compare(left, right) >= 0
            IrBinaryOp.AND -> (left as Boolean) && (right as Boolean)
            IrBinaryOp.OR -> (left as Boolean) || (right as Boolean)
        }
    }

    private fun compare(left: Any?, right: Any?): Int {
        return when {
            left is Long && right is Long -> left.compareTo(right)
            left is Double && right is Double -> left.compareTo(right)
            left is Char && right is Char -> left.compareTo(right)
            else -> error("Cannot compare $left and $right")
        }
    }

    private fun evalCall(expr: IrExpr.Call): Any? {
        val args = expr.args.map { evalExpr(it) }

        if (expr.name == "println") {
            val value = args.firstOrNull()
            output.appendLine(formatValue(value))
            return null
        }

        val func = functions[expr.name] ?: error("Undefined function: ${expr.name}")
        return executeFunction(func, args)
    }

    private fun formatValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Long -> value.toString()
        is Double -> value.toString()
        is Boolean -> value.toString()
        is Char -> value.toString()
        else -> value.toString()
    }

    /** Control-flow signal raised by `return`/`break`/`continue`. */
    private sealed class ControlSignal
    private data class ReturnSignal(val value: Any?) : ControlSignal()
    private object BreakSignal : ControlSignal()
    private object ContinueSignal : ControlSignal()
}
