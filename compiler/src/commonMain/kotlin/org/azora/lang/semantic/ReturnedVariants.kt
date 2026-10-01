/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.semantic

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef

/**
 * Decides what `return .Variant` means in a function that declares `?!`.
 *
 * ERROR_HANDLING_DIP: `return .Variant` fails the function with that variant
 * of its error set. The parser cannot see which sets declare which variants,
 * so it wrote every such return as a throw - and a variant of the *success*
 * type was thrown too. `parseObject(): SerialValue ?! SerializationError`
 * returning `.Object(fields)` failed with an error set that has no `Object`,
 * and `return .Nothing` from a `Value ?! ParseError` function failed when it
 * meant to succeed.
 *
 * Here the program is complete, so the variant is looked up:
 *
 * - a variant of the declared error set fails the function, as before;
 * - otherwise a variant of the success type is that value, returned;
 * - a name both declare, or neither does, is an error rather than a guess
 *   (neither is reported only where every declared error set is known).
 */
internal object ReturnedVariants {
    data class Result(val program: Program, val errors: List<String>)

    fun resolve(program: Program): Result {
        val errorVariants = HashMap<String, Set<String>>()
        val valueVariants = HashMap<String, Set<String>>()
        for (item in program.items) {
            when (item) {
                is TopLevel.Fail -> errorVariants[item.name] = item.variants.toSet()
                is TopLevel.Enum -> valueVariants[item.name] = item.variants.toSet()
                is TopLevel.Slot -> if (!item.isError) valueVariants[item.name] = item.variants.map { it.name }.toSet()
                else -> {}
            }
        }
        if (valueVariants.isEmpty()) return Result(program, emptyList())
        val errors = mutableListOf<String>()
        fun func(decl: FuncDecl, self: String?): FuncDecl {
            val failable = (decl.returnType as? TypeAnnotation.Explicit)?.ref as? TypeRef.Failable ?: return decl
            val ok = (failable.ok as? TypeRef.Named)?.name?.let { if (it == "Self" && self != null) self else it }
            val context = Context(failable.errSets, ok, errorVariants, valueVariants[ok].orEmpty(), errors)
            return decl.copy(body = context.body(decl.body))
        }
        val items = program.items.map { item ->
            when (item) {
                is TopLevel.Func -> item.copy(decl = func(item.decl, null))
                is TopLevel.Impl -> item.copy(methods = item.methods.map { func(it, item.typeName.substringBefore('<')) })
                is TopLevel.Spec -> item.copy(methods = item.methods.map { func(it, null) })
                else -> item
            }
        }
        return Result(program.copy(items = items), errors)
    }

    private class Context(
        val sets: List<String>,
        val ok: String?,
        val errorVariants: Map<String, Set<String>>,
        val okVariants: Set<String>,
        val errors: MutableList<String>,
    ) {
        fun body(stmts: List<Stmt>): List<Stmt> = stmts.map(::stmt)

        private fun stmt(s: Stmt): Stmt = when (s) {
            is Stmt.Throw -> if (s.returned) returned(s) else s
            is Stmt.If -> s.copy(thenBranch = body(s.thenBranch), elseBranch = s.elseBranch?.let(::body))
            is Stmt.While -> s.copy(body = body(s.body))
            is Stmt.For -> s.copy(body = body(s.body))
            is Stmt.Loop -> s.copy(body = body(s.body))
            is Stmt.When -> s.copy(
                branches = s.branches.map { it.copy(body = body(it.body)) },
                elseBranch = s.elseBranch?.let(::body),
            )
            is Stmt.Try -> s.copy(body = body(s.body), catchBody = s.catchBody?.let(::body))
            is Stmt.Scope -> s.copy(body = body(s.body))
            is Stmt.Defer -> s.copy(body = body(s.body))
            is Stmt.UsingContext -> s.copy(body = body(s.body))
            else -> s
        }

        private fun returned(s: Stmt.Throw): Stmt {
            val (variant, args) = when (val value = s.value) {
                is Expr.StringLiteral -> value.value to null
                is Expr.MethodCall -> value.name to value.args
                else -> return s
            }
            val inError = sets.any { variant in errorVariants[it].orEmpty() }
            val inOk = variant in okVariants
            if (inError && inOk) {
                errors.add(
                    "line ${s.line}: 'return .$variant' is ambiguous - both '$ok' and " +
                        "'${sets.joinToString(", ")}' have '$variant'; write '$ok.$variant' to return it " +
                        "or '${sets.first()}.$variant' to fail with it",
                )
                return s
            }
            if (!inError && !inOk && sets.all { it in errorVariants }) {
                val alsoOk = if (okVariants.isNotEmpty()) " or '$ok'" else ""
                errors.add(
                    "line ${s.line}: 'return .$variant' names no variant of " +
                        "'${sets.joinToString(", ")}'$alsoOk",
                )
                return s
            }
            if (inError || !inOk || ok == null) return s
            val owner = Expr.Identifier(ok, s.line, s.column, ok.length)
            val value = if (args == null) {
                Expr.Member(owner, variant, s.line, s.column)
            } else {
                Expr.MethodCall(owner, variant, args, s.line, s.column)
            }
            return Stmt.Return(value, s.line, s.column)
        }
    }
}
