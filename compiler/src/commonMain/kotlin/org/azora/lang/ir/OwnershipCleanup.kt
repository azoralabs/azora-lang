/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.ir

/** Materializes lexical destruction before optimization. Moves empty their source
 * at evaluation time, so conditional moves and explicit purge share the same
 * null-safe cleanup path. The function's own owners are registered as defers:
 * user defers reached later still see their owners alive. */
object OwnershipCleanup {
    fun lower(program: IrProgram): IrProgram = program.copy(items = program.items.map { item ->
        when (item) {
            is IrTopLevel.Func -> item.copy(function = item.function.copy(body = Body().lower(item.function.body, root = true)))
            is IrTopLevel.Test -> item.copy(body = Body().lower(item.body, root = true))
            else -> item
        }
    })

    private class Body {
        private data class Frame(val owners: MutableList<IrExpr.Var>, val root: Boolean, val loop: Boolean, val label: String?, val delayed: Boolean)
        private val frames = mutableListOf<Frame>()
        private var temporary = 0
        private fun purge(value: IrExpr) = IrStmt.ExprStmt(IrExpr.Call("__purge", listOf(value), IrType.Unit))
        private fun cleanup(frames: List<Frame>) = frames.asReversed().filterNot { it.delayed }.flatMap { it.owners.asReversed().map(::purge) }
        private fun owned(name: String) = frames.asReversed().flatMap { it.owners }.firstOrNull { it.name == name }

        fun lower(stmts: List<IrStmt>, root: Boolean = false, loop: Boolean = false, label: String? = null, initialOwners: List<IrExpr.Var> = emptyList()): List<IrStmt> {
            val frame = Frame(initialOwners.toMutableList(), root, loop, label, stmts.any { it is IrStmt.Defer })
            frames += frame
            val result = mutableListOf<IrStmt>()
            initialOwners.forEach { result += IrStmt.Defer(listOf(purge(it))) }
            for (raw in stmts) {
                val stmt = expressions(raw)
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
                        val transfer = if (value is IrExpr.Var && owned(value.name) != null)
                            IrExpr.Call("__take", listOf(value), value.type) else value
                        val nestedCleanup = cleanup(frames.filterNot { it.root })
                        if (transfer != null && (transfer !== value || nestedCleanup.isNotEmpty())) {
                            val name = "__owner_return_${temporary++}"
                            result += IrStmt.FinDecl(name, transfer.type, transfer)
                            result += nestedCleanup
                            result += IrStmt.Return(IrExpr.Var(name, transfer.type))
                        } else result += stmt
                    }
                    is IrStmt.Assignment -> {
                        val owner = owned(stmt.name)
                        if (owner == null) result += stmt else {
                            // Evaluate before dropping the old value; `x = take x`
                            // and a failed initializer cannot destroy the input early.
                            val name = "__owner_replace_${temporary++}"
                            result += IrStmt.FinDecl(name, stmt.value.type, stmt.value)
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
            is IrStmt.MemberAssign -> stmt.copy(target = expression(stmt.target), value = expression(stmt.value))
            is IrStmt.IndexAssign -> stmt.copy(target = expression(stmt.target), index = expression(stmt.index), value = expression(stmt.value))
            is IrStmt.Throw -> stmt.copy(value = expression(stmt.value))
            is IrStmt.Assert -> stmt.copy(condition = expression(stmt.condition), message = expression(stmt.message))
            else -> stmt
        }

        private fun expression(expr: IrExpr, task: Boolean = false): IrExpr = when (expr) {
            is IrExpr.Call -> expr.copy(args = expr.args.map { expression(it, task = expr.name == "async" || expr.name == "__launch") }, receiver = expr.receiver?.let { expression(it) })
            is IrExpr.StructCtor -> expr.copy(args = expr.args.map { expression(it) })
            is IrExpr.MethodCall -> expr.copy(target = expression(expr.target), args = expr.args.map { expression(it) })
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
                expr.copy(body = Body().lower(expr.body, root = true, initialOwners = captures))
            }
            else -> expr
        }
    }
}
