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
import org.azora.lang.frontend.ModuleVisibility
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.putIfAbsentCompat

/**
 * Injects standard-library declarations into a user compilation unit.
 *
 * Bundled-library symbols are **import-gated**: a file sees a module's names
 * only after importing it. With the bundled stdlib that looks like:
 *
 * - `import std.math` — unqualified access to that module (`abs(x)`) plus `math::abs(x)`,
 * - `import std.*` / `import std.{math, concurrency}` — wildcard/grouped module imports,
 * - `import std.math.abs` — selective import of listed names,
 * - `import std` / `import zone std` — every stdlib module,
 * - `std::math::abs(x)` / `std::abs(x)` — qualified access needs no import.
 *
 * The module root is derived from the loaded library modules; the frontend
 * import grammar does not special-case `std`. Only the items actually
 * referenced are appended (functions, constants, packs, enums, plus the extern
 * `bridge` signatures their bodies call), following bundled-library references
 * transitively. A user declaration always shadows a library item of the same
 * name, and programs that never touch the library compile exactly as before.
 */
object StdlibInjector {

    private val implicitCollectionTypes = setOf("List", "MutableList", "Set", "MutableSet", "Map", "MutableMap")

    private class Index {
        /** Library root module names, derived from loaded modules (for example "std"). */
        val roots = LinkedHashSet<String>()
        /** module ("std.math") → name → the item providing it. */
        val modules = LinkedHashMap<String, LinkedHashMap<String, TopLevel>>()
        /** Flat name → item view (first module wins), for transitive resolution. */
        val items = LinkedHashMap<String, TopLevel>()
        /** name → module that provides it, for import hints. */
        val moduleOfName = LinkedHashMap<String, String>()
        /** module → its declared visibility, for import gating. */
        val moduleVisibility = LinkedHashMap<String, ModuleVisibility>()
        /** Items from a library's conventional `<root>.root` module. */
        val implicitRootItems = LinkedHashMap<String, TopLevel>()
        /**
         * Top-level items that must be injected into every unit unconditionally,
         * gathered from `export module …` declarations (and the conventional
         * `<root>.root` module). Kept as raw items — in particular a `deepinline
         * zone { … }` block is injected whole so CTCE flattens it downstream,
         * exactly as it would inside its own module.
         */
        val alwaysInjectedItems = mutableListOf<TopLevel>()
        /** extern name → single-signature bridge declaring it. */
        val externs = LinkedHashMap<String, TopLevel.Bridge>()
        /** struct/pack name → its `impl` blocks (methods/oper overloads), injected alongside the pack. */
        val implsByType = LinkedHashMap<String, MutableList<TopLevel.Impl>>()
    }

    private val index: Index by lazy { buildIndex() }

    private fun normalizedTypeName(name: String): String =
        name.substringBefore('<').substringAfter("__")

    /** The bundled-library module providing [name] ("std.math"), or null — used for error hints. */
    fun moduleOf(name: String): String? = index.moduleOfName[name]

    private fun buildIndex(): Index {
        val idx = Index()
        for (program in AzStdlib.loadPrograms()) {
            val module = program.moduleName ?: continue
            val root = module.substringBefore('.')
            idx.roots.add(root)
            idx.moduleVisibility.putIfAbsentCompat(module, program.moduleVisibility)
            val moduleItems = idx.modules.getOrPut(module) { LinkedHashMap() }
            fun register(name: String, item: TopLevel) {
                moduleItems.putIfAbsentCompat(name, item)
                idx.items.putIfAbsentCompat(name, item)
                idx.moduleOfName.putIfAbsentCompat(name, module)
                for (knownRoot in idx.roots) {
                    val rootPrefix = "${knownRoot}__"
                    if (name.startsWith(rootPrefix)) {
                        val shortName = name.removePrefix(rootPrefix)
                        moduleItems.putIfAbsentCompat(shortName, item)
                        idx.items.putIfAbsentCompat(shortName, item)
                        idx.moduleOfName.putIfAbsentCompat(shortName, module)
                    }
                }
            }
            // A module is auto-imported into downstream/user units when it is
            // declared `export expose module …` (the default visibility) or follows
            // the conventional `<root>.root` naming. `export intern`/`export protect`
            // auto-import only within the library/folder, so they are not injected
            // into external units here; `export confine` is rejected at parse time.
            val alwaysOn = (program.isExported && program.moduleVisibility == ModuleVisibility.EXPOSE) ||
                module.substringAfterLast('.') == "root"
            for (item in program.items) {
                when (item) {
                    is TopLevel.Func -> register(item.decl.name, item)
                    is TopLevel.FinDecl -> register(item.name, item)
                    is TopLevel.LetDecl -> register(item.name, item)
                    is TopLevel.VarDecl -> register(item.name, item)
                    // Compile-time constants from `impl zone` / inline blocks (e.g.
                    // `Int::MAX_VALUE`). Folded away by CTCE once injected.
                    is TopLevel.InlineFin -> register(item.name, item)
                    is TopLevel.InlineLet -> register(item.name, item)
                    is TopLevel.InlineVar -> register(item.name, item)
                    is TopLevel.Pack -> register(item.name, item)
                    is TopLevel.Enum -> register(item.name, item)
                    is TopLevel.Fail -> register(item.name, item)
                    is TopLevel.Spec -> register(item.name, item)
                    is TopLevel.Deco -> register(item.name, item)
                    is TopLevel.Slot -> register(item.name, item)
                    is TopLevel.Impl -> {
                        val owner = item.typeName.substringBefore('.')
                        val keys = linkedSetOf(item.typeName, normalizedTypeName(item.typeName), owner)
                        for (key in keys) {
                            idx.implsByType.getOrPut(key) { mutableListOf() }.add(item)
                        }
                    }
                    is TopLevel.Bridge -> for (sig in item.funcs) {
                        idx.externs.putIfAbsentCompat(sig.name, TopLevel.Bridge(item.target, listOf(sig), item.line, item.column))
                    }
                    // `deepinline zone { … }` and similar compile-time blocks (e.g.
                    // `std.config`) carry their declarations opaquely; inject them
                    // whole so CTCE flattens them downstream just as it would in
                    // the module itself, rather than lifting each nested constant.
                    is TopLevel.InlineBlock, is TopLevel.DeepInlineBlock,
                    is TopLevel.InlineIf, is TopLevel.DeepInlineIf ->
                        if (alwaysOn) idx.alwaysInjectedItems.add(item)
                    else -> {}
                }
            }
            if (alwaysOn) {
                moduleItems.forEach { (name, item) -> idx.implicitRootItems.putIfAbsentCompat(name, item) }
            }
        }
        return idx
    }

    /**
     * Declarations made visible by imports of loaded library modules.
     *
     * Keeping the declaration, rather than only its name, is important when
     * independent modules export the same short name. An exact module import
     * must select that module's declaration instead of the flat index's first
     * match.
     */
    private fun importedItems(program: Program): Map<String, TopLevel> {
        val visible = LinkedHashMap<String, TopLevel>()
        for (item in program.items) {
            if (item !is TopLevel.UseImport) continue
            for ((path, selected) in item.imports) {
                for ((name, declaration) in itemsVisibleFromImport(path, selected)) {
                    visible.putIfAbsentCompat(name, declaration)
                }
            }
        }
        return visible
    }

    /**
     * Whether a bundled-library [module] may be imported by an external unit
     * (user code / a downstream library). Only `expose` modules are; `intern`,
     * `protect`, and `confine` modules are visible solely within the library or
     * folder that declares them, so importing them from user code fails as if
     * the module did not exist.
     */
    private fun isExternallyImportable(module: String): Boolean =
        index.moduleVisibility[module]?.let { it == ModuleVisibility.EXPOSE } ?: true

    private fun itemsVisibleFromImport(path: String, selected: String?): Map<String, TopLevel> {
        if (selected != null) {
            if (!isExternallyImportable(path)) return emptyMap()
            val module = index.modules[path] ?: return emptyMap()
            return module[selected]?.let { mapOf(selected to it) } ?: emptyMap()
        }
        if (index.modules[path] != null && isExternallyImportable(path)) return index.modules[path]!!
        val descendants = index.modules
            .filterKeys { it.startsWith("$path.") && isExternallyImportable(it) }
            .values
        if (descendants.isNotEmpty()) {
            val result = LinkedHashMap<String, TopLevel>()
            descendants.forEach { module ->
                module.forEach { (name, declaration) -> result.putIfAbsentCompat(name, declaration) }
            }
            return result
        }
        val (moduleName, itemName) = resolveSelectedLibraryPath(path) ?: return emptyMap()
        if (!isExternallyImportable(moduleName)) return emptyMap()
        val declaration = index.modules[moduleName]?.get(itemName) ?: return emptyMap()
        return mapOf(itemName to declaration)
    }

    private fun resolveSelectedLibraryPath(path: String): Pair<String, String>? {
        val segments = path.split('.')
        if (segments.size < 2) return null
        for (itemStart in segments.lastIndex downTo 1) {
            val module = segments.take(itemStart).joinToString(".")
            if (module !in index.modules) continue
            val item = segments.drop(itemStart).joinToString("__")
            if (index.modules[module]?.containsKey(item) == true) return module to item
        }
        return null
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
                is TopLevel.Deco -> names.add(item.name)
                is TopLevel.Spec -> names.add(item.name)
                is TopLevel.Slot -> names.add(item.name)
                is TopLevel.Bridge -> item.funcs.forEach { names.add(it.name) }
                else -> {}
            }
        }
        return names
    }

    /**
     * Returns [program] with every bundled-library item it references appended.
     *
     * Visibility is granted only by explicit `import` declarations. Zone
     * members are name-mangled at parse time (`std.math::abs` → `std__math__abs`),
     * so [importedItems] returns the mangled items exported by imported
     * modules. A reference resolves only if it is both mangled (written as a
     * qualified `Zone::name` path) and visible (its module was imported). Bare
     * references never match a mangled name, so bare access to library symbols
     * is rejected. Returns the program unchanged when nothing is referenced.
     */
    fun inject(program: Program): Program {
        if (index.items.isEmpty() && index.externs.isEmpty()) return program

        val shadowed = userDeclaredNames(program)

        val visible = LinkedHashMap<String, TopLevel>().apply {
            putAll(index.implicitRootItems)
            putAll(importedItems(program))
        }

        val referenced = mutableSetOf<String>()
        for (item in program.items) collectNamesFromItem(item, referenced)
        // Exported/root blocks are always injected; pull in whatever they reference.
        for (item in index.alwaysInjectedItems) collectNamesFromItem(item, referenced)
        val implicitReferenced = referenced.filterTo(mutableSetOf()) { it in implicitCollectionTypes }
        referenced.retainAll(visible.keys)
        referenced += implicitReferenced
        referenced += index.implicitRootItems.keys

        val injected = LinkedHashMap<String, TopLevel>()
        val injectedExterns = LinkedHashMap<String, TopLevel>()
        var frontier = referenced.toList()
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (name in frontier) {
                if (name in shadowed || name in injected) continue
                val item = visible[name] ?: index.items[name]
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

        if (injected.isEmpty() && injectedExterns.isEmpty() && index.alwaysInjectedItems.isEmpty()) return program
        // One declaration can be indexed by both its qualified and short export
        // names. Preserve discovery order while appending each AST item once.
        val existingIdentities = program.items.mapTo(mutableSetOf()) { itemIdentity(it) }
        val declarations = injected.values.distinct().filter { existingIdentities.add(itemIdentity(it)) }
        val externDeclarations = injectedExterns.values.distinct().filter { existingIdentities.add(itemIdentity(it)) }
        // Exported/root compile-time blocks are injected unconditionally.
        val alwaysDeclarations = index.alwaysInjectedItems.filter { existingIdentities.add(itemIdentity(it)) }
        if (declarations.isEmpty() && externDeclarations.isEmpty() && alwaysDeclarations.isEmpty()) return program
        return program.copy(items = program.items + declarations + externDeclarations + alwaysDeclarations)
    }

    private fun itemIdentity(item: TopLevel): String = when (item) {
        is TopLevel.Func -> "func:${item.decl.name}"
        is TopLevel.FinDecl -> "fin:${item.name}"
        is TopLevel.LetDecl -> "let:${item.name}"
        is TopLevel.VarDecl -> "var:${item.name}"
        is TopLevel.Pack -> "pack:${item.name}"
        is TopLevel.Enum -> "enum:${item.name}"
        is TopLevel.Fail -> "fail:${item.name}"
        is TopLevel.Spec -> "spec:${item.name}"
        is TopLevel.Deco -> "deco:${item.name}"
        is TopLevel.Slot -> "slot:${item.name}"
        is TopLevel.Impl -> "impl:${item.typeName}:${item.traitName.orEmpty()}:${item.line}:${item.column}"
        is TopLevel.Bridge -> "bridge:${item.target}:${item.funcs.joinToString(",") { it.name }}"
        else -> item.toString()
    }

    private fun attachImplsForType(
        typeName: String,
        injected: LinkedHashMap<String, TopLevel>,
        next: MutableList<String>,
    ) {
        val keys = linkedSetOf(typeName, normalizedTypeName(typeName))
        for (keyName in keys) {
            index.implsByType[keyName]?.forEach { impl ->
                val key = "impl::${normalizedTypeName(impl.typeName)}::${impl.traitName.orEmpty()}::${impl.line}:${impl.column}"
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
            is TopLevel.Func -> {
                collectNamesFromAnnotations(item.decl.annotations, names)
                collectNamesFromFunc(item.decl, names)
            }
            is TopLevel.FinDecl -> {
                collectNamesFromAnnotations(item.annotations, names)
                item.type?.let { collectNamesFromTypeRef(it, names) }
                collectNamesFromExpr(item.initializer, names)
            }
            is TopLevel.LetDecl -> {
                collectNamesFromAnnotations(item.annotations, names)
                item.type?.let { collectNamesFromTypeRef(it, names) }
                collectNamesFromExpr(item.initializer, names)
            }
            is TopLevel.InlineFin -> collectNamesFromExpr(item.initializer, names)
            is TopLevel.InlineLet -> collectNamesFromExpr(item.initializer, names)
            is TopLevel.InlineVar -> collectNamesFromExpr(item.initializer, names)
            is TopLevel.VarDecl -> {
                collectNamesFromAnnotations(item.annotations, names)
                item.type?.let { collectNamesFromTypeRef(it, names) }
                collectNamesFromExpr(item.initializer, names)
            }
            is TopLevel.Test -> {
                collectNamesFromAnnotations(item.annotations, names)
                item.body.forEach { collectNamesFromStmt(it, names) }
            }
            is TopLevel.Pack -> {
                collectNamesFromAnnotations(item.annotations, names)
                item.fields.forEach { field ->
                    collectNamesFromAnnotations(field.annotations, names)
                    collectNamesFromTypeAnnotation(TypeAnnotation.Explicit(field.type), names)
                    field.default?.let { collectNamesFromExpr(it, names) }
                }
            }
            is TopLevel.Enum -> collectNamesFromAnnotations(item.annotations + item.variantAnnotations.flatten(), names)
            is TopLevel.Fail -> collectNamesFromAnnotations(item.annotations + item.variantAnnotations.flatten(), names)
            is TopLevel.Solo -> {
                collectNamesFromAnnotations(item.annotations, names)
                item.fields.forEach { field ->
                    collectNamesFromAnnotations(field.annotations, names)
                    collectNamesFromTypeRef(field.type, names)
                    field.default?.let { collectNamesFromExpr(it, names) }
                }
                item.methods.forEach { collectNamesFromFunc(it, names) }
            }
            is TopLevel.Node -> {
                collectNamesFromAnnotations(item.annotations, names)
                item.params.forEach { collectNamesFromTypeRef(it.type, names) }
                item.extraFields.forEach { field ->
                    collectNamesFromAnnotations(field.annotations, names)
                    collectNamesFromTypeRef(field.type, names)
                    field.default?.let { collectNamesFromExpr(it, names) }
                }
                item.methods.forEach { collectNamesFromFunc(it, names) }
            }
            is TopLevel.Impl -> {
                names.add(item.typeName)
                item.traitName?.let { names.add(it) }
                item.traitArgs.forEach { collectNamesFromTypeRef(it, names) }
                item.decoratorArgs.forEach { collectNamesFromExpr(it, names) }
                item.decoratorNamedArgs.forEach { (_, value) -> collectNamesFromExpr(value, names) }
                item.methods.forEach { collectNamesFromFunc(it, names) }
            }
            is TopLevel.Spec -> {
                item.callback?.let {
                    collectNamesFromTypeRef(it.returnType, names)
                    it.params.forEach { param -> collectNamesFromTypeRef(param.type, names) }
                }
                item.methods.forEach { collectNamesFromFunc(it, names) }
            }
            is TopLevel.Deco -> {
                collectNamesFromAnnotations(item.annotations, names)
                item.fields.forEach { field ->
                    collectNamesFromAnnotations(field.annotations, names)
                    collectNamesFromTypeRef(field.type, names)
                    field.default?.let { collectNamesFromExpr(it, names) }
                }
                item.bindings.forEach { binding ->
                    names.add(binding.name)
                    binding.trailingTypeArgs.forEach { collectNamesFromTypeRef(it, names) }
                }
            }
            is TopLevel.InlineIf -> {
                collectNamesFromExpr(item.condition, names)
                item.thenBranch.forEach { collectNamesFromItem(it, names) }
                item.elseBranch?.forEach { collectNamesFromItem(it, names) }
            }
            is TopLevel.DeepInlineIf -> {
                collectNamesFromExpr(item.condition, names)
                item.thenBranch.forEach { collectNamesFromItem(it, names) }
                item.elseBranch?.forEach { collectNamesFromItem(it, names) }
            }
            is TopLevel.InlineBlock -> item.body.forEach { collectNamesFromItem(it, names) }
            is TopLevel.DeepInlineBlock -> item.body.forEach { collectNamesFromItem(it, names) }
            is TopLevel.View -> {
                collectNamesFromAnnotations(item.annotations, names)
                item.params.forEach {
                    collectNamesFromAnnotations(it.annotations, names)
                    collectNamesFromTypeRef(it.type, names)
                }
                item.body.forEach { collectNamesFromStmt(it, names) }
            }
            is TopLevel.Hook -> {
                collectNamesFromAnnotations(item.annotations, names)
                item.body.forEach { collectNamesFromStmt(it, names) }
            }
            is TopLevel.Slot -> collectNamesFromAnnotations(item.annotations, names)
            is TopLevel.TypeAlias -> {
                collectNamesFromAnnotations(item.annotations, names)
                collectNamesFromTypeRef(item.type, names)
            }
            is TopLevel.Bridge -> collectNamesFromAnnotations(item.annotations, names)
            else -> {}
        }
    }

    private fun collectNamesFromFunc(func: FuncDecl, names: MutableSet<String>) {
        collectNamesFromAnnotations(func.annotations, names)
        func.params.forEach {
            collectNamesFromAnnotations(it.annotations, names)
            collectNamesFromTypeAnnotation(TypeAnnotation.Explicit(it.type), names)
        }
        collectNamesFromTypeAnnotation(func.returnType, names)
        func.body.forEach { collectNamesFromStmt(it, names) }
    }

    private fun collectNamesFromAnnotations(annotations: List<org.azora.lang.frontend.Annotation>, names: MutableSet<String>) {
        annotations.forEach { annotation ->
            names.add(annotation.name)
            annotation.args.forEach { collectNamesFromExpr(it, names) }
            annotation.namedArgs.forEach { (_, value) -> collectNamesFromExpr(value, names) }
        }
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
            is Expr.TryPropagate -> collectNamesFromExpr(expr.expr, names)
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
                names.addAll(ref.errSets)
            }
            is TypeRef.Pointer -> collectNamesFromTypeRef(ref.inner, names)
            is TypeRef.Reference -> collectNamesFromTypeRef(ref.inner, names)
        }
    }
}
