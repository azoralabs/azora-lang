/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.semantic

import org.azora.lang.frontend.Program
import org.azora.lang.frontend.TopLevel
import org.azora.lang.ir.IrType
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
    /**
     * An implementation's target declares its parameters - `impl Show for
     * Box<T>`, `impl Box<T>` - so a name written there that is a type
     * (`impl Show for Box<Int>`, `impl ButtonInput<KeyCode>`) would declare a
     * parameter *called* `Int` that hides the type in every member (and that
     * [align] would rename to the pack's own), and the implementation would
     * hold for every `Box`. One implementation per instantiation is not
     * supported - generics are erased (GENERICS_DIP §21) and a member is
     * dispatched by its type alone - so it is refused, before anything is
     * renamed. A type written with structure there (`Box<Array<T>>`) is
     * refused by the parser.
     */
    fun typesWrittenAsParameters(program: Program): List<String> {
        val declared = HashSet<String>()
        // A library's packs by module: the unit's own types are not in scope
        // in a library's impl, and the library is checked in full where it is
        // compiled itself (std by its self-test, a package by `azora build`).
        val packsByModule = HashMap<String?, MutableSet<String>>()
        val packParams = HashMap<String, List<String>>()
        for (item in program.items) {
            val name = when (item) {
                is TopLevel.Pack -> {
                    packsByModule.getOrPut(item.declaringModule) { HashSet() }.add(item.name.substringAfterLast("__"))
                    packParams[item.name] = item.typeParams
                    item.name
                }
                is TopLevel.Slot -> item.name
                is TopLevel.Enum -> item.name
                is TopLevel.Fail -> item.name
                is TopLevel.Spec -> item.name
                is TopLevel.TypeAlias -> item.name
                else -> null
            }
            name?.let { declared.add(it.substringAfterLast("__")) }
        }
        val errors = LinkedHashSet<String>()
        for (item in program.items) {
            if (item !is TopLevel.Impl) continue
            val own = item.declaringModule == null || item.declaringModule == program.moduleName
            fun isType(name: String) = IrType.isPrimitiveName(name) || name in BUILTIN_TYPES ||
                (if (own) name in declared else packsByModule[item.declaringModule]?.contains(name) == true)
            val targetParams = packParams[item.typeName].orEmpty()
            for ((index, name) in item.typeParams.withIndex()) {
                // The target's own parameter, as its declaration names it
                // (`impl Integer for Int<N>` for `pack Int<N: …>`), is one.
                if (targetParams.getOrNull(index) == name) continue
                if (!isType(name)) continue
                val owner = item.typeName.substringAfterLast('.')
                val target = "$owner<${item.typeParams.joinToString(", ")}>"
                val head = item.traitName?.let { "impl ${it.substringAfterLast("__")} for $target" } ?: "impl $target"
                val suggested = (targetParams.getOrNull(index)?.let { listOf(it) }.orEmpty() + SUGGESTED)
                    .firstOrNull { it !in item.typeParams && !isType(it) } ?: "P"
                errors.add(
                    "line ${item.line}: '$head': '$name' is a type, but an implementation's target declares " +
                        "parameters, so this declares one called '$name' that hides it and implements every " +
                        "'$owner'; write a parameter ('$suggested') - an implementation for one instantiation " +
                        "is not supported, since generics are erased",
                )
            }
        }
        return errors.toList()
    }

    private val BUILTIN_TYPES = setOf("Array", "Tuple", "Task")
    private val SUGGESTED = listOf("T", "U", "V", "W", "X", "Y", "Z")

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
