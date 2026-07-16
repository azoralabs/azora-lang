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
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.PackField
import org.azora.lang.frontend.Param
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.frontend.VariadicFieldTemplate

/**
 * Monomorphizes variadic generic declarations — packs declared with a type
 * vararg (`pack Tuple<T...> where (...T).length >= 2 { inline for Ty in ...T with index { mixin "$index: $Ty" } }`)
 * and functions declared with one (`func<T...> tupleOf(elements: ...T): Tuple<...T>`).
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
 * arguments (`tupleOf<Int, Real>(…)`) when present, otherwise inferred from
 * literal arguments (`1` → Int, `2.0` → Real, …).
 */
internal object VariadicMonomorphizer {

    fun monomorphize(program: Program): Program {
        val packTemplates = linkedMapOf<String, TopLevel.Pack>()
        val funcTemplates = linkedMapOf<String, TopLevel.Func>()
        val constructibleTypes = linkedMapOf<String, List<String>>()
        val functionReturns = linkedMapOf<String, CallableReturn>()
        for (item in program.items) {
            when (item) {
                is TopLevel.Pack -> {
                    constructibleTypes[item.name] = item.typeParams
                    if (item.variadicParam != null) packTemplates[item.name] = item
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
                is TopLevel.Node -> constructibleTypes[item.name] = emptyList()
                else -> {}
            }
        }
        if (packTemplates.isEmpty() && funcTemplates.isEmpty()) return program

        val methodReturns = linkedMapOf<Pair<String, String>, CallableReturn>()
        for (item in program.items) {
            val owner = when (item) {
                is TopLevel.Impl -> item.typeName.substringBefore('<')
                is TopLevel.Solo -> item.name
                is TopLevel.Node -> item.name
                else -> null
            } ?: continue
            val ownerTypeParams = constructibleTypes[owner].orEmpty()
            val methods = when (item) {
                is TopLevel.Impl -> item.methods
                is TopLevel.Solo -> item.methods
                is TopLevel.Node -> item.methods
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
        )
        val rewritten = program.items.mapNotNull { ctx.rewriteTopLevel(it) }
        return program.copy(items = rewritten + ctx.packs.values + ctx.funcs.values)
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
) {
    val packs = linkedMapOf<String, TopLevel.Pack>()
    val funcs = linkedMapOf<String, TopLevel.Func>()

    /**
     * In-scope value bindings (parameter names, local `fin`/`var`/`let`) to their
     * *original* (pre-rewrite) [TypeRef], used to infer element types of variadic
     * calls with non-literal args (`tupleOf(t.0, t.1)`). Flat per function.
     */
    private val bindings = mutableMapOf<String, TypeRef>()

    // ------------------------------------------------------------------
    // Instantiation
    // ------------------------------------------------------------------

    fun instantiatePack(templateName: String, args: List<TypeRef>): String {
        val template = packTemplates[templateName] ?: return templateName
        val minLen = template.minVariadicLength ?: 0
        if (args.size < minLen) {
            error("variadic pack '$templateName' requires at least $minLen type arguments, got ${args.size}")
        }
        val mangled = mangleTemplate(templateName, args)
        if (mangled !in packs) {
            packs[mangled] = expandPack(mangled, template, args)
        }
        return mangled
    }

    fun instantiateFunc(templateName: String, elementTypes: List<TypeRef>): String {
        val template = funcTemplates[templateName] ?: return templateName
        val minLen = template.decl.minVariadicLength ?: 0
        if (elementTypes.size < minLen) {
            error("variadic function '$templateName' requires at least $minLen type arguments, got ${elementTypes.size}")
        }
        val mangled = mangleTemplate(templateName, elementTypes)
        if (mangled !in funcs) {
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

    private fun expandPack(mangled: String, template: TopLevel.Pack, args: List<TypeRef>): TopLevel.Pack {
        val enforce = template.annotations.any { it.name == "EnforceNumFields" }
        val fields = expandFields(template, args)
        return TopLevel.Pack(
            name = mangled,
            fields = fields,
            typeParams = emptyList(),
            line = template.line,
            column = template.column,
            annotations = if (enforce) listOf(Annotation("EnforceNumFields")) else template.annotations,
            visibility = template.visibility,
            shielded = template.shielded,
        )
    }

    /** Expands `inline for Ty in ...T with index { … }` over [args] into concrete fields. */
    private fun expandFields(template: TopLevel.Pack, args: List<TypeRef>): List<PackField> {
        val tpl = template.fieldTemplate
        if (tpl == null) return template.fields.map { it.copy(type = rewriteType(it.type)) }
        val out = mutableListOf<PackField>()
        for ((i, argType) in args.withIndex()) {
            for (f in tpl.fields) {
                val name = if (f.name.startsWith("\$index")) i.toString() else f.name
                val type = rewriteType(substituteLoopVar(f.type, tpl.loopVar, argType))
                out.add(PackField(name, type, mutable = true, default = null))
            }
            // `mixin "$index: $Ty"` — interpolate the comptime bindings, parse as a field.
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
        is TypeRef.Function -> "${type.params.joinToString(", ", "(", ")") { renderType(it) }} -> ${renderType(type.ret)}"
        is TypeRef.Failable -> if (type.errSets.size == 1) {
            "${renderType(type.ok)}!${type.errSets.single()}"
        } else {
            "${renderType(type.ok)}![${type.errSets.joinToString(", ")}]"
        }
        is TypeRef.Reference -> "${type.kind.spelling} ${renderType(type.inner)}"
    }

    /** Parses a rendered mixin string (e.g. `0: Int`) as a pack [PackField]. */
    private fun parseMixinField(rendered: String): PackField {
        val src = "@EnforceNumFields\npack __mixin { $rendered }"
        val program = Parser(Lexer(src).tokenize()).parse()
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
        is TypeRef.Function -> type.copy(params = type.params.map { substituteLoopVar(it, loopVar, replacement) }, ret = substituteLoopVar(type.ret, loopVar, replacement))
        is TypeRef.Tuple -> type.copy(elements = type.elements.map { substituteLoopVar(it, loopVar, replacement) })
        is TypeRef.Nullable -> type.copy(inner = substituteLoopVar(type.inner, loopVar, replacement))
        is TypeRef.Failable -> type.copy(ok = substituteLoopVar(type.ok, loopVar, replacement))
        is TypeRef.Pointer -> type.copy(inner = substituteLoopVar(type.inner, loopVar, replacement))
        is TypeRef.Reference -> type.copy(inner = substituteLoopVar(type.inner, loopVar, replacement))
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

    // ------------------------------------------------------------------
    // Rewriting
    // ------------------------------------------------------------------

    fun rewriteTopLevel(item: TopLevel): TopLevel? = when (item) {
        // Drop variadic templates — they are replaced by their monomorphized instances.
        is TopLevel.Pack -> if (item.variadicParam != null) null
            else item.copy(fields = item.fields.map { it.copy(type = rewriteType(it.type)) })
        is TopLevel.Func -> if (item.decl.name in funcTemplates) null
            else item.copy(decl = rewriteFuncDecl(item.decl))
        is TopLevel.Impl -> item.copy(
            traitArgs = item.traitArgs.map(::rewriteType),
            decoratorArgs = item.decoratorArgs.map(::rewriteExpr),
            decoratorNamedArgs = item.decoratorNamedArgs.map { (name, value) -> name to rewriteExpr(value) },
            methods = item.methods.map(::rewriteFuncDecl),
        )
        is TopLevel.Test -> item.copy(body = item.body.map(::rewriteStmt))
        is TopLevel.VarDecl -> item.copy(type = item.type?.let(::rewriteType), initializer = rewriteExpr(item.initializer))
        is TopLevel.LetDecl -> item.copy(type = item.type?.let(::rewriteType), initializer = rewriteExpr(item.initializer))
        is TopLevel.FinDecl -> item.copy(type = item.type?.let(::rewriteType), initializer = rewriteExpr(item.initializer))
        is TopLevel.InlineVar -> item.copy(initializer = rewriteExpr(item.initializer))
        is TopLevel.InlineLet -> item.copy(initializer = rewriteExpr(item.initializer))
        is TopLevel.InlineFin -> item.copy(initializer = rewriteExpr(item.initializer))
        else -> item
    }

    private fun rewriteFuncDecl(decl: FuncDecl): FuncDecl {
        bindings.clear()
        for (p in decl.params) bindings[p.name] = p.type
        return decl.copy(
            params = decl.params.map { it.copy(type = rewriteType(it.type)) },
            returnType = rewriteTypeAnnotation(decl.returnType),
            body = decl.body.map(::rewriteStmt),
        )
    }

    private fun rewriteTypeAnnotation(ann: TypeAnnotation): TypeAnnotation =
        if (ann is TypeAnnotation.Explicit) TypeAnnotation.Explicit(rewriteType(ann.ref)) else ann

    fun rewriteType(ref: TypeRef): TypeRef = when (ref) {
        is TypeRef.Named -> when {
            ref.name in packTemplates && ref.args.isNotEmpty() -> TypeRef.Named(instantiatePack(ref.name, ref.args))
            ref.args.isNotEmpty() -> ref.copy(args = ref.args.map(::rewriteType), variadic = false)
            else -> ref
        }
        is TypeRef.Array -> ref.copy(element = rewriteType(ref.element))
        is TypeRef.Map -> ref.copy(key = rewriteType(ref.key), value = rewriteType(ref.value))
        is TypeRef.Set -> ref.copy(element = rewriteType(ref.element))
        is TypeRef.Function -> ref.copy(params = ref.params.map(::rewriteType), ret = rewriteType(ref.ret))
        is TypeRef.Tuple -> ref.copy(elements = ref.elements.map(::rewriteType))
        is TypeRef.Nullable -> ref.copy(inner = rewriteType(ref.inner))
        is TypeRef.Failable -> ref.copy(ok = rewriteType(ref.ok))
        is TypeRef.Pointer -> ref.copy(inner = rewriteType(ref.inner))
        is TypeRef.Reference -> ref.copy(inner = rewriteType(ref.inner))
    }

    fun rewriteExpr(e: Expr): Expr = when (e) {
        is Expr.Call -> rewriteCall(e)
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
        is Expr.Lambda -> e.copy(params = e.params.map { it.copy(type = rewriteType(it.type)) }, body = e.body.map(::rewriteStmt))
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
            else e.copy(args = rewrittenArgs, typeArgs = e.typeArgs.map(::rewriteType))
    }

    /** Concrete element types for a variadic call, from explicit type args or inferred args; null if unknown. */
    private fun resolveElementTypes(call: Expr.Call): List<TypeRef>? {
        if (call.typeArgs.isNotEmpty()) return call.typeArgs
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
        is Expr.RealLiteral -> TypeRef.Named("Real")
        is Expr.StringLiteral -> TypeRef.Named("String")
        is Expr.BoolLiteral -> TypeRef.Named("Bool")
        is Expr.CharLiteral -> TypeRef.Named("Char")
        is Expr.Identifier -> bindings[e.name]
        is Expr.Grouping -> inferExprType(e.expr)
        is Expr.TupleAccess -> inferExprType(e.target)?.let { tupleElementType(it, e.index) }
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
        )
        is TypeRef.Tuple -> type.copy(elements = type.elements.map { substituteTypeParams(it, substitutions) })
        is TypeRef.Nullable -> type.copy(inner = substituteTypeParams(type.inner, substitutions))
        is TypeRef.Failable -> type.copy(ok = substituteTypeParams(type.ok, substitutions))
        is TypeRef.Pointer -> type.copy(inner = substituteTypeParams(type.inner, substitutions))
        is TypeRef.Reference -> type.copy(inner = substituteTypeParams(type.inner, substitutions))
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

    fun rewriteStmt(s: Stmt): Stmt = when (s) {
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
        is Stmt.Trace -> s.copy(message = rewriteExpr(s.message))
        is Stmt.InlineTrace -> s.copy(message = rewriteExpr(s.message))
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
        is Stmt.Zone -> s.copy(body = s.body.map(::rewriteStmt))
        is Stmt.FriendZone -> s.copy(body = s.body.map(::rewriteStmt))
        is Stmt.InlineBlock -> s.copy(body = s.body.map(::rewriteStmt))
        is Stmt.DeepInlineBlock -> s.copy(body = s.body.map(::rewriteStmt))
        is Stmt.Effect -> s.copy(body = s.body.map(::rewriteStmt))
        is Stmt.NoInline -> s.copy(stmt = rewriteStmt(s.stmt))
        is Stmt.Break, is Stmt.Continue -> s
    }

    // ------------------------------------------------------------------
    // Name mangling
    // ------------------------------------------------------------------

    private fun mangleTemplate(templateName: String, args: List<TypeRef>): String =
        "__" + templateName + args.joinToString("") { "_" + mangleType(it) }

    private fun mangleType(type: TypeRef): String = when (type) {
        is TypeRef.Named -> sanitize(type.name) + if (type.args.isEmpty()) "" else type.args.joinToString("_", "_") { mangleType(it) }
        is TypeRef.Array -> "Array_" + mangleType(type.element)
        is TypeRef.Map -> "Map_" + mangleType(type.key) + "_" + mangleType(type.value)
        is TypeRef.Set -> "Set_" + mangleType(type.element)
        is TypeRef.Function -> "Fn_" + type.params.joinToString("_") { mangleType(it) } + "_" + mangleType(type.ret)
        is TypeRef.Tuple -> "Tup_" + type.elements.joinToString("_") { mangleType(it) }
        is TypeRef.Nullable -> mangleType(type.inner) + "_N"
        is TypeRef.Failable -> mangleType(type.ok) + "_F"
        is TypeRef.Pointer -> mangleType(type.inner) + "_P"
        is TypeRef.Reference -> mangleType(type.inner) + "_R"
    }

    private fun sanitize(name: String): String =
        if (name.all { it.isLetterOrDigit() || it == '_' }) name else name.filter { it.isLetterOrDigit() || it == '_' }
}
