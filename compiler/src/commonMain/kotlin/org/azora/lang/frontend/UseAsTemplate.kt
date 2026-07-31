package org.azora.lang.frontend

/**
 * Expands a compact spec `use as` member-name template.
 *
 * The template text is ordinary text. `${T}`, `${T.typeName}`, and `${T.name}`
 * refer to the type argument bound to spec type parameter `T`. The expanded
 * type name is normalized for generated member identifiers, so
 * `"to${T.typeName}"` with `T = String` becomes `toString`.
 */
internal object UseAsTemplate {
    fun expand(template: String, typeParams: List<String>, traitArgs: List<TypeRef>): String {
        if ('$' !in template) return template
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch == '$' && i + 1 < template.length && template[i + 1] == '{') {
                val end = findPlaceholderEnd(template, i + 2)
                    ?: throw IllegalStateException("Unclosed placeholder in spec 'use as' template: $template")
                out.append(resolvePlaceholder(template.substring(i + 2, end).trim(), typeParams, traitArgs))
                i = end + 1
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    fun typeMemberSuffix(type: TypeRef): String {
        val raw = when (type) {
            is TypeRef.Named -> type.name
            else -> type.displayName()
        }.filter { it.isLetterOrDigit() }
        val cleaned = raw.ifEmpty { "Value" }
        return cleaned[0].uppercaseChar() + cleaned.drop(1)
    }

    private fun findPlaceholderEnd(template: String, start: Int): Int? {
        var i = start
        while (i < template.length) {
            if (template[i] == '}') return i
            i++
        }
        return null
    }

    private fun resolvePlaceholder(expr: String, typeParams: List<String>, traitArgs: List<TypeRef>): String {
        val parts = expr.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            throw IllegalStateException("Empty placeholder in spec 'use as' template")
        }
        val param = parts.first()
        val index = typeParams.indexOf(param)
        if (index < 0) {
            throw IllegalStateException("Unknown type parameter '$param' in spec 'use as' template")
        }
        val type = traitArgs.getOrNull(index) ?: TypeRef.Named("Value")
        if (parts.size == 1) return typeMemberSuffix(type)
        return when (val property = parts.drop(1).joinToString(".")) {
            "name", "typeName" -> typeMemberSuffix(type)
            "rawName", "displayName" -> type.displayName()
            else -> throw IllegalStateException("Unknown type template property '$property' in spec 'use as' template")
        }
    }
}
