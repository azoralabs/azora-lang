/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.semantic

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.TokenType

/**
 * A `for` header's step as its source wrote it, for the messages about it:
 * `'by 0.5' is a Float, …` at compile time and `the step 'by zero' does not
 * move the range forward` at run time (RANGE_DIP §6). Literals, names,
 * negation, member reads and calls are spelled out; anything else is `…`.
 */
internal fun stepText(expr: Expr): String = when (expr) {
    is Expr.IntLiteral -> expr.magnitude ?: expr.text ?: expr.value.toString()
    is Expr.DoubleLiteral -> expr.text ?: expr.value.toString()
    is Expr.Identifier -> expr.name.substringAfterLast("__")
    is Expr.Grouping -> "(${stepText(expr.expr)})"
    is Expr.Unary -> if (expr.op == TokenType.MINUS) "-${stepText(expr.operand)}" else "…"
    is Expr.Member -> "${stepText(expr.target)}.${expr.name}"
    is Expr.Call -> "${expr.callee.substringAfterLast("__")}(${expr.args.joinToString(", ") { stepText(it) }})"
    else -> "…"
}
