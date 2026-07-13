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

import org.azora.lang.frontend.Expr
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.putIfAbsentCompat

/**
 * Injects standard-library declarations into a user compilation unit.
 *
 * Standard-library symbols are **import-gated**: a file sees a module's names
 * only after importing it —
 *
 * - `use std.math` — unqualified access to that module (`abs(x)`) plus `math::abs(x)`,
 * - `use std.*` / `use std.{math, concurrency}` — wildcard/grouped module imports,
 * - `use std.math::abs` — selective import of listed names,
 * - `use std` / `use zone std` — every stdlib module,
 * - `std::math::abs(x)` / `std::abs(x)` — qualified access needs no import.
 *
 * Only the items actually referenced are appended (functions, constants,
 * packs, enums, plus the extern `bridge` signatures their bodies call),
 * following stdlib-internal references transitively. A user declaration always
 * shadows the stdlib item of the same name, and programs that never touch the
 * stdlib compile exactly as before.
 */
object StdlibInjector {

    private val implicitCollectionTypes = setOf("List", "MutableList", "Set", "MutableSet", "Map", "MutableMap")

    private class Index {
        /** module ("std.math") → name → the item providing it. */
        val modules = LinkedHashMap<String, LinkedHashMap<String, TopLevel>>()
        /** Flat name → item view (first module wins), for transitive resolution. */
        val items = LinkedHashMap<String, TopLevel>()
        /** name → module that provides it, for import hints. */
        val moduleOfName = LinkedHashMap<String, String>()
        /** extern name → single-signature bridge declaring it. */
        val externs = LinkedHashMap<String, TopLevel.Bridge>()
        /** struct/pack name → its `impl` blocks (methods/oper overloads), injected alongside the pack. */
        val implsByType = LinkedHashMap<String, MutableList<TopLevel.Impl>>()
    }

    private val index: Index by lazy { buildIndex() }

    private fun normalizedTypeName(name: String): String =
        name.substringBefore('<').substringAfter("__")

    /** The stdlib module providing [name] ("std.math"), or null — used for error hints. */
    fun moduleOf(name: String): String? = index.moduleOfName[name]

    private fun buildIndex(): Index {
        val idx = Index()
        for (program in AzStdlib.loadPrograms()) {
            val module = program.packageName ?: "std"
            val moduleItems = idx.modules.getOrPut(module) { LinkedHashMap() }
            fun register(name: String, item: TopLevel) {
                moduleItems.putIfAbsentCompat(name, item)
                idx.items.putIfAbsentCompat(name, item)
                idx.moduleOfName.putIfAbsentCompat(name, module)
                if (name.startsWith("std__")) {
                    val shortName = name.substringAfter("__")
                    moduleItems.putIfAbsentCompat(shortName, item)
                    idx.items.putIfAbsentCompat(shortName, item)
                    idx.moduleOfName.putIfAbsentCompat(shortName, module)
                }
            }
            for (item in program.items) {
                when (item) {
                    is TopLevel.Func -> register(item.decl.name, item)
                    is TopLevel.FinDecl -> register(item.name, item)
                    is TopLevel.LetDecl -> register(item.name, item)
                    is TopLevel.VarDecl -> register(item.name, item)
                    is TopLevel.Pack -> register(item.name, item)
                    is TopLevel.Enum -> register(item.name, item)
                    is TopLevel.Fail -> register(item.name, item)
                    is TopLevel.Spec -> register(item.name, item)
                    is TopLevel.Slot -> register(item.name, item)
                    is TopLevel.Impl -> {
                        val keys = linkedSetOf(item.typeName, normalizedTypeName(item.typeName))
                        for (key in keys) {
                            idx.implsByType.getOrPut(key) { mutableListOf() }.add(item)
                        }
                    }
                    is TopLevel.Bridge -> for (sig in item.funcs) {
                        idx.externs.putIfAbsentCompat(sig.name, TopLevel.Bridge(item.target, listOf(sig), item.line, item.column))
                    }
                    else -> {}
                }
            }
        }
        return idx
    }

    /** Names made visible by the program's `use std…` imports. */
    private fun importedNames(program: Program): Set<String> {
        val visible = mutableSetOf<String>()
        for (item in program.items) {
            if (item !is TopLevel.UseImport) continue
            for ((path, selected) in item.imports) {
                when {
                    path == "std" -> index.modules.values.forEach { visible += it.keys }
                    path.startsWith("std.") -> {
                        val module = index.modules[path] ?: continue
                        if (selected != null) {
                            if (selected in module) visible += selected
                        } else {
                            visible += module.keys
                        }
                    }
                }
            }
        }
        return visible
    }

    /** Short std module aliases made visible by imports (`use std.math` -> `math`). */
    private fun moduleAliases(program: Program): Set<String> {
        val aliases = mutableSetOf<String>()
        for (item in program.items) {
            if (item !is TopLevel.UseImport) continue
            for ((path, selected) in item.imports) {
                if (selected != null) continue
                when {
                    path == "std" -> index.modules.keys
                        .filter { it.startsWith("std.") }
                        .forEach { aliases += it.removePrefix("std.").substringBefore('.') }
                    path.startsWith("std.") && path in index.modules ->
                        aliases += path.removePrefix("std.").substringBefore('.')
                }
            }
        }
        return aliases
    }

    /** Names declared at the top level of the user [program] (these shadow the stdlib). */
    private fun userDeclaredNames(program: Program): Set<String> {
        val names = mutableSetOf<String>()
        for (item in program.items) {
            when (item) {
                is TopLevel.Func -> names.add(item.decl.name)
                is TopLevel.FinDecl -> names.add(item.name)
                is TopLevel.LetDecl -> names.add(item.name)
                is TopLevel.VarDecl -> names.add(item.name)
                is TopLevel.Pack -> names.add(item.name)
                is TopLevel.Enum -> names.add(item.name)
                is TopLevel.Fail -> names.add(item.name)
                is TopLevel.Slot -> names.add(item.name)
                is TopLevel.Bridge -> item.funcs.forEach { names.add(it.name) }
                else -> {}
            }
        }
        return names
    }

    /**
     * Returns [program] with every stdlib item it references appended —
     * gated by `use std…` imports; qualified `std.` access is rewritten to
     * plain calls and injected without an import. Returns the program
     * unchanged when nothing stdlib-related is referenced.
     */
    fun inject(program: Program): Program {
        if (index.items.isEmpty() && index.externs.isEmpty()) return program

        val shadowed = userDeclaredNames(program)

        // `std::math::abs(x)` / `std::PI` → plain names, collected as requirements.
        val qualified = mutableSetOf<String>()
        val moduleAliases = moduleAliases(program) - shadowed
        val rewritten = if ("std" in shadowed) program
            else QualifiedStdRewriter(index.modules, qualified, moduleAliases).rewrite(program)

        val visible = importedNames(rewritten) + qualified

        val referenced = mutableSetOf<String>()
        for (item in rewritten.items) collectNamesFromItem(item, referenced)
        val implicitReferenced = referenced.filterTo(mutableSetOf()) { it in implicitCollectionTypes }
        referenced.retainAll(visible)
        referenced += qualified
        referenced += implicitReferenced

        val injected = LinkedHashMap<String, TopLevel>()
        val injectedExterns = LinkedHashMap<String, TopLevel>()
        var frontier = referenced.toList()
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (name in frontier) {
                if (name in shadowed || name in injected) continue
                val item = index.items[name]
                if (item != null) {
                    injected[name] = item
                    attachImplsForType(name, injected, next)
                    val transitive = mutableSetOf<String>()
                    collectNamesFromItem(item, transitive)
                    next.addAll(transitive)
                    continue
                }
                attachImplsForType(name, injected, next)
                if (name !in injectedExterns) {
                    index.externs[name]?.let { injectedExterns[name] = it }
                }
            }
            frontier = next
        }

        if (injected.isEmpty() && injectedExterns.isEmpty()) return rewritten
        return rewritten.copy(items = rewritten.items + injected.values + injectedExterns.values)
    }

    private fun attachImplsForType(
        typeName: String,
        injected: LinkedHashMap<String, TopLevel>,
        next: MutableList<String>,
    ) {
        val keys = linkedSetOf(typeName, normalizedTypeName(typeName))
        for (keyName in keys) {
            index.implsByType[keyName]?.forEach { impl ->
                val key = "impl::${normalizedTypeName(impl.typeName)}::${impl.line}:${impl.column}"
                if (key !in injected) {
                    injected[key] = impl
                    val names = mutableSetOf<String>()
                    collectNamesFromItem(impl, names)
                    next.addAll(names)
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Reference collection
    // -----------------------------------------------------------------

    private fun collectNamesFromItem(item: TopLevel, names: MutableSet<String>) {
        when (item) {
            is TopLevel.Func -> collectNamesFromFunc(item.decl, names)
            is TopLevel.FinDecl -> {
                item.type?.let { collectNamesFromTypeRef(it, names) }
                collectNamesFromExpr(item.initializer, names)
            }
            is TopLevel.LetDecl -> {
                item.type?.let { collectNamesFromTypeRef(it, names) }
                collectNamesFromExpr(item.initializer, names)
            }
            is TopLevel.VarDecl -> {
                item.type?.let { collectNamesFromTypeRef(it, names) }
                collectNamesFromExpr(item.initializer, names)
            }
            is TopLevel.Test -> item.body.forEach { collectNamesFromStmt(it, names) }
            is TopLevel.Pack -> item.fields.forEach { field ->
                collectNamesFromTypeAnnotation(TypeAnnotation.Explicit(field.type), names)
                field.default?.let { collectNamesFromExpr(it, names) }
            }
            is TopLevel.Solo -> item.methods.forEach { collectNamesFromFunc(it, names) }
            is TopLevel.Impl -> {
                names.add(item.typeName)
                item.traitName?.let { names.add(it) }
                item.traitArgs.forEach { collectNamesFromTypeRef(it, names) }
                item.methods.forEach { collectNamesFromFunc(it, names) }
            }
            is TopLevel.Spec -> {
                item.callback?.let { collectNamesFromTypeRef(it.returnType, names) }
                item.methods.forEach { collectNamesFromFunc(it, names) }
            }
            else -> {}
        }
    }

    private fun collectNamesFromFunc(func: FuncDecl, names: MutableSet<String>) {
        func.params.forEach { collectNamesFromTypeAnnotation(TypeAnnotation.Explicit(it.type), names) }
        collectNamesFromTypeAnnotation(func.returnType, names)
        func.body.forEach { collectNamesFromStmt(it, names) }
    }

    private fun collectNamesFromStmt(stmt: Stmt, names: MutableSet<String>) {
        when (stmt) {
            is Stmt.VarDecl -> {
                collectNamesFromTypeAnnotation(stmt.type, names)
                collectNamesFromExpr(stmt.initializer, names)
            }
            is Stmt.FinDecl -> {
                collectNamesFromTypeAnnotation(stmt.type, names)
                collectNamesFromExpr(stmt.initializer, names)
            }
            is Stmt.LetDecl -> {
                collectNamesFromTypeAnnotation(stmt.type, names)
                collectNamesFromExpr(stmt.initializer, names)
            }
            is Stmt.InlineVar -> {
                collectNamesFromTypeAnnotation(stmt.type, names)
                collectNamesFromExpr(stmt.initializer, names)
            }
            is Stmt.InlineFin -> {
                collectNamesFromTypeAnnotation(stmt.type, names)
                collectNamesFromExpr(stmt.initializer, names)
            }
            is Stmt.InlineLet -> {
                collectNamesFromTypeAnnotation(stmt.type, names)
                collectNamesFromExpr(stmt.initializer, names)
            }
            is Stmt.RemDecl -> {
                collectNamesFromTypeAnnotation(stmt.type, names)
                collectNamesFromExpr(stmt.initializer, names)
            }
            is Stmt.Assignment -> collectNamesFromExpr(stmt.value, names)
            is Stmt.InlineAssignment -> collectNamesFromExpr(stmt.value, names)
            is Stmt.IndexAssign -> {
                collectNamesFromExpr(stmt.target, names)
                collectNamesFromExpr(stmt.index, names)
                collectNamesFromExpr(stmt.value, names)
            }
            is Stmt.MemberAssign -> {
                collectNamesFromExpr(stmt.target, names)
                collectNamesFromExpr(stmt.value, names)
            }
            is Stmt.DerefAssign -> {
                collectNamesFromExpr(stmt.target, names)
                collectNamesFromExpr(stmt.value, names)
            }
            is Stmt.Return -> stmt.value?.let { collectNamesFromExpr(it, names) }
            is Stmt.ExprStmt -> collectNamesFromExpr(stmt.expr, names)
            is Stmt.Throw -> collectNamesFromExpr(stmt.value, names)
            is Stmt.Panic -> collectNamesFromExpr(stmt.message, names)
            is Stmt.Yield -> collectNamesFromExpr(stmt.value, names)
            is Stmt.Assert -> {
                collectNamesFromExpr(stmt.condition, names)
                collectNamesFromExpr(stmt.message, names)
            }
            is Stmt.InlineAssert -> {
                collectNamesFromExpr(stmt.condition, names)
                collectNamesFromExpr(stmt.message, names)
            }
            is Stmt.Trace -> collectNamesFromExpr(stmt.message, names)
            is Stmt.InlineTrace -> collectNamesFromExpr(stmt.message, names)
            is Stmt.If -> {
                collectNamesFromExpr(stmt.condition, names)
                stmt.thenBranch.forEach { collectNamesFromStmt(it, names) }
                stmt.elseBranch?.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.InlineIf -> {
                collectNamesFromExpr(stmt.condition, names)
                stmt.thenBranch.forEach { collectNamesFromStmt(it, names) }
                stmt.elseBranch?.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.DeepInlineIf -> {
                collectNamesFromExpr(stmt.condition, names)
                stmt.thenBranch.forEach { collectNamesFromStmt(it, names) }
                stmt.elseBranch?.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.While -> {
                collectNamesFromExpr(stmt.condition, names)
                stmt.body.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.For -> {
                collectNamesFromExpr(stmt.iterable, names)
                stmt.step?.let { collectNamesFromExpr(it, names) }
                stmt.body.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.InlineFor -> {
                collectNamesFromExpr(stmt.iterable, names)
                stmt.body.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.Loop -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.When -> {
                collectNamesFromExpr(stmt.scrutinee, names)
                stmt.branches.forEach { b ->
                    b.patterns.forEach { collectNamesFromExpr(it, names) }
                    b.body.forEach { collectNamesFromStmt(it, names) }
                }
                stmt.elseBranch?.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.Try -> {
                stmt.body.forEach { collectNamesFromStmt(it, names) }
                stmt.catchBody?.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.Defer -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.Zone -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.FriendZone -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.InlineBlock -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.DeepInlineBlock -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.Effect -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.NoInline -> collectNamesFromStmt(stmt.stmt, names)
            is Stmt.Break, is Stmt.Continue -> {}
        }
    }

    private fun collectNamesFromExpr(expr: Expr, names: MutableSet<String>) {
        when (expr) {
            is Expr.Identifier -> {
                val item = index.items[expr.name]
                if (item != null && item !is TopLevel.Func) names.add(expr.name)
            }
            is Expr.Call -> {
                names.add(expr.callee)
                expr.args.forEach { collectNamesFromExpr(it, names) }
            }
            is Expr.MethodCall -> {
                collectNamesFromExpr(expr.target, names)
                expr.args.forEach { collectNamesFromExpr(it, names) }
            }
            is Expr.Binary -> {
                collectNamesFromExpr(expr.left, names)
                collectNamesFromExpr(expr.right, names)
            }
            is Expr.Unary -> collectNamesFromExpr(expr.operand, names)
            is Expr.Grouping -> collectNamesFromExpr(expr.expr, names)
            is Expr.Member -> collectNamesFromExpr(expr.target, names)
            is Expr.SafeMember -> collectNamesFromExpr(expr.target, names)
            is Expr.Index -> {
                collectNamesFromExpr(expr.target, names)
                collectNamesFromExpr(expr.index, names)
            }
            is Expr.Range -> {
                collectNamesFromExpr(expr.from, names)
                collectNamesFromExpr(expr.to, names)
            }
            is Expr.ArrayLiteral -> expr.elements.forEach { collectNamesFromExpr(it, names) }
            is Expr.MapLit -> expr.entries.forEach { (k, v) ->
                collectNamesFromExpr(k, names)
                collectNamesFromExpr(v, names)
            }
            is Expr.TupleLit -> expr.elements.forEach { collectNamesFromExpr(it, names) }
            is Expr.VariantLit -> expr.elements.forEach { collectNamesFromExpr(it, names) }
            is Expr.TupleAccess -> collectNamesFromExpr(expr.target, names)
            is Expr.StringTemplate -> expr.parts.forEach { part ->
                if (part is Expr.StringTemplatePart.Expr) collectNamesFromExpr(part.expr, names)
            }
            is Expr.Lambda -> {
                expr.params.forEach { collectNamesFromTypeAnnotation(TypeAnnotation.Explicit(it.type), names) }
                expr.body.forEach { collectNamesFromStmt(it, names) }
            }
            is Expr.NamedArg -> collectNamesFromExpr(expr.value, names)
            is Expr.CatchExpr -> {
                collectNamesFromExpr(expr.expr, names)
                collectNamesFromExpr(expr.fallback, names)
            }
            is Expr.IfExpr -> {
                collectNamesFromExpr(expr.condition, names)
                collectNamesFromExpr(expr.thenExpr, names)
                collectNamesFromExpr(expr.elseExpr, names)
            }
            is Expr.NullCoalesce -> {
                collectNamesFromExpr(expr.left, names)
                collectNamesFromExpr(expr.right, names)
            }
            is Expr.Cast -> {
                collectNamesFromExpr(expr.expr, names)
                collectNamesFromTypeRef(expr.targetType, names)
            }
            is Expr.IsCheck -> {
                collectNamesFromExpr(expr.expr, names)
                names.add(expr.typeName)
            }
            is Expr.Alloc -> collectNamesFromExpr(expr.value, names)
            is Expr.AllocBuffer -> {
                names.add(expr.typeName)
                collectNamesFromExpr(expr.count, names)
            }
            is Expr.Deref -> collectNamesFromExpr(expr.target, names)
            is Expr.Isolated -> collectNamesFromExpr(expr.value, names)
            is Expr.Await -> collectNamesFromExpr(expr.value, names)
            is Expr.Inject -> names.add(expr.typeName)
            is Expr.Spread -> collectNamesFromExpr(expr.array, names)
            else -> {}
        }
    }

    private fun collectNamesFromTypeAnnotation(annotation: TypeAnnotation, names: MutableSet<String>) {
        if (annotation is TypeAnnotation.Explicit) collectNamesFromTypeRef(annotation.ref, names)
    }

    private fun collectNamesFromTypeRef(ref: TypeRef, names: MutableSet<String>) {
        when (ref) {
            is TypeRef.Named -> {
                names.add(ref.name)
                ref.args.forEach { collectNamesFromTypeRef(it, names) }
            }
            is TypeRef.Array -> collectNamesFromTypeRef(ref.element, names)
            is TypeRef.Map -> {
                names.add("Map")
                collectNamesFromTypeRef(ref.key, names)
                collectNamesFromTypeRef(ref.value, names)
            }
            is TypeRef.Set -> {
                names.add("Set")
                collectNamesFromTypeRef(ref.element, names)
            }
            is TypeRef.Function -> {
                ref.params.forEach { collectNamesFromTypeRef(it, names) }
                collectNamesFromTypeRef(ref.ret, names)
            }
            is TypeRef.Tuple -> ref.elements.forEach { collectNamesFromTypeRef(it, names) }
            is TypeRef.Nullable -> collectNamesFromTypeRef(ref.inner, names)
            is TypeRef.Failable -> {
                collectNamesFromTypeRef(ref.ok, names)
                names.add(ref.errSet)
            }
            is TypeRef.Pointer -> collectNamesFromTypeRef(ref.inner, names)
            is TypeRef.Reference -> collectNamesFromTypeRef(ref.inner, names)
        }
    }
}
