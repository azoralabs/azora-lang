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
import org.azora.lang.ir.mangleMethodSymbol
import org.azora.lang.ir.IrUnaryOp
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.async

/**
 * Backend — interprets [IrProgram] directly instead of generating code.
 *
 * Uses a scope stack so that `realm { }` blocks introduce new scopes and
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

/** The single storage slot every member of a `union` instance shares. */
private const val UNION_SLOT = "__union"

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
        "pow" to { a -> (a[0] as Double).pow(a[1] as Double) },
        // The libm spellings, so a `bridge .C` that names the C function rather
        // than its Azora alias still runs under the interpreter — otherwise a
        // test covering such code can only be run by a native build.
        "fabs" to { a -> kotlin.math.abs(a[0] as Double) },
        "fmod" to { a -> (a[0] as Double).mod(a[1] as Double) },
        "fmin" to { a -> kotlin.math.min(a[0] as Double, a[1] as Double) },
        "fmax" to { a -> kotlin.math.max(a[0] as Double, a[1] as Double) },
        "atan2" to { a -> kotlin.math.atan2(a[0] as Double, a[1] as Double) },
        "hypot" to { a -> kotlin.math.hypot(a[0] as Double, a[1] as Double) },
        "log2" to { a -> kotlin.math.log2(a[0] as Double) },
        "log10" to { a -> kotlin.math.log10(a[0] as Double) },
        "sinh" to { a -> kotlin.math.sinh(a[0] as Double) },
        "cosh" to { a -> kotlin.math.cosh(a[0] as Double) },
        "tanh" to { a -> kotlin.math.tanh(a[0] as Double) },
        "trunc" to { a -> kotlin.math.truncate(a[0] as Double) },
        "cbrt" to { a -> kotlin.math.cbrt(a[0] as Double) },
        "exp2" to { a -> 2.0.pow(a[0] as Double) },
    )

    /**
     * The intrinsic behind a `bridge func`, found by name.
     *
     * `realm std` mangles its members (`sqrt` → `__std_math_sqrt`), and the
     * table is keyed by the mathematical name, so a mangled call falls back to
     * its final segment. Nothing outside `externImpls` matches, so an ordinary
     * user function with an underscore in its name is unaffected.
     */
    private fun externImplFor(name: String): ((List<Any?>) -> Any?)? =
        externImpls[name] ?: externImpls[name.substringAfterLast('_').let { if (it == "powr") "pow" else it }]

    /** The runBlocking coroutine scope — used by `task`/`await` for cooperative concurrency. */
    private var coroutineScope: CoroutineScope? = null

    /** Singleton instances for DI (`solo` / `inject`), keyed by type name. Synchronized for parallelism. */
    private val singletons = mutableMapOf<String, Any?>()

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
            runTestBlocks(program)
        }
    }

    /** Suspend test entry point for Wasm/JS, where blocking a thread is unavailable. */
    suspend fun runTestsSuspend(program: IrProgram): List<TestResult> {
        val mainState = resetFor()
        registerStructs(program)
        return kotlinx.coroutines.withContext(Dispatchers.Default + mainState) {
            runTestBlocks(program)
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.runTestBlocks(program: IrProgram): List<TestResult> {
        coroutineScope = this
        val tests = mutableListOf<IrTopLevel.Test>()
        for (item in program.items) {
            when (item) {
                is IrTopLevel.Global -> executeStmt(item.stmt)
                is IrTopLevel.Func -> functions[item.function.name] = item.function
                is IrTopLevel.Test -> tests.add(item)
                is IrTopLevel.Struct, is IrTopLevel.Enum, is IrTopLevel.Extern -> {}
            }
        }
        return tests.map { test ->
            try {
                executeTest(test)
                TestResult(test.name, passed = true, null)
            } catch (e: Exception) {
                TestResult(test.name, passed = false, e.message ?: e.toString())
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
                    // Thread-local variables (`__tl_` prefix) go into per-ExecState storage.
                    val name = when (val stmt = item.stmt) {
                        is IrStmt.VarDecl -> stmt.name
                        is IrStmt.FinDecl -> stmt.name
                        is IrStmt.LetDecl -> stmt.name
                        else -> null
                    }
                    if (name != null && name.startsWith("__tl_")) {
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
                is IrTopLevel.Enum -> { /* enum metadata needs no execution */ }
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
        if (name.startsWith("__tl_") && name in state().threadLocals) {
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
        if (name.startsWith("__tl_") && name in state().threadLocals) return state().threadLocals[name]
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
            is IrStmt.Scope -> {
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
                        // A pack instance assigns through its `oper[]=` (`Type_indexSet`).
                        val structType = target["__type"] as? String
                        val setFn = structType?.let { functions["${it}_indexSet"] }
                        if (setFn != null) {
                            executeFunction(setFn, listOf(target, key, value))
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            (target as MutableMap<Any?, Any?>)[key] = value
                        }
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
                map[unionSlotKey(map) ?: stmt.name] = evalExpr(stmt.value)
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
                val level = formatValue(evalExpr(stmt.level)).uppercase()
                val msg = formatValue(evalExpr(stmt.message))
                azSync(output) { output.appendLine("[$level] $msg") }
                outputListener?.invoke("[$level] $msg")
            }
        }
        return null
    }

    /**
     * The key a union member reads and writes.
     *
     * A union's members share one storage slot, so they share one key. This
     * interpreter holds values rather than bytes, so reading a member other than
     * the one last written currently yields that value instead of a
     * reinterpretation of its bits. That is a gap here, not in the language:
     * once the self-hosted compiler gives the interpreter a real memory model
     * this becomes a genuine reinterpretation, as it already is on LLVM.
     */
    private fun unionSlotKey(receiver: Map<*, *>): String? {
        val typeName = receiver["__type"] as? String ?: return null
        return if (structs[typeName]?.isUnion == true) UNION_SLOT else null
    }

    /**
     * `Hash`'s value for a built-in type.
     *
     * An integer hashes to itself, a float to its bit pattern — so `0.0` and
     * `-0.0` hash apart exactly as they compare apart. A pack never reaches
     * here: it supplies its own `hash`, derived or written.
     */
    private fun primitiveHash(value: Any?): Long = when (value) {
        null -> 0L
        is Long -> value
        is Boolean -> if (value) 1L else 0L
        is String -> value.hashCode().toLong()
        is Double -> value.toRawBits()
        is Float -> value.toRawBits().toLong()
        is Char -> value.code.toLong()
        else -> value.hashCode().toLong()
    }

    private suspend fun evalExpr(expr: IrExpr): Any? {
        return when (expr) {
            is IrExpr.IntLiteral -> expr.value
            is IrExpr.DoubleLiteral -> expr.value
            is IrExpr.StringLiteral -> expr.value
            is IrExpr.EnumLiteral -> expr.variant
            is IrExpr.BoolLiteral -> expr.value
            is IrExpr.CharLiteral -> expr.value
            is IrExpr.EnumToString -> formatValue(evalExpr(expr.value))
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
                        // A pack instance (e.g. an `ArrayList` behind a `List`-typed
                        // value) indexes through its `oper[]` (`Type_index`), not a
                        // raw key lookup on the struct's field map.
                        val structType = target["__type"] as? String
                        val indexFn = structType?.let { functions["${it}_index"] }
                        if (indexFn != null) {
                            executeFunction(indexFn, listOf(target, key))
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            (target as MutableMap<Any?, Any?>)[key]
                        }
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
                // `Hash`'s member on a primitive. A pack supplies its own, so
                // only the built-in value types are answered here — this is the
                // runtime half of `bridge spec Hash`.
                if (expr.name == "hash" && receiver !is Map<*, *>) {
                    return@evalExpr primitiveHash(receiver)
                }
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
                        unionSlotKey(receiver)?.let { slot ->
                            @Suppress("UNCHECKED_CAST")
                            return@evalExpr (receiver as Map<String, Any?>)[slot]
                        }
                        if (typeName != null && receiver.containsKey(expr.name)) {
                            @Suppress("UNCHECKED_CAST")
                            return@evalExpr (receiver as Map<String, Any?>)[expr.name]
                        }
                        // A computed property (`prop size = self._size`) is dispatched
                        // before the generic map fallbacks so a spec-typed value (e.g.
                        // `m: Map` backed by a `LinkedHashMap`) reads the pack's own
                        // `.size`, not the struct field count.
                        if (typeName != null) {
                            val propFunc = functions["${typeName}_prop_${expr.name}"]
                                ?: functions["${typeName}_${expr.name}"]
                            if (propFunc != null && propFunc.params.size == 1) {
                                return@evalExpr executeFunction(propFunc, listOf(receiver))
                            }
                        }
                        when (expr.name) {
                            "length", "size" -> return@evalExpr receiver.size.toLong()
                            "isEmpty" -> return@evalExpr receiver.isEmpty()
                            "isNotEmpty" -> return@evalExpr receiver.isNotEmpty()
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
                    else -> {
                        // A primitive has no runtime type tag, so a property
                        // extension on one (`prop seconds[self: Int&]`) is found
                        // through the receiver's static type instead.
                        val propFunc = functions["${expr.target.type}_${expr.name}"]
                        if (propFunc != null && propFunc.params.size == 1) {
                            executeFunction(propFunc, listOf(receiver))
                        } else {
                            error("no member '${expr.name}' on $receiver")
                        }
                    }
                }
            }
            is IrExpr.StructCtor -> {
                val map = linkedMapOf<String, Any?>()
                map["__type"] = expr.name
                if (structs[expr.name]?.isUnion == true) {
                    map[UNION_SLOT] = expr.args.firstOrNull()?.let { evalExpr(it) }
                } else {
                    for (i in expr.fieldNames.indices) {
                        map[expr.fieldNames[i]] = evalExpr(expr.args[i])
                    }
                }
                // Run the pack's `impl ctor()` (if any) so field-initializing
                // constructors execute. Only a receiver-only ctor (`mut ref self`)
                // is auto-invoked here; the instance is mutated in place.
                val ctor = functions["${expr.name}_ctor"]
                if (ctor != null && ctor.params.size == 1) {
                    executeFunction(ctor, listOf(map))
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
                    IrType.Long, IrType.ULong, IrType.Cent, IrType.UCent, IrType.ISize, IrType.USize -> n.toLong()
                    IrType.Float -> n.toFloat()
                    IrType.Double, IrType.Decimal -> n.toDouble()
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
                if (receiver is Map<*, *>) {
                    // A pack instance carries its concrete type in `__type`; dispatch a
                    // spec-typed call (e.g. `xs.add(4)` where `xs: MutableList`) to the
                    // concrete impl (`ArrayList_add`) rather than a builtin.
                    val structType = receiver["__type"] as? String
                    if (structType != null) {
                        val func = functions["${structType}_${expr.name}"]
                        if (func != null) {
                            return executeFunction(func, listOf(receiver) + args)
                        }
                    }
                }
                when {
                    // `Hash`'s member reached as a call, for a receiver whose
                    // own type does not supply one.
                    expr.name == "hash" && args.isEmpty() -> primitiveHash(receiver)
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
                            // `List`/`MutableList` positional access (spec methods).
                            "get" -> list[(args[0] as Long).toInt()]
                            "set" -> { list[(args[0] as Long).toInt()] = args[1]; null }
                            "removeAt" -> list.removeAt((args[0] as Long).toInt())
                            "removeFirst" -> list.removeAt(0)
                            "removeLast" -> list.removeAt(list.size - 1)
                            "first" -> list.first()
                            "last" -> list.last()
                            "size" -> list.size.toLong()
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
        // Values bound to a `List`/`Set`/`Map`-typed slot keep their native runtime
        // representation (a raw list or map): the interpreter supports positional
        // access, membership, mutation, and key lookup on those directly. Wrapping a
        // raw value in a `{__type, …}` struct would have no matching accessor method
        // and read back as null.
        return value
    }

    /**
     * The operator a value's *runtime* type declares, or null to fall through.
     *
     * A generic body is compiled once with its type parameter erased, so
     * `a + b` inside `func total<T>(…) where T: Arithmetic` reaches the
     * interpreter as a built-in add over two pack values. The bound guaranteed
     * the operator exists; this is where it is found, from the `__type` the
     * value carries.
     *
     * The mangled symbol is the one `SymbolCollector` registered, so a hit here
     * calls exactly the member a concrete call site would have called.
     */
    private suspend fun dispatchOperatorOnRuntimeType(op: IrBinaryOp, left: Any?, right: Any?): Any? {
        val receiver = left as? MutableMap<*, *> ?: return null
        val typeName = receiver["__type"] as? String ?: return null
        val symbol = binaryOperatorSymbol(op) ?: return null
        val operandType = (right as? MutableMap<*, *>)?.get("__type") as? String
        val candidates = listOfNotNull(
            operandType?.let { "${typeName}_${mangleMethodSymbol("$symbol@$it")}" },
            "${typeName}_${mangleMethodSymbol(symbol)}",
        )
        val target = candidates.firstNotNullOfOrNull { functions[it] } ?: return null
        return executeFunction(target, listOf(left, right))
    }

    /** The member name a binary operator is declared under. */
    private fun binaryOperatorSymbol(op: IrBinaryOp): String? = when (op) {
        IrBinaryOp.ADD -> "oper+"
        IrBinaryOp.SUB -> "oper-"
        IrBinaryOp.MUL -> "oper*"
        IrBinaryOp.DIV -> "oper/"
        IrBinaryOp.MOD -> "oper%"
        IrBinaryOp.BIT_AND -> "oper&"
        IrBinaryOp.BIT_OR -> "oper|"
        IrBinaryOp.BIT_XOR -> "oper^"
        IrBinaryOp.SHL -> "oper<<"
        IrBinaryOp.SHR -> "oper>>"
        else -> null
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

        // A generic body sees its type parameter erased, so `a + b` inside
        // `func total<T>(…) where T: Arithmetic` lowered to the built-in add
        // with no chance to find the operator. The values know their own type at
        // runtime, so the operator is looked up here — which is the same reason
        // the interpreter already compares erased values at runtime.
        dispatchOperatorOnRuntimeType(expr.op, left, right)?.let { return it }

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
            // Lexicographic by code point, which is what `impl Order for String`
            // promises — an order, not a collation.
            left is String && right is String -> left.compareTo(right)
            // `false < true`, the order `impl Order for Bool` states.
            left is Boolean && right is Boolean -> left.compareTo(right)
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

        // Value call `receiver(args)`: the receiver evaluates to a function value.
        expr.receiver?.let { recv ->
            val fn = evalExpr(recv)
            if (fn is Closure) {
                val st = state()
                val saved = st.scopes
                st.scopes = ArrayDeque()
                fn.capturedScopes.forEach { st.scopes.addLast(it) }
                pushScope()
                for (i in fn.params.indices) defineVar(fn.params[i].first, args.getOrNull(i))
                val result = executeBody(fn.body)
                popScope()
                st.scopes = saved
                return (result as? ReturnSignal)?.value
            }
            error("value is not callable: $fn")
        }

        if (expr.name == "__isCheck") {
            val value = args[0]
            val typeName = args[1] as String
            val result = when (typeName) {
                "Int", "UInt", "Byte", "UByte", "Short", "UShort", "Long", "ULong", "Cent", "UCent", "ISize", "USize" -> value is Long
                "Double", "Float", "Decimal" -> value is Double
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
                "Int", "UInt", "Byte", "UByte", "Short", "UShort", "Long", "ULong", "Cent", "UCent", "ISize", "USize" -> value is Long
                "Double", "Float", "Decimal" -> value is Double
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
            // Register with the current `realm alloc { }` arena (if any) for cleanup at exit.
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
        if (expr.name == "__purge") {
            // `purge <expr>` — if the value is a Map (struct/node instance), call its dtor if one exists.
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
        if (expr.name == "__inject") {
            val typeName = args[0] as String
            // Fast path: cached singleton (no suspend inside synchronized).
            val cached = azSync(singletons) { singletons[typeName] }
            if (cached != null) return cached
            // Slow path: create the singleton via its factory (outside the lock).
            val factoryName = "__singleton_${typeName.removePrefix("__")}"
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
        if (expr.name == "__std_print" || expr.name == "__std_println") {
            val value = args.firstOrNull()
            val text = formatValue(value)
            azSync(output) {
                if (expr.name == "__std_println") output.appendLine(text) else output.append(text)
            }
            outputListener?.invoke(text)
            return null
        }
        if (expr.name == "__panic") {
            // Unrecoverable runtime abort.
            throw AzoraPanicException(formatValue(args.firstOrNull()))
        }
        if (expr.name == "__std_convert_toString") {
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
        if (expr.name == "__std_Array_fill") {
            val count = (args[0] as Number).toInt()
            return MutableList<Any?>(count) { null }
        }
        if (expr.name == "async") {
            val thunk = args.firstOrNull() as? Closure ?: error("async expects a task body")
            val scope = coroutineScope ?: error("async used outside of the interpreter's structured scope")
            return TaskHandle(scope.async(context = childState()) { invokeClosure(thunk) })
        }
        if (expr.name == "__std_concurrency_cancel") {
            (args.firstOrNull() as? TaskHandle)?.deferred?.cancel()
            return null
        }
        if (expr.name == "channel") {
            // A buffered channel (effectively unbounded) for task-to-task communication.
            return AzoraChannel(Channel<Any?>(Channel.UNLIMITED))
        }
        if (expr.name == "__delay") {
            // Cooperative: the task yields the thread for the duration, so other
            // tasks in the same scope make progress.
            val ms = when (val v = args.firstOrNull()) {
                is Long -> v
                is Int -> v.toLong()
                is Double -> v.toLong()
                else -> 0L
            }
            if (ms > 0) delay(ms)
            return null
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
        val extern = externImplFor(expr.name)
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
        if (internalType == null || !isTupleStruct(internalType)) return value.toString()
        val fields = structs[internalType]?.fields
        val values = if (fields != null) {
            fields.map { field -> value[field.name] }
        } else {
            value.entries.filter { (key, _) -> key != "__type" }.map { (_, fieldValue) -> fieldValue }
        }
        return values.joinToString(", ", "${tupleTypeName(internalType)}(", ")", transform = ::formatStructuredValue)
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
        val qualifiedName = struct.namespace?.let { "$it::Tuple" } ?: "Tuple"
        return struct.fields.joinToString(", ", "$qualifiedName<", ">") { field ->
            sourceTypeName(field.type, nextVisiting)
        }
    }

    private fun isTupleStruct(name: String): Boolean =
        structs[name]?.fields?.let { fields ->
            fields.size >= 2 && fields.withIndex().all { (index, field) -> field.name == index.toString() }
        } == true

    private fun sourceTypeName(type: IrType, visiting: Set<String>): String = when (type) {
        is IrType.Named -> if (isTupleStruct(type.name)) tupleTypeName(type.name, visiting) else type.name
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
