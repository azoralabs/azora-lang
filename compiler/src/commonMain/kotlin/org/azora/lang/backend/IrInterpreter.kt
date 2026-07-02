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
    private var deferStack = mutableListOf<DeferredBlock>()
    /** Stack of yield accumulators — one per active `flow` generator call. */
    private val yieldAccumulators = ArrayDeque<MutableList<Any?>>()

    /** A deferred block, optionally restricted to run only on error (`fail defer`). */
    private class DeferredBlock(val body: List<IrStmt>, val onFail: Boolean)

    /** Scope stack: index 0 = global, last = innermost. */
    private var scopes = ArrayDeque<MutableMap<String, Any?>>()

    fun interpret(program: IrProgram): String {
        output.clear()
        functions.clear()
        scopes.clear()
        yieldAccumulators.clear()

        // Global scope
        scopes.addLast(mutableMapOf("__null" to null))

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

        val savedDefers = deferStack
        deferStack = mutableListOf()
        var retValue: Any? = null
        var failed = false
        var toRethrow: AzoraThrownException? = null
        try {
            val result = executeBody(func.body)
            retValue = (result as? ReturnSignal)?.value
        } catch (e: AzoraThrownException) {
            // The function exited via `throw`/`fail` — fail-defers should run.
            failed = true
            toRethrow = e
        } finally {
            // Run deferred blocks in reverse order (LIFO). Skip `fail defer`
            // blocks when the function returned normally.
            for (i in deferStack.indices.reversed()) {
                val d = deferStack[i]
                if (d.onFail && !failed) continue
                executeBody(d.body)
            }
            deferStack = savedDefers
        }
        popScope()
        if (toRethrow != null) throw toRethrow
        return retValue
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
                    when (result) {
                        is ReturnSignal -> return result
                        is BreakSignal -> {
                            // Consume unlabeled break or one aimed at this loop; else propagate.
                            if (result.label == null || result.label == stmt.label) break
                            return result
                        }
                        is ContinueSignal -> {
                            if (result.label != null && result.label != stmt.label) return result
                            // else fall through to the next iteration
                        }
                    }
                }
            }
            is IrStmt.For -> {
                val start = evalExpr(stmt.start) as Long
                val end = evalExpr(stmt.end) as Long
                val step = (stmt.step?.let { evalExpr(it) as Long } ?: 1L)
                var i = if (stmt.reverse) end else start
                while (if (stmt.reverse) i >= start else if (stmt.inclusive) i <= end else i < end) {
                    pushScope()
                    defineVar(stmt.counter, i)
                    val result = executeBody(stmt.body)
                    popScope()
                    when (result) {
                        is ReturnSignal -> return result
                        is BreakSignal -> {
                            if (result.label == null || result.label == stmt.label) break
                            return result
                        }
                        is ContinueSignal -> {
                            if (result.label != null && result.label != stmt.label) return result
                        }
                    }
                    i = if (stmt.reverse) i - step else i + step
                }
            }
            is IrStmt.Loop -> {
                while (true) {
                    pushScope()
                    val result = executeBody(stmt.body)
                    popScope()
                    when (result) {
                        is ReturnSignal -> return result
                        is BreakSignal -> {
                            if (result.label == null || result.label == stmt.label) break
                            return result
                        }
                        is ContinueSignal -> {
                            if (result.label != null && result.label != stmt.label) return result
                        }
                    }
                }
            }
            is IrStmt.Break -> return BreakSignal(stmt.label)
            is IrStmt.Continue -> return ContinueSignal(stmt.label)
            is IrStmt.Defer -> { deferStack.add(DeferredBlock(stmt.body, stmt.onFail)) }
            is IrStmt.Yield -> { yieldAccumulators.lastOrNull()?.add(evalExpr(stmt.value)) }
            is IrStmt.IndexAssign -> {
                val target = evalExpr(stmt.target)
                val key = evalExpr(stmt.index)
                val value = evalExpr(stmt.value)
                when (target) {
                    is MutableMap<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        (target as MutableMap<Any?, Any?>)[key] = value
                    }
                    is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        (target as MutableList<Any?>)[(key as Long).toInt()] = value
                    }
                    else -> error("Cannot index-assign to $target")
                }
            }
            is IrStmt.MemberAssign -> {
                @Suppress("UNCHECKED_CAST")
                val map = evalExpr(stmt.target) as MutableMap<String, Any?>
                map[stmt.name] = evalExpr(stmt.value)
            }
            is IrStmt.When -> {
                val scrut = evalExpr(stmt.scrutinee)
                var matched = false
                for (b in stmt.branches) {
                    var hit = false
                    for (p in b.patterns) {
                        if (p is IrExpr.SlotPattern) {
                            val scrutMap = scrut as? Map<*, *>
                            if (scrutMap != null && scrutMap["__tag"] == p.variantName) {
                                for (i in p.bindings.indices) {
                                    defineVar(p.bindings[i], scrutMap["__$i"])
                                }
                                hit = true; break
                            }
                        } else if (evalExpr(p) == scrut) { hit = true; break }
                    }
                    if (hit) {
                        matched = true
                        pushScope()
                        val result = executeBody(b.body)
                        popScope()
                        if (result is ControlSignal) return result
                        break
                    }
                }
                if (!matched && stmt.elseBranch != null) {
                    pushScope()
                    val result = executeBody(stmt.elseBranch)
                    popScope()
                    if (result is ControlSignal) return result
                }
            }
            is IrStmt.Throw -> throw AzoraThrownException(evalExpr(stmt.value))
            is IrStmt.Try -> {
                pushScope()
                var thrown: AzoraThrownException? = null
                var signal: ControlSignal? = null
                try {
                    val result = executeBody(stmt.body)
                    if (result is ControlSignal) signal = result
                } catch (e: AzoraThrownException) {
                    thrown = e
                }
                popScope()
                if (signal != null) return signal
                if (thrown != null && stmt.catchBody != null) {
                    pushScope()
                    if (stmt.catchName != null) defineVar(stmt.catchName, thrown.value)
                    val result = executeBody(stmt.catchBody)
                    popScope()
                    if (result is ControlSignal) return result
                } else if (thrown != null) {
                    throw thrown
                }
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
                    IrUnaryOp.BIT_NOT -> {
                        if (operand is Long) operand.inv() else error("Cannot bitwise-NOT $operand")
                    }
                }
            }
            is IrExpr.Binary -> evalBinary(expr)
            is IrExpr.Call -> evalCall(expr)
            is IrExpr.ArrayLiteral -> expr.elements.map { evalExpr(it) }.toMutableList()
            is IrExpr.MapLit -> {
                val map = linkedMapOf<Any?, Any?>()
                for ((k, v) in expr.entries) map[evalExpr(k)] = evalExpr(v)
                map
            }
            is IrExpr.Index -> {
                val target = evalExpr(expr.target)
                val key = evalExpr(expr.index)
                when (target) {
                    is MutableMap<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        (target as MutableMap<Any?, Any?>)[key]
                    }
                    is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        (target as MutableList<Any?>)[(key as Long).toInt()]
                    }
                    else -> error("Cannot index into $target")
                }
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
                    is String -> when (expr.name) {
                        "length" -> receiver.length.toLong()
                        "isEmpty" -> receiver.isEmpty()
                        "isNotEmpty" -> receiver.isNotEmpty()
                        else -> error("no member '${expr.name}' on string")
                    }
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
            is IrExpr.TupleLit -> expr.elements.map { evalExpr(it) }
            is IrExpr.TupleAccess -> {
                @Suppress("UNCHECKED_CAST")
                val list = evalExpr(expr.target) as List<Any?>
                list[expr.index]
            }
            is IrExpr.CatchExpr -> {
                try { evalExpr(expr.expr) } catch (e: AzoraThrownException) { evalExpr(expr.fallback) }
            }
            is IrExpr.Lambda -> Closure(expr.params, expr.body, scopes.toList())
            is IrExpr.SlotPattern -> error("SlotPattern should be handled by when matching, not evaluated")
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
                when {
                    receiver is String -> when (expr.name) {
                        "toUpperCase" -> receiver.uppercase()
                        "toLowerCase" -> receiver.lowercase()
                        "contains" -> receiver.contains(args[0] as String)
                        "startsWith" -> receiver.startsWith(args[0] as String)
                        "endsWith" -> receiver.endsWith(args[0] as String)
                        "trim" -> receiver.trim()
                        "replace" -> receiver.replace(args[0] as String, args[1] as String)
                        "split" -> receiver.split(args[0] as String).toMutableList()
                        "indexOf" -> receiver.indexOf(args[0] as String).toLong()
                        else -> error("no method '${expr.name}' on String")
                    }
                    receiver is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val list = receiver as MutableList<Any?>
                        when (expr.name) {
                            "add" -> { list.add(args[0]); null }
                            "insert" -> { list.add((args[0] as Long).toInt(), args[1]); null }
                            "remove" -> { list.removeAt((args[0] as Long).toInt()); null }
                            "contains" -> list.contains(args[0])
                            "indexOf" -> list.indexOf(args[0]).toLong()
                            "isEmpty" -> list.isEmpty()
                            "isNotEmpty" -> list.isNotEmpty()
                            else -> error("no method '${expr.name}' on array")
                        }
                    }
                    else -> error("no method '${expr.name}' on $receiver")
                }
            }
        }
    }

    private fun evalBinary(expr: IrExpr.Binary): Any {
        val left = evalExpr(expr.left)
        val right = evalExpr(expr.right)

        return when (expr.op) {
            IrBinaryOp.ADD -> when {
                left is String || right is String -> formatValue(left) + formatValue(right)
                left is Long && right is Long -> left + right
                left is Number && right is Number -> toNum(left) + toNum(right)
                else -> error("Cannot add $left and $right")
            }
            IrBinaryOp.SUB -> when {
                left is Long && right is Long -> left - right
                left is Number && right is Number -> toNum(left) - toNum(right)
                else -> error("Cannot subtract $left and $right")
            }
            IrBinaryOp.MUL -> when {
                left is Long && right is Long -> left * right
                left is Number && right is Number -> toNum(left) * toNum(right)
                else -> error("Cannot multiply $left and $right")
            }
            IrBinaryOp.DIV -> when {
                left is Long && right is Long -> left / right
                left is Number && right is Number -> toNum(left) / toNum(right)
                else -> error("Cannot divide $left and $right")
            }
            IrBinaryOp.MOD -> when {
                left is Long && right is Long -> left % right
                left is Number && right is Number -> toNum(left) % toNum(right)
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
            IrBinaryOp.BIT_AND -> (left as Long) and (right as Long)
            IrBinaryOp.BIT_OR -> (left as Long) or (right as Long)
            IrBinaryOp.BIT_XOR -> (left as Long) xor (right as Long)
            IrBinaryOp.SHL -> (left as Long) shl (right as Long).toInt()
            IrBinaryOp.SHR -> (left as Long) shr (right as Long).toInt()
        }
    }

    private fun compare(left: Any?, right: Any?): Int {
        return when {
            left is Long && right is Long -> left.compareTo(right)
            left is Double && right is Double -> left.compareTo(right)
            left is Char && right is Char -> left.compareTo(right)
            left is Number && right is Number -> toNum(left).compareTo(toNum(right))
            else -> error("Cannot compare $left and $right")
        }
    }

    private fun toNum(x: Any): Double = when (x) {
        is Long -> x.toDouble()
        is Double -> x
        else -> x.toString().toDouble()
    }

    /** Returns both operands as Long (if both are integer) or both as Double. */
    private fun pairNum(l: Any, r: Any): Pair<Double, Double> = toNum(l) to toNum(r)

    private fun evalCall(expr: IrExpr.Call): Any? {
        val args = expr.args.map { evalExpr(it) }

        if (expr.name == "__isCheck") {
            val value = args[0]
            val typeName = args[1] as String
            val result = when (typeName) {
                "Int", "UInt", "Byte", "UByte", "Short", "UShort", "Long", "ULong", "Cent", "UCent" -> value is Long
                "Real", "Float", "Decimal" -> value is Double
                "String" -> value is String
                "Bool" -> value is Boolean
                "Char" -> value is Char
                else -> {
                    // Check if it's a struct (Map) or slot (Map with __tag) or enum
                    value is Map<*, *>
                }
            }
            return result
        }
        if (expr.name == "__nullCoalesce") {
            return if (args[0] != null) args[0] else args[1]
        }
        if (expr.name == "__alloc") {
            // Heap-allocate: wrap the value in a mutable cell (a pointer).
            return Pointer(args[0])
        }
        if (expr.name == "__deref") {
            return (args[0] as Pointer).value
        }
        if (expr.name == "__derefAssign") {
            (args[0] as Pointer).value = args[1]
            return null
        }
        if (expr.name == "__isolated") {
            return deepCopy(args[0])
        }
        if (expr.name == "__safeMember") {
            val target = args[0]
            val fieldName = args[1] as String
            if (target == null) return null
            return when (target) {
                is MutableList<*> -> when (fieldName) {
                    "length" -> target.size.toLong()
                    "isEmpty" -> target.isEmpty()
                    "isNotEmpty" -> target.isNotEmpty()
                    else -> error("no member '$fieldName' on array")
                }
                is String -> when (fieldName) {
                    "length" -> target.length.toLong()
                    "isEmpty" -> target.isEmpty()
                    "isNotEmpty" -> target.isNotEmpty()
                    else -> error("no member '$fieldName' on string")
                }
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    (target as Map<String, Any?>)[fieldName]
                }
                else -> error("no member '$fieldName' on $target")
            }
        }
        if (expr.name == "println") {
            val value = args.firstOrNull()
            output.appendLine(formatValue(value))
            return null
        }

        val func = functions[expr.name]
        if (func != null) {
            // A `flow` generator: run its body, collecting `yield`ed values into a list.
            if (func.isFlow) {
                yieldAccumulators.addLast(mutableListOf())
                executeFunction(func, args)
                return yieldAccumulators.removeLast()
            }
            return executeFunction(func, args)
        }

        // Calling a lambda stored in a variable.
        val callee = lookupVar(expr.name)
        if (callee is Closure) {
            val saved = scopes
            scopes = ArrayDeque()
            callee.capturedScopes.forEach { scopes.addLast(it) }
            pushScope()
            for (i in callee.params.indices) defineVar(callee.params[i].first, args[i])
            val result = executeBody(callee.body)
            scopes = saved
            return (result as? ReturnSignal)?.value
        }
        error("Undefined function: ${expr.name}")
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

    /** Returns an independent deep copy of [value] (for `isolated(…)`). */
    private fun deepCopy(value: Any?): Any? = when (value) {
        null -> null
        // Immutable scalars are safe to share.
        is Long, is Double, is Boolean, is Char, is String -> value
        is Closure -> value
        is MutableList<*> -> {
            @Suppress("UNCHECKED_CAST")
            val list = value as MutableList<Any?>
            list.mapTo(mutableListOf()) { deepCopy(it) }
        }
        is MutableMap<*, *> -> {
            // Structs and maps: copy entries with deep-copied values.
            @Suppress("UNCHECKED_CAST")
            val map = value as MutableMap<String, Any?>
            val copy = linkedMapOf<String, Any?>()
            for ((k, v) in map) copy[k] = deepCopy(v)
            copy
        }
        is Pointer -> Pointer(deepCopy(value.value))
        else -> value
    }


    /** Control-flow signal raised by `return`/`break`/`continue`. */
    private sealed class ControlSignal
    private data class ReturnSignal(val value: Any?) : ControlSignal()
    /** `break`, optionally targeting a labeled loop ([label]). */
    private data class BreakSignal(val label: String?) : ControlSignal()
    /** `continue`, optionally targeting a labeled loop ([label]). */
    private data class ContinueSignal(val label: String?) : ControlSignal()

    /** A value thrown by `throw`, caught by `try`/`catch`. */
    private class AzoraThrownException(val value: Any?) : RuntimeException(value?.toString())

    /** A lambda value capturing its definition environment. */
    private class Closure(
        val params: List<Pair<String, org.azora.lang.ir.IrType>>,
        val body: List<org.azora.lang.ir.IrStmt>,
        val capturedScopes: List<MutableMap<String, Any?>>
    )

    /** A heap pointer — a mutable cell holding the pointee value. */
    private class Pointer(var value: Any?)
}
