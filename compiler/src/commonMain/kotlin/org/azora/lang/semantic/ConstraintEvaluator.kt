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
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TypeRef

/**
 * Decides whether a declaration's `where` clause holds for one set of type
 * arguments.
 *
 * A clause is an ordinary expression — `T is Number && N in 2..4` is an `&&` over
 * an [Expr.IsCheck] and an [Expr.InCheck] — so this walks the same tree the parser
 * produced rather than a constraint language of its own. Anything the evaluator
 * cannot decide is reported as [Outcome.Unknown] and treated as satisfied by
 * callers: a constraint that cannot be evaluated must not reject valid code.
 *
 * Two rules are fixed by design:
 *  - `T is Spec` is **nominal**: it holds only where an `impl Spec for T` is
 *    declared. Structural satisfaction does not count.
 *  - Evaluation happens once per monomorphised combination, not per instantiation
 *    site, so [bindings] name concrete arguments and never other parameters.
 */
internal object ConstraintEvaluator {

    /** What a clause evaluated to, and why when it failed. */
    sealed class Outcome {
        object Satisfied : Outcome()

        /** [reason] names the conjunct that failed, for the diagnostic. */
        data class Violated(val reason: String) : Outcome()

        /** The clause used something not yet evaluable; callers accept it. */
        data class Unknown(val what: String) : Outcome()
    }

    /**
     * One type argument, as bound for a monomorphised combination.
     *
     * A type parameter binds to a type name; a const parameter (`N: Int`) binds to
     * an integer.
     */
    sealed class Binding {
        data class Type(val name: String) : Binding()
        data class Const(val value: Long) : Binding()

        /** A variadic pack, bound to how many arguments it received. */
        data class Pack(val length: Long) : Binding()
    }

    /**
     * Evaluates [clause] under [bindings].
     *
     * Returns [Outcome.Satisfied] when there is no clause at all.
     */
    fun evaluate(
        clause: Expr?,
        bindings: Map<String, Binding>,
        table: SymbolTable?,
    ): Outcome {
        if (clause == null) return Outcome.Satisfied
        return eval(clause, bindings, table)
    }

    /** Converts a [TypeRef] argument into the binding it stands for. */
    fun bindingOf(arg: TypeRef): Binding? = when (arg) {
        is TypeRef.Const -> Binding.Const(arg.value)
        is TypeRef.Named -> Binding.Type(arg.name)
        else -> null
    }

    private fun eval(expr: Expr, env: Map<String, Binding>, table: SymbolTable?): Outcome = when (expr) {
        is Expr.Grouping -> eval(expr.expr, env, table)

        // `T is Number` — nominal conformance: an `impl Number for T` must exist.
        is Expr.IsCheck -> {
            val subject = nameOf(expr.expr)
            val bound = subject?.let { env[it] }
            when {
                bound == null -> Outcome.Unknown("'${subject ?: "expression"} is ${expr.typeName}'")
                // A pack bound only by its length says nothing about its elements'
                // conformance, and a const is not a type at all. Neither is decidable
                // here, so neither rejects.
                // TODO: element-wise conformance for a variadic pack.
                bound !is Binding.Type ->
                    Outcome.Unknown("'$subject is ${expr.typeName}' for a non-type binding")
                table == null -> Outcome.Unknown("'$subject is ${expr.typeName}' without a symbol table")
                table.conformsTo(bound.name, expr.typeName) -> Outcome.Satisfied
                else -> Outcome.Violated(
                    "'$subject is ${expr.typeName}': ${bound.name} does not implement ${expr.typeName}",
                )
            }
        }

        // `N in 2..4` — membership. Only an integer subject over a literal range is
        // decidable here; a list or set collection is left to the caller.
        // TODO: evaluate membership in array/set literal collections.
        is Expr.InCheck -> {
            val subject = constOf(expr.value, env)
            val range = rangeOf(expr.collection, env)
            when {
                subject == null || range == null -> Outcome.Unknown("membership constraint")
                (subject in range) != expr.negated -> Outcome.Satisfied
                else -> Outcome.Violated(
                    "'${render(expr.value)} in ${range.first}..${range.last}': " +
                        "${render(expr.value)} is $subject",
                )
            }
        }

        is Expr.Unary -> when (expr.op) {
            TokenType.BANG -> when (val inner = eval(expr.operand, env, table)) {
                is Outcome.Satisfied -> Outcome.Violated("negated constraint held")
                is Outcome.Violated -> Outcome.Satisfied
                is Outcome.Unknown -> inner
            }
            // TODO: other unary operators in constraint position.
            else -> Outcome.Unknown("unary ${expr.op}")
        }

        is Expr.Binary -> when (expr.op) {
            TokenType.AND_AND -> both(expr, env, table, requireAll = true)
            TokenType.OR_OR -> both(expr, env, table, requireAll = false)
            else -> compare(expr, env)
        }

        // TODO: calls, member access other than the variadic `.length` reading, and
        // any other expression a clause might legitimately contain.
        else -> Outcome.Unknown(render(expr))
    }

    /** `&&` needs both sides; `||` needs either. Unknown on one side is not fatal. */
    private fun both(
        expr: Expr.Binary,
        env: Map<String, Binding>,
        table: SymbolTable?,
        requireAll: Boolean,
    ): Outcome {
        val left = eval(expr.left, env, table)
        val right = eval(expr.right, env, table)
        val sides = listOf(left, right)
        return if (requireAll) {
            sides.firstOrNull { it is Outcome.Violated }
                ?: sides.firstOrNull { it is Outcome.Unknown }
                ?: Outcome.Satisfied
        } else {
            when {
                sides.any { it is Outcome.Satisfied } -> Outcome.Satisfied
                sides.any { it is Outcome.Unknown } -> sides.first { it is Outcome.Unknown }
                else -> Outcome.Violated(
                    sides.filterIsInstance<Outcome.Violated>().joinToString(" and ") { it.reason },
                )
            }
        }
    }

    /** A comparison between two integers, e.g. `N == 4` or `(...T).length >= 2`. */
    private fun compare(expr: Expr.Binary, env: Map<String, Binding>): Outcome {
        val left = constOf(expr.left, env) ?: return Outcome.Unknown(render(expr))
        val right = constOf(expr.right, env) ?: return Outcome.Unknown(render(expr))
        val holds = when (expr.op) {
            TokenType.EQUAL_EQUAL -> left == right
            TokenType.BANG_EQUAL -> left != right
            TokenType.LESS -> left < right
            TokenType.LESS_EQUAL -> left <= right
            TokenType.GREATER -> left > right
            TokenType.GREATER_EQUAL -> left >= right
            else -> return Outcome.Unknown("comparison ${expr.op}")
        }
        return if (holds) {
            Outcome.Satisfied
        } else {
            Outcome.Violated("'${render(expr.left)} ${spell(expr.op)} ${render(expr.right)}': $left vs $right")
        }
    }

    /** The integer an expression denotes, or null when it is not a known constant. */
    private fun constOf(expr: Expr, env: Map<String, Binding>): Long? = when (expr) {
        is Expr.IntLiteral -> expr.value.toString().toLongOrNull()
        is Expr.Grouping -> constOf(expr.expr, env)
        is Expr.Identifier -> (env[expr.name] as? Binding.Const)?.value
        // `(...T).length` — the pack's argument count, bound per combination.
        is Expr.Member ->
            if (expr.name == "length") {
                (nameOf(expr.target)?.let { env[it] } as? Binding.Pack)?.length
            } else {
                null
            }
        else -> null
    }

    /** The inclusive bounds of a literal range expression, or null. */
    private fun rangeOf(expr: Expr, env: Map<String, Binding>): LongRange? {
        val range = expr as? Expr.Range ?: return null
        val from = constOf(range.from, env) ?: return null
        val to = constOf(range.to, env) ?: return null
        return if (range.inclusive) from..to else from until to
    }

    /** The identifier an expression names, seeing through `(...)` grouping. */
    private fun nameOf(expr: Expr): String? = when (expr) {
        is Expr.Identifier -> expr.name
        is Expr.Grouping -> nameOf(expr.expr)
        else -> null
    }

    private fun spell(op: TokenType): String = when (op) {
        TokenType.EQUAL_EQUAL -> "=="
        TokenType.BANG_EQUAL -> "!="
        TokenType.LESS -> "<"
        TokenType.LESS_EQUAL -> "<="
        TokenType.GREATER -> ">"
        TokenType.GREATER_EQUAL -> ">="
        else -> op.toString()
    }

    /** A short rendering of a constraint fragment, for diagnostics only. */
    private fun render(expr: Expr): String = when (expr) {
        is Expr.Identifier -> expr.name
        is Expr.IntLiteral -> expr.value.toString()
        is Expr.Grouping -> render(expr.expr)
        is Expr.Member -> "${render(expr.target)}.${expr.name}"
        else -> "constraint"
    }
}
