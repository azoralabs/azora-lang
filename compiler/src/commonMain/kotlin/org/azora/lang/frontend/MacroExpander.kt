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
 * feed args to the same arms). The MVP patterns are `Empty` (zero args) and
 * `SeqCapture` (≥1 arg, bound to a name spliceable via `...$name`). Templates
 * are ordinary expressions; `$name` is a normal [Expr.Identifier] and
 * `...$name` a normal [Expr.Spread], so substitution is a structural copy with
 * splice-aware rebuilding of sequence containers.
 *
 * Errors throw [IllegalStateException], caught by `Compiler.compile()` and
 * surfaced as a [org.azora.lang.CompilationResult.Failure].
 */
internal object MacroExpander {

    /** Guards against infinite macro recursion (a macro expanding into itself). */
    private const val MAX_DEPTH = 64

    /** Per-name macro arms captured from [TopLevel.Meta] declarations (first decl wins). */
    private typealias MacroTable = Map<String, List<MacroArm>>

    /**
     * Rewrites every [Expr.MetaInvoke] in [program] into its matched arm's
     * template and removes all [TopLevel.Meta] declarations. Returns [program]
     * unchanged when it declares no macros.
     */
    fun expand(program: Program): Program {
        val macros = LinkedHashMap<String, MutableList<MacroArm>>()
        val nonMacros = mutableListOf<TopLevel>()
        for (item in program.items) {
            if (item is TopLevel.Meta) {
                macros.getOrPut(item.name) { mutableListOf() }.addAll(item.arms)
            } else {
                nonMacros.add(item)
            }
        }
        // Fast path: nothing to do when there are no macro declarations AND the
        // program never invokes a macro. (A program that *uses* `name!` without
        // defining/importing a macro still needs to run, so the use site can fail
        // clearly with "macro 'name' is not defined" rather than leaking
        // MetaInvoke into semantic analysis.)
        if (macros.isEmpty() && !program.usesMacros) return program
        val table: MacroTable = macros
        return program.copy(items = nonMacros.map { rewriteItem(it, table, 0) })
    }

    // ------------------------------------------------------------------
    // Item / function / parameter / annotation rewriting
    // ------------------------------------------------------------------

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
        is TopLevel.Pack -> item.copy(
            fields = item.fields.map { rewriteField(it, macros, depth) },
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        is TopLevel.Deco -> item.copy(
            fields = item.fields.map { rewriteField(it, macros, depth) },
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        is TopLevel.Solo -> item.copy(
            fields = item.fields.map { rewriteField(it, macros, depth) },
            methods = item.methods.map { rewriteFunc(it, macros, depth) },
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        is TopLevel.Node -> item.copy(
            parentArgs = item.parentArgs.map { rewriteExpr(it, macros, depth) },
            extraFields = item.extraFields.map { rewriteField(it, macros, depth) },
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
        is TopLevel.View -> item.copy(
            params = item.params.map { rewriteParam(it, macros, depth) },
            body = rewriteStmts(item.body, macros, depth),
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        is TopLevel.Hook -> item.copy(
            body = rewriteStmts(item.body, macros, depth),
            annotations = item.annotations.map { rewriteAnnotation(it, macros, depth) },
        )
        is TopLevel.Wrap -> item.copy(
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
        is Stmt.Zone -> stmt.copy(body = rewriteStmts(stmt.body, macros, depth))
        is Stmt.FriendZone -> stmt.copy(body = rewriteStmts(stmt.body, macros, depth))
        is Stmt.InlineBlock -> stmt.copy(body = rewriteStmts(stmt.body, macros, depth))
        is Stmt.DeepInlineBlock -> stmt.copy(body = rewriteStmts(stmt.body, macros, depth))
        is Stmt.Effect -> stmt.copy(body = rewriteStmts(stmt.body, macros, depth))
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
            is Expr.IntLiteral, is Expr.RealLiteral, is Expr.CharLiteral,
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
            is Expr.IsCheck -> expr.copy(expr = rewriteExpr(expr.expr, macros, depth))
            is Expr.Alloc -> expr.copy(value = rewriteExpr(expr.value, macros, depth))
            is Expr.AllocBuffer -> expr.copy(count = rewriteExpr(expr.count, macros, depth))
            is Expr.Deref -> expr.copy(target = rewriteExpr(expr.target, macros, depth))
            is Expr.Isolated -> expr.copy(value = rewriteExpr(expr.value, macros, depth))
            is Expr.Await -> expr.copy(value = rewriteExpr(expr.value, macros, depth))
            is Expr.TryPropagate -> expr.copy(expr = rewriteExpr(expr.expr, macros, depth))
            is Expr.Spread -> expr.copy(array = rewriteExpr(expr.array, macros, depth))
            is Expr.NamedArg -> expr.copy(value = rewriteExpr(expr.value, macros, depth))
            is Expr.Call -> expr.copy(args = expr.args.map { rewriteExpr(it, macros, depth) })
            is Expr.MethodCall -> expr.copy(
                target = rewriteExpr(expr.target, macros, depth),
                args = expr.args.map { rewriteExpr(it, macros, depth) },
            )
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
                is MacroPattern.MapEntryCapture -> {
                    // `[...${key: value}]` — key/value destructuring. Invocation-side
                    // support (parsing `map!["a": 1]` into paired args) is a later
                    // stage; the arm parses and stores its capture names for now.
                }
            }
        }
        return null
    }

    /** Resolves a [Expr.MetaInvoke] to its expanded template expression. */
    private fun expandOne(node: Expr.MetaInvoke, macros: MacroTable): Expr {
        val arms = macros[node.name]
            ?: fail(node.line, "macro '${node.name}' is not defined")
        val (arm, bindings) = matchArm(arms, node.args)
            ?: fail(node.line, "no matching arm in macro '${node.name}' for ${node.args.size} argument(s)")
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
            if (template.name in bindings) {
                fail(template.line.takeIf { it != 0 } ?: invokeLine,
                    "macro capture '\$${template.name}' must appear under '...' in a call/sequence position; a bare reference binds multiple expressions")
            }
            template
        }
        // Leaves — no Expr children. NullLiteral is an immutable singleton, safe to share.
        is Expr.IntLiteral, is Expr.RealLiteral, is Expr.CharLiteral,
        is Expr.StringLiteral, is Expr.BoolLiteral, is Expr.NullLiteral,
        is Expr.UpperScopeAccess, is Expr.Inject -> template
        is Expr.Unary -> template.copy(operand = substitute(template.operand, bindings, invokeLine))
        is Expr.Grouping -> template.copy(expr = substitute(template.expr, bindings, invokeLine))
        is Expr.Member -> template.copy(target = substitute(template.target, bindings, invokeLine))
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
        is Expr.IfExpr -> template.copy(
            condition = substitute(template.condition, bindings, invokeLine),
            thenExpr = substitute(template.thenExpr, bindings, invokeLine),
            elseExpr = substitute(template.elseExpr, bindings, invokeLine),
        )
        is Expr.Cast -> template.copy(expr = substitute(template.expr, bindings, invokeLine))
        is Expr.IsCheck -> template.copy(expr = substitute(template.expr, bindings, invokeLine))
        is Expr.Alloc -> template.copy(value = substitute(template.value, bindings, invokeLine))
        is Expr.AllocBuffer -> template.copy(count = substitute(template.count, bindings, invokeLine))
        is Expr.Deref -> template.copy(target = substitute(template.target, bindings, invokeLine))
        is Expr.Isolated -> template.copy(value = substitute(template.value, bindings, invokeLine))
        is Expr.Await -> template.copy(value = substitute(template.value, bindings, invokeLine))
        is Expr.TryPropagate -> template.copy(expr = substitute(template.expr, bindings, invokeLine))
        is Expr.Spread -> template.copy(array = substitute(template.array, bindings, invokeLine))
        is Expr.NamedArg -> template.copy(value = substitute(template.value, bindings, invokeLine))
        // Sequence containers — splice-aware.
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
        is Stmt.Zone -> stmt.copy(body = stmt.body.map { substituteStmt(it, bindings, invokeLine) })
        is Stmt.FriendZone -> stmt.copy(body = stmt.body.map { substituteStmt(it, bindings, invokeLine) })
        is Stmt.InlineBlock -> stmt.copy(body = stmt.body.map { substituteStmt(it, bindings, invokeLine) })
        is Stmt.DeepInlineBlock -> stmt.copy(body = stmt.body.map { substituteStmt(it, bindings, invokeLine) })
        is Stmt.Effect -> stmt.copy(body = stmt.body.map { substituteStmt(it, bindings, invokeLine) })
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
