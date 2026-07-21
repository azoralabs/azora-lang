/*
 * Copyright 2026 AzoraLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.azora.lang.frontend

/** Replaces free uses of a trace body's implicit `it` receiver. */
internal fun Expr.bindTraceReceiver(receiver: Expr): Expr = bindTraceReceiver(receiver, interpolation = false)

private fun Expr.bindTraceReceiver(receiver: Expr, interpolation: Boolean): Expr = when (this) {
    is Expr.Identifier -> if (name == "it") {
        if (interpolation) Expr.Cast(receiver, TypeRef.Named("String"), line = line, column = column)
        else receiver
    } else this
    is Expr.IntLiteral, is Expr.RealLiteral, is Expr.CharLiteral,
    is Expr.StringLiteral, is Expr.BoolLiteral, is Expr.NullLiteral,
    is Expr.UpperScopeAccess, is Expr.Inject -> this
    is Expr.Binary -> copy(left = left.bindTraceReceiver(receiver, interpolation), right = right.bindTraceReceiver(receiver, interpolation))
    is Expr.Unary -> copy(operand = operand.bindTraceReceiver(receiver, interpolation))
    is Expr.Call -> copy(args = args.map { it.bindTraceReceiver(receiver, interpolation) })
    is Expr.Grouping -> copy(expr = expr.bindTraceReceiver(receiver, interpolation))
    is Expr.Range -> copy(from = from.bindTraceReceiver(receiver, interpolation), to = to.bindTraceReceiver(receiver, interpolation))
    is Expr.ArrayLiteral -> copy(elements = elements.map { it.bindTraceReceiver(receiver, interpolation) })
    is Expr.SetLiteral -> copy(elements = elements.map { it.bindTraceReceiver(receiver, interpolation) })
    is Expr.Index -> copy(target = target.bindTraceReceiver(receiver, interpolation), index = index.bindTraceReceiver(receiver, interpolation))
    is Expr.Member -> copy(target = target.bindTraceReceiver(receiver, interpolation))
    is Expr.MethodCall -> copy(
        target = target.bindTraceReceiver(receiver, interpolation),
        args = args.map { it.bindTraceReceiver(receiver, interpolation) },
    )
    is Expr.StringTemplate -> copy(parts = parts.map { part ->
        if (part is Expr.StringTemplatePart.Expr) {
            Expr.StringTemplatePart.Expr(part.expr.bindTraceReceiver(receiver, interpolation = true))
        } else part
    })
    is Expr.TupleLit -> copy(elements = elements.map { it.bindTraceReceiver(receiver, interpolation) })
    is Expr.VariantLit -> copy(elements = elements.map { it.bindTraceReceiver(receiver, interpolation) })
    is Expr.TupleAccess -> copy(target = target.bindTraceReceiver(receiver, interpolation))
    is Expr.CatchExpr -> copy(
        expr = expr.bindTraceReceiver(receiver, interpolation),
        fallback = fallback.bindTraceReceiver(receiver, interpolation),
    )
    is Expr.TryPropagate -> copy(expr = expr.bindTraceReceiver(receiver, interpolation))
    is Expr.IfExpr -> copy(
        condition = condition.bindTraceReceiver(receiver, interpolation),
        thenExpr = thenExpr.bindTraceReceiver(receiver, interpolation),
        elseExpr = elseExpr.bindTraceReceiver(receiver, interpolation),
    )
    // A nested lambda owns its own implicit receiver.
    is Expr.Lambda -> this
    is Expr.NamedArg -> copy(value = value.bindTraceReceiver(receiver, interpolation))
    is Expr.NullCoalesce -> copy(
        left = left.bindTraceReceiver(receiver, interpolation),
        right = right.bindTraceReceiver(receiver, interpolation),
    )
    is Expr.SafeMember -> copy(target = target.bindTraceReceiver(receiver, interpolation))
    is Expr.Cast -> copy(expr = expr.bindTraceReceiver(receiver, interpolation))
    is Expr.IsCheck -> copy(expr = expr.bindTraceReceiver(receiver, interpolation))
    is Expr.MapLit -> copy(entries = entries.map { (key, value) ->
        key.bindTraceReceiver(receiver, interpolation) to value.bindTraceReceiver(receiver, interpolation)
    })
    is Expr.Alloc -> copy(value = value.bindTraceReceiver(receiver, interpolation))
    is Expr.AllocBuffer -> copy(count = count.bindTraceReceiver(receiver, interpolation))
    is Expr.Deref -> copy(target = target.bindTraceReceiver(receiver, interpolation))
    is Expr.Isolated -> copy(value = value.bindTraceReceiver(receiver, interpolation))
    is Expr.Await -> copy(value = value.bindTraceReceiver(receiver, interpolation))
    is Expr.Spread -> copy(array = array.bindTraceReceiver(receiver, interpolation))
    is Expr.MetaInvoke -> copy(args = args.map { it.bindTraceReceiver(receiver, interpolation) })
    is Expr.Slice -> copy(
        target = target.bindTraceReceiver(receiver, interpolation),
        start = start?.bindTraceReceiver(receiver, interpolation),
        stop = stop?.bindTraceReceiver(receiver, interpolation),
        step = step?.bindTraceReceiver(receiver, interpolation),
    )
}
