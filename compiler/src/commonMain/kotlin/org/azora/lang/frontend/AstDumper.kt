package org.azora.lang.frontend

/**
 * Produces a tree-style dump of the AST for debugging and display.
 */
fun Program.dumpTree(): String {
    val sb = StringBuilder()
    sb.appendLine("Program")
    if (moduleName != null) {
        sb.appendLine("    module: $moduleName")
    }
    for (item in items) {
        dumpTopLevel(sb, item, "    ")
    }
    return sb.toString().trimEnd()
}

/**
 * Renders stability decorators (`@Experimental(since: "0.0.1")` / `@Stable(since: "1.0.0")`)
 * as a compact suffix for the AST dump, so they surface in compiler output / docs tooling.
 */
private fun stabilityNote(annotations: List<Annotation>): String {
    val parts = mutableListOf<String>()
    annotations.find { it.name == "Experimental" || it.name == "Stable" }?.let { a ->
        val since = (a.namedArgs.firstOrNull { it.first == "since" }?.second as? Expr.StringLiteral)?.value
        parts.add(a.name + (since?.let { " since=$it" } ?: ""))
    }
    annotations.find { it.name == "Since" }?.let { a ->
        val v = (a.args.firstOrNull() as? Expr.StringLiteral)?.value
        parts.add("since" + (v?.let { "=$it" } ?: ""))
    }
    annotations.find { it.name == "Deprecated" }?.let { a ->
        val s = (a.namedArgs.firstOrNull { it.first == "since" }?.second as? Expr.StringLiteral)?.value
        val r = (a.namedArgs.firstOrNull { it.first == "replacement" }?.second as? Expr.StringLiteral)?.value
        parts.add("deprecated" + listOfNotNull(s?.let { "since=$it" }, r?.let { "replacement=$it" }).let { if (it.isEmpty()) "" else " (${it.joinToString(", ")})" })
    }
    return if (parts.isEmpty()) "" else " [${parts.joinToString(", ")}]"
}

private fun dumpTopLevel(sb: StringBuilder, item: TopLevel, indent: String) {
    when (item) {
        is TopLevel.Func -> {
            val func = item.decl
            val params = func.params.joinToString(", ") { "${it.name}: ${it.typeName}" }
            val inlineStr = if (func.isInline) ", inline" else ""
            val stability = stabilityNote(func.annotations)
            sb.appendLine("${indent}FuncDecl(name=${func.name}, returnType=${func.returnType}$inlineStr)$stability")
            for (param in func.params) {
                sb.appendLine("$indent    Param(name=${param.name}, type=${param.typeName})")
            }
            sb.appendLine("$indent    body:")
            for (stmt in func.body) {
                dumpStmt(sb, stmt, "$indent        ")
            }
        }
        is TopLevel.VarDecl -> {
            sb.appendLine("${indent}VarDecl(name=${item.name}, type=${item.typeName ?: "inferred"})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, item.initializer, "$indent        ")
        }
        is TopLevel.FinDecl -> {
            sb.appendLine("${indent}FinDecl(name=${item.name}, type=${item.typeName ?: "inferred"})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, item.initializer, "$indent        ")
        }
        is TopLevel.LetDecl -> {
            sb.appendLine("${indent}LetDecl(name=${item.name}, type=${item.typeName ?: "inferred"})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, item.initializer, "$indent        ")
        }
        is TopLevel.InlineVar -> {
            sb.appendLine("${indent}InlineVar(name=${item.name})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, item.initializer, "$indent        ")
        }
        is TopLevel.InlineFin -> {
            sb.appendLine("${indent}InlineFin(name=${item.name})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, item.initializer, "$indent        ")
        }
        is TopLevel.InlineLet -> {
            sb.appendLine("${indent}InlineLet(name=${item.name})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, item.initializer, "$indent        ")
        }
        is TopLevel.InlineAssignment -> {
            sb.appendLine("${indent}InlineAssignment(name=${item.name})")
            sb.appendLine("$indent    value:")
            dumpExpr(sb, item.value, "$indent        ")
        }
        is TopLevel.InlineIf -> {
            sb.appendLine("${indent}InlineIf")
            sb.appendLine("$indent    condition:")
            dumpExpr(sb, item.condition, "$indent        ")
            sb.appendLine("$indent    then:")
            for (i in item.thenBranch) dumpTopLevel(sb, i, "$indent        ")
            if (item.elseBranch != null) {
                sb.appendLine("$indent    else:")
                for (i in item.elseBranch) dumpTopLevel(sb, i, "$indent        ")
            }
        }
        is TopLevel.InlineBlock -> {
            sb.appendLine("${indent}InlineBlock")
            for (i in item.body) dumpTopLevel(sb, i, "$indent    ")
        }
        is TopLevel.DeepInlineBlock -> {
            sb.appendLine("${indent}DeepInlineBlock")
            for (i in item.body) dumpTopLevel(sb, i, "$indent    ")
        }
        is TopLevel.DeepInlineIf -> {
            sb.appendLine("${indent}DeepInlineIf")
            sb.appendLine("$indent    condition:")
            dumpExpr(sb, item.condition, "$indent        ")
            sb.appendLine("$indent    then:")
            for (i in item.thenBranch) dumpTopLevel(sb, i, "$indent        ")
            if (item.elseBranch != null) {
                sb.appendLine("$indent    else:")
                for (i in item.elseBranch) dumpTopLevel(sb, i, "$indent        ")
            }
        }
        is TopLevel.Test -> {
            sb.appendLine("${indent}Test(name=\"${item.name}\", method=${item.method})")
            sb.appendLine("$indent    body:")
            for (s in item.body) dumpStmt(sb, s, "$indent        ")
        }
        is TopLevel.InlineAssert -> {
            sb.appendLine("${indent}InlineAssert")
            sb.appendLine("$indent    condition:")
            dumpExpr(sb, item.condition, "$indent        ")
            sb.appendLine("$indent    message:")
            dumpExpr(sb, item.message, "$indent        ")
        }
        is TopLevel.InlineTrace -> {
            sb.appendLine("${indent}InlineTrace")
            sb.appendLine("$indent    message:")
            dumpExpr(sb, item.message, "$indent        ")
        }
        is TopLevel.Pack -> {
            val fields = item.fields.joinToString(", ") { "${it.name}: ${it.type}" }
            val stability = stabilityNote(item.annotations)
            sb.appendLine("${indent}Pack(name=${item.name}, fields=[$fields])$stability")
        }
        is TopLevel.Deco -> {
            val fields = item.fields.joinToString(", ") { "${it.name}: ${it.type}" }
            val targets = if (item.targets.isEmpty()) "" else ", targets=[${item.targets.joinToString(", ")}]"
            val bindings = if (item.bindings.isEmpty()) "" else item.bindings.joinToString(", ", prefix = ", bindings=[", postfix = "]") {
                val args = if (it.trailingTypeArgs.isEmpty()) "" else "<${it.trailingTypeArgs.joinToString(", ")}>"
                val bindingTargets = if (it.targets.isEmpty()) "" else " for [${it.targets.joinToString(", ")}]"
                "${it.name}$args$bindingTargets"
            }
            val stability = stabilityNote(item.annotations)
            sb.appendLine("${indent}Deco(name=${item.name}, fields=[$fields]$targets$bindings)$stability")
        }
        is TopLevel.Enum -> {
            sb.appendLine("${indent}Enum(name=${item.name}, variants=[${item.variants.joinToString(", ")}])")
        }
        is TopLevel.Fail -> {
            sb.appendLine("${indent}Fail(name=${item.name}, variants=[${item.variants.joinToString(", ")}])")
        }
        is TopLevel.Impl -> {
            val traitArgs = if (item.traitArgs.isNotEmpty()) "<${item.traitArgs.joinToString(", ")}>" else ""
            val values = (item.decoratorArgs.map { "positional" } + item.decoratorNamedArgs.map { it.first })
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "(", postfix = ")")
                .orEmpty()
            val trait = if (item.traitName != null) " for ${item.traitName}$traitArgs$values" else ""
            val target = item.typeName.replace(".", "::")
            sb.appendLine("${indent}Impl(type=$target$trait, methods=[${item.methods.joinToString(", ") { it.name }}])")
        }
        is TopLevel.Bridge -> {
            sb.appendLine("${indent}Bridge(target=${item.target}, funcs=[${item.funcs.joinToString(", ") { it.name }}])")
        }
        is TopLevel.Solo -> {
            sb.appendLine("${indent}Solo(name=${item.name}, fields=[${item.fields.joinToString(", ") { it.name }}], methods=[${item.methods.joinToString(", ") { it.name }}])")
        }
        is TopLevel.Wrap -> {
            sb.appendLine("${indent}Wrap(name=${item.name}, registrations=[${item.registrations.joinToString(", ") { "${it.typeName}(${it.args.size} args)" }}])")
        }
        is TopLevel.Node -> {
            val parent = if (item.parent != null) " : ${item.parent}" else ""
            sb.appendLine("${indent}Node(name=${item.name}$parent, leaf=${item.isLeaf}, params=[${item.params.joinToString(", ") { it.name }}], methods=[${item.methods.joinToString(", ") { it.name }}])")
        }
        is TopLevel.View -> {
            sb.appendLine("${indent}View(name=${item.name}, params=[${item.params.joinToString(", ") { it.name }}])")
        }
        is TopLevel.Hook -> {
            sb.appendLine("${indent}Hook(name=${item.name})")
        }
        is TopLevel.UseImport -> {
            sb.appendLine("${indent}UseImport(${item.imports.joinToString(", ") { (zone, item) -> if (item != null) "$zone::$item" else "$zone::*" }})")
        }
        is TopLevel.Spec -> {
            val typeParams = if (item.typeParams.isEmpty()) "" else "<${item.typeParams.joinToString(", ")}>"
            val callback = item.callback?.let {
                val params = if (it.requiresParens) "(${it.params.joinToString(", ") { p -> p.name + ": " + p.type }})" else ""
                val useAs = it.useAsTemplate?.let { template -> " use as \"$template\"" } ?: ""
                ", callback$params:${it.returnType}$useAs"
            } ?: ""
            sb.appendLine("${indent}Spec(name=${item.name}$typeParams, methods=[${item.methods.joinToString(", ") { it.name }}]$callback)")
        }
        is TopLevel.TypeAlias -> {
            sb.appendLine("${indent}TypeAlias(${item.name} = ${item.type})")
        }
        is TopLevel.Slot -> {
            sb.appendLine("${indent}Slot(name=${item.name}, variants=[${item.variants.joinToString(", ") { v -> v.name + "(" + v.payloadTypes.joinToString(",") + "" + ")" }}])")
        }
    }
}

private fun dumpStmt(sb: StringBuilder, stmt: Stmt, indent: String) {
    when (stmt) {
        is Stmt.VarDecl -> {
            sb.appendLine("${indent}VarDecl(name=${stmt.name}, type=${stmt.type})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, stmt.initializer, "$indent        ")
        }
        is Stmt.FinDecl -> {
            sb.appendLine("${indent}FinDecl(name=${stmt.name}, type=${stmt.type})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, stmt.initializer, "$indent        ")
        }
        is Stmt.LetDecl -> {
            sb.appendLine("${indent}LetDecl(name=${stmt.name}, type=${stmt.type})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, stmt.initializer, "$indent        ")
        }
        is Stmt.InlineFin -> {
            sb.appendLine("${indent}InlineFin(name=${stmt.name}, type=${stmt.type})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, stmt.initializer, "$indent        ")
        }
        is Stmt.InlineLet -> {
            sb.appendLine("${indent}InlineLet(name=${stmt.name}, type=${stmt.type})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, stmt.initializer, "$indent        ")
        }
        is Stmt.InlineVar -> {
            sb.appendLine("${indent}InlineVar(name=${stmt.name}, type=${stmt.type})")
            sb.appendLine("$indent    init:")
            dumpExpr(sb, stmt.initializer, "$indent        ")
        }
        is Stmt.InlineAssignment -> {
            sb.appendLine("${indent}InlineAssignment(name=${stmt.name})")
            sb.appendLine("$indent    value:")
            dumpExpr(sb, stmt.value, "$indent        ")
        }
        is Stmt.Assignment -> {
            sb.appendLine("${indent}Assignment(name=${stmt.name})")
            sb.appendLine("$indent    value:")
            dumpExpr(sb, stmt.value, "$indent        ")
        }
        is Stmt.Return -> {
            sb.appendLine("${indent}Return")
            if (stmt.value != null) {
                dumpExpr(sb, stmt.value, "$indent    ")
            }
        }
        is Stmt.ExprStmt -> {
            sb.appendLine("${indent}ExprStmt")
            dumpExpr(sb, stmt.expr, "$indent    ")
        }
        is Stmt.Zone -> {
            sb.appendLine("${indent}Zone")
            for (s in stmt.body) dumpStmt(sb, s, "$indent    ")
        }
        is Stmt.FriendZone -> {
            sb.appendLine("${indent}FriendZone")
            for (s in stmt.body) dumpStmt(sb, s, "$indent    ")
        }
        is Stmt.If -> {
            sb.appendLine("${indent}If")
            sb.appendLine("$indent    condition:")
            dumpExpr(sb, stmt.condition, "$indent        ")
            sb.appendLine("$indent    then:")
            for (s in stmt.thenBranch) dumpStmt(sb, s, "$indent        ")
            if (stmt.elseBranch != null) {
                sb.appendLine("$indent    else:")
                for (s in stmt.elseBranch) dumpStmt(sb, s, "$indent        ")
            }
        }
        is Stmt.InlineIf -> {
            sb.appendLine("${indent}InlineIf")
            sb.appendLine("$indent    condition:")
            dumpExpr(sb, stmt.condition, "$indent        ")
            sb.appendLine("$indent    then:")
            for (s in stmt.thenBranch) dumpStmt(sb, s, "$indent        ")
            if (stmt.elseBranch != null) {
                sb.appendLine("$indent    else:")
                for (s in stmt.elseBranch) dumpStmt(sb, s, "$indent        ")
            }
        }
        is Stmt.InlineBlock -> {
            sb.appendLine("${indent}InlineBlock")
            for (s in stmt.body) dumpStmt(sb, s, "$indent    ")
        }
        is Stmt.InlineFor -> {
            sb.appendLine("${indent}InlineFor ${stmt.name} in ${stmt.iterable::class.simpleName}")
            for (s in stmt.body) dumpStmt(sb, s, "$indent    ")
        }
        is Stmt.DeepInlineBlock -> {
            sb.appendLine("${indent}DeepInlineBlock")
            for (s in stmt.body) dumpStmt(sb, s, "$indent    ")
        }
        is Stmt.DeepInlineIf -> {
            sb.appendLine("${indent}DeepInlineIf")
            sb.appendLine("$indent    condition:")
            dumpExpr(sb, stmt.condition, "$indent        ")
            sb.appendLine("$indent    then:")
            for (s in stmt.thenBranch) dumpStmt(sb, s, "$indent        ")
            if (stmt.elseBranch != null) {
                sb.appendLine("$indent    else:")
                for (s in stmt.elseBranch) dumpStmt(sb, s, "$indent        ")
            }
        }
        is Stmt.NoInline -> {
            sb.appendLine("${indent}NoInline")
            dumpStmt(sb, stmt.stmt, "$indent    ")
        }
        is Stmt.Assert -> {
            sb.appendLine("${indent}Assert")
            sb.appendLine("$indent    condition:")
            dumpExpr(sb, stmt.condition, "$indent        ")
            sb.appendLine("$indent    message:")
            dumpExpr(sb, stmt.message, "$indent        ")
        }
        is Stmt.Trace -> {
            sb.appendLine("${indent}Trace")
            sb.appendLine("$indent    message:")
            dumpExpr(sb, stmt.message, "$indent        ")
        }
        is Stmt.InlineAssert -> {
            sb.appendLine("${indent}InlineAssert")
            sb.appendLine("$indent    condition:")
            dumpExpr(sb, stmt.condition, "$indent        ")
            sb.appendLine("$indent    message:")
            dumpExpr(sb, stmt.message, "$indent        ")
        }
        is Stmt.InlineTrace -> {
            sb.appendLine("${indent}InlineTrace")
            sb.appendLine("$indent    message:")
            dumpExpr(sb, stmt.message, "$indent        ")
        }
        is Stmt.While -> {
            sb.appendLine("${indent}While")
            sb.appendLine("$indent    condition:")
            dumpExpr(sb, stmt.condition, "$indent        ")
            sb.appendLine("$indent    body:")
            stmt.body.forEach { dumpStmt(sb, it, "$indent    ") }
        }
        is Stmt.For -> {
            sb.appendLine("${indent}For(var=${stmt.name})")
            sb.appendLine("$indent    iterable:")
            dumpExpr(sb, stmt.iterable, "$indent        ")
            sb.appendLine("$indent    body:")
            stmt.body.forEach { dumpStmt(sb, it, "$indent    ") }
        }
        is Stmt.Loop -> {
            sb.appendLine("${indent}Loop")
            stmt.body.forEach { dumpStmt(sb, it, "$indent    ") }
        }
        is Stmt.Break -> sb.appendLine("${indent}Break")
        is Stmt.Continue -> sb.appendLine("${indent}Continue")
        is Stmt.IndexAssign -> {
            sb.appendLine("${indent}IndexAssign")
            sb.appendLine("$indent    target:")
            dumpExpr(sb, stmt.target, "$indent        ")
            sb.appendLine("$indent    index:")
            dumpExpr(sb, stmt.index, "$indent        ")
            sb.appendLine("$indent    value:")
            dumpExpr(sb, stmt.value, "$indent        ")
        }
        is Stmt.MemberAssign -> {
            sb.appendLine("${indent}MemberAssign(name=${stmt.name})")
            sb.appendLine("$indent    target:")
            dumpExpr(sb, stmt.target, "$indent        ")
            sb.appendLine("$indent    value:")
            dumpExpr(sb, stmt.value, "$indent        ")
        }
        is Stmt.DerefAssign -> {
            sb.appendLine("${indent}DerefAssign")
            sb.appendLine("$indent    target:")
            dumpExpr(sb, stmt.target, "$indent        ")
            sb.appendLine("$indent    value:")
            dumpExpr(sb, stmt.value, "$indent        ")
        }
        is Stmt.RemDecl -> {
            sb.appendLine("${indent}ReactiveDecl(kind=${stmt.kind}, name=${stmt.name})")
        }
        is Stmt.Effect -> {
            sb.appendLine("${indent}Effect")
            for (s in stmt.body) dumpStmt(sb, s, "$indent    ")
        }
        is Stmt.When -> {
            sb.appendLine("${indent}When")
            sb.appendLine("$indent    scrutinee:")
            dumpExpr(sb, stmt.scrutinee, "$indent        ")
            for (branch in stmt.branches) {
                sb.appendLine("$indent    branch(${branch.patterns.size} patterns):")
                for (p in branch.patterns) dumpExpr(sb, p, "$indent        ")
                for (s in branch.body) dumpStmt(sb, s, "$indent        ")
            }
            if (stmt.elseBranch != null) {
                sb.appendLine("$indent    else:")
                for (s in stmt.elseBranch) dumpStmt(sb, s, "$indent        ")
            }
        }
        is Stmt.Throw -> {
            sb.appendLine("${indent}Throw")
            dumpExpr(sb, stmt.value, "$indent    ")
        }
        is Stmt.Panic -> {
            sb.appendLine("${indent}Panic(inline=${stmt.inlinePanic})")
            dumpExpr(sb, stmt.message, "$indent    ")
        }
        is Stmt.Yield -> {
            sb.appendLine("${indent}Yield")
            dumpExpr(sb, stmt.value, "$indent    ")
        }
        is Stmt.Try -> {
            sb.appendLine("${indent}Try(catchName=${stmt.catchName})")
            for (s in stmt.body) dumpStmt(sb, s, "$indent    ")
            if (stmt.catchBody != null) for (s in stmt.catchBody) dumpStmt(sb, s, "$indent    ")
        }
        is Stmt.Defer -> {
            sb.appendLine("${indent}Defer")
            stmt.body.forEach { dumpStmt(sb, it, "$indent    ") }
        }
    }
}

private fun dumpExpr(sb: StringBuilder, expr: Expr, indent: String) {
    when (expr) {
        is Expr.IntLiteral -> sb.appendLine("${indent}IntLiteral(${expr.value}, suffix=${expr.suffix})")
        is Expr.RealLiteral -> sb.appendLine("${indent}RealLiteral(${expr.value}, suffix=${expr.suffix})")
        is Expr.StringLiteral -> sb.appendLine("${indent}StringLiteral(\"${expr.value}\")")
        is Expr.BoolLiteral -> sb.appendLine("${indent}BoolLiteral(${expr.value})")
        is Expr.CharLiteral -> sb.appendLine("${indent}CharLiteral('${expr.value}')")
        is Expr.Identifier -> sb.appendLine("${indent}Identifier(${expr.name})")
        is Expr.Binary -> {
            sb.appendLine("${indent}Binary(op=${expr.op})")
            dumpExpr(sb, expr.left, "$indent    ")
            dumpExpr(sb, expr.right, "$indent    ")
        }
        is Expr.Unary -> {
            sb.appendLine("${indent}Unary(op=${expr.op})")
            dumpExpr(sb, expr.operand, "$indent    ")
        }
        is Expr.Call -> {
            sb.appendLine("${indent}Call(name=${expr.callee})")
            for (arg in expr.args) {
                dumpExpr(sb, arg, "$indent    ")
            }
        }
        is Expr.UpperScopeAccess -> {
            val access = "::" + "_::".repeat(expr.depth - 1) + expr.name
            sb.appendLine("${indent}UpperScopeAccess($access, depth=${expr.depth})")
        }
        is Expr.Grouping -> {
            sb.appendLine("${indent}Grouping")
            dumpExpr(sb, expr.expr, "$indent    ")
        }
        is Expr.Range -> {
            val op = if (expr.inclusive) ".." else "..<"
            sb.appendLine("${indent}Range($op)")
            dumpExpr(sb, expr.from, "$indent    ")
            dumpExpr(sb, expr.to, "$indent    ")
        }
        is Expr.TupleLit -> {
            sb.appendLine("${indent}TupleLit")
            for (e in expr.elements) dumpExpr(sb, e, "$indent    ")
        }
        is Expr.VariantLit -> {
            sb.appendLine("${indent}VariantLit")
            for (e in expr.elements) dumpExpr(sb, e, "$indent    ")
        }
        is Expr.TupleAccess -> {
            sb.appendLine("${indent}TupleAccess(.${expr.index})")
            dumpExpr(sb, expr.target, "$indent    ")
        }
        is Expr.CatchExpr -> {
            sb.appendLine("${indent}CatchExpr")
            dumpExpr(sb, expr.expr, "$indent    ")
            dumpExpr(sb, expr.fallback, "$indent    ")
        }
        is Expr.TryPropagate -> {
            sb.appendLine("${indent}TryPropagate")
            dumpExpr(sb, expr.expr, "$indent    ")
        }
        is Expr.IfExpr -> {
            sb.appendLine("${indent}IfExpr")
            dumpExpr(sb, expr.condition, "$indent    ")
            dumpExpr(sb, expr.thenExpr, "$indent    ")
            dumpExpr(sb, expr.elseExpr, "$indent    ")
        }
        is Expr.Lambda -> {
            sb.appendLine("${indent}Lambda(params=[${expr.params.joinToString(", ") { "${it.name}: ${it.type}" }}])")
            for (s in expr.body) dumpStmt(sb, s, "$indent    ")
        }
        is Expr.NamedArg -> {
            sb.appendLine("${indent}NamedArg(${expr.name})")
            dumpExpr(sb, expr.value, "$indent    ")
        }
        is Expr.NullLiteral -> sb.appendLine("${indent}NullLiteral")
        is Expr.NullCoalesce -> {
            sb.appendLine("${indent}NullCoalesce")
            dumpExpr(sb, expr.left, "$indent    ")
            dumpExpr(sb, expr.right, "$indent    ")
        }
        is Expr.Cast -> {
            sb.appendLine("${indent}Cast(${expr.targetType})")
            dumpExpr(sb, expr.expr, "$indent    ")
        }
        is Expr.IsCheck -> {
            sb.appendLine("${indent}IsCheck(${expr.typeName})")
            dumpExpr(sb, expr.expr, "$indent    ")
        }
        is Expr.MapLit -> {
            sb.appendLine("${indent}MapLit")
            for ((k, v) in expr.entries) { dumpExpr(sb, k, "$indent    "); dumpExpr(sb, v, "$indent    ") }
        }
        is Expr.SetLiteral -> {
            sb.appendLine("${indent}SetLiteral")
            for (element in expr.elements) dumpExpr(sb, element, "$indent    ")
        }
        is Expr.Alloc -> {
            sb.appendLine("${indent}Alloc")
            dumpExpr(sb, expr.value, "$indent    ")
        }
        is Expr.AllocBuffer -> {
            sb.appendLine("${indent}AllocBuffer(${expr.typeName})")
            dumpExpr(sb, expr.count, "$indent    ")
        }
        is Expr.Deref -> {
            sb.appendLine("${indent}Deref")
            dumpExpr(sb, expr.target, "$indent    ")
        }
        is Expr.Isolated -> {
            sb.appendLine("${indent}Isolated")
            dumpExpr(sb, expr.value, "$indent    ")
        }
        is Expr.Await -> {
            sb.appendLine("${indent}Await")
            dumpExpr(sb, expr.value, "$indent    ")
        }
        is Expr.Inject -> {
            sb.appendLine("${indent}Inject(${expr.typeName})")
        }
        is Expr.Spread -> {
            sb.appendLine("${indent}Spread")
            dumpExpr(sb, expr.array, "$indent    ")
        }
        is Expr.SafeMember -> {
            sb.appendLine("${indent}SafeMember(${expr.name})")
            dumpExpr(sb, expr.target, "$indent    ")
        }
        is Expr.ArrayLiteral -> {
            sb.appendLine("${indent}ArrayLiteral")
            for (elem in expr.elements) dumpExpr(sb, elem, "$indent    ")
        }
        is Expr.Index -> {
            sb.appendLine("${indent}Index")
            sb.appendLine("$indent    target:")
            dumpExpr(sb, expr.target, "$indent        ")
            sb.appendLine("$indent    index:")
            dumpExpr(sb, expr.index, "$indent        ")
        }
        is Expr.Member -> {
            sb.appendLine("${indent}Member(name=${expr.name})")
            dumpExpr(sb, expr.target, "$indent    ")
        }
        is Expr.MethodCall -> {
            sb.appendLine("${indent}MethodCall(name=${expr.name})")
            sb.appendLine("$indent    target:")
            dumpExpr(sb, expr.target, "$indent        ")
            for (arg in expr.args) dumpExpr(sb, arg, "$indent    ")
        }
        is Expr.StringTemplate -> {
            sb.appendLine("${indent}StringTemplate")
            for (part in expr.parts) {
                when (part) {
                    is Expr.StringTemplatePart.Literal -> sb.appendLine("$indent    Literal(\"${part.text}\")")
                    is Expr.StringTemplatePart.Expr -> {
                        sb.appendLine("$indent    Expr:")
                        dumpExpr(sb, part.expr, "$indent        ")
                    }
                }
            }
        }
    }
}
