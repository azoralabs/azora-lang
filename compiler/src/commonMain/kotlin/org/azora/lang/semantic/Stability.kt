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

import org.azora.lang.frontend.Annotation
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.Param
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.Suppressions
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.ir.ctorFactorySymbol
import org.azora.lang.ir.mangleMethodSymbol
import org.azora.lang.ir.sourceSymbol

/*
 * Reporting uses of `@Deprecated` and `@Experimental` declarations
 * (STABILITY_DIP §3-§4).
 *
 * A use is found in one of two ways, because a use is written in one of two
 * ways:
 *
 *  - **By name** - a call, a construction, a type, a global, a qualified
 *    variant, a decorator. The name says which declaration it is, so these are
 *    read from the program as written ([WrittenUses]), before compile-time
 *    evaluation can fold a call away.
 *  - **Through a value** - `list.add(x)`, `point.x`, `.Red` where a `Color` is
 *    expected. Only type resolution knows the receiver's type, so the resolver
 *    reports what each member resolved to ([StabilityRecorder]).
 *
 * Only the compiled unit's own code is checked: a library's uses of its own
 * declarations are the library's business, reported when it is compiled.
 */

/** What a stability decorator says. */
enum class StabilityKind { DEPRECATED, EXPERIMENTAL }

/** One `@Deprecated` or `@Experimental` on a declaration. */
data class StabilityMark(
    val kind: StabilityKind,
    val since: String? = null,
    /** `@Deprecated(replacement: …)` - what to write instead. */
    val replacement: String? = null,
)

/** Reads the stability decorators out of a declaration's annotations. */
object StabilityMarks {
    const val DEPRECATED = "Deprecated"
    const val EXPERIMENTAL = "Experimental"
    const val STABLE = "Stable"
    const val SINCE = "Since"

    fun of(annotations: List<Annotation>): List<StabilityMark> = annotations.mapNotNull { annotation ->
        when (annotation.name) {
            DEPRECATED -> StabilityMark(
                StabilityKind.DEPRECATED,
                since = text(annotation, "since"),
                replacement = text(annotation, "replacement"),
            )
            EXPERIMENTAL -> StabilityMark(StabilityKind.EXPERIMENTAL, since = text(annotation, "since", positional = 0))
            else -> null
        }
    }

    /** Whether [annotations] declare [kind] themselves. */
    fun declares(annotations: List<Annotation>, kind: StabilityKind): Boolean = of(annotations).any { it.kind == kind }

    /**
     * Whether [annotations] answer for a use of [kind] inside them:
     * `@Supress(kind: .Deprecated)` / `.Experimental`.
     */
    fun suppresses(annotations: List<Annotation>, kind: StabilityKind): Boolean =
        Suppressions.suppresses(annotations, suppressionCase(kind))

    fun suppressionCase(kind: StabilityKind): String = when (kind) {
        StabilityKind.DEPRECATED -> DEPRECATED
        StabilityKind.EXPERIMENTAL -> EXPERIMENTAL
    }

    /**
     * The marks a member carries: its own, and those of the `impl` block it was
     * written in. A member's own `@Stable` or `@Since` says it is not
     * experimental even where its block is; nothing un-deprecates a member of a
     * deprecated block.
     */
    fun ofMember(member: List<Annotation>, block: List<Annotation>): List<StabilityMark> {
        val own = of(member)
        val inherited = of(block).filter { mark ->
            own.none { it.kind == mark.kind } &&
                !(mark.kind == StabilityKind.EXPERIMENTAL && member.any { it.name == STABLE || it.name == SINCE })
        }
        return own + inherited
    }

    private fun text(annotation: Annotation, name: String, positional: Int? = null): String? {
        val value = annotation.namedArgs.firstOrNull { it.first == name }?.second
            ?: positional?.let { annotation.args.getOrNull(it) }
        return (value as? Expr.StringLiteral)?.value?.takeIf { it.isNotEmpty() }
    }
}

/**
 * The declarations a stability decorator can mark, each by the key a use names
 * it by.
 *
 * | Key | Declaration |
 * |---|---|
 * | `fn:<name>` | a free function, a bridge function, a member lifted to its type (`Arena__make`) |
 * | `member:<symbol>` | an `impl` member, by the symbol the method table resolves to; a `ctor` by its factory |
 * | `spec:<Spec>.<member>` | a member a spec declares, reached through a spec-typed value |
 * | `type:<name>` | a pack, enum, error set, spec, alias, decorator, solo or variant type |
 * | `variant:<Type>.<case>` | an enum or error-set case |
 * | `field:<Pack>.<field>` | a stored field |
 * | `value:<name>` | a top-level `fin` / `var` / `let`, a bridge value |
 */
class StabilityIndex private constructor(private val marks: Map<String, List<StabilityMark>>) {

    operator fun get(key: String): List<StabilityMark> = marks[key].orEmpty()

    fun isMarked(key: String): Boolean = key in marks

    operator fun plus(other: StabilityIndex): StabilityIndex = StabilityIndex(other.marks + marks)

    companion object {
        val EMPTY = StabilityIndex(emptyMap())

        fun of(program: Program): StabilityIndex {
            val out = mutableMapOf<String, List<StabilityMark>>()
            fun put(key: String, annotations: List<Annotation>) {
                val found = StabilityMarks.of(annotations)
                if (found.isNotEmpty()) out[key] = found
            }
            fun putMarks(key: String, found: List<StabilityMark>) {
                if (found.isNotEmpty()) out[key] = found
            }
            fun visit(item: TopLevel) {
                when (item) {
                    is TopLevel.Func -> put("fn:${item.decl.name}", item.decl.annotations)
                    is TopLevel.FinDecl -> put("value:${item.name}", item.annotations)
                    is TopLevel.VarDecl -> put("value:${item.name}", item.annotations)
                    is TopLevel.LetDecl -> put("value:${item.name}", item.annotations)
                    is TopLevel.Pack -> {
                        put("type:${item.name}", item.annotations)
                        item.fields.forEach { put("field:${item.name}.${it.name}", it.annotations) }
                    }
                    is TopLevel.Solo -> {
                        put("type:${item.name}", item.annotations)
                        item.fields.forEach { put("field:${item.name}.${it.name}", it.annotations) }
                        item.methods.forEach { put("member:${item.name}_${it.name}", it.annotations) }
                    }
                    is TopLevel.Deco -> put("type:${item.name}", item.annotations)
                    is TopLevel.Enum -> {
                        put("type:${item.name}", item.annotations)
                        item.variants.forEachIndexed { i, case ->
                            put("variant:${item.name}.$case", item.variantAnnotations.getOrNull(i).orEmpty())
                        }
                    }
                    is TopLevel.Fail -> {
                        put("type:${item.name}", item.annotations)
                        item.variants.forEachIndexed { i, case ->
                            put("variant:${item.name}.$case", item.variantAnnotations.getOrNull(i).orEmpty())
                        }
                    }
                    is TopLevel.Slot -> put("type:${item.name}", item.annotations)
                    is TopLevel.TypeAlias -> put("type:${item.name}", item.annotations)
                    is TopLevel.Spec -> {
                        put("type:${item.name}", item.annotations)
                        item.methods.forEach {
                            putMarks("spec:${item.name}.${it.name}", StabilityMarks.ofMember(it.annotations, item.annotations))
                        }
                    }
                    is TopLevel.Impl -> for (method in item.methods) {
                        val found = StabilityMarks.ofMember(method.annotations, item.annotations)
                        if (found.isEmpty()) continue
                        if (method.name == "ctor") {
                            val arity = method.params.size - method.contextualParams
                            putMarks("member:${ctorFactorySymbol(item.typeName, arity, method.isRepeated)}", found)
                        } else {
                            putMarks("member:${mangleMethodSymbol("${item.typeName}_${method.name}")}", found)
                        }
                    }
                    is TopLevel.Bridge -> {
                        val block = item.annotations
                        item.funcs.forEach { putMarks("fn:${it.localName ?: it.name}", StabilityMarks.of(block)) }
                        item.values.forEach { putMarks("value:${it.name}", StabilityMarks.of(block)) }
                    }
                    is TopLevel.InlineIf -> {
                        item.thenBranch.forEach(::visit)
                        item.elseBranch?.forEach(::visit)
                    }
                    is TopLevel.DeepInlineIf -> {
                        item.thenBranch.forEach(::visit)
                        item.elseBranch?.forEach(::visit)
                    }
                    is TopLevel.InlineBlock -> item.body.forEach(::visit)
                    is TopLevel.DeepInlineBlock -> item.body.forEach(::visit)
                    else -> {}
                }
            }
            program.items.forEach(::visit)
            program.tupleTemplates.forEach(::visit)
            return StabilityIndex(out)
        }
    }
}

/**
 * Which declarations the compiled unit wrote itself, read from it as parsed -
 * before libraries are added, specs' provided members are copied into its
 * implementations, or derivations generate members for its types.
 *
 * Generated code is not reported: the author did not write the use.
 */
class UserCode private constructor(
    val moduleName: String?,
    private val functions: Set<String>,
    private val values: Set<String>,
    private val types: Set<String>,
    /** A member written in one of the unit's own `impl` blocks: `name|line|column`. */
    private val members: Set<String>,
    /** The decorators above the unit's `module` header. */
    val moduleAnnotations: List<Annotation>,
) {
    /** Whether the unit is part of the standard library, whose own experimental uses are not reported. */
    val isStandardLibrary: Boolean get() = moduleName == "std" || moduleName?.startsWith("std.") == true

    fun isFunction(name: String): Boolean = name in functions
    fun isValue(name: String): Boolean = name in values
    fun isType(name: String): Boolean = name in types
    fun isImpl(impl: TopLevel.Impl): Boolean = impl.declaringModule == null || impl.declaringModule == moduleName
    fun isMember(impl: TopLevel.Impl, method: FuncDecl): Boolean =
        isImpl(impl) && "${method.name}|${method.line}|${method.column}" in members

    /** Whether [item] - a declaration of the program as later passes see it - was written by the unit. */
    fun owns(item: TopLevel): Boolean = when (item) {
        is TopLevel.Func -> isFunction(item.decl.name)
        is TopLevel.FinDecl -> isValue(item.name)
        is TopLevel.VarDecl -> isValue(item.name)
        is TopLevel.LetDecl -> isValue(item.name)
        is TopLevel.Pack -> isType(item.name)
        is TopLevel.Solo -> isType(item.name)
        is TopLevel.Deco -> isType(item.name)
        is TopLevel.Enum -> isType(item.name)
        is TopLevel.Fail -> isType(item.name)
        is TopLevel.Slot -> isType(item.name)
        is TopLevel.TypeAlias -> isType(item.name)
        is TopLevel.Spec -> isType(item.name)
        is TopLevel.Impl -> isImpl(item)
        is TopLevel.Bridge -> item.funcs.any { isFunction(it.localName ?: it.name) } || item.values.any { isValue(it.name) }
        is TopLevel.Test, is TopLevel.Graph,
        is TopLevel.InlineIf, is TopLevel.DeepInlineIf, is TopLevel.InlineBlock, is TopLevel.DeepInlineBlock,
        is TopLevel.InlineFin, is TopLevel.InlineLet, is TopLevel.InlineVar, is TopLevel.InlineAssignment,
        is TopLevel.InlineAssert, is TopLevel.InlineTrace -> true
        else -> false
    }

    companion object {
        fun of(parsed: Program): UserCode {
            val functions = mutableSetOf<String>()
            val values = mutableSetOf<String>()
            val types = mutableSetOf<String>()
            val members = mutableSetOf<String>()
            fun visit(item: TopLevel) {
                when (item) {
                    is TopLevel.Func -> functions += item.decl.name
                    is TopLevel.FinDecl -> values += item.name
                    is TopLevel.VarDecl -> values += item.name
                    is TopLevel.LetDecl -> values += item.name
                    is TopLevel.Pack -> types += item.name
                    is TopLevel.Solo -> types += item.name
                    is TopLevel.Deco -> types += item.name
                    is TopLevel.Enum -> types += item.name
                    is TopLevel.Fail -> types += item.name
                    is TopLevel.Slot -> types += item.name
                    is TopLevel.TypeAlias -> types += item.name
                    is TopLevel.Spec -> types += item.name
                    is TopLevel.Impl -> item.methods.forEach { members += "${it.name}|${it.line}|${it.column}" }
                    is TopLevel.Bridge -> {
                        item.funcs.forEach { functions += it.localName ?: it.name }
                        item.values.forEach { values += it.name }
                    }
                    is TopLevel.InlineIf -> { item.thenBranch.forEach(::visit); item.elseBranch?.forEach(::visit) }
                    is TopLevel.DeepInlineIf -> { item.thenBranch.forEach(::visit); item.elseBranch?.forEach(::visit) }
                    is TopLevel.InlineBlock -> item.body.forEach(::visit)
                    is TopLevel.DeepInlineBlock -> item.body.forEach(::visit)
                    else -> {}
                }
            }
            parsed.items.forEach(::visit)
            return UserCode(parsed.moduleName, functions, values, types, members, parsed.moduleAnnotations)
        }
    }
}

/**
 * One use of a declaration that may carry a stability mark.
 *
 * [context] is the decorators of the declarations the use is written inside,
 * innermost first: a use inside something that is itself deprecated (or
 * experimental), or that suppresses the warning, is not reported.
 */
data class StabilityUse(
    val key: String,
    val shown: String,
    val line: Int,
    val column: Int,
    val context: List<Annotation>,
)

/**
 * Collects the uses the resolver makes through a value while it checks the
 * unit's own code. The resolver says where it is (`enter…`/[leave]) and what
 * each member resolved to; this decides whether that is the unit's own code.
 */
class StabilityRecorder(private val user: UserCode?) {

    /** Where the resolver is: the decorators in force, or null outside the unit's own code. */
    class Owner internal constructor(val context: List<Annotation>)

    private var owner: Owner? = null
    private var typeAnnotations: Map<String, List<Annotation>> = emptyMap()
    private val recorded = mutableListOf<StabilityUse>()

    val uses: List<StabilityUse> get() = recorded

    /** Called once per resolution, with the program it resolves. */
    fun attach(program: Program) {
        recorded.clear()
        owner = null
        if (user == null) return
        typeAnnotations = buildMap {
            for (item in program.items) {
                when (item) {
                    is TopLevel.Pack -> put(item.name, item.annotations)
                    is TopLevel.Enum -> put(item.name, item.annotations)
                    is TopLevel.Fail -> put(item.name, item.annotations)
                    is TopLevel.Solo -> put(item.name, item.annotations)
                    is TopLevel.Slot -> put(item.name, item.annotations)
                    is TopLevel.Spec -> put(item.name, item.annotations)
                    else -> {}
                }
            }
        }
    }

    private fun enter(next: Owner?): Owner? {
        val previous = owner
        owner = next
        return previous
    }

    fun leave(previous: Owner?) {
        owner = previous
    }

    fun enterFunction(func: FuncDecl): Owner? =
        enter(if (user?.isFunction(func.name) == true) Owner(func.annotations) else null)

    fun enterMethod(impl: TopLevel.Impl, method: FuncDecl): Owner? = enter(
        if (user?.isMember(impl, method) == true) {
            Owner(method.annotations + impl.annotations + typeAnnotations[impl.typeName].orEmpty())
        } else null,
    )

    fun enterTest(test: TopLevel.Test): Owner? = enter(if (user != null) Owner(test.annotations) else null)

    fun enterValue(name: String, annotations: List<Annotation>): Owner? =
        enter(if (user?.isValue(name) == true) Owner(annotations) else null)

    /** A pack's or decorator's field defaults. */
    fun enterType(name: String, annotations: List<Annotation>): Owner? =
        enter(if (user?.isType(name) == true) Owner(annotations) else null)

    private fun note(key: String, shown: String, line: Int, column: Int) {
        val context = owner?.context ?: return
        recorded += StabilityUse(key, shown, line, column, context)
    }

    /** A member of a value, resolved to the method-table [symbol] declared on [type]. */
    fun member(symbol: String, type: String, name: String, line: Int, column: Int) =
        note("member:$symbol", "${sourceSymbol(type)}.$name", line, column)

    /** A stored field of a value. */
    fun field(type: String, name: String, line: Int, column: Int) =
        note("field:$type.$name", "${sourceSymbol(type)}.$name", line, column)

    /** A member reached through a spec-typed value. */
    fun specMember(spec: String, name: String, line: Int, column: Int) =
        note("spec:$spec.$name", "${sourceSymbol(spec)}.$name", line, column)

    /**
     * `.Case` or `.member(…)` where the position states the type: a case, or a
     * member lifted to the type.
     */
    fun inferred(type: String, name: String, line: Int, column: Int) {
        val shown = "${sourceSymbol(type)}.$name"
        note("variant:$type.$name", shown, line, column)
        note("fn:${type}__$name", shown, line, column)
        note("value:${type}__$name", shown, line, column)
    }

    /** A construction that resolved to a declared `ctor`, by its factory. */
    fun constructor(factory: String, type: String, line: Int, column: Int) =
        note("member:$factory", "${sourceSymbol(type)}.ctor", line, column)
}

/**
 * The uses written by name in the unit's own code: calls, constructions,
 * types, globals, qualified cases and decorators.
 *
 * Read before compile-time evaluation, which folds a call it can evaluate into
 * its value - the call is still a use of what it called. A name a local
 * binding shadows is not the declaration it shadows, so the walk keeps the
 * lexical scopes the resolver keeps.
 */
object WrittenUses {

    fun collect(program: Program, user: UserCode, index: StabilityIndex): List<StabilityUse> {
        val walker = Walker(index, typeAnnotations(program))
        for (item in program.items) walker.visitItem(item, user)
        return walker.uses
    }

    private fun typeAnnotations(program: Program): Map<String, List<Annotation>> = buildMap {
        for (item in program.items) {
            when (item) {
                is TopLevel.Pack -> put(item.name, item.annotations)
                is TopLevel.Enum -> put(item.name, item.annotations)
                is TopLevel.Fail -> put(item.name, item.annotations)
                is TopLevel.Solo -> put(item.name, item.annotations)
                is TopLevel.Slot -> put(item.name, item.annotations)
                is TopLevel.Spec -> put(item.name, item.annotations)
                else -> {}
            }
        }
    }

    private class Walker(
        private val index: StabilityIndex,
        private val typeAnnotations: Map<String, List<Annotation>>,
    ) {
        val uses = mutableListOf<StabilityUse>()

        /** Lexical scopes of local bindings, innermost last. */
        private val locals = ArrayDeque<MutableSet<String>>()
        /** Type parameters in scope, which a type name may mean instead of a declaration. */
        private val typeParams = ArrayDeque<Set<String>>()
        private var context: List<Annotation> = emptyList()
        private var line = 0

        private fun isLocal(name: String): Boolean = locals.any { name in it }
        private fun isTypeParam(name: String): Boolean = typeParams.any { name in it }
        private fun define(name: String) {
            if (locals.isEmpty()) locals.addLast(mutableSetOf())
            locals.last().add(name)
        }

        private inline fun scoped(block: () -> Unit) {
            locals.addLast(mutableSetOf())
            try { block() } finally { locals.removeLast() }
        }

        private inline fun withTypeParams(names: Collection<String>, block: () -> Unit) {
            typeParams.addLast(names.toSet())
            try { block() } finally { typeParams.removeLast() }
        }

        private inline fun inside(annotations: List<Annotation>, block: () -> Unit) {
            val saved = context
            context = annotations + context
            try { block() } finally { context = saved }
        }

        private fun use(key: String, shown: String, atLine: Int, column: Int) {
            if (!index.isMarked(key)) return
            uses += StabilityUse(key, shown, if (atLine > 0) atLine else line, column, context)
        }

        // ── declarations ──────────────────────────────────────────────

        fun visitItem(item: TopLevel, user: UserCode) {
            when (item) {
                is TopLevel.Impl -> if (user.isImpl(item)) impl(item, user)
                is TopLevel.InlineIf -> {
                    expr(item.condition)
                    item.thenBranch.forEach { visitItem(it, user) }
                    item.elseBranch?.forEach { visitItem(it, user) }
                }
                is TopLevel.DeepInlineIf -> {
                    expr(item.condition)
                    item.thenBranch.forEach { visitItem(it, user) }
                    item.elseBranch?.forEach { visitItem(it, user) }
                }
                is TopLevel.InlineBlock -> item.body.forEach { visitItem(it, user) }
                is TopLevel.DeepInlineBlock -> item.body.forEach { visitItem(it, user) }
                else -> if (user.owns(item)) declaration(item)
            }
        }

        private fun impl(item: TopLevel.Impl, user: UserCode) {
            line = item.line
            val typeContext = item.annotations + typeAnnotations[item.typeName].orEmpty()
            withTypeParams(item.typeParams) {
                inside(typeContext) {
                    annotations(item.annotations)
                    typeName(item.typeName, item.line, item.column)
                    item.traitName?.let { typeName(it, item.traitLine, item.traitColumn) }
                    item.traitArgs.forEach(::type)
                    item.decoratorArgs.forEach(::expr)
                    item.decoratorNamedArgs.forEach { expr(it.second) }
                    for (method in item.methods) {
                        if (user.isMember(item, method)) function(method)
                    }
                }
            }
        }

        private fun declaration(item: TopLevel) {
            when (item) {
                is TopLevel.Func -> function(item.decl)
                is TopLevel.FinDecl -> global(item.annotations, item.type, item.initializer, item.line)
                is TopLevel.VarDecl -> global(item.annotations, item.type, item.initializer, item.line)
                is TopLevel.LetDecl -> global(item.annotations, item.type, item.initializer, item.line)
                is TopLevel.InlineFin -> { line = item.line; expr(item.initializer) }
                is TopLevel.InlineLet -> { line = item.line; expr(item.initializer) }
                is TopLevel.InlineVar -> { line = item.line; expr(item.initializer) }
                is TopLevel.InlineAssignment -> { line = item.line; expr(item.value) }
                is TopLevel.InlineAssert -> { line = item.line; expr(item.condition); expr(item.message) }
                is TopLevel.InlineTrace -> { line = item.line; expr(item.message); item.level?.let(::expr) }
                is TopLevel.Test -> {
                    line = item.line
                    inside(item.annotations) {
                        annotations(item.annotations)
                        scoped { stmts(item.body) }
                    }
                }
                is TopLevel.Pack -> {
                    line = item.line
                    inside(item.annotations) {
                        annotations(item.annotations)
                        withTypeParams(item.typeParams + item.constParams) {
                            item.whereClause?.let(::expr)
                            item.fields.forEach { field ->
                                inside(field.annotations) {
                                    annotations(field.annotations)
                                    type(field.type)
                                    field.default?.let(::expr)
                                    field.condition?.let(::expr)
                                }
                            }
                        }
                    }
                }
                is TopLevel.Solo -> {
                    line = item.line
                    inside(item.annotations) {
                        annotations(item.annotations)
                        item.fields.forEach { field ->
                            annotations(field.annotations)
                            type(field.type)
                            field.default?.let(::expr)
                        }
                        item.methods.forEach(::function)
                    }
                }
                is TopLevel.Deco -> {
                    line = item.line
                    inside(item.annotations) {
                        annotations(item.annotations)
                        item.fields.forEach { field ->
                            type(field.type)
                            field.default?.let(::expr)
                        }
                    }
                }
                is TopLevel.Enum -> {
                    line = item.line
                    inside(item.annotations) {
                        annotations(item.annotations)
                        item.variantAnnotations.forEach(::annotations)
                    }
                }
                is TopLevel.Fail -> {
                    line = item.line
                    inside(item.annotations) {
                        annotations(item.annotations)
                        item.variantAnnotations.forEach(::annotations)
                        withTypeParams(item.typeParams) { item.variantPayloads.flatten().forEach(::type) }
                    }
                }
                is TopLevel.Slot -> {
                    line = item.line
                    inside(item.annotations) {
                        annotations(item.annotations)
                        withTypeParams(item.typeParams) { item.variants.flatMap { it.payloadTypes }.forEach(::type) }
                    }
                }
                is TopLevel.TypeAlias -> {
                    line = item.line
                    inside(item.annotations) {
                        annotations(item.annotations)
                        withTypeParams(item.typeParams) { type(item.type) }
                    }
                }
                is TopLevel.Spec -> {
                    line = item.line
                    inside(item.annotations) {
                        annotations(item.annotations)
                        withTypeParams(item.typeParams) {
                            item.parents.forEach(::type)
                            item.requires.forEach(::type)
                            item.methods.forEach(::function)
                        }
                    }
                }
                is TopLevel.Bridge -> {
                    line = item.line
                    inside(item.annotations) {
                        annotations(item.annotations)
                        for (sig in item.funcs) {
                            line = sig.line
                            withTypeParams(sig.typeParams) {
                                sig.params.forEach { param(it, define = false) }
                                type(sig.returnType)
                            }
                        }
                        for (value in item.values) {
                            line = value.line
                            type(value.type)
                            expr(value.initializer)
                        }
                    }
                }
                is TopLevel.Graph -> {
                    line = item.line
                    for (registration in item.registrations) {
                        typeName(registration.typeName, registration.line, registration.column)
                        registration.args.forEach(::expr)
                        registration.bindSpecs.forEach { typeName(it, registration.line, registration.column) }
                    }
                }
                else -> {}
            }
        }

        private fun global(annotations: List<Annotation>, type: TypeRef?, initializer: Expr, at: Int) {
            line = at
            inside(annotations) {
                annotations(annotations)
                type?.let(::type)
                expr(initializer)
            }
        }

        private fun function(decl: FuncDecl) {
            val savedLine = line
            line = decl.line
            inside(decl.annotations) {
                annotations(decl.annotations)
                withTypeParams(decl.typeParams + decl.constParams) {
                    scoped {
                        if (decl.declaresReceiver) define(decl.receiverName)
                        define("self")
                        decl.constParams.forEach(::define)
                        decl.params.forEach { param(it, define = true) }
                        (decl.returnType as? TypeAnnotation.Explicit)?.let { type(it.ref) }
                        decl.whereClause?.let(::expr)
                        stmts(decl.body)
                    }
                }
            }
            line = savedLine
        }

        private fun param(param: Param, define: Boolean) {
            annotations(param.annotations)
            type(param.type)
            param.defaultValue?.let(::expr)
            if (define) define(param.name)
        }

        private fun annotations(list: List<Annotation>) {
            for (annotation in list) {
                typeName(annotation.name, annotation.line, annotation.column)
                annotation.args.forEach(::expr)
                annotation.namedArgs.forEach { expr(it.second) }
            }
        }

        // ── types ─────────────────────────────────────────────────────

        private fun typeName(name: String, at: Int, column: Int) {
            if (isTypeParam(name)) return
            use("type:$name", sourceSymbol(name), at, column)
        }

        private fun type(ref: TypeRef) {
            when (ref) {
                is TypeRef.Named -> {
                    typeName(ref.name, ref.line, ref.column)
                    ref.args.forEach(::type)
                    ref.valueArgs.forEach(::expr)
                }
                is TypeRef.Array -> type(ref.element)
                is TypeRef.Map -> { type(ref.key); type(ref.value) }
                is TypeRef.Set -> type(ref.element)
                is TypeRef.Function -> { ref.receivers.forEach(::type); ref.params.forEach(::type); type(ref.ret) }
                is TypeRef.Tuple -> ref.elements.forEach(::type)
                is TypeRef.Nullable -> type(ref.inner)
                is TypeRef.Failable -> { type(ref.ok); ref.errSets.forEach { typeName(it, 0, 0) } }
                is TypeRef.Pointer -> type(ref.inner)
                is TypeRef.Reference -> type(ref.inner)
                is TypeRef.Const -> {}
            }
        }

        private fun annotation(type: TypeAnnotation) {
            (type as? TypeAnnotation.Explicit)?.let { type(it.ref) }
        }

        // ── statements ────────────────────────────────────────────────

        private fun stmts(list: List<Stmt>) = list.forEach(::stmt)

        private fun block(list: List<Stmt>) = scoped { stmts(list) }

        private fun stmt(s: Stmt) {
            if (s.line > 0) line = s.line
            when (s) {
                is Stmt.Import -> {}
                is Stmt.Exchange -> { expr(s.left); expr(s.right) }
                is Stmt.VarDecl -> { annotation(s.type); expr(s.initializer); define(s.name) }
                is Stmt.FinDecl -> { annotation(s.type); expr(s.initializer); define(s.name) }
                is Stmt.LetDecl -> { annotation(s.type); expr(s.initializer); define(s.name) }
                is Stmt.RemDecl -> { annotation(s.type); expr(s.initializer); define(s.name) }
                is Stmt.InlineFin -> { annotation(s.type); expr(s.initializer); define(s.name) }
                is Stmt.InlineLet -> { annotation(s.type); expr(s.initializer); define(s.name) }
                is Stmt.InlineVar -> { annotation(s.type); expr(s.initializer); define(s.name) }
                is Stmt.InlineAssignment -> { assigned(s.name); expr(s.value) }
                is Stmt.Assignment -> { assigned(s.name); expr(s.value) }
                is Stmt.DeepInlineBlock -> block(s.body)
                is Stmt.InlineBlock -> block(s.body)
                is Stmt.NoInline -> stmt(s.stmt)
                is Stmt.Return -> s.value?.let(::expr)
                is Stmt.ExprStmt -> expr(s.expr)
                is Stmt.Scope -> block(s.body)
                is Stmt.If -> { expr(s.condition); block(s.thenBranch); s.elseBranch?.let(::block) }
                is Stmt.InlineIf -> { expr(s.condition); block(s.thenBranch); s.elseBranch?.let(::block) }
                is Stmt.DeepInlineIf -> { expr(s.condition); block(s.thenBranch); s.elseBranch?.let(::block) }
                is Stmt.Assert -> { expr(s.condition); expr(s.message) }
                is Stmt.InlineAssert -> { expr(s.condition); expr(s.message) }
                is Stmt.Trace -> { expr(s.message); s.level?.let(::expr) }
                is Stmt.InlineTrace -> { expr(s.message); s.level?.let(::expr) }
                is Stmt.While -> { expr(s.condition); block(s.body) }
                is Stmt.For -> {
                    expr(s.iterable)
                    s.step?.let(::expr)
                    s.declaredType?.let(::type)
                    scoped {
                        define(s.name)
                        s.indexName?.let(::define)
                        stmts(s.body)
                    }
                }
                is Stmt.InlineFor -> {
                    expr(s.iterable)
                    scoped {
                        define(s.name)
                        s.indexName?.let(::define)
                        stmts(s.body)
                    }
                }
                is Stmt.Loop -> {
                    s.iterable?.let(::expr)
                    s.everySeconds?.let(::expr)
                    block(s.body)
                }
                is Stmt.Break -> s.value?.let(::expr)
                is Stmt.Continue -> {}
                is Stmt.IndexAssign -> { expr(s.target); expr(s.index); expr(s.value) }
                is Stmt.MemberAssign -> {
                    expr(s.target)
                    s.nameExpr?.let(::expr)
                    qualified(s.target, s.name, s.line, s.column)
                    expr(s.value)
                }
                is Stmt.When -> {
                    expr(s.scrutinee)
                    for (branch in s.branches) {
                        scoped {
                            branch.patterns.forEach(::pattern)
                            stmts(branch.body)
                        }
                    }
                    s.elseBranch?.let(::block)
                }
                is Stmt.Throw -> expr(s.value)
                is Stmt.Panic -> expr(s.message)
                is Stmt.DerefAssign -> { expr(s.target); expr(s.value) }
                is Stmt.Yield -> expr(s.value)
                is Stmt.Try -> {
                    block(s.body)
                    s.catchBody?.let { body ->
                        scoped {
                            s.catchName?.let(::define)
                            stmts(body)
                        }
                    }
                }
                is Stmt.Defer -> block(s.body)
                is Stmt.Effect -> {
                    s.dependencies?.forEach(::expr)
                    s.condition?.let(::expr)
                    block(s.body)
                }
                is Stmt.UsingContext -> { s.values.forEach(::expr); block(s.body) }
            }
        }

        /** A write to a name: a global written from here is used. */
        private fun assigned(name: String) {
            if (isLocal(name)) return
            use("value:$name", sourceSymbol(name), line, 0)
        }

        /**
         * A `when` pattern. A bare name inside a case's payload binds what the
         * case carries (`.Circle(r)`), so it is defined rather than read.
         */
        private fun pattern(p: Expr) {
            fun payload(args: List<Expr>) {
                for (arg in args) if (arg is Expr.Identifier) define(arg.name) else expr(arg)
            }
            when (p) {
                is Expr.InferredMember -> p.ctorArgs?.let(::payload)
                is Expr.MethodCall -> {
                    expr(p.target)
                    qualified(p.target, p.name, p.line, p.column)
                    payload(p.args)
                }
                is Expr.Call -> {
                    callee(p.callee, p.line, p.column)
                    p.typeArgs.forEach(::type)
                    payload(p.args)
                }
                else -> expr(p)
            }
        }

        // ── expressions ───────────────────────────────────────────────

        private fun callee(name: String, at: Int, column: Int) {
            if (isLocal(name)) return
            use("fn:$name", sourceSymbol(name), at, column)
            typeName(name, at, column)
            use("value:$name", sourceSymbol(name), at, column)
        }

        /**
         * `Type.name` written with a type in front: a case, or a member lifted
         * to its type. A local in front is a value, whose member only the
         * resolver can name.
         */
        private fun qualified(target: Expr, name: String, at: Int, column: Int) {
            val owner = (target as? Expr.Identifier)?.name ?: return
            if (isLocal(owner)) return
            use("variant:$owner.$name", "${sourceSymbol(owner)}.$name", at, column)
            use("fn:${owner}__$name", "${sourceSymbol(owner)}.$name", at, column)
            use("value:${owner}__$name", "${sourceSymbol(owner)}.$name", at, column)
        }

        private fun lambda(e: Expr.Lambda) {
            withTypeParams(e.typeParams) {
                scoped {
                    e.receivers.forEach { param(it, define = true) }
                    e.params.forEach { param(it, define = true) }
                    if (!e.paramsWritten || e.params.isEmpty()) define("it")
                    stmts(e.body)
                }
            }
        }

        private fun expr(e: Expr) {
            when (e) {
                is Expr.IntLiteral, is Expr.DoubleLiteral, is Expr.CharLiteral, is Expr.StringLiteral,
                is Expr.BoolLiteral, Expr.NullLiteral -> {}
                is Expr.Identifier -> {
                    if (isLocal(e.name)) return
                    use("value:${e.name}", sourceSymbol(e.name), e.line, e.column)
                    use("fn:${e.name}", sourceSymbol(e.name), e.line, e.column)
                    typeName(e.name, e.line, e.column)
                }
                is Expr.UpperScopeAccess -> use("value:${e.name}", sourceSymbol(e.name), e.line, e.column)
                is Expr.Binary -> { expr(e.left); expr(e.right) }
                is Expr.Unary -> expr(e.operand)
                is Expr.IncDec -> expr(e.target)
                is Expr.Call -> {
                    callee(e.callee, e.line, e.column)
                    e.typeArgs.forEach(::type)
                    e.receiver?.let(::expr)
                    e.args.forEach(::expr)
                }
                is Expr.Grouping -> expr(e.expr)
                is Expr.MapEntryArg -> { expr(e.key); expr(e.value) }
                is Expr.Range -> { expr(e.from); expr(e.to) }
                is Expr.ArrayLiteral -> e.elements.forEach(::expr)
                is Expr.SetLiteral -> e.elements.forEach(::expr)
                is Expr.Index -> { expr(e.target); expr(e.index) }
                // The type a `.Case` belongs to is the position's; the resolver names it.
                is Expr.InferredMember -> e.ctorArgs?.forEach(::expr)
                is Expr.Member -> {
                    expr(e.target)
                    e.nameExpr?.let(::expr)
                    qualified(e.target, e.name, e.line, e.column)
                }
                is Expr.MethodCall -> {
                    expr(e.target)
                    qualified(e.target, e.name, e.line, e.column)
                    e.args.forEach(::expr)
                }
                is Expr.StringTemplate -> e.parts.forEach { part ->
                    if (part is Expr.StringTemplatePart.Expr) expr(part.expr)
                }
                is Expr.TupleLit -> e.elements.forEach(::expr)
                is Expr.VariantLit -> e.elements.forEach(::expr)
                is Expr.TupleAccess -> expr(e.target)
                is Expr.CatchExpr -> { expr(e.expr); expr(e.fallback) }
                is Expr.TryPropagate -> expr(e.expr)
                is Expr.IfExpr -> { expr(e.condition); expr(e.thenExpr); expr(e.elseExpr) }
                is Expr.Seal -> expr(e.value)
                is Expr.Lambda -> lambda(e)
                is Expr.InlineForArgs -> {
                    expr(e.iterable)
                    scoped { define(e.name); expr(e.body) }
                }
                is Expr.NamedArg -> expr(e.value)
                is Expr.NullCoalesce -> { expr(e.left); expr(e.right) }
                is Expr.SafeMember -> expr(e.target)
                is Expr.Cast -> { expr(e.expr); type(e.targetType) }
                is Expr.IsCheck -> { expr(e.expr); typeName(e.typeName, e.line, e.column) }
                is Expr.InCheck -> { expr(e.value); expr(e.collection) }
                is Expr.MapLit -> e.entries.forEach { (k, v) -> expr(k); expr(v) }
                is Expr.Alloc -> expr(e.value)
                is Expr.Deref -> expr(e.target)
                is Expr.Isolated -> expr(e.value)
                is Expr.Await -> expr(e.value)
                is Expr.Inject -> typeName(e.typeName, e.line, e.column)
                is Expr.Spread -> expr(e.array)
                // Expanded before analysis; what it expands to was written by its macro.
                is Expr.MetaInvoke -> e.args.forEach(::expr)
                is Expr.Slice -> {
                    expr(e.target)
                    e.start?.let(::expr)
                    e.stop?.let(::expr)
                    e.step?.let(::expr)
                }
            }
        }
    }
}

/**
 * Turns the uses found into warnings.
 *
 * A deprecated declaration's use is always a warning. An experimental one's is
 * reported only when asked for ([reportExperimental]), and never inside the
 * standard library itself: most of std is experimental in 0.1, and a warning on
 * every list would teach readers to skip warnings. The decision is the owner's
 * (STABILITY_DIP §3).
 */
object StabilityReport {

    fun warnings(
        uses: List<StabilityUse>,
        index: StabilityIndex,
        user: UserCode,
        reportExperimental: Boolean,
    ): List<String> {
        val out = linkedSetOf<String>()
        val seen = mutableSetOf<String>()
        for (use in uses.sortedWith(compareBy({ it.line }, { it.column }))) {
            if (!seen.add("${use.key}|${use.line}|${use.column}")) continue
            for (mark in index[use.key]) {
                if (StabilityMarks.declares(use.context, mark.kind)) continue
                if (StabilityMarks.suppresses(use.context, mark.kind)) continue
                if (StabilityMarks.suppresses(user.moduleAnnotations, mark.kind)) continue
                when (mark.kind) {
                    StabilityKind.DEPRECATED -> out += deprecated(use, mark)
                    StabilityKind.EXPERIMENTAL ->
                        if (reportExperimental && !user.isStandardLibrary) out += experimental(use, mark)
                }
            }
        }
        return out.toList()
    }

    private fun deprecated(use: StabilityUse, mark: StabilityMark): String = buildString {
        append("warning: line ${use.line}: '${use.shown}' is deprecated")
        mark.since?.let { append(" since $it") }
        mark.replacement?.let { append(" - use '$it' instead") }
    }

    private fun experimental(use: StabilityUse, mark: StabilityMark): String = buildString {
        append("warning: line ${use.line}: '${use.shown}' is experimental")
        mark.since?.let { append(" (since $it)") }
        append(" and may change or be removed in any release")
    }
}
