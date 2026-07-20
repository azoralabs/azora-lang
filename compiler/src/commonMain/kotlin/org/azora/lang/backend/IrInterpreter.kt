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

import org.azora.lang.azRunBlocking
import org.azora.lang.azSync
import org.azora.lang.putIfAbsentCompat
import org.azora.lang.ir.IrBinaryOp
import org.azora.lang.ir.IrExpr
import org.azora.lang.ir.IrFunction
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrStmt
import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.IrType
import org.azora.lang.ir.IrUnaryOp
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
/**
 * Debugger attachment point for [IrInterpreter].
 *
 * Debug builds (see `Compiler.compile(debug = true)`) instrument every
 * statement with a `__dbg(line)` call; the interpreter forwards those to
 * [onLine] together with a snapshot of the variables currently in scope.
 * Because the hook is a `suspend` function, a debugger pauses execution by
 * simply not returning until the user resumes.
 */
interface AzoraDebugHost {
    suspend fun onLine(line: Int, locals: Map<String, Any?>)
}

class IrInterpreter {

    /** When set, receives `__dbg` line events from debug-instrumented programs. */
    var debugHost: AzoraDebugHost? = null

    /** When set, receives each println/trace line as it is produced (live output). */
    var outputListener: ((String) -> Unit)? = null

    /**
     * Command-line arguments passed to `func main() { ...args -> … }` (bound to the
     * synthetic variadic `args` param). Set this before [interpret]/[runTests].
     */
    var programArgs: List<String> = emptyList()

    private val output = StringBuilder()
    private val functions = mutableMapOf<String, IrFunction>()
    private val structs = mutableMapOf<String, IrTopLevel.Struct>()

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

    /** Flip/flop state: unique id → current boolean (true = flip, false = flop). */
    private val flipFlopState = mutableMapOf<Int, Boolean>()

    /** Thread-local initializers: name → initializer expression, re-evaluated per coroutine. */
    private val threadLocalInits = mutableListOf<Pair<String, IrExpr>>()

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
        val regionAllocations: ArrayDeque<MutableList<Pointer>> = ArrayDeque(),
        val threadLocals: MutableMap<String, Any?> = mutableMapOf()
    ) : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<ExecState>
        override val key: CoroutineContext.Key<*> get() = Key
    }

    /** The current coroutine's execution state. */
    private suspend fun state(): ExecState = coroutineContext[ExecState]!!

    /** A deferred block, optionally restricted to run only on error (`fail defer`). */
    private class DeferredBlock(val body: List<IrStmt>, val onFail: Boolean, val suppress: Boolean = false)

    /**
     * Runs the program and returns its captured output (synchronous entry point).
     *
     * Implemented via [azRunBlocking] — a real `runBlocking` on JVM/native (which can block the
     * calling thread), and a stub on Wasm/JS (where blocking is impossible). On Wasm/JS, use
     * [interpretSuspend] instead. The evaluator is `suspend` (to support `await`); it runs on
     * `Dispatchers.Default` so `task`/`launch` achieve real parallelism, and each task gets its
     * own [ExecState] (isolated scopes/defers) so concurrent tasks never share mutable state.
     */
    fun interpret(program: IrProgram): String {
        val mainState = resetFor()
        registerStructs(program)
        return azRunBlocking(Dispatchers.Default + mainState) {
            coroutineScope = this
            runProgramBody(program)
            azSync(output) { output.toString().trimEnd() }
        }
    }

    /**
     * Runs the program as a `suspend` function — the entry point on Wasm/JS, where `runBlocking` is
     * unavailable. Establishes the same `Dispatchers.Default + mainState` context via `withContext`,
     * so `task`/`await`/`flow`/`channel` work cooperatively (single-threaded on Wasm/JS).
     */
    suspend fun interpretSuspend(program: IrProgram): String {
        val mainState = resetFor()
        registerStructs(program)
        return kotlinx.coroutines.withContext(Dispatchers.Default + mainState) {
            coroutineScope = this
            runProgramBody(program)
            azSync(output) { output.toString().trimEnd() }
        }
    }

    /**
     * Runs every `test` block in [program] in isolation — no `main` function
     * required, and a failing assertion in one test does not abort the others.
     * Returns one [TestResult] per `test`, in source order. Used by the
     * `azora test` CLI runner.
     */
    fun runTests(program: IrProgram): List<TestResult> {
        val mainState = resetFor()
        registerStructs(program)
        return azRunBlocking(Dispatchers.Default + mainState) {
            coroutineScope = this
            val tests = mutableListOf<IrTopLevel.Test>()
            for (item in program.items) {
                when (item) {
                    is IrTopLevel.Global -> executeStmt(item.stmt)
                    is IrTopLevel.Func -> functions[item.function.name] = item.function
                    is IrTopLevel.Test -> tests.add(item)
                    is IrTopLevel.Struct, is IrTopLevel.Extern -> {}
                }
            }
            tests.map { test ->
                try {
                    executeTest(test)
                    TestResult(test.name, passed = true, null)
                } catch (e: Exception) {
                    TestResult(test.name, passed = false, e.message ?: e.toString())
                }
            }
        }
    }

    /** Clears per-run state and returns a fresh main [ExecState] (with a global scope) to seed the context. */
    private fun resetFor(): ExecState {
        output.clear()
        functions.clear()
        structs.clear()
        launchedTasks.clear()
        val mainState = ExecState()
        // Global scope
        mainState.scopes.addLast(mutableMapOf("__null" to null))
        return mainState
    }

    private fun registerStructs(program: IrProgram) {
        for (item in program.items) {
            if (item is IrTopLevel.Struct) structs[item.name] = item
        }
    }

    /**
     * Processes top-level items in source order, runs `main`, runs `test` blocks, joins fire-and-forget
     * `launch` tasks, and finally runs lifecycle `hook`s. Must run inside the coroutine context seeded
     * by [interpret] / [interpretSuspend] so [state] resolves the main [ExecState].
     */
    private suspend fun runProgramBody(program: IrProgram) {
        // Collect tests
        val tests = mutableListOf<IrTopLevel.Test>()

        // Process top-level items in source order
        for (item in program.items) {
            when (item) {
                is IrTopLevel.Global -> {
                    // Thread-local variables (`__tl__` prefix) go into per-ExecState storage.
                    val name = when (val stmt = item.stmt) {
                        is IrStmt.VarDecl -> stmt.name
                        is IrStmt.FinDecl -> stmt.name
                        is IrStmt.LetDecl -> stmt.name
                        else -> null
                    }
                    if (name != null && name.startsWith("__tl__")) {
                        executeStmt(item.stmt) // evaluates initializer, stores in global scope
                        // Move from global scope to threadLocals so child coroutines get fresh copies.
                        val value = state().scopes.first()[name]
                        state().scopes.first().remove(name)
                        state().threadLocals[name] = value
                        // Store the initializer so child coroutines (task/launch/flow) can re-evaluate it.
                        val init = when (val stmt = item.stmt) {
                            is IrStmt.VarDecl -> stmt.initializer
                            is IrStmt.FinDecl -> stmt.initializer
                            is IrStmt.LetDecl -> stmt.initializer
                            else -> null
                        }
                        if (init != null) threadLocalInits.add(name to init)
                    } else {
                        executeStmt(item.stmt)
                    }
                }
                is IrTopLevel.Func -> functions[item.function.name] = item.function
                is IrTopLevel.Test -> tests.add(item)
                is IrTopLevel.Struct -> { /* struct definitions need no execution */ }
                is IrTopLevel.Extern -> { /* extern declarations need no execution */ }
            }
        }

        // Execute main
        val main = functions["main"] ?: error("No 'main' function found")
        // `func main() { ...args -> … }` binds CLI args to a synthetic variadic param.
        val mainArgs = if (main.params.isNotEmpty()) listOf(programArgs.toMutableList()) else emptyList<Any?>()
        executeFunction(main, mainArgs)

        // Execute tests after main
        for (test in tests) {
            executeTest(test)
        }

        // Join any fire-and-forget `launch { … }` tasks so their side effects complete.
        val toJoin = azSync(launchedTasks) { launchedTasks.toList() }
        for (task in toJoin) task.await()

        // Execute lifecycle hooks (`hook start { }`, `hook stop { }`, etc.) in declaration order.
        for (fn in functions.keys.sorted()) {
            if (fn.startsWith("__hook_")) {
                executeFunction(functions[fn]!!, emptyList())
            }
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
        // Thread-local variables: store in the per-ExecState map.
        if (name.startsWith("__tl__") && name in state().threadLocals) {
            state().threadLocals[name] = value
            return
        }
        val s = state().scopes
        // Search from innermost to outermost for existing binding
        for (i in s.indices.reversed()) {
            if (name in s[i]) {
                val existing = s[i][name]
                // Auto-deref: if the variable holds a RefCell (ref/out param), update the cell.
                if (existing is RefCell) existing.value = value
                else s[i][name] = value
                return
            }
        }
        s.last()[name] = value
    }

    /** Look up variable from innermost scope outward. */
    /** User-visible variables in scope, innermost shadowing outermost (for the debugger). */
    private suspend fun snapshotLocals(): Map<String, Any?> {
        val snapshot = LinkedHashMap<String, Any?>()
        for (scope in state().scopes) {
            for ((name, value) in scope) {
                if (!name.startsWith("__")) snapshot[name] = value
            }
        }
        return snapshot
    }

    private suspend fun lookupVar(name: String): Any? {
        // Thread-local variables: each ExecState has its own independent copy.
        if (name.startsWith("__tl__") && name in state().threadLocals) return state().threadLocals[name]
        val s = state().scopes
        for (i in s.indices.reversed()) {
            if (name in s[i]) {
                // Auto-deref: if the variable holds a RefCell (ref/out param), return the inner value.
                val value = s[i][name]
                return if (value is RefCell) value.value else value
            }
        }
        return null
    }

    // -- Execution ----------------------------------------------------------

    private suspend fun executeFunction(func: IrFunction, args: List<Any?>): Any? {
        pushScope()

        // Bind parameters (ref/out params arrive pre-wrapped in RefCells from evalCall).
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
            is IrStmt.VarDecl -> defineVar(stmt.name, materializeDeclared(stmt.type, evalExpr(stmt.initializer)))
            is IrStmt.FinDecl -> defineVar(stmt.name, materializeDeclared(stmt.type, evalExpr(stmt.initializer)))
            is IrStmt.LetDecl -> defineVar(stmt.name, materializeDeclared(stmt.type, evalExpr(stmt.initializer)))
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
                // Reverse starts at the last value the forward loop would visit:
                // the largest reachable value (≤ end inclusive, < end exclusive).
                fun floorMod(a: Long, b: Long): Long = ((a % b) + b) % b
                var i = if (stmt.reverse) {
                    if (stmt.inclusive) end - floorMod(end - start, step)
                    else end - 1 - floorMod(end - 1 - start, step)
                } else start
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
                    is Pointer -> {
                        target.buffer[target.index + (key as Long).toInt()] = value
                    }
                    else -> error("Cannot index-assign to $target")
                }
            }
            is IrStmt.MemberAssign -> {
                // Auto-deref: assigning through a pointer writes through it (`p.v = x` == `(*p).v = x`).
                var target = evalExpr(stmt.target)
                if (target is Pointer) target = target.value
                @Suppress("UNCHECKED_CAST")
                val map = target as MutableMap<String, Any?>
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
                        } else if (p is IrExpr.Call && p.name == "__isCheck") {
                            // `is Type` pattern (e.g. matching a Var<…>): a boolean type test on the scrutinee.
                            if (evalExpr(p) == true) { hit = true; break }
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
                azSync(output) { output.appendLine("[TRACE] $msg") }
                outputListener?.invoke("[TRACE] $msg")
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
            is IrExpr.SetLit -> expr.elements.map { evalExpr(it) }.distinct().toMutableList()
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
                    is Pointer -> target.buffer[target.index + (key as Long).toInt()]
                    is String -> target[(key as Long).toInt()]
                    else -> error("Cannot index into $target")
                }
            }
            is IrExpr.Member -> {
                var receiver = evalExpr(expr.target)
                // Auto-deref: member access on a pointer reads through it (`p.v` == `(*p).v`).
                if (receiver is Pointer) receiver = receiver.value
                when (receiver) {
                    is MutableList<*> -> when (expr.name) {
                        "length", "size" -> receiver.size.toLong()
                        "data" -> {
                            @Suppress("UNCHECKED_CAST")
                            Pointer(receiver as MutableList<Any?>, 0)
                        }
                        "isEmpty" -> receiver.isEmpty()
                        "isNotEmpty" -> receiver.isNotEmpty()
                        else -> error("no member '${expr.name}' on array")
                    }
                    is String -> when (expr.name) {
                        "length", "size" -> receiver.length.toLong()
                        "isEmpty" -> receiver.isEmpty()
                        "isNotEmpty" -> receiver.isNotEmpty()
                        else -> error("no member '${expr.name}' on string")
                    }
                    is Map<*, *> -> {
                        val typeName = receiver["__type"] as? String
                        if (typeName != null && receiver.containsKey(expr.name)) {
                            @Suppress("UNCHECKED_CAST")
                            return@evalExpr (receiver as Map<String, Any?>)[expr.name]
                        }
                        when (expr.name) {
                            "length", "size" -> return@evalExpr receiver.size.toLong()
                            "isEmpty" -> return@evalExpr receiver.isEmpty()
                            "isNotEmpty" -> return@evalExpr receiver.isNotEmpty()
                        }
                        // Check for a computed property (prop): `Type_prop_name` method.
                        if (typeName != null) {
                            val propFunc = functions["${typeName}_prop_${expr.name}"]
                            if (propFunc != null) return@evalExpr executeFunction(propFunc, listOf(receiver))
                        }
                        @Suppress("UNCHECKED_CAST")
                        val result = (receiver as Map<String, Any?>)[expr.name]
                        // Fallback: if no field, check for prop method on the struct type.
                        if (result == null && receiver.containsKey("__type") == false) {
                            // For non-node structs, check Type_prop_name
                            val propKey = receiver.keys.firstOrNull()
                            // Can't easily get the type name for plain structs; skip for now.
                        }
                        result
                    }
                    else -> error("no member '${expr.name}' on $receiver")
                }
            }
            is IrExpr.StructCtor -> {
                val map = linkedMapOf<String, Any?>()
                map["__type"] = expr.name
                for (i in expr.fieldNames.indices) {
                    map[expr.fieldNames[i]] = evalExpr(expr.args[i])
                }
                map
            }
            is IrExpr.TupleLit -> expr.elements.map { evalExpr(it) }
            // A Var<...> holds exactly one value; `var(a, b, …)` holds the first. Its static type is
            // Variant over all candidate element types; at runtime it is just the held value, so
            // `when v { is T -> … }` becomes a runtime type test.
            is IrExpr.VariantLit -> if (expr.elements.isEmpty()) null else evalExpr(expr.elements.first())
            is IrExpr.TupleAccess -> {
                @Suppress("UNCHECKED_CAST")
                val list = evalExpr(expr.target) as List<Any?>
                list[expr.index]
            }
            is IrExpr.IfExpr -> {
                if (evalExpr(expr.condition) as Boolean) evalExpr(expr.thenExpr)
                else evalExpr(expr.elseExpr)
            }
            is IrExpr.CatchExpr -> {
                try { evalExpr(expr.expr) } catch (e: AzoraThrownException) { evalExpr(expr.fallback) }
            }
            is IrExpr.NumCast -> {
                val v = evalExpr(expr.value)
                val n: Number? = when (v) {
                    is Number -> v
                    is Char -> v.code
                    is Boolean -> if (v) 1 else 0
                    else -> null // pointer-ish FFI cast — no interpreter meaning, pass through
                }
                if (n == null) v else when (expr.type) {
                    IrType.Int, IrType.UInt -> n.toInt().toLong()
                    IrType.Byte, IrType.UByte -> n.toByte().toLong()
                    IrType.Short, IrType.UShort -> n.toShort().toLong()
                    IrType.Long, IrType.ULong, IrType.Cent, IrType.UCent -> n.toLong()
                    IrType.Float -> n.toFloat()
                    IrType.Real, IrType.Decimal -> n.toDouble()
                    IrType.Char -> n.toInt().toChar()
                    else -> v
                }
            }
            is IrExpr.Lambda -> {
                val st = state()
                Closure(expr.params, expr.body, st.scopes.toList())
            }
            is IrExpr.Await -> {
                val task = evalExpr(expr.value)
                when (task) {
                    is TaskHandle -> task.deferred.await()
                    is Closure -> {
                        val scope = coroutineScope ?: error("await used outside of the interpreter's runBlocking scope")
                        // Legacy `await task { ... }`: run the thunk as a structured child.
                        scope.async(context = childState()) { invokeClosure(task) }.await()
                    }
                    else -> error("await requires Task<T>, got $task")
                }
            }
            is IrExpr.SlotPattern -> error("SlotPattern should be handled by when matching, not evaluated")
            is IrExpr.Spread -> error("Spread should be handled by evalCall, not evaluated directly")
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
                // Dynamic dispatch for node instances: walk the __chain to find the method.
                if (receiver is Map<*, *>) {
                    val chain = receiver["__chain"] as? MutableList<*>
                    if (chain != null) {
                        for (t in chain) {
                            val mangled = "${t}_${expr.name}"
                            val func = functions[mangled]
                            if (func != null) {
                                return executeFunction(func, listOf(receiver) + args)
                            }
                        }
                    }
                }
                when {
                    // `#expr` (oper#) — hash of a primitive/value-type operand.
                    expr.name == "oper#" -> when (receiver) {
                        is Long -> receiver
                        is Boolean -> if (receiver) 1L else 0L
                        is String -> receiver.hashCode().toLong()
                        is Double -> receiver.toRawBits().toLong()
                        is Float -> receiver.toRawBits().toLong()
                        else -> receiver.hashCode().toLong()
                    }
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
                    receiver is MutableMap<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val map = receiver as MutableMap<Any?, Any?>
                        when (expr.name) {
                            "get" -> map[args[0]]
                            "put" -> { map[args[0]] = args[1]; null }
                            "containsKey" -> map.containsKey(args[0])
                            "clear" -> { map.clear(); null }
                            "isEmpty" -> map.isEmpty()
                            "isNotEmpty" -> map.isNotEmpty()
                            else -> error("no method '${expr.name}' on map")
                        }
                    }
                    receiver is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val list = receiver as MutableList<Any?>
                        if (expr.target.type is IrType.Set) {
                            return@evalExpr when (expr.name) {
                                "add" -> if (list.contains(args[0])) false else { list.add(args[0]); true }
                                "remove" -> list.remove(args[0])
                                "contains" -> list.contains(args[0])
                                "clear" -> { list.clear(); null }
                                "isEmpty" -> list.isEmpty()
                                "isNotEmpty" -> list.isNotEmpty()
                                else -> error("no method '${expr.name}' on set")
                            }
                        }
                        when (expr.name) {
                            "add" -> { list.add(args[0]); null }
                            "insert" -> { list.add((args[0] as Long).toInt(), args[1]); null }
                            "remove" -> { list.removeAt((args[0] as Long).toInt()); null }
                            "contains" -> list.contains(args[0])
                            "indexOf" -> list.indexOf(args[0]).toLong()
                            "isEmpty" -> list.isEmpty()
                            "isNotEmpty" -> list.isNotEmpty()
                            "fill" -> {
                                // `arr().fill(count)` — pre-allocate `count` null slots.
                                val count = (args[0] as Long).toInt()
                                repeat(count) { list.add(null) }
                                list
                            }
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

    private fun materializeDeclared(type: IrType, value: Any?): Any? {
        val name = (type as? IrType.Named)?.name ?: return value
        return when {
            name in setOf("List", "MutableList") && value is MutableList<*> -> {
                val elements = value.toMutableList()
                linkedMapOf<String, Any?>(
                    "__type" to name,
                    "data" to elements,
                    "size" to elements.size.toLong(),
                    "capacity" to maxOf(8, elements.size).toLong(),
                )
            }
            name in setOf("Set", "MutableSet") && value is MutableList<*> -> {
                val elements = value.distinct().toMutableList()
                linkedMapOf<String, Any?>(
                    "__type" to name,
                    "data" to elements,
                    "size" to elements.size.toLong(),
                    "capacity" to maxOf(8, elements.size).toLong(),
                )
            }
            name in setOf("Map", "MutableMap") && value is Map<*, *> && !value.containsKey("__type") -> {
                val entries = value.entries.toList()
                linkedMapOf<String, Any?>(
                    "__type" to name,
                    "keys" to entries.map { it.key }.toMutableList(),
                    "values" to entries.map { it.value }.toMutableList(),
                    "size" to entries.size.toLong(),
                    "capacity" to maxOf(8, entries.size).toLong(),
                )
            }
            else -> value
        }
    }

    private suspend fun evalBinary(expr: IrExpr.Binary): Any {
        // Short-circuit logical operators: the right operand must not be evaluated
        // when the left already determines the result (matches the codegen backends).
        if (expr.op == IrBinaryOp.AND) {
            return (evalExpr(expr.left) as Boolean) && (evalExpr(expr.right) as Boolean)
        }
        if (expr.op == IrBinaryOp.OR) {
            return (evalExpr(expr.left) as Boolean) || (evalExpr(expr.right) as Boolean)
        }

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
        // Evaluate args, splicing any Spread (arr...) into individual elements.
        val args = mutableListOf<Any?>()
        for (argExpr in expr.args) {
            if (argExpr is IrExpr.Spread) {
                val arr = evalExpr(argExpr.array)
                if (arr is MutableList<*>) {
                    @Suppress("UNCHECKED_CAST")
                    args.addAll(arr as MutableList<Any?>)
                } else error("spread requires an array, got $arr")
            } else {
                args.add(evalExpr(argExpr))
            }
        }

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
        if (expr.name == "__dynCast") {
            // `x as? T` — the value if it is a `T` at runtime, else null.
            val value = args[0]
            val typeName = args[1] as String
            val matches = when (typeName) {
                "Int", "UInt", "Byte", "UByte", "Short", "UShort", "Long", "ULong", "Cent", "UCent" -> value is Long
                "Real", "Float", "Decimal" -> value is Double
                "String" -> value is String
                "Bool" -> value is Boolean
                "Char" -> value is Char
                else -> value is Map<*, *> && (value["__type"] == typeName || value["__tag"] == typeName || value["__type"] == null)
            }
            return if (matches) value else null
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
        if (expr.name == "__allocBuffer") {
            // alloc T(count) — a buffer of `count` zero/null-initialized T's → T* (index 0).
            val count = (args[0] as Long).toInt()
            val ptr = Pointer(MutableList(count) { null }, 0)
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
        if (expr.name == "__drop") {
            // `drop <expr>` — if the value is a Map (struct/node instance), call its dtor if one exists.
            val value = args[0]
            if (value is Map<*, *>) {
                val typeName = value["__type"] as? String
                if (typeName != null) {
                    val dtorFunc = functions["${typeName}_dtor"]
                    if (dtorFunc != null) executeFunction(dtorFunc, listOf(value))
                }
            }
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
        if (expr.name == "__flipflop") {
            // Alternating execution: returns true on first call, false on second, etc.
            val id = (args[0] as Long).toInt()
            val current = flipFlopState[id] ?: true
            flipFlopState[id] = !current
            return current
        }
        if (expr.name == "__inject") {
            val typeName = args[0] as String
            // Fast path: cached singleton (no suspend inside synchronized).
            val cached = azSync(singletons) { singletons[typeName] }
            if (cached != null) return cached
            // Slow path: create the singleton via its factory (outside the lock).
            val factoryName = "__singleton_$typeName"
            val factory = functions[factoryName]
                ?: error("No singleton factory for '$typeName' — is it declared as `solo`?")
            val instance = executeFunction(factory, emptyList())
            // putIfAbsent handles the race where another coroutine created it meanwhile.
            return azSync(singletons) { singletons.putIfAbsentCompat(typeName, instance) ?: instance }
        }
        if (expr.name == "__safeMember") {
            val target = args[0]
            val fieldName = args[1] as String
            if (target == null) return null
            return when (target) {
                is MutableList<*> -> when (fieldName) {
                    "length", "size" -> target.size.toLong()
                    "data" -> {
                        @Suppress("UNCHECKED_CAST")
                        Pointer(target as MutableList<Any?>, 0)
                    }
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
        if (expr.name == "__dbg") {
            // Debug-build line marker — pauses here while a debugger is attached.
            debugHost?.onLine(((args.firstOrNull() as? Long) ?: 0L).toInt(), snapshotLocals())
            return null
        }
        if (expr.name == "std__println") {
            val value = args.firstOrNull()
            val text = formatValue(value)
            azSync(output) { output.appendLine(text) }
            outputListener?.invoke(text)
            return null
        }
        if (expr.name == "__panic") {
            // Unrecoverable runtime abort.
            throw AzoraPanicException(formatValue(args.firstOrNull()))
        }
        if (expr.name == "std__convert__toString") {
            return formatValue(args.firstOrNull())
        }
        if (expr.name == "stringLength") return (args[0] as String).length.toLong()
        if (expr.name == "charAt") return (args[0] as String)[(args[1] as Number).toInt()]
        if (expr.name == "substring") {
            return (args[0] as String).substring((args[1] as Number).toInt(), (args[2] as Number).toInt())
        }
        if (expr.name == "ord") return (args[0] as Char).code.toLong()
        if (expr.name == "chr") return (args[0] as Number).toInt().toChar()
        if (expr.name == "isDigit") return (args[0] as Char) in '0'..'9'
        // Low-level string intrinsics used by std.string's free-function wrappers.
        if (expr.name == "startsWith") return (args[0] as String).startsWith(args[1] as String)
        if (expr.name == "endsWith") return (args[0] as String).endsWith(args[1] as String)
        if (expr.name == "contains") return (args[0] as String).contains(args[1] as String)
        if (expr.name == "indexOf") return (args[0] as String).indexOf(args[1] as String).toLong()
        if (expr.name == "trim") return (args[0] as String).trim()
        if (expr.name == "toUpper") return (args[0] as String).uppercase()
        if (expr.name == "toLower") return (args[0] as String).lowercase()
        if (expr.name == "replace") return (args[0] as String).replace(args[1] as String, args[2] as String)
        if (expr.name == "split") return (args[0] as String).split(args[1] as String).toMutableList()
        if (expr.name == "toChars") return (args[0] as String).toMutableList()
        if (expr.name == "fromChars") {
            @Suppress("UNCHECKED_CAST")
            return (args[0] as List<Char>).joinToString("")
        }
        if (expr.name == "isAlpha") return (args[0] as Char).isLetter()
        // `Array::fill<T>(count)` — allocate `count` default (null) slots.
        if (expr.name == "Array__fill") {
            val count = (args[0] as Number).toInt()
            return MutableList<Any?>(count) { null }
        }
        if (expr.name == "async") {
            val thunk = args.firstOrNull() as? Closure ?: error("async expects a task body")
            val scope = coroutineScope ?: error("async used outside of the interpreter's structured scope")
            return TaskHandle(scope.async(context = childState()) { invokeClosure(thunk) })
        }
        if (expr.name == "std__concurrency__cancel") {
            (args.firstOrNull() as? TaskHandle)?.deferred?.cancel()
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
            azSync(launchedTasks) { launchedTasks.add(deferred) }
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
            if (func.isTask) {
                val scope = coroutineScope ?: error("task used outside of the interpreter's structured scope")
                return TaskHandle(scope.async(context = childState()) { executeFunction(func, args) })
            }
            // Wrap ref/out params in RefCells so mutations propagate back to the caller.
            if (func.refParams.isEmpty()) {
                return executeFunction(func, args)
            }
            val refCells = mutableMapOf<Int, RefCell>()
            val wrappedArgs = args.toMutableList()
            for (i in func.refParams) {
                if (i < wrappedArgs.size) {
                    val cell = RefCell(wrappedArgs[i])
                    refCells[i] = cell
                    wrappedArgs[i] = cell
                }
            }
            val result = executeFunction(func, wrappedArgs)
            // Propagate ref/out mutations back to the caller's variables.
            for ((i, cell) in refCells) {
                val argExpr = expr.args.getOrNull(i)
                if (argExpr is IrExpr.Var) {
                    assignVar(argExpr.name, cell.value)
                }
            }
            return result
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
    /** A child [ExecState] whose scopes are a snapshot of the current coroutine's.
     *  Thread-local variables are re-evaluated from their initializers so each coroutine
     *  gets its own independent copy. */
    private suspend fun childState(): ExecState {
        val child = ExecState(scopes = ArrayDeque(state().scopes.toList()))
        // Re-evaluate thread-local initializers for the child coroutine (fresh copies).
        for ((name, init) in threadLocalInits) {
            child.threadLocals[name] = evalExpr(init)
        }
        return child
    }

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
        is Map<*, *> -> formatMapValue(value)
        else -> value.toString()
    }

    private fun formatMapValue(value: Map<*, *>): String {
        val internalType = value["__type"] as? String
        if (internalType?.startsWith("__Tuple_") != true) return value.toString()

        return value.entries.joinToString(", ", "{", "}") { (key, fieldValue) ->
            val displayedValue = if (key == "__type") tupleTypeName(internalType) else fieldValue
            "${quoteValue(key.toString())}=${formatStructuredValue(displayedValue)}"
        }
    }

    private fun formatStructuredValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> quoteValue(value)
        is Char -> quoteValue(value.toString())
        is Map<*, *> -> formatMapValue(value)
        else -> value.toString()
    }

    private fun quoteValue(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun tupleTypeName(internalName: String, visiting: Set<String> = emptySet()): String {
        if (internalName in visiting) return internalName
        val struct = structs[internalName] ?: return internalName
        val nextVisiting = visiting + internalName
        return struct.fields.joinToString(", ", "Tuple<", ">") { field ->
            sourceTypeName(field.type, nextVisiting)
        }
    }

    private fun sourceTypeName(type: IrType, visiting: Set<String>): String = when (type) {
        is IrType.Named -> if (type.name.startsWith("__Tuple_")) tupleTypeName(type.name, visiting) else type.name
        else -> type.toString()
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

    /** Unrecoverable runtime `panic` — propagates out of [interpret]. */
    private class AzoraPanicException(message: String) : RuntimeException("panic: $message")

    /** A lambda value capturing its definition environment. */
    private class Closure(
        val params: List<Pair<String, org.azora.lang.ir.IrType>>,
        val body: List<org.azora.lang.ir.IrStmt>,
        val capturedScopes: List<MutableMap<String, Any?>>
    )

    /** A structured child task. Its Deferred is parented to the interpreter root scope. */
    private class TaskHandle(val deferred: kotlinx.coroutines.Deferred<Any?>)

    /** A heap pointer — a mutable cell holding the pointee value. */
    private class Pointer(val buffer: MutableList<Any?>, val index: Int) {
        val value: Any? get() = buffer[index]
        fun setValue(v: Any?) { buffer[index] = v }

        override fun equals(other: Any?): Boolean =
            other is Pointer && other.buffer === buffer && other.index == index
        override fun hashCode(): Int = buffer.hashCode() * 31 + index
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

    /** A mutable reference cell for `ref`/`out` parameters — auto-unwrapped by lookupVar/assignVar. */
    private class RefCell(var value: Any?)
}

/** Outcome of running a single `test` block via [IrInterpreter.runTests]. */
data class TestResult(val name: String, val passed: Boolean, val message: String?)
