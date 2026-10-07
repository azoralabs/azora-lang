/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.ir

/** Materializes lexical destruction before optimization. Moves empty their source
 * at evaluation time, so conditional moves and explicit purge share the same
 * null-safe cleanup path. The function's own owners are registered as defers:
 * user defers reached later still see their owners alive. */
object OwnershipCleanup {
    /**
     * [copied] answers whether a value of a type duplicates implicitly (`Copy`).
     * Such a value stays its binding's after it is handed to something that
     * keeps it, so whatever keeps it receives an independent copy instead of
     * an alias the binding's own cleanup would later free.
     */
    fun lower(
        program: IrProgram,
        returnsBorrow: (String) -> Boolean = { false },
        copied: (IrType) -> Boolean = { false },
    ): IrProgram {
        val functions = program.items.filterIsInstance<IrTopLevel.Func>().map { it.function }.associateBy { it.name }
        val fields = program.items.filterIsInstance<IrTopLevel.Struct>().associate { struct -> struct.name to struct.fields.associateBy { it.name } }
        val context = Context(functions, retainedParameters(functions), fields, copied, freshResults(functions, fields, returnsBorrow), returnsBorrow)
        return program.copy(items = program.items.map { item ->
        when (item) {
            is IrTopLevel.Func -> item.copy(function = item.function.copy(body = Body(context, returnsBorrow(item.function.name)).lower(
                item.function.body, root = true, initialOwners = item.function.params.mapIndexedNotNull { i, (name, type) ->
                    if (type is IrType.Function && i !in item.function.refParams) IrExpr.Var(name, type) else null
                },
            )))
            is IrTopLevel.Test -> item.copy(body = Body(context).lower(item.body, root = true))
            else -> item
        }
        })
    }

    private class Context(
        val functions: Map<String, IrFunction>,
        val retained: Map<String, Set<Int>>,
        val fields: Map<String, Map<String, IrField>>,
        val copied: (IrType) -> Boolean,
        val fresh: Set<String>,
        val returnsBorrow: (String) -> Boolean,
    )

    /** A type whose values are allocations an owner frees: a pack or an array. */
    private fun allocates(type: IrType, fields: Map<String, Map<String, IrField>>): Boolean =
        type is IrType.Array || (type is IrType.Named && type.name in fields)

    /**
     * Functions whose every result is a new allocation the caller then owns: a
     * constructed value, an owned local, a moved or copied value, or another
     * such call. A result that may be a place someone else owns - a field, a
     * global, an element - is not, and neither is a borrowed (`T&`) result.
     */
    private fun freshResults(
        functions: Map<String, IrFunction>,
        fields: Map<String, Map<String, IrField>>,
        returnsBorrow: (String) -> Boolean,
    ): Set<String> {
        val candidates = functions.values.filter { allocates(it.returnType, fields) && !returnsBorrow(it.name) }
        // Optimistic: recursion is assumed fresh until a return says otherwise.
        val fresh = candidates.mapTo(mutableSetOf()) { it.name }
        var changed = true
        while (changed) {
            changed = false
            for (function in candidates) {
                if (function.name !in fresh) continue
                val owned = mutableSetOf<String>()
                var ok = true
                fun freshValue(value: IrExpr?): Boolean = when (value) {
                    is IrExpr.StructCtor, is IrExpr.ArrayLiteral -> true
                    is IrExpr.Call -> value.name == "__take" || value.name == "__isolated" || value.name in fresh
                    is IrExpr.Var -> value.name in owned
                    is IrExpr.IfExpr -> freshValue(value.thenExpr) && freshValue(value.elseExpr)
                    else -> false
                }
                fun walk(stmts: List<IrStmt>) {
                    for (stmt in stmts) {
                        when (stmt) {
                            is IrStmt.VarDecl -> if (stmt.ownsValue) owned += stmt.name
                            is IrStmt.FinDecl -> if (stmt.ownsValue) owned += stmt.name
                            is IrStmt.LetDecl -> if (stmt.ownsValue) owned += stmt.name
                            is IrStmt.Return -> if (!freshValue(stmt.value)) ok = false
                            is IrStmt.Scope -> walk(stmt.body)
                            is IrStmt.If -> { walk(stmt.thenBranch); stmt.elseBranch?.let(::walk) }
                            is IrStmt.While -> walk(stmt.body)
                            is IrStmt.For -> walk(stmt.body)
                            is IrStmt.ForEach -> walk(stmt.body)
                            is IrStmt.Loop -> walk(stmt.body)
                            is IrStmt.When -> { stmt.branches.forEach { walk(it.body) }; stmt.elseBranch?.let(::walk) }
                            is IrStmt.Try -> { walk(stmt.body); stmt.catchBody?.let(::walk) }
                            else -> {}
                        }
                    }
                }
                walk(function.body)
                if (!ok) {
                    fresh -= function.name
                    changed = true
                }
            }
        }
        return fresh
    }

    /** Builtin container members that keep their arguments. */
    private val storingMembers = setOf("add", "insert", "put", "push", "addFirst", "addLast", "set")

    private fun stores(call: IrExpr.MethodCall): Boolean =
        call.name in storingMembers && (call.target.type is IrType.Array || call.target.type is IrType.Set || call.target.type is IrType.Map)

    /** Whether compiler intrinsic [name] keeps its argument [index]: a move, or a value stored behind a pointer. */
    private fun keptByIntrinsic(name: String, index: Int): Boolean =
        name == "__take" || name == "__alloc" || (name == "__derefAssign" && index == 1)

    /** A type parameter's erased slot: its value's real type is known only at the call site. */
    private fun erased(type: IrType): Boolean = type == IrType.Any || (type is IrType.Nullable && type.inner == IrType.Any)

    /**
     * By-value parameters each function keeps beyond the call: stored in a
     * container, a field or a constructed value, returned, moved out, or passed
     * to a parameter that is itself kept. Parameters are borrowed by the callee,
     * so a kept one must arrive as a value nobody else will free; an erased
     * generic body cannot copy it, so its concrete caller does.
     */
    private fun retainedParameters(functions: Map<String, IrFunction>): Map<String, Set<Int>> {
        val retained = functions.mapValues { mutableSetOf<Int>() }
        var changed = true
        while (changed) {
            changed = false
            for (function in functions.values) {
                val found = retained.getValue(function.name)
                val parameters = function.params.withIndex()
                    .filter { it.index !in function.refParams }
                    .associate { it.value.first to it.index }
                val aliases = mutableMapOf<String, Int>()
                fun parameter(value: IrExpr?): Int? = when (value) {
                    is IrExpr.Var -> aliases[value.name] ?: parameters[value.name]
                    is IrExpr.Call -> if (value.name == "__take") parameter(value.args.singleOrNull()) else null
                    else -> null
                }
                fun keep(value: IrExpr?) {
                    val index = parameter(value) ?: return
                    if (found.add(index)) changed = true
                }
                // A lambda body holds statements, so the expression walk reaches
                // back into the statement walk declared after it.
                var nested: (IrStmt) -> Unit = {}
                fun visit(expr: IrExpr?) {
                    when (expr) {
                        null -> {}
                        is IrExpr.Call -> {
                            val kept = retained[expr.name].orEmpty()
                            expr.args.forEachIndexed { i, arg -> if (i in kept || keptByIntrinsic(expr.name, i)) keep(arg); visit(arg) }
                            visit(expr.receiver)
                        }
                        is IrExpr.MethodCall -> { if (stores(expr)) expr.args.forEach(::keep); visit(expr.target); expr.args.forEach(::visit) }
                        is IrExpr.StructCtor -> expr.args.forEach { keep(it); visit(it) }
                        is IrExpr.ArrayLiteral -> expr.elements.forEach { keep(it); visit(it) }
                        is IrExpr.SetLit -> expr.elements.forEach { keep(it); visit(it) }
                        is IrExpr.TupleLit -> expr.elements.forEach { keep(it); visit(it) }
                        is IrExpr.MapLit -> expr.entries.forEach { (key, value) -> keep(key); keep(value); visit(key); visit(value) }
                        is IrExpr.Binary -> { visit(expr.left); visit(expr.right) }
                        is IrExpr.Unary -> visit(expr.operand)
                        is IrExpr.Member -> visit(expr.target)
                        is IrExpr.Index -> { visit(expr.target); visit(expr.index) }
                        is IrExpr.IfExpr -> { visit(expr.condition); visit(expr.thenExpr); visit(expr.elseExpr) }
                        is IrExpr.CatchExpr -> { visit(expr.expr); visit(expr.fallback) }
                        is IrExpr.NumCast -> visit(expr.value)
                        is IrExpr.Await -> visit(expr.value)
                        is IrExpr.Lambda -> expr.body.forEach { nested(it) }
                        else -> {}
                    }
                }
                // A binding that owns its value and starts as a parameter has
                // taken the parameter over: it frees it when it goes.
                fun declare(name: String, initializer: IrExpr, owns: Boolean) {
                    if (initializer is IrExpr.Var && !owns) parameter(initializer)?.let { aliases[name] = it } else keep(initializer)
                    visit(initializer)
                }
                fun statement(stmt: IrStmt) {
                    when (stmt) {
                        is IrStmt.VarDecl -> declare(stmt.name, stmt.initializer, stmt.ownsValue)
                        is IrStmt.FinDecl -> declare(stmt.name, stmt.initializer, stmt.ownsValue)
                        is IrStmt.LetDecl -> declare(stmt.name, stmt.initializer, stmt.ownsValue)
                        is IrStmt.Assignment -> { keep(stmt.value); visit(stmt.value) }
                        is IrStmt.IndexAssign -> { keep(stmt.value); visit(stmt.target); visit(stmt.index); visit(stmt.value) }
                        is IrStmt.MemberAssign -> { keep(stmt.value); visit(stmt.target); visit(stmt.value) }
                        is IrStmt.Return -> { keep(stmt.value); visit(stmt.value) }
                        is IrStmt.Throw -> { keep(stmt.value); visit(stmt.value) }
                        is IrStmt.ExprStmt -> visit(stmt.expr)
                        is IrStmt.Scope -> stmt.body.forEach(::statement)
                        is IrStmt.If -> { visit(stmt.condition); stmt.thenBranch.forEach(::statement); stmt.elseBranch?.forEach(::statement) }
                        is IrStmt.While -> { visit(stmt.condition); stmt.body.forEach(::statement) }
                        is IrStmt.For -> { visit(stmt.start); visit(stmt.end); visit(stmt.step); stmt.body.forEach(::statement) }
                        is IrStmt.ForEach -> { visit(stmt.iterable); stmt.body.forEach(::statement) }
                        is IrStmt.Loop -> stmt.body.forEach(::statement)
                        is IrStmt.When -> { visit(stmt.scrutinee); stmt.branches.forEach { it.body.forEach(::statement) }; stmt.elseBranch?.forEach(::statement) }
                        is IrStmt.Try -> { stmt.body.forEach(::statement); stmt.catchBody?.forEach(::statement) }
                        is IrStmt.Defer -> stmt.body.forEach(::statement)
                        else -> {}
                    }
                }
                nested = ::statement
                function.body.forEach(::statement)
            }
        }
        return retained
    }

    /** [borrowedReturn]: the function returns `T&`, so a returned place stays its owner's. */
    private class Body(val context: Context, val borrowedReturn: Boolean = false) {
        val functions get() = context.functions
        private data class Frame(val owners: MutableList<IrExpr.Var>, val root: Boolean, val loop: Boolean, val label: String?, val delayed: Boolean, val aliases: MutableMap<String, IrExpr.Var> = mutableMapOf())
        private val frames = mutableListOf<Frame>()
        private var temporary = 0
        private fun purge(value: IrExpr) = IrStmt.ExprStmt(IrExpr.Call("__purge", listOf(value), IrType.Unit))
        private fun cleanup(frames: List<Frame>) = frames.asReversed().filterNot { it.delayed }.flatMap { it.owners.asReversed().map(::purge) }
        private fun owned(name: String) = frames.asReversed().flatMap { it.owners }.firstOrNull { it.name == name }
        private fun origin(value: IrExpr.Var): IrExpr.Var? = owned(value.name)
            ?: frames.asReversed().firstNotNullOfOrNull { it.aliases[value.name] }

        fun lower(stmts: List<IrStmt>, root: Boolean = false, loop: Boolean = false, label: String? = null, initialOwners: List<IrExpr.Var> = emptyList()): List<IrStmt> {
            val frame = Frame(initialOwners.toMutableList(), root, loop, label, stmts.any { it is IrStmt.Defer })
            frames += frame
            val result = mutableListOf<IrStmt>()
            initialOwners.forEach { result += IrStmt.Defer(listOf(purge(it))) }
            for (raw in stmts) {
                val stmt = expressions(raw)
                val alias = when (stmt) {
                    is IrStmt.VarDecl -> stmt.name to stmt.initializer
                    is IrStmt.FinDecl -> stmt.name to stmt.initializer
                    is IrStmt.LetDecl -> stmt.name to stmt.initializer
                    else -> null
                }
                if (alias?.second is IrExpr.Var) origin(alias.second as IrExpr.Var)?.let { frame.aliases[alias.first] = it }
                val declaration = when (stmt) {
                    is IrStmt.VarDecl -> if (stmt.ownsValue && !stmt.lazy && stmt.reactiveLifetime == null) IrExpr.Var(stmt.name, stmt.type) else null
                    is IrStmt.FinDecl -> if (stmt.ownsValue && !stmt.lazy && stmt.reactiveLifetime == null) IrExpr.Var(stmt.name, stmt.type) else null
                    is IrStmt.LetDecl -> if (stmt.ownsValue && !stmt.lazy && stmt.reactiveLifetime == null) IrExpr.Var(stmt.name, stmt.type) else null
                    else -> null
                }
                if (declaration != null) {
                    result += stmt
                    frame.owners += declaration
                    // The registration also covers a failable call that leaves
                    // this lexical body before its explicit cleanup edge.
                    result += IrStmt.Defer(listOf(purge(declaration)))
                    continue
                }
                when (stmt) {
                    is IrStmt.Return -> {
                        val value = stmt.value
                        val owner = (value as? IrExpr.Var)?.let(::origin)
                        // A returned owner moves out. Any other `Copy` place - a
                        // parameter, a global, a field - belongs to someone else,
                        // and the caller will own and free what it receives.
                        val transfer = if (owner != null)
                            IrExpr.Call("__take", listOf(owner), value!!.type)
                        else if (value != null && !borrowedReturn) isolatedPlace(value) else value
                        val nestedCleanup = cleanup(frames.filterNot { it.root })
                        if (transfer != null && (transfer !== value || nestedCleanup.isNotEmpty())) {
                            val name = "__owner_return_${temporary++}"
                            result += IrStmt.FinDecl(name, transfer.type, transfer)
                            result += nestedCleanup
                            result += IrStmt.Return(IrExpr.Var(name, transfer.type))
                        } else result += stmt
                    }
                    is IrStmt.Assignment -> {
                        frames.asReversed().firstOrNull { stmt.name in it.aliases }?.aliases?.remove(stmt.name)
                        (stmt.value as? IrExpr.Var)?.let(::origin)?.let { frame.aliases[stmt.name] = it }
                        val owner = owned(stmt.name)
                        if (owner == null) result += stmt else {
                            // Evaluate before dropping the old value; `x = take x`
                            // and a failed initializer cannot destroy the input early.
                            val name = "__owner_replace_${temporary++}"
                            result += IrStmt.FinDecl(name, stmt.value.type, isolatedPlace(stmt.value))
                            result += purge(owner)
                            result += stmt.copy(value = IrExpr.Var(name, stmt.value.type))
                        }
                    }
                    is IrStmt.Break, is IrStmt.Continue -> {
                        val target = if (stmt is IrStmt.Break) stmt.label else (stmt as IrStmt.Continue).label
                        val index = frames.indexOfLast { it.loop && (target == null || target == it.label) }
                        if (index >= 0) result += cleanup(frames.subList(index, frames.size))
                        result += stmt
                    }
                    is IrStmt.Throw -> { result += cleanup(frames.filterNot { it.root }); result += stmt }
                    is IrStmt.ExprStmt -> {
                        val call = stmt.expr as? IrExpr.MethodCall
                        val removed = call?.takeIf { it.target.type is IrType.Array && it.name in setOf("removeAt", "removeFirst", "removeLast", "pop") }
                        if (removed != null && (removed.type is IrType.Named || removed.type is IrType.Array || removed.type is IrType.Pointer || removed.type is IrType.Nullable))
                            result += purge(removed) else result += stmt
                    }
                    is IrStmt.Scope -> result += stmt.copy(body = lower(stmt.body))
                    is IrStmt.If -> result += stmt.copy(thenBranch = lower(stmt.thenBranch), elseBranch = stmt.elseBranch?.let { lower(it) })
                    is IrStmt.While -> result += stmt.copy(body = lower(stmt.body, loop = true, label = stmt.label))
                    is IrStmt.For -> result += stmt.copy(body = lower(stmt.body, loop = true, label = stmt.label))
                    is IrStmt.ForEach -> result += stmt.copy(body = lower(stmt.body, loop = true, label = stmt.label))
                    is IrStmt.Loop -> result += stmt.copy(body = lower(stmt.body, loop = true, label = stmt.label))
                    is IrStmt.When -> result += stmt.copy(branches = stmt.branches.map { it.copy(body = lower(it.body)) }, elseBranch = stmt.elseBranch?.let { lower(it) })
                    is IrStmt.Try -> result += stmt.copy(body = lower(stmt.body), catchBody = stmt.catchBody?.let { lower(it) })
                    else -> result += stmt
                }
            }
            if (!root) result += cleanup(listOf(frame))
            frames.removeAt(frames.lastIndex)
            return result
        }

        private fun expressions(stmt: IrStmt): IrStmt = when (stmt) {
            is IrStmt.VarDecl -> stmt.copy(initializer = expression(stmt.initializer))
            is IrStmt.FinDecl -> stmt.copy(initializer = expression(stmt.initializer))
            is IrStmt.LetDecl -> stmt.copy(initializer = expression(stmt.initializer))
            is IrStmt.Assignment -> stmt.copy(value = expression(stmt.value))
            is IrStmt.Return -> stmt.copy(value = stmt.value?.let { expression(it) })
            is IrStmt.ExprStmt -> stmt.copy(expr = expression(stmt.expr))
            is IrStmt.If -> stmt.copy(condition = expression(stmt.condition))
            is IrStmt.While -> stmt.copy(condition = expression(stmt.condition))
            is IrStmt.ForEach -> stmt.copy(iterable = expression(stmt.iterable))
            is IrStmt.For -> stmt.copy(start = expression(stmt.start), end = expression(stmt.end), step = stmt.step?.let { expression(it) })
            is IrStmt.When -> stmt.copy(scrutinee = expression(stmt.scrutinee))
            is IrStmt.MemberAssign -> stmt.copy(target = expression(stmt.target), value = expression(stmt.value).let {
                if (ownsField(stmt.target.type, stmt.name)) isolatedPlace(it) else it
            })
            is IrStmt.IndexAssign -> stmt.copy(target = expression(stmt.target), index = expression(stmt.index), value = expression(stmt.value).let {
                if (stmt.target.type is IrType.Array || stmt.target.type is IrType.Map) isolatedPlace(it) else it
            })
            is IrStmt.Throw -> stmt.copy(value = expression(stmt.value))
            is IrStmt.Assert -> stmt.copy(condition = expression(stmt.condition), message = expression(stmt.message))
            else -> stmt
        }

        private fun expression(expr: IrExpr, task: Boolean = false): IrExpr = when (expr) {
            is IrExpr.Call -> expr.copy(args = expr.args.mapIndexed { i, value ->
                val argument = expression(value, task = expr.name == "async" || expr.name == "__launch")
                val callee = functions[expr.name]
                val parameter = callee?.params?.getOrNull(i)?.second
                when {
                    callee == null -> argument
                    // A shared borrow cannot be kept, so a new allocation lent
                    // to one is freed after the call too - unless the result is
                    // itself a borrow, which may point into it.
                    i in callee.refParams -> if (i !in callee.exclusiveParams && !context.returnsBorrow(expr.name) &&
                        freshArgument(argument)) IrExpr.Call("__temporary", listOf(argument), argument.type) else argument
                    parameter is IrType.Function -> copyCallablePlace(argument)
                    // An erased parameter the callee keeps: only this call site
                    // knows the value is a `Copy` it must not share.
                    parameter != null && erased(parameter) && i in context.retained[expr.name].orEmpty() -> isolatedPlace(argument)
                    // A new allocation lent to a parameter the callee only
                    // borrows has no owner once the call returns: this call
                    // site frees it, right after the call.
                    i !in context.retained[expr.name].orEmpty() && expr.name != "async" && expr.name != "__launch" &&
                        freshArgument(argument) -> IrExpr.Call("__temporary", listOf(argument), argument.type)
                    else -> argument
                }
            }, receiver = expr.receiver?.let { expression(it) })
            is IrExpr.StructCtor -> expr.copy(args = expr.args.map { expression(it) })
            is IrExpr.MethodCall -> expr.copy(target = expression(expr.target), args = expr.args.map {
                val argument = copyCallablePlace(expression(it))
                if (stores(expr)) isolatedPlace(argument) else argument
            })
            is IrExpr.ArrayLiteral -> expr.copy(elements = expr.elements.map { expression(it) })
            is IrExpr.TupleLit -> expr.copy(elements = expr.elements.map { expression(it) })
            is IrExpr.Binary -> expr.copy(left = expression(expr.left), right = expression(expr.right))
            is IrExpr.Unary -> expr.copy(operand = expression(expr.operand))
            is IrExpr.Member -> expr.copy(target = expression(expr.target))
            is IrExpr.Index -> expr.copy(target = expression(expr.target), index = expression(expr.index))
            is IrExpr.IfExpr -> expr.copy(condition = expression(expr.condition), thenExpr = expression(expr.thenExpr), elseExpr = expression(expr.elseExpr))
            is IrExpr.CatchExpr -> expr.copy(expr = expression(expr.expr), fallback = expression(expr.fallback))
            is IrExpr.NumCast -> expr.copy(value = expression(expr.value))
            is IrExpr.Await -> expr.copy(value = expression(expr.value))
            is IrExpr.Lambda -> {
                val captures = if (task) expr.captureInitializers.mapNotNull { (name, value) ->
                    if (value.type is IrType.Named || value.type is IrType.Pointer || value.type is IrType.Nullable) IrExpr.Var(name, value.type) else null
                } else emptyList()
                val parameters = expr.params.take((expr.type as IrType.Function).params.size).mapNotNull { (name, type) ->
                    if (type is IrType.Function) IrExpr.Var(name, type) else null
                }
                expr.copy(body = Body(context).lower(expr.body, root = true, initialOwners = captures + parameters))
            }
            else -> expr
        }

        /**
         * [value] as an independent copy when it names a `Copy` value that
         * something else already owns - a binding, a field or an element. A
         * temporary has no other owner, so handing it over moves it.
         */
        private fun isolatedPlace(value: IrExpr): IrExpr =
            if ((value is IrExpr.Var || value is IrExpr.Member || value is IrExpr.Index) && context.copied(value.type))
                IrExpr.Call("__isolated", listOf(value), value.type) else value

        /** A value nothing owns yet: constructed, or returned new by a call. */
        private fun freshArgument(value: IrExpr): Boolean = allocates(value.type, context.fields) &&
            !((value.type as? IrType.Array)?.element?.let(::erased) ?: false) && when (value) {
            is IrExpr.StructCtor, is IrExpr.ArrayLiteral -> true
            is IrExpr.Call -> value.name in context.fresh
            else -> false
        }

        private fun ownsField(owner: IrType, name: String): Boolean {
            val pack = (owner as? IrType.Named)?.name ?: return false
            return context.fields[pack]?.get(name)?.ownsValue ?: false
        }

        private fun copyCallablePlace(value: IrExpr): IrExpr =
            if (value.type is IrType.Function && (value is IrExpr.Var || value is IrExpr.Member || value is IrExpr.Index))
                IrExpr.Call("__isolated", listOf(value), value.type) else value
    }
}
