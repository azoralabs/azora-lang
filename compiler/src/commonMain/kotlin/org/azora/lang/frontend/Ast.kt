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

// ---------------------------------------------------------------------------
// Expressions
// ---------------------------------------------------------------------------

/**
 * Base class for all AST expression nodes.
 *
 * Every expression carries source-location metadata ([line], [column], [length])
 * so that later compiler phases can produce precise diagnostics.
 */
sealed class Expr {
    /** 1-based line number where this expression starts. */
    abstract val line: Int
    /** 1-based column number where this expression starts. */
    abstract val column: Int
    /** Length of the source text that produced this expression. */
    abstract val length: Int

    /**
     * Integer literal expression (e.g. `42`, `42L`, `42s`).
     *
     * @property value the parsed integer value
     * @property suffix the numeric type suffix (e.g. [NumericSuffix.LONG] for `42L`)
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class IntLiteral(val value: Long, override val line: Int, override val column: Int = 0, override val length: Int = 0, val suffix: NumericSuffix = NumericSuffix.NONE) : Expr()

    /**
     * Floating-point literal expression (e.g. `3.14`, `3.14f`, `3.14D`).
     *
     * @property value the parsed double-precision value
     * @property suffix the numeric type suffix (e.g. [NumericSuffix.FLOAT] for `3.14f`)
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class RealLiteral(val value: Double, override val line: Int, override val column: Int = 0, override val length: Int = 0, val suffix: NumericSuffix = NumericSuffix.NONE) : Expr()

    /**
     * Character literal expression (e.g. `'a'`, `'\n'`).
     *
     * @property value the character value
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class CharLiteral(val value: Char, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * String literal expression (e.g. `"hello"`).
     *
     * @property value the unescaped string content
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class StringLiteral(val value: String, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Boolean literal expression (`true` or `false`).
     *
     * @property value the boolean value
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class BoolLiteral(val value: Boolean, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Variable or function name reference.
     *
     * @property name the identifier text
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Identifier(val name: String, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Binary operator expression (e.g. `a + b`, `x == y`).
     *
     * @property left the left-hand operand
     * @property op the operator token type
     * @property right the right-hand operand
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Binary(val left: Expr, val op: TokenType, val right: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Unary operator expression (e.g. `-x`, `!flag`).
     *
     * @property op the operator token type ([TokenType.MINUS] or [TokenType.BANG])
     * @property operand the operand expression
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Unary(val op: TokenType, val operand: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Function call expression (e.g. `add(1, 2)`).
     *
     * @property callee the name of the function being called
     * @property args the list of argument expressions
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Call(val callee: String, val args: List<Expr>, override val line: Int, override val column: Int = 0, override val length: Int = 0, val typeArgs: List<TypeRef> = emptyList()) : Expr()

    /**
     * Parenthesized expression (e.g. `(a + b)`).
     *
     * @property expr the inner expression
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Grouping(val expr: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Upper scope access (`::name`). Resolves the variable in the parent scope,
     * skipping the current scope. Used when a local variable shadows an outer one.
     *
     * @property name the variable name to look up in the upper scope
     */
    /**
     * Upper scope access (`::name`, `::::name`, etc.).
     * Each `::` skips one scope level.
     *
     * @property name the variable name
     * @property depth how many scopes to skip (1 for `::`, 2 for `::::`, etc.)
     */
    data class UpperScopeAccess(val name: String, val depth: Int = 1, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Integer range expression `a..b` (inclusive) or `a..<b` (exclusive).
     *
     * Currently ranges are used as the iterable of a `for` loop.
     *
     * @property from the start bound expression
     * @property to the end bound expression
     * @property inclusive whether the end is included (`..` vs `..<`)
     */
    data class Range(val from: Expr, val to: Expr, val inclusive: Boolean, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Array literal `[a, b, c]` (or empty `[]`).
     *
     * @property elements the element expressions
     */
    data class ArrayLiteral(val elements: List<Expr>, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** Set literal `![a, b, c]`. */
    data class SetLiteral(val elements: List<Expr>, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Index access `target[index]`.
     *
     * @property target the indexed expression (an array)
     * @property index the index expression
     */
    data class Index(val target: Expr, val index: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Member access `target.name`.
     *
     * @property target the receiver expression
     * @property name the member name
     */
    data class Member(val target: Expr, val name: String, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Method call `target.name(args)`.
     *
     * @property target the receiver expression
     * @property name the method name
     * @property args the argument expressions
     */
    data class MethodCall(val target: Expr, val name: String, val args: List<Expr>, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * One segment of a string-interpolation template.
     */
    sealed class StringTemplatePart {
        /** A literal text chunk. */
        data class Literal(val text: String) : StringTemplatePart()
        /** An embedded expression. */
        data class Expr(val expr: org.azora.lang.frontend.Expr) : StringTemplatePart()
    }

    /**
     * Interpolated string `"hello $name, count: ${n + 1}"`.
     *
     * @property parts the ordered literal/expr segments
     */
    data class StringTemplate(val parts: List<StringTemplatePart>, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** Tuple literal `(a, b, c)` (two or more elements). */
    data class TupleLit(val elements: List<Expr>, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** Variant literal `var(a, b, c)` — constructs a `Var<...>` holding exactly one of the given
     *  candidate values (the first, by default). At least two candidates are required. */
    data class VariantLit(val elements: List<Expr>, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** Tuple positional access `target.index` (e.g. `pair.0`). */
    data class TupleAccess(val target: Expr, val index: Int, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `expr catch fallback` — evaluates [expr]; if it throws, evaluates [fallback]. */
    data class CatchExpr(val expr: Expr, val fallback: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * If-expression `if cond { a } else { b }` — both branches are single
     * expressions and one of them becomes the value of the whole expression.
     */
    data class IfExpr(val condition: Expr, val thenExpr: Expr, val elseExpr: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * Lambda `{ params -> body }`. Parameters carry explicit types. The result type is a
     * function type inferred from the body's return value.
     */
    data class Lambda(val params: List<Param>, val body: List<Stmt>, override val line: Int, override val column: Int = 0, override val length: Int = 0, val variadic: Boolean = false) : Expr()

    /** A named argument `name: value` in a call expression. */
    data class NamedArg(val name: String, val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `null` literal. */
    object NullLiteral : Expr() {
        override val line get() = 0
        override val column get() = 0
        override val length get() = 0
    }

    /** `a ?? b` — returns `a` if non-null, else `b`. */
    data class NullCoalesce(val left: Expr, val right: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `a?.field` — safe member access; returns null if `a` is null. */
    data class SafeMember(val target: Expr, val name: String, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `expr as Type` — type cast. */
    data class Cast(val expr: Expr, val targetType: TypeRef, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `expr is Type` — runtime type check, returns Bool. */
    data class IsCheck(val expr: Expr, val typeName: String, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** Map literal `["k": v, "k2": v2]`. */
    data class MapLit(val entries: List<Pair<Expr, Expr>>, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `alloc <expr>` — heap-allocate a value and return a pointer to it. */
    data class Alloc(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `alloc T(count)` — allocate a buffer of `count` elements of type T (C++-style), returning `T*`. */
    data class AllocBuffer(val typeName: String, val count: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `*ptr` — dereference a pointer. */
    data class Deref(val target: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `isolated(expr)` — produce an independent deep copy of [value]. */
    data class Isolated(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `await task` — suspend until the task completes and yield its result. */
    data class Await(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `inject Type` — resolve the singleton instance of [typeName] from the DI container. */
    data class Inject(val typeName: String, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `arr...` — spread an array's elements as individual call arguments. */
    data class Spread(val array: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()
}

// ---------------------------------------------------------------------------
// Statements
// ---------------------------------------------------------------------------

/**
 * Base class for all AST statement nodes.
 *
 * Statements represent actions that execute in sequence within a function body.
 * Every statement carries source-location metadata for diagnostics.
 */
sealed class Stmt {
    /** 1-based line number where this statement starts. */
    abstract val line: Int
    /** 1-based column number where this statement starts. */
    abstract val column: Int
    /** Length of the source text that produced this statement. */
    abstract val length: Int

    /**
     * Mutable binding (`var`).
     *
     * @property name the variable name
     * @property type the declared or inferred type annotation
     * @property initializer the expression that provides the initial value
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class VarDecl(
        val name: String,
        val type: TypeAnnotation,
        val initializer: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Deeply immutable binding (`fin`).
     *
     * @property name the variable name
     * @property type the declared or inferred type annotation
     * @property initializer the expression that provides the initial value
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class FinDecl(
        val name: String,
        val type: TypeAnnotation,
        val initializer: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Immutable binding (`let`).
     *
     * @property name the variable name
     * @property type the declared or inferred type annotation
     * @property initializer the expression that provides the initial value
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class LetDecl(
        val name: String,
        val type: TypeAnnotation,
        val initializer: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Compile-time constant binding (`inline fin`).
     *
     * Evaluated during CTFE. The initializer must be a compile-time constant.
     * All references to the name are replaced with the computed value --
     * the binding itself is removed from the final AST.
     *
     * @property name the binding name
     * @property type the declared or inferred type annotation
     * @property initializer the compile-time constant expression
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class InlineFin(
        val name: String,
        val type: TypeAnnotation,
        val initializer: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Deep compile-time block (`deepinline { ... }`).
     *
     * Like `inline { }` but recursive -- nested `if`, `var`, etc. are
     * also compile-time. Use `noinline` to escape back to runtime.
     *
     * @property body the list of statements evaluated at compile time
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class DeepInlineBlock(
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Cancels inline context (`noinline stmt`).
     *
     * Inside a `deepinline { }` or `inline { }` block, marks a statement
     * as runtime -- it will not be evaluated at compile time.
     *
     * @property stmt the wrapped runtime statement
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class NoInline(
        val stmt: Stmt,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Compile-time block (`inline { ... }`).
     *
     * All statements inside are implicitly compile-time:
     * `var` becomes `inline var`, `fin` becomes `inline fin`, `let` becomes `inline let`,
     * `if` becomes `inline if`, assignment becomes `inline assignment`.
     * Runtime statements (e.g. `println(...)`) survive into the final AST.
     *
     * @property body the list of statements inside the inline block
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class InlineBlock(
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Compile-time immutable binding (`inline let`).
     *
     * @property name the binding name
     * @property type the declared or inferred type annotation
     * @property initializer the compile-time constant expression
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class InlineLet(
        val name: String,
        val type: TypeAnnotation,
        val initializer: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Compile-time mutable binding (`inline var`).
     *
     * @property name the binding name
     * @property type the declared or inferred type annotation
     * @property initializer the compile-time constant expression
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class InlineVar(
        val name: String,
        val type: TypeAnnotation,
        val initializer: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Compile-time reassignment (`inline x = expr`).
     *
     * @property name the name of the compile-time variable being reassigned
     * @property value the new value expression
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class InlineAssignment(
        val name: String,
        val value: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Runtime variable reassignment (e.g. `x = 42`).
     *
     * @property name the name of the variable being reassigned
     * @property value the new value expression
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Assignment(
        val name: String,
        val value: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Return statement, optionally carrying a value.
     *
     * @property value the return value expression, or `null` for `Unit`-returning functions
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Return(
        val value: Expr?,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Expression used as a statement (e.g. a function call like `println("hi")`).
     *
     * @property expr the expression being evaluated for its side effects
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class ExprStmt(
        val expr: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Scoped block (`zone { ... }`). Introduces a new variable scope.
     *
     * @property body the list of statements inside the zone
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Zone(
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        /** `zone alloc { }` — allocations inside are tracked and freed at exit. */
        val alloc: Boolean = false,
        /** Explicit opt-in boundary for operations whose contracts cannot be proven safe. */
        val unsafe: Boolean = false
    ) : Stmt()

    /**
     * Friend zone block (`friend zone { ... }`).
     *
     * Multiple friend zone blocks in the same parent scope share a persistent
     * variable scope. Variables declared in one friend zone are visible in
     * other friend zones, but not in the regular code between them.
     *
     * @property body the statements inside the friend zone
     */
    data class FriendZone(
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        /** `friend zone alloc { }` — shared-scope + arena. */
        val alloc: Boolean = false
    ) : Stmt()

    /**
     * Runtime if/else statement.
     *
     * @property condition the boolean condition expression
     * @property thenBranch the statements to execute when the condition is true
     * @property elseBranch the statements to execute when the condition is false, or `null` if no else branch
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class If(
        val condition: Expr,
        val thenBranch: List<Stmt>,
        val elseBranch: List<Stmt>?,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Compile-time conditional.
     *
     * Evaluated during CTFE. The condition must be a compile-time constant.
     * Only the taken branch survives into the final AST -- the other branch
     * is completely removed (not even type-checked).
     *
     * @property condition the compile-time boolean condition
     * @property thenBranch the statements to emit when the condition is true
     * @property elseBranch the statements to emit when the condition is false, or `null`
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class InlineIf(
        val condition: Expr,
        val thenBranch: List<Stmt>,
        val elseBranch: List<Stmt>?,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Deep compile-time conditional (`deepinline if`).
     *
     * Like `inline if` but the taken branch is recursively deep-inlined:
     * all `var`/`fin`/`let`/`if`/assignment inside become compile-time.
     * Use `noinline` to escape back to runtime.
     *
     * @property condition the compile-time boolean condition
     * @property thenBranch the statements to deep-inline when the condition is true
     * @property elseBranch the statements to deep-inline when the condition is false, or `null`
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class DeepInlineIf(
        val condition: Expr,
        val thenBranch: List<Stmt>,
        val elseBranch: List<Stmt>?,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Runtime assertion (`assert condition { "message" }`).
     *
     * Evaluates condition at runtime; if false, prints error message and aborts.
     * NOT allowed at global scope.
     *
     * @property condition the boolean condition expression
     * @property message the error message expression (must be String)
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Assert(
        val condition: Expr,
        val message: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Runtime trace (`trace { expr }`).
     *
     * Prints `[TRACE] message` at runtime.
     * NOT allowed at global scope.
     *
     * @property message the message expression (must be String)
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Trace(
        val message: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Compile-time assertion (`inline assert condition { "message" }`).
     *
     * Evaluated during CTFE. If condition is false, produces a compilation error.
     * Allowed in all scopes including global.
     *
     * @property condition the compile-time boolean condition
     * @property message the error message expression (must be String)
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class InlineAssert(
        val condition: Expr,
        val message: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Compile-time trace (`inline trace { expr }`).
     *
     * Evaluated during CTFE, message stored as a compiler warning.
     * Allowed in all scopes including global.
     *
     * @property message the message expression (must be String)
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class InlineTrace(
        val message: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * `while` loop. Repeatedly executes [body] while [condition] is true.
     *
     * @property condition the boolean loop condition
     * @property body the statements to execute each iteration
     */
    data class While(
        val condition: Expr,
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        /** Optional `@label` for labeled `break`/`continue`. */
        val label: String? = null
    ) : Stmt()

    /**
     * `for name in iterable { body }` loop.
     *
     * Currently [iterable] must be an [Expr.Range]; the loop variable [name]
     * takes each integer value in the range.
     *
     * @property name the loop variable name
     * @property iterable the iterable expression (a range)
     * @property body the statements to execute each iteration
     */
    data class For(
        val name: String,
        val iterable: Expr,
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        /** Optional step for integer-range loops: `for x by N in a..b`. Null means step 1. */
        val step: Expr? = null,
        /** Iterate the range downwards: `reverse for x in a..b`. */
        val reverse: Boolean = false,
        /** Optional `@label` for labeled `break`/`continue`. */
        val label: String? = null
    ) : Stmt()

    /**
     * Infinite `loop { body }`. Exits via `break`.
     *
     * @property body the statements to execute repeatedly
     */
    data class Loop(
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        /** Optional `@label` for labeled `break`/`continue`. */
        val label: String? = null,
        /** Optional iterable for `loop iterable { }` — when present, desugars to
         *  `iterable.reset(); while iterable.hasNext() { body }`. */
        val iterable: Expr? = null
    ) : Stmt()

    /**
     * Compile-time unrolled loop (`inline for x in a..b { body }`).
     *
     * The range bounds must be compile-time integer constants. The [CtfeEvaluator]
     * substitutes [name] with each value and splices the (folded) body into the
     * enclosing scope, so this node never survives into semantic analysis/IR.
     *
     * @property name the loop variable, bound to each integer value during unrolling
     * @property iterable the range expression (`a..b` / `a..<b`)
     * @property body the statements unrolled once per value
     */
    data class InlineFor(
        val name: String,
        val iterable: Expr,
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * `break` statement. Exits the enclosing loop. With a label (`break @lbl`)
     * it exits the loop tagged with that label, skipping any inner loops.
     *
     * @property label the target label, or `null` for the innermost loop
     */
    data class Break(val label: String? = null, override val line: Int = 0, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /**
     * `continue` statement. Skips to the next iteration of the enclosing loop.
     * With a label (`continue @lbl`) it targets the loop tagged with that label.
     *
     * @property label the target label, or `null` for the innermost loop
     */
    data class Continue(val label: String? = null, override val line: Int = 0, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /**
     * Index assignment `target[index] = value`.
     *
     * @property target the indexed expression (an array)
     * @property index the index expression
     * @property value the new value expression
     */
    data class IndexAssign(
        val target: Expr,
        val index: Expr,
        val value: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * Member assignment `target.name = value`.
     *
     * @property target the receiver expression
     * @property name the member name
     * @property value the new value expression
     */
    data class MemberAssign(
        val target: Expr,
        val name: String,
        val value: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /**
     * One branch of a `when` expression: any of [patterns] matches → run [body].
     */
    data class WhenBranch(val patterns: List<Expr>, val body: List<Stmt>, val line: Int, val column: Int = 0)

    /**
     * `when scrutinee { patterns -> body ... else -> body }`.
     *
     * @property scrutinee the matched expression
     * @property branches the pattern branches
     * @property elseBranch the fallback branch, or `null`
     */
    data class When(
        val scrutinee: Expr,
        val branches: List<WhenBranch>,
        val elseBranch: List<Stmt>?,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0
    ) : Stmt()

    /** `throw value` — raises [value] as a throwable. */
    data class Throw(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /**
     * `panic "msg"` — unrecoverable runtime abort with [message].
     * `inline panic "msg"` ([inlinePanic]) — if reached during compile-time evaluation,
     * aborts the compiler with [message].
     */
    data class Panic(val message: Expr, val inlinePanic: Boolean, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /** `*ptr = value` — store through a pointer. */
    data class DerefAssign(val target: Expr, val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /** `yield value` — emit a value from a `flow` generator. */
    data class Yield(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /**
     * `try { body } catch { name -> handler }`.
     *
     * @property body the protected statements
     * @property catchName the binding name for the caught value, or `null` if none
     * @property catchBody the handler statements, or `null` if the try has no catch
     */
    data class Try(val body: List<Stmt>, val catchName: String?, val catchBody: List<Stmt>?, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /** `defer { body }` — runs [body] when the enclosing function exits. */
    data class Defer(val body: List<Stmt>, override val line: Int, override val column: Int = 0, override val length: Int = 0, val onFail: Boolean = false, val suppress: Boolean = false) : Stmt()

    /** `mem`/`rem`/`ret x: T = init` — reactive state declaration. */
    data class RemDecl(
        val name: String,
        val type: TypeAnnotation,
        val initializer: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        val kind: ReactiveKind = ReactiveKind.REM,
    ) : Stmt()

    /** `effect { body }` — reactive side-effect; re-runs when tracked `rem` variables change. */
    data class Effect(val body: List<Stmt>, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Stmt()
}

// ---------------------------------------------------------------------------
// Type annotations
// ---------------------------------------------------------------------------

/**
 * A structured reference to a type as written in source code.
 *
 * This is the AST-level type representation produced by the parser. The
 * semantic layer resolves a [TypeRef] into a concrete [org.azora.lang.ir.IrType].
 *
 * Variants:
 * - [Named] -- a simple or generic type name: `Int`, `String`, `List<Int>`
 * - [Array] -- internal fixed-array representation for `[T]` and literals
 * - [Map] -- structural map literal type (kept for backends)
 * - [Set] -- structural set literal type (kept for backends)
 * - [Function] -- `(A, B) -> R`
 * - [Tuple] -- `(A, B)` (two or more elements)
 * - [Nullable] -- `T?`
 */
sealed class TypeRef {
    enum class RefKind(val spelling: String) {
        BORROWED("ref"),
        MUTABLE("mut ref"),
        SHARED("shared ref"),
        WEAK("weak ref")
    }

    /** A named type, optionally generic: `Int`, `List<Int>`. */
    data class Named(val name: String, val args: List<TypeRef> = emptyList(), val variadic: Boolean = false) : TypeRef() {
        override fun toString() = if (args.isEmpty()) name else "$name<${args.joinToString(", ")}>"
    }

    /** Fixed array type `[T]`. */
    data class Array(val element: TypeRef) : TypeRef() {
        override fun toString() = "[$element]"
    }

    /** Structural map type `map[K, V]`. */
    data class Map(val key: TypeRef, val value: TypeRef) : TypeRef() {
        override fun toString() = "map[$key, $value]"
    }

    /** Structural set type `set[T]`. */
    data class Set(val element: TypeRef) : TypeRef() {
        override fun toString() = "set[$element]"
    }

    /** Function type `(A, B) -> R`. */
    data class Function(val params: List<TypeRef>, val ret: TypeRef) : TypeRef() {
        override fun toString() = "(${params.joinToString(", ")}) -> $ret"
    }

    /** Tuple type `(A, B)` (two or more elements). */
    data class Tuple(val elements: List<TypeRef>) : TypeRef() {
        override fun toString() = "(${elements.joinToString(", ")})"
    }

    /** Nullable type `T?`. */
    data class Nullable(val inner: TypeRef) : TypeRef() {
        override fun toString() = "$inner?"
    }

    /** Failable type `T!ErrSet` — a value of [ok] or an error from set [errSet]. */
    data class Failable(val ok: TypeRef, val errSet: String) : TypeRef() {
        override fun toString() = "$ok!$errSet"
    }

    /** Pointer type `T*` — a reference to a heap value of [inner]. */
    data class Pointer(val inner: TypeRef) : TypeRef() {
        override fun toString() = "$inner*"
    }

    /** A checked reference. Ownership is carried by the qualifier, not punctuation. */
    data class Reference(val kind: RefKind, val inner: TypeRef) : TypeRef() {
        override fun toString() = "${kind.spelling} $inner"
    }

    /** Human-readable name for diagnostics (the simple name for [Named]). */
    fun displayName(): String = when (this) {
        is Named -> name
        else -> toString()
    }
}

/**
 * Represents a type annotation on a variable or return type.
 *
 * Azora supports both explicit type annotations (e.g. `var x: Int = 0`) and
 * type inference (e.g. `var x = 0`), represented by the two variants.
 */
sealed class TypeAnnotation {
    /**
     * An explicit, user-specified type annotation (e.g. `: Int`, `: arr[Int]`).
     *
     * @property ref the structured type reference as parsed from source
     */
    data class Explicit(val ref: TypeRef) : TypeAnnotation() {
        /** Convenience: the display name of the referenced type. */
        val name: String get() = ref.displayName()
    }

    /**
     * A type that should be inferred by the compiler from context (no annotation present).
     */
    object Inferred : TypeAnnotation() {
        override fun toString() = "inferred"
    }
}

// ---------------------------------------------------------------------------
// Declarations
// ---------------------------------------------------------------------------

/**
 * A function parameter declaration.
 *
 * @property name the parameter name
 * @property type the structured type reference as written in source
 */
enum class Visibility { EXPOSE, PROTECT, CONFINE, SHIELD }

enum class ReactiveKind { MEM, REM, RET }

/** Parameter modifier: `""` (default), `"ref"` (by-reference), `"out"` (output), `"mut"` (mutable). */
typealias ParamModifier = String

data class Param(
    val name: String,
    val type: TypeRef,
    val defaultValue: Expr? = null,
    val modifier: ParamModifier = "",
    /** True when declared with the `...T` variadic syntax (call sites pack extra args). */
    val variadic: Boolean = false,
    /** Parameter-level decorators, parsed from `name: @Decorator Type`. */
    val annotations: List<Annotation> = emptyList(),
) {
    /** Convenience: the type name as written in source (for diagnostics/dumping). */
    val typeName: String get() = type.displayName()
}

/**
 * A field of a `pack` (struct) declaration.
 *
 * @property name the field name
 * @property type the field's type reference
 * @property mutable whether the field is `var` (mutable) vs `fin`/`let` (immutable)
 * @property default an optional default-value expression
 */
data class PackField(
    val name: String,
    val type: TypeRef,
    val mutable: Boolean,
    val default: Expr?,
    val visibility: Visibility = Visibility.EXPOSE,
)

/**
 * Field generator for a variadic pack body, parsed from
 * `inline for <loopVar> in ...<packVar> with index { <fields and/or mixins> }`.
 *
 * At monomorphization, the template is expanded once per concrete type in the
 * variadic pack: `<loopVar>` binds to each element type, and `$index` (in a
 * structured field name or a [Expr.StringTemplate] mixin) becomes the literal
 * positional index (`0`, `1`, …).
 *
 * @property loopVar the per-iteration type binding (e.g. `Ty`)
 * @property packVar the variadic type param being iterated (e.g. `T`)
 * @property fields structured generated fields; `name` may be `$index` (positional)
 * @property mixins string-template mixins (`mixin "$index: $Ty"`) interpolated with
 *   the loop bindings and parsed as a field declaration at expansion time
 */
data class VariadicFieldTemplate(
    val loopVar: String,
    val packVar: String,
    val fields: List<TplField>,
    val mixins: List<Expr.StringTemplate> = emptyList(),
)

/** A single field in a [VariadicFieldTemplate]. `type` may reference [VariadicFieldTemplate.loopVar]. */
data class TplField(val name: String, val type: TypeRef)

/**
 * A function declaration in the AST.
 *
 * Represents a complete function including its signature and body. Functions
 * may be marked as `inline` to be substituted at call sites by the CTFE evaluator.
 *
 * @property name the function name
 * @property params the list of parameter declarations
 * @property returnType the return type annotation (explicit or inferred)
 * @property body the list of statements forming the function body
 * @property isInline whether this function is marked `inline` for compile-time substitution
 * @property line 1-based source line where the function declaration starts
 * @property column 1-based source column where the function declaration starts
 * @property length source text length of the function declaration
 */
data class FuncDecl(
    val name: String,
    val params: List<Param>,
    val returnType: TypeAnnotation,
    val body: List<Stmt>,
    val isInline: Boolean = false,
    val typeParams: List<String> = emptyList(),
    val line: Int,
    val column: Int = 0,
    val length: Int = 0,
    /** Decorator/annotation applications (`@Name` / `@Name(args)`). Not yet enforced. */
    val annotations: List<Annotation> = emptyList(),
    /** `flow` generator: calling it returns a (eagerly-built) list of `yield`ed values. */
    val isFlow: Boolean = false,
    /** `repl func` — overrides a parent node's method. */
    val isOverride: Boolean = false,
    /** `virt func` — virtual method (dynamic dispatch). */
    val isVirtual: Boolean = false,
    /** `task name(...)` — a structured asynchronous function. */
    val isTask: Boolean = false,
    /** Declaration requires an explicit unsafe calling context. */
    val isUnsafe: Boolean = false,
    /** Visibility exported to import/member access rules. */
    val visibility: Visibility = Visibility.EXPOSE,
    /** Receiver mutability for impl/extension methods: `ref self` or `mut ref self`. */
    val receiverModifier: ParamModifier = "mut ref",
    /** Name of the variadic type param (`T` in `func<T...>`), or null for a fixed function. */
    val variadicParam: String? = null,
    /** Minimum element count from a `where <var>.length >= N` clause, or null if unconstrained. */
    val minVariadicLength: Int? = null,
    /** How this declaration may be invoked when registered as an impl member. */
    val memberCallStyle: MemberCallStyle = MemberCallStyle.NORMAL,
)

enum class MemberCallStyle {
    NORMAL,
    PROPERTY,
    METHOD,
}

/**
 * A decorator/annotation application: `@Name`, `@Name(args)`, or `@target:Name`.
 *
 * @property name the decorator name
 * @property args optional arguments (`@Name(a, b)`)
 * @property target optional use-site target (`@file:Name`, `@field:Name`)
 * @property line 1-based source line
 * @property column 1-based source column
 */
data class Annotation(
    val name: String,
    val args: List<Expr> = emptyList(),
    val target: String? = null,
    val line: Int = 0,
    val column: Int = 0,
    /** Named arguments `@name(key = value)` / `@name(key: value)`, in source order. */
    val namedArgs: List<Pair<String, Expr>> = emptyList(),
)

/**
 * Compact callback form for specs such as
 * `spec Into<T>: T { ref self } use as "to${T.typeName}"`.
 *
 * The spec has no body; implementations provide one callback body directly in
 * `impl Into<String> for X { ref self -> ... }`. [requiresParens] is true only
 * when the spec itself declares a parameter list (`spec Into<T>(): T ...`).
 */
data class SpecCallback(
    val returnType: TypeRef,
    val requiresParens: Boolean,
    val params: List<Param> = emptyList(),
    val receiverModifier: ParamModifier,
    val receiverName: String,
    val useAsTemplate: String? = null,
    val typeParams: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Top-level items (can be functions or compile-time constructs)
// ---------------------------------------------------------------------------

/**
 * Base class for top-level items in a program.
 *
 * Top-level items can be function declarations or compile-time constructs
 * (inline variables, inline conditionals, inline blocks) that are resolved
 * by the CTFE evaluator before semantic analysis.
 */
sealed class TopLevel {
    /**
     * A top-level function declaration.
     *
     * @property decl the full function declaration
     */
    data class Func(val decl: FuncDecl) : TopLevel()

    /** Runtime top-level mutable binding (`var`). Survives CTFE. */
    data class VarDecl(val name: String, val type: TypeRef?, val initializer: Expr, val line: Int, val column: Int = 0, val annotations: List<Annotation> = emptyList(), val threadlocal: Boolean = false, val visibility: Visibility = Visibility.EXPOSE) : TopLevel() {
        /** Convenience: the type name as written in source, or null. */
        val typeName: String? get() = type?.displayName()
    }
    /** Runtime top-level deeply immutable binding (`fin`). Survives CTFE. */
    data class FinDecl(val name: String, val type: TypeRef?, val initializer: Expr, val line: Int, val column: Int = 0, val annotations: List<Annotation> = emptyList(), val threadlocal: Boolean = false, val visibility: Visibility = Visibility.EXPOSE) : TopLevel() {
        /** Convenience: the type name as written in source, or null. */
        val typeName: String? get() = type?.displayName()
    }
    /** Runtime top-level immutable binding (`let`). Survives CTFE. */
    data class LetDecl(val name: String, val type: TypeRef?, val initializer: Expr, val line: Int, val column: Int = 0, val annotations: List<Annotation> = emptyList(), val visibility: Visibility = Visibility.EXPOSE) : TopLevel() {
        /** Convenience: the type name as written in source, or null. */
        val typeName: String? get() = type?.displayName()
    }

    /**
     * A top-level compile-time mutable binding (`inline var`).
     *
     * @property name the binding name
     * @property initializer the compile-time constant expression
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class InlineVar(val name: String, val initializer: Expr, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A top-level compile-time deeply immutable binding (`inline fin`).
     *
     * @property name the binding name
     * @property initializer the compile-time constant expression
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class InlineFin(val name: String, val initializer: Expr, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A top-level compile-time immutable binding (`inline let`).
     *
     * @property name the binding name
     * @property initializer the compile-time constant expression
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class InlineLet(val name: String, val initializer: Expr, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A top-level compile-time reassignment (`inline x = expr`).
     *
     * @property name the name of the compile-time variable being reassigned
     * @property value the new value expression
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class InlineAssignment(val name: String, val value: Expr, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A top-level compile-time conditional (`inline if`).
     *
     * The condition is evaluated at compile time. Only the taken branch's
     * top-level items survive into the final program.
     *
     * @property condition the compile-time boolean condition
     * @property thenBranch the top-level items to include when the condition is true
     * @property elseBranch the top-level items to include when the condition is false, or `null`
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class InlineIf(val condition: Expr, val thenBranch: List<TopLevel>, val elseBranch: List<TopLevel>?, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A top-level compile-time block (`inline { ... }`).
     *
     * All items inside are implicitly compile-time. Functions pass through;
     * bindings and conditionals are evaluated at compile time.
     *
     * @property body the list of top-level items inside the block
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class InlineBlock(val body: List<TopLevel>, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A top-level deep compile-time block (`deepinline { ... }`).
     *
     * Like [InlineBlock] but recursive -- all nested constructs are also
     * evaluated at compile time unless escaped with `noinline`.
     *
     * @property body the list of top-level items inside the block
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class DeepInlineBlock(val body: List<TopLevel>, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A top-level deep compile-time conditional (`deepinline if`).
     *
     * Like [InlineIf] but the taken branch is recursively deep-inlined.
     *
     * @property condition the compile-time boolean condition
     * @property thenBranch the top-level items to deep-inline when the condition is true
     * @property elseBranch the top-level items to deep-inline when the condition is false, or `null`
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class DeepInlineIf(val condition: Expr, val thenBranch: List<TopLevel>, val elseBranch: List<TopLevel>?, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A top-level test declaration (`test "name" { body }`).
     *
     * @property name the test name string
     * @property body the test body statements
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class Test(val name: String, val body: List<Stmt>, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A `pack` (struct) declaration: `pack Name { fin x: Int, var y: Int = 0 }`.
     *
     * @property name the struct name
     * @property fields the ordered list of field declarations
     */
    data class Pack(
        val name: String,
        val fields: List<PackField>,
        val typeParams: List<String> = emptyList(),
        val line: Int,
        val column: Int = 0,
        val annotations: List<Annotation> = emptyList(),
        val visibility: Visibility = Visibility.EXPOSE,
        /** `shield pack X {}` prevents external extensions from taking `mut ref self`. */
        val shielded: Boolean = false,
        /** Name of the variadic type param (`T` in `pack Tuple<T...>`), or null for a fixed pack. */
        val variadicParam: String? = null,
        /** Minimum element count from a `where <var>.length >= N` clause, or null if unconstrained. */
        val minVariadicLength: Int? = null,
        /** Field generator for a variadic pack body (`inline for Ty in ...T with index { … }`), or null. */
        val fieldTemplate: VariadicFieldTemplate? = null,
    ) : TopLevel()

    /** `deco Name { fields }` — declares a decorator/annotation type. Parsed and stored; not yet enforced. */
    data class Deco(val name: String, val fields: List<PackField>, val line: Int, val column: Int = 0) : TopLevel()

    /** An extern function signature inside a `bridge` block: `func sin(x: Real): Real` (no body). */
    data class BridgeSig(val name: String, val params: List<Param>, val returnType: TypeRef, val line: Int, val column: Int = 0)

    /** `bridge <target> { func sigs }` — declares extern functions for active FFI targets (C/LLVM, JS/WASM). */
    data class Bridge(val target: String, val funcs: List<BridgeSig>, val line: Int, val column: Int = 0) : TopLevel()

    /** `solo Name { fields; methods }` — declares a singleton struct with one lazily-created shared instance. */
    data class Solo(val name: String, val fields: List<PackField>, val methods: List<FuncDecl>, val line: Int, val column: Int = 0, val visibility: Visibility = Visibility.EXPOSE) : TopLevel()

    /** A constructor parameter for a `node`: `var name: Type` or `fin name: Type`. Stored as a field. */
    data class NodeParam(val name: String, val type: TypeRef, val mutable: Boolean)

    /**
     * `node Name(params) [: Parent(args)] { methods }` — an inheritable type with ctor params (fields)
     * and methods. `leaf node` cannot be subclassed. `repl func` marks overrides.
     */
    data class Node(
        val name: String,
        val params: List<NodeParam>,
        val methods: List<FuncDecl>,
        val parent: String? = null,
        val parentArgs: List<Expr> = emptyList(),
        val isLeaf: Boolean = false,
        val extraFields: List<PackField> = emptyList(),
        val line: Int,
        val column: Int = 0,
        val visibility: Visibility = Visibility.EXPOSE,
    ) : TopLevel()

    /** A singleton registration inside a `wrap` block: `solo Type(args) [bind Spec]`. */
    data class WrapReg(val typeName: String, val args: List<Expr>, val bindSpec: String? = null, val line: Int = 0, val column: Int = 0)

    /** `wrap Name { solo Type(args); Concrete bind Spec }` — a DI container that wires singletons. */
    data class Wrap(val name: String, val registrations: List<WrapReg>, val line: Int, val column: Int = 0) : TopLevel()

    /** `view Name(params) { body }` — a reactive UI component (like a function but with reactive semantics). */
    data class View(val name: String, val params: List<Param>, val body: List<Stmt>, val line: Int, val column: Int = 0) : TopLevel()

    /** `hook name { body }` — a lifecycle callback (start/stop/etc). Called by the runtime. */
    data class Hook(val name: String, val body: List<Stmt>, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * `use ZoneName` or `use ZoneName.Item` — imports items from a named zone so they're
     * accessible without the `ZoneName::` prefix. [imports] is a list of (zoneName, itemName)
     * pairs where itemName is null for "import all".
     */
    data class UseImport(val imports: List<Pair<String, String?>>, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A simple `enum` declaration: `enum Color { Red; Green; Blue }`.
     *
     * @property name the enum name
     * @property variants the variant names, in declaration order
     */
    data class Enum(
        val name: String,
        val variants: List<String>,
        val line: Int,
        val column: Int = 0,
        val annotations: List<Annotation> = emptyList(),
        /** Per-variant annotations, parallel to [variants] (e.g. `Red @deprecated(...)`). */
        val variantAnnotations: List<List<Annotation>> = emptyList(),
    ) : TopLevel()

    /** `fail ErrSet { V1, V2 }` — an error set (a named set of error variants). */
    data class Fail(
        val name: String,
        val variants: List<String>,
        val line: Int,
        val column: Int = 0,
        val annotations: List<Annotation> = emptyList(),
        /** Per-variant annotations, parallel to [variants] (e.g. `NotFound @deprecated(...)`). */
        val variantAnnotations: List<List<Annotation>> = emptyList(),
    ) : TopLevel()

    /**
     * An `impl Type { methods }` block. Each method gets an implicit `self: Type` receiver;
     * calls desugar to `Type_method(self, ...)`.
     *
     * @property typeName the struct the methods extend
     * @property methods the method declarations (without an explicit `self` parameter)
     */
    data class Impl(
        val typeName: String,
        val methods: List<FuncDecl>,
        val traitName: String? = null,
        val line: Int,
        val column: Int = 0,
        /** `impl pack X {}` is the same-file/private implementation form. */
        val isPackImpl: Boolean = false,
        /** `func X.name(...) { ref self -> ... }` extension implementation form. */
        val isExtension: Boolean = false,
        /** Generic arguments on the implemented spec, e.g. `String` in `Into<String>`. */
        val traitArgs: List<TypeRef> = emptyList(),
    ) : TopLevel()

    /** `spec Name { func method(params): Ret; ... }` or compact callback `spec Name<T>: T { ref self } use as "to${T.typeName}"`. */
    data class Spec(
        val name: String,
        val methods: List<FuncDecl>,
        val line: Int,
        val column: Int = 0,
        val callback: SpecCallback? = null,
    ) : TopLevel()

    /** `typealias Name = Type` — a type alias. */
    data class TypeAlias(val name: String, val type: TypeRef, val line: Int, val column: Int = 0) : TopLevel()

    /** A variant of a `slot` (tagged union): `VariantName(Type1, Type2)` or `VariantName` (no payload). */
    data class SlotVariant(val name: String, val payloadTypes: List<TypeRef>)

    /** `slot Name { Variant(Type); Variant2(Type1, Type2); Variant3 }` — a tagged union. */
    data class Slot(val name: String, val variants: List<SlotVariant>, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A top-level compile-time assertion (`inline assert condition { "message" }`).
     *
     * @property condition the compile-time boolean condition
     * @property message the error message expression
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class InlineAssert(val condition: Expr, val message: Expr, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * A top-level compile-time trace (`inline trace { expr }`).
     *
     * @property message the message expression
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class InlineTrace(val message: Expr, val line: Int, val column: Int = 0) : TopLevel()
}

/**
 * The root of an Azora AST, representing a complete source file.
 *
 * @property moduleName the declared module name, or `null` if no `module` declaration is present
 * @property items the list of top-level items (functions and compile-time constructs)
 */
data class Program(
    val moduleName: String?,
    val items: List<TopLevel>,
    /**
     * Pack names owned by the source unit before stdlib injection. `impl pack`
     * uses this set to stay limited to the file that declared the pack.
     */
    val localPackNames: Set<String> = emptySet(),
) {
    /** Convenience — returns only the resolved function declarations. */
    val functions: List<FuncDecl> get() = items.filterIsInstance<TopLevel.Func>().map { it.decl }

    /** Convenience — returns only the test declarations. */
    val tests: List<TopLevel.Test> get() = items.filterIsInstance<TopLevel.Test>()
}
