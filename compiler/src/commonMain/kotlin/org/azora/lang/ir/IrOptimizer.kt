/*
 * Copyright 2026 AzoraTech
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

package org.azora.lang.ir

/**
 * IR Optimization Passes.
 *
 * Runs on the typed IR before backend lowering. Each pass transforms
 * [IrProgram] → [IrProgram]. Passes are composable and order-independent
 * where possible.
 *
 * Current passes:
 *  1. [constantFold] — Evaluate constant binary/unary expressions.
 *  2. [deadCodeElimination] — Remove unreachable code after return statements.
 *  3. [constantPropagation] — Replace variable reads with known constant values.
 */
class IrOptimizer {

    /**
     * Applies all optimization passes to the given IR program.
     *
     * Passes are applied in order: constant folding, constant propagation,
     * then dead code elimination.
     *
     * @param program the unoptimized IR program
     * @return a new [IrProgram] with all optimizations applied
     */
    fun optimize(program: IrProgram): IrProgram {
        var result = program
        result = constantFold(result)
        result = constantPropagation(result)
        result = deadCodeElimination(result)
        result = unusedSymbolElimination(result)
        return result
    }

    // -----------------------------------------------------------------------
    // Pass 1: Constant Folding
    // -----------------------------------------------------------------------

    private fun constantFold(program: IrProgram): IrProgram {
        return program.copy(items = program.items.map { item ->
            when (item) {
                is IrTopLevel.Global -> IrTopLevel.Global(foldStmt(item.stmt))
                is IrTopLevel.Func -> IrTopLevel.Func(item.function.copy(body = item.function.body.map { foldStmt(it) }))
                is IrTopLevel.Test -> IrTopLevel.Test(item.name, item.body.map { foldStmt(it) })
                is IrTopLevel.Struct -> item
            }
        })
    }

    private fun foldStmt(stmt: IrStmt): IrStmt = when (stmt) {
        is IrStmt.VarDecl -> stmt.copy(initializer = foldExpr(stmt.initializer))
        is IrStmt.FinDecl -> stmt.copy(initializer = foldExpr(stmt.initializer))
        is IrStmt.LetDecl -> stmt.copy(initializer = foldExpr(stmt.initializer))
        is IrStmt.Assignment -> stmt.copy(value = foldExpr(stmt.value))
        is IrStmt.Return -> stmt.copy(value = stmt.value?.let { foldExpr(it) })
        is IrStmt.ExprStmt -> stmt.copy(expr = foldExpr(stmt.expr))
        is IrStmt.If -> stmt.copy(
            condition = foldExpr(stmt.condition),
            thenBranch = stmt.thenBranch.map { foldStmt(it) },
            elseBranch = stmt.elseBranch?.map { foldStmt(it) }
        )
        is IrStmt.Zone -> stmt.copy(body = stmt.body.map { foldStmt(it) })
        is IrStmt.Assert -> stmt.copy(condition = foldExpr(stmt.condition), message = foldExpr(stmt.message))
        is IrStmt.Trace -> stmt.copy(message = foldExpr(stmt.message))
        is IrStmt.While -> stmt.copy(condition = foldExpr(stmt.condition), body = stmt.body.map { foldStmt(it) })
        is IrStmt.For -> stmt.copy(start = foldExpr(stmt.start), end = foldExpr(stmt.end), body = stmt.body.map { foldStmt(it) })
        is IrStmt.Loop -> stmt.copy(body = stmt.body.map { foldStmt(it) })
        is IrStmt.Break -> stmt
        is IrStmt.Continue -> stmt
        is IrStmt.IndexAssign -> stmt.copy(target = foldExpr(stmt.target), index = foldExpr(stmt.index), value = foldExpr(stmt.value))
        is IrStmt.MemberAssign -> stmt.copy(target = foldExpr(stmt.target), value = foldExpr(stmt.value))
        is IrStmt.Yield -> stmt.copy(value = foldExpr(stmt.value))
        is IrStmt.When -> stmt.copy(
            scrutinee = foldExpr(stmt.scrutinee),
            branches = stmt.branches.map { b -> b.copy(patterns = b.patterns.map { foldExpr(it) }, body = b.body.map { foldStmt(it) }) },
            elseBranch = stmt.elseBranch?.map { foldStmt(it) }
        )
        is IrStmt.Throw -> stmt.copy(value = foldExpr(stmt.value))
        is IrStmt.Try -> stmt.copy(
            body = stmt.body.map { foldStmt(it) },
            catchBody = stmt.catchBody?.map { foldStmt(it) }
        )
        is IrStmt.Defer -> stmt.copy(body = stmt.body.map { foldStmt(it) })
        is IrStmt.ForEach -> stmt.copy(iterable = foldExpr(stmt.iterable), body = stmt.body.map { foldStmt(it) })
    }

    private fun foldExpr(expr: IrExpr): IrExpr = when (expr) {
        is IrExpr.Binary -> {
            val left = foldExpr(expr.left)
            val right = foldExpr(expr.right)
            tryFoldBinary(left, expr.op, right) ?: expr.copy(left = left, right = right)
        }
        is IrExpr.Unary -> {
            val operand = foldExpr(expr.operand)
            tryFoldUnary(expr.op, operand) ?: expr.copy(operand = operand)
        }
        is IrExpr.Call -> expr.copy(args = expr.args.map { foldExpr(it) })
        else -> expr
    }

    private fun tryFoldBinary(left: IrExpr, op: IrBinaryOp, right: IrExpr): IrExpr? {
        if (left is IrExpr.IntLiteral && right is IrExpr.IntLiteral) {
            return when (op) {
                IrBinaryOp.ADD -> IrExpr.IntLiteral(left.value + right.value)
                IrBinaryOp.SUB -> IrExpr.IntLiteral(left.value - right.value)
                IrBinaryOp.MUL -> IrExpr.IntLiteral(left.value * right.value)
                IrBinaryOp.DIV -> if (right.value != 0L) IrExpr.IntLiteral(left.value / right.value) else null
                IrBinaryOp.MOD -> if (right.value != 0L) IrExpr.IntLiteral(left.value % right.value) else null
                IrBinaryOp.EQ -> IrExpr.BoolLiteral(left.value == right.value)
                IrBinaryOp.NEQ -> IrExpr.BoolLiteral(left.value != right.value)
                IrBinaryOp.LT -> IrExpr.BoolLiteral(left.value < right.value)
                IrBinaryOp.LTE -> IrExpr.BoolLiteral(left.value <= right.value)
                IrBinaryOp.GT -> IrExpr.BoolLiteral(left.value > right.value)
                IrBinaryOp.GTE -> IrExpr.BoolLiteral(left.value >= right.value)
                else -> null
            }
        }
        if (left is IrExpr.RealLiteral && right is IrExpr.RealLiteral) {
            return when (op) {
                IrBinaryOp.ADD -> IrExpr.RealLiteral(left.value + right.value)
                IrBinaryOp.SUB -> IrExpr.RealLiteral(left.value - right.value)
                IrBinaryOp.MUL -> IrExpr.RealLiteral(left.value * right.value)
                IrBinaryOp.DIV -> IrExpr.RealLiteral(left.value / right.value)
                IrBinaryOp.EQ -> IrExpr.BoolLiteral(left.value == right.value)
                IrBinaryOp.NEQ -> IrExpr.BoolLiteral(left.value != right.value)
                IrBinaryOp.LT -> IrExpr.BoolLiteral(left.value < right.value)
                IrBinaryOp.LTE -> IrExpr.BoolLiteral(left.value <= right.value)
                IrBinaryOp.GT -> IrExpr.BoolLiteral(left.value > right.value)
                IrBinaryOp.GTE -> IrExpr.BoolLiteral(left.value >= right.value)
                IrBinaryOp.BIT_AND -> null // skip constant folding for bitwise
                IrBinaryOp.BIT_OR -> null
                IrBinaryOp.BIT_XOR -> null
                IrBinaryOp.SHL -> null
                IrBinaryOp.SHR -> null
                else -> null
            }
        }
        if (left is IrExpr.BoolLiteral && right is IrExpr.BoolLiteral) {
            return when (op) {
                IrBinaryOp.AND -> IrExpr.BoolLiteral(left.value && right.value)
                IrBinaryOp.OR -> IrExpr.BoolLiteral(left.value || right.value)
                IrBinaryOp.EQ -> IrExpr.BoolLiteral(left.value == right.value)
                IrBinaryOp.NEQ -> IrExpr.BoolLiteral(left.value != right.value)
                else -> null
            }
        }
        return null
    }

    private fun tryFoldUnary(op: IrUnaryOp, operand: IrExpr): IrExpr? {
        if (op == IrUnaryOp.NEG && operand is IrExpr.IntLiteral) return IrExpr.IntLiteral(-operand.value)
        if (op == IrUnaryOp.NEG && operand is IrExpr.RealLiteral) return IrExpr.RealLiteral(-operand.value)
        if (op == IrUnaryOp.NOT && operand is IrExpr.BoolLiteral) return IrExpr.BoolLiteral(!operand.value)
        return null
    }

    // -----------------------------------------------------------------------
    // Pass 2: Constant Propagation
    // -----------------------------------------------------------------------

    private fun constantPropagation(program: IrProgram): IrProgram {
        val globalConstants = mutableMapOf<String, IrExpr>()
        return program.copy(items = program.items.map { item ->
            when (item) {
                is IrTopLevel.Global -> {
                    val optimized = propagateStmts(listOf(item.stmt), globalConstants)
                    IrTopLevel.Global(optimized.first())
                }
                is IrTopLevel.Func -> IrTopLevel.Func(propagateInFunction(item.function))
                is IrTopLevel.Test -> IrTopLevel.Test(item.name, propagateStmts(item.body, mutableMapOf()))
                is IrTopLevel.Struct -> item
            }
        })
    }

    private fun propagateInFunction(func: IrFunction): IrFunction {
        // Track variables known to hold constant values
        val constants = mutableMapOf<String, IrExpr>()

        val newBody = propagateStmts(func.body, constants)

        return func.copy(body = newBody)
    }

    private fun propagateStmts(stmts: List<IrStmt>, constants: MutableMap<String, IrExpr>): List<IrStmt> {
        return stmts.map { stmt ->
            when (stmt) {
                is IrStmt.VarDecl -> {
                    val propagated = propagateExpr(stmt.initializer, constants)
                    val folded = foldExpr(propagated)
                    if (isConstant(folded)) constants[stmt.name] = folded
                    stmt.copy(initializer = folded)
                }
                is IrStmt.FinDecl -> {
                    val propagated = propagateExpr(stmt.initializer, constants)
                    val folded = foldExpr(propagated)
                    // fin is always constant-propagatable (never reassigned)
                    if (isConstant(folded)) constants[stmt.name] = folded
                    stmt.copy(initializer = folded)
                }
                is IrStmt.LetDecl -> {
                    val propagated = propagateExpr(stmt.initializer, constants)
                    val folded = foldExpr(propagated)
                    // let is always constant-propagatable (never reassigned)
                    if (isConstant(folded)) constants[stmt.name] = folded
                    stmt.copy(initializer = folded)
                }
                is IrStmt.Assignment -> {
                    val propagated = propagateExpr(stmt.value, constants)
                    val folded = foldExpr(propagated)
                    if (isConstant(folded)) constants[stmt.name] = folded
                    else constants.remove(stmt.name)
                    stmt.copy(value = folded)
                }
                is IrStmt.Return -> {
                    val value = stmt.value?.let { foldExpr(propagateExpr(it, constants)) }
                    stmt.copy(value = value)
                }
                is IrStmt.ExprStmt -> {
                    stmt.copy(expr = foldExpr(propagateExpr(stmt.expr, constants)))
                }
                is IrStmt.If -> {
                    val cond = foldExpr(propagateExpr(stmt.condition, constants))
                    // If condition is constant, eliminate the branch at IR level
                    val result = if (cond is IrExpr.BoolLiteral) {
                        // Wrap surviving branch in an If with true condition
                        // (DCE will handle further cleanup)
                        val branch = if (cond.value) stmt.thenBranch else (stmt.elseBranch ?: emptyList())
                        stmt.copy(
                            condition = cond,
                            thenBranch = propagateStmts(branch, constants.toMutableMap()),
                            elseBranch = null
                        )
                    } else {
                        stmt.copy(
                            condition = cond,
                            thenBranch = propagateStmts(stmt.thenBranch, constants.toMutableMap()),
                            elseBranch = stmt.elseBranch?.let { propagateStmts(it, constants.toMutableMap()) }
                        )
                    }
                    // A variable assigned in a conditionally-executed branch is no
                    // longer known to hold its previous constant after the `if`.
                    invalidate(constants, collectAssigned(stmt.thenBranch))
                    stmt.elseBranch?.let { invalidate(constants, collectAssigned(it)) }
                    result
                }
                is IrStmt.Zone -> {
                    val result = stmt.copy(body = propagateStmts(stmt.body, constants.toMutableMap()))
                    // Reassignments to outer variables inside the zone persist.
                    invalidate(constants, collectAssigned(stmt.body))
                    result
                }
                is IrStmt.Assert -> stmt.copy(
                    condition = foldExpr(propagateExpr(stmt.condition, constants)),
                    message = foldExpr(propagateExpr(stmt.message, constants))
                )
                is IrStmt.Trace -> stmt.copy(
                    message = foldExpr(propagateExpr(stmt.message, constants))
                )
                is IrStmt.While -> {
                    // Variables mutated in the loop body are not constant across
                    // iterations, so drop them before lowering the condition/body.
                    val assigned = collectAssigned(stmt.body)
                    val inner = constants.toMutableMap().also { invalidate(it, assigned) }
                    val result = stmt.copy(
                        condition = foldExpr(propagateExpr(stmt.condition, inner)),
                        body = propagateStmts(stmt.body, inner)
                    )
                    invalidate(constants, assigned)
                    result
                }
                is IrStmt.For -> {
                    // The counter and any body-assigned variables vary per iteration.
                    val assigned = collectAssigned(stmt.body) + stmt.counter
                    val start = foldExpr(propagateExpr(stmt.start, constants))
                    val end = foldExpr(propagateExpr(stmt.end, constants))
                    val inner = constants.toMutableMap().also { invalidate(it, assigned) }
                    val result = stmt.copy(
                        start = start,
                        end = end,
                        body = propagateStmts(stmt.body, inner)
                    )
                    invalidate(constants, assigned)
                    result
                }
                is IrStmt.Loop -> {
                    val assigned = collectAssigned(stmt.body)
                    val inner = constants.toMutableMap().also { invalidate(it, assigned) }
                    val result = stmt.copy(body = propagateStmts(stmt.body, inner))
                    invalidate(constants, assigned)
                    result
                }
                is IrStmt.Break -> stmt
                is IrStmt.Continue -> stmt
                is IrStmt.IndexAssign -> stmt.copy(
                    target = foldExpr(propagateExpr(stmt.target, constants)),
                    index = foldExpr(propagateExpr(stmt.index, constants)),
                    value = foldExpr(propagateExpr(stmt.value, constants))
                )
                is IrStmt.MemberAssign -> stmt.copy(
                    target = foldExpr(propagateExpr(stmt.target, constants)),
                    value = foldExpr(propagateExpr(stmt.value, constants))
                )
                is IrStmt.Yield -> stmt.copy(value = foldExpr(propagateExpr(stmt.value, constants)))
                is IrStmt.When -> {
                    val result = stmt.copy(
                        scrutinee = foldExpr(propagateExpr(stmt.scrutinee, constants)),
                        branches = stmt.branches.map { b ->
                            b.copy(
                                patterns = b.patterns.map { foldExpr(propagateExpr(it, constants)) },
                                body = propagateStmts(b.body, constants.toMutableMap())
                            )
                        },
                        elseBranch = stmt.elseBranch?.let { propagateStmts(it, constants.toMutableMap()) }
                    )
                    for (b in stmt.branches) invalidate(constants, collectAssigned(b.body))
                    stmt.elseBranch?.let { invalidate(constants, collectAssigned(it)) }
                    result
                }
                is IrStmt.Throw -> stmt.copy(value = foldExpr(propagateExpr(stmt.value, constants)))
                is IrStmt.Try -> {
                    val result = stmt.copy(
                        body = propagateStmts(stmt.body, constants.toMutableMap()),
                        catchBody = stmt.catchBody?.let { propagateStmts(it, constants.toMutableMap()) }
                    )
                    invalidate(constants, collectAssigned(stmt.body))
                    stmt.catchBody?.let { invalidate(constants, collectAssigned(it)) }
                    result
                }
                is IrStmt.Defer -> {
                    val result = stmt.copy(body = propagateStmts(stmt.body, constants.toMutableMap()))
                    invalidate(constants, collectAssigned(stmt.body))
                    result
                }
                is IrStmt.ForEach -> {
                    val result = stmt.copy(
                        iterable = foldExpr(propagateExpr(stmt.iterable, constants)),
                        body = propagateStmts(stmt.body, constants.toMutableMap())
                    )
                    invalidate(constants, collectAssigned(stmt.body))
                    result
                }
            }
        }
    }

    /** Removes [names] from the known-constants map (they are no longer constant). */
    private fun invalidate(constants: MutableMap<String, IrExpr>, names: Set<String>) {
        for (name in names) constants.remove(name)
    }

    /**
     * Collects the names of variables reassigned anywhere within [stmts]
     * (including nested control flow). Such variables cannot be treated as
     * holding a known constant once the enclosing construct may have run.
     */
    private fun collectAssigned(stmts: List<IrStmt>): Set<String> {
        val assigned = mutableSetOf<String>()
        fun visit(stmt: IrStmt) {
            when (stmt) {
                is IrStmt.Assignment -> assigned.add(stmt.name)
                is IrStmt.If -> { stmt.thenBranch.forEach(::visit); stmt.elseBranch?.forEach(::visit) }
                is IrStmt.Zone -> stmt.body.forEach(::visit)
                is IrStmt.While -> stmt.body.forEach(::visit)
                is IrStmt.For -> { assigned.add(stmt.counter); stmt.body.forEach(::visit) }
                is IrStmt.ForEach -> { assigned.add(stmt.elem); stmt.body.forEach(::visit) }
                is IrStmt.Loop -> stmt.body.forEach(::visit)
                is IrStmt.When -> {
                    stmt.branches.forEach { it.body.forEach(::visit) }
                    stmt.elseBranch?.forEach(::visit)
                }
                is IrStmt.Try -> { stmt.body.forEach(::visit); stmt.catchBody?.forEach(::visit) }
                is IrStmt.Defer -> stmt.body.forEach(::visit)
                else -> {}
            }
        }
        stmts.forEach(::visit)
        return assigned
    }

    private fun propagateExpr(expr: IrExpr, constants: Map<String, IrExpr>): IrExpr = when (expr) {
        is IrExpr.Var -> constants[expr.name] ?: expr
        is IrExpr.Binary -> expr.copy(
            left = propagateExpr(expr.left, constants),
            right = propagateExpr(expr.right, constants)
        )
        is IrExpr.Unary -> expr.copy(operand = propagateExpr(expr.operand, constants))
        is IrExpr.Call -> expr.copy(args = expr.args.map { propagateExpr(it, constants) })
        else -> expr
    }

    private fun isConstant(expr: IrExpr): Boolean = when (expr) {
        is IrExpr.IntLiteral, is IrExpr.RealLiteral,
        is IrExpr.StringLiteral, is IrExpr.BoolLiteral,
        is IrExpr.CharLiteral -> true
        else -> false
    }

    // -----------------------------------------------------------------------
    // Pass 3: Dead Code Elimination
    // -----------------------------------------------------------------------

    private fun deadCodeElimination(program: IrProgram): IrProgram {
        return program.copy(items = program.items.map { item ->
            when (item) {
                is IrTopLevel.Global -> IrTopLevel.Global(eliminateDeadCode(listOf(item.stmt)).first())
                is IrTopLevel.Func -> IrTopLevel.Func(item.function.copy(body = eliminateDeadCode(item.function.body)))
                is IrTopLevel.Test -> IrTopLevel.Test(item.name, eliminateDeadCode(item.body))
                is IrTopLevel.Struct -> item
            }
        })
    }

    private fun eliminateDeadCode(body: List<IrStmt>): List<IrStmt> {
        val result = mutableListOf<IrStmt>()
        for (stmt in body) {
            val cleaned = when (stmt) {
                is IrStmt.If -> {
                    val cond = stmt.condition
                    if (cond is IrExpr.BoolLiteral) {
                        // Constant condition — inline the taken branch directly
                        val branch = if (cond.value) stmt.thenBranch else (stmt.elseBranch ?: emptyList())
                        result.addAll(eliminateDeadCode(branch))
                        continue
                    } else {
                        stmt.copy(
                            thenBranch = eliminateDeadCode(stmt.thenBranch),
                            elseBranch = stmt.elseBranch?.let { eliminateDeadCode(it) }
                        )
                    }
                }
                is IrStmt.Zone -> stmt.copy(body = eliminateDeadCode(stmt.body))
                else -> stmt
            }
            result.add(cleaned)
            if (stmt is IrStmt.Return) break
        }
        return result
    }

    // -----------------------------------------------------------------------
    // Pass 4: Unused Symbol Elimination
    // -----------------------------------------------------------------------

    /**
     * Removes unused functions, globals, and local declarations.
     * `main` is always kept as the entry point.
     */
    private fun unusedSymbolElimination(program: IrProgram): IrProgram {
        // Collect all referenced names across the entire program
        val usedNames = mutableSetOf<String>()

        // Start from main — it's always reachable
        val funcMap = mutableMapOf<String, IrFunction>()
        for (item in program.items) {
            if (item is IrTopLevel.Func) funcMap[item.function.name] = item.function
        }

        // Also collect names from test bodies (tests are always reachable)
        for (item in program.items) {
            if (item is IrTopLevel.Test) {
                val refs = collectReferencedNames(item.body)
                usedNames.addAll(refs)
                for (ref in refs) {
                    if (ref in funcMap) usedNames.add(ref)
                }
            }
        }

        // Transitively collect all used function names starting from "main"
        val worklist = ArrayDeque<String>()
        if ("main" in funcMap) worklist.add("main")
        // Also enqueue functions referenced by tests
        for (item in program.items) {
            if (item is IrTopLevel.Test) {
                val refs = collectReferencedNames(item.body)
                for (ref in refs) {
                    if (ref in funcMap) worklist.add(ref)
                }
            }
        }
        val reachableFuncs = mutableSetOf<String>()
        while (worklist.isNotEmpty()) {
            val name = worklist.removeFirst()
            if (!reachableFuncs.add(name)) continue
            val func = funcMap[name] ?: continue
            val refs = collectReferencedNames(func.body)
            usedNames.addAll(refs)
            // Enqueue called functions
            for (ref in refs) {
                if (ref in funcMap) worklist.add(ref)
            }
        }

        // Also collect names used in global initializers of reachable globals
        for (item in program.items) {
            if (item is IrTopLevel.Global) {
                val globalName = when (val s = item.stmt) {
                    is IrStmt.VarDecl -> s.name
                    is IrStmt.FinDecl -> s.name
                    is IrStmt.LetDecl -> s.name
                    else -> null
                }
                if (globalName != null && globalName in usedNames) {
                    val initRefs = collectReferencedNamesFromExpr(when (val s = item.stmt) {
                        is IrStmt.VarDecl -> s.initializer
                        is IrStmt.FinDecl -> s.initializer
                        is IrStmt.LetDecl -> s.initializer
                        else -> null
                    })
                    usedNames.addAll(initRefs)
                }
            }
        }

        // Filter top-level items: keep reachable functions, used globals, and all tests
        val filteredItems = program.items.filter { item ->
            when (item) {
                is IrTopLevel.Func -> item.function.name in reachableFuncs
                is IrTopLevel.Global -> {
                    val name = when (val s = item.stmt) {
                        is IrStmt.VarDecl -> s.name
                        is IrStmt.FinDecl -> s.name
                        is IrStmt.LetDecl -> s.name
                        else -> null
                    }
                    name != null && name in usedNames
                }
                is IrTopLevel.Test -> true // Tests are always kept
                is IrTopLevel.Struct -> true // Struct definitions are always kept
            }
        }

        // Remove unused local declarations inside each function
        val cleanedItems = filteredItems.map { item ->
            when (item) {
                is IrTopLevel.Func -> IrTopLevel.Func(removeUnusedLocals(item.function))
                is IrTopLevel.Global -> item
                is IrTopLevel.Test -> item
                is IrTopLevel.Struct -> item
            }
        }

        return program.copy(items = cleanedItems)
    }

    private fun removeUnusedLocals(func: IrFunction): IrFunction {
        val usedVars = collectReferencedVarNames(func.body)
        val cleanedBody = removeUnusedDeclsFromBody(func.body, usedVars)
        return func.copy(body = cleanedBody)
    }

    private fun removeUnusedDeclsFromBody(body: List<IrStmt>, usedVars: Set<String>): List<IrStmt> {
        return body.mapNotNull { stmt ->
            when (stmt) {
                is IrStmt.VarDecl -> if (stmt.name in usedVars) stmt else null
                is IrStmt.FinDecl -> if (stmt.name in usedVars) stmt else null
                is IrStmt.LetDecl -> if (stmt.name in usedVars) stmt else null
                is IrStmt.If -> stmt.copy(
                    thenBranch = removeUnusedDeclsFromBody(stmt.thenBranch, usedVars),
                    elseBranch = stmt.elseBranch?.let { removeUnusedDeclsFromBody(it, usedVars) }
                )
                is IrStmt.Zone -> {
                    val cleaned = removeUnusedDeclsFromBody(stmt.body, usedVars)
                    if (cleaned.isEmpty()) null else stmt.copy(body = cleaned)
                }
                is IrStmt.Assert -> stmt // Never eliminate asserts
                is IrStmt.Trace -> stmt // Never eliminate traces
                else -> stmt
            }
        }
    }

    /** Collects all names referenced in expressions (vars, calls, upper vars). */
    private fun collectReferencedNames(body: List<IrStmt>): Set<String> {
        val names = mutableSetOf<String>()
        for (stmt in body) collectReferencedNamesFromStmt(stmt, names)
        return names
    }

    private fun collectReferencedNamesFromStmt(stmt: IrStmt, names: MutableSet<String>) {
        when (stmt) {
            is IrStmt.VarDecl -> collectReferencedNamesFromExpr(stmt.initializer, names)
            is IrStmt.FinDecl -> collectReferencedNamesFromExpr(stmt.initializer, names)
            is IrStmt.LetDecl -> collectReferencedNamesFromExpr(stmt.initializer, names)
            is IrStmt.Assignment -> collectReferencedNamesFromExpr(stmt.value, names)
            is IrStmt.Return -> stmt.value?.let { collectReferencedNamesFromExpr(it, names) }
            is IrStmt.ExprStmt -> collectReferencedNamesFromExpr(stmt.expr, names)
            is IrStmt.If -> {
                collectReferencedNamesFromExpr(stmt.condition, names)
                stmt.thenBranch.forEach { collectReferencedNamesFromStmt(it, names) }
                stmt.elseBranch?.forEach { collectReferencedNamesFromStmt(it, names) }
            }
            is IrStmt.Zone -> stmt.body.forEach { collectReferencedNamesFromStmt(it, names) }
            is IrStmt.Assert -> {
                collectReferencedNamesFromExpr(stmt.condition, names)
                collectReferencedNamesFromExpr(stmt.message, names)
            }
            is IrStmt.Trace -> collectReferencedNamesFromExpr(stmt.message, names)
            is IrStmt.While -> {
                collectReferencedNamesFromExpr(stmt.condition, names)
                stmt.body.forEach { collectReferencedNamesFromStmt(it, names) }
            }
            is IrStmt.For -> {
                collectReferencedNamesFromExpr(stmt.start, names)
                collectReferencedNamesFromExpr(stmt.end, names)
                stmt.body.forEach { collectReferencedNamesFromStmt(it, names) }
            }
            is IrStmt.ForEach -> {
                collectReferencedNamesFromExpr(stmt.iterable, names)
                stmt.body.forEach { collectReferencedNamesFromStmt(it, names) }
            }
            is IrStmt.Loop -> stmt.body.forEach { collectReferencedNamesFromStmt(it, names) }
            is IrStmt.Break -> {}
            is IrStmt.Continue -> {}
            is IrStmt.IndexAssign -> {
                collectReferencedNamesFromExpr(stmt.target, names)
                collectReferencedNamesFromExpr(stmt.index, names)
                collectReferencedNamesFromExpr(stmt.value, names)
            }
            is IrStmt.MemberAssign -> {
                collectReferencedNamesFromExpr(stmt.target, names)
                collectReferencedNamesFromExpr(stmt.value, names)
            }
            is IrStmt.When -> {
                collectReferencedNamesFromExpr(stmt.scrutinee, names)
                for (b in stmt.branches) {
                    b.patterns.forEach { collectReferencedNamesFromExpr(it, names) }
                    b.body.forEach { collectReferencedNamesFromStmt(it, names) }
                }
                stmt.elseBranch?.forEach { collectReferencedNamesFromStmt(it, names) }
            }
            is IrStmt.Throw -> collectReferencedNamesFromExpr(stmt.value, names)
            is IrStmt.Yield -> collectReferencedNamesFromExpr(stmt.value, names)
            is IrStmt.Try -> {
                stmt.body.forEach { collectReferencedNamesFromStmt(it, names) }
                stmt.catchBody?.forEach { collectReferencedNamesFromStmt(it, names) }
            }
            is IrStmt.Defer -> stmt.body.forEach { collectReferencedNamesFromStmt(it, names) }
        }
    }

    private fun collectReferencedNamesFromExpr(expr: IrExpr?, names: MutableSet<String>) {
        if (expr == null) return
        when (expr) {
            is IrExpr.Var -> names.add(expr.name)
            is IrExpr.Call -> {
                names.add(expr.name)
                expr.args.forEach { collectReferencedNamesFromExpr(it, names) }
            }
            is IrExpr.Binary -> {
                collectReferencedNamesFromExpr(expr.left, names)
                collectReferencedNamesFromExpr(expr.right, names)
            }
            is IrExpr.Unary -> collectReferencedNamesFromExpr(expr.operand, names)
            is IrExpr.ArrayLiteral -> expr.elements.forEach { collectReferencedNamesFromExpr(it, names) }
            is IrExpr.Index -> {
                collectReferencedNamesFromExpr(expr.target, names)
                collectReferencedNamesFromExpr(expr.index, names)
            }
            is IrExpr.Member -> collectReferencedNamesFromExpr(expr.target, names)
            is IrExpr.MethodCall -> {
                collectReferencedNamesFromExpr(expr.target, names)
                expr.args.forEach { collectReferencedNamesFromExpr(it, names) }
            }
            else -> {}
        }
    }

    /** Convenience overload for single expression. */
    private fun collectReferencedNamesFromExpr(expr: IrExpr?): Set<String> {
        val names = mutableSetOf<String>()
        collectReferencedNamesFromExpr(expr, names)
        return names
    }

    /** Collects variable names used in expressions (for local unused detection). */
    private fun collectReferencedVarNames(body: List<IrStmt>): Set<String> {
        val names = mutableSetOf<String>()
        for (stmt in body) collectReferencedVarNamesFromStmt(stmt, names)
        return names
    }

    private fun collectReferencedVarNamesFromStmt(stmt: IrStmt, names: MutableSet<String>) {
        when (stmt) {
            is IrStmt.VarDecl -> collectReferencedNamesFromExpr(stmt.initializer, names)
            is IrStmt.FinDecl -> collectReferencedNamesFromExpr(stmt.initializer, names)
            is IrStmt.LetDecl -> collectReferencedNamesFromExpr(stmt.initializer, names)
            is IrStmt.Assignment -> { names.add(stmt.name); collectReferencedNamesFromExpr(stmt.value, names) }
            is IrStmt.Return -> stmt.value?.let { collectReferencedNamesFromExpr(it, names) }
            is IrStmt.ExprStmt -> collectReferencedNamesFromExpr(stmt.expr, names)
            is IrStmt.If -> {
                collectReferencedNamesFromExpr(stmt.condition, names)
                stmt.thenBranch.forEach { collectReferencedVarNamesFromStmt(it, names) }
                stmt.elseBranch?.forEach { collectReferencedVarNamesFromStmt(it, names) }
            }
            is IrStmt.Zone -> stmt.body.forEach { collectReferencedVarNamesFromStmt(it, names) }
            is IrStmt.Assert -> {
                collectReferencedNamesFromExpr(stmt.condition, names)
                collectReferencedNamesFromExpr(stmt.message, names)
            }
            is IrStmt.Trace -> collectReferencedNamesFromExpr(stmt.message, names)
            is IrStmt.While -> {
                collectReferencedNamesFromExpr(stmt.condition, names)
                stmt.body.forEach { collectReferencedVarNamesFromStmt(it, names) }
            }
            is IrStmt.For -> {
                collectReferencedNamesFromExpr(stmt.start, names)
                collectReferencedNamesFromExpr(stmt.end, names)
                stmt.body.forEach { collectReferencedVarNamesFromStmt(it, names) }
            }
            is IrStmt.ForEach -> {
                collectReferencedNamesFromExpr(stmt.iterable, names)
                stmt.body.forEach { collectReferencedVarNamesFromStmt(it, names) }
            }
            is IrStmt.Loop -> stmt.body.forEach { collectReferencedVarNamesFromStmt(it, names) }
            is IrStmt.Break -> {}
            is IrStmt.Continue -> {}
            is IrStmt.IndexAssign -> {
                collectReferencedNamesFromExpr(stmt.target, names)
                collectReferencedNamesFromExpr(stmt.index, names)
                collectReferencedNamesFromExpr(stmt.value, names)
            }
            is IrStmt.MemberAssign -> {
                collectReferencedNamesFromExpr(stmt.target, names)
                collectReferencedNamesFromExpr(stmt.value, names)
            }
            is IrStmt.When -> {
                collectReferencedNamesFromExpr(stmt.scrutinee, names)
                for (b in stmt.branches) {
                    b.patterns.forEach { collectReferencedNamesFromExpr(it, names) }
                    b.body.forEach { collectReferencedVarNamesFromStmt(it, names) }
                }
                stmt.elseBranch?.forEach { collectReferencedVarNamesFromStmt(it, names) }
            }
            is IrStmt.Throw -> collectReferencedNamesFromExpr(stmt.value, names)
            is IrStmt.Yield -> collectReferencedNamesFromExpr(stmt.value, names)
            is IrStmt.Try -> {
                stmt.body.forEach { collectReferencedVarNamesFromStmt(it, names) }
                stmt.catchBody?.forEach { collectReferencedVarNamesFromStmt(it, names) }
            }
            is IrStmt.Defer -> stmt.body.forEach { collectReferencedVarNamesFromStmt(it, names) }
        }
    }
}
