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

package org.azora.lang.stdlib

import org.azora.lang.frontend.Annotation
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.MemberCallStyle
import org.azora.lang.frontend.ModuleVisibility
import org.azora.lang.frontend.Param
import org.azora.lang.frontend.ParamModifier
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.frontend.Visibility
import org.azora.lang.ir.sourceSymbol
import org.azora.lang.semantic.StabilityKind
import org.azora.lang.semantic.StabilityMarks

/**
 * The stable surface of the standard library (STABILITY_DIP §2): every
 * declaration visible outside its module and not `@Experimental`, with its
 * signature.
 *
 * The listing is checked into the repository (`std/STABLE_SURFACE.txt`) and a
 * test compares it with what the sources declare, so a change to what 0.1
 * promises shows up in review as a change to that file.
 *
 * Read from the sources as parsed - no analysis - because what is promised is
 * what is written: a declaration's name, its parameters and their borrows,
 * its result and its errors.
 */
object StdSurface {

    /** One `.az` source of the library: its path below `std/` and its text. */
    data class Source(val path: String, val text: String)

    const val HEADER = """# The stable surface of Azora's standard library (STABILITY_DIP §2).
#
# Every declaration visible outside its module and not @Experimental, with its
# signature. Generated from std/ by StdSurfaceTest; do not edit by hand.
# Regenerate after an intended change:
#   AZORA_REGENERATE_SURFACE=1 ./gradlew :compiler:desktopTest --tests '*StdSurfaceTest*'
"""

    fun render(sources: List<Source>): String = survey(sources).listing

    /**
     * Stable declarations whose signatures name an experimental type: a promise
     * that cannot be kept, since the type it hands out may change. Each is
     * `module: line - names experimental 'Type'`.
     */
    fun contradictions(sources: List<Source>): List<String> = survey(sources).contradictions

    private class Survey(val listing: String, val contradictions: List<String>)

    private fun survey(sources: List<Source>): Survey {
        // Parsed in path order against one compile-time environment, as the
        // loader parses the library: a list one module binds (`Numbers`) is
        // iterated by another's `inline for`.
        val lists = mutableMapOf<String, List<String>>()
        val enums = mutableMapOf<String, List<String>>()
        val listScopes = mutableMapOf<String, String>()
        val parsed = sources.sortedBy { it.path }.map {
            it to Parser(Lexer(it.text).tokenize(), lists, enums, typeListScope = listScopes).parse()
        }
        // What a declaration's type or spec says decides its members' fate
        // wherever they are written, so it is read from every module first.
        val experimentalTypes = mutableSetOf<String>()
        for ((_, program) in parsed) {
            forEachDeclaration(program.items) { item ->
                val (name, annotations) = when (item) {
                    is TopLevel.Pack -> item.name to item.annotations
                    is TopLevel.Enum -> item.name to item.annotations
                    is TopLevel.Fail -> item.name to item.annotations
                    is TopLevel.Spec -> item.name to item.annotations
                    is TopLevel.Slot -> item.name to item.annotations
                    is TopLevel.Solo -> item.name to item.annotations
                    is TopLevel.TypeAlias -> item.name to item.annotations
                    else -> return@forEachDeclaration
                }
                if (StabilityMarks.declares(annotations, StabilityKind.EXPERIMENTAL)) experimentalTypes += name
            }
        }
        val contradictions = mutableListOf<String>()
        val listing = buildString {
            append(HEADER)
            for ((source, program) in parsed) {
                val module = Module(experimentalTypes)
                val lines = module.lines(program)
                contradictions += module.contradictions.map { "${program.moduleName ?: source.path}: $it" }
                if (lines.isEmpty()) continue
                append('\n')
                append(program.moduleName ?: source.path).append("  (").append(source.path).append(")\n")
                lines.forEach { append("  ").append(it).append('\n') }
            }
        }
        return Survey(listing, contradictions)
    }

    private fun forEachDeclaration(items: List<TopLevel>, action: (TopLevel) -> Unit) {
        for (item in items) {
            when (item) {
                is TopLevel.InlineBlock -> forEachDeclaration(item.body, action)
                is TopLevel.DeepInlineBlock -> forEachDeclaration(item.body, action)
                is TopLevel.InlineIf -> {
                    forEachDeclaration(item.thenBranch, action)
                    item.elseBranch?.let { forEachDeclaration(it, action) }
                }
                is TopLevel.DeepInlineIf -> {
                    forEachDeclaration(item.thenBranch, action)
                    item.elseBranch?.let { forEachDeclaration(it, action) }
                }
                else -> action(item)
            }
        }
    }

    private class Module(private val experimentalTypes: Set<String>) {
        private val out = mutableListOf<String>()
        val contradictions = mutableListOf<String>()
        private val mentioned = mutableSetOf<String>()
        private val experimentalTails = experimentalTypes.mapTo(mutableSetOf()) { sourceSymbol(it).substringAfterLast("::") }
        private val liftedWithImpl = mutableSetOf<String>()
        private var hiddenByTests: Set<String> = emptySet()

        fun lines(program: Program): List<String> {
            if (program.moduleVisibility == ModuleVisibility.CONFINED) return emptyList()
            hiddenByTests = program.testScopeMembers.keys
            // A receiver-free member of a spec implementation is kept in the impl
            // and also lifted to its type; it is listed once, with the impl.
            forEachDeclaration(program.items) { item ->
                if (item is TopLevel.Impl && item.traitName != null) {
                    item.methods.filter { !it.declaresReceiver }.forEach { liftedWithImpl += "${item.typeName}__${it.name}" }
                }
            }
            forEachDeclaration(program.items) { item -> if (declaredName(item) !in hiddenByTests) declaration(item) }
            return out
        }

        private fun declaredName(item: TopLevel): String? = when (item) {
            is TopLevel.Func -> item.decl.name
            is TopLevel.FinDecl -> item.name
            is TopLevel.VarDecl -> item.name
            is TopLevel.LetDecl -> item.name
            is TopLevel.Pack -> item.name
            is TopLevel.Enum -> item.name
            is TopLevel.Fail -> item.name
            is TopLevel.Spec -> item.name
            is TopLevel.Slot -> item.name
            is TopLevel.Solo -> item.name
            is TopLevel.TypeAlias -> item.name
            is TopLevel.Deco -> item.name
            else -> null
        }

        /** Visible outside its module: neither `_private`, compiler-internal `__name`, nor narrowed. */
        private fun visible(name: String, visibility: Visibility = Visibility.PUBLIC): Boolean {
            val tail = name.substringAfterLast("__").ifEmpty { name }
            return !name.startsWith("_") && !tail.startsWith("_") && visibility.reach == Visibility.Reach.PUBLIC
        }

        private fun stable(annotations: List<Annotation>): Boolean =
            !StabilityMarks.declares(annotations, StabilityKind.EXPERIMENTAL)

        private fun emit(text: String, annotations: List<Annotation>) {
            line(text + marks(annotations))
        }

        /** A listed line: a stable declaration that names an experimental type is a contradiction. */
        private fun line(text: String) {
            out += text
            val named = mentioned.filter { it in experimentalTails }
            if (named.isNotEmpty()) {
                contradictions += "${text.trim()} - names experimental ${named.sorted().joinToString(", ") { "'$it'" }}"
            }
            mentioned.clear()
        }

        /** A type as written, noting the declarations it names. */
        private fun t(ref: TypeRef): String {
            names(ref)
            return type(ref)
        }

        private fun names(ref: TypeRef) {
            when (ref) {
                is TypeRef.Named -> { mentioned += sourceSymbol(ref.name).substringAfterLast("::"); ref.args.forEach(::names) }
                is TypeRef.Array -> names(ref.element)
                is TypeRef.Map -> { names(ref.key); names(ref.value) }
                is TypeRef.Set -> names(ref.element)
                is TypeRef.Function -> { ref.receivers.forEach(::names); ref.params.forEach(::names); names(ref.ret) }
                is TypeRef.Tuple -> ref.elements.forEach(::names)
                is TypeRef.Nullable -> names(ref.inner)
                is TypeRef.Failable -> { names(ref.ok); ref.errSets.forEach { mentioned += sourceSymbol(it).substringAfterLast("::") } }
                is TypeRef.Pointer -> names(ref.inner)
                is TypeRef.Reference -> names(ref.inner)
                is TypeRef.Const -> {}
            }
        }

        private fun declaration(item: TopLevel) {
            when (item) {
                is TopLevel.Func -> {
                    val decl = item.decl
                    if (!visible(decl.name, decl.visibility) || !stable(decl.annotations)) return
                    if (decl.name in liftedWithImpl) return
                    // A member lifted out of its type keeps that type's fate.
                    val owner = decl.name.substringBeforeLast("__", "")
                    if (owner.isNotEmpty() && owner in experimentalTypes) return
                    emit(function(decl, sourceSymbol(decl.name)), decl.annotations)
                }
                is TopLevel.FinDecl -> value("fin", item.name, item.type, item.annotations, item.visibility)
                is TopLevel.VarDecl -> value(if (item.valueMutable) "var" else "val", item.name, item.type, item.annotations, item.visibility)
                is TopLevel.LetDecl -> value("let", item.name, item.type, item.annotations, item.visibility)
                is TopLevel.Bridge -> {
                    if (!stable(item.annotations)) return
                    for (sig in item.funcs) {
                        val name = sig.localName ?: sig.name
                        if (!visible(name)) continue
                        emit(
                            "bridge func${typeParams(sig.typeParams)} ${sourceSymbol(name)}" +
                                "(${sig.params.joinToString(", ") { param(it) }}): ${t(sig.returnType)}",
                            item.annotations,
                        )
                    }
                    for (value in item.values) {
                        if (!visible(value.name)) continue
                        val keyword = if (value.mutable) "var" else if (value.isLet) "let" else "fin"
                        emit("bridge $keyword ${sourceSymbol(value.name)}: ${t(value.type)}", item.annotations)
                    }
                }
                is TopLevel.Pack -> {
                    if (!visible(item.name, item.visibility) || !stable(item.annotations)) return
                    val kind = when {
                        item.isUnion -> "union"
                        item.isBridge -> "bridge pack"
                        else -> "pack"
                    }
                    emit("$kind ${sourceSymbol(item.name)}${typeParams(item.typeParams)}", item.annotations)
                    for (field in item.fields) {
                        if (!visible(field.name, field.visibility) || !stable(field.annotations)) continue
                        val keyword = if (field.mutable) "var" else "fin"
                        line("  $keyword ${field.name}: ${t(field.type)}" +
                            (if (field.default != null) " = ${expr(field.default)}" else "") + marks(field.annotations))
                    }
                }
                is TopLevel.Solo -> {
                    if (!visible(item.name, item.visibility) || !stable(item.annotations)) return
                    emit("solo pack ${sourceSymbol(item.name)}", item.annotations)
                    for (field in item.fields) {
                        if (!visible(field.name, field.visibility) || !stable(field.annotations)) continue
                        line("  ${if (field.mutable) "var" else "fin"} ${field.name}: ${t(field.type)}" + marks(field.annotations))
                    }
                    item.methods.forEach { member(it, emptyList()) }
                }
                is TopLevel.Enum -> {
                    if (!visible(item.name) || !stable(item.annotations)) return
                    val cases = item.variants.indices
                        .filter { stable(item.variantAnnotations.getOrNull(it).orEmpty()) }
                        .joinToString(", ") { item.variants[it] + marks(item.variantAnnotations.getOrNull(it).orEmpty()) }
                    emit("enum ${sourceSymbol(item.name)}${typeParams(item.typeParams)} { $cases }", item.annotations)
                }
                is TopLevel.Fail -> {
                    if (!visible(item.name) || !stable(item.annotations)) return
                    val cases = item.variants.indices
                        .filter { stable(item.variantAnnotations.getOrNull(it).orEmpty()) }
                        .joinToString(", ") { i ->
                            val payload = item.variantPayloads.getOrNull(i).orEmpty()
                            item.variants[i] +
                                (if (payload.isEmpty()) "" else payload.joinToString(", ", "(", ")") { t(it) }) +
                                marks(item.variantAnnotations.getOrNull(i).orEmpty())
                        }
                    emit("error ${sourceSymbol(item.name)}${typeParams(item.typeParams)} { $cases }", item.annotations)
                }
                is TopLevel.Slot -> {
                    if (!visible(item.name) || !stable(item.annotations)) return
                    val cases = item.variants.joinToString(", ") { v ->
                        v.name + if (v.payloadTypes.isEmpty()) "" else v.payloadTypes.joinToString(", ", "(", ")") { t(it) }
                    }
                    emit(
                        "${if (item.isError) "variant error" else "variant enum"} ${sourceSymbol(item.name)}${typeParams(item.typeParams)} { $cases }",
                        item.annotations,
                    )
                }
                is TopLevel.TypeAlias -> {
                    if (!visible(item.name) || !stable(item.annotations)) return
                    emit("typealias ${sourceSymbol(item.name)}${typeParams(item.typeParams)} = ${t(item.type)}", item.annotations)
                }
                is TopLevel.Deco -> {
                    if (!visible(item.name) || !stable(item.annotations)) return
                    val fields = item.fields.filter { visible(it.name, it.visibility) }
                        .joinToString(", ") { "${it.name}: ${t(it.type)}" }
                    emit(
                        "${if (item.isBridge) "bridge annot" else "annot"} @${sourceSymbol(item.name)}" +
                            if (fields.isEmpty()) "" else " { $fields }",
                        item.annotations,
                    )
                }
                is TopLevel.Spec -> {
                    if (!visible(item.name) || !stable(item.annotations)) return
                    val kind = when {
                        item.isBridge -> "bridge spec"
                        item.isDirect -> "direct spec"
                        else -> "spec"
                    }
                    val parents = (item.parents + item.requires).takeIf { it.isNotEmpty() }
                        ?.joinToString(", ", " : ") { t(it) }.orEmpty()
                    emit("$kind ${sourceSymbol(item.name)}${typeParams(item.typeParams)}$parents", item.annotations)
                    item.methods.forEach { member(it, item.annotations) }
                }
                is TopLevel.Impl -> {
                    if (!stable(item.annotations)) return
                    // `derive D for Pack::field` / `Pack::*` targets a member; the
                    // pack decides whether it is part of the surface.
                    val target = item.typeName.substringBefore('.')
                    if (target in experimentalTypes || item.traitName in experimentalTypes) return
                    if (!visible(target) || target in hiddenByTests) return
                    val members = item.methods.filter { visible(it.name, it.visibility) && stable(it.annotations) }
                    val head = buildString {
                        append("impl ")
                        item.traitName?.let {
                            append(sourceSymbol(it))
                            if (item.traitArgs.isNotEmpty()) append(item.traitArgs.joinToString(", ", "<", ">") { t(it) })
                            append(" for ")
                        }
                        append(sourceSymbol(item.typeName))
                        append(typeParams(item.typeParams))
                    }
                    // A conformance is part of the surface even with no member of
                    // its own: `impl Hash for Int` is what lets an `Int` key a map.
                    if (members.isEmpty() && item.traitName == null) return
                    emit(head, item.annotations)
                    members.forEach { member(it, item.annotations) }
                }
                else -> {}
            }
        }

        private fun value(keyword: String, name: String, type: TypeRef?, annotations: List<Annotation>, visibility: Visibility) {
            if (!visible(name, visibility) || !stable(annotations)) return
            emit("$keyword ${sourceSymbol(name)}" + (type?.let { ": ${t(it)}" } ?: ""), annotations)
        }

        private fun member(decl: FuncDecl, block: List<Annotation>) {
            if (!visible(decl.name, decl.visibility) || !stable(decl.annotations)) return
            line("  " + function(decl, decl.name, member = true) + marks(decl.annotations))
        }

        private fun function(decl: FuncDecl, name: String, member: Boolean = false): String = buildString {
            if (decl.isDeepInline) append("deepinline ") else if (decl.isInline) append("inline ")
            if (decl.isReactive) append("react ")
            if (decl.isTask) append("async ")
            if (decl.isUnsafe) append("unsafe ")
            val property = decl.memberCallStyle == MemberCallStyle.PROPERTY ||
                decl.memberCallStyle == MemberCallStyle.STATIC_PROPERTY
            val lifecycle = decl.name == "ctor" || decl.name == "dtor"
            when {
                lifecycle -> append(decl.name)
                property -> append("prop")
                else -> append("func")
            }
            append(typeParams(decl.typeParams))
            append(' ')
            if (lifecycle) {
                // `ctor .(…)` / `dtor .()`: the receiver is the value being built
                // or destroyed, written without a borrow.
                append(".")
                append(decl.params.joinToString(", ", "(", ")") { param(it) })
                (decl.returnType as? TypeAnnotation.Explicit)?.let { append(": ").append(t(it.ref)) }
                decl.whereClause?.let { append(" where ").append(expr(it)) }
                return@buildString
            }
            if (member && decl.declaresReceiver) {
                append(
                    when (decl.receiverModifier) {
                        ParamModifier.SHARED -> "&."
                        ParamModifier.EXCLUSIVE -> "!."
                        ParamModifier.NONE -> "."
                    },
                )
            }
            append(name)
            if (!property) append(decl.params.joinToString(", ", "(", ")") { param(it) })
            (decl.returnType as? TypeAnnotation.Explicit)?.let { append(": ").append(t(it.ref)) }
            decl.whereClause?.let { append(" where ").append(expr(it)) }
        }

        private fun param(p: Param): String = buildString {
            if (p.variadic) append("...")
            append(p.name).append(": ")
            if (p.returnsOwnership) append("return ")
            append(t(p.type))
            if (p.type !is TypeRef.Reference) {
                when (p.modifier) {
                    ParamModifier.SHARED -> append('&')
                    ParamModifier.EXCLUSIVE -> append('!')
                    ParamModifier.NONE -> {}
                }
            }
            p.defaultValue?.let { append(" = ").append(expr(it)) }
        }

        private fun typeParams(names: List<String>): String =
            if (names.isEmpty()) "" else names.joinToString(", ", "<", ">")

        /** A declaration's stability decorators, as written, other than the `@Experimental` it cannot have here. */
        private fun marks(annotations: List<Annotation>): String {
            val shown = annotations.filter {
                it.name == StabilityMarks.STABLE || it.name == StabilityMarks.SINCE || it.name == StabilityMarks.DEPRECATED
            }
            if (shown.isEmpty()) return ""
            return shown.joinToString(" ", "  ") { a ->
                val args = a.args.map(::expr) + a.namedArgs.map { (k, v) -> "$k: ${expr(v)}" }
                "@${a.name}" + if (args.isEmpty()) "" else args.joinToString(", ", "(", ")")
            }
        }
    }

    /** A type as it is written. */
    fun type(ref: TypeRef): String = when (ref) {
        is TypeRef.Named -> buildString {
            if (ref.variadic) append("...")
            append(ref.qualifier?.let { "$it::" }.orEmpty())
            append(sourceSymbol(ref.name))
            val args = ref.args.map(::type) + ref.valueArgs.map(::expr)
            if (args.isNotEmpty()) append(args.joinToString(", ", "<", ">"))
        }
        is TypeRef.Array -> "[${type(ref.element)}]"
        is TypeRef.Map -> "map[${type(ref.key)}, ${type(ref.value)}]"
        is TypeRef.Set -> "set[${type(ref.element)}]"
        is TypeRef.Function -> buildString {
            if (ref.isEscaping) append("escaping ")
            if (ref.isInline) append("inline ")
            val kind = ref.kind.surfaceName
            if (kind.isNotEmpty() && ref.kind != org.azora.lang.frontend.CallableKind.FUNC) append(kind).append(' ')
            when (ref.receivers.size) {
                0 -> {}
                1 -> append(type(ref.receivers.single())).append('.')
                else -> append(ref.receivers.joinToString(", ", "(", ").") { type(it) })
            }
            append(ref.params.joinToString(", ", "(", ")") { type(it) })
            append(" -> ").append(type(ref.ret))
        }
        is TypeRef.Tuple -> ref.elements.joinToString(", ", "(", ")") { type(it) }
        is TypeRef.Nullable -> "${type(ref.inner)}?"
        is TypeRef.Failable -> "${type(ref.ok)} ?! " +
            if (ref.errSets.size == 1) sourceSymbol(ref.errSets.single())
            else ref.errSets.joinToString(", ", "[", "]") { sourceSymbol(it) }
        is TypeRef.Pointer -> type(ref.inner) + if (ref.mutable) "^" else "*"
        is TypeRef.Const -> ref.toString()
        is TypeRef.Reference -> type(ref.inner) + (if (ref.kind == TypeRef.RefKind.MUTABLE) "!" else "&") +
            if (ref.origins.isEmpty()) "" else ref.origins.joinToString(", ", "|", "|")
    }

    /**
     * A default value, a `where` clause or a decorator argument, as written -
     * for the forms such places hold. Anything else is shown as `…`: that a
     * default exists is part of the surface even where its value is not shown.
     */
    fun expr(e: Expr): String = written(e).let { if ("…" in it) "…" else it }

    private fun written(e: Expr): String = when (e) {
        is Expr.IntLiteral -> e.text ?: e.value.toString()
        is Expr.DoubleLiteral -> e.text ?: e.value.toString()
        is Expr.BoolLiteral -> e.value.toString()
        is Expr.StringLiteral -> "\"" + e.value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Expr.CharLiteral -> "'" + (if (e.value in 32..126) e.value.toChar().toString() else "\\u{${e.value.toString(16)}}") + "'"
        Expr.NullLiteral -> "null"
        is Expr.Identifier -> sourceSymbol(e.name)
        is Expr.InferredMember -> "." + e.name + (e.ctorArgs?.joinToString(", ", "(", ")") { written(it) } ?: "")
        is Expr.Member -> "${written(e.target)}.${e.name}"
        is Expr.Grouping -> "(${written(e.expr)})"
        is Expr.Unary -> (symbol(e.op) ?: "…") + written(e.operand)
        is Expr.Binary -> symbol(e.op)?.let { "${written(e.left)} $it ${written(e.right)}" } ?: "…"
        is Expr.Call -> sourceSymbol(e.callee) +
            (if (e.typeArgs.isEmpty()) "" else e.typeArgs.joinToString(", ", "<", ">") { type(it) }) +
            e.args.joinToString(", ", "(", ")") { written(it) }
        is Expr.NamedArg -> "${e.name}: ${written(e.value)}"
        is Expr.ArrayLiteral -> e.elements.joinToString(", ", "[", "]") { written(it) }
        else -> "…"
    }

    private fun symbol(op: org.azora.lang.frontend.TokenType): String? = when (op.name) {
        "PLUS" -> "+"
        "MINUS" -> "-"
        "STAR" -> "*"
        "SLASH" -> "/"
        "PERCENT" -> "%"
        "EQUAL_EQUAL" -> "=="
        "BANG_EQUAL" -> "!="
        "LESS" -> "<"
        "LESS_EQUAL" -> "<="
        "GREATER" -> ">"
        "GREATER_EQUAL" -> ">="
        "AND", "AMP_AMP" -> "&&"
        "OR", "PIPE_PIPE" -> "||"
        "BANG", "NOT" -> "!"
        "COLON" -> ":"
        "SHIFT_LEFT" -> "<<"
        "SHIFT_RIGHT" -> ">>"
        "AMP" -> "&"
        "PIPE" -> "|"
        "CARET" -> "^"
        "TILDE" -> "~"
        else -> null
    }
}
