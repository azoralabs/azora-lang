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
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.frontend.TopLevel

/**
 * Gives an implementation the spec members it did not write.
 *
 * A spec member with a body is *provided*: every implementation would write it
 * identically, so it is written once with the spec instead. An implementation
 * that supplies its own keeps it - that is what overriding is - and one that
 * does not gets a copy of the spec's, which from here on is an ordinary member
 * and needs nothing else to know it was inherited.
 *
 * Copied rather than dispatched to, because a spec is not a value: there is
 * nothing at runtime to hold the shared body, and the type that has the member
 * is the one that must carry it.
 */
object SpecDefaults {

    /**
     * An inherited member with the implementation's associated types filled in.
     *
     * The spec wrote `Item`; this implementation said what `Item` is. The copy
     * belongs to the type, so it says so too - nothing downstream has to know
     * the name was ever associated with anything.
     */
    private fun bindAssoc(method: FuncDecl, bindings: Map<String, TypeRef>): FuncDecl {
        if (bindings.isEmpty()) return method
        fun bind(ref: TypeRef): TypeRef = when (ref) {
            is TypeRef.Named -> bindings[ref.name] ?: ref.copy(args = ref.args.map(::bind))
            is TypeRef.Array -> ref.copy(element = bind(ref.element))
            is TypeRef.Nullable -> ref.copy(inner = bind(ref.inner))
            is TypeRef.Reference -> ref.copy(inner = bind(ref.inner))
            // `body: (Item) -> Unit` - a callable's own types are as much the
            // member's signature as its parameters are.
            is TypeRef.Function -> ref.copy(
                params = ref.params.map(::bind),
                ret = bind(ref.ret),
                receivers = ref.receivers.map(::bind),
            )
            is TypeRef.Failable -> ref.copy(ok = bind(ref.ok))
            is TypeRef.Pointer -> ref.copy(inner = bind(ref.inner))
            else -> ref
        }
        fun bindAnnotation(annotation: TypeAnnotation): TypeAnnotation =
            if (annotation is TypeAnnotation.Explicit) TypeAnnotation.Explicit(bind(annotation.ref)) else annotation
        // A provided body names types too - `store<Self>(…)` is the whole reason
        // one spec member can serve every implementation - so the body is bound
        // on the same terms as the signature.
        fun bindExpr(expr: Expr): Expr = when (expr) {
            is Expr.Call -> expr.copy(
                args = expr.args.map(::bindExpr),
                typeArgs = expr.typeArgs.map(::bind),
                receiver = expr.receiver?.let(::bindExpr),
            )
            is Expr.MethodCall -> expr.copy(target = bindExpr(expr.target), args = expr.args.map(::bindExpr))
            is Expr.Member -> expr.copy(target = bindExpr(expr.target))
            is Expr.Binary -> expr.copy(left = bindExpr(expr.left), right = bindExpr(expr.right))
            is Expr.Unary -> expr.copy(operand = bindExpr(expr.operand))
            is Expr.Grouping -> expr.copy(expr = bindExpr(expr.expr))
            is Expr.NamedArg -> expr.copy(value = bindExpr(expr.value))
            else -> expr
        }

        fun bindStmt(stmt: Stmt): Stmt = when (stmt) {
            is Stmt.FinDecl -> stmt.copy(type = bindAnnotation(stmt.type), initializer = bindExpr(stmt.initializer))
            is Stmt.VarDecl -> stmt.copy(type = bindAnnotation(stmt.type), initializer = bindExpr(stmt.initializer))
            is Stmt.ExprStmt -> stmt.copy(expr = bindExpr(stmt.expr))
            is Stmt.Return -> stmt.copy(value = stmt.value?.let(::bindExpr))
            else -> stmt
        }

        return method.copy(
            params = method.params.map { it.copy(type = bind(it.type)) },
            returnType = bindAnnotation(method.returnType),
            body = method.body.map(::bindStmt),
        )
    }

    fun apply(program: Program): Program {
        val declared = program.items.filterIsInstance<TopLevel.Spec>()
            .filter { spec -> spec.methods.any { it.body.isNotEmpty() } }
        if (declared.isEmpty()) return program
        // Keyed by the plain name as well as the mangled one: an implementation
        // names the spec as its source wrote it (`Iterator`), and injection
        // may have given the declaration a scope-qualified name.
        val specs = mutableMapOf<String, TopLevel.Spec>()
        for (spec in declared) {
            specs[spec.name] = spec
            specs[spec.name.substringAfterLast("__")] = spec
        }

        return program.copy(
            items = program.items.map { item ->
                val impl = item as? TopLevel.Impl ?: return@map item
                val spec = impl.traitName
                    ?.let { specs[it] ?: specs[it.substringAfterLast("__")] }
                    ?: return@map item
                val own = impl.methods.mapTo(mutableSetOf()) { it.name }
                val inherited = spec.methods
                    .filter { it.body.isNotEmpty() && it.name !in own }
                    // `Self` in a provided body is this implementation's type -
                    // that is what makes one body serve every implementation.
                    .map { bindAssoc(it, impl.assocBindings + ("Self" to TypeRef.Named(impl.typeName))) }
                if (inherited.isEmpty()) item else impl.copy(methods = impl.methods + inherited)
            },
        )
    }
}
