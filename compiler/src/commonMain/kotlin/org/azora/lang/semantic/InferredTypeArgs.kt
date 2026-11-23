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
 * Fills in the type argument a nested call did not write.
 *
 * `storageInsert<Clickable>(store(composition), …)` states what the outer call
 * builds, and the parameter it fills says `Storage<T>`. That is enough to know
 * the inner `store` is `store<Clickable>` - the outer call has already said so,
 * and writing it again says nothing new.
 *
 * Done before the compile-time loop rather than during type resolution, because
 * a type argument is what an `inline` body substitutes and what `T.typeName`
 * reads. By the time types are resolved the substitution has happened, and an
 * unwritten argument has already become the literal name `T`.
 *
 * Only an omitted list is filled: a call that wrote its own type arguments is
 * left exactly as it is.
 */
object InferredTypeArgs {

    fun apply(program: Program): Program {
        val generic = mutableMapOf<String, FuncDecl>()
        // Members by their own name: `target.name<T>(…)` does not say whose member
        // it is before types are resolved, so every generic member of that name
        // is a candidate, and they must agree on what each argument is.
        val members = mutableMapOf<String, MutableList<FuncDecl>>()
        fun remember(decl: FuncDecl) {
            if (decl.typeParams.isEmpty()) return
            generic[decl.name] = decl
            // A library's declaration carries its scope; a call names it plainly.
            // getOrPut, not putIfAbsent: the latter is a JVM-only extension and
            // this file is commonMain, so it does not resolve on Kotlin/Wasm.
            generic.getOrPut(decl.name.substringAfterLast("__")) { decl }
        }
        for (item in program.items) {
            when (item) {
                is TopLevel.Func -> remember(item.decl)
                is TopLevel.Impl -> item.methods.forEach { method ->
                    remember(method)
                    if (method.typeParams.isNotEmpty()) members.getOrPut(method.name) { mutableListOf() }.add(method)
                }
                else -> {}
            }
        }
        if (generic.isEmpty()) return program

        val rewriter = Rewriter(generic, members)
        return program.copy(
            items = program.items.map { item ->
                when (item) {
                    is TopLevel.Func -> TopLevel.Func(rewriter.decl(item.decl))
                    is TopLevel.Impl -> item.copy(methods = item.methods.map(rewriter::decl))
                    else -> item
                }
            },
        )
    }

    /**
     * Walks every statement and expression - inside `if` values, lambdas (a UI
     * builder's block), `when` arms and the rest - so a nested call is filled
     * wherever it is written.
     */
    private class Rewriter(
        private val generic: Map<String, FuncDecl>,
        private val members: Map<String, List<FuncDecl>>,
    ) : org.azora.lang.frontend.AstMapper() {

        fun decl(d: FuncDecl): FuncDecl = d.copy(body = stmts(d.body))

        override fun expr(e: Expr): Expr {
            val mapped = super.expr(e)
            return when (mapped) {
                is Expr.Call -> filled(mapped)
                is Expr.MethodCall -> filledMember(mapped)
                else -> mapped
            }
        }

        /**
         * [call] with each argument's own type arguments inferred from the ones
         * the member call wrote: `paint.topmost<Pressable>(store(pass), x, y)`
         * makes the inner call `store<Pressable>(pass)`. The receiver is not a
         * parameter here, and the members of that name that take as many type
         * arguments must expect the same thing at a position, or it is left alone.
         */
        private fun filledMember(call: Expr.MethodCall): Expr.MethodCall {
            if (call.typeArgs.isEmpty()) return call
            val candidates = members[call.name].orEmpty().filter { it.typeParams.size == call.typeArgs.size }
            if (candidates.isEmpty()) return call
            return call.copy(
                args = call.args.mapIndexed { i, argument ->
                    val expected = candidates.map { member ->
                        val stated = member.typeParams.zip(call.typeArgs).toMap()
                        member.params.getOrNull(member.contextualParams + i)?.type?.let { substitute(it, stated) }
                            ?: return@mapIndexed argument
                    }.distinct().singleOrNull() ?: return@mapIndexed argument
                    stateTypeArgs(argument, expected)
                },
            )
        }

        /** [call] with each argument's own type arguments inferred from this one's. */
        private fun filled(call: Expr.Call): Expr.Call {
            if (call.typeArgs.isEmpty()) return call
            val outer = generic[call.callee] ?: generic[call.callee.substringAfterLast("__")] ?: return call
            val stated = outer.typeParams.zip(call.typeArgs).toMap()
            if (stated.isEmpty()) return call
            return call.copy(
                args = call.args.mapIndexed { i, argument ->
                    val expected = outer.params.getOrNull(i)?.type?.let { substitute(it, stated) }
                        ?: return@mapIndexed argument
                    stateTypeArgs(argument, expected)
                },
            )
        }

        /**
         * [argument] with its type arguments stated, when [expected] settles them.
         *
         * The argument's declared result is matched against the type the position
         * expects; whatever that binds is written out in the order the callee
         * declared. A parameter left unbound means the position did not say what
         * it is, and the call is left alone for the ordinary rules to report.
         */
        private fun stateTypeArgs(argument: Expr, expected: TypeRef): Expr {
            if (argument !is Expr.Call || argument.typeArgs.isNotEmpty()) return argument
            val callee = generic[argument.callee]
                ?: generic[argument.callee.substringAfterLast("__")]
                ?: return argument
            val declared = (callee.returnType as? TypeAnnotation.Explicit)?.ref ?: return argument
            val bound = mutableMapOf<String, TypeRef>()
            if (!unify(declared, expected, callee.typeParams.toSet(), bound)) return argument
            val complete = callee.typeParams.map { bound[it] ?: return argument }
            return argument.copy(typeArgs = complete)
        }

        /** Matches [pattern] against [actual], binding any of [params] it meets. */
        private fun unify(
            pattern: TypeRef,
            actual: TypeRef,
            params: Set<String>,
            bound: MutableMap<String, TypeRef>,
        ): Boolean {
            // A borrow is how a parameter is passed, not what it is.
            if (pattern is TypeRef.Reference) return unify(pattern.inner, actual, params, bound)
            if (actual is TypeRef.Reference) return unify(pattern, actual.inner, params, bound)
            if (pattern is TypeRef.Named && pattern.name in params && pattern.args.isEmpty()) {
                val previous = bound[pattern.name]
                if (previous != null && previous != actual) return false
                bound[pattern.name] = actual
                return true
            }
            return when {
                pattern is TypeRef.Named && actual is TypeRef.Named ->
                    pattern.name == actual.name &&
                        pattern.args.size == actual.args.size &&
                        pattern.args.indices.all { unify(pattern.args[it], actual.args[it], params, bound) }
                pattern is TypeRef.Array && actual is TypeRef.Array ->
                    unify(pattern.element, actual.element, params, bound)
                pattern is TypeRef.Nullable && actual is TypeRef.Nullable ->
                    unify(pattern.inner, actual.inner, params, bound)
                pattern is TypeRef.Pointer && actual is TypeRef.Pointer ->
                    unify(pattern.inner, actual.inner, params, bound)
                else -> pattern == actual
            }
        }

        private fun substitute(ref: TypeRef, stated: Map<String, TypeRef>): TypeRef = when (ref) {
            is TypeRef.Named ->
                stated[ref.name] ?: ref.copy(args = ref.args.map { substitute(it, stated) })
            is TypeRef.Array -> ref.copy(element = substitute(ref.element, stated))
            is TypeRef.Nullable -> ref.copy(inner = substitute(ref.inner, stated))
            is TypeRef.Reference -> ref.copy(inner = substitute(ref.inner, stated))
            is TypeRef.Pointer -> ref.copy(inner = substitute(ref.inner, stated))
            is TypeRef.Function -> ref.copy(
                params = ref.params.map { substitute(it, stated) },
                ret = substitute(ref.ret, stated),
                receivers = ref.receivers.map { substitute(it, stated) },
            )
            else -> ref
        }
    }
}
