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

package org.azora.lang.frontend

/**
 * Rewrites bare references that appear INSIDE a zone member's body to their
 * zone-mangled form, so that sibling declarations resolve without qualification.
 *
 * Zones desugar at parse time to flat mangled top-level items (`friend zone
 * std::math { func floor(){...} }` → `std__math__floor`). A sibling call like
 * `floor()` inside `std__math__round` would otherwise fail to resolve, because
 * the reference stays bare while the declaration is mangled.
 *
 * For each zone member (a top-level item whose name contains `__`), this pass
 * walks its body/initializer and rewrites a bare identifier/callee `X` to
 * `<zonePrefix>__X` when such a mangled sibling exists in the program — UNLESS
 * `X` is shadowed by a parameter or local declaration in that member (e.g.
 * `func pow(value, exp)` has a parameter `exp` that must not be rewritten to a
 * hypothetical `std__math__exp` sibling). Bare names with no mangled sibling
 * are left untouched. This gives zone members bare sibling access while keeping
 * cross-zone access qualified — the "friend" visibility rule.
 */
internal object IntraZoneRewriter {

    fun rewrite(program: Program): Program {
        val mangled = HashSet<String>()
        for (item in program.items) {
            if (item is TopLevel.Bridge) {
                item.funcs.mapTo(mangled) { it.name }
                continue
            }
            val name = nameOf(item) ?: continue
            if ("__" in name) mangled.add(name)
        }
        if (mangled.isEmpty()) return program

        val rewrittenItems = program.items.map { item ->
            if (item is TopLevel.Impl && item.zonePrefix != null) {
                return@map item.copy(methods = item.methods.map { method ->
                    val shadowed = collectShadowed(method)
                    method.copy(body = method.body.map { stmt(it, item.zonePrefix, mangled, shadowed) })
                })
            }
            val name = nameOf(item)
            val prefix = name?.zonePrefix() ?: return@map item
            val shadowed = collectShadowed(item)
            when (item) {
                is TopLevel.Func -> item.copy(decl = item.decl.copy(body = item.decl.body.map { stmt(it, prefix, mangled, shadowed) }))
                is TopLevel.Test -> item.copy(body = item.body.map { stmt(it, prefix, mangled, shadowed) })
                is TopLevel.FinDecl -> item.copy(initializer = expr(item.initializer, prefix, mangled, shadowed))
                is TopLevel.LetDecl -> item.copy(initializer = expr(item.initializer, prefix, mangled, shadowed))
                is TopLevel.VarDecl -> item.copy(initializer = expr(item.initializer, prefix, mangled, shadowed))
                else -> item
            }
        }
        return program.copy(items = rewrittenItems)
    }

    private fun nameOf(item: TopLevel): String? = when (item) {
        is TopLevel.Func -> item.decl.name
        is TopLevel.FinDecl -> item.name
        is TopLevel.LetDecl -> item.name
        is TopLevel.VarDecl -> item.name
        else -> null
    }

    /** `std__math__floor` → `std__math`; a bare name with no `__` → null. */
    private fun String.zonePrefix(): String? {
        val idx = lastIndexOf("__")
        if (idx <= 0) return null
        return substring(0, idx)
    }

    /**
     * Names declared inside [item] (parameters + local bindings) that shadow a
     * same-named zone sibling and must therefore NOT be rewritten.
     */
    private fun collectShadowed(item: TopLevel): Set<String> {
        val names = mutableSetOf<String>()
        when (item) {
            is TopLevel.Func -> {
                names.addAll(collectShadowed(item.decl))
            }
            is TopLevel.Test -> item.body.forEach { collectStmtNames(it, names) }
            else -> {}
        }
        return names
    }

    private fun collectShadowed(func: FuncDecl): Set<String> = mutableSetOf<String>().apply {
        func.params.forEach { add(it.name) }
        func.body.forEach { collectStmtNames(it, this) }
    }

    private fun collectStmtNames(s: Stmt, names: MutableSet<String>) {
        when (s) {
            is Stmt.VarDecl -> { names.add(s.name); collectExprNames(s.initializer, names) }
            is Stmt.LetDecl -> { names.add(s.name); collectExprNames(s.initializer, names) }
            is Stmt.FinDecl -> { names.add(s.name); collectExprNames(s.initializer, names) }
            is Stmt.Assignment -> collectExprNames(s.value, names)
            is Stmt.IndexAssign -> { collectExprNames(s.target, names); collectExprNames(s.index, names); collectExprNames(s.value, names) }
            is Stmt.MemberAssign -> { collectExprNames(s.target, names); collectExprNames(s.value, names) }
            is Stmt.DerefAssign -> { collectExprNames(s.target, names); collectExprNames(s.value, names) }
            is Stmt.Return -> s.value?.let { collectExprNames(it, names) }
            is Stmt.ExprStmt -> collectExprNames(s.expr, names)
            is Stmt.Throw -> collectExprNames(s.value, names)
            is Stmt.Yield -> collectExprNames(s.value, names)
            is Stmt.Assert -> { collectExprNames(s.condition, names); collectExprNames(s.message, names) }
            is Stmt.Trace -> { s.level?.let { collectExprNames(it, names) }; collectExprNames(s.message, names) }
            is Stmt.InlineTrace -> { s.level?.let { collectExprNames(it, names) }; collectExprNames(s.message, names) }
            is Stmt.If -> { collectExprNames(s.condition, names); s.thenBranch.forEach { collectStmtNames(it, names) }; s.elseBranch?.forEach { collectStmtNames(it, names) } }
            is Stmt.While -> { collectExprNames(s.condition, names); s.body.forEach { collectStmtNames(it, names) } }
            is Stmt.For -> { names.add(s.name); collectExprNames(s.iterable, names); s.step?.let { collectExprNames(it, names) }; s.body.forEach { collectStmtNames(it, names) } }
            is Stmt.Loop -> s.body.forEach { collectStmtNames(it, names) }
            is Stmt.When -> {
                collectExprNames(s.scrutinee, names)
                s.branches.forEach { it.patterns.forEach { p -> collectExprNames(p, names) }; it.body.forEach { b -> collectStmtNames(b, names) } }
                s.elseBranch?.forEach { collectStmtNames(it, names) }
            }
            is Stmt.Try -> { s.body.forEach { collectStmtNames(it, names) }; s.catchBody?.forEach { collectStmtNames(it, names) } }
            is Stmt.Defer -> s.body.forEach { collectStmtNames(it, names) }
            is Stmt.Zone -> s.body.forEach { collectStmtNames(it, names) }
            is Stmt.FriendZone -> s.body.forEach { collectStmtNames(it, names) }
            else -> {}
        }
    }

    private fun collectExprNames(e: Expr, names: MutableSet<String>) {
        when (e) {
            is Expr.Lambda -> {
                e.params.forEach { names.add(it.name) }
                e.receivers.forEach { names.add(it.name) }
                e.body.forEach { collectStmtNames(it, names) }
            }
            is Expr.MethodCall -> { collectExprNames(e.target, names); e.args.forEach { collectExprNames(it, names) } }
            is Expr.Member -> collectExprNames(e.target, names)
            is Expr.Call -> e.args.forEach { collectExprNames(it, names) }
            is Expr.Binary -> { collectExprNames(e.left, names); collectExprNames(e.right, names) }
            is Expr.Unary -> collectExprNames(e.operand, names)
            is Expr.Grouping -> collectExprNames(e.expr, names)
            is Expr.Index -> { collectExprNames(e.target, names); collectExprNames(e.index, names) }
            is Expr.Range -> { collectExprNames(e.from, names); collectExprNames(e.to, names) }
            is Expr.ArrayLiteral -> e.elements.forEach { collectExprNames(it, names) }
            is Expr.MapLit -> e.entries.forEach { (k, v) -> collectExprNames(k, names); collectExprNames(v, names) }
            is Expr.TupleLit -> e.elements.forEach { collectExprNames(it, names) }
            is Expr.VariantLit -> e.elements.forEach { collectExprNames(it, names) }
            is Expr.TupleAccess -> collectExprNames(e.target, names)
            is Expr.StringTemplate -> e.parts.forEach { p -> if (p is Expr.StringTemplatePart.Expr) collectExprNames(p.expr, names) }
            is Expr.NamedArg -> collectExprNames(e.value, names)
            is Expr.CatchExpr -> { collectExprNames(e.expr, names); collectExprNames(e.fallback, names) }
            is Expr.TryPropagate -> collectExprNames(e.expr, names)
            is Expr.IfExpr -> { collectExprNames(e.condition, names); collectExprNames(e.thenExpr, names); collectExprNames(e.elseExpr, names) }
            is Expr.NullCoalesce -> { collectExprNames(e.left, names); collectExprNames(e.right, names) }
            is Expr.Cast -> collectExprNames(e.expr, names)
            is Expr.IsCheck -> collectExprNames(e.expr, names)
            is Expr.SafeMember -> collectExprNames(e.target, names)
            is Expr.Alloc -> collectExprNames(e.value, names)
            is Expr.AllocBuffer -> collectExprNames(e.count, names)
            is Expr.Deref -> collectExprNames(e.target, names)
            is Expr.Isolated -> collectExprNames(e.value, names)
            is Expr.Await -> collectExprNames(e.value, names)
            is Expr.Spread -> collectExprNames(e.array, names)
            else -> {}
        }
    }

    private fun maybe(name: String, prefix: String, mangled: Set<String>, shadowed: Set<String>): String? {
        if ("__" in name) return null              // already qualified
        if (name in shadowed) return null          // parameter/local shadows the sibling
        val qualified = "${prefix}__$name"
        return if (qualified in mangled) qualified else null
    }

    private fun stmt(s: Stmt, prefix: String, mangled: Set<String>, shadowed: Set<String>): Stmt = when (s) {
        is Stmt.VarDecl -> s.copy(initializer = expr(s.initializer, prefix, mangled, shadowed))
        is Stmt.LetDecl -> s.copy(initializer = expr(s.initializer, prefix, mangled, shadowed))
        is Stmt.FinDecl -> s.copy(initializer = expr(s.initializer, prefix, mangled, shadowed))
        is Stmt.Assignment -> s.copy(value = expr(s.value, prefix, mangled, shadowed))
        is Stmt.IndexAssign -> s.copy(target = expr(s.target, prefix, mangled, shadowed), index = expr(s.index, prefix, mangled, shadowed), value = expr(s.value, prefix, mangled, shadowed))
        is Stmt.MemberAssign -> s.copy(target = expr(s.target, prefix, mangled, shadowed), value = expr(s.value, prefix, mangled, shadowed))
        is Stmt.DerefAssign -> s.copy(target = expr(s.target, prefix, mangled, shadowed), value = expr(s.value, prefix, mangled, shadowed))
        is Stmt.Return -> s.copy(value = s.value?.let { expr(it, prefix, mangled, shadowed) })
        is Stmt.ExprStmt -> s.copy(expr = expr(s.expr, prefix, mangled, shadowed))
        is Stmt.Throw -> s.copy(value = expr(s.value, prefix, mangled, shadowed))
        is Stmt.Yield -> s.copy(value = expr(s.value, prefix, mangled, shadowed))
        is Stmt.Assert -> s.copy(condition = expr(s.condition, prefix, mangled, shadowed), message = expr(s.message, prefix, mangled, shadowed))
        is Stmt.Trace -> s.copy(
            message = expr(s.message, prefix, mangled, shadowed),
            level = s.level?.let { expr(it, prefix, mangled, shadowed) },
        )
        is Stmt.InlineTrace -> s.copy(
            message = expr(s.message, prefix, mangled, shadowed),
            level = s.level?.let { expr(it, prefix, mangled, shadowed) },
        )
        is Stmt.If -> s.copy(condition = expr(s.condition, prefix, mangled, shadowed), thenBranch = s.thenBranch.map { stmt(it, prefix, mangled, shadowed) }, elseBranch = s.elseBranch?.map { stmt(it, prefix, mangled, shadowed) })
        is Stmt.While -> s.copy(condition = expr(s.condition, prefix, mangled, shadowed), body = s.body.map { stmt(it, prefix, mangled, shadowed) })
        is Stmt.For -> s.copy(iterable = expr(s.iterable, prefix, mangled, shadowed), step = s.step?.let { expr(it, prefix, mangled, shadowed) }, body = s.body.map { stmt(it, prefix, mangled, shadowed) })
        is Stmt.Loop -> s.copy(body = s.body.map { stmt(it, prefix, mangled, shadowed) })
        is Stmt.When -> s.copy(
            scrutinee = expr(s.scrutinee, prefix, mangled, shadowed),
            branches = s.branches.map { it.copy(patterns = it.patterns.map { expr(it, prefix, mangled, shadowed) }, body = it.body.map { stmt(it, prefix, mangled, shadowed) }) },
            elseBranch = s.elseBranch?.map { stmt(it, prefix, mangled, shadowed) },
        )
        is Stmt.Try -> s.copy(body = s.body.map { stmt(it, prefix, mangled, shadowed) }, catchBody = s.catchBody?.map { stmt(it, prefix, mangled, shadowed) })
        is Stmt.Defer -> s.copy(body = s.body.map { stmt(it, prefix, mangled, shadowed) })
        is Stmt.Zone -> s.copy(body = s.body.map { stmt(it, prefix, mangled, shadowed) })
        is Stmt.FriendZone -> s.copy(body = s.body.map { stmt(it, prefix, mangled, shadowed) })
        else -> s
    }

    private fun expr(e: Expr, prefix: String, mangled: Set<String>, shadowed: Set<String>): Expr = when (e) {
        is Expr.Identifier -> maybe(e.name, prefix, mangled, shadowed)?.let { e.copy(name = it) } ?: e
        is Expr.MethodCall -> e.copy(target = expr(e.target, prefix, mangled, shadowed), args = e.args.map { expr(it, prefix, mangled, shadowed) })
        is Expr.Member -> e.copy(target = expr(e.target, prefix, mangled, shadowed))
        is Expr.Call -> {
            val args = e.args.map { expr(it, prefix, mangled, shadowed) }
            val calleeQual = maybe(e.callee, prefix, mangled, shadowed)
            if (calleeQual != null) e.copy(callee = calleeQual, args = args) else e.copy(args = args)
        }
        is Expr.Binary -> e.copy(left = expr(e.left, prefix, mangled, shadowed), right = expr(e.right, prefix, mangled, shadowed))
        is Expr.Unary -> e.copy(operand = expr(e.operand, prefix, mangled, shadowed))
        is Expr.Grouping -> e.copy(expr = expr(e.expr, prefix, mangled, shadowed))
        is Expr.Index -> e.copy(target = expr(e.target, prefix, mangled, shadowed), index = expr(e.index, prefix, mangled, shadowed))
        is Expr.Range -> e.copy(from = expr(e.from, prefix, mangled, shadowed), to = expr(e.to, prefix, mangled, shadowed))
        is Expr.ArrayLiteral -> e.copy(elements = e.elements.map { expr(it, prefix, mangled, shadowed) })
        is Expr.MapLit -> e.copy(entries = e.entries.map { (k, v) -> expr(k, prefix, mangled, shadowed) to expr(v, prefix, mangled, shadowed) })
        is Expr.TupleLit -> e.copy(elements = e.elements.map { expr(it, prefix, mangled, shadowed) })
        is Expr.VariantLit -> e.copy(elements = e.elements.map { expr(it, prefix, mangled, shadowed) })
        is Expr.TupleAccess -> e.copy(target = expr(e.target, prefix, mangled, shadowed))
        is Expr.StringTemplate -> e.copy(parts = e.parts.map { part ->
            if (part is Expr.StringTemplatePart.Expr) Expr.StringTemplatePart.Expr(expr(part.expr, prefix, mangled, shadowed)) else part
        })
        is Expr.Lambda -> e.copy(body = e.body.map { stmt(it, prefix, mangled, shadowed) })
        is Expr.NamedArg -> e.copy(value = expr(e.value, prefix, mangled, shadowed))
        is Expr.CatchExpr -> e.copy(expr = expr(e.expr, prefix, mangled, shadowed), fallback = expr(e.fallback, prefix, mangled, shadowed))
        is Expr.TryPropagate -> e.copy(expr = expr(e.expr, prefix, mangled, shadowed))
        is Expr.IfExpr -> e.copy(condition = expr(e.condition, prefix, mangled, shadowed), thenExpr = expr(e.thenExpr, prefix, mangled, shadowed), elseExpr = expr(e.elseExpr, prefix, mangled, shadowed))
        is Expr.NullCoalesce -> e.copy(left = expr(e.left, prefix, mangled, shadowed), right = expr(e.right, prefix, mangled, shadowed))
        is Expr.Cast -> e.copy(expr = expr(e.expr, prefix, mangled, shadowed))
        is Expr.IsCheck -> e.copy(expr = expr(e.expr, prefix, mangled, shadowed))
        is Expr.SafeMember -> e.copy(target = expr(e.target, prefix, mangled, shadowed))
        is Expr.Alloc -> e.copy(value = expr(e.value, prefix, mangled, shadowed))
        is Expr.AllocBuffer -> e.copy(count = expr(e.count, prefix, mangled, shadowed))
        is Expr.Deref -> e.copy(target = expr(e.target, prefix, mangled, shadowed))
        is Expr.Isolated -> e.copy(value = expr(e.value, prefix, mangled, shadowed))
        is Expr.Await -> e.copy(value = expr(e.value, prefix, mangled, shadowed))
        is Expr.Spread -> e.copy(array = expr(e.array, prefix, mangled, shadowed))
        else -> e
    }
}
