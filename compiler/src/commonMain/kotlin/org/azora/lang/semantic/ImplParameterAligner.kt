/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.semantic

import org.azora.lang.frontend.Program
import org.azora.lang.frontend.TopLevel
import org.azora.lang.stdlib.DeclarationRenamer

/**
 * Gives an implementation its type's own parameter names.
 *
 * `impl Box<U>` and the extension `func<U> Box<U>&.peek(): U` name the
 * parameter `pack Box<T>` declared as `U`. A member is typed against the
 * declaration's parameters and instantiated from a receiver's arguments by
 * those names, so `U` must become `T` before symbol collection - otherwise a
 * call on a `Box<Int>` answers an unknown `U`.
 *
 * Only an inherent implementation of a pack is renamed, and only when every
 * position lines up and no member declares a parameter of its own that the
 * renaming would capture.
 */
internal object ImplParameterAligner {
    fun align(program: Program): Program {
        val packs = program.items.filterIsInstance<TopLevel.Pack>().associateBy { it.name }
        var changed = false
        val items = program.items.map { item ->
            if (item !is TopLevel.Impl || item.traitName != null || item.variadicParam != null) return@map item
            if (item.typeParams.isEmpty()) return@map item
            val declared = packs[item.typeName]?.typeParams ?: return@map item
            if (declared.size != item.typeParams.size || declared == item.typeParams) return@map item
            val renames = item.typeParams.zip(declared).filter { (written, own) -> written != own }.toMap()
            val captured = declared.toSet() + renames.keys
            if (item.methods.any { method -> method.typeParams.any { it in captured } }) return@map item
            // Without its parameters in scope the renamer treats them as free
            // names, which is exactly what is being renamed here.
            val renamed = DeclarationRenamer(renames).item(item.copy(typeParams = emptyList())) as TopLevel.Impl
            changed = true
            renamed.copy(typeParams = declared)
        }
        return if (changed) program.copy(items = items) else program
    }
}
