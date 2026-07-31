package org.azora.lang.frontend

/**
 * Rewrites compact callback spec impls after all files/libraries have been
 * assembled into one [Program].
 *
 * This is intentionally a whole-program step: an `impl Into<String> for X {
 * ref self -> ... }` can live in a different file from `spec Into<T> ... use
 * as ...`, so parser-local state is not enough to produce the final member
 * name reliably.
 */
object CallbackImplNormalizer {
    fun normalize(program: Program): Program {
        val callbacks = program.items.filterIsInstance<TopLevel.Spec>()
            .mapNotNull { spec -> spec.callback?.let { spec.name to it } }
            .toMap()
        if (callbacks.isEmpty()) return program
        return program.copy(items = program.items.map { normalizeItem(it, callbacks) })
    }

    private fun normalizeItem(item: TopLevel, callbacks: Map<String, SpecCallback>): TopLevel {
        val impl = item as? TopLevel.Impl ?: return item
        val traitName = impl.traitName ?: return item
        val callback = callbacks[traitName] ?: return item
        if (impl.methods.size != 1) return item
        val method = impl.methods.single()
        return impl.copy(
            methods = listOf(
                method.copy(
                    name = callbackMethodName(traitName, impl.traitArgs, callback),
                    params = callback.params.map { param ->
                        param.copy(type = substituteType(param.type, callback.typeParams, impl.traitArgs))
                    },
                    returnType = TypeAnnotation.Explicit(substituteType(callback.returnType, callback.typeParams, impl.traitArgs)),
                    memberCallStyle = if (callback.requiresParens) MemberCallStyle.METHOD else MemberCallStyle.PROPERTY,
                )
            )
        )
    }

    private fun callbackMethodName(traitName: String, traitArgs: List<TypeRef>, callback: SpecCallback): String {
        callback.useAsTemplate?.let { return UseAsTemplate.expand(it, callback.typeParams, traitArgs) }
        return if (traitName.isEmpty()) "callback" else traitName[0].lowercaseChar() + traitName.drop(1)
    }

    private fun substituteType(type: TypeRef, typeParams: List<String>, traitArgs: List<TypeRef>): TypeRef {
        val named = type as? TypeRef.Named ?: return type
        val index = typeParams.indexOf(named.name)
        return if (index >= 0) traitArgs.getOrElse(index) { named } else named
    }
}
