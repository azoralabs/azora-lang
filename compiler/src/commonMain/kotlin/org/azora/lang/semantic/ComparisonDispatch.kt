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

import org.azora.lang.frontend.TokenType

/**
 * How a comparison operator on a user type is answered.
 *
 * `DIPs/OPERATOR_OVERLOADING_DIP.MD` §5.4 and §5.5: a type states its ordering
 * once, as `<=>`, and its equality once, as `==`. The four relational operators
 * and `!=` are then *rewrites* rather than members, so a type never declares
 * six things that must agree.
 *
 * This lives in one place because the resolver and the lowerer both have to
 * make the same decision, and two copies of it would be the bug that has not
 * happened yet — the existing `operOverloadName` tables in `TypeResolver` and
 * `IrGenerator` are already such a pair.
 */
internal sealed class ComparisonPlan {
    /** The type declares this operator; call it. An explicit member always wins. */
    data class Direct(val mangled: String) : ComparisonPlan()

    /**
     * Call `<=>` and ask its result. [predicate] is a property of `Compare` or
     * `PartialCompare` — `isLess`, `isLessOrEqual`, `isGreater`,
     * `isGreaterOrEqual` — whose meaning differs between the two enums, which is
     * exactly why the choice is made by naming a member rather than by emitting
     * a comparison here.
     *
     * The `<=>` call is the receiver, so it is evaluated once.
     */
    data class Spaceship(val mangled: String, val predicate: String) : ComparisonPlan()

    /** `a != b` where only `==` is declared: `!(a == b)`. */
    data class NegatedEquals(val mangled: String) : ComparisonPlan()
}

/** The `Compare`/`PartialCompare` property that answers [op]. */
private fun relationalPredicate(op: TokenType): String? = when (op) {
    TokenType.LESS -> "isLess"
    TokenType.LESS_EQUAL -> "isLessOrEqual"
    TokenType.GREATER -> "isGreater"
    TokenType.GREATER_EQUAL -> "isGreaterOrEqual"
    else -> null
}

/**
 * What `left OP right` should do, or null when nothing in this family applies
 * and the caller's own rules (primitives, pointers, the built-in numeric path)
 * still stand.
 *
 * [typeName] is the left operand's nominal type and [operandKey] the right
 * operand's overload key.
 */
internal fun comparisonPlan(
    op: TokenType,
    typeName: String,
    operandKey: String?,
    table: SymbolTable,
): ComparisonPlan? {
    val direct = operOverloadMemberName(op)?.let { table.lookupOperator(typeName, it, operandKey) }
    if (direct != null) return ComparisonPlan.Direct(direct)

    // `!=` from `==`.
    if (op == TokenType.BANG_EQUAL) {
        val eq = table.lookupOperator(typeName, "oper==", operandKey) ?: return null
        return ComparisonPlan.NegatedEquals(eq)
    }

    // `<`, `<=`, `>`, `>=` from `<=>`.
    val predicate = relationalPredicate(op) ?: return null
    val spaceship = table.lookupOperator(typeName, "oper<=>", operandKey) ?: return null
    return ComparisonPlan.Spaceship(spaceship, predicate)
}

/**
 * The member a *unary* operator is declared under.
 *
 * `-x` and `x - y` share a symbol and are told apart by operand count, which
 * the overload suffix already encodes: `oper- [self: Self&]` registers as
 * `oper-` and `oper- [self: Self&](rhs: Vec2&)` as `oper-@Vec2`. So the name is
 * the same and the lookup passes no operand key.
 */
internal fun unaryOverloadName(op: TokenType): String? = when (op) {
    TokenType.MINUS -> "oper-"
    TokenType.BANG -> "oper!"
    TokenType.TILDE -> "oper~"
    else -> null
}

/** The member name [op] is declared under, for the operators that have one. */
internal fun operOverloadMemberName(op: TokenType): String? = when (op) {
    TokenType.PLUS -> "oper+"
    TokenType.MINUS -> "oper-"
    TokenType.STAR -> "oper*"
    TokenType.SLASH -> "oper/"
    TokenType.PERCENT -> "oper%"
    TokenType.EQUAL_EQUAL -> "oper=="
    TokenType.BANG_EQUAL -> "oper!="
    TokenType.LESS -> "oper<"
    TokenType.LESS_EQUAL -> "oper<="
    TokenType.GREATER -> "oper>"
    TokenType.GREATER_EQUAL -> "oper>="
    TokenType.SPACESHIP -> "oper<=>"
    TokenType.TILDE -> "oper~"
    TokenType.AMP -> "oper&"
    TokenType.PIPE -> "oper|"
    TokenType.CARET -> "oper^"
    TokenType.SHIFT_LEFT -> "oper<<"
    TokenType.SHIFT_RIGHT -> "oper>>"
    else -> null
}
