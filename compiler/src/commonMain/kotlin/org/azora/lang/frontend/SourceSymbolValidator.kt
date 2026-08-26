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

/** Validates names exactly as the author wrote them, before compiler synthesis. */
internal object SourceSymbolValidator {

    fun validateTokens(tokens: List<Token>) {
        for (token in tokens) {
            if (token.type != TokenType.IDENTIFIER) continue
            val name = token.lexeme
            when {
                name.startsWith("__") -> sourceError(
                    token.line,
                    "symbol '$name' is reserved for compiler-generated declarations; user symbols cannot start with '__'",
                )
                '_' in name.drop(1) -> sourceError(
                    token.line,
                    "invalid symbol '$name': '_' is allowed only once, at the beginning of a private declaration",
                )
            }
        }
    }

    fun validateProgram(program: Program) {
        program.moduleName?.split('.')?.forEach { declaration(it, "module", 1) }
        program.scopeTypeNamespaces.values.forEach { path ->
            path.split("::").forEach { declaration(it, "scope", 1) }
        }
        program.items.forEach(::topLevel)
        program.typeFunctions.forEach { declaration(it.name, "type property", it.line) }
        program.typeFunctions.forEach { function ->
            function.params.forEach { declaration(it.name, "type parameter", function.line) }
            typeFunctionStatements(function.body, function.line)
        }
    }

    private fun topLevel(item: TopLevel) {
        when (item) {
            is TopLevel.Func -> function(item.decl)
            is TopLevel.VarDecl -> storage(item.name, item.line)
            is TopLevel.FinDecl -> storage(item.name, item.line)
            is TopLevel.LetDecl -> storage(item.name, item.line)
            is TopLevel.InlineVar -> storage(item.name, item.line)
            is TopLevel.InlineFin -> storage(item.name, item.line)
            is TopLevel.InlineLet -> storage(item.name, item.line)
            is TopLevel.InlineAssignment -> expression(item.value)
            is TopLevel.InlineIf -> {
                expression(item.condition)
                item.thenBranch.forEach(::topLevel)
                item.elseBranch?.forEach(::topLevel)
            }
            is TopLevel.InlineBlock -> item.body.forEach(::topLevel)
            is TopLevel.DeepInlineBlock -> item.body.forEach(::topLevel)
            is TopLevel.DeepInlineIf -> {
                expression(item.condition)
                item.thenBranch.forEach(::topLevel)
                item.elseBranch?.forEach(::topLevel)
            }
            is TopLevel.Test -> item.body.forEach(::statement)
            is TopLevel.Pack -> {
                if (item.nameMacro == null) declaration(item.name, "pack", item.line, privateAllowed = true)
                else expression(item.nameMacro)
                item.typeParams.forEach { declaration(it, "type parameter", item.line) }
                item.variadicParam?.let { declaration(it, "variadic type parameter", item.line) }
                item.fields.forEach { field(it, item.line, privateAllowed = true) }
                item.fieldTemplate?.let { template ->
                    declaration(template.loopVar, "inline loop variable", item.line)
                    declaration(template.packVar, "variadic type parameter", item.line)
                    template.fields.forEach { declaration(it.name, "generated field", item.line, privateAllowed = true) }
                    template.mixins.forEach(::expression)
                }
                item.whereClause?.let(::expression)
            }
            is TopLevel.Deco -> {
                declaration(item.name, "decorator", item.line, privateAllowed = true)
                item.fields.forEach { field(it, item.line, privateAllowed = true) }
            }
            is TopLevel.Bridge -> {
                item.funcs.forEach { signature ->
                    scopeDeclaration(signature.name, "bridge function", signature.line)
                    signature.localName?.let { scopeDeclaration(it, "bridge function", signature.line) }
                    signature.nameMacro?.let(::expression)
                    signature.typeParams.forEach { declaration(it, "type parameter", signature.line) }
                    signature.params.forEach { parameter(it, signature.line) }
                }
                item.values.forEach { value ->
                    scopeDeclaration(value.name, "bridge value", value.line)
                    value.nameMacro?.let(::expression)
                    expression(value.initializer)
                }
            }
            is TopLevel.Solo -> {
                declaration(item.name, "solo pack", item.line, privateAllowed = true)
                item.fields.forEach { field(it, item.line, privateAllowed = true) }
                item.methods.forEach(::function)
            }
            is TopLevel.Graph -> declaration(item.name, "graph", item.line, privateAllowed = true)
            is TopLevel.UseImport -> Unit
            is TopLevel.Enum -> {
                declaration(item.name, "enum", item.line, privateAllowed = true)
                item.variants.forEach { declaration(it, "enum value", item.line, privateAllowed = true) }
            }
            is TopLevel.Fail -> {
                declaration(item.name, "error", item.line, privateAllowed = true)
                item.variants.forEach { declaration(it, "error value", item.line, privateAllowed = true) }
            }
            is TopLevel.Impl -> {
                item.typeParams.forEach { declaration(it, "type parameter", item.line) }
                item.variadicParam?.let { declaration(it, "variadic type parameter", item.line) }
                item.methods.forEach(::function)
                item.decoratorArgs.forEach(::expression)
                item.decoratorNamedArgs.forEach { expression(it.second) }
            }
            is TopLevel.Spec -> {
                declaration(item.name, "spec", item.line, privateAllowed = true)
                item.typeParams.forEach { declaration(it, "type parameter", item.line) }
                item.methods.forEach(::function)
                item.callback?.let { callback ->
                    declaration(callback.receiverName, "receiver", item.line)
                    callback.params.forEach { parameter(it, item.line) }
                    callback.typeParams.forEach { declaration(it, "type parameter", item.line) }
                }
            }
            is TopLevel.TypeAlias -> {
                declaration(item.name, "type alias", item.line, privateAllowed = true)
                item.typeParams.forEach { declaration(it, "type parameter", item.line) }
            }
            is TopLevel.Slot -> {
                declaration(item.name, if (item.isError) "variant error" else "variant enum", item.line, privateAllowed = true)
                item.variants.forEach { declaration(it.name, "variant", item.line, privateAllowed = true) }
            }
            is TopLevel.Meta -> {
                scopeDeclaration(item.name, "macro", item.line)
                item.parameter?.let { declaration(it, "macro parameter", item.line) }
                item.arms.forEach { arm ->
                    when (val pattern = arm.pattern) {
                        is MacroPattern.Empty -> Unit
                        is MacroPattern.SeqCapture -> declaration(pattern.name, "macro capture", item.line)
                        is MacroPattern.TypedCapture -> declaration(pattern.name, "macro capture", item.line)
                        is MacroPattern.MapEntryCapture -> {
                            declaration(pattern.keyName, "macro capture", item.line)
                            declaration(pattern.valueName, "macro capture", item.line)
                        }
                    }
                    expression(arm.template)
                    arm.templateTail.forEach(::expression)
                }
            }
            is TopLevel.InlineAssert -> {
                expression(item.condition)
                expression(item.message)
            }
            is TopLevel.InlineTrace -> {
                item.level?.let(::expression)
                expression(item.message)
            }
        }
    }

    private fun function(function: FuncDecl) {
        scopeDeclaration(
            function.name,
            if (function.memberCallStyle == MemberCallStyle.PROPERTY) "property" else "function",
            function.line,
        )
        function.typeParams.forEach { declaration(it, "type parameter", function.line) }
        function.variadicParam?.let { declaration(it, "variadic type parameter", function.line) }
        function.params.forEach { parameter(it, function.line) }
        function.extensionReceiver?.let { parameter(it, function.line) }
        declaration(function.receiverName, "receiver", function.line)
        function.body.forEach(::statement)
        function.whereClause?.let(::expression)
    }

    private fun parameter(parameter: Param, line: Int) {
        declaration(parameter.name, "parameter", line)
        parameter.defaultValue?.let(::expression)
    }

    private fun field(field: PackField, line: Int, privateAllowed: Boolean) {
        declaration(field.name, "field", line, privateAllowed)
        field.default?.let(::expression)
        field.repeats.forEach {
            declaration(it.variable, "inline loop variable", line)
            expression(it.range)
        }
        field.condition?.let(::expression)
    }

    private fun statement(statement: Stmt) {
        when (statement) {
            is Stmt.VarDecl -> { declaration(statement.name, "local variable", statement.line); expression(statement.initializer) }
            is Stmt.FinDecl -> { declaration(statement.name, "local variable", statement.line); expression(statement.initializer) }
            is Stmt.LetDecl -> { declaration(statement.name, "local variable", statement.line); expression(statement.initializer) }
            is Stmt.InlineVar -> { declaration(statement.name, "local compile-time variable", statement.line); expression(statement.initializer) }
            is Stmt.InlineFin -> { declaration(statement.name, "local compile-time variable", statement.line); expression(statement.initializer) }
            is Stmt.InlineLet -> { declaration(statement.name, "local compile-time variable", statement.line); expression(statement.initializer) }
            is Stmt.RemDecl -> { declaration(statement.name, "reactive local variable", statement.line); expression(statement.initializer) }
            is Stmt.InlineAssignment -> expression(statement.value)
            is Stmt.Assignment -> expression(statement.value)
            is Stmt.Return -> statement.value?.let(::expression)
            is Stmt.ExprStmt -> expression(statement.expr)
            is Stmt.Scope -> statement.body.forEach(::statement)
            is Stmt.If -> { expression(statement.condition); statement.thenBranch.forEach(::statement); statement.elseBranch?.forEach(::statement) }
            is Stmt.InlineIf -> { expression(statement.condition); statement.thenBranch.forEach(::statement); statement.elseBranch?.forEach(::statement) }
            is Stmt.DeepInlineIf -> { expression(statement.condition); statement.thenBranch.forEach(::statement); statement.elseBranch?.forEach(::statement) }
            is Stmt.DeepInlineBlock -> statement.body.forEach(::statement)
            is Stmt.NoInline -> statement(statement.stmt)
            is Stmt.InlineBlock -> statement.body.forEach(::statement)
            is Stmt.Assert -> { expression(statement.condition); expression(statement.message) }
            is Stmt.Trace -> { statement.level?.let(::expression); expression(statement.message) }
            is Stmt.InlineAssert -> { expression(statement.condition); expression(statement.message) }
            is Stmt.InlineTrace -> { statement.level?.let(::expression); expression(statement.message) }
            is Stmt.While -> { statement.label?.let { declaration(it, "loop label", statement.line) }; expression(statement.condition); statement.body.forEach(::statement) }
            is Stmt.For -> {
                declaration(statement.name, "loop variable", statement.line)
                statement.indexName?.let { declaration(it, "loop index", statement.line) }
                statement.label?.let { declaration(it, "loop label", statement.line) }
                expression(statement.iterable)
                statement.step?.let(::expression)
                statement.body.forEach(::statement)
            }
            is Stmt.Loop -> { statement.label?.let { declaration(it, "loop label", statement.line) }; statement.iterable?.let(::expression); statement.body.forEach(::statement) }
            is Stmt.InlineFor -> { declaration(statement.name, "inline loop variable", statement.line); statement.indexName?.let { declaration(it, "inline loop index", statement.line) }; expression(statement.iterable); statement.body.forEach(::statement) }
            is Stmt.Break -> statement.value?.let(::expression)
            is Stmt.Continue -> Unit
            is Stmt.IndexAssign -> { expression(statement.target); expression(statement.index); expression(statement.value) }
            is Stmt.MemberAssign -> { expression(statement.target); statement.nameExpr?.let(::expression); expression(statement.value) }
            is Stmt.When -> { expression(statement.scrutinee); statement.branches.forEach { branch -> branch.patterns.forEach(::expression); branch.body.forEach(::statement) }; statement.elseBranch?.forEach(::statement) }
            is Stmt.Throw -> expression(statement.value)
            is Stmt.Panic -> expression(statement.message)
            is Stmt.DerefAssign -> { expression(statement.target); expression(statement.value) }
            is Stmt.Yield -> expression(statement.value)
            is Stmt.Try -> { statement.catchName?.let { declaration(it, "catch variable", statement.line) }; statement.body.forEach(::statement); statement.catchBody?.forEach(::statement) }
            is Stmt.Defer -> statement.body.forEach(::statement)
            is Stmt.Effect -> { statement.dependencies?.forEach(::expression); statement.body.forEach(::statement) }
            is Stmt.UsingContext -> { statement.values.forEach(::expression); statement.body.forEach(::statement) }
        }
    }

    private fun expression(expression: Expr) {
        when (expression) {
            // `.Name` names nothing on its own; `.(args)` carries expressions
            // that name whatever any other call's arguments would.
            is Expr.InferredMember -> expression.ctorArgs?.forEach(::expression)
            is Expr.MapEntryArg -> {
                expression(expression.key)
                expression(expression.value)
            }
            is Expr.IntLiteral, is Expr.DoubleLiteral, is Expr.CharLiteral, is Expr.StringLiteral,
            is Expr.BoolLiteral, is Expr.Identifier, is Expr.UpperScopeAccess, is Expr.NullLiteral,
            is Expr.Inject -> Unit
            is Expr.Binary -> { expression(expression.left); expression(expression.right) }
            is Expr.Unary -> expression(expression.operand)
            is Expr.IncDec -> expression(expression.target)
            is Expr.Call -> { expression.args.forEach(::expression); expression.receiver?.let(::expression) }
            is Expr.Grouping -> expression(expression.expr)
            is Expr.Range -> { expression(expression.from); expression(expression.to) }
            is Expr.ArrayLiteral -> expression.elements.forEach(::expression)
            is Expr.SetLiteral -> expression.elements.forEach(::expression)
            is Expr.Index -> { expression(expression.target); expression(expression.index) }
            is Expr.Member -> { expression(expression.target); expression.nameExpr?.let(::expression) }
            is Expr.MethodCall -> { expression(expression.target); expression.args.forEach(::expression) }
            is Expr.StringTemplate -> expression.parts.forEach { if (it is Expr.StringTemplatePart.Expr) expression(it.expr) }
            is Expr.TupleLit -> expression.elements.forEach(::expression)
            is Expr.VariantLit -> expression.elements.forEach(::expression)
            is Expr.TupleAccess -> expression(expression.target)
            is Expr.CatchExpr -> { expression(expression.expr); expression(expression.fallback) }
            is Expr.TryPropagate -> expression(expression.expr)
            is Expr.Seal -> expression(expression.value)
            is Expr.IfExpr -> { expression(expression.condition); expression(expression.thenExpr); expression(expression.elseExpr) }
            is Expr.Lambda -> {
                expression.typeParams.forEach { declaration(it, "lambda type parameter", expression.line) }
                expression.variadicTypeParam?.let { declaration(it, "lambda variadic type parameter", expression.line) }
                expression.params.forEach { parameter(it, expression.line) }
                expression.receivers.forEach { parameter(it, expression.line) }
                expression.captures.forEach { capture ->
                    declaration(capture.name, "lambda capture", capture.line)
                }
                expression.body.forEach(::statement)
            }
            is Expr.InlineForArgs -> { declaration(expression.name, "inline loop variable", expression.line); expression(expression.iterable); expression(expression.body) }
            is Expr.NamedArg -> expression(expression.value)
            is Expr.NullCoalesce -> { expression(expression.left); expression(expression.right) }
            is Expr.SafeMember -> expression(expression.target)
            is Expr.Cast -> expression(expression.expr)
            is Expr.IsCheck -> expression(expression.expr)
            is Expr.InCheck -> { expression(expression.value); expression(expression.collection) }
            is Expr.MapLit -> expression.entries.forEach { (key, value) -> expression(key); expression(value) }
            is Expr.Alloc -> expression(expression.value)
            is Expr.Deref -> expression(expression.target)
            is Expr.Isolated -> expression(expression.value)
            is Expr.Await -> expression(expression.value)
            is Expr.Spread -> expression(expression.array)
            is Expr.MetaInvoke -> expression.args.forEach(::expression)
            is Expr.Slice -> { expression(expression.target); expression.start?.let(::expression); expression.stop?.let(::expression); expression.step?.let(::expression) }
        }
    }

    private fun typeFunctionStatements(statements: List<TypeFunctionStmt>, line: Int) {
        statements.forEach { statement ->
            when (statement) {
                is TypeFunctionStmt.Binding -> declaration(statement.name, "type property variable", line)
                is TypeFunctionStmt.Assignment -> Unit
                is TypeFunctionStmt.ForEach -> {
                    declaration(statement.name, "type property loop variable", line)
                    typeFunctionStatements(statement.body, line)
                }
                is TypeFunctionStmt.If -> {
                    typeFunctionStatements(statement.thenBody, line)
                    typeFunctionStatements(statement.elseBody, line)
                }
                is TypeFunctionStmt.Return -> Unit
            }
        }
    }

    private fun storage(name: String, line: Int) {
        scopePrefix(name)?.split("__")?.forEach { declaration(it, "scope", line) }
        declaration(sourceMember(name), "storage variable", line, privateAllowed = true)
    }

    private fun scopeDeclaration(name: String, kind: String, line: Int) {
        scopePrefix(name)?.split("__")?.forEach { declaration(it, "scope", line) }
        declaration(sourceMember(name), kind, line, privateAllowed = true)
    }

    private fun declaration(name: String, kind: String, line: Int, privateAllowed: Boolean = false) {
        // User-written `__` was rejected from the original token stream. A name
        // that appears only now was synthesized by parser lowering.
        if (name.startsWith("__")) return
        // `int`, `uint` and `float` are the compiler's own primitives written
        // without their `__`. Nothing may be called one, or called the same
        // with a single underscore: a reader seeing `_int` beside `__int`
        // should not have to work out which is which.
        if (name.trimStart('_') in PRIMITIVE_WORDS) {
            sourceError(
                line,
                "'$name' is too close to the compiler's own '__${name.trimStart('_')}'; " +
                    "a $kind cannot be called that",
            )
        }
        if (!name.startsWith('_')) return
        // `[&]` and `func f(_: Int)` - a receiver or parameter the declaration
        // takes and deliberately does not use. `_` is the name for that, and
        // the only place a name may be one.
        if (name == "_" && kind in NAMELESS_KINDS) return
        when {
            name == "_" -> sourceError(line, "'_' is not a declaration name")
            !privateAllowed -> sourceError(
                line,
                "invalid $kind '$name': this declaration cannot be private with a leading '_'",
            )
        }
    }

    /** What may be called `_`: something taken and deliberately not used. */
    private val NAMELESS_KINDS = setOf("receiver", "parameter")

    /** The compiler's own primitives, minus the `__` that reserves them. */
    private val PRIMITIVE_WORDS = setOf("int", "uint", "float")

    private fun sourceMember(name: String): String {
        for (index in name.length - 2 downTo 1) {
            if (name[index] == '_' && name[index + 1] == '_' && name[index - 1] != '_') {
                return name.substring(index + 2)
            }
        }
        return name
    }

    private fun scopePrefix(name: String): String? {
        for (index in name.length - 2 downTo 1) {
            if (name[index] == '_' && name[index + 1] == '_' && name[index - 1] != '_') {
                return name.substring(0, index)
            }
        }
        return null
    }

    private fun sourceError(line: Int, message: String): Nothing =
        throw IllegalStateException("line $line: $message")
}
