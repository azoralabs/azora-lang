/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.semantic

import org.azora.lang.frontend.Expr

/**
 * A `when` pattern that destructures a variant's payload.
 *
 * Two spellings name one: `Value.Flag(v)` writes the variant's type, and
 * `.Flag(v)` leaves it to the scrutinee, as `.Nothing` already did for a
 * variant without a payload. The resolver and the IR generator both read
 * patterns through this, so neither binds a payload the other ignores.
 */
internal object SlotPatterns {
    data class Parts(val slot: String, val variant: String, val bindings: List<Expr>)

    /**
     * The slot, variant and binding expressions [pattern] names, or null when
     * it does not destructure. [scrutineeSlot] is the slot the scrutinee holds,
     * which is what a `.Variant(…)` pattern belongs to.
     */
    fun parts(pattern: Expr, scrutineeSlot: String?): Parts? = when {
        pattern is Expr.MethodCall && pattern.target is Expr.Identifier ->
            Parts((pattern.target as Expr.Identifier).name, pattern.name, pattern.args)
        pattern is Expr.InferredMember && pattern.name.isNotEmpty() && pattern.ctorArgs != null &&
            scrutineeSlot != null -> Parts(scrutineeSlot, pattern.name, pattern.ctorArgs)
        else -> null
    }
}
