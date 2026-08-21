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

package org.azora.lang.frontend

/**
 * What `@Supress(kind: …)` silences, and where.
 *
 * The decorator is declared in `std/core.az` as `bridge annot @Supress for .*`
 * with a single `kind: SupressKind` field, so it can be written above anything
 * - including the `module` header, which answers for the whole unit.
 *
 * Two rules, and only two:
 *
 *  - On a declaration it answers for that declaration and for what is written
 *    inside it. `@Supress(kind: .Unused)` above a `func` covers the function's
 *    own name and its locals.
 *  - On the `module` header it answers for every declaration the module makes,
 *    but not for bindings inside a `func` or `prop` body. A module-wide sweep
 *    is for names the module publishes; a local nobody reads is still the
 *    author's own business, and the function that wants it quiet says so.
 */
object Suppressions {

    /** The decorator's name, as `std/core.az` spells it. */
    const val DECORATOR = "Supress"

    /** The `SupressKind` case that silences "is never used". */
    const val UNUSED = "Unused"

    /** Whether [annotations] carry `@Supress(kind: .`[kind]`)`. */
    fun suppresses(annotations: List<Annotation>, kind: String): Boolean =
        annotations.any { it.name == DECORATOR && kinds(it).contains(kind) }

    /** Whether [annotations] silence "is never used". */
    fun suppressesUnused(annotations: List<Annotation>): Boolean = suppresses(annotations, UNUSED)

    /**
     * The `SupressKind` cases one application names.
     *
     * `kind:` is the only field, so a positional argument means the same thing
     * as the named one, and `.Unused` is read as the case it is - the leading
     * dot is how a case is written where its type is already known.
     */
    private fun kinds(annotation: Annotation): Set<String> {
        val arguments = annotation.args + annotation.namedArgs.map { it.second }
        return arguments.mapNotNullTo(mutableSetOf(), ::caseName)
    }

    private fun caseName(expr: Expr): String? = when (expr) {
        is Expr.Identifier -> expr.name.substringAfterLast('.')
        is Expr.Member -> expr.name
        else -> null
    }
}
