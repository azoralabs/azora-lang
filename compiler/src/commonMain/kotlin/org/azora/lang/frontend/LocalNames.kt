package org.azora.lang.frontend

/**
 * Every name a body declares for its own use, in any nested block: bindings,
 * loop variables and a `catch` binding. A name declared here shadows a
 * declaration of the same spelling outside the body.
 */
internal fun localNamesDeclaredIn(body: List<Stmt>): Set<String> {
    val names = mutableSetOf<String>()
    fun visit(s: Stmt) {
        when (s) {
            is Stmt.VarDecl -> names.add(s.name)
            is Stmt.FinDecl -> names.add(s.name)
            is Stmt.LetDecl -> names.add(s.name)
            is Stmt.InlineFin -> names.add(s.name)
            is Stmt.InlineLet -> names.add(s.name)
            is Stmt.InlineVar -> names.add(s.name)
            is Stmt.RemDecl -> names.add(s.name)
            is Stmt.For -> { names.add(s.name); s.indexName?.let(names::add); s.body.forEach(::visit) }
            is Stmt.InlineFor -> { names.add(s.name); s.indexName?.let(names::add); s.body.forEach(::visit) }
            is Stmt.Try -> { s.catchName?.let(names::add); s.body.forEach(::visit); s.catchBody?.forEach(::visit) }
            is Stmt.If -> { s.thenBranch.forEach(::visit); s.elseBranch?.forEach(::visit) }
            is Stmt.InlineIf -> { s.thenBranch.forEach(::visit); s.elseBranch?.forEach(::visit) }
            is Stmt.DeepInlineIf -> { s.thenBranch.forEach(::visit); s.elseBranch?.forEach(::visit) }
            is Stmt.While -> s.body.forEach(::visit)
            is Stmt.Loop -> s.body.forEach(::visit)
            is Stmt.Scope -> s.body.forEach(::visit)
            is Stmt.InlineBlock -> s.body.forEach(::visit)
            is Stmt.DeepInlineBlock -> s.body.forEach(::visit)
            is Stmt.Defer -> s.body.forEach(::visit)
            is Stmt.Effect -> s.body.forEach(::visit)
            is Stmt.UsingContext -> s.body.forEach(::visit)
            is Stmt.When -> { s.branches.forEach { it.body.forEach(::visit) }; s.elseBranch?.forEach(::visit) }
            is Stmt.NoInline -> visit(s.stmt)
            else -> {}
        }
    }
    body.forEach(::visit)
    return names
}
