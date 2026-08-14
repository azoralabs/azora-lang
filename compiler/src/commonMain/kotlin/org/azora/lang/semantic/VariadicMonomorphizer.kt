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

import org.azora.lang.frontend.ParamModifier
import org.azora.lang.frontend.Annotation
import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.NamedTypeMacroCall
import org.azora.lang.frontend.NumericSuffix
import org.azora.lang.frontend.PackField
import org.azora.lang.frontend.Param
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TokenType
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeFormKind
import org.azora.lang.frontend.TypeRef
import org.azora.lang.frontend.TypeTypeArm
import org.azora.lang.frontend.VariadicFieldTemplate

/**
 * Monomorphizes variadic generic declarations - packs declared with a type
 * vararg (`pack Tuple<...T> where (...T).length >= 2 { inline for Ty in ...T with index { mixin "$index: $Ty" } }`)
 * and functions declared with one (`func<...T> tupleOf(elements: ...T): Tuple<...T>`).
 *
 * Azora erases ordinary generics at the IR boundary, but a variadic pack's arity
 * varies per instantiation, so it cannot be erased. Instead every concrete
 * instantiation is materialized as a distinct declaration:
 *
 * - `Tuple(Int, String)` (i.e. `Tuple<Int, String>`) →
 *   `pack __Tuple_Int_String { 0: Int; 1: String }` (annotated `@EnforceNumFields`
 *   so numeric field names are permitted).
 * - `tupleOf<Int, String>(a, b)` → a real monomorphized function
 *   `__tupleOf_Int_String(_0: Int, _1: String): __Tuple_Int_String` whose body is
 *   `return __Tuple_Int_String(_0, _1)`.
 *
 * The pass runs before semantic analysis, rewriting every `Tuple<...>` type
 * reference and every `tupleOf(...)` / `Tuple(...)` call to its monomorphized
 * name, dropping the original templates, and appending the synthesized concrete
 * declarations. Downstream phases (semantic, IR, backends) then see only
 * ordinary fixed packs and functions.
 *
 * Element types for a `tupleOf`/`Tuple` call are taken from explicit call type
 * arguments (`tupleOf<Int, Double>(…)`) when present, otherwise inferred from
 * literal arguments (`1` → Int, `2.0` → Double, …).
 */
internal object VariadicMonomorphizer {

    fun monomorphize(program: Program): Program {
        val packTemplates = linkedMapOf<String, TopLevel.Pack>()
        val funcTemplates = linkedMapOf<String, TopLevel.Func>()
        val implTemplates = linkedMapOf<String, MutableList<TopLevel.Impl>>()
        val constructibleTypes = linkedMapOf<String, List<String>>()
        val functionReturns = linkedMapOf<String, CallableReturn>()
        for (item in program.items) {
            when (item) {
                is TopLevel.Pack -> {
                    constructibleTypes[item.name] = item.typeParams
                    // A pack is monomorphised when its layout depends on its arguments:
                    // a variadic pack generates fields from its element types, and a pack
                    // with conditional fields has a different layout per binding. An
                    // ordinary generic still erases to one struct, exactly as before.
                    if (item.variadicParam != null || item.fields.any { it.condition != null }) {
                        packTemplates[item.name] = item
                    }
                }
                is TopLevel.Func -> {
                    explicitReturnType(item.decl)?.let {
                        functionReturns[item.decl.name] = CallableReturn(item.decl.typeParams, it)
                    }
                    // Only a variadic function that RETURNS a variadic pack (`Tuple<T…>`)
                    // is a monomorphization template. Plain variadic functions that just
                    // collect args into an array (`args: …T`) are left as runtime variadics.
                    val decl = item.decl
                    if (decl.variadicParam != null && returnedVariadicPackName(decl) != null) {
                        funcTemplates[decl.name] = item
                    }
                }
                is TopLevel.Solo -> constructibleTypes[item.name] = emptyList()
                else -> {}
            }
        }
        for (item in program.items.filterIsInstance<TopLevel.Impl>()) {
            // Every impl of a monomorphised pack is a template: the methods are
            // materialised once per specialization, which is what lets a body written
            // against `Self` see that specialization's own fields.
            if (item.typeName in packTemplates) {
                implTemplates.getOrPut(item.typeName) { mutableListOf() }.add(item)
            }
        }
        val typeMacroRules = program.typeMacroRules
        if (packTemplates.isEmpty() && funcTemplates.isEmpty() && typeMacroRules.isEmpty()) return program

        val methodReturns = linkedMapOf<Pair<String, String>, CallableReturn>()
        for (item in program.items) {
            val owner = when (item) {
                is TopLevel.Impl -> item.typeName.substringBefore('<')
                is TopLevel.Solo -> item.name
                else -> null
            } ?: continue
            val ownerTypeParams = constructibleTypes[owner].orEmpty()
            val methods = when (item) {
                is TopLevel.Impl -> item.methods
                is TopLevel.Solo -> item.methods
                else -> emptyList()
            }
            for (method in methods) {
                explicitReturnType(method)?.let {
                    methodReturns[owner to method.name] = CallableReturn(ownerTypeParams + method.typeParams, it)
                }
            }
        }

        val ctx = MonoContext(
            packTemplates,
            funcTemplates,
            constructibleTypes,
            functionReturns,
            methodReturns,
            implTemplates,
            typeMacroRules,
            program.realmTypeNamespaces,
            program.items.filterIsInstance<TopLevel.Pack>().associate { it.name to it.fields },
            program.items.filterIsInstance<TopLevel.Enum>().associate { it.name to it.variants },
        )
        // An impl on an ordinary pack still needs its reflected loops expanded - the
        // pack's own fields are known even when it is not monomorphised.
        // A `typealias Vec2i = Vec<Int, 2>` names one specialization, and
        // `Vec2i::zero` has to reach that specialization's static member. Resolving
        // the aliases first gives every later rewrite the mangled name to use.
        ctx.resolveStaticAliases(program.items)
        val expanded = program.items
            .map { ctx.expandPlainImplReflection(it, program) }
            .map { ctx.expandConstGenericMembers(it) }
        // A static block's members are parser-mangled to `Pack__member` and, for a
        // monomorphised pack, are templates in exactly the way its methods are: they
        // are held back here and re-emitted once per specialization below.
        val staticTemplates = expanded.filter { ctx.isStaticTemplateMember(it) }
        val rewritten = expanded.filterNot { ctx.isStaticTemplateMember(it) }.mapNotNull { ctx.rewriteTopLevel(it) }
        val finalItems = rewritten + ctx.packs.values + ctx.funcs.values + ctx.expandImpls() +
            ctx.expandStaticMembers(staticTemplates)
        return program.copy(
            items = finalItems,
            realmTypeNamespaces = program.realmTypeNamespaces + ctx.generatedPackNamespaces,
        )
    }

    private fun explicitReturnType(decl: FuncDecl): TypeRef? =
        (decl.returnType as? TypeAnnotation.Explicit)?.ref

    /** The name of the variadic pack a function template returns (`Tuple<T…>` → "Tuple"). */
    private fun returnedVariadicPackName(decl: FuncDecl): String? {
        val ref = (decl.returnType as? TypeAnnotation.Explicit)?.ref as? TypeRef.Named ?: return null
        return if (ref.variadic) ref.name else null
    }
}

private data class CallableReturn(
    val typeParams: List<String>,
    val returnType: TypeRef,
)

private class MonoContext(
    private val packTemplates: Map<String, TopLevel.Pack>,
    private val funcTemplates: Map<String, TopLevel.Func>,
    private val constructibleTypes: Map<String, List<String>>,
    private val functionReturns: Map<String, CallableReturn>,
    private val methodReturns: Map<Pair<String, String>, CallableReturn>,
    private val implTemplates: Map<String, List<TopLevel.Impl>>,
    private val typeMacroRules: List<TypeTypeArm>,
    private val typeNamespaces: Map<String, String>,
    /** Every declared pack's fields, so reflection over a named type can answer. */
    private val plainPackFields: Map<String, List<PackField>>,
    /** Every declared enum's variants, for resolving variant const arguments. */
    private val enumVariants: Map<String, List<String>>,
) {

    /**
     * The one specialization authority, shared with TypeResolver's field lookup so a
     * layout is computed once and both agree on it.
     */
    private val specializer = PackSpecializer(SymbolTable())

    val packs = linkedMapOf<String, TopLevel.Pack>()
    val funcs = linkedMapOf<String, TopLevel.Func>()
    val generatedPackNamespaces = linkedMapOf<String, String>()
    private val packArguments = linkedMapOf<String, List<TypeRef>>()

    /**
     * In-scope value bindings (parameter names, local `fin`/`var`/`let`) to their
     * *original* (pre-rewrite) [TypeRef], used to infer element types of variadic
     * calls with non-literal args (`tupleOf(t.0, t.1)`). Flat per function.
     */
    private val bindings = mutableMapOf<String, TypeRef>()

    // ------------------------------------------------------------------
    // Instantiation
    // ------------------------------------------------------------------

    /**
     * Gives each enum-variant argument the position it stands for.
     *
     * `.ColumnMajor` may be written in a module that has not seen `MatrixOrder` - a
     * library declares the enum, a consumer names one of its variants. The whole
     * program is in view here, so this is where the name becomes a number the
     * compile-time machinery can compare.
     */
    private fun resolveVariantArguments(arguments: List<TypeRef>): List<TypeRef> {
        if (arguments.none { it is TypeRef.Const && it.unresolved }) return arguments
        return arguments.map { argument ->
            val const = argument as? TypeRef.Const ?: return@map argument
            if (!const.unresolved) return@map argument
            val variant = const.label ?: return@map argument
            val owners = enumVariants.filterValues { variant in it }
            when (owners.size) {
                1 -> TypeRef.Const(owners.values.first().indexOf(variant).toLong(), variant)
                0 -> error("no declared enum has a variant '.$variant'")
                else -> error(
                    "'.$variant' is ambiguous between ${owners.keys.sorted().joinToString(", ")}; " +
                        "qualify it, as in '${owners.keys.sorted().first()}.$variant'",
                )
            }
        }
    }

    /**
     * Rejects a specialization whose `where` clause does not hold.
     *
     * The only binding a variadic template has is its pack's length, so this is a
     * thin adapter onto [ConstraintEvaluator] rather than a second implementation:
     * `(...T).length >= 2` evaluates there as an ordinary comparison. A clause the
     * evaluator cannot decide is accepted - under-enforcing beats rejecting valid
     * code.
     */
    private fun checkConstraints(
        what: String,
        clause: org.azora.lang.frontend.Expr?,
        variadicParam: String?,
        args: List<TypeRef>,
        declaration: TopLevel.Pack? = null,
    ) {
        if (clause == null) return
        // A variadic pack binds only its length; an ordinary generic binds each of
        // its type and const parameters, so `where T is Number && N in 2..4` is
        // decided here - before the specialization is published.
        val bindings = buildMap {
            if (variadicParam != null) {
                put(variadicParam, ConstraintEvaluator.Binding.Pack(args.size.toLong()))
            }
            declaration?.typeParams?.forEachIndexed { index, parameter ->
                args.getOrNull(index)?.let { argument ->
                    ConstraintEvaluator.bindingOf(argument)?.let { put(parameter, it) }
                }
            }
        }
        if (bindings.isEmpty()) return
        val outcome = ConstraintEvaluator.evaluate(clause, bindings, table = null)
        if (outcome is ConstraintEvaluator.Outcome.Violated) {
            error("$what does not satisfy its 'where' clause: ${outcome.reason}")
        }
    }

    fun instantiatePack(templateName: String, supplied: List<TypeRef>): String {
        val template = packTemplates[templateName] ?: return templateName
        // A const parameter the use site omitted takes its declared default, so the
        // specialization is the same one an explicit spelling would name.
        val args = resolveVariantArguments(specializer.withDefaults(template, supplied))
        // `Vec<T, N>` inside another generic has chosen no layout, so it stays on the
        // template. PackSpecializer decides what "concrete" means; this must not
        // re-derive that rule.
        if (template.variadicParam == null && specializer.keyForRefs(template, args) == null) {
            return templateName
        }
        val mangled = mangleTemplate(templateName, args)
        // This pass runs twice - once before the re-injection that pulls in whatever
        // an expansion newly referenced, once after - so a specialization emitted by
        // the first run is already a declared pack when the second run reaches the
        // same use. Emitting it again would define the struct, and every impl member
        // on it, a second time. The name is the specialization, so seeing it declared
        // is proof the work is done; only the mangled name is still needed.
        if (mangled !in packs && mangled !in plainPackFields) {
            // One check per unique combination: the mangled name IS the combination,
            // so validating inside this guard runs the clause exactly once per
            // specialization rather than once per use site.
            checkConstraints(
                if (template.variadicParam != null) "variadic pack '$templateName'" else "'$templateName'",
                template.whereClause,
                template.variadicParam,
                args,
                template,
            )
            val concreteArgs = args.map(::rewriteType)
            packArguments[mangled] = concreteArgs
            packs[mangled] = expandPack(mangled, template, concreteArgs)
            typeNamespaces[templateName]?.let { generatedPackNamespaces[mangled] = it }
        }
        return mangled
    }

    fun instantiateFunc(templateName: String, elementTypes: List<TypeRef>): String {
        val template = funcTemplates[templateName] ?: return templateName
        val mangled = mangleTemplate(templateName, elementTypes)
        if (mangled !in funcs) {
            checkConstraints(
                "variadic function '$templateName'",
                template.decl.whereClause,
                template.decl.variadicParam,
                elementTypes,
            )
            val packName = returnedVariadicPackName(template.decl) ?: templateName
            val packMangled = instantiatePack(packName, elementTypes)
            funcs[mangled] = expandFunc(mangled, template.decl, elementTypes, packMangled)
        }
        return mangled
    }

    /** The name of the variadic pack a function template returns (`Tuple<T…>` → "Tuple"). */
    private fun returnedVariadicPackName(decl: FuncDecl): String? {
        val ref = (decl.returnType as? TypeAnnotation.Explicit)?.ref as? TypeRef.Named ?: return null
        return if (ref.variadic && ref.name in packTemplates) ref.name else null
    }

    /** Replaces a declaration's type parameters with the concrete arguments. */
    private fun substituteParams(ref: TypeRef, bindings: Map<String, TypeRef>): TypeRef = when (ref) {
        is TypeRef.Named -> bindings[ref.name]
            ?: ref.copy(args = ref.args.map { substituteParams(it, bindings) })
        is TypeRef.Array -> ref.copy(element = substituteParams(ref.element, bindings))
        is TypeRef.Map -> ref.copy(
            key = substituteParams(ref.key, bindings),
            value = substituteParams(ref.value, bindings),
        )
        is TypeRef.Set -> ref.copy(element = substituteParams(ref.element, bindings))
        is TypeRef.Nullable -> ref.copy(inner = substituteParams(ref.inner, bindings))
        is TypeRef.Failable -> ref.copy(ok = substituteParams(ref.ok, bindings))
        is TypeRef.Pointer -> ref.copy(inner = substituteParams(ref.inner, bindings))
        is TypeRef.Reference -> ref.copy(inner = substituteParams(ref.inner, bindings))
        else -> ref
    }

    /**
     * Gives a template's `= 0` the width the specialization chose.
     *
     * `var x: T = 0` is not a declaration of an `Int` zero - it is a zero of whatever
     * `T` turns out to be. The literal is retyped here so the field's default matches
     * the field, without loosening what a literal means in code someone wrote for one
     * concrete type.
     */
    private fun retypeIntDefault(default: Expr, fieldType: TypeRef): Expr {
        val literal = default as? Expr.IntLiteral ?: return default
        if (literal.suffix != NumericSuffix.NONE) return default
        val suffix = when ((fieldType as? TypeRef.Named)?.name) {
            "Byte" -> NumericSuffix.BYTE
            "UByte" -> NumericSuffix.UBYTE
            "Short" -> NumericSuffix.SHORT
            "UShort" -> NumericSuffix.USHORT
            "UInt" -> NumericSuffix.UINT
            "Long" -> NumericSuffix.LONG
            "ULong" -> NumericSuffix.ULONG
            "Cent" -> NumericSuffix.CENT
            "UCent" -> NumericSuffix.UCENT
            "Float" -> NumericSuffix.FLOAT
            "Decimal" -> NumericSuffix.DECIMAL
            else -> return default
        }
        return literal.copy(suffix = suffix)
    }

    private fun expandPack(mangled: String, template: TopLevel.Pack, args: List<TypeRef>): TopLevel.Pack {
        val enforce = template.annotations.any { it.name == "EnforceNumFields" }
        // A variadic pack generates its fields from the element types; a pack with
        // conditional fields selects from its own, and PackSpecializer is the single
        // authority for that selection.
        val fields = if (template.variadicParam != null) {
            expandFields(template, args)
        } else {
            val key = specializer.keyForRefs(template, args)
            if (key == null) {
                template.fields.map { it.copy(type = rewriteType(it.type)) }
            } else {
                val substitution = template.typeParams.zip(args).toMap()
                specializer.specializeForRefs(key, template, args).fields.map {
                    val fieldType = rewriteType(substituteParams(it.type, substitution))
                    it.copy(
                        type = fieldType,
                        default = it.default?.let { d -> retypeIntDefault(d, fieldType) },
                        condition = null,
                    )
                }
            }
        }
        return TopLevel.Pack(
            name = mangled,
            fields = fields,
            typeParams = emptyList(),
            line = template.line,
            column = template.column,
            annotations = if (enforce) listOf(Annotation("EnforceNumFields")) else template.annotations,
            visibility = template.visibility,
        )
    }

    /** Expands `inline for Ty in ...T with index { … }` over [args] into concrete fields. */
    private fun expandFields(template: TopLevel.Pack, args: List<TypeRef>): List<PackField> {
        val tpl = template.fieldTemplate
        if (tpl == null) return template.fields.map { it.copy(type = rewriteType(it.type)) }
        val out = template.fields.mapTo(mutableListOf()) { it.copy(type = rewriteType(it.type)) }
        for ((i, argType) in args.withIndex()) {
            for (f in tpl.fields) {
                val name = if (f.name.startsWith("\$index")) i.toString() else f.name
                val type = rewriteType(substituteLoopVar(f.type, tpl.loopVar, argType))
                out.add(PackField(name, type, mutable = true, default = null))
            }
            // `mixin "$index: $Ty"` - interpolate the comptime bindings, parse as a field.
            for (mixin in tpl.mixins) {
                val field = parseMixinField(renderMixin(mixin, tpl, i, argType))
                out.add(PackField(field.name, rewriteType(field.type), mutable = true, default = null))
            }
        }
        return out
    }

    /** Renders a mixin string template with the per-iteration comptime bindings. */
    private fun renderMixin(tpl: Expr.StringTemplate, template: VariadicFieldTemplate, index: Int, argType: TypeRef): String {
        val sb = StringBuilder()
        for (part in tpl.parts) {
            when (part) {
                is Expr.StringTemplatePart.Literal -> sb.append(part.text)
                is Expr.StringTemplatePart.Expr -> {
                    val id = (part.expr as? Expr.Identifier)?.name
                    val value = when (id) {
                        "index" -> index.toString()
                        template.loopVar -> renderType(argType)
                        else -> error("mixin references unknown comptime '\$$id' in variadic template")
                    }
                    sb.append(value)
                }
            }
        }
        return sb.toString()
    }

    /** Source-level rendering of a [TypeRef] (inverse of parsing), for mixin interpolation. */
    private fun renderType(type: TypeRef): String = when (type) {
        is TypeRef.Named -> type.name + if (type.args.isEmpty()) "" else type.args.joinToString(", ", "<", ">") { renderType(it) }
        is TypeRef.Array -> "[${renderType(type.element)}]"
        is TypeRef.Map -> "Map<${renderType(type.key)}, ${renderType(type.value)}>"
        is TypeRef.Set -> "Set<${renderType(type.element)}>"
        is TypeRef.Tuple -> type.elements.joinToString(", ", "(", ")") { renderType(it) }
        is TypeRef.Nullable -> "${renderType(type.inner)}?"
        is TypeRef.Pointer -> "${renderType(type.inner)}*"
        is TypeRef.Function -> buildString {
            append(type.kind.surfaceName)
            if (type.receivers.isNotEmpty()) {
                append(type.receivers.joinToString(", ", "[", "]") { renderType(it) })
            }
            if (type.params.isNotEmpty()) {
                append(type.params.joinToString(", ", "(", ")") { renderType(it) })
            }
            append(" -> ")
            append(renderType(type.ret))
        }
        is TypeRef.Failable -> if (type.errSets.size == 1) {
            "${renderType(type.ok)}!${type.errSets.single()}"
        } else {
            "${renderType(type.ok)}![${type.errSets.joinToString(", ")}]"
        }
        is TypeRef.Reference -> "${type.kind.spelling} ${renderType(type.inner)}"
        is TypeRef.Const -> type.value.toString()
    }

    /** Parses a rendered mixin string (e.g. `0: Int`) as a pack [PackField]. */
    private fun parseMixinField(rendered: String): PackField {
        val src = "@EnforceNumFields\npack __mixin { $rendered }"
        val program = Parser(Lexer(src).tokenize(), internalSource = true).parse()
        val pack = program.items.filterIsInstance<TopLevel.Pack>().firstOrNull()
            ?: error("mixin '$rendered' did not produce a field")
        return pack.fields.first()
    }

    private fun substituteLoopVar(type: TypeRef, loopVar: String, replacement: TypeRef): TypeRef = when (type) {
        is TypeRef.Named -> if (type.name == loopVar && type.args.isEmpty()) replacement
            else type.copy(args = type.args.map { substituteLoopVar(it, loopVar, replacement) })
        is TypeRef.Array -> type.copy(element = substituteLoopVar(type.element, loopVar, replacement))
        is TypeRef.Map -> type.copy(key = substituteLoopVar(type.key, loopVar, replacement), value = substituteLoopVar(type.value, loopVar, replacement))
        is TypeRef.Set -> type.copy(element = substituteLoopVar(type.element, loopVar, replacement))
        is TypeRef.Function -> type.copy(
            params = type.params.map { substituteLoopVar(it, loopVar, replacement) },
            ret = substituteLoopVar(type.ret, loopVar, replacement),
            receivers = type.receivers.map { substituteLoopVar(it, loopVar, replacement) },
        )
        is TypeRef.Tuple -> type.copy(elements = type.elements.map { substituteLoopVar(it, loopVar, replacement) })
        is TypeRef.Nullable -> type.copy(inner = substituteLoopVar(type.inner, loopVar, replacement))
        is TypeRef.Failable -> type.copy(ok = substituteLoopVar(type.ok, loopVar, replacement))
        is TypeRef.Pointer -> type.copy(inner = substituteLoopVar(type.inner, loopVar, replacement))
        is TypeRef.Reference -> type.copy(inner = substituteLoopVar(type.inner, loopVar, replacement))
        is TypeRef.Const -> type
    }

    private fun expandFunc(mangled: String, template: FuncDecl, elementTypes: List<TypeRef>, packMangled: String): TopLevel.Func {
        val params = elementTypes.mapIndexed { i, ty -> Param("_$i", rewriteType(ty)) }
        val returnType = TypeAnnotation.Explicit(TypeRef.Named(packMangled))
        val ctorArgs = elementTypes.mapIndexed { i, _ -> Expr.Identifier("_$i", template.line, template.column, 2) }
        val body = listOf(Stmt.Return(Expr.Call(packMangled, ctorArgs, template.line, template.column, 1), template.line, template.column, 0))
        return TopLevel.Func(FuncDecl(
            name = mangled,
            params = params,
            returnType = returnType,
            body = body,
            isInline = false,
            typeParams = emptyList(),
            line = template.line,
            column = template.column,
        ))
    }

    /** Materializes generic impl templates once for every concrete variadic pack. */
    /**
     * Expands `inline for … in reflect<Self>.fields` inside an impl on a pack that is
     * not monomorphised.
     *
     * A monomorphised pack goes through [expandImpls], which knows the specialization.
     * An ordinary pack's fields are already concrete, so the same expansion applies -
     * this is the only difference between the two cases.
     */
    fun expandPlainImplReflection(item: TopLevel, program: Program): TopLevel {
        if (item !is TopLevel.Impl) return item
        if (item.typeName in packTemplates) return item
        val pack = program.items.filterIsInstance<TopLevel.Pack>().firstOrNull { it.name == item.typeName }
            ?: return item
        if (pack.fields.isEmpty()) return item
        return item.copy(
            methods = item.methods.map { it.copy(body = expandReflectedFields(it.body, pack.fields)) },
        )
    }

    /**
     * Emits one copy of each member that is generic over its own const parameters.
     *
     * `oper * <N: Int> [self: Int&](rhs: Vec<Ty, N>&)` is not a member of a
     * monomorphised pack - it lives on `Int` - yet its body depends on `N`, which
     * only a `Vec` specialization fixes. So the specializations are what drive it:
     * one copy per `Vec<Ty, N>` that exists, with `N` bound.
     *
     * The copies are told apart by their operand, which is what
     * [SymbolTable.lookupOperator] resolves on: `Int * Vec<Int,2>` and
     * `Int * Vec<Int,3>` reach different members rather than colliding.
     */
    fun expandConstGenericMembers(item: TopLevel): TopLevel {
        if (item !is TopLevel.Impl) return item
        // A member of a monomorphised pack is expanded by expandImpls instead.
        if (item.typeName in packTemplates) return item
        if (item.methods.none { it.constParams.isNotEmpty() }) return item
        val methods = item.methods.flatMap { method ->
            if (method.constParams.isEmpty()) return@flatMap listOf(method)
            val application = signatureApplications(method).firstOrNull { application ->
                application.name in packTemplates &&
                    application.args.any { (it as? TypeRef.Named)?.name in method.constParams }
            } ?: return@flatMap listOf(method)
            val template = packTemplates[application.name] ?: return@flatMap listOf(method)
            val copies = packArguments.entries
                .filter { (mangled, _) -> mangled.startsWith("__${application.name}_") }
                .mapNotNull { (mangled, concrete) ->
                    val bindings = matchApplication(application, template, concrete)
                        ?: return@mapNotNull null
                    specializeConstGenericMember(method, bindings + (application.name to TypeRef.Named(mangled)), mangled)
                }
            // With no specialization to bind it, the member cannot be emitted at all -
            // its body asks for a layout nothing has chosen.
            copies.ifEmpty { emptyList() }
        }
        return item.copy(methods = methods)
    }

    /** Every named type application a member's signature mentions. */
    private fun signatureApplications(method: FuncDecl): List<TypeRef.Named> {
        val found = mutableListOf<TypeRef.Named>()
        fun visit(ref: TypeRef) {
            when (ref) {
                is TypeRef.Named -> {
                    if (ref.args.isNotEmpty()) found.add(ref)
                    ref.args.forEach(::visit)
                }
                is TypeRef.Reference -> visit(ref.inner)
                is TypeRef.Pointer -> visit(ref.inner)
                is TypeRef.Nullable -> visit(ref.inner)
                is TypeRef.Failable -> visit(ref.ok)
                is TypeRef.Array -> visit(ref.element)
                else -> {}
            }
        }
        method.params.forEach { visit(it.type) }
        (method.returnType as? TypeAnnotation.Explicit)?.let { visit(it.ref) }
        return found
    }

    /**
     * Binds an application's parameters from one concrete specialization.
     *
     * `Vec<Ty, N>` against `Vec<Int, 2>` binds `N` to 2 - and only when `Ty` really is
     * `Int`, so a member written for one element type is not emitted for another.
     */
    private fun matchApplication(
        application: TypeRef.Named,
        template: TopLevel.Pack,
        concrete: List<TypeRef>,
    ): Map<String, TypeRef>? {
        if (application.args.size != concrete.size) return null
        val bindings = mutableMapOf<String, TypeRef>()
        application.args.forEachIndexed { index, argument ->
            val actual = concrete[index]
            val name = (argument as? TypeRef.Named)?.takeIf { it.args.isEmpty() }?.name
            when {
                name != null && name in template.constParams -> return@forEachIndexed
                name != null && bindings[name].let { it == null || it == actual } -> bindings[name] = actual
                argument.toString() != actual.toString() -> return null
            }
        }
        // A const parameter of the *member* takes its value from the same position.
        template.typeParams.forEachIndexed { index, parameter ->
            if (parameter !in template.constParams) return@forEachIndexed
            val argument = application.args.getOrNull(index) as? TypeRef.Named ?: return@forEachIndexed
            if (argument.args.isEmpty()) bindings[argument.name] = concrete[index]
        }
        return bindings
    }

    /** One copy of a const-generic member, with its parameters bound. */
    private fun specializeConstGenericMember(
        method: FuncDecl,
        bindings: Map<String, TypeRef>,
        mangled: String,
    ): FuncDecl {
        constBindings = bindings.mapNotNull { (name, ref) ->
            (ref as? TypeRef.Const)?.let { name to it.value }
        }.toMap()
        typeBindings = bindings
        return try {
            val bound = method.copy(
                params = method.params.map { it.copy(type = substituteParams(it.type, bindings)) },
                returnType = substituteParams(method.returnType, bindings),
                typeParams = method.typeParams.filterNot { it in bindings },
                constParams = method.constParams.filterNot { it in bindings }.toSet(),
                body = expandReflectedFields(method.body, packs[mangled]?.fields.orEmpty()),
                // Operand-keyed, so the copies coexist on the same receiver.
                name = if (method.name.startsWith("oper")) "${method.name}@$mangled" else method.name,
            )
            rewriteFuncDecl(bound)
        } finally {
            constBindings = emptyMap()
            typeBindings = emptyMap()
        }
    }

    /** The pack a parser-mangled static member belongs to, or null. */
    private fun staticMemberOwner(item: TopLevel): String? {
        val name = when (item) {
            is TopLevel.Func -> item.decl.name
            is TopLevel.FinDecl -> item.name
            is TopLevel.VarDecl -> item.name
            is TopLevel.LetDecl -> item.name
            else -> return null
        }
        return packTemplates.keys.firstOrNull { name.startsWith("${it}__") }
    }

    /** True for a static member of a pack that is monomorphised. */
    fun isStaticTemplateMember(item: TopLevel): Boolean = staticMemberOwner(item) != null

    /** Renames a static member onto one specialization of its pack. */
    private fun renameStaticMember(item: TopLevel, owner: String, mangled: String): TopLevel {
        fun rename(name: String) = mangled + name.removePrefix(owner)
        return when (item) {
            is TopLevel.Func -> item.copy(decl = item.decl.copy(name = rename(item.decl.name)))
            is TopLevel.FinDecl -> item.copy(name = rename(item.name))
            is TopLevel.VarDecl -> item.copy(name = rename(item.name))
            is TopLevel.LetDecl -> item.copy(name = rename(item.name))
            else -> item
        }
    }

    /**
     * Emits every static member once per specialization of the pack it is on.
     *
     * `impl Vec<T, N>:: { fin zero = Vec<T, N>(0) }` describes one value per layout,
     * not one value: `Vec<Int, 2>::zero` and `Vec<Int, 3>::zero` differ in type and
     * in field count. Each copy has the pack's parameters bound, so `N` is a number
     * the compile-time machinery can act on rather than a name.
     */
    fun expandStaticMembers(templates: List<TopLevel>): List<TopLevel> {
        return templates.flatMap { template ->
            val owner = staticMemberOwner(template) ?: return@flatMap emptyList()
            val declaration = packTemplates[owner] ?: return@flatMap emptyList()
            packArguments.entries
                .filter { (mangled, _) -> mangled.startsWith("__${owner}_") }
                .mapNotNull { (mangled, arguments) ->
                    val bindings = declaration.typeParams.zip(arguments).toMap()
                    val bound = renameStaticMember(substituteStaticMember(template, bindings), owner, mangled)
                    // The bodies read `N` as a value, so the const arguments are in
                    // scope for the rewrite that expands their compile-time loops.
                    constBindings = bindings.mapNotNull { (name, ref) ->
                        (ref as? TypeRef.Const)?.let { name to it.value }
                    }.toMap()
                    typeBindings = bindings
                    // One static member may call another (`fin right = axis<0>()`,
                    // spelled `Vec__axis` by then). Inside a copy, those names mean
                    // this specialization's members, not the template's.
                    val previousAlias = staticAliases.put(owner, mangled)
                    try {
                        rewriteTopLevel(bound)
                    } finally {
                        constBindings = emptyMap()
                        typeBindings = emptyMap()
                        if (previousAlias == null) staticAliases.remove(owner) else staticAliases[owner] = previousAlias
                    }
                }
        }
    }

    /** Binds the pack's parameters throughout one static member's signature. */
    private fun substituteStaticMember(item: TopLevel, bindings: Map<String, TypeRef>): TopLevel = when (item) {
        is TopLevel.Func -> item.copy(
            decl = item.decl.copy(
                params = item.decl.params.map { it.copy(type = substituteParams(it.type, bindings)) },
                returnType = substituteParams(item.decl.returnType, bindings),
                typeParams = item.decl.typeParams.filterNot { it in bindings },
            ),
        )
        is TopLevel.FinDecl -> item.copy(type = item.type?.let { substituteParams(it, bindings) })
        is TopLevel.VarDecl -> item.copy(type = item.type?.let { substituteParams(it, bindings) })
        is TopLevel.LetDecl -> item.copy(type = item.type?.let { substituteParams(it, bindings) })
        else -> item
    }

    fun expandImpls(): List<TopLevel.Impl> = packArguments.entries.flatMap { (mangled, arguments) ->
        val templateName = packTemplates.keys.firstOrNull { mangled.startsWith("__${it}_") }
            ?: return@flatMap emptyList()
        val struct = packs[mangled] ?: return@flatMap emptyList()
        // The specialization binds the pack's parameters, and its impl's members are
        // written against those same names - `ctor (all: T)` on `Vec<Int, 3>` takes an
        // Int. Without this the member would keep the template's `T`, which names
        // nothing once the pack is concrete.
        val packTemplate = packTemplates[templateName]
        // The pack's own name binds to this specialization as well: the parser has
        // already turned `Self` into `Vec` by the time an impl gets here, so a member
        // returning `Self` must still land on `Vec<Float, 2>` and not the template.
        val argumentBindings = packTemplate?.typeParams.orEmpty().zip(arguments).toMap() +
            (templateName to TypeRef.Named(mangled)) +
            ("Self" to TypeRef.Named(mangled))
        // A member's own `where` narrows which specializations have it: `cross` exists
        // on a 3-vector and nowhere else, so it is not emitted where its clause fails.
        val constraintBindings = packTemplate?.typeParams.orEmpty()
            .zip(arguments)
            .mapNotNull { (parameter, argument) ->
                ConstraintEvaluator.bindingOf(argument)?.let { parameter to it }
            }
            .toMap()
        implTemplates[templateName].orEmpty().map { template ->
            val methods = template.methods.filterNot { method ->
                ConstraintEvaluator.evaluate(method.whereClause, constraintBindings, null) is
                    ConstraintEvaluator.Outcome.Violated
            }.map { method ->
                val selfType = TypeRef.Named(mangled)
                // A member's own type parameters shadow the pack's - but only the ones
                // it really declares: the impl's parameters are carried on its members
                // too, and those are exactly what the specialization binds.
                val ownTypeParams = method.typeParams.toSet() - template.typeParams.toSet() -
                    packTemplate?.typeParams.orEmpty().toSet()
                val bindings = argumentBindings.filterKeys { it !in ownTypeParams }
                // Bound before the body is touched: reflection over an explicit
                // application (`reflect<Vec<U, N>>`) needs `N` to pick a layout, and
                // that happens while the reflected loops expand, not after.
                constBindings = argumentBindings.mapNotNull { (name, ref) ->
                    (ref as? TypeRef.Const)?.let { name to it.value }
                }.toMap()
                typeBindings = bindings
                val specialized = method.copy(
                    params = method.params.map {
                        it.copy(type = substituteParams(substituteSelf(it.type, selfType), bindings))
                    },
                    returnType = substituteParams(substituteSelf(method.returnType, selfType), bindings),
                    body = expandReflectedFields(method.body, struct.fields),
                    typeParams = method.typeParams.filterNot {
                        it == template.variadicParam || it in argumentBindings
                    },
                    variadicParam = method.variadicParam?.takeUnless {
                        template.variadicParam != null && it == template.variadicParam
                    },
                )
                try {
                    rewriteFuncDecl(specialized)
                } finally {
                    constBindings = emptyMap()
                    typeBindings = emptyMap()
                }
            }
            template.copy(
                typeName = mangled,
                methods = methods,
                traitArgs = template.traitArgs.map { substituteSelf(it, TypeRef.Named(mangled)) },
                typeParams = emptyList(),
                variadicParam = null,
            )
        }
    }

    private fun substituteParams(annotation: TypeAnnotation, bindings: Map<String, TypeRef>): TypeAnnotation =
        if (annotation is TypeAnnotation.Explicit) {
            TypeAnnotation.Explicit(substituteParams(annotation.ref, bindings))
        } else {
            annotation
        }

    private fun substituteSelf(annotation: TypeAnnotation, selfType: TypeRef): TypeAnnotation =
        if (annotation is TypeAnnotation.Explicit) TypeAnnotation.Explicit(substituteSelf(annotation.ref, selfType)) else annotation

    private fun substituteSelf(type: TypeRef, selfType: TypeRef): TypeRef = when (type) {
        is TypeRef.Named -> if (type.name == "Self" && type.args.isEmpty()) selfType
            else type.copy(args = type.args.map { substituteSelf(it, selfType) })
        is TypeRef.Array -> type.copy(element = substituteSelf(type.element, selfType))
        is TypeRef.Map -> type.copy(key = substituteSelf(type.key, selfType), value = substituteSelf(type.value, selfType))
        is TypeRef.Set -> type.copy(element = substituteSelf(type.element, selfType))
        is TypeRef.Function -> type.copy(
            params = type.params.map { substituteSelf(it, selfType) },
            ret = substituteSelf(type.ret, selfType),
            receivers = type.receivers.map { substituteSelf(it, selfType) },
        )
        is TypeRef.Tuple -> type.copy(elements = type.elements.map { substituteSelf(it, selfType) })
        is TypeRef.Nullable -> type.copy(inner = substituteSelf(type.inner, selfType))
        is TypeRef.Failable -> type.copy(ok = substituteSelf(type.ok, selfType))
        is TypeRef.Pointer -> type.copy(inner = substituteSelf(type.inner, selfType))
        is TypeRef.Reference -> type.copy(inner = substituteSelf(type.inner, selfType))
        is TypeRef.Const -> type
    }

    private data class FieldBinding(
        val variable: String,
        val indexName: String?,
        val index: Int,
        val field: PackField,
    )

    private fun reflectedSelfFields(expr: Expr): Boolean = reflectedFieldsOf(expr) != null

    /**
     * The `__reflect(X).fields` call [expr] is, or null when it is something else.
     *
     * `X` is `Self` inside an impl, or a named pack - possibly applied, as in
     * `reflect<Vec<U, N>>`, where the arguments choose the layout being asked about.
     */
    private fun reflectedFieldsOf(expr: Expr): Expr.Call? {
        val member = expr as? Expr.Member ?: return null
        if (member.name != "fields") return null
        val call = (member.target as? Expr.Grouping)?.expr ?: member.target
        if (call !is Expr.Call || call.callee != "__reflect") return null
        val name = (call.args.singleOrNull() as? Expr.Identifier)?.name ?: return null
        return if (name == "Self" || name in packTemplates || name in plainPackFields) call else null
    }

    /**
     * The fields the reflected type in [expr] has, or [fallback] for `Self`.
     *
     * A named pack answers for itself: `reflect<Vec<U, N>>` inside an impl on
     * `Vec<T, N>` is a different type with the same layout family, and `N` is what
     * decides which member of it. An argument that is still a parameter leaves its
     * conditions undecided, which keeps the field rather than dropping it.
     */
    private fun reflectedFields(expr: Expr, fallback: List<PackField>): List<PackField> {
        val call = reflectedFieldsOf(expr) ?: return fallback
        val name = (call.args.singleOrNull() as? Expr.Identifier)?.name ?: return fallback
        if (name == "Self") return fallback
        val template = packTemplates[name] ?: return plainPackFields[name] ?: fallback
        val arguments = call.typeArgs.map { substituteParams(it, typeBindings) }
        val bindings = template.typeParams.zip(arguments)
            .mapNotNull { (parameter, argument) ->
                ConstraintEvaluator.bindingOf(argument)?.let { parameter to it }
            }
            .toMap()
        return template.fields.filter {
            ConstraintEvaluator.evaluate(it.condition, bindings, null) !is
                ConstraintEvaluator.Outcome.Violated
        }
    }

    private fun expandReflectedFields(body: List<Stmt>, fields: List<PackField>): List<Stmt> {
        currentFields = fields
        return expandReflectedFieldsInner(body, fields)
    }

    private fun expandReflectedFieldsInner(body: List<Stmt>, fields: List<PackField>): List<Stmt> = body.flatMap { stmt ->
        if (stmt is Stmt.InlineFor && reflectedSelfFields(stmt.iterable)) {
            reflectedFields(stmt.iterable, fields).flatMapIndexed { index, field ->
                val binding = FieldBinding(stmt.name, stmt.indexName, index, field)
                val iteration = stmt.body.flatMap { expandReflectedStmt(it, fields, binding) }
                // Each iteration is its own scope, as a runtime loop body would be:
                // a `fin value = …` inside the loop is one binding per field, not one
                // name declared as many times as the pack has fields.
                if (iteration.any { it is Stmt.FinDecl || it is Stmt.VarDecl || it is Stmt.LetDecl }) {
                    listOf(Stmt.Scope(iteration, stmt.line, stmt.column))
                } else {
                    iteration
                }
            }
        } else {
            expandReflectedStmt(stmt, fields, null)
        }
    }

    private fun expandReflectedStmt(stmt: Stmt, fields: List<PackField>, binding: FieldBinding?): List<Stmt> {
        fun expr(value: Expr) = substituteReflectedExpr(value, binding)
        fun nested(items: List<Stmt>) = items.flatMap { expandReflectedStmt(it, fields, binding) }
        val rewritten = when (stmt) {
            is Stmt.VarDecl -> stmt.copy(type = substituteSelf(stmt.type, TypeRef.Named("Self")), initializer = expr(stmt.initializer))
            is Stmt.FinDecl -> stmt.copy(type = substituteSelf(stmt.type, TypeRef.Named("Self")), initializer = expr(stmt.initializer))
            is Stmt.LetDecl -> stmt.copy(type = substituteSelf(stmt.type, TypeRef.Named("Self")), initializer = expr(stmt.initializer))
            is Stmt.InlineVar -> stmt.copy(initializer = expr(stmt.initializer))
            is Stmt.InlineFin -> stmt.copy(initializer = expr(stmt.initializer))
            is Stmt.InlineLet -> stmt.copy(initializer = expr(stmt.initializer))
            is Stmt.RemDecl -> stmt.copy(initializer = expr(stmt.initializer))
            is Stmt.Assignment -> stmt.copy(value = expr(stmt.value))
            is Stmt.InlineAssignment -> stmt.copy(value = expr(stmt.value))
            is Stmt.IndexAssign -> stmt.copy(target = expr(stmt.target), index = expr(stmt.index), value = expr(stmt.value))
            is Stmt.MemberAssign -> {
                val folded = stmt.nameExpr?.let { n -> binding?.let { foldFieldNameExpr(n, it) } }
                stmt.copy(
                    target = expr(stmt.target),
                    value = expr(stmt.value),
                    name = folded ?: stmt.name,
                    nameExpr = if (folded != null) null else stmt.nameExpr,
                )
            }
            is Stmt.DerefAssign -> stmt.copy(target = expr(stmt.target), value = expr(stmt.value))
            is Stmt.Return -> stmt.copy(value = stmt.value?.let(::expr))
            is Stmt.ExprStmt -> stmt.copy(expr = expr(stmt.expr))
            is Stmt.Throw -> stmt.copy(value = expr(stmt.value))
            is Stmt.Panic -> stmt.copy(message = expr(stmt.message))
            is Stmt.Yield -> stmt.copy(value = expr(stmt.value))
            is Stmt.Assert -> stmt.copy(condition = expr(stmt.condition), message = expr(stmt.message))
            is Stmt.InlineAssert -> stmt.copy(condition = expr(stmt.condition), message = expr(stmt.message))
            is Stmt.Trace -> stmt.copy(message = expr(stmt.message), level = stmt.level?.let(::expr))
            is Stmt.InlineTrace -> stmt.copy(message = expr(stmt.message), level = stmt.level?.let(::expr))
            is Stmt.If -> stmt.copy(condition = expr(stmt.condition), thenBranch = nested(stmt.thenBranch), elseBranch = stmt.elseBranch?.let(::nested))
            is Stmt.InlineIf -> stmt.copy(condition = expr(stmt.condition), thenBranch = nested(stmt.thenBranch), elseBranch = stmt.elseBranch?.let(::nested))
            is Stmt.DeepInlineIf -> stmt.copy(condition = expr(stmt.condition), thenBranch = nested(stmt.thenBranch), elseBranch = stmt.elseBranch?.let(::nested))
            is Stmt.While -> stmt.copy(condition = expr(stmt.condition), body = nested(stmt.body))
            is Stmt.For -> stmt.copy(iterable = expr(stmt.iterable), step = stmt.step?.let(::expr), body = nested(stmt.body))
            is Stmt.InlineFor -> {
                if (reflectedSelfFields(stmt.iterable)) return expandReflectedFields(listOf(stmt), fields)
                stmt.copy(iterable = expr(stmt.iterable), body = nested(stmt.body))
            }
            is Stmt.Loop -> stmt.copy(iterable = stmt.iterable?.let(::expr), body = nested(stmt.body))
            is Stmt.When -> stmt.copy(scrutinee = expr(stmt.scrutinee), branches = stmt.branches.map { branch ->
                branch.copy(patterns = branch.patterns.map(::expr), body = nested(branch.body))
            }, elseBranch = stmt.elseBranch?.let(::nested))
            is Stmt.Try -> stmt.copy(body = nested(stmt.body), catchBody = stmt.catchBody?.let(::nested))
            is Stmt.Defer -> stmt.copy(body = nested(stmt.body))
            is Stmt.Scope -> stmt.copy(body = nested(stmt.body))
            is Stmt.InlineBlock -> stmt.copy(body = nested(stmt.body))
            is Stmt.DeepInlineBlock -> stmt.copy(body = nested(stmt.body))
            is Stmt.Effect -> stmt.copy(
                dependencies = stmt.dependencies?.map(::expr),
                body = nested(stmt.body),
            )
            is Stmt.WithContext -> stmt.copy(values = stmt.values.map(::expr), body = nested(stmt.body))
            is Stmt.NoInline -> stmt.copy(stmt = expandReflectedStmt(stmt.stmt, fields, binding).single())
            is Stmt.Break, is Stmt.Continue -> stmt
        }
        return listOf(rewritten)
    }

    /**
     * The field name a spliced name expression denotes, or null when it names
     * something else.
     *
     * `${f.name}` is the field's own name; `${f.value}` would be its value, which is
     * not a name, so only `.name` folds here.
     */
    private fun foldFieldNameExpr(nameExpr: Expr, binding: FieldBinding): String? {
        val member = nameExpr as? Expr.Member ?: return null
        if (member.name != "name") return null
        if ((member.target as? Expr.Identifier)?.name != binding.variable) return null
        return binding.field.name
    }

    /** One argument, or several when it is an `inline for` over the reflected fields. */
    private fun expandArgument(arg: Expr, binding: FieldBinding?): List<Expr> {
        if (arg !is Expr.InlineForArgs || !reflectedSelfFields(arg.iterable)) {
            return listOf(substituteReflectedExpr(arg, binding))
        }
        return reflectedFields(arg.iterable, currentFields).mapIndexed { index, field ->
            substituteReflectedExpr(arg.body, FieldBinding(arg.name, null, index, field))
        }
    }

    /** The fields of the pack currently being expanded, for argument splats. */
    private var currentFields: List<PackField> = emptyList()

    private fun substituteReflectedExpr(expr: Expr, binding: FieldBinding?): Expr {
        if (binding != null) {
            if (expr is Expr.Identifier && expr.name == binding.indexName) {
                return Expr.IntLiteral(binding.index.toLong(), expr.line, expr.column, expr.length)
            }
            // `self.${f.name}` - a spliced member name folds to the field it names, so
            // everything after this sees an ordinary member access.
            if (expr is Expr.Member && expr.nameExpr != null) {
                val folded = foldFieldNameExpr(expr.nameExpr, binding)
                if (folded != null) {
                    return Expr.Member(
                        substituteReflectedExpr(expr.target, binding),
                        folded,
                        expr.line,
                        expr.column,
                        expr.length,
                    )
                }
            }
            if (expr is Expr.Member && expr.name == "value" &&
                (expr.target as? Expr.Identifier)?.name == binding.variable
            ) {
                return Expr.Member(
                    Expr.Identifier("self", expr.line, expr.column, 4),
                    binding.field.name,
                    expr.line,
                    expr.column,
                    expr.length,
                )
            }
        }
        return when (expr) {
            is Expr.Binary -> expr.copy(left = substituteReflectedExpr(expr.left, binding), right = substituteReflectedExpr(expr.right, binding))
            // `Self(inline for f in reflect<Self>.fields { … })` - the loop becomes one
            // argument per field, so a constructor is written once whatever the layout.
            is Expr.Call -> expr.copy(args = expr.args.flatMap { arg -> expandArgument(arg, binding) })
            is Expr.Unary -> expr.copy(operand = substituteReflectedExpr(expr.operand, binding))
            is Expr.Grouping -> expr.copy(expr = substituteReflectedExpr(expr.expr, binding))
            is Expr.Range -> expr.copy(from = substituteReflectedExpr(expr.from, binding), to = substituteReflectedExpr(expr.to, binding))
            is Expr.ArrayLiteral -> expr.copy(elements = expr.elements.map { substituteReflectedExpr(it, binding) })
            is Expr.SetLiteral -> expr.copy(elements = expr.elements.map { substituteReflectedExpr(it, binding) })
            is Expr.Index -> expr.copy(target = substituteReflectedExpr(expr.target, binding), index = substituteReflectedExpr(expr.index, binding))
            is Expr.Member -> expr.copy(target = substituteReflectedExpr(expr.target, binding))
            is Expr.MethodCall -> expr.copy(target = substituteReflectedExpr(expr.target, binding), args = expr.args.map { substituteReflectedExpr(it, binding) })
            is Expr.StringTemplate -> expr.copy(parts = expr.parts.map { part ->
                if (part is Expr.StringTemplatePart.Expr) Expr.StringTemplatePart.Expr(substituteReflectedExpr(part.expr, binding)) else part
            })
            is Expr.TupleLit -> expr.copy(elements = expr.elements.map { substituteReflectedExpr(it, binding) })
            is Expr.VariantLit -> expr.copy(elements = expr.elements.map { substituteReflectedExpr(it, binding) })
            is Expr.TupleAccess -> expr.copy(target = substituteReflectedExpr(expr.target, binding))
            is Expr.CatchExpr -> expr.copy(expr = substituteReflectedExpr(expr.expr, binding), fallback = substituteReflectedExpr(expr.fallback, binding))
            is Expr.TryPropagate -> expr.copy(expr = substituteReflectedExpr(expr.expr, binding))
            is Expr.IfExpr -> expr.copy(condition = substituteReflectedExpr(expr.condition, binding), thenExpr = substituteReflectedExpr(expr.thenExpr, binding), elseExpr = substituteReflectedExpr(expr.elseExpr, binding))
            is Expr.Lambda -> expr.copy(body = expr.body.flatMap { expandReflectedStmt(it, emptyList(), binding) })
            is Expr.NamedArg -> expr.copy(value = substituteReflectedExpr(expr.value, binding))
            is Expr.NullCoalesce -> expr.copy(left = substituteReflectedExpr(expr.left, binding), right = substituteReflectedExpr(expr.right, binding))
            is Expr.SafeMember -> expr.copy(target = substituteReflectedExpr(expr.target, binding))
            is Expr.Cast -> expr.copy(expr = substituteReflectedExpr(expr.expr, binding))
            is Expr.IsCheck -> expr.copy(expr = substituteReflectedExpr(expr.expr, binding))
            is Expr.MapLit -> expr.copy(entries = expr.entries.map { (key, value) -> substituteReflectedExpr(key, binding) to substituteReflectedExpr(value, binding) })
            is Expr.Alloc -> expr.copy(value = substituteReflectedExpr(expr.value, binding))
            is Expr.AllocBuffer -> expr.copy(count = substituteReflectedExpr(expr.count, binding))
            is Expr.Deref -> expr.copy(target = substituteReflectedExpr(expr.target, binding))
            is Expr.Isolated -> expr.copy(value = substituteReflectedExpr(expr.value, binding))
            is Expr.Await -> expr.copy(value = substituteReflectedExpr(expr.value, binding))
            is Expr.Spread -> expr.copy(array = substituteReflectedExpr(expr.array, binding))
            else -> expr
        }
    }

    // ------------------------------------------------------------------
    // Rewriting
    // ------------------------------------------------------------------

    fun rewriteTopLevel(item: TopLevel): TopLevel? = withSourceLine(item.sourceLine()) {
        when (item) {
        // Drop variadic templates - they are replaced by their monomorphized instances.
        is TopLevel.Pack -> if (item.variadicParam != null) null
            else item.copy(fields = item.fields.map { it.copy(type = rewriteType(it.type)) })
        is TopLevel.Func -> if (item.decl.name in funcTemplates) null
            else item.copy(decl = rewriteFuncDecl(item.decl))
        // An impl on a monomorphised pack is a template: `expandImpls` emits one copy
        // per specialization, so the template itself must not survive - its members
        // still mention the pack's parameters and there is no type to check them on.
        is TopLevel.Impl -> if (item.typeName in packTemplates) null else item.copy(
            traitArgs = item.traitArgs.map(::rewriteType),
            decoratorArgs = item.decoratorArgs.map(::rewriteExpr),
            decoratorNamedArgs = item.decoratorNamedArgs.map { (name, value) -> name to rewriteExpr(value) },
            methods = item.methods.map(::rewriteFuncDecl),
        )
        // `typealias Vec2i = Vec<Int, 2>` names a specialization; leaving the
        // application unrewritten would make a later pass instantiate it a second
        // time, alongside the copy this pass already emitted.
        is TopLevel.TypeAlias -> if (item.typeParams.isEmpty()) item.copy(type = rewriteType(item.type)) else item
        is TopLevel.Test -> item.copy(body = item.body.map(::rewriteStmt))
        is TopLevel.VarDecl -> item.copy(type = item.type?.let(::rewriteType), initializer = rewriteExpr(item.initializer))
        is TopLevel.LetDecl -> item.copy(type = item.type?.let(::rewriteType), initializer = rewriteExpr(item.initializer))
        is TopLevel.FinDecl -> item.copy(type = item.type?.let(::rewriteType), initializer = rewriteExpr(item.initializer))
        is TopLevel.InlineVar -> item.copy(initializer = rewriteExpr(item.initializer))
        is TopLevel.InlineLet -> item.copy(initializer = rewriteExpr(item.initializer))
        is TopLevel.InlineFin -> item.copy(initializer = rewriteExpr(item.initializer))
        is TopLevel.InlineTrace -> item.copy(
            message = rewriteExpr(item.message),
            level = item.level?.let(::rewriteExpr),
        )
        else -> item
        }
    }

    private fun rewriteFuncDecl(decl: FuncDecl): FuncDecl = withSourceLine(decl.line) {
        bindings.clear()
        for (p in decl.params) bindings[p.name] = p.type
        decl.copy(
            params = decl.params.map(::rewriteParam),
            returnType = rewriteTypeAnnotation(decl.returnType),
            body = decl.body.map(::rewriteStmt),
        )
    }

    private fun rewriteParam(param: Param): Param {
        val rewritten = rewriteType(param.type)
        val reference = rewritten as? TypeRef.Reference
        return if (reference != null) {
            param.copy(
                type = reference.inner,
                modifier = if (param.modifier == ParamModifier.NONE) reference.kind.paramModifier else param.modifier,
            )
        } else {
            param.copy(type = rewritten)
        }
    }

    private fun rewriteTypeAnnotation(ann: TypeAnnotation): TypeAnnotation =
        if (ann is TypeAnnotation.Explicit) TypeAnnotation.Explicit(rewriteType(ann.ref)) else ann

    /**
     * Type arguments bound while a static member is specialized.
     *
     * Sits alongside [constBindings]: one binds `N` where it is read as a value, this
     * binds `T` and `N` where they are written as types, so `Vec<T, N>(0)` in a static
     * member names the specialization the copy belongs to.
     */
    private var typeBindings: Map<String, TypeRef> = emptyMap()

    fun rewriteType(ref: TypeRef): TypeRef = when (ref) {
        is TypeRef.Named -> when {
            ref.args.isEmpty() && ref.name in typeBindings -> rewriteType(typeBindings.getValue(ref.name))
            NamedTypeMacroCall.isCall(ref) -> expandNamedTypeMacro(ref)
            ref.name in packTemplates && ref.args.isNotEmpty() -> TypeRef.Named(instantiatePack(ref.name, ref.args))
            ref.args.isNotEmpty() -> ref.copy(args = ref.args.map(::rewriteType), variadic = false)
            else -> ref
        }
        is TypeRef.Array -> ref.copy(element = rewriteType(ref.element))
        is TypeRef.Map -> ref.copy(key = rewriteType(ref.key), value = rewriteType(ref.value))
        is TypeRef.Set -> ref.copy(element = rewriteType(ref.element))
        is TypeRef.Function -> ref.copy(
            params = ref.params.map(::rewriteType),
            ret = rewriteType(ref.ret),
            receivers = ref.receivers.map(::rewriteType),
        )
        // `(A, B)` in type position was grammar supplied by a `.Type` macro, and
        // `.Type` is gone. The type itself is not: write `Tuple<A, B>`.
        is TypeRef.Tuple -> error(
            "parenthesized tuple types were removed with 'macro .Type'; " +
                "write 'Tuple<${ref.elements.joinToString(", ") { it.displayName() }}>'",
        )
        is TypeRef.Nullable -> ref.copy(inner = rewriteType(ref.inner))
        is TypeRef.Failable -> ref.copy(ok = rewriteType(ref.ok))
        is TypeRef.Pointer -> ref.copy(inner = rewriteType(ref.inner))
        is TypeRef.Reference -> ref.copy(inner = rewriteType(ref.inner))
        is TypeRef.Const -> ref
    }

    private fun expandNamedTypeMacro(call: TypeRef.Named): TypeRef {
        val expectedKind = when (NamedTypeMacroCall.form(call)) {
            NamedTypeMacroCall.Form.Prefix -> TypeFormKind.PREFIX
            NamedTypeMacroCall.Form.List -> TypeFormKind.PREFIX_LIST
            NamedTypeMacroCall.Form.Infix -> TypeFormKind.INFIX
        }
        val name = NamedTypeMacroCall.name(call)
        val modifier = NamedTypeMacroCall.modifier(call)
        // `with,without|L2,S1,S1` - the words the use site wrote, and the shape
        // of each operand: how many types, and whether they were bracketed.
        val clauses = modifier.contains('|')
        val keywords = if (clauses) modifier.substringBefore('|').split(",") else emptyList()
        val shapes = if (clauses) modifier.substringAfter('|').split(",") else emptyList()
        val plain = if (clauses) "" else modifier
        val named = typeMacroRules.filter {
            it.name == name && it.prefix == plain && it.keywords == keywords &&
                (!clauses || it.holeIsList == shapes.map { shape -> shape.startsWith("L") }) &&
                (clauses || it.kind == expectedKind)
        }
        // A macro may have one arm per shape it accepts - `@with T` and
        // `@with [A, B]` are both infix `with`. The arm that fits the arguments
        // is the one meant: an exact arity wins, and the list arm takes the rest.
        val matches = if (clauses) {
            named
        } else {
            named.filter { it.holes.size == call.args.size && !it.listTail }
                .ifEmpty { named.filter { it.listTail && call.args.size >= it.holes.size - 1 } }
                .ifEmpty { named }
        }
        if (matches.isEmpty()) {
            val spelling = buildString {
                if (modifier.isNotEmpty()) append(modifier).append(' ')
                append(name)
            }
            error("undefined type macro '$spelling'; import the module that declares it")
        }
        if (matches.size > 1) {
            error("ambiguous type macro '$name': ${matches.size} matching rules are visible")
        }
        val rule = matches.single()
        val rewrittenArgs = call.args.map(::rewriteType)
        val bindings: Map<String, List<TypeRef>> = when {
            rule.kind == TypeFormKind.PREFIX_LIST && rule.holes.size == 1 ->
                mapOf(rule.holes.single() to rewrittenArgs)
            // `$Q @with [...$ITEMS]` - the leading holes take one argument each
            // and the last one takes everything that is left.
            // A clause sequence: each hole takes its own operand, sized by the
            // shape the use site wrote.
            rule.keywords.isNotEmpty() -> {
                var at = 0
                rule.holes.mapIndexed { index, hole ->
                    val count = shapes[index].drop(1).toIntOrNull() ?: 1
                    val slice = rewrittenArgs.subList(at, minOf(at + count, rewrittenArgs.size)).toList()
                    at += count
                    hole to slice
                }.toMap()
            }
            rule.listTail && rewrittenArgs.size >= rule.holes.size - 1 -> {
                val leading = rule.holes.dropLast(1)
                leading.mapIndexed { index, hole -> hole to listOf(rewrittenArgs[index]) }.toMap() +
                    mapOf(rule.holes.last() to rewrittenArgs.drop(leading.size))
            }
            rule.holes.size == rewrittenArgs.size ->
                rule.holes.zip(rewrittenArgs.map(::listOf)).toMap()
            else -> error(
                "type macro '$name' expects ${rule.holes.size} type argument(s), got ${rewrittenArgs.size}",
            )
        }
        return rewriteType(substituteTypeMacroTemplate(rule.template, bindings))
    }

    private fun expandShapeTypeMacro(kind: TypeFormKind, arguments: List<TypeRef>): TypeRef {
        val matches = typeMacroRules.filter {
            it.kind == kind && it.name == null && it.prefix.isEmpty()
        }
        if (matches.isEmpty()) {
            typeMacroError(
                "no visible 'meta .Type' rule matches the parenthesized type form; " +
                    "import the module that declares this grammar",
            )
        }
        if (matches.size > 1) {
            typeMacroError("ambiguous parenthesized type form: ${matches.size} matching 'meta .Type' rules are visible")
        }
        val rule = matches.single()
        val rewrittenArguments = arguments.map(::rewriteType)
        val bindings = when {
            rule.holes.size == 1 ->
                mapOf(rule.holes.single() to rewrittenArguments)
            rule.holes.size == rewrittenArguments.size ->
                rule.holes.zip(rewrittenArguments.map(::listOf)).toMap()
            else -> error(
                "parenthesized type form expects ${rule.holes.size} type argument(s), " +
                    "got ${rewrittenArguments.size}",
            )
        }
        return rewriteType(substituteTypeMacroTemplate(rule.template, bindings))
    }

    private fun substituteTypeMacroTemplate(
        type: TypeRef,
        bindings: Map<String, List<TypeRef>>,
    ): TypeRef = when (type) {
        is TypeRef.Named -> {
            val direct = bindings[type.name]
            if (direct != null) {
                val values = direct
                if (values.size != 1) {
                    error("variadic type-macro hole '${type.name}' must be expanded inside '<...${type.name}>'")
                }
                values.single()
            } else {
                val args = if (type.variadic) {
                    type.args.flatMap { argument ->
                        if (argument is TypeRef.Named && argument.name in bindings) {
                            bindings.getValue(argument.name)
                        } else {
                            listOf(substituteTypeMacroTemplate(argument, bindings))
                        }
                    }
                } else {
                    type.args.map { substituteTypeMacroTemplate(it, bindings) }
                }
                type.copy(args = args, variadic = false)
            }
        }
        is TypeRef.Array -> type.copy(element = substituteTypeMacroTemplate(type.element, bindings))
        is TypeRef.Map -> type.copy(
            key = substituteTypeMacroTemplate(type.key, bindings),
            value = substituteTypeMacroTemplate(type.value, bindings),
        )
        is TypeRef.Set -> type.copy(element = substituteTypeMacroTemplate(type.element, bindings))
        is TypeRef.Function -> type.copy(
            params = type.params.map { substituteTypeMacroTemplate(it, bindings) },
            ret = substituteTypeMacroTemplate(type.ret, bindings),
            receivers = type.receivers.map { substituteTypeMacroTemplate(it, bindings) },
        )
        is TypeRef.Tuple -> type.copy(elements = type.elements.map { substituteTypeMacroTemplate(it, bindings) })
        is TypeRef.Nullable -> type.copy(inner = substituteTypeMacroTemplate(type.inner, bindings))
        is TypeRef.Failable -> type.copy(ok = substituteTypeMacroTemplate(type.ok, bindings))
        is TypeRef.Pointer -> type.copy(inner = substituteTypeMacroTemplate(type.inner, bindings))
        is TypeRef.Reference -> type.copy(inner = substituteTypeMacroTemplate(type.inner, bindings))
        is TypeRef.Const -> type
    }

    /**
     * Const arguments bound while a static member is specialized.
     *
     * `N` is a type parameter in the declaration and a number in each specialization;
     * inside one, reading `N` must produce that number so `0..<N` is a range the
     * compile-time machinery can walk.
     */
    private var constBindings: Map<String, Long> = emptyMap()

    /** Alias name → the mangled specialization it names, for `Alias::member`. */
    private val staticAliases = mutableMapOf<String, String>()

    /**
     * Records which specialization each type alias names.
     *
     * `Alias::member` is spelled `Alias__member` by the time it reaches here, so the
     * alias has to be resolved before any body is rewritten - hence a pass of its
     * own, ahead of the rest.
     */
    fun resolveStaticAliases(items: List<TopLevel>) {
        for (item in items) {
            if (item !is TopLevel.TypeAlias || item.typeParams.isNotEmpty()) continue
            val target = item.type as? TypeRef.Named ?: continue
            // Monomorphization runs more than once. On a later pass the alias already
            // names the specialization, and it still has to map `Vec3f::zero` onto it.
            if (packTemplates.keys.any { target.name.startsWith("__${it}_") }) {
                staticAliases[item.name] = target.name
                continue
            }
            if (target.name !in packTemplates) continue
            val mangled = (rewriteType(target) as? TypeRef.Named)?.name ?: continue
            if (mangled != target.name) staticAliases[item.name] = mangled
        }
    }

    /** `Alias` and `Alias__member` become the specialization they name. */
    private fun throughStaticAlias(name: String): String {
        staticAliases[name]?.let { return it }
        val separator = name.indexOf("__")
        if (separator <= 0) return name
        val mangled = staticAliases[name.substring(0, separator)] ?: return name
        return mangled + name.substring(separator)
    }

    fun rewriteExpr(e: Expr): Expr = when (e) {
        // `T.typeName` - the bound type's own name, as a string. Folded here
        // because here is where `T` stops being a parameter and becomes a type:
        // a specialization knows what it was given, and nothing later does.
        is Expr.Member if e.name == "typeName" &&
            (e.target as? Expr.Identifier)?.name?.let { it in typeBindings } == true ->
            Expr.StringLiteral(
                typeBindings.getValue((e.target as Expr.Identifier).name).displayName(),
                e.line,
                e.column,
            )
        // `N` reads as its value and `T` as its type: `inline if N >= 3` and
        // `T is Integer` are both decidable once a specialization binds them.
        is Expr.Identifier -> constBindings[e.name]?.let { Expr.IntLiteral(it, e.line, e.column) }
            ?: (typeBindings[e.name] as? TypeRef.Named)?.takeIf { it.args.isEmpty() }
                ?.let { e.copy(name = it.name) }
            ?: e.copy(name = throughStaticAlias(e.name))
        is Expr.Call -> rewriteCall(e)
        is Expr.Binary -> e.copy(left = rewriteExpr(e.left), right = rewriteExpr(e.right))
        is Expr.InCheck -> e.copy(value = rewriteExpr(e.value), collection = rewriteExpr(e.collection))
        is Expr.InlineForArgs -> e.copy(iterable = rewriteExpr(e.iterable), body = rewriteExpr(e.body))
        is Expr.Unary -> e.copy(operand = rewriteExpr(e.operand))
        is Expr.Grouping -> e.copy(expr = rewriteExpr(e.expr))
        is Expr.Range -> e.copy(from = rewriteExpr(e.from), to = rewriteExpr(e.to))
        is Expr.ArrayLiteral -> e.copy(elements = e.elements.map(::rewriteExpr))
        is Expr.SetLiteral -> e.copy(elements = e.elements.map(::rewriteExpr))
        is Expr.Index -> e.copy(target = rewriteExpr(e.target), index = rewriteExpr(e.index))
        is Expr.Member -> e.copy(target = rewriteExpr(e.target))
        is Expr.MethodCall -> e.copy(target = rewriteExpr(e.target), args = e.args.map(::rewriteExpr))
        is Expr.StringTemplate -> e.copy(parts = e.parts.map { p ->
            if (p is Expr.StringTemplatePart.Expr) Expr.StringTemplatePart.Expr(rewriteExpr(p.expr)) else p
        })
        is Expr.TupleLit -> e.copy(elements = e.elements.map(::rewriteExpr))
        is Expr.VariantLit -> e.copy(elements = e.elements.map(::rewriteExpr))
        is Expr.TupleAccess -> e.copy(target = rewriteExpr(e.target))
        is Expr.CatchExpr -> e.copy(expr = rewriteExpr(e.expr), fallback = rewriteExpr(e.fallback))
        is Expr.TryPropagate -> e.copy(expr = rewriteExpr(e.expr))
        is Expr.IfExpr -> e.copy(condition = rewriteExpr(e.condition), thenExpr = rewriteExpr(e.thenExpr), elseExpr = rewriteExpr(e.elseExpr))
        is Expr.Lambda -> e.copy(
            params = e.params.map(::rewriteParam),
            receivers = e.receivers.map(::rewriteParam),
            body = e.body.map(::rewriteStmt),
        )
        is Expr.NamedArg -> e.copy(value = rewriteExpr(e.value))
        is Expr.NullCoalesce -> e.copy(left = rewriteExpr(e.left), right = rewriteExpr(e.right))
        is Expr.SafeMember -> e.copy(target = rewriteExpr(e.target))
        is Expr.Cast -> e.copy(expr = rewriteExpr(e.expr), targetType = rewriteType(e.targetType))
        is Expr.IsCheck -> e.copy(expr = rewriteExpr(e.expr))
        is Expr.MapLit -> e.copy(entries = e.entries.map { (k, v) -> rewriteExpr(k) to rewriteExpr(v) })
        is Expr.Alloc -> e.copy(value = rewriteExpr(e.value))
        is Expr.AllocBuffer -> e.copy(count = rewriteExpr(e.count))
        is Expr.Deref -> e.copy(target = rewriteExpr(e.target))
        is Expr.Isolated -> e.copy(value = rewriteExpr(e.value))
        is Expr.Await -> e.copy(value = rewriteExpr(e.value))
        is Expr.Spread -> e.copy(array = rewriteExpr(e.array))
        else -> e
    }

    private fun rewriteCall(e: Expr.Call): Expr {
        val elementTypes = resolveElementTypes(e)
        val mangled = when {
            e.callee in funcTemplates && elementTypes != null -> instantiateFunc(e.callee, elementTypes)
            e.callee in packTemplates && elementTypes != null -> instantiatePack(e.callee, elementTypes)
            else -> null
        }
        val rewrittenArgs = e.args.map(::rewriteExpr)
        return if (mangled != null) e.copy(callee = mangled, args = rewrittenArgs, typeArgs = emptyList())
            else e.copy(
                // `Self(…)` in a specialized member builds that specialization.
                callee = (typeBindings[e.callee] as? TypeRef.Named)
                    ?.takeIf { it.args.isEmpty() }?.name
                    ?: throughStaticAlias(e.callee),
                args = rewrittenArgs,
                typeArgs = e.typeArgs.map(::rewriteType),
            )
    }

    /** Concrete element types for a variadic call, from explicit type args or inferred args; null if unknown. */
    private fun resolveElementTypes(call: Expr.Call): List<TypeRef>? {
        // Inside a specialized static member, `Vec<T, N>(0)` names that member's own
        // specialization - the parameters have values here.
        if (call.typeArgs.isNotEmpty()) return call.typeArgs.map { substituteParams(it, typeBindings) }
        val inferred = call.args.map { inferExprType(it) }
        return if (inferred.all { it != null }) inferred.filterNotNull() else null
    }

    /**
     * Infers a [TypeRef] for an argument expression so variadic calls can be
     * monomorphized from non-literal args. Handles literals, in-scope bindings,
     * positional access into a tuple, nested variadic calls, and groupings.
     */
    private fun inferExprType(e: Expr): TypeRef? = when (e) {
        is Expr.IntLiteral -> TypeRef.Named("Int")
        is Expr.DoubleLiteral -> TypeRef.Named("Double")
        is Expr.StringLiteral -> TypeRef.Named("String")
        is Expr.BoolLiteral -> TypeRef.Named("Bool")
        is Expr.CharLiteral -> TypeRef.Named("Char")
        is Expr.Identifier -> bindings[e.name]
        is Expr.Grouping -> inferExprType(e.expr)
        is Expr.Binary -> inferBinaryType(e)
        is Expr.TupleAccess -> inferExprType(e.target)?.let { tupleElementType(it, e.index) }
        // Positional tuple access parses as a `Member` with a numeric field name
        // (`v.0`, `v.1`); resolve it against the target's tuple element types.
        is Expr.Member -> e.name.toIntOrNull()?.let { idx -> inferExprType(e.target)?.let { tupleElementType(it, idx) } }
        is Expr.Call -> {
            when {
                e.callee in funcTemplates -> resolveElementTypes(e)?.let { elementTypes ->
                    TypeRef.Named(returnedVariadicPackName(funcTemplates[e.callee]!!.decl) ?: e.callee, elementTypes)
                }
                e.callee in packTemplates -> resolveElementTypes(e)?.let { TypeRef.Named(e.callee, it) }
                e.callee in constructibleTypes -> TypeRef.Named(e.callee, e.typeArgs)
                else -> functionReturns[e.callee]?.let { resolveCallableReturn(it, e.typeArgs) }
            }
        }
        is Expr.MethodCall -> {
            val receiver = inferExprType(e.target) as? TypeRef.Named
            receiver?.let {
                methodReturns[it.name.substringBefore('<') to e.name]?.let { callable ->
                    resolveCallableReturn(callable, it.args)
                }
            }
        }
        is Expr.StringTemplate -> TypeRef.Named("String")
        else -> null
    }

    private fun inferBinaryType(expr: Expr.Binary): TypeRef? {
        if (expr.op in setOf(
                TokenType.EQUAL_EQUAL,
                TokenType.BANG_EQUAL,
                TokenType.LESS,
                TokenType.LESS_EQUAL,
                TokenType.GREATER,
                TokenType.GREATER_EQUAL,
                TokenType.AND_AND,
                TokenType.OR_OR,
            )
        ) {
            return TypeRef.Named("Bool")
        }
        val left = inferExprType(expr.left) as? TypeRef.Named ?: return null
        val right = inferExprType(expr.right) as? TypeRef.Named ?: return null
        if (left == right) return left
        if (expr.op == TokenType.PLUS && (left.name == "String" || right.name == "String")) {
            return TypeRef.Named("String")
        }
        val rank = mapOf(
            "Byte" to 0,
            "UByte" to 0,
            "Short" to 1,
            "UShort" to 1,
            "Int" to 2,
            "UInt" to 2,
            "Long" to 3,
            "ULong" to 3,
            "Cent" to 4,
            "UCent" to 4,
            "Float" to 5,
            "Double" to 6,
            "Decimal" to 7,
        )
        val leftRank = rank[left.name] ?: return null
        val rightRank = rank[right.name] ?: return null
        return if (leftRank >= rightRank) left else right
    }

    private fun resolveCallableReturn(callable: CallableReturn, typeArgs: List<TypeRef>): TypeRef {
        if (callable.typeParams.isEmpty() || typeArgs.isEmpty()) return callable.returnType
        val substitutions = callable.typeParams.zip(typeArgs).toMap()
        return substituteTypeParams(callable.returnType, substitutions)
    }

    private fun substituteTypeParams(type: TypeRef, substitutions: Map<String, TypeRef>): TypeRef = when (type) {
        is TypeRef.Named -> substitutions[type.name] ?: type.copy(args = type.args.map { substituteTypeParams(it, substitutions) })
        is TypeRef.Array -> type.copy(element = substituteTypeParams(type.element, substitutions))
        is TypeRef.Map -> type.copy(
            key = substituteTypeParams(type.key, substitutions),
            value = substituteTypeParams(type.value, substitutions),
        )
        is TypeRef.Set -> type.copy(element = substituteTypeParams(type.element, substitutions))
        is TypeRef.Function -> type.copy(
            params = type.params.map { substituteTypeParams(it, substitutions) },
            ret = substituteTypeParams(type.ret, substitutions),
            receivers = type.receivers.map { substituteTypeParams(it, substitutions) },
        )
        is TypeRef.Tuple -> type.copy(elements = type.elements.map { substituteTypeParams(it, substitutions) })
        is TypeRef.Nullable -> type.copy(inner = substituteTypeParams(type.inner, substitutions))
        is TypeRef.Failable -> type.copy(ok = substituteTypeParams(type.ok, substitutions))
        is TypeRef.Pointer -> type.copy(inner = substituteTypeParams(type.inner, substitutions))
        is TypeRef.Reference -> type.copy(inner = substituteTypeParams(type.inner, substitutions))
        is TypeRef.Const -> type
    }

    /** Element [index] of a tuple-typed [type] (structural `Tuple` or a variadic pack ref). */
    private fun tupleElementType(type: TypeRef, index: Int): TypeRef? = when (type) {
        is TypeRef.Tuple -> type.elements.getOrNull(index)
        is TypeRef.Named -> if (type.name in packTemplates && type.args.isNotEmpty()) type.args.getOrNull(index) else null
        else -> null
    }

    /** Records a local binding's type (explicit annotation, else inferred) for later arg inference. */
    private fun recordBinding(name: String, type: TypeAnnotation, initializer: Expr) {
        val t = (type as? TypeAnnotation.Explicit)?.ref ?: inferExprType(initializer)
        if (t != null) bindings[name] = t
    }

    fun rewriteStmt(s: Stmt): Stmt = withSourceLine(s.line) {
        when (s) {
        is Stmt.VarDecl -> { recordBinding(s.name, s.type, s.initializer); s.copy(type = rewriteTypeAnnotation(s.type), initializer = rewriteExpr(s.initializer)) }
        is Stmt.FinDecl -> { recordBinding(s.name, s.type, s.initializer); s.copy(type = rewriteTypeAnnotation(s.type), initializer = rewriteExpr(s.initializer)) }
        is Stmt.LetDecl -> { recordBinding(s.name, s.type, s.initializer); s.copy(type = rewriteTypeAnnotation(s.type), initializer = rewriteExpr(s.initializer)) }
        is Stmt.InlineVar -> { recordBinding(s.name, s.type, s.initializer); s.copy(type = rewriteTypeAnnotation(s.type), initializer = rewriteExpr(s.initializer)) }
        is Stmt.InlineFin -> { recordBinding(s.name, s.type, s.initializer); s.copy(type = rewriteTypeAnnotation(s.type), initializer = rewriteExpr(s.initializer)) }
        is Stmt.InlineLet -> { recordBinding(s.name, s.type, s.initializer); s.copy(type = rewriteTypeAnnotation(s.type), initializer = rewriteExpr(s.initializer)) }
        is Stmt.RemDecl -> { recordBinding(s.name, s.type, s.initializer); s.copy(type = rewriteTypeAnnotation(s.type), initializer = rewriteExpr(s.initializer)) }
        is Stmt.Assignment -> s.copy(value = rewriteExpr(s.value))
        is Stmt.InlineAssignment -> s.copy(value = rewriteExpr(s.value))
        is Stmt.IndexAssign -> s.copy(target = rewriteExpr(s.target), index = rewriteExpr(s.index), value = rewriteExpr(s.value))
        is Stmt.MemberAssign -> s.copy(target = rewriteExpr(s.target), value = rewriteExpr(s.value))
        is Stmt.DerefAssign -> s.copy(target = rewriteExpr(s.target), value = rewriteExpr(s.value))
        is Stmt.Return -> s.copy(value = s.value?.let(::rewriteExpr))
        is Stmt.ExprStmt -> s.copy(expr = rewriteExpr(s.expr))
        is Stmt.Throw -> s.copy(value = rewriteExpr(s.value))
        is Stmt.Panic -> s.copy(message = rewriteExpr(s.message))
        is Stmt.Yield -> s.copy(value = rewriteExpr(s.value))
        is Stmt.Assert -> s.copy(condition = rewriteExpr(s.condition), message = rewriteExpr(s.message))
        is Stmt.InlineAssert -> s.copy(condition = rewriteExpr(s.condition), message = rewriteExpr(s.message))
        is Stmt.Trace -> s.copy(message = rewriteExpr(s.message), level = s.level?.let(::rewriteExpr))
        is Stmt.InlineTrace -> s.copy(message = rewriteExpr(s.message), level = s.level?.let(::rewriteExpr))
        is Stmt.If -> s.copy(condition = rewriteExpr(s.condition), thenBranch = s.thenBranch.map(::rewriteStmt), elseBranch = s.elseBranch?.map(::rewriteStmt))
        is Stmt.InlineIf -> s.copy(condition = rewriteExpr(s.condition), thenBranch = s.thenBranch.map(::rewriteStmt), elseBranch = s.elseBranch?.map(::rewriteStmt))
        is Stmt.DeepInlineIf -> s.copy(condition = rewriteExpr(s.condition), thenBranch = s.thenBranch.map(::rewriteStmt), elseBranch = s.elseBranch?.map(::rewriteStmt))
        is Stmt.While -> s.copy(condition = rewriteExpr(s.condition), body = s.body.map(::rewriteStmt))
        is Stmt.For -> s.copy(iterable = rewriteExpr(s.iterable), step = s.step?.let(::rewriteExpr), body = s.body.map(::rewriteStmt))
        is Stmt.InlineFor -> s.copy(iterable = rewriteExpr(s.iterable), body = s.body.map(::rewriteStmt))
        is Stmt.Loop -> s.copy(body = s.body.map(::rewriteStmt))
        is Stmt.When -> s.copy(scrutinee = rewriteExpr(s.scrutinee), branches = s.branches.map { b -> b.copy(patterns = b.patterns.map(::rewriteExpr), body = b.body.map(::rewriteStmt)) }, elseBranch = s.elseBranch?.map(::rewriteStmt))
        is Stmt.Try -> s.copy(body = s.body.map(::rewriteStmt), catchBody = s.catchBody?.map(::rewriteStmt))
        is Stmt.Defer -> s.copy(body = s.body.map(::rewriteStmt))
        is Stmt.Scope -> s.copy(body = s.body.map(::rewriteStmt))
        is Stmt.InlineBlock -> s.copy(body = s.body.map(::rewriteStmt))
        is Stmt.DeepInlineBlock -> s.copy(body = s.body.map(::rewriteStmt))
        is Stmt.Effect -> s.copy(
            dependencies = s.dependencies?.map(::rewriteExpr),
            body = s.body.map(::rewriteStmt),
        )
        is Stmt.WithContext -> s.copy(values = s.values.map(::rewriteExpr), body = s.body.map(::rewriteStmt))
        is Stmt.NoInline -> s.copy(stmt = rewriteStmt(s.stmt))
        is Stmt.Break, is Stmt.Continue -> s
        }
    }

    private var currentSourceLine: Int? = null

    private inline fun <T> withSourceLine(line: Int?, block: () -> T): T {
        val previous = currentSourceLine
        if (line != null && line > 0) currentSourceLine = line
        return try {
            block()
        } finally {
            currentSourceLine = previous
        }
    }

    private fun typeMacroError(message: String): Nothing {
        val line = currentSourceLine
        error(if (line != null) "line $line: $message" else message)
    }

    private fun TopLevel.sourceLine(): Int? = when (this) {
        is TopLevel.Func -> decl.line
        is TopLevel.VarDecl -> line
        is TopLevel.FinDecl -> line
        is TopLevel.LetDecl -> line
        is TopLevel.InlineVar -> line
        is TopLevel.InlineFin -> line
        is TopLevel.InlineLet -> line
        is TopLevel.InlineAssignment -> line
        is TopLevel.InlineIf -> line
        is TopLevel.InlineBlock -> line
        is TopLevel.DeepInlineBlock -> line
        is TopLevel.DeepInlineIf -> line
        is TopLevel.Test -> line
        is TopLevel.Pack -> line
        is TopLevel.Deco -> line
        is TopLevel.Bridge -> line
        is TopLevel.Solo -> line
        is TopLevel.Graph -> line
        is TopLevel.UseImport -> line
        is TopLevel.Enum -> line
        is TopLevel.Fail -> line
        is TopLevel.Impl -> line
        is TopLevel.Spec -> line
        is TopLevel.TypeAlias -> line
        is TopLevel.Slot -> line
        is TopLevel.Meta -> line
        is TopLevel.InlineAssert -> line
        is TopLevel.InlineTrace -> line
    }

    // ------------------------------------------------------------------
    // Name mangling
    // ------------------------------------------------------------------

    /**
     * The canonical symbol for one specialization: `__` once, then single `_`
     * between every segment.
     *
     * Both halves can already be canonical names - a specialization of a
     * specialization embeds `__Vec_Double_3` as an argument - so the parts are
     * joined and then normalized, rather than trusting each to be separator-free.
     * Without that, nesting accumulates separators (`..._u64__Vec_Double_3`) and
     * the symbol no longer matches the one ABI every backend reads.
     */
    private fun mangleTemplate(templateName: String, args: List<TypeRef>): String =
        canonicalSymbol(templateName + args.joinToString("") { "_" + mangleType(it) })

    /** [raw] with one leading `__` and no repeated separators anywhere after it. */
    private fun canonicalSymbol(raw: String): String =
        "__" + raw.trimStart('_').replace(Regex("_{2,}"), "_")

    private fun mangleType(type: TypeRef): String = when (type) {
        is TypeRef.Named -> sanitize(type.name) + if (type.args.isEmpty()) "" else type.args.joinToString("_", "_") { mangleType(it) }
        is TypeRef.Array -> "Array_" + mangleType(type.element)
        is TypeRef.Map -> "Map_" + mangleType(type.key) + "_" + mangleType(type.value)
        is TypeRef.Set -> "Set_" + mangleType(type.element)
        is TypeRef.Function -> type.kind.surfaceName + "_P_" +
            type.params.joinToString("_") { mangleType(it) } + "_R_" +
            type.receivers.joinToString("_") { mangleType(it) } + "_" +
            mangleType(type.ret)
        is TypeRef.Tuple -> "Tup_" + type.elements.joinToString("_") { mangleType(it) }
        is TypeRef.Nullable -> mangleType(type.inner) + "_N"
        is TypeRef.Failable -> mangleType(type.ok) + "_F"
        is TypeRef.Pointer -> mangleType(type.inner) + "_P"
        is TypeRef.Reference -> mangleType(type.inner) + "_R"
        is TypeRef.Const -> type.value.toString()
    }

    private fun sanitize(name: String): String =
        if (name.all { it.isLetterOrDigit() || it == '_' }) name else name.filter { it.isLetterOrDigit() || it == '_' }
}
