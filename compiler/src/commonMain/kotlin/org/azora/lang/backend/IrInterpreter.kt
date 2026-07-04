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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.async

/**
 * Backend — interprets [IrProgram] directly instead of generating code.
 *
 * Uses a scope stack so that `zone { }` blocks introduce new scopes and
 * `::` / `::::` can resolve variables at the correct depth.
 */
class IrInterpreter {

    private val output = StringBuilder()
    private val functions = mutableMapOf<String, IrFunction>()

    /**
     * Implementations for extern (`bridge`) functions that the interpreter can run directly.
     * In a real backend these link to native code; here common C-math maps to `kotlin.math`.
     */
    private val externImpls: Map<String, (List<Any?>) -> Any?> = mapOf(
        "sin" to { a -> kotlin.math.sin(a[0] as Double) },
        "cos" to { a -> kotlin.math.cos(a[0] as Double) },
        "tan" to { a -> kotlin.math.tan(a[0] as Double) },
        "asin" to { a -> kotlin.math.asin(a[0] as Double) },
        "acos" to { a -> kotlin.math.acos(a[0] as Double) },
        "atan" to { a -> kotlin.math.atan(a[0] as Double) },
        "sqrt" to { a -> kotlin.math.sqrt(a[0] as Double) },
        "exp" to { a -> kotlin.math.exp(a[0] as Double) },
        "ln" to { a -> kotlin.math.ln(a[0] as Double) },
        "log" to { a -> kotlin.math.log10(a[0] as Double) },
        "floor" to { a -> kotlin.math.floor(a[0] as Double) },
        "ceil" to { a -> kotlin.math.ceil(a[0] as Double) },
        "round" to { a -> kotlin.math.round(a[0] as Double) },
        "abs" to { a -> if (a[0] is Long) kotlin.math.abs(a[0] as Long) else kotlin.math.abs(a[0] as Double) },
        "pow" to { a -> (a[0] as Double).pow(a[1] as Double) }
    )

    /** The runBlocking coroutine scope — used by `task`/`await` for cooperative concurrency. */
    private var coroutineScope: CoroutineScope? = null

    /** Singleton instances for DI (`solo` / `inject`), keyed by type name. Synchronized for parallelism. */
    private val singletons = mutableMapOf<String, Any?>()

    /** Fire-and-forget tasks created via `launch { … }`; joined before interpret() returns. */
    private val launchedTasks = mutableListOf<kotlinx.coroutines.Deferred<Any?>>()

    /**
     * Per-coroutine execution state. Each coroutine (main + each `task`/`launch`/`flow`)
     * gets its own [ExecState] in its coroutine context, so concurrent tasks on different
     * threads don't share mutable scope/defer/flow state. Accessed via [state].
     */
    private class ExecState(
        var scopes: ArrayDeque<MutableMap<String, Any?>> = ArrayDeque(),
        var deferStack: MutableList<DeferredBlock> = mutableListOf(),
        val yieldAccumulators: ArrayDeque<MutableList<Any?>> = ArrayDeque(),
        val flowProduceChannels: ArrayDeque<SendChannel<Any?>> = ArrayDeque(),
        val regionAllocations: ArrayDeque<MutableList<Pointer>> = ArrayDeque()
    ) : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<ExecState>
        override val key: CoroutineContext.Key<*> get() = Key
    }

    /** The current coroutine's execution state. */
    private suspend fun state(): ExecState = coroutineContext[ExecState]!!

    /** A deferred block, optionally restricted to run only on error (`fail defer`). */
    private class DeferredBlock(val body: List<IrStmt>, val onFail: Boolean, val suppress: Boolean = false)

    fun interpret(program: IrProgram): String {
        output.clear()
        functions.clear()
        launchedTasks.clear()

        val mainState = ExecState()
        // Global scope
        mainState.scopes.addLast(mutableMapOf("__null" to null))

        // Collect tests
        val tests = mutableListOf<IrTopLevel.Test>()

        // The evaluator is `suspend` (to support `await`); run it on a multi-threaded
        // dispatcher (Dispatchers.Default) so `task`/`launch` achieve real parallelism.
        // Each task gets its own ExecState (isolated scopes/defers), so concurrent tasks
        // never share mutable execution state. The public interpret() stays non-suspend.
        return kotlinx.coroutines.runBlocking(Dispatchers.Default + mainState) {
            coroutineScope = this

            // Process top-level items in source order
            for (item in program.items) {
                when (item) {
                    is IrTopLevel.Global -> executeStmt(item.stmt)
                    is IrTopLevel.Func -> functions[item.function.name] = item.function
                    is IrTopLevel.Test -> tests.add(item)
                    is IrTopLevel.Struct -> { /* struct definitions need no execution */ }
                    is IrTopLevel.Extern -> { /* extern declarations need no execution */ }
                }
            }

            // Execute main
            val main = functions["main"] ?: error("No 'main' function found")
            executeFunction(main, emptyList())

            // Execute tests after main
            for (test in tests) {
                executeTest(test)
            }

            // Join any fire-and-forget `launch { … }` tasks so their side effects complete.
            val toJoin = synchronized(launchedTasks) { launchedTasks.toList() }
            for (task in toJoin) task.await()

            synchronized(output) { output.toString().trimEnd() }
        }
    }

    private suspend fun executeTest(test: IrTopLevel.Test) {
        pushScope()
        try {
            executeBody(test.body)
        } finally {
            popScope()
        }
    }

    // -- Scope management ---------------------------------------------------

    private suspend fun pushScope() { state().scopes.addLast(mutableMapOf()) }
    private suspend fun popScope() { state().scopes.removeLast() }

    private suspend fun defineVar(name: String, value: Any?) {
        state().scopes.last()[name] = value
    }

    private suspend fun assignVar(name: String, value: Any?) {
        val s = state().scopes
        // Search from innermost to outermost for existing binding
        for (i in s.indices.reversed()) {
            if (name in s[i]) {
                s[i][name] = value
                return
            }
        }
        s.last()[name] = value
    }

    /** Look up variable from innermost scope outward. */
    private suspend fun lookupVar(name: String): Any? {
        val s = state().scopes
        for (i in s.indices.reversed()) {
            if (name in s[i]) return s[i][name]
        }
        return null
    }

    // -- Execution ----------------------------------------------------------

    private suspend fun executeFunction(func: IrFunction, args: List<Any?>): Any? {
        pushScope()

        // Bind parameters
        for (i in func.params.indices) {
            defineVar(func.params[i].first, args[i])
        }

        val st = state()
        val savedDefers = st.deferStack
        st.deferStack = mutableListOf()
        var retValue: Any? = null
        var failed = false
        var toRethrow: AzoraThrownException? = null
        var suppressed = false
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
            for (i in st.deferStack.indices.reversed()) {
                val d = st.deferStack[i]
                if (d.onFail && !failed) continue
                executeBody(d.body)
                if (d.suppress) suppressed = true
            }
            st.deferStack = savedDefers
        }
        popScope()
        if (toRethrow != null && !suppressed) throw toRethrow
        return retValue
    }

    private suspend fun executeBody(body: List<IrStmt>): Any? {
        for (stmt in body) {
            val result = executeStmt(stmt)
            if (result is ControlSignal) return result
        }
        return null
    }

    private suspend fun executeStmt(stmt: IrStmt): Any? {
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
                if (stmt.alloc) state().regionAllocations.addLast(mutableListOf())
                var signal: ControlSignal? = null
                try {
                    val result = executeBody(stmt.body)
                    if (result is ControlSignal) signal = result
                } finally {
                    if (stmt.alloc) {
                        // Free all allocations made in this arena (null their pointee cells).
                        for (ptr in state().regionAllocations.removeLast()) ptr.setValue(null)
                    }
                }
                popScope()
                if (signal != null) return signal
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
            is IrStmt.ForEach -> {
                val iterable = evalExpr(stmt.iterable)
                // Both MutableList and kotlinx ReceiveChannel expose `iterator()`, but neither
                // shares a common Iterable supertype, so iterate each directly.
                when (iterable) {
                    is MutableList<*> -> {
                        for (item in iterable) {
                            pushScope()
                            defineVar(stmt.elem, item)
                            val result = executeBody(stmt.body)
                            popScope()
                            if (result is BreakSignal) break
                            if (result is ReturnSignal) return result
                        }
                    }
                    is kotlinx.coroutines.channels.ReceiveChannel<*> -> {
                        try {
                            for (item in iterable) {
                                pushScope()
                                defineVar(stmt.elem, item)
                                val result = executeBody(stmt.body)
                                popScope()
                                if (result is BreakSignal) break
                                if (result is ReturnSignal) return result
                            }
                        } finally {
                            // Cancel the producer so an early `break` (or an infinite flow)
                            // doesn't leave it suspended and block runBlocking.
                            @Suppress("UNCHECKED_CAST")
                            (iterable as kotlinx.coroutines.channels.ReceiveChannel<Any?>).cancel()
                        }
                    }
                    else -> error("cannot iterate over $iterable")
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
            is IrStmt.Defer -> { state().deferStack.add(DeferredBlock(stmt.body, stmt.onFail, stmt.suppress)) }
            is IrStmt.Yield -> {
                val st = state()
                val channel = st.flowProduceChannels.lastOrNull()
                val value = evalExpr(stmt.value)
                if (channel != null) channel.send(value) // lazy flow: suspend until received
                else st.yieldAccumulators.lastOrNull()?.add(value) // eager fallback
            }
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
                synchronized(output) { output.appendLine("[TRACE] $msg") }
            }
        }
        return null
    }

    private suspend fun evalExpr(expr: IrExpr): Any? {
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
            is IrExpr.Lambda -> {
                val st = state()
                Closure(expr.params, expr.body, st.scopes.toList())
            }
            is IrExpr.Await -> {
                val task = evalExpr(expr.value)
                val closure = task as? Closure ?: error("await requires a task (a `task { … }` value)")
                val scope = coroutineScope ?: error("await used outside of the interpreter's runBlocking scope")
                // Run the task in its own coroutine with an isolated ExecState (real parallelism
                // on Dispatchers.Default); each task has its own scope/defer stack.
                scope.async(context = childState()) { invokeClosure(closure) }.await()
            }
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
                    receiver is AzoraChannel -> when (expr.name) {
                        "send" -> { receiver.channel.send(args[0]); null }
                        "receive" -> receiver.channel.receive()
                        "close" -> { receiver.channel.close(); null }
                        else -> error("no method '${expr.name}' on channel")
                    }
                    else -> error("no method '${expr.name}' on $receiver")
                }
            }
        }
    }

    private suspend fun evalBinary(expr: IrExpr.Binary): Any {
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

    private suspend fun evalCall(expr: IrExpr.Call): Any? {
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
            val ptr = asPointer(args[0])
            // Register with the current `zone alloc { }` arena (if any) for cleanup at exit.
            state().regionAllocations.lastOrNull()?.add(ptr)
            return ptr
        }
        if (expr.name == "__deref") {
            return (args[0] as Pointer).value
        }
        if (expr.name == "__derefAssign") {
            (args[0] as Pointer).setValue(args[1])
            return null
        }
        if (expr.name == "__ptrAdd") {
            val ptr = args[0] as Pointer
            val n = (args[1] as Long).toInt()
            return Pointer(ptr.buffer, ptr.index + n)
        }
        if (expr.name == "__ptrSub") {
            val ptr = args[0] as Pointer
            val n = (args[1] as Long).toInt()
            return Pointer(ptr.buffer, ptr.index - n)
        }
        if (expr.name == "__ptrDiff") {
            val a = args[0] as Pointer
            val b = args[1] as Pointer
            return (a.index - b.index).toLong()
        }
        if (expr.name == "__isolated") {
            return deepCopy(args[0])
        }
        if (expr.name == "__inject") {
            val typeName = args[0] as String
            // Fast path: cached singleton (no suspend inside synchronized).
            val cached = synchronized(singletons) { singletons[typeName] }
            if (cached != null) return cached
            // Slow path: create the singleton via its factory (outside the lock).
            val factoryName = "__singleton_$typeName"
            val factory = functions[factoryName]
                ?: error("No singleton factory for '$typeName' — is it declared as `solo`?")
            val instance = executeFunction(factory, emptyList())
            // putIfAbsent handles the race where another coroutine created it meanwhile.
            return synchronized(singletons) { singletons.putIfAbsent(typeName, instance) ?: instance }
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
            synchronized(output) { output.appendLine(formatValue(value)) }
            return null
        }
        if (expr.name == "channel") {
            // A buffered channel (effectively unbounded) for task-to-task communication.
            return AzoraChannel(Channel<Any?>(Channel.UNLIMITED))
        }
        if (expr.name == "__launch") {
            // `launch { … }` — start a fire-and-forget task; joined before interpret() returns.
            val thunk = args[0] as? Closure ?: error("launch expects a task body")
            val scope = coroutineScope ?: error("launch used outside of the interpreter's runBlocking scope")
            val deferred = scope.async(context = childState()) { invokeClosure(thunk) }
            synchronized(launchedTasks) { launchedTasks.add(deferred) }
            return null
        }

        val func = functions[expr.name]
        if (func != null) {
            // A `flow` generator: return a LAZY producer (rendezvous channel). The body
            // runs in a coroutine, suspending at each `yield` until the consumer receives.
            if (func.isFlow) {
                val scope = coroutineScope ?: error("flow used outside of the interpreter's runBlocking scope")
                // The producer runs in its own coroutine with an isolated ExecState
                // (scopes snapshotted from the caller) so concurrent flows don't share state.
                return scope.produce<Any?>(context = childState()) {
                    state().flowProduceChannels.addLast(this)
                    try {
                        executeFunction(func, args)
                    } finally {
                        state().flowProduceChannels.removeLast()
                    }
                }
            }
            return executeFunction(func, args)
        }

        // Calling a lambda stored in a variable.
        val callee = lookupVar(expr.name)
        if (callee is Closure) {
            val st = state()
            val saved = st.scopes
            st.scopes = ArrayDeque()
            callee.capturedScopes.forEach { st.scopes.addLast(it) }
            pushScope()
            for (i in callee.params.indices) defineVar(callee.params[i].first, args[i])
            val result = executeBody(callee.body)
            popScope()
            st.scopes = saved
            return (result as? ReturnSignal)?.value
        }
        // Extern (`bridge`) function: resolve to a known implementation (e.g. C-math).
        val extern = externImpls[expr.name]
        if (extern != null) return extern(args)
        error("Undefined function: ${expr.name}")
    }

    /** A child [ExecState] whose scopes are a snapshot of the current coroutine's. */
    private suspend fun childState(): ExecState = ExecState(scopes = ArrayDeque(state().scopes.toList()))

    /** Runs a no-argument closure (a `task { … }` thunk) and returns its result. */
    private suspend fun invokeClosure(closure: Closure): Any? {
        val st = state()
        val saved = st.scopes
        st.scopes = ArrayDeque()
        closure.capturedScopes.forEach { st.scopes.addLast(it) }
        pushScope()
        val result = executeBody(closure.body)
        popScope()
        st.scopes = saved
        return (result as? ReturnSignal)?.value ?: result
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
        is Pointer -> {
            val copiedBuffer = value.buffer.map { deepCopy(it) }.toMutableList()
            Pointer(copiedBuffer, value.index)
        }
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
    private class Pointer(val buffer: MutableList<Any?>, val index: Int) {
        val value: Any? get() = buffer[index]
        fun setValue(v: Any?) { buffer[index] = v }

        override fun equals(other: Any?): Boolean =
            other is Pointer && other.buffer === buffer && other.index == index
        override fun hashCode(): Int = System.identityHashCode(buffer) * 31 + index
    }

    /** Wraps a value in a single-element Pointer buffer (or reuses a list directly). */
    private fun asPointer(value: Any?): Pointer =
        if (value is MutableList<*>) {
            @Suppress("UNCHECKED_CAST")
            Pointer(value as MutableList<Any?>, 0)
        } else {
            Pointer(mutableListOf(value), 0)
        }

    /** A communication channel between tasks, wrapping a kotlinx.coroutines channel. */
    private class AzoraChannel(val channel: Channel<Any?>)
}
