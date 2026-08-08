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

package org.azora.lang.semantic

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef

/**
 * Expands `inline for X in reflect<*>.withDeco<D> { body }` at compile time.
 *
 * The loop is unrolled once per program declaration carrying decorator `D` - a
 * pack, an enum or a free function - with `X` substituted by that declaration's
 * name. The result is ordinary static code (`X::run(w)` → `Movement::run(w);
 * Physics::run(w)`, or `X(world, time)` → `movement(world, time)`), so it lowers
 * to every backend with no function values.
 *
 * Inside the unrolled body, `std::reflect<X>` queries resolve against the bound
 * declaration: `.annotMeta<D>.field` becomes the value that declaration passed for
 * `field` (or the decorator's default), `.hasDeco<D>` a boolean, and `.declName`
 * the declaration's own name as a string. Those are the only compile-time
 * context a caller gets here, and they are what lets a library read a decorator's
 * arguments back - the decorator stays the single place the information is
 * written.
 *
 * The pass is fully generic: it attaches no meaning to any decorator and knows
 * nothing about ECS or any library. It runs after stdlib/library injection, so
 * declarations decorated in any module are visible. `reflect<*>.withDeco<D>`
 * parses to a call `__withDeco(*)` carrying `D` as its single type argument (see
 * the parser).
 */
object ReflectDecoExpander {

    fun expand(program: Program): Program {
        val decoratedByName = HashMap<String, MutableList<String>>()
        for (item in program.items) {
            val (name, annotations) = when (item) {
                is TopLevel.Pack -> item.name to item.annotations
                is TopLevel.Enum -> item.name to item.annotations
                is TopLevel.Fail -> item.name to item.annotations
                // A decorated free function enumerates under its own name, so the
                // unrolled body can call it directly.
                is TopLevel.Func -> item.decl.name to item.decl.annotations
                else -> continue
            }
            for (a in annotations) decoratedByName.getOrPut(a.name) { mutableListOf() }.add(name)
        }
        if (decoratedByName.isEmpty()) return program
        return program.copy(items = program.items.map { item ->
            when (item) {
                is TopLevel.Func -> item.copy(decl = expandDecl(item.decl, decoratedByName, program))
                is TopLevel.Impl -> item.copy(methods = item.methods.map { expandDecl(it, decoratedByName, program) })
                else -> item
            }
        })
    }

    private fun expandDecl(decl: FuncDecl, decorated: Map<String, List<String>>, program: Program): FuncDecl =
        decl.copy(body = expandStmts(decl.body, decorated, program))

    private fun expandStmts(stmts: List<Stmt>, decorated: Map<String, List<String>>, program: Program): List<Stmt> =
        stmts.flatMap { stmt ->
            val deco = withDecoName(stmt)
            if (stmt is Stmt.InlineFor && deco != null) {
                val names = decorated[deco].orEmpty()
                names.flatMap { name ->
                    val sub = Substitution(stmt.name, name, program)
                    expandStmts(stmt.body, decorated, program).map { sub.stmt(it) }
                }
            } else {
                listOf(recurseStmt(stmt, decorated, program))
            }
        }

    /** The decorator name if [stmt] is `inline for X in reflect<*>.withDeco<D>`, else null. */
    private fun withDecoName(stmt: Stmt): String? {
        if (stmt !is Stmt.InlineFor) return null
        val call = stmt.iterable as? Expr.Call ?: return null
        if (call.callee != "__withDeco") return null
        return (call.typeArgs.singleOrNull() as? TypeRef.Named)?.name
    }

    /** Recurses into nested statement bodies so `withDeco` loops anywhere are expanded. */
    private fun recurseStmt(stmt: Stmt, decorated: Map<String, List<String>>, program: Program): Stmt = when (stmt) {
        is Stmt.If -> stmt.copy(thenBranch = expandStmts(stmt.thenBranch, decorated, program), elseBranch = stmt.elseBranch?.let { expandStmts(it, decorated, program) })
        is Stmt.While -> stmt.copy(body = expandStmts(stmt.body, decorated, program))
        is Stmt.For -> stmt.copy(body = expandStmts(stmt.body, decorated, program))
        is Stmt.Loop -> stmt.copy(body = expandStmts(stmt.body, decorated, program))
        is Stmt.Scope -> stmt.copy(body = expandStmts(stmt.body, decorated, program))
        is Stmt.When -> stmt.copy(
            branches = stmt.branches.map { it.copy(body = expandStmts(it.body, decorated, program)) },
            elseBranch = stmt.elseBranch?.let { expandStmts(it, decorated, program) },
        )
        else -> stmt
    }

    // -- Loop-variable substitution -----------------------------------------

    /**
     * One unrolled iteration: rewrites the loop variable [from] to the bound
     * declaration [to], and resolves the `std::reflect<[from]>` queries that only
     * this binding can answer.
     */
    private class Substitution(val from: String, val to: String, val program: Program) {

        private fun subName(name: String): String = when {
            name == from -> to
            name.startsWith("${from}__") -> to + name.substring(from.length) // `S__run` → `Movement__run`
            else -> name
        }

        fun stmt(stmt: Stmt): Stmt = when (stmt) {
            is Stmt.ExprStmt -> stmt.copy(expr = expr(stmt.expr))
            is Stmt.Return -> stmt.copy(value = stmt.value?.let { expr(it) })
            is Stmt.VarDecl -> stmt.copy(type = type(stmt.type), initializer = expr(stmt.initializer))
            is Stmt.FinDecl -> stmt.copy(type = type(stmt.type), initializer = expr(stmt.initializer))
            is Stmt.LetDecl -> stmt.copy(type = type(stmt.type), initializer = expr(stmt.initializer))
            is Stmt.Assignment -> stmt.copy(value = expr(stmt.value))
            is Stmt.MemberAssign -> stmt.copy(target = expr(stmt.target), value = expr(stmt.value))
            is Stmt.IndexAssign -> stmt.copy(target = expr(stmt.target), index = expr(stmt.index), value = expr(stmt.value))
            is Stmt.If -> stmt.copy(condition = expr(stmt.condition), thenBranch = stmt.thenBranch.map { stmt(it) }, elseBranch = stmt.elseBranch?.map { stmt(it) })
            is Stmt.While -> stmt.copy(condition = expr(stmt.condition), body = stmt.body.map { stmt(it) })
            is Stmt.For -> stmt.copy(iterable = expr(stmt.iterable), body = stmt.body.map { stmt(it) })
            is Stmt.Loop -> stmt.copy(body = stmt.body.map { stmt(it) })
            is Stmt.Scope -> stmt.copy(body = stmt.body.map { stmt(it) })
            is Stmt.When -> stmt.copy(
                scrutinee = expr(stmt.scrutinee),
                branches = stmt.branches.map { b -> b.copy(patterns = b.patterns.map { expr(it) }, body = b.body.map { stmt(it) }) },
                elseBranch = stmt.elseBranch?.map { stmt(it) },
            )
            else -> stmt
        }

        fun type(ann: TypeAnnotation): TypeAnnotation =
            if (ann is TypeAnnotation.Explicit) TypeAnnotation.Explicit(typeRef(ann.ref)) else ann

        fun typeRef(ref: TypeRef): TypeRef = when (ref) {
            is TypeRef.Named -> ref.copy(name = if (ref.name == from) to else ref.name, args = ref.args.map { typeRef(it) })
            is TypeRef.Array -> ref.copy(element = typeRef(ref.element))
            is TypeRef.Nullable -> ref.copy(inner = typeRef(ref.inner))
            is TypeRef.Reference -> ref.copy(inner = typeRef(ref.inner))
            else -> ref
        }

        fun expr(e: Expr): Expr {
            reflectQuery(e)?.let { return it }
            return when (e) {
                is Expr.Identifier -> if (e.name == from) e.copy(name = to) else e
                is Expr.Call -> e.copy(callee = subName(e.callee), args = e.args.map { expr(it) }, receiver = e.receiver?.let { expr(it) })
                is Expr.MethodCall -> e.copy(target = expr(e.target), args = e.args.map { expr(it) })
                is Expr.Member -> e.copy(target = expr(e.target))
                is Expr.SafeMember -> e.copy(target = expr(e.target))
                is Expr.Index -> e.copy(target = expr(e.target), index = expr(e.index))
                is Expr.TupleAccess -> e.copy(target = expr(e.target))
                is Expr.Binary -> e.copy(left = expr(e.left), right = expr(e.right))
                is Expr.Unary -> e.copy(operand = expr(e.operand))
                is Expr.Grouping -> e.copy(expr = expr(e.expr))
                is Expr.Cast -> e.copy(expr = expr(e.expr), targetType = typeRef(e.targetType))
                is Expr.ArrayLiteral -> e.copy(elements = e.elements.map { expr(it) })
                is Expr.StringTemplate -> e.copy(parts = e.parts.map { part ->
                    if (part is Expr.StringTemplatePart.Expr) Expr.StringTemplatePart.Expr(expr(part.expr)) else part
                })
                is Expr.CatchExpr -> e.copy(expr = expr(e.expr), fallback = expr(e.fallback))
                else -> e
            }
        }

        // -- Compile-time reflection over the bound declaration ---------------

        /** The declaration the loop variable is bound to, or null if [e] asks about something else. */
        private fun boundReflect(e: Expr): Boolean {
            val call = e as? Expr.Call ?: return false
            if (call.callee != "__reflect") return false
            return (call.args.singleOrNull() as? Expr.Identifier)?.name == from
        }

        private fun site(): DecoratorMetadata.Site? =
            DecoratorMetadata.findSite(Expr.Identifier(to, 0, 0, to.length), emptyMap(), program)

        /**
         * Folds `std::reflect<X>.declName`, `.hasDeco<D>` and `.annotMeta<D>.field`
         * against the bound declaration; null when [e] is not such a query.
         *
         * An unresolvable query is left alone rather than guessed at, so the
         * existing "compile-time-only" diagnostic still reports it.
         */
        private fun reflectQuery(e: Expr): Expr? = when {
            // `std::reflect<X>.declName` - the declaration's own name.
            e is Expr.Member && e.name == "declName" && boundReflect(e.target) ->
                Expr.StringLiteral(to, e.line, e.column)

            // `std::reflect<X>.hasDeco<D>`
            e is Expr.Call && e.callee == "__hasDeco" &&
                (e.args.singleOrNull() as? Expr.Identifier)?.name == from -> {
                val deco = e.typeArgs.singleOrNull()?.displayName()
                val applied = site()?.let { s -> deco?.let { DecoratorMetadata.findApplied(s, it, program) } }
                Expr.BoolLiteral(applied != null, e.line, e.column)
            }

            // `std::reflect<X>.annotMeta<D>.field`
            e is Expr.Member && (e.target as? Expr.Call)?.callee == "__annotMeta" &&
                ((e.target as Expr.Call).args.singleOrNull() as? Expr.Identifier)?.name == from -> {
                val query = e.target as Expr.Call
                val deco = query.typeArgs.singleOrNull()?.displayName()
                val applied = site()?.let { s -> deco?.let { DecoratorMetadata.findApplied(s, it, program) } }
                applied?.let { DecoratorMetadata.fieldValue(it, e.name) }
            }

            else -> null
        }
    }
}
