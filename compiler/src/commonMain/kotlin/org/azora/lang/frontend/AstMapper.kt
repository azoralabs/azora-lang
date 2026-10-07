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
 * A structural copy of a function body that visits every statement, expression,
 * parameter and written type - including the bodies of lambdas, `using`,
 * `defer`, `try` and `effect` blocks.
 *
 * A pass overrides the hooks it cares about and calls `super` for the rest, so it
 * reaches code at any depth without restating the shape of the tree. Missing a
 * node kind is what kept compile-time loops from expanding inside a trailing
 * lambda; the `when`s here are exhaustive, so a new node kind fails to compile
 * until it is mapped.
 *
 * [stmts] may expand one statement into several (or none) through [expandStmt],
 * which is how an unrolled loop splices its iterations into the enclosing block.
 */
open class AstMapper {

    /** A block: each statement mapped, and possibly expanded in place. */
    open fun stmts(body: List<Stmt>): List<Stmt> = body.flatMap { expandStmt(it) }

    /** One statement as a block entry; by default exactly one mapped statement. */
    open fun expandStmt(s: Stmt): List<Stmt> = listOf(stmt(s))

    open fun typeRef(ref: TypeRef): TypeRef = when (ref) {
        is TypeRef.Named -> ref.copy(args = ref.args.map { typeRef(it) })
        is TypeRef.Array -> ref.copy(element = typeRef(ref.element))
        is TypeRef.Map -> ref.copy(key = typeRef(ref.key), value = typeRef(ref.value))
        is TypeRef.Set -> ref.copy(element = typeRef(ref.element))
        is TypeRef.Function -> ref.copy(
            params = ref.params.map { typeRef(it) },
            ret = typeRef(ref.ret),
            receivers = ref.receivers.map { typeRef(it) },
        )
        is TypeRef.Tuple -> ref.copy(elements = ref.elements.map { typeRef(it) })
        is TypeRef.Nullable -> ref.copy(inner = typeRef(ref.inner))
        is TypeRef.Failable -> ref.copy(ok = typeRef(ref.ok))
        is TypeRef.Pointer -> ref.copy(inner = typeRef(ref.inner))
        is TypeRef.Reference -> ref.copy(inner = typeRef(ref.inner))
        else -> ref
    }

    open fun typeAnnotation(annotation: TypeAnnotation): TypeAnnotation =
        if (annotation is TypeAnnotation.Explicit) TypeAnnotation.Explicit(typeRef(annotation.ref)) else annotation

    open fun param(param: Param): Param = param.copy(
        type = typeRef(param.type),
        defaultValue = param.defaultValue?.let { expr(it) },
    )

    open fun stmt(s: Stmt): Stmt = when (s) {
        is Stmt.Import -> s
        is Stmt.VarDecl -> s.copy(type = typeAnnotation(s.type), initializer = expr(s.initializer))
        is Stmt.FinDecl -> s.copy(type = typeAnnotation(s.type), initializer = expr(s.initializer))
        is Stmt.LetDecl -> s.copy(type = typeAnnotation(s.type), initializer = expr(s.initializer))
        is Stmt.InlineVar -> s.copy(initializer = expr(s.initializer))
        is Stmt.InlineFin -> s.copy(initializer = expr(s.initializer))
        is Stmt.InlineLet -> s.copy(initializer = expr(s.initializer))
        is Stmt.RemDecl -> s.copy(initializer = expr(s.initializer))
        is Stmt.Assignment -> s.copy(value = expr(s.value))
        is Stmt.InlineAssignment -> s.copy(value = expr(s.value))
        is Stmt.Return -> s.copy(value = s.value?.let { expr(it) })
        is Stmt.ExprStmt -> s.copy(expr = expr(s.expr))
        is Stmt.Throw -> s.copy(value = expr(s.value))
        is Stmt.Panic -> s.copy(message = expr(s.message))
        is Stmt.Yield -> s.copy(value = expr(s.value))
        is Stmt.Assert -> s.copy(condition = expr(s.condition), message = expr(s.message))
        is Stmt.InlineAssert -> s.copy(condition = expr(s.condition), message = expr(s.message))
        is Stmt.Trace -> s.copy(message = expr(s.message), level = s.level?.let { expr(it) })
        is Stmt.InlineTrace -> s.copy(message = expr(s.message), level = s.level?.let { expr(it) })
        is Stmt.Exchange -> s.copy(left = expr(s.left), right = expr(s.right))
        is Stmt.IndexAssign -> s.copy(target = expr(s.target), index = expr(s.index), value = expr(s.value))
        is Stmt.MemberAssign -> s.copy(target = expr(s.target), value = expr(s.value))
        is Stmt.DerefAssign -> s.copy(target = expr(s.target), value = expr(s.value))
        is Stmt.If -> s.copy(
            condition = expr(s.condition),
            thenBranch = stmts(s.thenBranch),
            elseBranch = s.elseBranch?.let { stmts(it) },
        )
        is Stmt.InlineIf -> s.copy(
            condition = expr(s.condition),
            thenBranch = stmts(s.thenBranch),
            elseBranch = s.elseBranch?.let { stmts(it) },
        )
        is Stmt.DeepInlineIf -> s.copy(
            condition = expr(s.condition),
            thenBranch = stmts(s.thenBranch),
            elseBranch = s.elseBranch?.let { stmts(it) },
        )
        is Stmt.While -> s.copy(condition = expr(s.condition), body = stmts(s.body))
        is Stmt.For -> s.copy(iterable = expr(s.iterable), step = s.step?.let { expr(it) }, body = stmts(s.body))
        is Stmt.InlineFor -> s.copy(iterable = expr(s.iterable), body = stmts(s.body))
        is Stmt.Loop -> s.copy(iterable = s.iterable?.let { expr(it) }, body = stmts(s.body))
        is Stmt.Scope -> s.copy(body = stmts(s.body))
        is Stmt.InlineBlock -> s.copy(body = stmts(s.body))
        is Stmt.DeepInlineBlock -> s.copy(body = stmts(s.body))
        is Stmt.Effect -> s.copy(body = stmts(s.body), dependencies = s.dependencies?.map { expr(it) })
        is Stmt.UsingContext -> s.copy(values = s.values.map { expr(it) }, body = stmts(s.body))
        is Stmt.Defer -> s.copy(body = stmts(s.body))
        is Stmt.Try -> s.copy(body = stmts(s.body), catchBody = s.catchBody?.let { stmts(it) })
        is Stmt.When -> s.copy(
            scrutinee = expr(s.scrutinee),
            branches = s.branches.map { branch ->
                branch.copy(patterns = branch.patterns.map { expr(it) }, body = stmts(branch.body))
            },
            elseBranch = s.elseBranch?.let { stmts(it) },
        )
        is Stmt.NoInline -> s.copy(stmt = stmt(s.stmt))
        is Stmt.Break -> s.copy(value = s.value?.let { expr(it) })
        is Stmt.Continue -> s
    }

    /** One call argument; a pass may expand it into several. */
    open fun args(args: List<Expr>): List<Expr> = args.map { expr(it) }

    open fun expr(e: Expr): Expr = when (e) {
        is Expr.InferredMember -> e
        is Expr.MapEntryArg -> e.copy(key = expr(e.key), value = expr(e.value))
        is Expr.Identifier -> e
        is Expr.IntLiteral, is Expr.DoubleLiteral, is Expr.CharLiteral,
        is Expr.StringLiteral, is Expr.BoolLiteral, is Expr.NullLiteral,
        is Expr.UpperScopeAccess, is Expr.Inject -> e
        is Expr.Unary -> e.copy(operand = expr(e.operand))
        is Expr.IncDec -> e.copy(target = expr(e.target))
        is Expr.Grouping -> e.copy(expr = expr(e.expr))
        is Expr.Member -> e.copy(target = expr(e.target))
        is Expr.SafeMember -> e.copy(target = expr(e.target))
        is Expr.TupleAccess -> e.copy(target = expr(e.target))
        is Expr.Index -> e.copy(target = expr(e.target), index = expr(e.index))
        is Expr.Range -> e.copy(from = expr(e.from), to = expr(e.to))
        is Expr.Binary -> e.copy(left = expr(e.left), right = expr(e.right))
        is Expr.NullCoalesce -> e.copy(left = expr(e.left), right = expr(e.right))
        is Expr.CatchExpr -> e.copy(expr = expr(e.expr), fallback = expr(e.fallback))
        is Expr.Seal -> e.copy(value = expr(e.value))
        is Expr.IfExpr -> e.copy(condition = expr(e.condition), thenExpr = expr(e.thenExpr), elseExpr = expr(e.elseExpr))
        is Expr.Cast -> e.copy(expr = expr(e.expr), targetType = typeRef(e.targetType))
        is Expr.InlineForArgs -> e.copy(iterable = expr(e.iterable), body = expr(e.body))
        is Expr.IsCheck -> e.copy(expr = expr(e.expr))
        is Expr.InCheck -> e.copy(value = expr(e.value), collection = expr(e.collection))
        is Expr.Alloc -> e.copy(value = expr(e.value))
        is Expr.Deref -> e.copy(target = expr(e.target))
        is Expr.Isolated -> e.copy(value = expr(e.value))
        is Expr.Await -> e.copy(value = expr(e.value))
        is Expr.TryPropagate -> e.copy(expr = expr(e.expr))
        is Expr.Spread -> e.copy(array = expr(e.array))
        is Expr.NamedArg -> e.copy(value = expr(e.value))
        is Expr.Call -> e.copy(
            args = args(e.args),
            receiver = e.receiver?.let { expr(it) },
            typeArgs = e.typeArgs.map { typeRef(it) },
        )
        is Expr.MethodCall -> e.copy(target = expr(e.target), args = args(e.args))
        is Expr.ArrayLiteral -> e.copy(elements = e.elements.map { expr(it) })
        is Expr.SetLiteral -> e.copy(elements = e.elements.map { expr(it) })
        is Expr.TupleLit -> e.copy(elements = e.elements.map { expr(it) })
        is Expr.VariantLit -> e.copy(elements = e.elements.map { expr(it) })
        is Expr.MapLit -> e.copy(entries = e.entries.map { (key, value) -> expr(key) to expr(value) })
        is Expr.StringTemplate -> e.copy(parts = e.parts.map { part ->
            if (part is Expr.StringTemplatePart.Expr) Expr.StringTemplatePart.Expr(expr(part.expr)) else part
        })
        is Expr.Lambda -> e.copy(
            params = e.params.map { param(it) },
            receivers = e.receivers.map { param(it) },
            body = stmts(e.body),
        )
        is Expr.MetaInvoke -> e.copy(args = e.args.map { expr(it) })
        is Expr.Slice -> e.copy(
            target = expr(e.target),
            start = e.start?.let { expr(it) },
            stop = e.stop?.let { expr(it) },
            step = e.step?.let { expr(it) },
        )
    }
}
