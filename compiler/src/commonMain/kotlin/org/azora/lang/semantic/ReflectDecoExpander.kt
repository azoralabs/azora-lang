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

import org.azora.lang.frontend.Annotation
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.NamedTypeMacroCall
import org.azora.lang.frontend.TypeFormKind
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.Param
import org.azora.lang.frontend.ParamModifier
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef

/**
 * Expands `inline for X in reflect<*>.withAnnot<D> { body }` at compile time.
 *
 * The loop is unrolled once per program declaration carrying decorator `D` - a
 * pack, an enum or a free function - with `X` substituted by that declaration's
 * name. The result is ordinary static code (`X::run(w)` → `Movement::run(w);
 * Physics::run(w)`, or `X(world, time)` → `movement(world, time)`), so it lowers
 * to every backend with no function values.
 *
 * Inside the unrolled body, `std::reflect<X>` queries resolve against the bound
 * declaration: `.annotMeta<D>.field` becomes the value that declaration passed for
 * `field` (or the decorator's default), `.hasAnnot<D>` a boolean, and `.declName`
 * the declaration's own name as a string. Those are the only compile-time
 * context a caller gets here, and they are what lets a library read a decorator's
 * arguments back - the decorator stays the single place the information is
 * written.
 *
 * The pass is fully generic: it attaches no meaning to any decorator and knows
 * nothing about any library. It runs after stdlib/library injection, so
 * declarations decorated in any module are visible. `reflect<*>.withAnnot<D>`
 * parses to a call `__withAnnot(*)` carrying `D` as its single type argument (see
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
            val deco = withAnnotName(stmt)
            if (stmt is Stmt.InlineFor && deco != null) {
                val names = decorated[deco].orEmpty()
                names.flatMap { name ->
                    val sub = Substitution(stmt.name, name, program)
                    sub.stmts(expandStmts(stmt.body, decorated, program))
                }
            } else {
                listOf(recurseStmt(stmt, decorated, program))
            }
        }

    /** The decorator name if [stmt] is `inline for X in reflect<*>.withAnnot<D>`, else null. */
    private fun withAnnotName(stmt: Stmt): String? {
        if (stmt !is Stmt.InlineFor) return null
        val call = stmt.iterable as? Expr.Call ?: return null
        if (call.callee != "__withAnnot") return null
        return (call.typeArgs.singleOrNull() as? TypeRef.Named)?.name
    }

    /** Recurses into nested statement bodies so `withAnnot` loops anywhere are expanded. */
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

        /**
         * A block, with any `inline for` over the parameters unrolled in place.
         *
         * The loop is a statement as often as it is an argument - reading a
         * parameter's name or its mutability is something a body does, not
         * something a call does - so both positions unroll here.
         */
        fun stmts(body: List<Stmt>): List<Stmt> = body.flatMap { s ->
            if (s is Stmt.InlineFor && boundParams(s.iterable)) {
                params().flatMap { param -> ParamSub(s.name, param, program).stmts(s.body).map { stmt(it) } }
            } else {
                listOf(stmt(s))
            }
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
            is Stmt.If -> stmt.copy(condition = expr(stmt.condition), thenBranch = stmts(stmt.thenBranch), elseBranch = stmt.elseBranch?.let { stmts(it) })
            is Stmt.While -> stmt.copy(condition = expr(stmt.condition), body = stmts(stmt.body))
            is Stmt.For -> stmt.copy(iterable = expr(stmt.iterable), body = stmts(stmt.body))
            is Stmt.Loop -> stmt.copy(body = stmts(stmt.body))
            is Stmt.Scope -> stmt.copy(body = stmts(stmt.body))
            is Stmt.When -> stmt.copy(
                scrutinee = expr(stmt.scrutinee),
                branches = stmt.branches.map { b -> b.copy(patterns = b.patterns.map { expr(it) }, body = stmts(b.body)) },
                elseBranch = stmt.elseBranch?.let { stmts(it) },
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
                is Expr.Call -> e.copy(callee = subName(e.callee), args = e.args.flatMap { arg(it) }, receiver = e.receiver?.let { expr(it) })
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

        // -- Parameters ------------------------------------------------------

        /**
         * One argument, or one per parameter when it is an `inline for` over them.
         *
         * `F(inline for P in std::reflect<F>.params { P::provide(world) })` is how a
         * caller supplies a declaration whose signature it does not know: the loop
         * unrolls to one argument per parameter, with `P` standing for that
         * parameter's type. Nothing here decides what an argument *is* - the body
         * does, so what a parameter is built from stays in the library that has
         * the parameters, not in this pass.
         */
        private fun arg(e: Expr): List<Expr> {
            if (e !is Expr.InlineForArgs || !boundParams(e.iterable)) return listOf(expr(e))
            return params().map { param -> expr(ParamSub(e.name, param, program).expr(e.body)) }
        }

        /** Whether [e] is `std::reflect<X>.params` for the bound declaration. */
        private fun boundParams(e: Expr): Boolean =
            e is Expr.Member && e.name == "params" && boundReflect((e.target as? Expr.Grouping)?.expr ?: e.target)

        /**
         * A parameter's type, with a type macro on it expanded.
         *
         * Reflection runs before type macros do, so a parameter written
         * `@rows [A, B]` still names the macro here. What it stands for is what
         * a caller has to build, so it is expanded now; [VariadicMonomorphizer]
         * remains the authority on the rules and expands every other position.
         */
        private fun expandedType(ref: TypeRef): TypeRef {
            val named = ref as? TypeRef.Named ?: return ref
            if (!NamedTypeMacroCall.isCall(named)) return ref
            val name = NamedTypeMacroCall.name(named)
            val shape = when (NamedTypeMacroCall.form(named)) {
                NamedTypeMacroCall.Form.Prefix -> TypeFormKind.PREFIX
                NamedTypeMacroCall.Form.List -> TypeFormKind.PREFIX_LIST
                NamedTypeMacroCall.Form.Infix -> TypeFormKind.INFIX
            }
            val modifier = NamedTypeMacroCall.modifier(named)
            val clauses = modifier.contains('|')
            val keywords = if (clauses) modifier.substringBefore('|').split(",") else emptyList()
            val shapes = if (clauses) modifier.substringAfter('|').split(",") else emptyList()
            val rule = program.typeMacroRules.firstOrNull { arm ->
                arm.name == name && arm.keywords == keywords &&
                    (if (clauses) arm.holeIsList == shapes.map { it.startsWith("L") } else arm.kind == shape)
            } ?: return ref

            var at = 0
            val bindings = rule.holes.mapIndexed { index, hole ->
                val count = if (clauses) shapes[index].drop(1).toIntOrNull() ?: 1 else named.args.size
                val slice = named.args.subList(at, minOf(at + count, named.args.size)).toList()
                at += count
                hole to slice
            }.toMap()

            fun fill(template: TypeRef): TypeRef = when (template) {
                is TypeRef.Named -> bindings[template.name]?.singleOrNull()
                    ?: template.copy(args = template.args.flatMap { bindings[(it as? TypeRef.Named)?.name] ?: listOf(fill(it)) })
                else -> template
            }
            return fill(rule.template)
        }

        /** The bound declaration's parameters, in order. */
        private fun params(): List<Param> =
            program.items.filterIsInstance<TopLevel.Func>()
                .firstOrNull { it.decl.name == to }
                ?.decl?.params?.map { it.copy(type = expandedType(it.type)) }
                .orEmpty()

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
         * Folds `std::reflect<X>.declName`, `.hasAnnot<D>` and `.annotMeta<D>.field`
         * against the bound declaration; null when [e] is not such a query.
         *
         * An unresolvable query is left alone rather than guessed at, so the
         * existing "compile-time-only" diagnostic still reports it.
         */
        private fun reflectQuery(e: Expr): Expr? = when {
            // `std::reflect<X>.declName` - the declaration's own name.
            e is Expr.Member && e.name == "declName" && boundReflect(e.target) ->
                Expr.StringLiteral(to, e.line, e.column)

            // `std::reflect<X>.hasAnnot<D>`
            e is Expr.Call && e.callee == "__hasAnnot" &&
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

    /**
     * One unrolled parameter: the loop variable stands for that parameter.
     *
     * Substituted as a type (`provide<P>(world)`) and as the qualifier of a static
     * call (`P::provide(world)` - which the parser has already joined into one
     * name, `P__provide`). A borrowed or applied type answers under its own base
     * name, because that is where its statics are declared: `Rows<Shape>` and
     * `Store&` reach `Rows::provide` and `Store::provide`.
     */
    private class ParamSub(val from: String, val param: Param, val program: Program) {

        private val type: TypeRef get() = param.type

        private fun base(ref: TypeRef): TypeRef.Named? = when (ref) {
            is TypeRef.Named -> ref
            is TypeRef.Reference -> base(ref.inner)
            is TypeRef.Nullable -> base(ref.inner)
            else -> null
        }

        fun typeRef(ref: TypeRef): TypeRef = when (ref) {
            is TypeRef.Named ->
                if ((ref.name == from || ref.name == "$from.ActualType") && ref.args.isEmpty()) type
                else ref.copy(args = ref.args.map { typeRef(it) })
            is TypeRef.Array -> ref.copy(element = typeRef(ref.element))
            is TypeRef.Nullable -> ref.copy(inner = typeRef(ref.inner))
            is TypeRef.Reference -> ref.copy(inner = typeRef(ref.inner))
            else -> ref
        }

        /** Whether [e] names a member of the bound parameter. */
        private fun onParam(e: Expr): Boolean = (e as? Expr.Identifier)?.name == from

        fun stmts(body: List<Stmt>): List<Stmt> = body.map { stmt(it) }

        fun stmt(s: Stmt): Stmt = when (s) {
            is Stmt.ExprStmt -> s.copy(expr = expr(s.expr))
            is Stmt.Return -> s.copy(value = s.value?.let { expr(it) })
            is Stmt.VarDecl -> s.copy(type = typeAnn(s.type), initializer = expr(s.initializer))
            is Stmt.FinDecl -> s.copy(type = typeAnn(s.type), initializer = expr(s.initializer))
            is Stmt.LetDecl -> s.copy(type = typeAnn(s.type), initializer = expr(s.initializer))
            is Stmt.Assignment -> s.copy(value = expr(s.value))
            is Stmt.MemberAssign -> s.copy(target = expr(s.target), value = expr(s.value))
            is Stmt.If -> s.copy(condition = expr(s.condition), thenBranch = stmts(s.thenBranch), elseBranch = s.elseBranch?.let { stmts(it) })
            is Stmt.While -> s.copy(condition = expr(s.condition), body = stmts(s.body))
            is Stmt.For -> s.copy(iterable = expr(s.iterable), body = stmts(s.body))
            is Stmt.Loop -> s.copy(body = stmts(s.body))
            is Stmt.Scope -> s.copy(body = stmts(s.body))
            is Stmt.When -> s.copy(
                scrutinee = expr(s.scrutinee),
                branches = s.branches.map { b -> b.copy(patterns = b.patterns.map { expr(it) }, body = stmts(b.body)) },
                elseBranch = s.elseBranch?.let { stmts(it) },
            )
            else -> s
        }

        private fun typeAnn(ann: TypeAnnotation): TypeAnnotation =
            if (ann is TypeAnnotation.Explicit) TypeAnnotation.Explicit(typeRef(ann.ref)) else ann

        /** The decorator [name] written on this parameter, or null. */
        private fun annotationOn(name: String?): Annotation? =
            param.annotations.firstOrNull { it.name == name }

        /**
         * The decorator [name] as this parameter sees it, or null when no such
         * decorator is declared at all.
         *
         * A parameter that does not carry it still answers, with the decorator's
         * own defaults: `hasAnnot` is what says whether it was written, and the
         * branch that reads `annotMeta` under a false `hasAnnot` is dead code
         * that must still be expandable - it is expanded before anything knows
         * the condition is false.
         */
        private fun applied(name: String?): DecoratorMetadata.Applied? =
            program.items.filterIsInstance<TopLevel.Deco>().firstOrNull { it.name == name }
                ?.let { DecoratorMetadata.Applied(it, annotationOn(name)) }

        /** Whether [e] is a reflect query about this parameter. */
        private fun about(e: Expr?, callee: String): Expr.Call? =
            (e as? Expr.Call)?.takeIf {
                it.callee == callee && ((it.args.singleOrNull() as? Expr.Identifier)?.name == from)
            }

        fun expr(e: Expr): Expr = when (e) {
            // `P.annotMeta<D>.field` - what the parameter's decorator was given.
            is Expr.Member if about(e.target, "__annotMeta") != null -> {
                val query = e.target as Expr.Call
                applied(query.typeArgs.singleOrNull()?.displayName())
                    ?.let { DecoratorMetadata.fieldValue(it, e.name) } ?: e
            }
            is Expr.Member -> if (onParam(e.target)) member(e) else e.copy(target = expr(e.target))
            // `P.hasAnnot<D>` - `x: @Query T` is how a parameter says what it is,
            // and this is how the caller reads it back.
            is Expr.Call if about(e, "__hasAnnot") != null ->
                Expr.BoolLiteral(annotationOn(e.typeArgs.singleOrNull()?.displayName()) != null, e.line, e.column)
            is Expr.Identifier -> e
            is Expr.Call -> {
                val named = base(type)
                val callee = if (named != null && e.callee.startsWith("${from}__")) {
                    named.name + e.callee.substring(from.length)
                } else {
                    e.callee
                }
                // The applied arguments travel with the call, so
                // `Rows<Shape>::provide` is specialized for `Shape` and not for
                // whatever `Rows`' parameter would otherwise erase to.
                val typeArgs = if (callee != e.callee && e.typeArgs.isEmpty()) named?.args.orEmpty() else e.typeArgs.map { typeRef(it) }
                e.copy(callee = callee, args = e.args.map { expr(it) }, typeArgs = typeArgs, receiver = e.receiver?.let { expr(it) })
            }
            is Expr.MethodCall -> e.copy(target = expr(e.target), args = e.args.map { expr(it) })
            is Expr.Index -> e.copy(target = expr(e.target), index = expr(e.index))
            is Expr.Binary -> e.copy(left = expr(e.left), right = expr(e.right))
            is Expr.Unary -> e.copy(operand = expr(e.operand))
            is Expr.Grouping -> e.copy(expr = expr(e.expr))
            is Expr.Cast -> e.copy(expr = expr(e.expr), targetType = typeRef(e.targetType))
            is Expr.StringTemplate -> e.copy(parts = e.parts.map { part ->
                if (part is Expr.StringTemplatePart.Expr) Expr.StringTemplatePart.Expr(expr(part.expr)) else part
            })
            is Expr.ArrayLiteral -> e.copy(elements = e.elements.map { expr(it) })
            else -> e
        }

        /**
         * One member of the bound parameter, folded to what it stands for.
         *
         * Everything here is decided at expansion: a `ReflectedParam` is not a
         * value and never reaches the generated program, so a member with no
         * compile-time answer would have no runtime one either.
         */
        private fun member(e: Expr.Member): Expr = when (e.name) {
            "name" -> Expr.StringLiteral(param.name, e.line, e.column)
            "hasDefault" -> Expr.BoolLiteral(param.defaultValue != null, e.line, e.column)
            "isNullable" -> Expr.BoolLiteral(param.type is TypeRef.Nullable, e.line, e.column)
            "defaultValue" -> param.defaultValue ?: Expr.NullLiteral
            "isVararg" -> Expr.BoolLiteral(param.variadic, e.line, e.column)
            "mutability" -> Expr.Member(
                Expr.Identifier("std__ParamMutability", e.line, e.column, 18),
                when (param.modifier) {
                    ParamModifier.SHARED -> "Shared"
                    ParamModifier.EXCLUSIVE -> "Exclusive"
                    else -> "Value"
                },
                e.line,
                e.column,
                e.length,
            )
            // `ActualType` is folded in type position, where a type can be
            // written; as an expression there is nothing it could evaluate to.
            else -> e
        }
    }
}
