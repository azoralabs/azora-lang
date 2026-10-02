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
import org.azora.lang.frontend.NamedTypeMacroCall
import org.azora.lang.frontend.PackField
import org.azora.lang.frontend.Param
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.frontend.localNamesDeclaredIn

/**
 * Every import the program writes: at file scope, and opening any of its blocks.
 * A block's import binds only there; this is the list of what was written, not
 * of what any one place can name.
 */
fun Program.writtenImports(): List<TopLevel.UseImport> {
    val imports = items.filterIsInstanceTo(mutableListOf<TopLevel.UseImport>())
    val collector = DeclarationRenamer(emptyMap(), onImportingBlock = { found, _ -> found.mapTo(imports) { it.use } })
    items.forEach { if (it !is TopLevel.UseImport) collector.item(it) }
    return imports
}

/**
 * Gives library declarations the identity their module binds them to.
 *
 * The program is one flat namespace, so a library declaration that source may
 * not name - a dependency nothing imported - carries a name no source can spell
 * (`lib__seq__Vector`), and every reference its module makes to it is rewritten
 * to match. [names] maps each spelling the module uses to that identity; a name
 * it does not contain is left as written.
 *
 * `Owner__member` - a type's lifted static, such as its literal factory - moves
 * with its owner. A parameter, local or lambda parameter shadows a value of the
 * same name, and a type parameter shadows a type, exactly as in the source.
 *
 * Bridge symbols keep their names (they are the foreign identity), macro
 * templates are expanded where they are used, and a `when` arm's bindings are
 * not references; none of these are rewritten.
 */
internal class DeclarationRenamer(
    private val names: Map<String, String>,
    /** What each body `import` binds: spelling → identity, within its block. */
    private val bodyImports: Map<Stmt.Import, Map<String, String>> = emptyMap(),
    /** Called with each block that imports and the statements it scopes. */
    private val onImportingBlock: ((List<Stmt.Import>, List<Stmt>) -> Unit)? = null,
) {

    private fun renamed(name: String): String? {
        names[name]?.let { return it }
        val separator = name.indexOf("__")
        if (separator <= 0) return null
        val owner = names[name.substring(0, separator)] ?: return null
        return owner + name.substring(separator)
    }

    private fun declared(name: String): String = renamed(name) ?: name

    /**
     * Values shadow by name; types by type parameter. [renames] are the body
     * imports in force: an inner block's own imports extend its enclosing ones.
     */
    private class Scope(val values: Set<String>, val types: Set<String>, val renames: Map<String, String> = emptyMap()) {
        fun withValues(more: Collection<String>) = Scope(values + more, types, renames)
        fun withTypes(more: Collection<String>) = Scope(values, types + more, renames)
        fun withRenames(more: Map<String, String>) = Scope(values, types, renames + more)
    }

    private val top = Scope(emptySet(), emptySet())

    private fun value(name: String, scope: Scope): String =
        if (name in scope.values) name else scope.renames[name] ?: renamed(name) ?: name

    private fun type(name: String, scope: Scope): String =
        if (name in scope.types) name else scope.renames[name] ?: renamed(name) ?: name

    /** [ref], as a type written at the top of its module. */
    fun type(ref: TypeRef): TypeRef = typeRef(ref, top)

    fun item(item: TopLevel): TopLevel = when (item) {
        is TopLevel.Func -> item.copy(decl = func(item.decl, top, declared(item.decl.name)))
        is TopLevel.VarDecl -> item.copy(
            name = declared(item.name),
            type = item.type?.let { typeRef(it, top) },
            initializer = expr(item.initializer, top),
        )
        is TopLevel.FinDecl -> item.copy(
            name = declared(item.name),
            type = item.type?.let { typeRef(it, top) },
            initializer = expr(item.initializer, top),
        )
        is TopLevel.LetDecl -> item.copy(
            name = declared(item.name),
            type = item.type?.let { typeRef(it, top) },
            initializer = expr(item.initializer, top),
        )
        is TopLevel.InlineFin -> item.copy(name = declared(item.name), initializer = expr(item.initializer, top))
        is TopLevel.InlineLet -> item.copy(name = declared(item.name), initializer = expr(item.initializer, top))
        is TopLevel.InlineVar -> item.copy(name = declared(item.name), initializer = expr(item.initializer, top))
        is TopLevel.Test -> item.copy(body = block(item.body, top.withValues(localNames(item.body))))
        is TopLevel.Pack -> {
            val scope = top.withTypes(item.typeParams + item.constParams)
            item.copy(
                name = declared(item.name),
                fields = item.fields.map { field(it, scope) },
                annotations = annotations(item.annotations, scope),
                whereClause = item.whereClause?.let { expr(it, scope) },
            )
        }
        is TopLevel.Deco -> item.copy(
            name = declared(item.name),
            fields = item.fields.map { field(it, top) },
            annotations = annotations(item.annotations, top),
            bindings = item.bindings.map { binding ->
                binding.copy(
                    name = type(binding.name, top),
                    trailingTypeArgs = binding.trailingTypeArgs.map { typeRef(it, top) },
                )
            },
        )
        is TopLevel.Enum -> item.copy(name = declared(item.name), annotations = annotations(item.annotations, top))
        is TopLevel.Fail -> {
            val scope = top.withTypes(item.typeParams)
            item.copy(
                name = declared(item.name),
                variantPayloads = item.variantPayloads.map { payload -> payload.map { typeRef(it, scope) } },
            )
        }
        is TopLevel.Slot -> {
            val scope = top.withTypes(item.typeParams)
            item.copy(
                name = declared(item.name),
                variants = item.variants.map { variant ->
                    variant.copy(payloadTypes = variant.payloadTypes.map { typeRef(it, scope) })
                },
            )
        }
        is TopLevel.Impl -> {
            val scope = top.withTypes(item.typeParams)
            item.copy(
                typeName = type(item.typeName, scope),
                traitName = item.traitName?.let { type(it, scope) },
                traitArgs = item.traitArgs.map { typeRef(it, scope) },
                methods = item.methods.map { func(it, scope, it.name) },
                decoratorArgs = item.decoratorArgs.map { expr(it, scope) },
                decoratorNamedArgs = item.decoratorNamedArgs.map { (name, value) -> name to expr(value, scope) },
                annotations = annotations(item.annotations, scope),
                assocBindings = item.assocBindings.mapValues { (_, bound) -> typeRef(bound, scope) },
            )
        }
        is TopLevel.Spec -> {
            val scope = top.withTypes(item.typeParams + item.assocNames)
            item.copy(
                name = declared(item.name),
                methods = item.methods.map { func(it, scope, it.name) },
                parents = item.parents.map { typeRef(it, scope) },
                requires = item.requires.map { typeRef(it, scope) },
                typeDefaults = item.typeDefaults.mapValues { (_, default) -> typeRef(default, scope) },
            )
        }
        is TopLevel.TypeAlias -> item.copy(name = declared(item.name), type = typeRef(item.type, top.withTypes(item.typeParams)))
        is TopLevel.InlineAssert -> item.copy(condition = expr(item.condition, top), message = expr(item.message, top))
        is TopLevel.InlineTrace -> item.copy(message = expr(item.message, top), level = item.level?.let { expr(it, top) })
        else -> item
    }

    private fun func(decl: FuncDecl, owner: Scope, name: String): FuncDecl {
        val scope = owner
            .withTypes(decl.typeParams)
            .withValues(decl.params.map { it.name } + decl.receiverName + localNames(decl.body))
        return decl.copy(
            name = name,
            params = decl.params.map { param(it, scope) },
            returnType = annotation(decl.returnType, scope),
            body = block(decl.body, scope),
            annotations = annotations(decl.annotations, scope),
            whereClause = decl.whereClause?.let { expr(it, scope) },
        )
    }

    private fun param(p: Param, scope: Scope): Param = p.copy(
        type = typeRef(p.type, scope),
        defaultValue = p.defaultValue?.let { expr(it, scope) },
        annotations = annotations(p.annotations, scope),
    )

    private fun field(f: PackField, scope: Scope): PackField = f.copy(
        type = typeRef(f.type, scope),
        default = f.default?.let { expr(it, scope) },
        annotations = annotations(f.annotations, scope),
        condition = f.condition?.let { expr(it, scope) },
    )

    private fun annotations(list: List<Annotation>, scope: Scope): List<Annotation> = list.map { a ->
        a.copy(
            name = if (a.qualifier == null) type(a.name, scope) else a.name,
            args = a.args.map { expr(it, scope) },
            namedArgs = a.namedArgs.map { (name, value) -> name to expr(value, scope) },
        )
    }

    private fun annotation(a: TypeAnnotation, scope: Scope): TypeAnnotation = when (a) {
        is TypeAnnotation.Explicit -> TypeAnnotation.Explicit(typeRef(a.ref, scope))
        else -> a
    }

    /**
     * A type macro's name is renamed only by a block import that binds it: a
     * module's declaration of the same spelling is not the macro.
     */
    private fun typeMacroCall(ref: TypeRef.Named, scope: Scope): TypeRef {
        val name = NamedTypeMacroCall.name(ref)
        return NamedTypeMacroCall.create(
            scope.renames[name] ?: name,
            ref.args.map { typeRef(it, scope) },
            NamedTypeMacroCall.modifier(ref),
            NamedTypeMacroCall.form(ref),
        )
    }

    private fun typeRef(ref: TypeRef, scope: Scope): TypeRef = when (ref) {
        is TypeRef.Named -> if (NamedTypeMacroCall.isCall(ref)) typeMacroCall(ref, scope) else ref.copy(
            name = if (ref.qualifier == null) type(ref.name, scope) else ref.name,
            args = ref.args.map { typeRef(it, scope) },
            valueArgs = ref.valueArgs.map { expr(it, scope) },
        )
        is TypeRef.Array -> ref.copy(element = typeRef(ref.element, scope))
        is TypeRef.Map -> ref.copy(key = typeRef(ref.key, scope), value = typeRef(ref.value, scope))
        is TypeRef.Set -> ref.copy(element = typeRef(ref.element, scope))
        is TypeRef.Function -> ref.copy(
            params = ref.params.map { typeRef(it, scope) },
            ret = typeRef(ref.ret, scope),
            receivers = ref.receivers.map { typeRef(it, scope) },
        )
        is TypeRef.Tuple -> ref.copy(elements = ref.elements.map { typeRef(it, scope) })
        is TypeRef.Nullable -> ref.copy(inner = typeRef(ref.inner, scope))
        is TypeRef.Failable -> ref.copy(ok = typeRef(ref.ok, scope))
        is TypeRef.Pointer -> ref.copy(inner = typeRef(ref.inner, scope))
        is TypeRef.Reference -> ref.copy(inner = typeRef(ref.inner, scope))
        is TypeRef.Const -> ref
    }

    /** A block's own imports bind for it and the blocks nested in it; the statements leave. */
    private fun block(body: List<Stmt>, scope: Scope): List<Stmt> {
        val imports = body.filterIsInstance<Stmt.Import>()
        if (imports.isEmpty()) return body.map { stmt(it, scope) }
        onImportingBlock?.invoke(imports, body)
        val inner = scope.withRenames(imports.fold(emptyMap()) { bound, import -> bound + bodyImports[import].orEmpty() })
        return body.filter { it !is Stmt.Import }.map { stmt(it, inner) }
    }

    private fun stmt(s: Stmt, scope: Scope): Stmt = when (s) {
        is Stmt.VarDecl -> s.copy(type = annotation(s.type, scope), initializer = expr(s.initializer, scope))
        is Stmt.FinDecl -> s.copy(type = annotation(s.type, scope), initializer = expr(s.initializer, scope))
        is Stmt.LetDecl -> s.copy(type = annotation(s.type, scope), initializer = expr(s.initializer, scope))
        is Stmt.InlineFin -> s.copy(type = annotation(s.type, scope), initializer = expr(s.initializer, scope))
        is Stmt.InlineLet -> s.copy(type = annotation(s.type, scope), initializer = expr(s.initializer, scope))
        is Stmt.InlineVar -> s.copy(type = annotation(s.type, scope), initializer = expr(s.initializer, scope))
        is Stmt.RemDecl -> s.copy(type = annotation(s.type, scope), initializer = expr(s.initializer, scope))
        is Stmt.InlineAssignment -> s.copy(name = value(s.name, scope), value = expr(s.value, scope))
        is Stmt.Assignment -> s.copy(name = value(s.name, scope), value = expr(s.value, scope))
        is Stmt.Exchange -> s.copy(left = expr(s.left, scope), right = expr(s.right, scope))
        is Stmt.IndexAssign -> s.copy(target = expr(s.target, scope), index = expr(s.index, scope), value = expr(s.value, scope))
        is Stmt.MemberAssign -> s.copy(
            target = expr(s.target, scope),
            value = expr(s.value, scope),
            nameExpr = s.nameExpr?.let { expr(it, scope) },
        )
        is Stmt.DerefAssign -> s.copy(target = expr(s.target, scope), value = expr(s.value, scope))
        is Stmt.Return -> s.copy(value = s.value?.let { expr(it, scope) })
        is Stmt.ExprStmt -> s.copy(expr = expr(s.expr, scope))
        is Stmt.Throw -> s.copy(value = expr(s.value, scope))
        is Stmt.Panic -> s.copy(message = expr(s.message, scope))
        is Stmt.Yield -> s.copy(value = expr(s.value, scope))
        is Stmt.Assert -> s.copy(condition = expr(s.condition, scope), message = expr(s.message, scope))
        is Stmt.InlineAssert -> s.copy(condition = expr(s.condition, scope), message = expr(s.message, scope))
        is Stmt.Trace -> s.copy(message = expr(s.message, scope), level = s.level?.let { expr(it, scope) })
        is Stmt.InlineTrace -> s.copy(message = expr(s.message, scope), level = s.level?.let { expr(it, scope) })
        is Stmt.Scope -> s.copy(body = block(s.body, scope))
        is Stmt.InlineBlock -> s.copy(body = block(s.body, scope))
        is Stmt.DeepInlineBlock -> s.copy(body = block(s.body, scope))
        is Stmt.NoInline -> s.copy(stmt = stmt(s.stmt, scope))
        is Stmt.If -> s.copy(
            condition = expr(s.condition, scope),
            thenBranch = block(s.thenBranch, scope),
            elseBranch = s.elseBranch?.let { block(it, scope) },
        )
        is Stmt.InlineIf -> s.copy(
            condition = expr(s.condition, scope),
            thenBranch = block(s.thenBranch, scope),
            elseBranch = s.elseBranch?.let { block(it, scope) },
        )
        is Stmt.DeepInlineIf -> s.copy(
            condition = expr(s.condition, scope),
            thenBranch = block(s.thenBranch, scope),
            elseBranch = s.elseBranch?.let { block(it, scope) },
        )
        is Stmt.While -> s.copy(condition = expr(s.condition, scope), body = block(s.body, scope))
        is Stmt.For -> s.copy(
            iterable = expr(s.iterable, scope),
            body = block(s.body, scope),
            step = s.step?.let { expr(it, scope) },
            declaredType = s.declaredType?.let { typeRef(it, scope) },
        )
        is Stmt.Loop -> s.copy(
            body = block(s.body, scope),
            iterable = s.iterable?.let { expr(it, scope) },
            everySeconds = s.everySeconds?.let { expr(it, scope) },
        )
        is Stmt.InlineFor -> s.copy(iterable = expr(s.iterable, scope), body = block(s.body, scope))
        is Stmt.Break -> s.copy(value = s.value?.let { expr(it, scope) })
        is Stmt.When -> s.copy(
            scrutinee = expr(s.scrutinee, scope),
            branches = s.branches.map { branch ->
                // `.Some(v)` binds `v` rather than reading it; any other pattern
                // is an expression naming what it matches.
                branch.copy(
                    patterns = branch.patterns.map { if (it is Expr.InferredMember) it else expr(it, scope) },
                    body = block(branch.body, scope),
                )
            },
            elseBranch = s.elseBranch?.let { block(it, scope) },
        )
        is Stmt.Try -> s.copy(body = block(s.body, scope), catchBody = s.catchBody?.let { block(it, scope) })
        is Stmt.Defer -> s.copy(body = block(s.body, scope))
        is Stmt.Effect -> s.copy(
            body = block(s.body, scope),
            dependencies = s.dependencies?.map { expr(it, scope) },
            condition = s.condition?.let { expr(it, scope) },
        )
        is Stmt.UsingContext -> s.copy(values = s.values.map { expr(it, scope) }, body = block(s.body, scope))
        else -> s
    }

    private fun expr(e: Expr, scope: Scope): Expr = when (e) {
        is Expr.Identifier -> e.copy(name = value(e.name, scope))
        is Expr.Call -> e.copy(
            callee = value(e.callee, scope),
            args = e.args.map { expr(it, scope) },
            typeArgs = e.typeArgs.map { typeRef(it, scope) },
            receiver = e.receiver?.let { expr(it, scope) },
        )
        is Expr.Binary -> e.copy(left = expr(e.left, scope), right = expr(e.right, scope))
        is Expr.Unary -> e.copy(operand = expr(e.operand, scope))
        is Expr.IncDec -> e.copy(target = expr(e.target, scope))
        is Expr.Grouping -> e.copy(expr = expr(e.expr, scope))
        is Expr.MapEntryArg -> e.copy(key = expr(e.key, scope), value = expr(e.value, scope))
        is Expr.Range -> e.copy(from = expr(e.from, scope), to = expr(e.to, scope))
        is Expr.ArrayLiteral -> e.copy(elements = e.elements.map { expr(it, scope) })
        is Expr.SetLiteral -> e.copy(elements = e.elements.map { expr(it, scope) })
        is Expr.MapLit -> e.copy(entries = e.entries.map { (key, value) -> expr(key, scope) to expr(value, scope) })
        is Expr.Index -> e.copy(target = expr(e.target, scope), index = expr(e.index, scope))
        is Expr.InferredMember -> e.copy(ctorArgs = e.ctorArgs?.map { expr(it, scope) })
        is Expr.Member -> e.copy(target = expr(e.target, scope), nameExpr = e.nameExpr?.let { expr(it, scope) })
        is Expr.MethodCall -> e.copy(target = expr(e.target, scope), args = e.args.map { expr(it, scope) })
        is Expr.StringTemplate -> e.copy(parts = e.parts.map { part ->
            if (part is Expr.StringTemplatePart.Expr) Expr.StringTemplatePart.Expr(expr(part.expr, scope)) else part
        })
        is Expr.TupleLit -> e.copy(
            elements = e.elements.map { expr(it, scope) },
        )
        is Expr.VariantLit -> e.copy(elements = e.elements.map { expr(it, scope) })
        is Expr.TupleAccess -> e.copy(target = expr(e.target, scope))
        is Expr.CatchExpr -> e.copy(expr = expr(e.expr, scope), fallback = expr(e.fallback, scope))
        is Expr.TryPropagate -> e.copy(expr = expr(e.expr, scope))
        is Expr.IfExpr -> e.copy(
            condition = expr(e.condition, scope),
            thenExpr = expr(e.thenExpr, scope),
            elseExpr = expr(e.elseExpr, scope),
        )
        is Expr.Seal -> e.copy(value = expr(e.value, scope))
        is Expr.Lambda -> {
            val inner = scope
                .withTypes(e.typeParams)
                .withValues(e.params.map { it.name } + e.receivers.map { it.name } + localNames(e.body))
            e.copy(
                params = e.params.map { param(it, inner) },
                receivers = e.receivers.map { param(it, inner) },
                body = block(e.body, inner),
            )
        }
        is Expr.InlineForArgs -> e.copy(iterable = expr(e.iterable, scope), body = expr(e.body, scope))
        is Expr.NamedArg -> e.copy(value = expr(e.value, scope))
        is Expr.NullCoalesce -> e.copy(left = expr(e.left, scope), right = expr(e.right, scope))
        is Expr.SafeMember -> e.copy(target = expr(e.target, scope))
        is Expr.Cast -> e.copy(expr = expr(e.expr, scope), targetType = typeRef(e.targetType, scope))
        is Expr.IsCheck -> e.copy(expr = expr(e.expr, scope), typeName = type(e.typeName, scope))
        is Expr.InCheck -> e.copy(value = expr(e.value, scope), collection = expr(e.collection, scope))
        is Expr.Alloc -> e.copy(value = expr(e.value, scope))
        is Expr.Deref -> e.copy(target = expr(e.target, scope))
        is Expr.Isolated -> e.copy(value = expr(e.value, scope))
        is Expr.Await -> e.copy(value = expr(e.value, scope))
        is Expr.Inject -> e.copy(typeName = type(e.typeName, scope))
        is Expr.Spread -> e.copy(array = expr(e.array, scope))
        is Expr.MetaInvoke -> e.copy(args = e.args.map { expr(it, scope) })
        is Expr.Slice -> e.copy(
            target = expr(e.target, scope),
            start = e.start?.let { expr(it, scope) },
            stop = e.stop?.let { expr(it, scope) },
            step = e.step?.let { expr(it, scope) },
        )
        else -> e
    }

    /**
     * Every name a body binds, wherever it binds it. Shadowing is judged per
     * declaration rather than per block, as for scope siblings: a local that
     * shares a hidden declaration's name keeps its own meaning throughout.
     */
    private fun localNames(body: List<Stmt>): Set<String> = localNamesDeclaredIn(body)
}
