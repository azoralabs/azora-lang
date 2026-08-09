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
 * Expands `meta` macros ([TopLevel.Meta] / [Expr.MetaInvoke]) into ordinary
 * expressions, then drops the macro declarations.
 *
 * Runs in `Compiler.compile()` after stdlib injection and before variadic
 * monomorphization, so both user-defined and injected stdlib macros are
 * available and macro-generated variadic calls (e.g. `std::listOf(…)`)
 * monomorphize normally. Because every `MetaInvoke` is rewritten into concrete
 * [Expr] nodes here, downstream passes (validation, semantic analysis, IR)
 * never observe macro nodes.
 *
 * Macro arms match **delimiter-agnostically** (`vec!()`, `vec![…]`, `vec!{}` all
 * feed args to the same arms). Patterns support empty arguments, typed single
 * captures, and sequence captures spliceable via `...$name`. Templates use
 * ordinary expressions; declaration macros may additionally emit adjacent
 * fragments and select one with `inline if` using their compiler-supplied
 * parameter.
 *
 * Errors throw [IllegalStateException], caught by `Compiler.compile()` and
 * surfaced as a [org.azora.lang.CompilationResult.Failure].
 */
internal object MacroExpander {

    /** Guards against infinite macro recursion (a macro expanding into itself). */
    private const val MAX_DEPTH = 64

    private data class MacroDefinition(val parameter: String?, val arms: List<MacroArm>)

    /** Per-name macro definitions captured from [TopLevel.Meta] declarations (first decl wins). */
    private typealias MacroTable = Map<String, MacroDefinition>

    /** Value-infix macros active during the current expansion (op → rule). Reset per call. */
    private var activeInfix: Map<String, InfixMacroRule> = emptyMap()

    /** Replaces the hole identifiers of an infix macro (`$a`, `$b`) with the operands. */
    private fun substituteInfix(template: Expr, holes: Map<String, Expr>): Expr = when (template) {
        is Expr.Identifier -> holes[template.name] ?: template
        is Expr.Call -> template.copy(
            args = template.args.map { substituteInfix(it, holes) },
            receiver = template.receiver?.let { substituteInfix(it, holes) },
        )
        is Expr.MethodCall -> template.copy(
            target = substituteInfix(template.target, holes),
            args = template.args.map { substituteInfix(it, holes) },
        )
        is Expr.Member -> template.copy(target = substituteInfix(template.target, holes))
        is Expr.Binary -> template.copy(left = substituteInfix(template.left, holes), right = substituteInfix(template.right, holes))
        is Expr.Unary -> template.copy(operand = substituteInfix(template.operand, holes))
        is Expr.Index -> template.copy(target = substituteInfix(template.target, holes), index = substituteInfix(template.index, holes))
        is Expr.Grouping -> template.copy(expr = substituteInfix(template.expr, holes))
        is Expr.Cast -> template.copy(expr = substituteInfix(template.expr, holes))
        is Expr.NamedArg -> template.copy(value = substituteInfix(template.value, holes))
        else -> template
    }

    /**
     * Rewrites every [Expr.MetaInvoke] in [program] into its matched arm's
     * template and removes all [TopLevel.Meta] declarations. Returns [program]
     * unchanged when it declares no macros.
     */
    fun expand(program: Program): Program {
        val macroArms = LinkedHashMap<String, MutableList<MacroArm>>()
        val macroParameters = LinkedHashMap<String, String?>()
        val nonMacros = mutableListOf<TopLevel>()
        val infixOps = mutableSetOf<String>()
        for (item in program.items) {
            if (item is TopLevel.Meta) {
                // `meta .Infix("op")` is encoded as a meta named `__infix__op`; it
                // carries no invocable arms, only the infix-operator registration.
                val op = item.name.removePrefix("__infix__")
                if (op != item.name) infixOps.add(op)
                else {
                    macroArms.getOrPut(item.name) { mutableListOf() }.addAll(item.arms)
                    macroParameters.putIfAbsent(item.name, item.parameter)
                }
            } else {
                nonMacros.add(item)
            }
        }
        val withInfix: (Program) -> Program = {
            if (infixOps.isEmpty()) it else it.copy(infixOperators = it.infixOperators + infixOps)
        }
        // Fast path: nothing to do when there are no macro declarations AND the
        // program never invokes a macro. (A program that *uses* `name!` without
        // defining/importing a macro still needs to run, so the use site can fail
        // clearly with "macro 'name' is not defined" rather than leaking
        // MetaInvoke into semantic analysis.)
        activeInfix = program.infixMacros.associateBy { it.op }
        if (macroArms.isEmpty() && activeInfix.isEmpty() && !program.usesMacros) return withInfix(program)
        val table: MacroTable = macroArms.mapValues { (name, arms) ->
            MacroDefinition(macroParameters[name], arms)
        }
        val renamedTypes = mutableMapOf<String, String>()
        val rewritten = nonMacros.flatMap { rewriteItems(it, table, 0, renamedTypes) }
        return withInfix(program.copy(
            items = rewritten,
            localPackNames = program.localPackNames.mapTo(linkedSetOf()) { renamedTypes[it] ?: it },
            realmTypeNamespaces = program.realmTypeNamespaces.mapKeys { (name, _) -> renamedTypes[name] ?: name },
        ))
    }

    // ------------------------------------------------------------------
    // Item / function / parameter / annotation rewriting
    // ------------------------------------------------------------------

    /** A bridge name macro may add an ordinary local wrapper beside its extern. */
    private fun rewriteItems(
        item: TopLevel,
        macros: MacroTable,
        depth: Int,
        renamedTypes: MutableMap<String, String>,
    ): List<TopLevel> {
        if (item is TopLevel.Pack && item.nameMacro != null) {
            val (foreignName, expandedLocal) = expandDeclarationName(item.nameMacro, macros, item.line, isType = true)
            val localName = item.localRealm?.let { "${it}__$expandedLocal" } ?: expandedLocal
            renamedTypes[item.name] = localName
            return listOf(rewriteItem(item.copy(
                name = localName,
                foreignName = foreignName,
                nameMacro = null,
                localRealm = null,
            ), macros, depth))
        }
        if (item !is TopLevel.Bridge) return listOf(rewriteItem(item, macros, depth))

        val wrappers = mutableListOf<TopLevel>()
        val funcs = item.funcs.map { signature ->
            val (foreignName, expandedLocal) = if (signature.nameMacro == null) {
                signature.name to signature.localName
            } else {
                expandDeclarationName(signature.nameMacro, macros, signature.line, isType = false)
            }
            val localName = expandedLocal?.let { local ->
                signature.localRealm?.let { "${it}__$local" } ?: local
            }
            if (localName != null && localName != foreignName) {
                wrappers += bridgeWrapper(localName, foreignName, signature.params, signature.returnType, signature.typeParams, signature.line)
            }
            signature.copy(
                name = foreignName,
                localName = null,
                nameMacro = null,
                localRealm = null,
                params = signature.params.map { rewriteParam(it, macros, depth) },
            )
        }
        val values = item.values.map { value ->
            val (foreignName, expandedLocal) = if (value.nameMacro == null) {
                (value.foreignName ?: value.name) to value.name
            } else {
                expandDeclarationName(value.nameMacro, macros, value.line, isType = false)
            }
            val localName = value.localRealm?.let { "${it}__$expandedLocal" } ?: expandedLocal
            value.copy(
                name = localName,
                foreignName = foreignName,
                nameMacro = null,
                localRealm = null,
                initializer = rewriteExpr(value.initializer, macros, depth),
            )
        }
        val bridge = item.copy(
            funcs = funcs,
            values = values,
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        return listOf(bridge) + wrappers
    }

    private fun bridgeWrapper(
        localName: String,
        foreignName: String,
        params: List<Param>,
        returnType: TypeRef,
        typeParams: List<String>,
        line: Int,
    ): TopLevel.Func {
        val call = Expr.Call(foreignName, params.map { Expr.Identifier(it.name, line) }, line)
        val body = if ((returnType as? TypeRef.Named)?.name?.substringAfterLast("__") == "Unit") {
            listOf(Stmt.ExprStmt(call, line))
        } else {
            listOf(Stmt.Return(call, line))
        }
        return TopLevel.Func(FuncDecl(
            name = localName,
            params = params,
            returnType = TypeAnnotation.Explicit(returnType),
            body = body,
            typeParams = typeParams,
            line = line,
        ))
    }

    /** Expands the two adjacent name fragments produced by a declaration macro. */
    private fun expandDeclarationName(
        invocation: Expr.MetaInvoke,
        macros: MacroTable,
        line: Int,
        isType: Boolean,
    ): Pair<String, String> {
        val definition = macros[invocation.name]
            ?: fail(line, "macro '${invocation.name}' is not defined")
        val (arm, captures) = matchArm(definition.arms, invocation.args)
            ?: fail(line, "no matching arm in macro '${invocation.name}' for ${invocation.args.size} argument(s)")
        val parts = listOf(arm.template) + arm.templateTail
        if (parts.size != 2) {
            fail(line, "a bridge declaration-name macro must expand to exactly two String fragments: backend name then Azora name")
        }
        val bindings = captures.toMutableMap()
        definition.parameter?.let { parameter ->
            bindings[parameter] = listOf(Expr.BoolLiteral(isType, line))
        }
        val expanded = parts.map { part ->
            rewriteExpr(substitute(part, bindings, line), macros, 1)
        }
        val foreignName = (expanded[0] as? Expr.StringLiteral)?.value
            ?: fail(line, "a bridge declaration-name macro's backend-name fragment must be String")
        val localName = (expanded[1] as? Expr.StringLiteral)?.value
            ?: fail(line, "a bridge declaration-name macro's Azora-name fragment must be String")
        validateMacroLocalName(foreignName, localName, line)
        return foreignName to localName
    }

    private fun validateMacroLocalName(foreignName: String, localName: String, line: Int) {
        if (!Regex("_?[A-Za-z][A-Za-z0-9]*").matches(localName)) {
            fail(line, "foreign name '$foreignName' produces invalid Azora identifier '$localName'")
        }
        if (localName in AzoraSyntaxVocabulary.reservedKeywords) {
            fail(line, "foreign name '$foreignName' produces reserved Azora keyword '$localName'")
        }
    }

    /** General macro-time String projection: `"API_VERSION".lowerCamel`. */
    private fun lowerCamel(value: String): String {
        val words = value
            .replace(Regex("([a-z0-9])([A-Z])"), "${'$'}1 ${'$'}2")
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val first = words.first().lowercase()
        val rest = words.drop(1).joinToString("") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
        return first + rest
    }

    /** General macro-time String projection: `"native_HTTP_client".upperCamel`. */
    private fun upperCamel(value: String): String =
        lowerCamel(value).replaceFirstChar { it.uppercase() }

    private fun rewriteItem(item: TopLevel, macros: MacroTable, depth: Int): TopLevel = when (item) {
        is TopLevel.Func -> item.copy(
            decl = rewriteFunc(item.decl, macros, depth),
        )
        is TopLevel.VarDecl -> item.copy(
            initializer = rewriteExpr(item.initializer, macros, depth),
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        is TopLevel.FinDecl -> item.copy(
            initializer = rewriteExpr(item.initializer, macros, depth),
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        is TopLevel.LetDecl -> item.copy(
            initializer = rewriteExpr(item.initializer, macros, depth),
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        is TopLevel.InlineVar -> item.copy(initializer = rewriteExpr(item.initializer, macros, depth))
        is TopLevel.InlineFin -> item.copy(initializer = rewriteExpr(item.initializer, macros, depth))
        is TopLevel.InlineLet -> item.copy(initializer = rewriteExpr(item.initializer, macros, depth))
        is TopLevel.InlineAssignment -> item.copy(value = rewriteExpr(item.value, macros, depth))
        is TopLevel.InlineTrace -> item.copy(
            message = rewriteExpr(item.message, macros, depth),
            level = item.level?.let { rewriteExpr(it, macros, depth) },
        )
        is TopLevel.InlineIf -> item.copy(
            condition = rewriteExpr(item.condition, macros, depth),
            thenBranch = item.thenBranch.map { rewriteItem(it, macros, depth) },
            elseBranch = item.elseBranch?.map { rewriteItem(it, macros, depth) },
        )
        is TopLevel.DeepInlineIf -> item.copy(
            condition = rewriteExpr(item.condition, macros, depth),
            thenBranch = item.thenBranch.map { rewriteItem(it, macros, depth) },
            elseBranch = item.elseBranch?.map { rewriteItem(it, macros, depth) },
        )
        is TopLevel.InlineBlock -> item.copy(body = item.body.map { rewriteItem(it, macros, depth) })
        is TopLevel.DeepInlineBlock -> item.copy(body = item.body.map { rewriteItem(it, macros, depth) })
        is TopLevel.Test -> item.copy(
            body = rewriteStmts(item.body, macros, depth),
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        is TopLevel.Pack -> {
            val named = item.nameMacro?.let { invocation ->
                val (foreignName, localName) = expandDeclarationName(invocation, macros, item.line, isType = true)
                item.copy(name = localName, foreignName = foreignName, nameMacro = null, localRealm = null)
            } ?: item
            named.copy(
                fields = named.fields.map { rewriteField(it, macros, depth) },
                annotations = named.annotations.map { rewriteAnnotation(it, macros, depth) },
            )
        }
        is TopLevel.Deco -> item.copy(
            fields = item.fields.map { rewriteField(it, macros, depth) },
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        is TopLevel.Solo -> item.copy(
            fields = item.fields.map { rewriteField(it, macros, depth) },
            methods = item.methods.map { rewriteFunc(it, macros, depth) },
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        is TopLevel.Impl -> item.copy(
            methods = item.methods.map { rewriteFunc(it, macros, depth) },
            decoratorArgs = item.decoratorArgs.map { rewriteExpr(it, macros, depth) },
            decoratorNamedArgs = item.decoratorNamedArgs.map { (k, v) -> k to rewriteExpr(v, macros, depth) },
        )
        is TopLevel.Spec -> item.copy(
            methods = item.methods.map { rewriteFunc(it, macros, depth) },
            callback = item.callback?.let { cb ->
                cb.copy(
                    params = cb.params.map { rewriteParam(it, macros, depth) },
                )
            },
        )
        is TopLevel.Graph -> item.copy(
            registrations = item.registrations.map { reg ->
                reg.copy(args = reg.args.map { rewriteExpr(it, macros, depth) })
            },
        )
        // Variants with annotations but no Expr bodies still get annotation args rewritten.
        is TopLevel.Enum, is TopLevel.Fail, is TopLevel.Slot,
        is TopLevel.TypeAlias, is TopLevel.Bridge -> rewriteAnnotationsOnly(item, macros, depth)
        // Nested-only or macro-free declarations: nothing to expand.
        else -> item
    }

    /** Rewrites annotation args for a declaration whose only Expr surface is its annotations. */
    @Suppress("UNCHECKED_CAST")
    private fun rewriteAnnotationsOnly(item: TopLevel, macros: MacroTable, depth: Int): TopLevel {
        return when (item) {
            is TopLevel.Enum -> item.copy(
                annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
                variantAnnotations = item.variantAnnotations.map { anns -> anns.map { rewriteAnnotation(it, macros, depth) } },
            )
            is TopLevel.Fail -> item.copy(
                annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
                variantAnnotations = item.variantAnnotations.map { anns -> anns.map { rewriteAnnotation(it, macros, depth) } },
            )
            is TopLevel.Slot -> item.copy(annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) })
            is TopLevel.TypeAlias -> item.copy(annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) })
            is TopLevel.Bridge -> item.copy(annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) })
            else -> item
        }
    }

    private fun rewriteFunc(decl: FuncDecl, macros: MacroTable, depth: Int): FuncDecl = decl.copy(
        params = decl.params.map { rewriteParam(it, macros, depth) },
        body = rewriteStmts(decl.body, macros, depth),
        annotations = decl.annotations.map { rewriteAnnotation(it, macros, depth) },
    )

    private fun rewriteParam(p: Param, macros: MacroTable, depth: Int): Param = p.copy(
        defaultValue = p.defaultValue?.let { rewriteExpr(it, macros, depth) },
        annotations = p.annotations.map { rewriteAnnotation(it, macros, depth) },
    )

    private fun rewriteField(field: PackField, macros: MacroTable, depth: Int): PackField = field.copy(
        default = field.default?.let { rewriteExpr(it, macros, depth) },
        annotations = field.annotations.map { rewriteAnnotation(it, macros, depth) },
    )

    private fun rewriteAnnotation(a: Annotation, macros: MacroTable, depth: Int): Annotation = a.copy(
        args = a.args.map { rewriteExpr(it, macros, depth) },
        namedArgs = a.namedArgs.map { (k, v) -> k to rewriteExpr(v, macros, depth) },
    )

    // ------------------------------------------------------------------
    // Statement rewriting
    // ------------------------------------------------------------------

    private fun rewriteStmts(stmts: List<Stmt>, macros: MacroTable, depth: Int): List<Stmt> =
        stmts.map { rewriteStmt(it, macros, depth) }

    private fun rewriteStmt(stmt: Stmt, macros: MacroTable, depth: Int): Stmt = when (stmt) {
        is Stmt.VarDecl -> stmt.copy(initializer = rewriteExpr(stmt.initializer, macros, depth))
        is Stmt.FinDecl -> stmt.copy(initializer = rewriteExpr(stmt.initializer, macros, depth))
        is Stmt.LetDecl -> stmt.copy(initializer = rewriteExpr(stmt.initializer, macros, depth))
        is Stmt.InlineVar -> stmt.copy(initializer = rewriteExpr(stmt.initializer, macros, depth))
        is Stmt.InlineFin -> stmt.copy(initializer = rewriteExpr(stmt.initializer, macros, depth))
        is Stmt.InlineLet -> stmt.copy(initializer = rewriteExpr(stmt.initializer, macros, depth))
        is Stmt.RemDecl -> stmt.copy(initializer = rewriteExpr(stmt.initializer, macros, depth))
        is Stmt.Assignment -> stmt.copy(value = rewriteExpr(stmt.value, macros, depth))
        is Stmt.InlineAssignment -> stmt.copy(value = rewriteExpr(stmt.value, macros, depth))
        is Stmt.Return -> stmt.copy(value = stmt.value?.let { rewriteExpr(it, macros, depth) })
        is Stmt.ExprStmt -> stmt.copy(expr = rewriteExpr(stmt.expr, macros, depth))
        is Stmt.Throw -> stmt.copy(value = rewriteExpr(stmt.value, macros, depth))
        is Stmt.Panic -> stmt.copy(message = rewriteExpr(stmt.message, macros, depth))
        is Stmt.Yield -> stmt.copy(value = rewriteExpr(stmt.value, macros, depth))
        is Stmt.Assert -> stmt.copy(
            condition = rewriteExpr(stmt.condition, macros, depth),
            message = rewriteExpr(stmt.message, macros, depth),
        )
        is Stmt.InlineAssert -> stmt.copy(
            condition = rewriteExpr(stmt.condition, macros, depth),
            message = rewriteExpr(stmt.message, macros, depth),
        )
        is Stmt.Trace -> stmt.copy(
            message = rewriteExpr(stmt.message, macros, depth),
            level = stmt.level?.let { rewriteExpr(it, macros, depth) },
        )
        is Stmt.InlineTrace -> stmt.copy(
            message = rewriteExpr(stmt.message, macros, depth),
            level = stmt.level?.let { rewriteExpr(it, macros, depth) },
        )
        is Stmt.IndexAssign -> stmt.copy(
            target = rewriteExpr(stmt.target, macros, depth),
            index = rewriteExpr(stmt.index, macros, depth),
            value = rewriteExpr(stmt.value, macros, depth),
        )
        is Stmt.MemberAssign -> stmt.copy(
            target = rewriteExpr(stmt.target, macros, depth),
            value = rewriteExpr(stmt.value, macros, depth),
        )
        is Stmt.DerefAssign -> stmt.copy(
            target = rewriteExpr(stmt.target, macros, depth),
            value = rewriteExpr(stmt.value, macros, depth),
        )
        is Stmt.If -> stmt.copy(
            condition = rewriteExpr(stmt.condition, macros, depth),
            thenBranch = rewriteStmts(stmt.thenBranch, macros, depth),
            elseBranch = stmt.elseBranch?.let { rewriteStmts(it, macros, depth) },
        )
        is Stmt.InlineIf -> stmt.copy(
            condition = rewriteExpr(stmt.condition, macros, depth),
            thenBranch = rewriteStmts(stmt.thenBranch, macros, depth),
            elseBranch = stmt.elseBranch?.let { rewriteStmts(it, macros, depth) },
        )
        is Stmt.DeepInlineIf -> stmt.copy(
            condition = rewriteExpr(stmt.condition, macros, depth),
            thenBranch = rewriteStmts(stmt.thenBranch, macros, depth),
            elseBranch = stmt.elseBranch?.let { rewriteStmts(it, macros, depth) },
        )
        is Stmt.While -> stmt.copy(
            condition = rewriteExpr(stmt.condition, macros, depth),
            body = rewriteStmts(stmt.body, macros, depth),
        )
        is Stmt.For -> stmt.copy(
            iterable = rewriteExpr(stmt.iterable, macros, depth),
            step = stmt.step?.let { rewriteExpr(it, macros, depth) },
            body = rewriteStmts(stmt.body, macros, depth),
        )
        is Stmt.InlineFor -> stmt.copy(
            iterable = rewriteExpr(stmt.iterable, macros, depth),
            body = rewriteStmts(stmt.body, macros, depth),
        )
        is Stmt.Loop -> stmt.copy(
            iterable = stmt.iterable?.let { rewriteExpr(it, macros, depth) },
            body = rewriteStmts(stmt.body, macros, depth),
        )
        is Stmt.Scope -> stmt.copy(body = rewriteStmts(stmt.body, macros, depth))
        is Stmt.InlineBlock -> stmt.copy(body = rewriteStmts(stmt.body, macros, depth))
        is Stmt.DeepInlineBlock -> stmt.copy(body = rewriteStmts(stmt.body, macros, depth))
        is Stmt.Effect -> stmt.copy(
            body = rewriteStmts(stmt.body, macros, depth),
            dependencies = stmt.dependencies?.map { rewriteExpr(it, macros, depth) },
        )
        is Stmt.WithContext -> stmt.copy(
            values = stmt.values.map { rewriteExpr(it, macros, depth) },
            body = rewriteStmts(stmt.body, macros, depth),
        )
        is Stmt.Defer -> stmt.copy(body = rewriteStmts(stmt.body, macros, depth))
        is Stmt.Try -> stmt.copy(
            body = rewriteStmts(stmt.body, macros, depth),
            catchBody = stmt.catchBody?.let { rewriteStmts(it, macros, depth) },
        )
        is Stmt.When -> stmt.copy(
            scrutinee = rewriteExpr(stmt.scrutinee, macros, depth),
            branches = stmt.branches.map { b ->
                b.copy(
                    patterns = b.patterns.map { rewriteExpr(it, macros, depth) },
                    body = rewriteStmts(b.body, macros, depth),
                )
            },
            elseBranch = stmt.elseBranch?.let { rewriteStmts(it, macros, depth) },
        )
        is Stmt.NoInline -> stmt.copy(stmt = rewriteStmt(stmt.stmt, macros, depth))
        is Stmt.Break, is Stmt.Continue -> stmt
    }

    // ------------------------------------------------------------------
    // Expression rewriting (the workhorse)
    // ------------------------------------------------------------------

    private fun rewriteExpr(expr: Expr, macros: MacroTable, depth: Int): Expr {
        if (depth > MAX_DEPTH) {
            fail(expr.line, "macro expansion exceeded depth cap ($MAX_DEPTH); possible infinite macro recursion")
        }
        // A MetaInvoke: rewrite its args, expand to the matched template, then
        // re-run on the result (templates may themselves contain MetaInvoke).
        if (expr is Expr.MetaInvoke) {
            val args = expr.args.map { rewriteExpr(it, macros, depth) }
            val node = expr.copy(args = args)
            val expanded = expandOne(node, macros)
            return rewriteExpr(expanded, macros, depth + 1)
        }
        // All other variants: structural copy recursing into every Expr child.
        return when (expr) {
            is Expr.Identifier -> expr
            is Expr.IntLiteral, is Expr.DoubleLiteral, is Expr.CharLiteral,
            is Expr.StringLiteral, is Expr.BoolLiteral, is Expr.NullLiteral,
            is Expr.UpperScopeAccess, is Expr.Inject -> expr
            is Expr.Unary -> expr.copy(operand = rewriteExpr(expr.operand, macros, depth))
            is Expr.Grouping -> expr.copy(expr = rewriteExpr(expr.expr, macros, depth))
            is Expr.Member -> expr.copy(target = rewriteExpr(expr.target, macros, depth))
            is Expr.SafeMember -> expr.copy(target = rewriteExpr(expr.target, macros, depth))
            is Expr.TupleAccess -> expr.copy(target = rewriteExpr(expr.target, macros, depth))
            is Expr.Index -> expr.copy(
                target = rewriteExpr(expr.target, macros, depth),
                index = rewriteExpr(expr.index, macros, depth),
            )
            is Expr.Range -> expr.copy(
                from = rewriteExpr(expr.from, macros, depth),
                to = rewriteExpr(expr.to, macros, depth),
            )
            is Expr.Binary -> expr.copy(
                left = rewriteExpr(expr.left, macros, depth),
                right = rewriteExpr(expr.right, macros, depth),
            )
            is Expr.NullCoalesce -> expr.copy(
                left = rewriteExpr(expr.left, macros, depth),
                right = rewriteExpr(expr.right, macros, depth),
            )
            is Expr.CatchExpr -> expr.copy(
                expr = rewriteExpr(expr.expr, macros, depth),
                fallback = rewriteExpr(expr.fallback, macros, depth),
            )
            is Expr.IfExpr -> expr.copy(
                condition = rewriteExpr(expr.condition, macros, depth),
                thenExpr = rewriteExpr(expr.thenExpr, macros, depth),
                elseExpr = rewriteExpr(expr.elseExpr, macros, depth),
            )
            is Expr.Cast -> expr.copy(expr = rewriteExpr(expr.expr, macros, depth))
            is Expr.InlineForArgs -> expr.copy(
                iterable = rewriteExpr(expr.iterable, macros, depth),
                body = rewriteExpr(expr.body, macros, depth),
            )
            is Expr.IsCheck -> expr.copy(expr = rewriteExpr(expr.expr, macros, depth))
            is Expr.InCheck -> expr.copy(
                value = rewriteExpr(expr.value, macros, depth),
                collection = rewriteExpr(expr.collection, macros, depth),
            )
            is Expr.Alloc -> expr.copy(value = rewriteExpr(expr.value, macros, depth))
            is Expr.AllocBuffer -> expr.copy(count = rewriteExpr(expr.count, macros, depth))
            is Expr.Deref -> expr.copy(target = rewriteExpr(expr.target, macros, depth))
            is Expr.Isolated -> expr.copy(value = rewriteExpr(expr.value, macros, depth))
            is Expr.Await -> expr.copy(value = rewriteExpr(expr.value, macros, depth))
            is Expr.TryPropagate -> expr.copy(expr = rewriteExpr(expr.expr, macros, depth))
            is Expr.Spread -> expr.copy(array = rewriteExpr(expr.array, macros, depth))
            is Expr.NamedArg -> expr.copy(value = rewriteExpr(expr.value, macros, depth))
            is Expr.Call -> expr.copy(args = expr.args.map { rewriteExpr(it, macros, depth) })
            is Expr.MethodCall -> {
                // Value-infix macro: `a op b` (parsed as `a.op(b)`) rewrites to the
                // `meta .Infix("op") { $a $b => … }` template.
                val infix = activeInfix[expr.name]
                if (infix != null && expr.args.size == 1) {
                    val target = rewriteExpr(expr.target, macros, depth)
                    val arg = rewriteExpr(expr.args[0], macros, depth)
                    rewriteExpr(substituteInfix(infix.template, mapOf(infix.left to target, infix.right to arg)), macros, depth)
                } else {
                    expr.copy(
                        target = rewriteExpr(expr.target, macros, depth),
                        args = expr.args.map { rewriteExpr(it, macros, depth) },
                    )
                }
            }
            is Expr.ArrayLiteral -> expr.copy(elements = expr.elements.map { rewriteExpr(it, macros, depth) })
            is Expr.SetLiteral -> expr.copy(elements = expr.elements.map { rewriteExpr(it, macros, depth) })
            is Expr.TupleLit -> expr.copy(elements = expr.elements.map { rewriteExpr(it, macros, depth) })
            is Expr.VariantLit -> expr.copy(elements = expr.elements.map { rewriteExpr(it, macros, depth) })
            is Expr.MapLit -> expr.copy(entries = expr.entries.map { (k, v) ->
                rewriteExpr(k, macros, depth) to rewriteExpr(v, macros, depth)
            })
            is Expr.StringTemplate -> expr.copy(parts = expr.parts.map { part ->
                if (part is Expr.StringTemplatePart.Expr)
                    Expr.StringTemplatePart.Expr(rewriteExpr(part.expr, macros, depth))
                else part
            })
            is Expr.Lambda -> expr.copy(
                params = expr.params.map { rewriteParam(it, macros, depth) },
                receivers = expr.receivers.map { rewriteParam(it, macros, depth) },
                body = rewriteStmts(expr.body, macros, depth),
            )
            is Expr.MetaInvoke -> expr // unreachable (handled above); satisfies exhaustiveness
            is Expr.Slice -> expr.copy(
                target = rewriteExpr(expr.target, macros, depth),
                start = expr.start?.let { rewriteExpr(it, macros, depth) },
                stop = expr.stop?.let { rewriteExpr(it, macros, depth) },
                step = expr.step?.let { rewriteExpr(it, macros, depth) },
            )
        }
    }

    // ------------------------------------------------------------------
    // Pattern matching + expansion
    // ------------------------------------------------------------------

    /** Matches [args] against the first compatible [arms]; returns the arm and its bindings, or null. */
    private fun matchArm(arms: List<MacroArm>, args: List<Expr>): Pair<MacroArm, Map<String, List<Expr>>>? {
        for (arm in arms) {
            when (arm.pattern) {
                is MacroPattern.Empty -> {
                    if (args.isEmpty()) return arm to emptyMap()
                }
                is MacroPattern.SeqCapture -> {
                    if (args.isNotEmpty()) {
                        return arm to mapOf(arm.pattern.name to args)
                    }
                }
                is MacroPattern.TypedCapture -> {
                    val argument = args.singleOrNull() ?: continue
                    if (macroArgumentMatches(argument, arm.pattern.type)) {
                        return arm to mapOf(arm.pattern.name to listOf(argument))
                    }
                }
                is MacroPattern.MapEntryCapture -> {
                    // `[...${key: value}]` - key/value destructuring. Invocation-side
                    // support (parsing `map!["a": 1]` into paired args) is a later
                    // stage; the arm parses and stores its capture names for now.
                }
            }
        }
        return null
    }

    /** Type checking available while selecting a typed macro arm. */
    private fun macroArgumentMatches(argument: Expr, expected: TypeRef): Boolean {
        val name = (expected as? TypeRef.Named)?.name?.substringAfterLast("__") ?: return false
        return when (name) {
            "String" -> argument is Expr.StringLiteral
            "Bool" -> argument is Expr.BoolLiteral
            "Int", "Long", "Byte", "Short", "UInt", "ULong", "UByte", "UShort" -> argument is Expr.IntLiteral
            "Real", "Double", "Float" -> argument is Expr.DoubleLiteral || argument is Expr.IntLiteral
            "Char" -> argument is Expr.CharLiteral
            else -> false
        }
    }

    /** Resolves a [Expr.MetaInvoke] to its expanded template expression. */
    private fun expandOne(node: Expr.MetaInvoke, macros: MacroTable): Expr {
        val definition = macros[node.name]
            ?: fail(node.line, "macro '${node.name}' is not defined")
        val (arm, bindings) = matchArm(definition.arms, node.args)
            ?: fail(node.line, "no matching arm in macro '${node.name}' for ${node.args.size} argument(s)")
        if (definition.parameter != null) {
            fail(node.line, "macro '${node.name}' has declaration parameter '${definition.parameter}' and can only be applied to a declaration")
        }
        if (arm.templateTail.isNotEmpty()) {
            fail(node.line, "macro '${node.name}' expands to declaration fragments and cannot be used as an expression")
        }
        return substitute(arm.template, bindings, node.line)
    }

    // ------------------------------------------------------------------
    // Template substitution (splice-aware)
    // ------------------------------------------------------------------

    /**
     * Produces a fresh [Expr] from [template] with macro [bindings] substituted.
     *
     * Sequence containers ([Expr.Call.args], collection `.elements`,
     * [Expr.MethodCall.args]) are rebuilt via [substituteSeq] so a
     * `...$capture` splices its captured expressions inline. Bare `$capture`
 * references outside a spread position are rejected (they bind multiple exprs).
     */
    private fun substitute(template: Expr, bindings: Map<String, List<Expr>>, invokeLine: Int): Expr = when (template) {
        is Expr.Identifier -> {
            val captured = bindings[template.name]
            if (captured != null) {
                if (captured.size == 1) return captured.single()
                fail(template.line.takeIf { it != 0 } ?: invokeLine,
                    "macro capture '\$${template.name}' must appear under '...' when it binds multiple expressions")
            }
            template
        }
        // Leaves - no Expr children. NullLiteral is an immutable singleton, safe to share.
        is Expr.IntLiteral, is Expr.DoubleLiteral, is Expr.CharLiteral,
        is Expr.StringLiteral, is Expr.BoolLiteral, is Expr.NullLiteral,
        is Expr.UpperScopeAccess, is Expr.Inject -> template
        is Expr.Unary -> template.copy(operand = substitute(template.operand, bindings, invokeLine))
        is Expr.Grouping -> template.copy(expr = substitute(template.expr, bindings, invokeLine))
        is Expr.Member -> {
            val target = substitute(template.target, bindings, invokeLine)
            when {
                target is Expr.StringLiteral && template.name == "lowerCamel" ->
                    Expr.StringLiteral(lowerCamel(target.value), template.line, template.column)
                target is Expr.StringLiteral && template.name == "upperCamel" ->
                    Expr.StringLiteral(upperCamel(target.value), template.line, template.column)
                target is Expr.BoolLiteral && template.name == "isType" -> target
                else -> template.copy(target = target)
            }
        }
        is Expr.SafeMember -> template.copy(target = substitute(template.target, bindings, invokeLine))
        is Expr.TupleAccess -> template.copy(target = substitute(template.target, bindings, invokeLine))
        is Expr.Index -> template.copy(
            target = substitute(template.target, bindings, invokeLine),
            index = substitute(template.index, bindings, invokeLine),
        )
        is Expr.Range -> template.copy(
            from = substitute(template.from, bindings, invokeLine),
            to = substitute(template.to, bindings, invokeLine),
        )
        is Expr.Binary -> template.copy(
            left = substitute(template.left, bindings, invokeLine),
            right = substitute(template.right, bindings, invokeLine),
        )
        is Expr.NullCoalesce -> template.copy(
            left = substitute(template.left, bindings, invokeLine),
            right = substitute(template.right, bindings, invokeLine),
        )
        is Expr.CatchExpr -> template.copy(
            expr = substitute(template.expr, bindings, invokeLine),
            fallback = substitute(template.fallback, bindings, invokeLine),
        )
        is Expr.IfExpr -> {
            val condition = substitute(template.condition, bindings, invokeLine)
            when (condition) {
                is Expr.BoolLiteral -> substitute(
                    if (condition.value) template.thenExpr else template.elseExpr,
                    bindings,
                    invokeLine,
                )
                else -> template.copy(
                    condition = condition,
                    thenExpr = substitute(template.thenExpr, bindings, invokeLine),
                    elseExpr = substitute(template.elseExpr, bindings, invokeLine),
                )
            }
        }
        is Expr.Cast -> template.copy(expr = substitute(template.expr, bindings, invokeLine))
        is Expr.InlineForArgs -> template.copy(
            iterable = substitute(template.iterable, bindings, invokeLine),
            body = substitute(template.body, bindings, invokeLine),
        )
        is Expr.IsCheck -> template.copy(expr = substitute(template.expr, bindings, invokeLine))
        is Expr.InCheck -> template.copy(
            value = substitute(template.value, bindings, invokeLine),
            collection = substitute(template.collection, bindings, invokeLine),
        )
        is Expr.Alloc -> template.copy(value = substitute(template.value, bindings, invokeLine))
        is Expr.AllocBuffer -> template.copy(count = substitute(template.count, bindings, invokeLine))
        is Expr.Deref -> template.copy(target = substitute(template.target, bindings, invokeLine))
        is Expr.Isolated -> template.copy(value = substitute(template.value, bindings, invokeLine))
        is Expr.Await -> template.copy(value = substitute(template.value, bindings, invokeLine))
        is Expr.TryPropagate -> template.copy(expr = substitute(template.expr, bindings, invokeLine))
        is Expr.Spread -> template.copy(array = substitute(template.array, bindings, invokeLine))
        is Expr.NamedArg -> template.copy(value = substitute(template.value, bindings, invokeLine))
        // Sequence containers - splice-aware.
        is Expr.Call -> template.copy(args = substituteSeq(template.args, bindings, invokeLine))
        is Expr.MethodCall -> template.copy(
            target = substitute(template.target, bindings, invokeLine),
            args = substituteSeq(template.args, bindings, invokeLine),
        )
        is Expr.ArrayLiteral -> template.copy(elements = substituteSeq(template.elements, bindings, invokeLine))
        is Expr.SetLiteral -> template.copy(elements = substituteSeq(template.elements, bindings, invokeLine))
        is Expr.TupleLit -> template.copy(elements = substituteSeq(template.elements, bindings, invokeLine))
        is Expr.VariantLit -> template.copy(elements = substituteSeq(template.elements, bindings, invokeLine))
        is Expr.MapLit -> template.copy(entries = template.entries.map { (k, v) ->
            substitute(k, bindings, invokeLine) to substitute(v, bindings, invokeLine)
        })
        is Expr.StringTemplate -> template.copy(parts = template.parts.map { part ->
            if (part is Expr.StringTemplatePart.Expr)
                Expr.StringTemplatePart.Expr(substitute(part.expr, bindings, invokeLine))
            else part
        })
        is Expr.Lambda -> template.copy(
            params = template.params.map { p ->
                p.copy(defaultValue = p.defaultValue?.let { substitute(it, bindings, invokeLine) })
            },
            receivers = template.receivers.map { p ->
                p.copy(defaultValue = p.defaultValue?.let { substitute(it, bindings, invokeLine) })
            },
            body = template.body.map { substituteStmt(it, bindings, invokeLine) },
        )
        // A nested MetaInvoke surviving into a template: splice-substitute its
        // args (a `...$capture` must splice here, just as in a Call) and leave the
        // node for the outer rewriteExpr loop to expand.
        is Expr.MetaInvoke -> template.copy(args = substituteSeq(template.args, bindings, invokeLine))
        is Expr.Slice -> template.copy(
            target = substitute(template.target, bindings, invokeLine),
            start = template.start?.let { substitute(it, bindings, invokeLine) },
            stop = template.stop?.let { substitute(it, bindings, invokeLine) },
            step = template.step?.let { substitute(it, bindings, invokeLine) },
        )
    }

    /** Mirrors [substitute] for the Stmt children of a [Expr.Lambda] body. */
    private fun substituteStmt(stmt: Stmt, bindings: Map<String, List<Expr>>, invokeLine: Int): Stmt = when (stmt) {
        is Stmt.VarDecl -> stmt.copy(initializer = substitute(stmt.initializer, bindings, invokeLine))
        is Stmt.FinDecl -> stmt.copy(initializer = substitute(stmt.initializer, bindings, invokeLine))
        is Stmt.LetDecl -> stmt.copy(initializer = substitute(stmt.initializer, bindings, invokeLine))
        is Stmt.InlineVar -> stmt.copy(initializer = substitute(stmt.initializer, bindings, invokeLine))
        is Stmt.InlineFin -> stmt.copy(initializer = substitute(stmt.initializer, bindings, invokeLine))
        is Stmt.InlineLet -> stmt.copy(initializer = substitute(stmt.initializer, bindings, invokeLine))
        is Stmt.RemDecl -> stmt.copy(initializer = substitute(stmt.initializer, bindings, invokeLine))
        is Stmt.Assignment -> stmt.copy(value = substitute(stmt.value, bindings, invokeLine))
        is Stmt.InlineAssignment -> stmt.copy(value = substitute(stmt.value, bindings, invokeLine))
        is Stmt.Return -> stmt.copy(value = stmt.value?.let { substitute(it, bindings, invokeLine) })
        is Stmt.ExprStmt -> stmt.copy(expr = substitute(stmt.expr, bindings, invokeLine))
        is Stmt.Throw -> stmt.copy(value = substitute(stmt.value, bindings, invokeLine))
        is Stmt.Panic -> stmt.copy(message = substitute(stmt.message, bindings, invokeLine))
        is Stmt.Yield -> stmt.copy(value = substitute(stmt.value, bindings, invokeLine))
        is Stmt.Assert -> stmt.copy(
            condition = substitute(stmt.condition, bindings, invokeLine),
            message = substitute(stmt.message, bindings, invokeLine),
        )
        is Stmt.InlineAssert -> stmt.copy(
            condition = substitute(stmt.condition, bindings, invokeLine),
            message = substitute(stmt.message, bindings, invokeLine),
        )
        is Stmt.Trace -> stmt.copy(
            message = substitute(stmt.message, bindings, invokeLine),
            level = stmt.level?.let { substitute(it, bindings, invokeLine) },
        )
        is Stmt.InlineTrace -> stmt.copy(
            message = substitute(stmt.message, bindings, invokeLine),
            level = stmt.level?.let { substitute(it, bindings, invokeLine) },
        )
        is Stmt.IndexAssign -> stmt.copy(
            target = substitute(stmt.target, bindings, invokeLine),
            index = substitute(stmt.index, bindings, invokeLine),
            value = substitute(stmt.value, bindings, invokeLine),
        )
        is Stmt.MemberAssign -> stmt.copy(
            target = substitute(stmt.target, bindings, invokeLine),
            value = substitute(stmt.value, bindings, invokeLine),
        )
        is Stmt.DerefAssign -> stmt.copy(
            target = substitute(stmt.target, bindings, invokeLine),
            value = substitute(stmt.value, bindings, invokeLine),
        )
        is Stmt.If -> stmt.copy(
            condition = substitute(stmt.condition, bindings, invokeLine),
            thenBranch = stmt.thenBranch.map { substituteStmt(it, bindings, invokeLine) },
            elseBranch = stmt.elseBranch?.map { substituteStmt(it, bindings, invokeLine) },
        )
        is Stmt.While -> stmt.copy(
            condition = substitute(stmt.condition, bindings, invokeLine),
            body = stmt.body.map { substituteStmt(it, bindings, invokeLine) },
        )
        is Stmt.For -> stmt.copy(
            iterable = substitute(stmt.iterable, bindings, invokeLine),
            step = stmt.step?.let { substitute(it, bindings, invokeLine) },
            body = stmt.body.map { substituteStmt(it, bindings, invokeLine) },
        )
        is Stmt.Loop -> stmt.copy(
            iterable = stmt.iterable?.let { substitute(it, bindings, invokeLine) },
            body = stmt.body.map { substituteStmt(it, bindings, invokeLine) },
        )
        is Stmt.Scope -> stmt.copy(body = stmt.body.map { substituteStmt(it, bindings, invokeLine) })
        is Stmt.InlineBlock -> stmt.copy(body = stmt.body.map { substituteStmt(it, bindings, invokeLine) })
        is Stmt.DeepInlineBlock -> stmt.copy(body = stmt.body.map { substituteStmt(it, bindings, invokeLine) })
        is Stmt.Effect -> stmt.copy(
            body = stmt.body.map { substituteStmt(it, bindings, invokeLine) },
            dependencies = stmt.dependencies?.map { substitute(it, bindings, invokeLine) },
        )
        is Stmt.WithContext -> stmt.copy(
            values = stmt.values.map { substitute(it, bindings, invokeLine) },
            body = stmt.body.map { substituteStmt(it, bindings, invokeLine) },
        )
        is Stmt.Defer -> stmt.copy(body = stmt.body.map { substituteStmt(it, bindings, invokeLine) })
        is Stmt.Try -> stmt.copy(
            body = stmt.body.map { substituteStmt(it, bindings, invokeLine) },
            catchBody = stmt.catchBody?.map { substituteStmt(it, bindings, invokeLine) },
        )
        is Stmt.When -> stmt.copy(
            scrutinee = substitute(stmt.scrutinee, bindings, invokeLine),
            branches = stmt.branches.map { b ->
                b.copy(
                    patterns = b.patterns.map { substitute(it, bindings, invokeLine) },
                    body = b.body.map { substituteStmt(it, bindings, invokeLine) },
                )
            },
            elseBranch = stmt.elseBranch?.map { substituteStmt(it, bindings, invokeLine) },
        )
        is Stmt.NoInline -> stmt.copy(stmt = substituteStmt(stmt.stmt, bindings, invokeLine))
        is Stmt.InlineIf -> stmt.copy(
            condition = substitute(stmt.condition, bindings, invokeLine),
            thenBranch = stmt.thenBranch.map { substituteStmt(it, bindings, invokeLine) },
            elseBranch = stmt.elseBranch?.map { substituteStmt(it, bindings, invokeLine) },
        )
        is Stmt.DeepInlineIf -> stmt.copy(
            condition = substitute(stmt.condition, bindings, invokeLine),
            thenBranch = stmt.thenBranch.map { substituteStmt(it, bindings, invokeLine) },
            elseBranch = stmt.elseBranch?.map { substituteStmt(it, bindings, invokeLine) },
        )
        is Stmt.InlineFor -> stmt.copy(
            iterable = substitute(stmt.iterable, bindings, invokeLine),
            body = stmt.body.map { substituteStmt(it, bindings, invokeLine) },
        )
        is Stmt.Break, is Stmt.Continue -> stmt
    }

    /**
     * Rebuilds a sequence-position [Expr] list, splicing any `...$capture` whose
     * name is in [bindings] inline (each spliced element itself substituted).
     */
    private fun substituteSeq(children: List<Expr>, bindings: Map<String, List<Expr>>, invokeLine: Int): List<Expr> {
        if (children.isEmpty()) return children
        val out = ArrayList<Expr>(children.size)
        for (child in children) {
            val spliced = trySpread(child, bindings)
            if (spliced != null) {
                for (element in spliced) out.add(substitute(element, bindings, invokeLine))
            } else {
                out.add(substitute(child, bindings, invokeLine))
            }
        }
        return out
    }

    /** Returns the captured list if [child] is `Spread(Identifier(name))` with [name] bound; else null. */
    private fun trySpread(child: Expr, bindings: Map<String, List<Expr>>): List<Expr>? {
        if (child !is Expr.Spread) return null
        val inner = child.array
        if (inner !is Expr.Identifier) return null
        return bindings[inner.name]
    }

    private fun fail(line: Int, message: String): Nothing {
        throw IllegalStateException(if (line != 0) "$message at line $line" else message)
    }
}
