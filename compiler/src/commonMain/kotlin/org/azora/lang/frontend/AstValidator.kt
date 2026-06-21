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

package org.azora.lang.frontend

/**
 * Phase 1, Step 3 -- AST Validation.
 *
 * Catches structural errors that are hard to express in grammar rules:
 *  - Duplicate function names
 *  - Duplicate parameter names within a function
 *  - Non-Unit functions missing return statements
 *  - Empty variable names
 *
 * This pass runs after parsing and before semantic analysis. It operates
 * purely on the AST structure without resolving types or symbols.
 */
class AstValidator {

    /**
     * Validates the given [program] AST and returns a list of error messages.
     *
     * @param program the parsed AST to validate
     * @return a list of human-readable error strings (empty if the AST is valid)
     */
    fun validate(program: Program): List<String> {
        val errors = mutableListOf<String>()

        // Top-level runtime var/let are not thread-safe — only fin allowed
        for (item in program.items) {
            when (item) {
                is TopLevel.VarDecl -> errors.add("line ${item.line}: top-level 'var' is not allowed (not thread-safe). Use 'fin' for immutable globals or 'inline var' for compile-time variables")
                is TopLevel.LetDecl -> errors.add("line ${item.line}: top-level 'let' is not allowed (not thread-safe). Use 'fin' for immutable globals or 'inline let' for compile-time variables")
                else -> {}
            }
        }

        // Validate test declarations
        val testNames = mutableSetOf<String>()
        for (item in program.items) {
            if (item is TopLevel.Test) {
                if (!testNames.add(item.name)) {
                    errors.add("line ${item.line}: duplicate test '${item.name}'")
                }
                for (stmt in item.body) {
                    validateStmt(stmt, "test \"${item.name}\"", errors)
                }
            }
        }

        // Check for duplicate function names
        val seen = mutableSetOf<String>()
        for (func in program.functions) {
            if (!seen.add(func.name)) {
                errors.add("line ${func.line}: duplicate function '${func.name}'")
            }
        }

        for (func in program.functions) {
            validateFunction(func, errors)
        }

        return errors
    }

    private fun validateFunction(func: FuncDecl, errors: MutableList<String>) {
        // Duplicate parameter names
        val paramNames = mutableSetOf<String>()
        for (param in func.params) {
            if (!paramNames.add(param.name)) {
                errors.add("line ${func.line}: duplicate parameter '${param.name}' in function '${func.name}'")
            }
        }

        // Non-Unit functions must have at least one return on every path
        // (simplified: check that at least one return exists in the body tree)
        if (func.returnType is TypeAnnotation.Explicit && func.returnType.name != "Unit") {
            if (!hasReturnInBody(func.body)) {
                errors.add("line ${func.line}: function '${func.name}' declares return type " +
                        "'${func.returnType}' but has no return statement")
            }
        }

        // Validate statements
        for (stmt in func.body) {
            validateStmt(stmt, func.name, errors)
        }
    }

    private fun validateStmt(stmt: Stmt, funcName: String, errors: MutableList<String>) {
        when (stmt) {
            is Stmt.VarDecl -> {
                if (stmt.name.isEmpty()) {
                    errors.add("line ${stmt.line}: empty variable name in function '$funcName'")
                }
            }
            is Stmt.FinDecl -> {
                if (stmt.name.isEmpty()) {
                    errors.add("line ${stmt.line}: empty variable name in function '$funcName'")
                }
            }
            is Stmt.Assignment -> {
                if (stmt.name.isEmpty()) {
                    errors.add("line ${stmt.line}: empty variable name in assignment")
                }
            }
            is Stmt.Return -> {}
            is Stmt.ExprStmt -> {}
            is Stmt.If -> {
                stmt.thenBranch.forEach { validateStmt(it, funcName, errors) }
                stmt.elseBranch?.forEach { validateStmt(it, funcName, errors) }
            }
            is Stmt.InlineIf -> {
                stmt.thenBranch.forEach { validateStmt(it, funcName, errors) }
                stmt.elseBranch?.forEach { validateStmt(it, funcName, errors) }
            }
            is Stmt.DeepInlineIf -> {
                stmt.thenBranch.forEach { validateStmt(it, funcName, errors) }
                stmt.elseBranch?.forEach { validateStmt(it, funcName, errors) }
            }
            is Stmt.DeepInlineBlock -> stmt.body.forEach { validateStmt(it, funcName, errors) }
            is Stmt.NoInline -> validateStmt(stmt.stmt, funcName, errors)
            is Stmt.InlineBlock -> stmt.body.forEach { validateStmt(it, funcName, errors) }
            is Stmt.InlineFin -> {}
            is Stmt.InlineLet -> {}
            is Stmt.InlineVar -> {}
            is Stmt.InlineAssignment -> {}
            is Stmt.LetDecl -> {}
            is Stmt.Zone -> stmt.body.forEach { validateStmt(it, funcName, errors) }
            is Stmt.FriendZone -> stmt.body.forEach { validateStmt(it, funcName, errors) }
            is Stmt.Assert -> {}
            is Stmt.Trace -> {}
            is Stmt.InlineAssert -> {}
            is Stmt.InlineTrace -> {}
            is Stmt.While -> stmt.body.forEach { validateStmt(it, funcName, errors) }
            is Stmt.For -> stmt.body.forEach { validateStmt(it, funcName, errors) }
            is Stmt.Loop -> stmt.body.forEach { validateStmt(it, funcName, errors) }
            is Stmt.Break -> {}
            is Stmt.Continue -> {}
            is Stmt.IndexAssign -> {}
            is Stmt.MemberAssign -> {}
            is Stmt.When -> {
                for (branch in stmt.branches) {
                    branch.body.forEach { validateStmt(it, funcName, errors) }
                }
                stmt.elseBranch?.forEach { validateStmt(it, funcName, errors) }
            }
            is Stmt.Throw -> validateStmt(Stmt.ExprStmt(stmt.value, stmt.line, stmt.column), funcName, errors)
            is Stmt.Try -> {
                stmt.body.forEach { validateStmt(it, funcName, errors) }
                stmt.catchBody?.forEach { validateStmt(it, funcName, errors) }
            }
            is Stmt.Defer -> stmt.body.forEach { validateStmt(it, funcName, errors) }
        }
    }

    private fun hasReturnInBody(body: List<Stmt>): Boolean = body.any { stmt ->
        when (stmt) {
            is Stmt.Return -> true
            is Stmt.If -> hasReturnInBody(stmt.thenBranch) ||
                    (stmt.elseBranch != null && hasReturnInBody(stmt.elseBranch))
            is Stmt.InlineIf -> hasReturnInBody(stmt.thenBranch) ||
                    (stmt.elseBranch != null && hasReturnInBody(stmt.elseBranch))
            is Stmt.DeepInlineIf -> hasReturnInBody(stmt.thenBranch) ||
                    (stmt.elseBranch != null && hasReturnInBody(stmt.elseBranch))
            is Stmt.Zone -> hasReturnInBody(stmt.body)
            is Stmt.FriendZone -> hasReturnInBody(stmt.body)
            is Stmt.While -> hasReturnInBody(stmt.body)
            is Stmt.For -> hasReturnInBody(stmt.body)
            is Stmt.Loop -> hasReturnInBody(stmt.body)
            is Stmt.When -> stmt.branches.any { hasReturnInBody(it.body) } ||
                    (stmt.elseBranch != null && hasReturnInBody(stmt.elseBranch))
            else -> false
        }
    }
}
