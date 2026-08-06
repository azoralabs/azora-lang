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
import org.azora.lang.frontend.IntraRealmRewriter
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.ModuleVisibility
import org.azora.lang.frontend.ModuleQualifiedSymbol
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeFunctionCall
import org.azora.lang.frontend.TypeFunctionDecl
import org.azora.lang.frontend.TypeRef
import org.azora.lang.frontend.TypeTypeArm
import org.azora.lang.putIfAbsentCompat

/**
 * Injects standard-library declarations into a user compilation unit.
 *
 * Bundled-library symbols are **import-gated**: a file sees a module's names
 * only after importing it. With the bundled stdlib that looks like:
 *
 * - `import std.math` - access to that module while preserving its realm path (`math::abs(x)`),
 * - `import std.*` / `import std.{math, concurrency}` - wildcard/grouped module imports,
 * - `import std.math.abs` - selective import of listed names,
 * - `import std.*` - every module below that namespace,
 * - importing a module never creates bare aliases for declarations inside a realm,
 * - compile-time type functions may instead use a complete module-plus-realm path,
 *   such as `std.traits.std::promote!(Int, Double)`.
 *
 * The module root is derived from the loaded library modules; the frontend
 * import grammar does not special-case `std`. Only the items actually
 * referenced are appended (functions, constants, packs, enums, plus the extern
 * `bridge` signatures their bodies call), following bundled-library references
 * transitively. A user declaration always shadows a library item of the same
 * name, and programs that never touch the library compile exactly as before.
 */
class StdlibInjector private constructor(
    private val programs: List<Program>,
    private val configOverrides: Map<String, String>,
) {
    private data class RealmTypeExport(
        val shortName: String,
        val qualifier: String,
        val module: String,
        val declaration: TopLevel,
    ) {
        val qualifiedName: String get() = "$qualifier::$shortName"
    }

    companion object {
        internal var DEBUG_INJECT = false

        private val standard: StdlibInjector by lazy {
            StdlibInjector(AzStdlib.loadPrograms(), emptyMap())
        }

        /**
         * Creates an isolated import/injection context for one compilation.
         * Additional sources are ordinary Azora modules and are resolved with
         * exactly the same rules as bundled standard-library modules.
         */
        fun create(
            additionalSources: List<Pair<String, String>> = emptyList(),
            configOverrides: Map<String, String> = emptyMap(),
        ): StdlibInjector {
            if (additionalSources.isEmpty() && configOverrides.isEmpty()) return standard
            // Seeded with the standard library's compile-time lists so a package
            // source can iterate `Numbers` exactly as a stdlib file does. The lists
            // are bound while the stdlib is parsed, so it has to be read first -
            // reading the map before that yields nothing to iterate.
            AzStdlib.loadPrograms()
            val typeListEnv = AzStdlib.comptimeLists.toMutableMap()
            val enumEnv = AzStdlib.declaredEnums.toMutableMap()
            val additionalPrograms = additionalSources.map { (path, source) ->
                val program = try {
                    Parser(Lexer(source).tokenize(), typeListEnv, enumEnv).parse()
                } catch (error: Exception) {
                    throw IllegalArgumentException(
                        "Failed to parse library source '$path': ${error.message ?: error.toString()}",
                        error,
                    )
                }
                checkFileMatchesModule(path, program.moduleName)
                program
            }
            return StdlibInjector(AzStdlib.loadPrograms() + additionalPrograms, configOverrides)
        }

        /** Compatibility lookup against the bundled standard library. */
        fun moduleOf(name: String): String? = standard.moduleOf(name)

        /**
         * Enforces that a source file's path agrees with its declared module.
         *
         * A `src` directory is a **module root**: it is where a package's own
         * sources begin, so it never appears in a module path. Everything below
         * it must match the *end* of the declared module, and the leading
         * segments the path does not spell are the package's own prefix. In a
         * multi-package workspace that is what lets
         * `azora-render/src/render.az` declare `mod engine.render` - the
         * package supplies `engine`, the file supplies `render`.
         *
         * Without a `src` root the whole path must match, so a single-directory
         * project still gets the strict `a/b/c.az` → `mod a.b.c` rule. A
         * folder-index file `a/b/b.az` represents `mod a.b` in either case.
         * Files with no `module` declaration are unconstrained.
         *
         * @throws IllegalArgumentException when the path cannot denote the module.
         */
        fun checkFileMatchesModule(path: String, moduleName: String?) {
            if (moduleName == null) return
            val segments = path.replace('\\', '/').removeSuffix(".azora").removeSuffix(".az")
                .split('/')
                .filter { it.isNotEmpty() && it != "." && it != SOURCE_ROOT }
            if (segments.isEmpty()) return
            val moduleSegments = moduleName.split('.')

            // The path spells the end of the module; the segments it leaves out
            // are the package prefix, which a single source root cannot know.
            if (segments.size <= moduleSegments.size &&
                segments == moduleSegments.takeLast(segments.size)
            ) {
                return
            }
            // Folder-index `b/b.az` denotes `mod ….b`, so the path carries one
            // segment more than the module - which is why this is checked before
            // any length guard rejects it.
            if (segments.size >= 2 &&
                segments.size <= moduleSegments.size + 1 &&
                segments.last() == segments[segments.size - 2] &&
                segments.dropLast(1) == moduleSegments.takeLast(segments.size - 1)
            ) {
                return
            }
            throw mismatch(path, moduleName, moduleSegments.takeLast(segments.size))
        }

        private fun mismatch(path: String, moduleName: String, expected: List<String>) =
            IllegalArgumentException(
                "file path '$path' does not match module '$moduleName': a file declaring " +
                    "'module $moduleName' must be located at '${expected.joinToString("/")}.az' " +
                    "relative to its source root",
            )

        /** The directory name that starts a package's own module namespace. */
        private const val SOURCE_ROOT = "src"
    }

    private val implicitCollectionTypes = setOf("List", "MutableList", "Set", "MutableSet", "Map", "MutableMap")

    /**
     * Evaluates an `expose if COND` condition against the boolean CLI overrides.
     * `null` (unconditional) and `true` keep the export; an unresolvable or `false`
     * condition drops it. (config.az defaults like `AUTO_IMPORT_MACROS = false` are
     * captured by the `false` fallback when no override is present.)
     */
    private fun evalExportIf(cond: Expr?, boolOverrides: Map<String, Boolean>): Boolean = when (cond) {
        null -> true
        is Expr.BoolLiteral -> cond.value
        is Expr.Identifier -> boolOverrides[cond.name] ?: false
        else -> false
    }

    private class Index {
        /** Library root module names, derived from loaded modules (for example "std"). */
        val roots = LinkedHashSet<String>()
        /** module ("std.math") → name → the item providing it. */
        val modules = LinkedHashMap<String, LinkedHashMap<String, TopLevel>>()
        /** Compile-time type functions are indexed separately because they emit no runtime item. */
        val typeFunctionsByModule = LinkedHashMap<String, MutableList<TypeFunctionDecl>>()
        /** Library-defined named type macros, import-gated by their declaring module. */
        val typeMacrosByModule = LinkedHashMap<String, MutableList<TypeTypeArm>>()
        /** Qualified or short type-function name -> overloads, for internal library dependencies. */
        val typeFunctionsByName = LinkedHashMap<String, MutableList<TypeFunctionDecl>>()
        val alwaysTypeFunctions = mutableListOf<TypeFunctionDecl>()
        val alwaysTypeMacros = mutableListOf<TypeTypeArm>()
        /** Infix operators/macros from every library module (small, additive, always available). */
        val allInfixOperators = LinkedHashSet<String>()
        val allInfixMacros = mutableListOf<org.azora.lang.frontend.InfixMacroRule>()
        /** Flat name → item view (first module wins), for transitive resolution. */
        val items = LinkedHashMap<String, TopLevel>()
        /** name → module that provides it, for import hints. */
        val moduleOfName = LinkedHashMap<String, String>()
        /** Source-level qualified type path → declaration and import metadata. */
        val realmTypesByQualifiedName = LinkedHashMap<String, RealmTypeExport>()
        /** Bare type name → every realm-scoped declaration with that short name. */
        val realmTypesByShortName = LinkedHashMap<String, MutableList<RealmTypeExport>>()
        /** module → its declared visibility, for import gating. */
        val moduleVisibility = LinkedHashMap<String, ModuleVisibility>()
        /** Items from a library's conventional `<root>.core` module. */
        val implicitRootItems = LinkedHashMap<String, TopLevel>()
        /**
         * Top-level items that must be injected into every unit unconditionally,
         * gathered from `expose mod …` declarations (and the conventional
         * `<root>.core` module). Kept as raw items - in particular a `deepinline
         * realm { … }` block is injected whole so CTCE flattens it downstream,
         * exactly as it would inside its own module.
         */
        val alwaysInjectedItems = mutableListOf<TopLevel>()
        /** extern name → single-signature bridge declaring it. */
        val externs = LinkedHashMap<String, TopLevel.Bridge>()
        /** struct/pack name → its `impl` blocks (methods/oper overloads), injected alongside the pack. */
        val implsByType = LinkedHashMap<String, MutableList<TopLevel.Impl>>()
        /**
         * Per-module `expose use …` re-exports. When a program imports [module]
         * (or [module] is auto-injected via `expose mod`), each (path, selected)
         * pair here is also imported transitively - e.g. `std.char` re-exporting
         * `std.char.core` so a bare `import std.char` suffices.
         */
        val exportedImportsByModule = LinkedHashMap<String, MutableList<Pair<String, String?>>>()
        /** Modules published via `export expose module …` (auto-injected into every unit). */
        val alwaysOnModules = mutableListOf<String>()
    }

    private val index: Index by lazy { buildIndex() }

    private fun normalizedTypeName(name: String): String =
        name.substringBefore('<').substringAfter("__")

    /** The bundled-library module providing [name] ("std.math"), or null - used for error hints. */
    fun moduleOf(name: String): String? = index.moduleOfName[name]

    /**
     * Returns the source-level qualified access path for an imported realm member.
     * Members declared by `use realm` or `use friend realm` keep bare names and
     * intentionally return null because they require no explicit realm prefix.
     */
    fun qualifiedAccessOf(name: String, program: Program): String? {
        val item = index.items[name] ?: return null
        val visible = LinkedHashMap<String, TopLevel>().apply {
            putAll(index.implicitRootItems)
            putAll(importedItems(program))
        }
        if (visible.values.none { it == item }) return null
        val declaredName = when (item) {
            is TopLevel.Func -> item.decl.name
            is TopLevel.FinDecl -> item.name
            is TopLevel.LetDecl -> item.name
            is TopLevel.VarDecl -> item.name
            is TopLevel.Bridge -> item.funcs.singleOrNull()?.name
            else -> return null
        } ?: return null
        if ("__" !in declaredName) return null
        return declaredName.split("__").joinToString("::")
    }

    /**
     * Rejects imports that name a namespace/folder rather than an actual module
     * file. `import std` fails because there is no `std` module - only modules
     * *under* `std` (`std.math`, `std.container`, …). Callers that want every
     * module below a namespace write `import std.*`; a specific one, `import
     * std.container`. Unknown roots (e.g. a user's own module) are left alone.
     */
    fun validateImports(program: Program): List<String> {
        if (index.modules.isEmpty()) return emptyList()
        val known = index.modules.keys
        val errors = mutableListOf<String>()
        for (item in program.items) {
            if (item !is TopLevel.UseImport) continue
            for ((path, selected) in item.imports) {
                // Wildcard and selective-item forms are validated by name resolution.
                if (selected != null) continue
                val isExactModule = path in known && isExternallyImportable(path)
                val isSelectedItem = resolveSelectedLibraryPath(path)
                    ?.let { isExternallyImportable(it.first) && index.modules[it.first]?.containsKey(it.second) == true } == true
                if (isExactModule || isSelectedItem) continue
                // Only flag paths that are a real namespace of known modules (so a
                // typo'd or user-defined root is not falsely rejected here).
                if (known.any { it.startsWith("$path.") }) {
                    errors.add(
                        "cannot 'import $path': '$path' is a namespace, not a module - " +
                            "import a specific module such as 'import $path.<name>', or 'import $path.*' " +
                            "to pull in every module below it (line ${item.line})"
                    )
                }
            }
        }
        return errors
    }

    /**
     * Validates source-level type qualification independently from declaration
     * injection. Importing a module makes a type visible, but a type declared in
     * a named realm must still be written as `Realm::Type` outside that realm.
     */
    fun validateTypeAccess(program: Program): List<String> {
        val visibleDeclarations = importedItems(program).values.toSet()
        val localGlobalTypes = program.items.mapNotNull(::typeDeclarationName)
            .filterTo(mutableSetOf()) { it !in program.realmTypeNamespaces }
        val errors = linkedSetOf<String>()

        class Validator {
            fun type(ref: TypeRef, line: Int, typeParams: Set<String>, currentRealm: String?) {
                when (ref) {
                    is TypeRef.Named -> {
                        if (!TypeFunctionCall.isCall(ref) &&
                            ref.name !in typeParams &&
                            ref.name !in localGlobalTypes
                        ) {
                            val localRealm = program.realmTypeNamespaces[ref.name]
                            val exports = index.realmTypesByShortName[ref.name].orEmpty()
                            val visibleExports = exports.filter { it.declaration in visibleDeclarations }
                            if (localRealm != null) {
                                when {
                                    ref.qualifier == null && currentRealm != localRealm ->
                                        errors.add(
                                            "line $line: undefined type '${ref.name}'; '${ref.name}' is part of " +
                                                "realm '$localRealm', use '$localRealm::${ref.name}' instead",
                                        )
                                    ref.qualifier != null && ref.qualifier != localRealm ->
                                        errors.add(
                                            "line $line: undefined type '${ref.qualifier}::${ref.name}'; " +
                                                "'${ref.name}' is part of realm '$localRealm'",
                                        )
                                }
                            } else if (ref.qualifier == null) {
                                visibleExports.firstOrNull()?.let { export ->
                                    errors.add(
                                        "line $line: undefined type '${ref.name}'; '${ref.name}' is part of " +
                                            "realm '${export.qualifier}', use '${export.qualifiedName}' instead",
                                    )
                                }
                            } else {
                                val qualified = "${ref.qualifier}::${ref.name}"
                                val export = index.realmTypesByQualifiedName[qualified]
                                when {
                                    export != null && export.declaration !in visibleDeclarations ->
                                        errors.add(
                                            "line $line: undefined type '$qualified' - '${ref.name}' is provided by " +
                                                "'${export.module}': add 'use ${export.module}'",
                                        )
                                    export == null && visibleExports.isNotEmpty() -> {
                                        val declared = visibleExports.first()
                                        errors.add(
                                            "line $line: undefined type '$qualified'; '${ref.name}' is part of " +
                                                "realm '${declared.qualifier}'",
                                        )
                                    }
                                }
                            }
                        }
                        ref.args.forEach { type(it, line, typeParams, currentRealm) }
                    }
                    is TypeRef.Array -> type(ref.element, line, typeParams, currentRealm)
                    is TypeRef.Map -> {
                        type(ref.key, line, typeParams, currentRealm)
                        type(ref.value, line, typeParams, currentRealm)
                    }
                    is TypeRef.Set -> type(ref.element, line, typeParams, currentRealm)
                    is TypeRef.Function -> {
                        ref.params.forEach { type(it, line, typeParams, currentRealm) }
                        ref.receivers.forEach { type(it, line, typeParams, currentRealm) }
                        type(ref.ret, line, typeParams, currentRealm)
                    }
                    is TypeRef.Tuple -> ref.elements.forEach { type(it, line, typeParams, currentRealm) }
                    is TypeRef.Nullable -> type(ref.inner, line, typeParams, currentRealm)
                    is TypeRef.Failable -> type(ref.ok, line, typeParams, currentRealm)
                    is TypeRef.Pointer -> type(ref.inner, line, typeParams, currentRealm)
                    is TypeRef.Reference -> type(ref.inner, line, typeParams, currentRealm)
                    is TypeRef.Const -> {}
                }
            }

            fun annotation(value: TypeAnnotation, line: Int, typeParams: Set<String>, currentRealm: String?) {
                if (value is TypeAnnotation.Explicit) type(value.ref, line, typeParams, currentRealm)
            }

            fun function(func: FuncDecl, currentRealm: String?) {
                val typeParams = func.typeParams.toSet()
                func.params.forEach {
                    type(it.type, func.line, typeParams, currentRealm)
                    it.defaultValue?.let { value -> expression(value, typeParams, currentRealm) }
                }
                annotation(func.returnType, func.line, typeParams, currentRealm)
                func.body.forEach { statement(it, typeParams, currentRealm) }
            }

            fun expression(expr: Expr, typeParams: Set<String>, currentRealm: String?) {
                when (expr) {
                    is Expr.Binary -> {
                        expression(expr.left, typeParams, currentRealm)
                        expression(expr.right, typeParams, currentRealm)
                    }
                    is Expr.Unary -> expression(expr.operand, typeParams, currentRealm)
                    is Expr.Call -> {
                        expr.typeArgs.forEach { type(it, expr.line, typeParams, currentRealm) }
                        expr.args.forEach { expression(it, typeParams, currentRealm) }
                        expr.receiver?.let { expression(it, typeParams, currentRealm) }
                    }
                    is Expr.Grouping -> expression(expr.expr, typeParams, currentRealm)
                    is Expr.Range -> {
                        expression(expr.from, typeParams, currentRealm)
                        expression(expr.to, typeParams, currentRealm)
                    }
                    is Expr.ArrayLiteral -> expr.elements.forEach { expression(it, typeParams, currentRealm) }
                    is Expr.SetLiteral -> expr.elements.forEach { expression(it, typeParams, currentRealm) }
                    is Expr.Index -> {
                        expression(expr.target, typeParams, currentRealm)
                        expression(expr.index, typeParams, currentRealm)
                    }
                    is Expr.Member -> expression(expr.target, typeParams, currentRealm)
                    is Expr.MethodCall -> {
                        expression(expr.target, typeParams, currentRealm)
                        expr.args.forEach { expression(it, typeParams, currentRealm) }
                    }
                    is Expr.StringTemplate -> expr.parts.forEach {
                        if (it is Expr.StringTemplatePart.Expr) {
                            expression(it.expr, typeParams, currentRealm)
                        }
                    }
                    is Expr.TupleLit -> expr.elements.forEach { expression(it, typeParams, currentRealm) }
                    is Expr.VariantLit -> expr.elements.forEach { expression(it, typeParams, currentRealm) }
                    is Expr.TupleAccess -> expression(expr.target, typeParams, currentRealm)
                    is Expr.CatchExpr -> {
                        expression(expr.expr, typeParams, currentRealm)
                        expression(expr.fallback, typeParams, currentRealm)
                    }
                    is Expr.TryPropagate -> expression(expr.expr, typeParams, currentRealm)
                    is Expr.IfExpr -> {
                        expression(expr.condition, typeParams, currentRealm)
                        expression(expr.thenExpr, typeParams, currentRealm)
                        expression(expr.elseExpr, typeParams, currentRealm)
                    }
                    is Expr.Lambda -> {
                        expr.params.forEach {
                            type(it.type, expr.line, typeParams, currentRealm)
                            it.defaultValue?.let { value -> expression(value, typeParams, currentRealm) }
                        }
                        expr.receivers.forEach { type(it.type, expr.line, typeParams, currentRealm) }
                        expr.body.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Expr.NamedArg -> expression(expr.value, typeParams, currentRealm)
                    is Expr.NullCoalesce -> {
                        expression(expr.left, typeParams, currentRealm)
                        expression(expr.right, typeParams, currentRealm)
                    }
                    is Expr.SafeMember -> expression(expr.target, typeParams, currentRealm)
                    is Expr.Cast -> {
                        expression(expr.expr, typeParams, currentRealm)
                        type(expr.targetType, expr.line, typeParams, currentRealm)
                    }
                    is Expr.IsCheck -> expression(expr.expr, typeParams, currentRealm)
                    is Expr.MapLit -> expr.entries.forEach { (key, value) ->
                        expression(key, typeParams, currentRealm)
                        expression(value, typeParams, currentRealm)
                    }
                    is Expr.Alloc -> expression(expr.value, typeParams, currentRealm)
                    is Expr.AllocBuffer -> expression(expr.count, typeParams, currentRealm)
                    is Expr.Deref -> expression(expr.target, typeParams, currentRealm)
                    is Expr.Isolated -> expression(expr.value, typeParams, currentRealm)
                    is Expr.Await -> expression(expr.value, typeParams, currentRealm)
                    is Expr.Spread -> expression(expr.array, typeParams, currentRealm)
                    is Expr.MetaInvoke -> expr.args.forEach { expression(it, typeParams, currentRealm) }
                    is Expr.Slice -> {
                        expression(expr.target, typeParams, currentRealm)
                        expr.start?.let { expression(it, typeParams, currentRealm) }
                        expr.stop?.let { expression(it, typeParams, currentRealm) }
                        expr.step?.let { expression(it, typeParams, currentRealm) }
                    }
                    else -> {}
                }
            }

            fun statement(stmt: Stmt, typeParams: Set<String>, currentRealm: String?) {
                when (stmt) {
                    is Stmt.VarDecl -> {
                        annotation(stmt.type, stmt.line, typeParams, currentRealm)
                        expression(stmt.initializer, typeParams, currentRealm)
                    }
                    is Stmt.FinDecl -> {
                        annotation(stmt.type, stmt.line, typeParams, currentRealm)
                        expression(stmt.initializer, typeParams, currentRealm)
                    }
                    is Stmt.LetDecl -> {
                        annotation(stmt.type, stmt.line, typeParams, currentRealm)
                        expression(stmt.initializer, typeParams, currentRealm)
                    }
                    is Stmt.InlineVar -> {
                        annotation(stmt.type, stmt.line, typeParams, currentRealm)
                        expression(stmt.initializer, typeParams, currentRealm)
                    }
                    is Stmt.InlineFin -> {
                        annotation(stmt.type, stmt.line, typeParams, currentRealm)
                        expression(stmt.initializer, typeParams, currentRealm)
                    }
                    is Stmt.InlineLet -> {
                        annotation(stmt.type, stmt.line, typeParams, currentRealm)
                        expression(stmt.initializer, typeParams, currentRealm)
                    }
                    is Stmt.RemDecl -> {
                        annotation(stmt.type, stmt.line, typeParams, currentRealm)
                        expression(stmt.initializer, typeParams, currentRealm)
                    }
                    is Stmt.InlineAssignment -> expression(stmt.value, typeParams, currentRealm)
                    is Stmt.Assignment -> expression(stmt.value, typeParams, currentRealm)
                    is Stmt.Return -> stmt.value?.let { expression(it, typeParams, currentRealm) }
                    is Stmt.ExprStmt -> expression(stmt.expr, typeParams, currentRealm)
                    is Stmt.If -> {
                        expression(stmt.condition, typeParams, currentRealm)
                        stmt.thenBranch.forEach { statement(it, typeParams, currentRealm) }
                        stmt.elseBranch?.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Stmt.InlineIf -> {
                        expression(stmt.condition, typeParams, currentRealm)
                        stmt.thenBranch.forEach { statement(it, typeParams, currentRealm) }
                        stmt.elseBranch?.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Stmt.DeepInlineIf -> {
                        expression(stmt.condition, typeParams, currentRealm)
                        stmt.thenBranch.forEach { statement(it, typeParams, currentRealm) }
                        stmt.elseBranch?.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Stmt.Assert -> {
                        expression(stmt.condition, typeParams, currentRealm)
                        expression(stmt.message, typeParams, currentRealm)
                    }
                    is Stmt.Trace -> {
                        expression(stmt.message, typeParams, currentRealm)
                        stmt.level?.let { expression(it, typeParams, currentRealm) }
                    }
                    is Stmt.InlineAssert -> {
                        expression(stmt.condition, typeParams, currentRealm)
                        expression(stmt.message, typeParams, currentRealm)
                    }
                    is Stmt.InlineTrace -> {
                        expression(stmt.message, typeParams, currentRealm)
                        stmt.level?.let { expression(it, typeParams, currentRealm) }
                    }
                    is Stmt.While -> {
                        expression(stmt.condition, typeParams, currentRealm)
                        stmt.body.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Stmt.For -> {
                        expression(stmt.iterable, typeParams, currentRealm)
                        stmt.step?.let { expression(it, typeParams, currentRealm) }
                        stmt.body.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Stmt.InlineFor -> {
                        expression(stmt.iterable, typeParams, currentRealm)
                        stmt.body.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Stmt.Loop -> {
                        stmt.iterable?.let { expression(it, typeParams, currentRealm) }
                        stmt.body.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Stmt.IndexAssign -> {
                        expression(stmt.target, typeParams, currentRealm)
                        expression(stmt.index, typeParams, currentRealm)
                        expression(stmt.value, typeParams, currentRealm)
                    }
                    is Stmt.MemberAssign -> {
                        expression(stmt.target, typeParams, currentRealm)
                        expression(stmt.value, typeParams, currentRealm)
                    }
                    is Stmt.When -> {
                        expression(stmt.scrutinee, typeParams, currentRealm)
                        stmt.branches.forEach { branch ->
                            branch.patterns.forEach { expression(it, typeParams, currentRealm) }
                            branch.body.forEach { statement(it, typeParams, currentRealm) }
                        }
                        stmt.elseBranch?.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Stmt.Throw -> expression(stmt.value, typeParams, currentRealm)
                    is Stmt.Panic -> expression(stmt.message, typeParams, currentRealm)
                    is Stmt.DerefAssign -> {
                        expression(stmt.target, typeParams, currentRealm)
                        expression(stmt.value, typeParams, currentRealm)
                    }
                    is Stmt.Yield -> expression(stmt.value, typeParams, currentRealm)
                    is Stmt.Try -> {
                        stmt.body.forEach { statement(it, typeParams, currentRealm) }
                        stmt.catchBody?.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Stmt.Defer -> stmt.body.forEach { statement(it, typeParams, currentRealm) }
                    is Stmt.Scope -> stmt.body.forEach { statement(it, typeParams, currentRealm) }
                    is Stmt.InlineBlock -> stmt.body.forEach { statement(it, typeParams, currentRealm) }
                    is Stmt.DeepInlineBlock -> stmt.body.forEach { statement(it, typeParams, currentRealm) }
                    is Stmt.Effect -> {
                        stmt.dependencies?.forEach { expression(it, typeParams, currentRealm) }
                        stmt.body.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Stmt.WithContext -> {
                        stmt.values.forEach { expression(it, typeParams, currentRealm) }
                        stmt.body.forEach { statement(it, typeParams, currentRealm) }
                    }
                    is Stmt.NoInline -> statement(stmt.stmt, typeParams, currentRealm)
                    else -> {}
                }
            }
        }

        val validator = Validator()
        for (item in program.items) {
            val currentRealm = itemRealm(item)
                ?: typeDeclarationName(item)?.let(program.realmTypeNamespaces::get)
            when (item) {
                is TopLevel.Func -> validator.function(item.decl, currentRealm)
                is TopLevel.FinDecl -> {
                    item.type?.let { validator.type(it, item.line, emptySet(), currentRealm) }
                    validator.expression(item.initializer, emptySet(), currentRealm)
                }
                is TopLevel.LetDecl -> {
                    item.type?.let { validator.type(it, item.line, emptySet(), currentRealm) }
                    validator.expression(item.initializer, emptySet(), currentRealm)
                }
                is TopLevel.VarDecl -> {
                    item.type?.let { validator.type(it, item.line, emptySet(), currentRealm) }
                    validator.expression(item.initializer, emptySet(), currentRealm)
                }
                is TopLevel.Test -> item.body.forEach { validator.statement(it, emptySet(), currentRealm) }
                is TopLevel.Pack -> {
                    val typeParams = item.typeParams.toSet()
                    item.fields.forEach {
                        validator.type(it.type, item.line, typeParams, currentRealm)
                        it.default?.let { value -> validator.expression(value, typeParams, currentRealm) }
                    }
                }
                is TopLevel.Solo -> {
                    item.fields.forEach {
                        validator.type(it.type, item.line, emptySet(), currentRealm)
                        it.default?.let { value -> validator.expression(value, emptySet(), currentRealm) }
                    }
                    item.methods.forEach { validator.function(it, currentRealm) }
                }
                is TopLevel.Impl -> {
                    val typeParams = item.typeParams.toSet()
                    item.traitArgs.forEach { validator.type(it, item.line, typeParams, currentRealm) }
                    item.decoratorArgs.forEach { validator.expression(it, typeParams, currentRealm) }
                    item.decoratorNamedArgs.forEach { (_, value) ->
                        validator.expression(value, typeParams, currentRealm)
                    }
                    item.methods.forEach { validator.function(it, currentRealm) }
                }
                is TopLevel.Spec -> item.methods.forEach { validator.function(it, currentRealm) }
                is TopLevel.Deco -> item.fields.forEach {
                    validator.type(it.type, item.line, emptySet(), currentRealm)
                }
                is TopLevel.Slot -> item.variants.forEach { variant ->
                    variant.payloadTypes.forEach {
                        validator.type(it, item.line, emptySet(), currentRealm)
                    }
                }
                is TopLevel.TypeAlias ->
                    validator.type(item.type, item.line, item.typeParams.toSet(), currentRealm)
                is TopLevel.Bridge -> item.funcs.forEach { signature ->
                    val typeParams = signature.typeParams.toSet()
                    signature.params.forEach {
                        validator.type(it.type, signature.line, typeParams, currentRealm)
                    }
                    validator.type(signature.returnType, signature.line, typeParams, currentRealm)
                }
                else -> {}
            }
        }
        return errors.toList()
    }

    /** The name a top-level item declares, whatever kind of declaration it is. */
    private fun declaredNameOf(item: TopLevel): String? = when (item) {
        is TopLevel.Func -> item.decl.name
        is TopLevel.FinDecl -> item.name
        is TopLevel.LetDecl -> item.name
        is TopLevel.VarDecl -> item.name
        else -> typeDeclarationName(item)
    }

    private fun typeDeclarationName(item: TopLevel): String? = when (item) {
        is TopLevel.Pack -> item.name
        is TopLevel.Enum -> item.name
        is TopLevel.Fail -> item.name
        is TopLevel.Spec -> item.name
        is TopLevel.Deco -> item.name
        is TopLevel.Slot -> item.name
        is TopLevel.Solo -> item.name
        is TopLevel.TypeAlias -> item.name
        else -> null
    }

    private fun itemRealm(item: TopLevel): String? {
        if (item is TopLevel.Impl) {
            return item.realmPrefix?.replace("__", "::")
        }
        val mangledName = when (item) {
            is TopLevel.Func -> item.decl.name
            is TopLevel.FinDecl -> item.name
            is TopLevel.LetDecl -> item.name
            is TopLevel.VarDecl -> item.name
            else -> return null
        }
        return mangledName.substringBeforeLast("__", "")
            .takeIf { it.isNotEmpty() }
            ?.replace("__", "::")
    }

    private fun buildIndex(): Index {
        val idx = Index()
        val boolOverrides = configOverrides.mapValues { it.value.trim() == "true" }
        for (program in programs) {
            val module = program.moduleName ?: continue
            val root = module.substringBefore('.')
            idx.roots.add(root)
            idx.moduleVisibility.putIfAbsentCompat(module, program.moduleVisibility)
            val moduleItems = idx.modules.getOrPut(module) { LinkedHashMap() }
            idx.typeFunctionsByModule.getOrPut(module) { mutableListOf() }.addAll(program.typeFunctions)
            idx.typeMacrosByModule.getOrPut(module) { mutableListOf() }.addAll(program.typeMacroRules)
            idx.allInfixOperators.addAll(program.infixOperators)
            idx.allInfixMacros.addAll(program.infixMacros)
            for (declaration in program.typeFunctions) {
                idx.typeFunctionsByName.getOrPut(declaration.name) { mutableListOf() }.add(declaration)
                val shortName = declaration.name.substringAfterLast("__")
                if (shortName != declaration.name) {
                    idx.typeFunctionsByName.getOrPut(shortName) { mutableListOf() }.add(declaration)
                }
            }
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
            // declared `export expose module …` (the default visibility). The module
            // name is irrelevant. `expose confine` auto-imports only
            // within the library/folder, so they are not injected into external units
            // here; `expose confine` is rejected at parse time.
            val alwaysOn = program.isExported && evalExportIf(program.exportCondition, boolOverrides) &&
                program.moduleVisibility == ModuleVisibility.PUBLIC
            for (item in program.items) {
                when (item) {
                    is TopLevel.Func -> register(item.decl.name, item)
                    is TopLevel.FinDecl -> register(item.name, item)
                    is TopLevel.LetDecl -> register(item.name, item)
                    is TopLevel.VarDecl -> register(item.name, item)
                    // Compile-time constants from `impl realm` / inline blocks (e.g.
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
                    is TopLevel.TypeAlias -> register(item.name, item)
                    is TopLevel.Meta -> register(item.name, item)
                    is TopLevel.Impl -> {
                        val owner = item.typeName.substringBefore('.')
                        val keys = linkedSetOf(item.typeName, normalizedTypeName(item.typeName), owner)
                        for (key in keys) {
                            idx.implsByType.getOrPut(key) { mutableListOf() }.add(item)
                        }
                    }
                    is TopLevel.Bridge -> for (sig in item.funcs) {
                        val declaration = TopLevel.Bridge(
                            item.target,
                            listOf(sig),
                            item.line,
                            item.column,
                            item.annotations,
                        )
                        idx.externs.putIfAbsentCompat(sig.name, declaration)
                        register(sig.name, declaration)
                    }
                    // `deepinline realm { … }` and similar compile-time blocks (e.g.
                    // `std.config`) carry their declarations opaquely; inject them
                    // whole so CTCE flattens them downstream just as it would in
                    // the module itself, rather than lifting each nested constant.
                    is TopLevel.InlineBlock, is TopLevel.DeepInlineBlock,
                    is TopLevel.InlineIf, is TopLevel.DeepInlineIf ->
                        if (alwaysOn) idx.alwaysInjectedItems.add(item)
                    else -> {}
                }
            }
            for ((shortName, qualifier) in program.realmTypeNamespaces) {
                val declaration = moduleItems[shortName] ?: continue
                val export = RealmTypeExport(shortName, qualifier, module, declaration)
                idx.realmTypesByQualifiedName.putIfAbsentCompat(export.qualifiedName, export)
                idx.realmTypesByShortName.getOrPut(shortName) { mutableListOf() }.add(export)
            }
            // Record this module's `expose use …` re-exports for transitive
            // import propagation, and (if always-on) the module name itself.
            for (item in program.items) {
                if (item is TopLevel.UseImport && item.exported && evalExportIf(item.condition, boolOverrides)) {
                    idx.exportedImportsByModule.getOrPut(module) { mutableListOf() }.addAll(item.imports)
                }
            }
            if (alwaysOn) {
                idx.alwaysOnModules.add(module)
                moduleItems.forEach { (name, item) -> idx.implicitRootItems.putIfAbsentCompat(name, item) }
                idx.alwaysTypeFunctions.addAll(program.typeFunctions)
                idx.alwaysTypeMacros.addAll(program.typeMacroRules)
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
        // Seed with the program's own imports, plus the re-exports of any
        // `expose mod` library that is auto-injected into every unit - its
        // `expose use …` declarations apply to importers transitively.
        val seeds = ArrayDeque<Pair<String, String?>>()
        for (item in program.items) {
            if (item is TopLevel.UseImport && !item.exported) seeds.addAll(item.imports)
        }
        for (module in index.alwaysOnModules) {
            seeds.addAll(index.exportedImportsByModule[module].orEmpty())
        }
        // Expand transitively: resolving a module also pulls in its `export import`
        // re-exports, and so on (visited guards against cycles).
        val visited = mutableSetOf<String>()
        while (seeds.isNotEmpty()) {
            val (path, selected) = seeds.removeFirst()
            val key = "$path::${selected ?: "*"}"
            if (!visited.add(key)) continue
            for ((name, declaration) in itemsVisibleFromImport(path, selected)) {
                visible.putIfAbsentCompat(name, declaration)
            }
            for (module in modulesForPath(path)) {
                seeds.addAll(index.exportedImportsByModule[module].orEmpty())
            }
        }
        return visible
    }

    /**
     * Every library module this [program] can name - its own imports expanded
     * transitively through re-exports, plus the always-on modules.
     *
     * Used to decide whether an extension declared on a common type is actually
     * within reach. It mirrors [importedItems]'s traversal but keeps the module
     * names rather than the declarations they contribute.
     */
    private fun reachableModules(program: Program): Set<String> {
        val reached = mutableSetOf<String>()
        reached.addAll(index.alwaysOnModules)
        val seeds = ArrayDeque<Pair<String, String?>>()
        for (item in program.items) {
            if (item is TopLevel.UseImport && !item.exported) seeds.addAll(item.imports)
        }
        for (module in index.alwaysOnModules) {
            seeds.addAll(index.exportedImportsByModule[module].orEmpty())
        }
        val visited = mutableSetOf<String>()
        while (seeds.isNotEmpty()) {
            val (path, selected) = seeds.removeFirst()
            if (!visited.add("$path::${selected ?: "*"}")) continue
            for (module in modulesForPath(path)) {
                reached.add(module)
                seeds.addAll(index.exportedImportsByModule[module].orEmpty())
            }
        }
        return reached
    }

    /** The library module(s) an import [path] reaches: itself if exact, else descendant modules. */
    private fun modulesForPath(path: String): List<String> =
        if (index.modules.containsKey(path)) listOf(path)
        else index.modules.keys.filter { it.startsWith("$path.") }.toList()

    private fun importedTypeFunctions(program: Program): List<TypeFunctionDecl> {
        val visible = mutableListOf<TypeFunctionDecl>()
        for (item in program.items) {
            if (item !is TopLevel.UseImport) continue
            for ((path, selected) in item.imports) {
                val modules = when {
                    index.typeFunctionsByModule.containsKey(path) -> listOf(path)
                    else -> index.typeFunctionsByModule.keys.filter { it.startsWith("$path.") }
                }.filter(::isExternallyImportable)
                for (module in modules) {
                    val declarations = index.typeFunctionsByModule[module].orEmpty()
                    val selectedDeclarations = if (selected == null || selected == "*") declarations else declarations.filter { declaration ->
                        declaration.name == selected || declaration.name.substringAfterLast("__") == selected
                    }
                    for (declaration in selectedDeclarations) {
                        visible.add(declaration)
                    }
                }
            }
        }
        return visible
    }

    private fun importedTypeMacros(program: Program): List<TypeTypeArm> {
        val visible = mutableListOf<TypeTypeArm>()
        val seeds = ArrayDeque<Pair<String, String?>>()
        for (item in program.items) {
            if (item is TopLevel.UseImport && !item.exported) seeds.addAll(item.imports)
        }
        for (module in index.alwaysOnModules) {
            seeds.addAll(index.exportedImportsByModule[module].orEmpty())
        }

        // Type macros obey the same transitive `export import` visibility as
        // ordinary declarations. A facade module may therefore re-export a
        // library-defined grammar without copying or compiler-registering it.
        val visited = mutableSetOf<String>()
        while (seeds.isNotEmpty()) {
            val (path, selected) = seeds.removeFirst()
            val key = "$path::${selected ?: "*"}"
            if (!visited.add(key)) continue
            for (module in modulesForPath(path).filter(::isExternallyImportable)) {
                val declarations = index.typeMacrosByModule[module].orEmpty()
                visible.addAll(
                    if (selected == null || selected == "*") declarations
                    else declarations.filter { it.name == selected },
                )
                seeds.addAll(index.exportedImportsByModule[module].orEmpty())
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
        index.moduleVisibility[module]?.let { it == ModuleVisibility.PUBLIC } ?: true

    private fun itemsVisibleFromImport(path: String, selected: String?): Map<String, TopLevel> {
        // `import path.*` - wildcard: the exact module at `path` (if any) plus every
        // descendant module. This is the only form that pulls in a whole namespace.
        if (selected == "*") {
            val result = LinkedHashMap<String, TopLevel>()
            if (isExternallyImportable(path)) {
                index.modules[path]?.forEach { (name, declaration) -> result.putIfAbsentCompat(name, declaration) }
            }
            index.modules
                .filterKeys { it.startsWith("$path.") && isExternallyImportable(it) }
                .values.forEach { module ->
                    module.forEach { (name, declaration) -> result.putIfAbsentCompat(name, declaration) }
                }
            return result
        }
        if (selected != null) {
            if (!isExternallyImportable(path)) return emptyMap()
            val module = index.modules[path] ?: return emptyMap()
            return module[selected]?.let { mapOf(selected to it) } ?: emptyMap()
        }
        // `import path` - plain: `path` must name an actual module file, or resolve to
        // a single `module.item` selection. A bare namespace/folder (e.g. `std`, which
        // has no `std.az`) pulls in nothing; `validateImports` rejects it up front.
        if (index.modules[path] != null && isExternallyImportable(path)) return index.modules[path]!!
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
                is TopLevel.Meta -> names.add(item.name)
                else -> {}
            }
        }
        return names
    }

    /**
     * Returns [program] with every bundled-library item it references appended.
     *
     * Visibility is granted only by explicit `import` declarations. Realm
     * members are name-mangled at parse time (`std.math::abs` → `std__math__abs`),
     * so [importedItems] returns the mangled items exported by imported
     * modules. A reference resolves only if it is both mangled (written as a
     * qualified `Realm::name` path) and visible (its module was imported). Bare
     * references never match a mangled name, so bare access to library symbols
     * is rejected. Returns the program unchanged when nothing is referenced.
     */
    fun inject(program: Program): Program {
        if (
            index.items.isEmpty() &&
            index.externs.isEmpty() &&
            index.typeFunctionsByModule.isEmpty() &&
            index.typeMacrosByModule.isEmpty()
        ) return program

        val importedTypeFunctions = index.alwaysTypeFunctions + importedTypeFunctions(program)
        val typeMacros = (
            program.typeMacroRules + index.alwaysTypeMacros + importedTypeMacros(program)
        ).distinct()
        val typeMacrosChanged = typeMacros.size != program.typeMacroRules.size

        val shadowed = userDeclaredNames(program)
        val reachable = reachableModules(program)

        val visible = LinkedHashMap<String, TopLevel>().apply {
            putAll(index.implicitRootItems)
            putAll(importedItems(program))
        }

        val referenced = mutableSetOf<String>()
        for (item in program.items) collectNamesFromItem(item, referenced)
        // Exported/core blocks are always injected; pull in whatever they reference.
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
                    attachImplsForType(name, injected, next, reachable)
                    attachStaticMembersForType(name, injected, next)
                    val transitive = mutableSetOf<String>()
                    collectNamesFromItem(item, transitive)
                    next.addAll(transitive)
                    continue
                }
                attachImplsForType(name, injected, next, reachable)
                attachStaticMembersForType(name, injected, next)
                if (name !in injectedExterns) {
                    index.externs[name]?.let { injectedExterns[name] = it }
                }
            }
            frontier = next
        }
        if (StdlibInjector.DEBUG_INJECT) {
            println("[inject] shadowed=${shadowed.filter { "erial" in it }}")
            println("[inject] visibleSerial=${visible.keys.filter { "erial" in it }.sorted()}")
            println("[inject] referencedSerial=${referenced.filter { "erial" in it }.sorted()}")
            println("[inject] injectedSerial=${injected.keys.filter { "erial" in it }.sorted()}")
            println("[inject] indexHasIsValid=${index.items.containsKey("isValidSerialNumber")}")
        }

        // Runtime declarations may depend on private compile-time type functions.
        // Resolve those dependencies without making them visible merely because
        // user source mentioned an unimported type-function name.
        val dependencyNames = mutableSetOf<String>()
        injected.values.forEach { collectNamesFromItem(it, dependencyNames) }
        index.alwaysInjectedItems.forEach { collectNamesFromItem(it, dependencyNames) }
        val dependencyTypeFunctions = dependencyNames.flatMap { name ->
            index.typeFunctionsByName[name].orEmpty()
        }
        val userTypeNames = mutableSetOf<String>()
        program.items.forEach { collectNamesFromItem(it, userTypeNames) }
        val fullyQualifiedTypeFunctions = userTypeNames
            .filter(ModuleQualifiedSymbol::isQualified)
            .flatMap { encodedName ->
                val module = ModuleQualifiedSymbol.module(encodedName)
                val symbol = ModuleQualifiedSymbol.symbol(encodedName)
                if (!isExternallyImportable(module)) emptyList()
                else index.typeFunctionsByModule[module]
                    .orEmpty()
                    .filter { it.name == symbol }
                    .map { it.copy(name = encodedName) }
            }
        // Injection runs twice; remove the exact same declaration object while
        // preserving independently declared duplicate signatures for diagnostics.
        val typeFunctions = (
            program.typeFunctions + importedTypeFunctions + dependencyTypeFunctions + fullyQualifiedTypeFunctions
        ).distinct()
        val typeFunctionsChanged = typeFunctions.size != program.typeFunctions.size

        if (
            injected.isEmpty() &&
            injectedExterns.isEmpty() &&
            index.alwaysInjectedItems.isEmpty() &&
            !typeFunctionsChanged &&
            !typeMacrosChanged
        ) return program
        // One declaration can be indexed by both its qualified and short export
        // names. Preserve discovery order while appending each AST item once.
        val existingIdentities = program.items.mapTo(mutableSetOf()) { itemIdentity(it) }
        val declarations = injected.values.distinct().filter { existingIdentities.add(itemIdentity(it)) }
        val externDeclarations = injectedExterns.values.distinct().filter { existingIdentities.add(itemIdentity(it)) }
        // Exported/core compile-time blocks are injected unconditionally.
        val alwaysDeclarations = index.alwaysInjectedItems.filter { existingIdentities.add(itemIdentity(it)) }
        val injectedRealmTypeNamespaces = buildMap {
            for (declaration in declarations + alwaysDeclarations) {
                val name = typeDeclarationName(declaration) ?: continue
                val qualifier = index.realmTypesByShortName[name]
                    ?.firstOrNull { it.declaration === declaration }
                    ?.qualifier
                    ?: continue
                put(name, qualifier)
            }
        }
        if (
            declarations.isEmpty() &&
            externDeclarations.isEmpty() &&
            alwaysDeclarations.isEmpty() &&
            !typeFunctionsChanged &&
            !typeMacrosChanged
        ) return program
        return IntraRealmRewriter.rewrite(program.copy(
            items = program.items + declarations + externDeclarations + alwaysDeclarations,
            typeFunctions = typeFunctions,
            typeMacroRules = typeMacros,
            infixOperators = program.infixOperators + index.allInfixOperators,
            infixMacros = program.infixMacros + index.allInfixMacros,
            realmTypeNamespaces = program.realmTypeNamespaces + injectedRealmTypeNamespaces,
        ))
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
        is TopLevel.Meta -> "meta:${item.name}"
        is TopLevel.Impl -> "impl:${item.typeName}:${item.traitName.orEmpty()}:${item.line}:${item.column}"
        is TopLevel.Bridge -> "bridge:${item.target}:${item.funcs.joinToString(",") { it.name }}"
        else -> item.toString()
    }

    /**
     * Top-level items grouped by the type whose `::` block declared them.
     *
     * `impl Vec<T, N>:: { fin zero = … }` becomes the top-level `Vec__zero`, so the
     * owner is everything before the last `__`.
     */
    private val staticMembersByOwner: Map<String, List<String>> by lazy {
        index.items.keys
            .filter { "__" in it }
            .groupBy { it.substringBeforeLast("__") }
    }

    /**
     * Pulls in the members a type's `::` block declared.
     *
     * They are part of the type in the same way its impls are: `Vec3f::zero` names
     * one, and nothing else in the consumer's source mentions `Vec__zero` for the
     * dependency walk to find.
     */
    private fun attachStaticMembersForType(
        typeName: String,
        injected: LinkedHashMap<String, TopLevel>,
        next: MutableList<String>,
    ) {
        // Only a declared type has a `::` block. Without this, a module-qualified
        // name (`std__io__println`) would look like a member of a type `std__io`.
        val owner = index.items[typeName]
        if (owner !is TopLevel.Pack && owner !is TopLevel.Enum) return
        for (member in staticMembersByOwner[typeName].orEmpty()) {
            if (member in injected) continue
            val item = index.items[member] ?: continue
            injected[member] = item
            val names = mutableSetOf<String>()
            collectNamesFromItem(item, names)
            next.addAll(names)
        }
    }

    private fun attachImplsForType(
        typeName: String,
        injected: LinkedHashMap<String, TopLevel>,
        next: MutableList<String>,
        reachableModules: Set<String>? = null,
    ) {
        val keys = linkedSetOf(typeName, normalizedTypeName(typeName))
        for (keyName in keys) {
            index.implsByType[keyName]?.filter { impl ->
                // An impl on a widely-used type (`Int`, `String`) must not arrive
                // just because the program mentions that type: a unit-suffix
                // extension in an unimported module would otherwise drag its whole
                // module's transitive closure into every program. A module the
                // program cannot see contributes nothing it could have called.
                reachableModules == null ||
                    impl.declaringModule == null ||
                    impl.declaringModule in reachableModules
            }?.forEach { impl ->
                // Include the member names: a multi-operator impl (`oper[.. , reverse..]`)
                // expands to several impls sharing one source position, so position
                // alone would collapse them into one.
                val members = impl.methods.joinToString(",") { it.name }
                val key = "impl::${normalizedTypeName(impl.typeName)}::${impl.traitName.orEmpty()}::${impl.line}:${impl.column}::$members"
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
    // -----------------------------------------------------------------
    // Underscore privacy
    // -----------------------------------------------------------------

    /**
     * True when [name] belongs to the module that declares it and nowhere else.
     *
     * A leading underscore is the whole rule, but realm members arrive mangled,
     * so the underscore to look at is the member's, not the separator's.
     */
    private fun isPrivateName(name: String): Boolean = memberSegmentOf(name).startsWith("_")

    /**
     * The member part of a possibly realm-mangled name.
     *
     * `realm Secret { func _hidden }` mangles to `Secret___hidden` - a `__`
     * separator immediately followed by the member's own underscore. Splitting on
     * the last `__` would swallow that underscore and report the member as
     * public, so the separator is the last `__` that is not itself preceded by
     * one: `Secret___hidden` → `_hidden`, `std__math__PI` → `PI`.
     */
    private fun memberSegmentOf(name: String): String {
        for (i in name.length - 2 downTo 1) {
            if (name[i] == '_' && name[i + 1] == '_' && name[i - 1] != '_') return name.substring(i + 2)
        }
        return name
    }

    /**
     * Rejects references to another module's private declarations.
     *
     * The declaration is still *injected* - a public function that calls its own
     * module's `_helper` has to keep working for whoever imports it. What is
     * withheld is the right to name it from outside, which is what privacy
     * actually means here.
     */
    fun validatePrivateAccess(program: Program): List<String> {
        val imported = importedItems(program)
        val privateNames = imported.keys.filterTo(mutableSetOf()) { isPrivateName(it) }
        if (privateNames.isEmpty()) return emptyList()

        // The program's own declarations shadow imports, so a local `_helper`
        // is the caller's own and never someone else's private one.
        val ownNames = program.items.mapNotNullTo(mutableSetOf()) { declaredNameOf(it) }

        val referenced = mutableSetOf<String>()
        for (item in program.items) collectNamesFromItem(item, referenced)

        val errors = linkedSetOf<String>()
        for (name in referenced) {
            if (name !in privateNames || name in ownNames) continue
            val owner = index.moduleOfName[name]
            val shown = name.replace("__", "::")
            errors.add(
                "'$shown' is private to ${owner?.let { "module '$it'" } ?: "the module that declares it"} - " +
                    "a leading underscore keeps a declaration inside the module that writes it",
            )
        }
        return errors.toList()
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
            is TopLevel.InlineTrace -> {
                item.level?.let { collectNamesFromExpr(it, names) }
                collectNamesFromExpr(item.message, names)
            }
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
                // `O: MatrixOrder` is part of the pack's signature: a use site writes
                // `.ColumnMajor` and never the enum's name, so nothing else would
                // bring it in.
                names.addAll(item.constEnums.values)
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
            is TopLevel.Slot -> collectNamesFromAnnotations(item.annotations, names)
            is TopLevel.TypeAlias -> {
                collectNamesFromAnnotations(item.annotations, names)
                collectNamesFromTypeRef(item.type, names)
            }
            is TopLevel.Bridge -> collectNamesFromAnnotations(item.annotations, names)
            is TopLevel.Meta -> {
                // A `meta` is a compile-time macro definition, not a dependency
                // root. Registering its name keeps it available to the macro
                // expander, but its arm templates must NOT be treated as injection
                // dependencies: an arm like `@arr[…] => std::arrayOf(…)` references
                // every collection factory the macro *could* expand to, and pulling
                // those eagerly drags the whole container library into every program
                // (including un-optimized builds where dead-code elimination cannot
                // remove it). The real expansion targets are pulled on demand by the
                // re-injection that runs after the macro expander rewrites call sites.
                names.add(item.name)
            }
            else -> {}
        }
    }

    private fun collectNamesFromFunc(func: FuncDecl, names: MutableSet<String>) {
        collectNamesFromAnnotations(func.annotations, names)
        func.params.forEach {
            collectNamesFromAnnotations(it.annotations, names)
            collectNamesFromTypeAnnotation(TypeAnnotation.Explicit(it.type), names)
            // A default value is real code that runs at every call site which
            // omits the argument, so whatever it names has to be injected too -
            // otherwise a constant used *only* as a default is never pulled in
            // and the call lowers to a reference this unit never defines.
            it.defaultValue?.let { default -> collectNamesFromExpr(default, names) }
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
            is Stmt.Trace -> {
                stmt.level?.let { collectNamesFromExpr(it, names) }
                collectNamesFromExpr(stmt.message, names)
            }
            is Stmt.InlineTrace -> {
                stmt.level?.let { collectNamesFromExpr(it, names) }
                collectNamesFromExpr(stmt.message, names)
            }
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
            is Stmt.Scope -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.InlineBlock -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.DeepInlineBlock -> stmt.body.forEach { collectNamesFromStmt(it, names) }
            is Stmt.Effect -> {
                stmt.dependencies?.forEach { collectNamesFromExpr(it, names) }
                stmt.body.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.WithContext -> {
                stmt.values.forEach { collectNamesFromExpr(it, names) }
                stmt.body.forEach { collectNamesFromStmt(it, names) }
            }
            is Stmt.NoInline -> collectNamesFromStmt(stmt.stmt, names)
            is Stmt.Break, is Stmt.Continue -> {}
        }
    }

    private fun collectNamesFromExpr(expr: Expr, names: MutableSet<String>) {
        when (expr) {
            is Expr.Identifier -> {
                val item = index.items[expr.name]
                if (item != null && item !is TopLevel.Func) names.add(expr.name)
                // `Vec3f::zero` is one identifier (`Vec3f__zero`) by the time it gets
                // here, and the member lives on whatever `Vec3f` names - so the base
                // has to be pulled in too, the same as for a `Type::member` call.
                if ("__" in expr.name) {
                    names.add(expr.name.substringBeforeLast("__"))
                    // A realm-qualified *type* keeps its bare name, so `std::SerialValue`
                    // arrives as `std__SerialValue` while the declaration is `SerialValue`.
                    // Without the tail the type is never injected and the reference
                    // fails to resolve.
                    names.add(expr.name.substringAfterLast("__"))
                }
            }
            is Expr.Call -> {
                names.add(expr.callee)
                // A `Type::member` static call (e.g. `Array::fill`) mangles to
                // `Type__member`. The member is provided by an `impl realm for Type`
                // block, which is only attached when the base type itself is pulled
                // in - so also mark the base type as referenced.
                if ("__" in expr.callee) {
                    names.add(expr.callee.substringBeforeLast("__"))
                    // `std::SerialField(…)` constructs a realm-scoped pack, whose
                    // declaration is the bare tail; see the identifier case above.
                    names.add(expr.callee.substringAfterLast("__"))
                }
                expr.args.forEach { collectNamesFromExpr(it, names) }
            }
            is Expr.MethodCall -> {
                collectNamesFromExpr(expr.target, names)
                // The method name may be a universal infix (`a to b`), which is a
                // top-level function; pull it in so it is injected and registered.
                names.add(expr.name)
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
                expr.receivers.forEach { collectNamesFromTypeAnnotation(TypeAnnotation.Explicit(it.type), names) }
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
            is Expr.MetaInvoke -> {
                names.add(expr.name)
                expr.args.forEach { collectNamesFromExpr(it, names) }
            }
            else -> {}
        }
    }

    private fun collectNamesFromTypeAnnotation(annotation: TypeAnnotation, names: MutableSet<String>) {
        if (annotation is TypeAnnotation.Explicit) collectNamesFromTypeRef(annotation.ref, names)
    }

    private fun collectNamesFromTypeRef(ref: TypeRef, names: MutableSet<String>) {
        when (ref) {
            is TypeRef.Named -> {
                names.add(if (TypeFunctionCall.isCall(ref)) TypeFunctionCall.name(ref) else ref.name)
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
                ref.receivers.forEach { collectNamesFromTypeRef(it, names) }
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
            is TypeRef.Const -> {}
        }
    }
}
