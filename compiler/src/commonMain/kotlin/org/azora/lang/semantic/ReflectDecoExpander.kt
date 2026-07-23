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
 * The loop is unrolled once per program type carrying decorator `D`, with `X`
 * substituted by that type's name. The result is ordinary static code (e.g.
 * `X::run(w)` → `Movement::run(w); Physics::run(w)`), so it lowers to every
 * backend with no function values.
 *
 * The pass is fully generic: it attaches no meaning to any decorator and knows
 * nothing about ECS or any library. It runs after stdlib/library injection, so
 * types decorated in any module are visible. `reflect<*>.withDeco<D>` parses to a
 * call `__withDeco(*)` carrying `D` as its single type argument (see the parser).
 */
object ReflectDecoExpander {

    fun expand(program: Program): Program {
        if (program.items.none { it is TopLevel.Pack }) return program
        val decoratedByName = HashMap<String, MutableList<String>>()
        for (item in program.items) {
            val (name, annotations) = when (item) {
                is TopLevel.Pack -> item.name to item.annotations
                else -> continue
            }
            for (a in annotations) decoratedByName.getOrPut(a.name) { mutableListOf() }.add(name)
        }
        return program.copy(items = program.items.map { item ->
            if (item is TopLevel.Func) item.copy(decl = expandDecl(item.decl, decoratedByName)) else item
        })
    }

    private fun expandDecl(decl: FuncDecl, decorated: Map<String, List<String>>): FuncDecl =
        decl.copy(body = expandStmts(decl.body, decorated))

    private fun expandStmts(stmts: List<Stmt>, decorated: Map<String, List<String>>): List<Stmt> =
        stmts.flatMap { stmt ->
            val deco = withDecoName(stmt)
            if (stmt is Stmt.InlineFor && deco != null) {
                val types = decorated[deco].orEmpty()
                types.flatMap { typeName ->
                    expandStmts(stmt.body, decorated).map { substituteStmt(it, stmt.name, typeName) }
                }
            } else {
                listOf(recurseStmt(stmt, decorated))
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
    private fun recurseStmt(stmt: Stmt, decorated: Map<String, List<String>>): Stmt = when (stmt) {
        is Stmt.If -> stmt.copy(thenBranch = expandStmts(stmt.thenBranch, decorated), elseBranch = stmt.elseBranch?.let { expandStmts(it, decorated) })
        is Stmt.While -> stmt.copy(body = expandStmts(stmt.body, decorated))
        is Stmt.For -> stmt.copy(body = expandStmts(stmt.body, decorated))
        is Stmt.Loop -> stmt.copy(body = expandStmts(stmt.body, decorated))
        is Stmt.Zone -> stmt.copy(body = expandStmts(stmt.body, decorated))
        is Stmt.When -> stmt.copy(
            branches = stmt.branches.map { it.copy(body = expandStmts(it.body, decorated)) },
            elseBranch = stmt.elseBranch?.let { expandStmts(it, decorated) },
        )
        else -> stmt
    }

    // -- Loop-variable substitution -----------------------------------------

    private fun subName(name: String, from: String, to: String): String = when {
        name == from -> to
        name.startsWith("${from}__") -> to + name.substring(from.length) // `S__run` → `Movement__run`
        else -> name
    }

    private fun substituteStmt(stmt: Stmt, from: String, to: String): Stmt = when (stmt) {
        is Stmt.ExprStmt -> stmt.copy(expr = sub(stmt.expr, from, to))
        is Stmt.Return -> stmt.copy(value = stmt.value?.let { sub(it, from, to) })
        is Stmt.VarDecl -> stmt.copy(type = subType(stmt.type, from, to), initializer = sub(stmt.initializer, from, to))
        is Stmt.FinDecl -> stmt.copy(type = subType(stmt.type, from, to), initializer = sub(stmt.initializer, from, to))
        is Stmt.LetDecl -> stmt.copy(type = subType(stmt.type, from, to), initializer = sub(stmt.initializer, from, to))
        is Stmt.Assignment -> stmt.copy(value = sub(stmt.value, from, to))
        is Stmt.MemberAssign -> stmt.copy(target = sub(stmt.target, from, to), value = sub(stmt.value, from, to))
        is Stmt.IndexAssign -> stmt.copy(target = sub(stmt.target, from, to), index = sub(stmt.index, from, to), value = sub(stmt.value, from, to))
        is Stmt.If -> stmt.copy(condition = sub(stmt.condition, from, to), thenBranch = stmt.thenBranch.map { substituteStmt(it, from, to) }, elseBranch = stmt.elseBranch?.map { substituteStmt(it, from, to) })
        is Stmt.While -> stmt.copy(condition = sub(stmt.condition, from, to), body = stmt.body.map { substituteStmt(it, from, to) })
        is Stmt.For -> stmt.copy(iterable = sub(stmt.iterable, from, to), body = stmt.body.map { substituteStmt(it, from, to) })
        is Stmt.Loop -> stmt.copy(body = stmt.body.map { substituteStmt(it, from, to) })
        is Stmt.Zone -> stmt.copy(body = stmt.body.map { substituteStmt(it, from, to) })
        else -> stmt
    }

    private fun subType(ann: TypeAnnotation, from: String, to: String): TypeAnnotation =
        if (ann is TypeAnnotation.Explicit) TypeAnnotation.Explicit(subTypeRef(ann.ref, from, to)) else ann

    private fun subTypeRef(ref: TypeRef, from: String, to: String): TypeRef = when (ref) {
        is TypeRef.Named -> ref.copy(name = if (ref.name == from) to else ref.name, args = ref.args.map { subTypeRef(it, from, to) })
        is TypeRef.Array -> ref.copy(element = subTypeRef(ref.element, from, to))
        is TypeRef.Nullable -> ref.copy(inner = subTypeRef(ref.inner, from, to))
        is TypeRef.Reference -> ref.copy(inner = subTypeRef(ref.inner, from, to))
        else -> ref
    }

    private fun sub(expr: Expr, from: String, to: String): Expr = when (expr) {
        is Expr.Identifier -> if (expr.name == from) expr.copy(name = to) else expr
        is Expr.Call -> expr.copy(callee = subName(expr.callee, from, to), args = expr.args.map { sub(it, from, to) }, receiver = expr.receiver?.let { sub(it, from, to) })
        is Expr.MethodCall -> expr.copy(target = sub(expr.target, from, to), args = expr.args.map { sub(it, from, to) })
        is Expr.Member -> expr.copy(target = sub(expr.target, from, to))
        is Expr.SafeMember -> expr.copy(target = sub(expr.target, from, to))
        is Expr.Index -> expr.copy(target = sub(expr.target, from, to), index = sub(expr.index, from, to))
        is Expr.TupleAccess -> expr.copy(target = sub(expr.target, from, to))
        is Expr.Binary -> expr.copy(left = sub(expr.left, from, to), right = sub(expr.right, from, to))
        is Expr.Unary -> expr.copy(operand = sub(expr.operand, from, to))
        is Expr.Grouping -> expr.copy(expr = sub(expr.expr, from, to))
        is Expr.Cast -> expr.copy(expr = sub(expr.expr, from, to), targetType = subTypeRef(expr.targetType, from, to))
        is Expr.ArrayLiteral -> expr.copy(elements = expr.elements.map { sub(it, from, to) })
        else -> expr
    }
}
