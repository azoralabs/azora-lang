/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.semantic

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TokenType
import org.azora.lang.ir.IrType

/**
 * Where a condition proves an optional binding non-null (TYPE_DIP §2.1).
 *
 * A binding whose name cannot be rebound - a `fin` or `let` local, or a
 * parameter that is not an exclusive borrow - and holds a `T?` reads as `T`
 * where a test has shown it is not null: the `then` side of `x != null`, the
 * `else` side of `x == null`, the right operand of `x != null && …` and of
 * `x == null || …`, a `while` body, and the rest of a block after an `if`
 * whose other branch cannot fall through (`return`, `throw`, `break`,
 * `continue`). A `var` or `val` never narrows: its name can be rebound, so
 * anything between the test and the read could set it back to null. A
 * lambda's body starts with nothing narrowed.
 *
 * The resolver and the IR generator both apply this, at the same places, so
 * every read the resolver types as `T` is a read the IR unwraps. A narrowing
 * is tied to the binding it was proven for, so a nested declaration of the
 * same name is not narrowed.
 */
internal object OptionalNarrowing {
    /** The names [condition] proves non-null when it evaluates to [outcome]. */
    fun proven(condition: Expr, outcome: Boolean): List<String> = when (condition) {
        is Expr.Grouping -> proven(condition.expr, outcome)
        is Expr.Unary -> if (condition.op == TokenType.BANG) proven(condition.operand, !outcome) else emptyList()
        is Expr.Binary -> when (condition.op) {
            TokenType.AND_AND -> if (outcome) proven(condition.left, true) + proven(condition.right, true) else emptyList()
            TokenType.OR_OR -> if (!outcome) proven(condition.left, false) + proven(condition.right, false) else emptyList()
            TokenType.BANG_EQUAL -> if (outcome) listOfNotNull(nullTested(condition)) else emptyList()
            TokenType.EQUAL_EQUAL -> if (!outcome) listOfNotNull(nullTested(condition)) else emptyList()
            else -> emptyList()
        }
        else -> emptyList()
    }

    /** The outcome of [logical]'s left operand under which its right operand runs, or null for any other operator. */
    fun rightOperandOutcome(logical: Expr.Binary): Boolean? = when (logical.op) {
        TokenType.AND_AND -> true
        TokenType.OR_OR -> false
        else -> null
    }

    /** The binding `x == null` or `null != x` tests, or null. */
    private fun nullTested(test: Expr.Binary): String? {
        fun name(expr: Expr): String? = when (expr) {
            is Expr.Grouping -> name(expr.expr)
            is Expr.Identifier -> expr.name
            else -> null
        }
        return when {
            test.right is Expr.NullLiteral -> name(test.left)
            test.left is Expr.NullLiteral -> name(test.right)
            else -> null
        }
    }

    /** Whether running [body] never reaches the statement after it. */
    fun exits(body: List<Stmt>): Boolean = when (val last = body.lastOrNull()) {
        is Stmt.Return, is Stmt.Throw, is Stmt.Break, is Stmt.Continue -> true
        is Stmt.If -> last.elseBranch != null && exits(last.thenBranch) && exits(last.elseBranch)
        else -> false
    }

    /** Whether [symbol] can be narrowed: it cannot be rebound, has plain storage, and holds an optional. */
    fun narrowable(symbol: VariableSymbol?): Boolean =
        symbol != null && !symbol.mutable && !symbol.hasStorageEffects && symbol.type is IrType.Nullable
}
