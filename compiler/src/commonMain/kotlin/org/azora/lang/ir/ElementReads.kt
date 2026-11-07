/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.ir

/**
 * Which array parameters of a function may have their elements read.
 *
 * An erased generic body reads `Array<T>` in erased slots, so a concrete
 * array whose elements are laid out at another width can be handed to it only
 * when the body never reaches an element: it asks for the length
 * (`size`, `length`, `isEmpty`, `isNotEmpty`) or passes the array on to a
 * parameter that does no more. `Array_isEmpty(self: Array<Any>)` reads
 * `self.size` and nothing else, so an `Array<Int>` may be given to it; `sum`
 * and `map` read elements, so it may not (GENERICS_DIP §21.2 requires those
 * calls to be specialized).
 *
 * The answer is conservative. Any other mention of the parameter - an index, a
 * loop over it, a binding, a field store, a return, a lambda that captures it,
 * a call to a function this program does not define - counts as a read, and so
 * does a call that recurses back into a function still being examined.
 */
class ElementReads(functions: Iterable<IrFunction>) {
    private val byName = functions.associateBy { it.name }
    private val known = mutableMapOf<String, Set<Int>>()
    private val examining = mutableSetOf<String>()

    /** True when [function] may read the elements of its parameter [index]. */
    fun readsElements(function: String, index: Int): Boolean {
        val fn = byName[function] ?: return true
        return index in readers(fn)
    }

    private fun readers(fn: IrFunction): Set<Int> {
        known[fn.name]?.let { return it }
        if (!examining.add(fn.name)) return fn.params.indices.toSet()
        try {
            val result = fn.params.indices.filter { i -> fn.body.any { reads(it, fn.params[i].first) } }.toSet()
            known[fn.name] = result
            return result
        } finally {
            examining.remove(fn.name)
        }
    }

    private fun reads(stmt: IrStmt, name: String): Boolean = when (stmt) {
        is IrStmt.VarDecl -> reads(stmt.initializer, name)
        is IrStmt.FinDecl -> reads(stmt.initializer, name)
        is IrStmt.LetDecl -> reads(stmt.initializer, name)
        is IrStmt.Assignment -> stmt.name == name || reads(stmt.value, name)
        is IrStmt.Exchange -> reads(stmt.left, name) || reads(stmt.right, name)
        is IrStmt.IndexAssign -> reads(stmt.target, name) || reads(stmt.index, name) || reads(stmt.value, name)
        is IrStmt.MemberAssign -> reads(stmt.target, name) || reads(stmt.value, name)
        is IrStmt.Return -> stmt.value?.let { reads(it, name) } ?: false
        is IrStmt.ExprStmt -> reads(stmt.expr, name)
        is IrStmt.If -> reads(stmt.condition, name) || readsAny(stmt.thenBranch, name) || readsAny(stmt.elseBranch, name)
        is IrStmt.Scope -> readsAny(stmt.body, name)
        is IrStmt.Assert -> reads(stmt.condition, name) || reads(stmt.message, name)
        is IrStmt.Trace -> reads(stmt.level, name) || reads(stmt.message, name)
        is IrStmt.While -> reads(stmt.condition, name) || readsAny(stmt.body, name)
        is IrStmt.For -> reads(stmt.start, name) || reads(stmt.end, name) ||
            (stmt.step?.let { reads(it, name) } ?: false) || readsAny(stmt.body, name)
        is IrStmt.ForEach -> reads(stmt.iterable, name) || readsAny(stmt.body, name)
        is IrStmt.Loop -> readsAny(stmt.body, name)
        is IrStmt.When -> reads(stmt.scrutinee, name) ||
            stmt.branches.any { branch -> branch.patterns.any { reads(it, name) } || readsAny(branch.body, name) } ||
            readsAny(stmt.elseBranch, name)
        is IrStmt.Throw -> reads(stmt.value, name)
        is IrStmt.Try -> readsAny(stmt.body, name) || readsAny(stmt.catchBody, name)
        is IrStmt.Defer -> readsAny(stmt.body, name)
        is IrStmt.Effect -> (stmt.condition?.let { reads(it, name) } ?: false) || readsAny(stmt.body, name)
        is IrStmt.Yield -> reads(stmt.value, name)
        is IrStmt.Break, is IrStmt.Continue -> false
    }

    private fun readsAny(stmts: List<IrStmt>?, name: String): Boolean = stmts?.any { reads(it, name) } ?: false

    private fun isParam(expr: IrExpr, name: String) = expr is IrExpr.Var && expr.name == name

    private fun reads(expr: IrExpr, name: String): Boolean = when (expr) {
        // Any use not recognised below is a read.
        is IrExpr.Var -> expr.name == name
        is IrExpr.Member -> if (isParam(expr.target, name) && expr.name in LENGTH_MEMBERS) false else reads(expr.target, name)
        is IrExpr.Call -> (expr.receiver?.let { reads(it, name) } ?: false) ||
            expr.args.withIndex().any { (i, arg) ->
                // Passed on whole to a direct call: as much as that parameter reads.
                if (expr.receiver == null && isParam(arg, name)) readsElements(expr.name, i) else reads(arg, name)
            }
        is IrExpr.Unary -> reads(expr.operand, name)
        is IrExpr.IncDec -> reads(expr.target, name)
        is IrExpr.Binary -> reads(expr.left, name) || reads(expr.right, name)
        is IrExpr.ArrayLiteral -> expr.elements.any { reads(it, name) }
        is IrExpr.MapLit -> expr.entries.any { reads(it.first, name) || reads(it.second, name) }
        is IrExpr.SetLit -> expr.elements.any { reads(it, name) }
        is IrExpr.Index -> reads(expr.target, name) || reads(expr.index, name)
        is IrExpr.MethodCall -> reads(expr.target, name) || expr.args.any { reads(it, name) }
        is IrExpr.StructCtor -> expr.args.any { reads(it, name) }
        is IrExpr.StringTemplate -> expr.parts.any { it is IrExpr.IrTemplatePart.Expr && reads(it.expr, name) }
        is IrExpr.TupleLit -> expr.elements.any { reads(it, name) }
        is IrExpr.VariantLit -> expr.elements.any { reads(it, name) }
        is IrExpr.TupleAccess -> reads(expr.target, name)
        is IrExpr.CatchExpr -> reads(expr.expr, name) || reads(expr.fallback, name)
        is IrExpr.IfExpr -> reads(expr.condition, name) || reads(expr.thenExpr, name) || reads(expr.elseExpr, name)
        is IrExpr.NumCast -> reads(expr.value, name)
        is IrExpr.EnumToString -> reads(expr.value, name)
        // A closure that mentions the array may read it whenever it runs.
        is IrExpr.Lambda -> expr.body.any { reads(it, name) } ||
            name in expr.byRefCaptures || name in expr.valueCaptures ||
            expr.captureInitializers.values.any { reads(it, name) }
        is IrExpr.Await -> reads(expr.value, name)
        is IrExpr.Spread -> reads(expr.array, name)
        is IrExpr.IntLiteral, is IrExpr.DoubleLiteral, is IrExpr.StringLiteral, is IrExpr.EnumLiteral,
        is IrExpr.BoolLiteral, is IrExpr.CharLiteral, IrExpr.UnitLiteral, is IrExpr.SlotPattern -> false
    }

    private companion object {
        /** Members that read an array's length header and no element. */
        val LENGTH_MEMBERS = setOf("size", "length", "isEmpty", "isNotEmpty")
    }
}
