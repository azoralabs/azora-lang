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
/**
 * The method names that read a value out of an optional and drop the `?` from
 * its type: the primitive `take opt.require()` and the shorthand `opt.take()`.
 *
 * They are typed and lowered by the compiler rather than declared, so that they
 * are available on every optional whatever it wraps.
 */
val OPTIONAL_UNWRAP = setOf("require", "take")

/**
 * Which ownership operation an [Expr.Isolated] performs.
 *
 * @property spelling how it is written in source, for diagnostics.
 * @property capability the spec the operand's type must carry, or null when the
 *   operation asks nothing of it.
 */
enum class OwnershipOp(val spelling: String, val capability: String?) {
    /** `isolated(v)` — a deep copy, with no capability contract. */
    ISOLATE("isolated", null),

    /** `clone v` — an independently owned duplicate. */
    CLONE("clone", "Clone"),

    /**
     * `take v` — ownership transfer. Duplicates nothing, and leaves the operand
     * unusable, which is what separates it from the other two.
     *
     * It asks nothing of the operand: every value can be given away, so a
     * capability for it would say nothing.
     */
    TAKE("take", null),

    /**
     * `lend v` — ownership transfer the callee gives back.
     *
     * The parameter it feeds is marked `return`, so the value comes home when
     * the call ends and the operand stays usable. It differs from a borrow in
     * that the callee genuinely owns the value while it runs, and from `take`
     * in that the caller gets it back.
     */
    LEND("lend", null),

    /** `v.&` / `v&` — a shared, read-only borrow. Owns nothing. */
    SHARE("&", null),

    /** `v.!` / `v!` — an exclusive, mutable borrow. Owns nothing. */
    BORROW("!", null),
    ;

    /** True for the two forms that borrow rather than duplicate or transfer. */
    val isBorrow: Boolean get() = this == SHARE || this == BORROW
}

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
    data class DoubleLiteral(val value: Double, override val line: Int, override val column: Int = 0, override val length: Int = 0, val suffix: NumericSuffix = NumericSuffix.NONE) : Expr()

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
    /**
     * A call `callee(args)`. When [receiver] is non-null the call target is an
     * arbitrary expression value (e.g. `fs[0](x)`, `getFn()(x)`) rather than a
     * named function; [callee] is then unused. The receiver must evaluate to a
     * function value.
     */
    data class Call(val callee: String, val args: List<Expr>, override val line: Int, override val column: Int = 0, override val length: Int = 0, val typeArgs: List<TypeRef> = emptyList(), val receiver: Expr? = null) : Expr()

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
    /**
     * `target.name`, where the name may be computed.
     *
     * [nameExpr] carries a `${ … }` written in name position — `self.${f.name}`.
     * Compile-time expansion folds it into [name]; every later stage sees an
     * ordinary member access, so nothing downstream needs to know it was spliced.
     */
    data class Member(
        val target: Expr,
        val name: String,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        val nameExpr: Expr? = null,
    ) : Expr()

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

    /** `try expr` — evaluates [expr] and propagates any failure to the caller. */
    data class TryPropagate(val expr: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * If-expression `if cond { a } else { b }` — both branches are single
     * expressions and one of them becomes the value of the whole expression.
     */
    data class IfExpr(val condition: Expr, val thenExpr: Expr, val elseExpr: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * A first-class callable value.
     *
     * [params] are ordinary call parameters. [receivers] are contextual
     * parameters which may be supplied explicitly after the ordinary
     * arguments or resolved from an enclosing `with` block.
     */
    data class Lambda(
        val params: List<Param>,
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        val variadic: Boolean = false,
        val receivers: List<Param> = emptyList(),
        val kind: CallableKind = CallableKind.FUNC,
    ) : Expr()

    /**
     * `inline for <name> in <iterable> { <expr> }` written as a call argument.
     *
     * Expands to one argument per iteration, so a constructor can be built from a
     * type's fields without naming them. Replaced during compile-time expansion;
     * it never reaches semantic analysis.
     */
    data class InlineForArgs(
        val name: String,
        val iterable: Expr,
        val body: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
    ) : Expr()

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

    /**
     * A type cast. Surface sugar: `x as T` ([CastKind.STATIC], = `std::cast<T>(x)`),
     * `x as? T` ([CastKind.DYNAMIC], = `std::dyncast<T>(x)`, result `T?`), and
     * `x as* T` ([CastKind.REINTERPRET], = `std::bitcast<T>(x)`).
     */
    data class Cast(val expr: Expr, val targetType: TypeRef, val kind: CastKind = CastKind.STATIC, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `expr is Type` — runtime type check, returns Bool. */
    data class IsCheck(val expr: Expr, val typeName: String, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * `value in collection` — membership.
     *
     * Written as an ordinary expression so a `where` clause needs no grammar of its
     * own: `N in 2..4` is this node over a range, and the same spelling works for a
     * list or a set.
     */
    data class InCheck(
        val value: Expr,
        val collection: Expr,
        val negated: Boolean = false,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
    ) : Expr()

    /** Map literal `["k": v, "k2": v2]`. */
    data class MapLit(val entries: List<Pair<Expr, Expr>>, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `alloc <expr>` — heap-allocate a value and return a pointer to it. */
    /** `alloc* value` → `T*` (read-only), `alloc^ value` → `T^` (mutable). */
    data class Alloc(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0, val mutable: Boolean = false) : Expr()

    /** `alloc T(count)` — allocate a buffer of `count` elements of type T (C++-style), returning `T*`. */
    data class AllocBuffer(val typeName: String, val count: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `*ptr` — dereference a pointer. */
    data class Deref(val target: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * An ownership operation on [value]: `isolated(v)`, `clone v`, or `take v`.
     *
     * All three are one node because they differ only in what they require of
     * the operand and whether they duplicate it — the shape is identical, and
     * every pass that merely walks the tree should treat them alike.
     */
    data class Isolated(
        val value: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        val op: OwnershipOp = OwnershipOp.ISOLATE,
    ) : Expr()

    /** `await task` — suspend until the task completes and yield its result. */
    data class Await(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `inject Type` — resolve the singleton instance of [typeName] from the DI container. */
    data class Inject(val typeName: String, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `arr...` — spread an array's elements as individual call arguments. */
    data class Spread(val array: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * A macro invocation `name!(…)`, `name![…]`, or `name!{…}` (see [TopLevel.Meta]).
     */
    data class MetaInvoke(val name: String, val args: List<Expr>, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * A slice expression `target[start:stop:step]` (Python-style), despatched to the
     * target type's `oper[:]` overload. Any of [start]/[stop]/[step] may be null
     * (omitted), matching Python's open-ended slicing.
     */
    data class Slice(val target: Expr, val start: Expr?, val stop: Expr?, val step: Expr?, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()
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
     * Rebindable binding: `var` (mutable value) or `val` (immutable value).
     *
     * The four binding keywords vary two independent axes — whether the *name*
     * can be rebound, and whether the *value* can be mutated through it. The
     * name axis picks the node ([VarDecl] rebinds, [LetDecl]/[FinDecl] do not);
     * [valueMutable] carries the value axis:
     *
     * | keyword | rebind | mutate value | node                        |
     * |---------|--------|--------------|-----------------------------|
     * | `var`   | yes    | yes          | `VarDecl(valueMutable=true)`  |
     * | `val`   | yes    | no           | `VarDecl(valueMutable=false)` |
     * | `let`   | no     | yes          | `LetDecl`                     |
     * | `fin`   | no     | no           | `FinDecl`                     |
     *
     * @property name the variable name
     * @property type the declared or inferred type annotation
     * @property initializer the expression that provides the initial value
     * @property valueMutable false for `val` — the value cannot be mutated or
     *   borrowed mutably through this name, though the name can be rebound
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
        override val length: Int = 0,
        val valueMutable: Boolean = true
    ) : Stmt()

    /**
     * Immutable binding, immutable value (`fin`). See [VarDecl] for the model.
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
     * Immutable binding, mutable value (`let`). See [VarDecl] for the model.
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
     * Evaluated during CTCE. The initializer must be a compile-time constant.
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
     * Compile-time rebindable binding (`inline var` / `inline val`).
     *
     * All four binding keywords work in a compile-time scope exactly as they do
     * at runtime; see [VarDecl] for the two-axis model.
     *
     * @property name the binding name
     * @property type the declared or inferred type annotation
     * @property initializer the compile-time constant expression
     * @property valueMutable false for `inline val`
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
        override val length: Int = 0,
        val valueMutable: Boolean = true
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
        override val length: Int = 0,
        /**
         * The operator of the `op=` this came from, when it came from one.
         *
         * `a += b` desugars to `a = a + b` in the parser, which has no types to
         * decide with. Keeping the operator lets the lowerer notice that the
         * type declared an in-place `oper+=` and call it instead — otherwise a
         * declared compound-assignment operator is dead code.
         */
        val compoundOp: TokenType? = null,
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
     * Scoped block (`scope { ... }`). Introduces a new variable scope.
     *
     * @property body the list of statements inside the scope
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Scope(
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        /** `scope alloc { }` — allocations inside are tracked and freed at exit. */
        val alloc: Boolean = false,
        /** Explicit opt-in boundary for operations whose contracts cannot be proven safe. */
        val unsafe: Boolean = false,
        /**
         * True for a scope the source actually wrote.
         *
         * Sibling written scopes share one scope, so a binding made in the first
         * is visible in the second. The compiler also builds scopes of its own —
         * to scope an inlined body, an `unsafe { }` block, a desugared for-else
         * — and those must stay independent, or two inlined calls would collide
         * on their locals.
         */
        val shared: Boolean = false
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
     * Evaluated during CTCE. The condition must be a compile-time constant.
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
     * Runtime trace (`trace level { expr }`).
     *
     * Prints `[LEVEL] message` at runtime. [level] is `null` only for ASTs built
     * by older compiler clients; the compiler resolves that to the first
     * `LogLevel` variant. Source parsed by the current grammar always supplies it.
     * NOT allowed at global scope.
     *
     * @property message the message expression (must be String)
     * @property level the selected `LogLevel` expression
     * @property liftBody whether the message came from a brace lambda and must
     * be lifted into a generated IR function
     * @property explicitLevel whether source code selected a log level
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class Trace(
        val message: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        val level: Expr? = null,
        val liftBody: Boolean = true,
        val explicitLevel: Boolean = true,
    ) : Stmt()

    /**
     * Compile-time assertion (`inline assert condition { "message" }`).
     *
     * Evaluated during CTCE. If condition is false, produces a compilation error.
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
     * Compile-time trace (`inline trace level { expr }`).
     *
     * Evaluated during CTCE, message stored as a compiler warning.
     * Allowed in all scopes including global.
     *
     * @property message the message expression (must be String)
     * @property level the selected compile-time `LogLevel` expression
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    data class InlineTrace(
        val message: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        val level: Expr? = null,
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
        override val length: Int = 0,
        /** Optional compile-time position binding introduced by `with index`. */
        val indexName: String? = null,
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
        override val length: Int = 0,
        /**
         * The operator of the `op=` this came from, when it came from one.
         *
         * `a += b` desugars to `a = a + b` in the parser, which has no types to
         * decide with. Keeping the operator lets the lowerer notice that the
         * type declared an in-place `oper+=` and call it instead — otherwise a
         * declared compound-assignment operator is dead code.
         */
        val compoundOp: TokenType? = null,
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
        override val length: Int = 0,
        /** The operator of the `op=` this came from; see [Assignment.compoundOp]. */
        val compoundOp: TokenType? = null,
        /** A `${ … }` written in name position; folded into [name] during expansion. */
        val nameExpr: Expr? = null,
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
    data class Effect(
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        /** Null means automatic dependency tracking; an empty list is explicit. */
        val dependencies: List<Expr>? = null,
        /** `effect defer { ... }` runs at reactive-owner disposal. */
        val deferred: Boolean = false,
    ) : Stmt()

    /** `with value { ... }` / `with [a, b] { ... }` contextual receiver scope. */
    data class WithContext(
        val values: List<Expr>,
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
    ) : Stmt()
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
        WEAK("weak ref");

        /** The parameter/receiver borrow this reference kind stands for. */
        val paramModifier: ParamModifier
            get() = if (this == MUTABLE) ParamModifier.EXCLUSIVE else ParamModifier.SHARED
    }

    /**
     * A named type, optionally generic.
     *
     * [qualifier] preserves the source-level realm path (`std` in
     * `std::Tuple<Int, String>`). Semantic passes still use [name] as the
     * canonical declaration name, while visibility checks can distinguish a
     * qualified type reference from a bare one.
     */
    data class Named(
        val name: String,
        val args: List<TypeRef> = emptyList(),
        val variadic: Boolean = false,
        val qualifier: String? = null,
    ) : TypeRef() {
        override fun toString() = when {
            TypeFunctionCall.isCall(this) -> "${TypeFunctionCall.name(this)}<${args.joinToString(", ")}>"
            args.isEmpty() -> qualifiedName()
            else -> "${qualifiedName()}<${args.joinToString(", ")}>"
        }

        private fun qualifiedName(): String = qualifier?.let { "$it::$name" } ?: name

        /*
         * A qualifier is source-level access metadata, not part of type
         * identity. `std::Tuple<Int, Int>` must resolve to the same semantic
         * type as the Tuple declaration's own `Tuple<Int, Int>` references.
         */
        override fun equals(other: Any?): Boolean =
            other is Named && name == other.name && args == other.args && variadic == other.variadic

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + args.hashCode()
            result = 31 * result + variadic.hashCode()
            return result
        }
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

    /** First-class callable type with ordinary and contextual parameters. */
    data class Function(
        val params: List<TypeRef>,
        val ret: TypeRef,
        val receivers: List<TypeRef> = emptyList(),
        val kind: CallableKind = CallableKind.FUNC,
    ) : TypeRef() {
        override fun toString(): String {
            val prefix = if (kind == CallableKind.FUNC) "" else "${kind.surfaceName} "
            val context = if (receivers.isEmpty()) "" else receivers.joinToString(", ", "[", "]")
            val arguments = if (params.isEmpty()) {
                if (receivers.isEmpty()) "()" else ""
            } else {
                params.joinToString(", ", "(", ")")
            }
            return "$prefix$context$arguments -> $ret"
        }
    }

    /** Tuple type `(A, B)` (two or more elements). */
    data class Tuple(val elements: List<TypeRef>) : TypeRef() {
        override fun toString() = "(${elements.joinToString(", ")})"
    }

    /** Nullable type `T?`. */
    data class Nullable(val inner: TypeRef) : TypeRef() {
        override fun toString() = "$inner?"
    }

    /** Failable type `T!Error` or `T![A, B]`. */
    data class Failable(val ok: TypeRef, val errSets: List<String>) : TypeRef() {
        init {
            require(errSets.isNotEmpty()) { "A failable type requires at least one error set" }
            require(errSets.distinct().size == errSets.size) { "A failable type cannot repeat an error set" }
        }

        constructor(ok: TypeRef, errSet: String) : this(ok, listOf(errSet))

        val errSet: String get() = errSets.single()

        override fun toString(): String = if (errSets.size == 1) {
            "$ok!${errSets.single()}"
        } else {
            "$ok![${errSets.joinToString(", ")}]"
        }
    }

    /** Pointer type `T*` — a reference to a heap value of [inner]. */
    /**
     * Pointer type `T*` (read-only) or `T^` (mutable).
     *
     * The sigil is the whole difference and it is carried at every site — the
     * type, the allocation that produced it, and the dereference that reads it —
     * so a mutation through a pointer is visible without consulting the
     * declaration.
     */
    data class Pointer(val inner: TypeRef, val mutable: Boolean = false) : TypeRef() {
        override fun toString() = "$inner*"
    }

    /**
     * A compile-time integer used as a **const-generic type argument** — the `3` in
     * `Array<Int, 3>`. Not a runtime type; it supplies a value (e.g. an array's
     * element count) at type-argument position. Resolved into the dependent type
     * (e.g. `IrType.Array(_, size = 3)`).
     */
    /**
     * A const-generic *value* argument: `3` in `Array<Int, 3>`.
     *
     * [label] names the enum variant the value stands for (`RowMajor` for
     * `Mat<…, .RowMajor>`), so an enum-typed const argument keeps its name in
     * diagnostics and in the mangled specialization rather than becoming a bare
     * ordinal. Comparison is by [value]: the label is how it reads, not what it is.
     */
    data class Const(val value: Long, val label: String? = null) : TypeRef() {
        override fun toString() = label ?: value.toString()

        /** True when the variant's position is not known yet. See [UNRESOLVED]. */
        val unresolved: Boolean get() = value == UNRESOLVED && label != null

        // Identity is the label when there is one: a variant argument may be written
        // where its enum is not yet in scope, and `.ColumnMajor` is the same argument
        // whether or not its position has been looked up.
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Const && (if (label != null || other.label != null) label == other.label else value == other.value))
        override fun hashCode(): Int = label?.hashCode() ?: value.hashCode()

        companion object {
            /** The position of a variant whose enum has not been seen yet. */
            const val UNRESOLVED = Long.MIN_VALUE
        }
    }

    /**
     * A checked reference. Ownership is carried by the qualifier, not punctuation.
     *
     * [origins] names the parameters a returned borrow comes from — the `[a, b]`
     * in `func choose(a: String&, b: String&): String&[a, b]`. Azora infers most
     * borrow relationships, so this is written only where a public signature has
     * to state one; empty means "inferred".
     */
    data class Reference(
        val kind: RefKind,
        val inner: TypeRef,
        val origins: List<String> = emptyList(),
    ) : TypeRef() {
        override fun toString() =
            if (origins.isEmpty()) "${kind.spelling} $inner"
            else "${kind.spelling} $inner[${origins.joinToString(", ")}]"
    }

    /** Human-readable name for diagnostics (the simple name for [Named]). */
    fun displayName(): String = when (this) {
        is Named -> name
        else -> toString()
    }
}

// ---------------------------------------------------------------------------
// Compile-time type functions
// ---------------------------------------------------------------------------

/** A type parameter of a `deepinline prop`. */
data class TypeFunctionParam(val name: String, val variadic: Boolean = false)

/**
 * A compile-time type property (`deepinline prop Name<...T>: Type { … }`).
 *
 * A type property receives types and returns a [TypeRef]. It is erased before
 * IR generation and can therefore never be called at runtime; a use site spells
 * it exactly like a generic type (`Promote<T, U>`).
 */
data class TypeFunctionDecl(
    val name: String,
    val params: List<TypeFunctionParam>,
    val body: List<TypeFunctionStmt>,
    val minVariadicLength: Int? = null,
    /** The declaration's `where` clause as an expression; see [TopLevel.Pack.whereClause]. */
    val whereClause: Expr? = null,
    val line: Int,
    val column: Int = 0,
    val annotations: List<Annotation> = emptyList(),
) {
    val variadicParam: String? get() = params.singleOrNull { it.variadic }?.name
}

/** Statements accepted in a `deepinline prop` body. */
sealed class TypeFunctionStmt {
    data class Binding(val name: String, val value: TypeFunctionExpr, val mutable: Boolean) : TypeFunctionStmt()
    data class Assignment(val name: String, val value: TypeFunctionExpr) : TypeFunctionStmt()
    data class ForEach(
        val name: String,
        val packName: String,
        val startIndex: Int,
        val body: List<TypeFunctionStmt>,
    ) : TypeFunctionStmt()
    /** `if COND { … } [else { … }]` — a branch over a type comparison. */
    data class If(
        val condition: TypeFunctionCondition,
        val thenBody: List<TypeFunctionStmt>,
        val elseBody: List<TypeFunctionStmt>,
    ) : TypeFunctionStmt()
    data class Return(val value: TypeFunctionExpr) : TypeFunctionStmt()
}

/** Expressions evaluated by a compile-time type function. */
sealed class TypeFunctionExpr {
    data class Reference(val name: String) : TypeFunctionExpr()
    data class PackElement(val packName: String, val index: Int) : TypeFunctionExpr()
    data class Call(val name: String, val args: List<TypeFunctionExpr>) : TypeFunctionExpr()
    data class Conditional(
        val condition: TypeFunctionCondition,
        val thenValue: TypeFunctionExpr,
        val elseValue: TypeFunctionExpr,
    ) : TypeFunctionExpr()
}

/** A type comparison used by a [TypeFunctionExpr.Conditional]. */
data class TypeFunctionCondition(
    val left: TypeFunctionExpr,
    val operator: TokenType,
    val right: TypeFunctionExpr,
    val compareRank: Boolean,
)

/** Internal encoding for a deferred `Name<...>` type-property call inside a [TypeRef]. */
object TypeFunctionCall {
    private const val PREFIX = "__azora_type_function__"

    fun create(name: String, args: List<TypeRef>): TypeRef.Named = TypeRef.Named(PREFIX + name, args)
    fun isCall(ref: TypeRef.Named): Boolean = ref.name.startsWith(PREFIX)
    fun name(ref: TypeRef.Named): String = ref.name.removePrefix(PREFIX)
}

/**
 * Internal encoding for a library-defined named type-macro invocation.
 */
object NamedTypeMacroCall {
    private const val PREFIX = "__azora_named_type_macro__"
    private const val SEPARATOR = "\u001f"

    enum class Form { Prefix, List, Infix }

    fun create(name: String, args: List<TypeRef>, modifier: String = "", form: Form): TypeRef.Named =
        TypeRef.Named("$PREFIX$modifier$SEPARATOR${form.name}$SEPARATOR$name", args)

    fun isCall(ref: TypeRef.Named): Boolean = ref.name.startsWith(PREFIX)

    fun modifier(ref: TypeRef.Named): String =
        ref.name.removePrefix(PREFIX).substringBefore(SEPARATOR)

    fun form(ref: TypeRef.Named): Form =
        Form.valueOf(ref.name.removePrefix(PREFIX).substringAfter(SEPARATOR).substringBefore(SEPARATOR))

    fun name(ref: TypeRef.Named): String =
        ref.name.removePrefix(PREFIX).substringAfter(SEPARATOR).substringAfter(SEPARATOR)
}

/** Encodes a source path containing both an owning module and a realm-qualified symbol. */
object ModuleQualifiedSymbol {
    private const val PREFIX = "__azora_module_qualified__"
    private const val SEPARATOR = "::"

    fun create(module: String, symbol: String): String = "$PREFIX$module$SEPARATOR$symbol"
    fun isQualified(name: String): Boolean = name.startsWith(PREFIX) && SEPARATOR in name
    fun module(name: String): String = name.removePrefix(PREFIX).substringBefore(SEPARATOR)
    fun symbol(name: String): String = name.removePrefix(PREFIX).substringAfter(SEPARATOR)
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
     * No annotation was written.
     *
     * What that means depends on where it appears. On a **binding** the type is
     * inferred from the initializer. On a **`func` or `prop`** it is not
     * inferred: an omitted return type *is* the annotation, and it says `Unit`
     * (see `DIPs/DO_NOT_INFER_RETURN_TYPE.MD`). The distinction between "written
     * `: Unit`" and "wrote nothing" is kept precisely so a `return <value>` in
     * the second case can be reported as a missing declaration rather than as an
     * ordinary type mismatch. A **lambda** keeps inference: it has no
     * declaration to read, so its result type comes from its body.
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
/**
 * How widely a declaration can be reached.
 *
 * [PUBLIC] is the default and needs no keyword: a declaration is reachable
 * unless something says otherwise. [CONFINE] narrows it to the declaring
 * package. Anything narrower than that is spelled with a leading underscore on
 * the name itself, which keeps the restriction where a reader is already
 * looking.
 */
enum class Visibility { PUBLIC, CONFINE }

/**
 * Visibility of a whole module (`[expose] [confine] mod x`).
 *
 * - [EXPOSE] (default): importable everywhere, including downstream libraries.
 * - [PROTECT]: importable only within the declaring folder.
 * - [CONFINE]: private — not importable anywhere (e.g. a test file or an app's
 *   `main` module).
 *
 * Orthogonal to `export` (see [Program.isExported]): `expose` auto-imports the
 * module into every unit within its visibility scope. `expose confine` is
 * contradictory and rejected at parse time.
 */
enum class ModuleVisibility { PUBLIC, CONFINE }

/**
 * Compile-time metadata for a declaration's enclosing realm, surfaced by
 * `(reflect X).realm`. The global (top-level) scope is a realm with [label]
 * `"global"`, [isInline] `false`, and no [parent].
 *
 * @property label the realm's string label (`realm "my realm" { … }`), or null for
 *   an unlabeled `realm { … }`; `"global"` for the top-level scope.
 * @property isInline whether the realm is `inline`/`deepinline`, or is nested in
 *   one (inline-ness is inherited by nested realms).
 * @property parent the enclosing realm, or null at the global scope.
 */
data class RealmMeta(val label: String?, val isInline: Boolean, val parent: RealmMeta? = null)

/**
 * Kind of a type cast ([Expr.Cast]).
 *
 * - [STATIC]: converting cast (`static_cast`) — numeric conversions, stringify to
 *   `String`, unchecked up/down casts. Spelled `x as T` / `std::cast<T>(x)`.
 * - [DYNAMIC]: runtime-checked downcast (`dynamic_cast`) yielding `T?` (null on a
 *   type mismatch). Spelled `x as? T` / `std::dyncast<T>(x)`.
 * - [REINTERPRET]: bit reinterpretation (`reinterpret_cast`), representation-
 *   preserving. Spelled `x as* T` / `std::bitcast<T>(x)`.
 */
enum class CastKind { STATIC, DYNAMIC, REINTERPRET }

enum class ReactiveKind { MEM, REM, RET }

/** Surface callable families supported by first-class lambda values. */
enum class CallableKind(val surfaceName: String) {
    FUNC(""),
    TASK("async"),
}

/** Test execution mode mirrored by the compiler-predefined `TestMethod` enum. */
enum class TestMethod { This, All }

/**
 * How a parameter or receiver borrows its argument.
 *
 * Written as a postfix sigil on the name — `x&` borrows for reading, `x!`
 * borrows exclusively — so none of these has a keyword behind it. [NONE] is a
 * plain by-value parameter.
 */
enum class ParamModifier {
    NONE,
    /** `x&` — shared, read-only. */
    SHARED,
    /** `x!` — exclusive; the callee may write through it. */
    EXCLUSIVE;

    val writable: Boolean get() = this == EXCLUSIVE

    /** How the borrow is written at the parameter: `&`, `!`, or nothing. */
    val sigil: String get() = when (this) {
        NONE -> ""
        SHARED -> "&"
        EXCLUSIVE -> "!"
    }
}

data class Param(
    val name: String,
    val type: TypeRef,
    val defaultValue: Expr? = null,
    val modifier: ParamModifier = ParamModifier.NONE,
    /** True when declared with the `...T` variadic syntax (call sites pack extra args). */
    val variadic: Boolean = false,
    /** Parameter-level decorators, parsed from `name: @Decorator Type`. */
    val annotations: List<Annotation> = emptyList(),
    /**
     * `name: return T` — ownership of this parameter goes back to the caller.
     *
     * The callee owns the value while it runs and the caller owns it again
     * afterwards, so the argument is written `lend x` rather than `take x` and
     * `x` stays usable. See §13 of `OWNERSHIP_BORROWING_DIP.md`.
     */
    val returnsOwnership: Boolean = false,
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
/**
 * One compile-time repetition of a field block.
 *
 * `inline for r in 0..<R { var m$r$c: T = 0 }` declares a *family* of fields, not one
 * field with a predicate: the name varies with the loop variable, so the layout has
 * as many members as the range has values. Nested loops stack, outermost first.
 */
data class FieldRepeat(val variable: String, val range: Expr)

data class PackField(
    val name: String,
    val type: TypeRef,
    val mutable: Boolean,
    val default: Expr?,
    val visibility: Visibility = Visibility.PUBLIC,
    val annotations: List<Annotation> = emptyList(),
    /**
     * Compile-time loops this field is declared inside, outermost first.
     *
     * Empty for an ordinary field. When present the declaration is a template: one
     * field per combination of loop values, with `$var` in [name] replaced by the
     * value it took. Expanded by PackSpecializer, which is the single authority on
     * what a pack's concrete layout is.
     */
    val repeats: List<FieldRepeat> = emptyList(),
    /**
     * When set, the field exists only for argument bindings satisfying it.
     *
     * `inline if N >= 3 { var z: T = 0 }` and `var w: T = 0 where N == 4` both put
     * their condition here, so a conditional field is one field with a predicate
     * rather than a separate kind of declaration. Evaluated by
     * ConstraintEvaluator against the same bindings a `where` clause uses.
     */
    val condition: Expr? = null,
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
 * may be marked as `inline` to be substituted at call sites by the CTCE evaluator.
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
    val visibility: Visibility = Visibility.PUBLIC,
    /**
     * False when the declaration wrote no return type at all.
     *
     * An omitted return type means `Unit`, so [returnType] is already correct —
     * this only records *how* it got there, which is what lets a `return <value>`
     * be reported as a missing declaration rather than an ordinary mismatch. It
     * is separate from `returnType is TypeAnnotation.Inferred` because the
     * failable shorthand `func f() ?! E` also declares nothing, yet must carry an
     * explicit `Unit ?! E` for the error set.
     */
    val returnTypeDeclared: Boolean = true,
    /** Receiver mutability for impl/extension methods: `self&` (immutable) or `self!` (mutable). */
    val receiverModifier: ParamModifier = ParamModifier.EXCLUSIVE,
    /** Receiver name for impl/extension methods (conventionally `self`, but arbitrary). */
    val receiverName: String = "self",
    /**
     * Bracketed extension receiver: `func m()[self: Type&]: R`. When present, the
     * function is an extension method on the receiver's type (callable as
     * `value.m()` or inside `with value { m() }`). The param's type carries the
     * borrow (`Type&` → `ref`, `Type!` → `mut ref`).
     */
    val extensionReceiver: Param? = null,
    /** Name of the variadic type param (`T` in `func name<...T>`), or null for a fixed function. */
    val variadicParam: String? = null,
    /** Minimum element count from a `where <var>.length >= N` clause, or null if unconstrained. */
    val minVariadicLength: Int? = null,
    /** The declaration's `where` clause as an expression; see [TopLevel.Pack.whereClause]. */
    val whereClause: Expr? = null,
    /**
     * Type parameters declared as const value params (`name: Int` in `<…>`), e.g.
     * `N` in `func<T, N: Int>`. They are supplied as [TypeRef.Const] arguments at
     * call/instantiation sites and fold into dependent types (e.g. `Array<T, N>`).
     */
    val constParams: Set<String> = emptySet(),
    /** Default values for const parameters, e.g. `O: MatrixOrder = .RowMajor`. */
    val constDefaults: Map<String, TypeRef> = emptyMap(),
    /** Const parameter → the enum whose variants it ranges over, when it has one. */
    val constEnums: Map<String, String> = emptyMap(),
    /** How this declaration may be invoked when registered as an impl member. */
    val memberCallStyle: MemberCallStyle = MemberCallStyle.NORMAL,
    /**
     * A spec member's call-site alias template — `use as "to${T.typeName}"`.
     *
     * The member keeps its own canonical name (`into`), and an implementation is
     * additionally reachable under the expanded template (`.toString`). Written
     * on the member rather than the spec because it is the member that gets the
     * second name.
     */
    val useAsTemplate: String? = null,
    /**
     * A generic `infx` whose receiver is a type parameter (`infx<K,V> K.to(v)`):
     * callable as an infix method on any receiver type (`a to b` → `to(a, b)`).
     * The first param is the receiver (`self`).
     */
    val isUniversalInfix: Boolean = false,
)

enum class MemberCallStyle {
    NORMAL,
    PROPERTY,
    METHOD,

    /**
     * A property reached through the type rather than a value — `Type::name`.
     *
     * A spec requirement written without a receiver (`prop rank: Int`) asks for one
     * of these, and `impl Spec for Type:: { … }` supplies it.
     */
    STATIC_PROPERTY,

    /**
     * A method reached through the type rather than a value — `Type::name(args)`.
     *
     * A spec requirement written without a receiver (`func from(value: T): Self`)
     * asks for one of these, and `impl Spec for Type:: { … }` supplies it. It is
     * the only shape a *constructing* conversion can have: `From` builds a value,
     * so there is no `self` to build it from.
     */
    STATIC_METHOD,
}

/**
 * The delimiter a macro arm was *written* with: `()` ([PAREN]), `[]`
 * ([BRACKET]), or `{}` ([BRACE]). Stored for diagnostics only — macro arms
 * match delimiter-agnostically, so `vec!()`, `vec![]`, and `vec!{}` all feed
 * their arguments to the same arms.
 */
enum class MacroDelimiter { PAREN, BRACKET, BRACE }

/**
 * The left-hand pattern of a macro arm, matched against a invocation's
 * argument list.
 *
 * - [Empty] matches zero arguments (`[]` / `()` / `{}`).
 * - [SeqCapture] matches one or more arguments and binds the whole list to
 *   [name] (e.g. `[...$items]` binds `"$items"`), spliceable via `...$items`
 *   in the arm template.
 */
sealed class MacroPattern {
    /** `[]` / `()` / `{}` — matches an invocation that passed no arguments. */
    object Empty : MacroPattern()
    /** `(...$name)` / `[...$name]` / `{...$name}` — matches ≥1 arg, binding the full list to [name]. */
    data class SeqCapture(val name: String) : MacroPattern()
    /**
     * `[...${key: value}]` — matches ≥1 `k: v` argument pair (each passed as an
     * [Expr.MapEntryArg]), binding the key exprs to [keyName] and the value exprs
     * to [valueName] so a template can splice them (e.g. `...std::mapEntry($key, $value)`).
     */
    data class MapEntryCapture(val keyName: String, val valueName: String) : MacroPattern()
}

/**
 * One arm of a `meta` declaration: when an invocation's arguments match
 * [pattern], the macro expands to [template] with `$captures` substituted.
 *
 * @property delimiter the delimiter the arm was written with (diagnostic only)
 * @property pattern the argument-list pattern to match
 * @property template the expansion expression (ordinary [Expr], may reference captures)
 */
data class MacroArm(val delimiter: MacroDelimiter, val pattern: MacroPattern, val template: Expr)

/**
 * A value-level infix macro arm from `meta .Infix("op") { $a $b => template }`.
 * A use `x op y` rewrites to [template] with `$a`→x and `$b`→y.
 */
data class InfixMacroRule(val op: String, val left: String, val right: String, val template: Expr)

/**
 * Which built-in type-sugar form a [TypeTypeArm] pattern matches. Used by the
 * (staged) `meta type` rewriting pass to map a source form to its template.
 */
enum class TypeFormKind { ARRAY, ARRAY_SIZED, SET, MAP, TUPLE, PREFIX, PREFIX_LIST, INFIX }

/**
 * One arm of a `meta type { pattern => template }` declaration: when a type
 * of [kind]'s shape appears, expand it to [template] substituting [holes]
 * positionally with the actual type arguments.
 *
 * Shape rules are expanded after library injection, so syntax is available
 * only when the module declaring its rule is imported. This keeps the parser
 * independent of library types such as Tuple.
 */
data class TypeTypeArm(
    val kind: TypeFormKind,
    val holes: List<String>,
    val template: TypeRef,
    val prefix: String = "",
    /** Name of a library-defined prefix/list/infix type macro; null for shape sugar. */
    val name: String? = null,
)

/**
 * A decorator/annotation application: `@Name` or `@Name(args)`.
 *
 * @property name the decorator name
 * @property args optional arguments (`@Name(a, b)`)
 * @property line 1-based source line
 * @property column 1-based source column
 */
data class Annotation(
    val name: String,
    val args: List<Expr> = emptyList(),
    val line: Int = 0,
    val column: Int = 0,
    /** Named arguments `@name(key = value)` / `@name(key: value)`, in source order. */
    val namedArgs: List<Pair<String, Expr>> = emptyList(),
)

/**
 * A decorator-to-spec binding declared by `deco D bind Spec<...Args>`.
 *
 * The decorated declaration's type is inserted as the bound spec's first
 * generic argument. [trailingTypeArgs] supply any remaining generic arguments.
 */
/** Declaration categories accepted by decorator and binding `for` clauses. */
/**
 * Where a decorator may be applied. Mirrors `bridge enum DecoTarget` in
 * `std/core.az`; the two are matched by name, so they have to agree.
 */
enum class DecoTarget {
    Pack, Func, AsyncFunc, Prop, AsyncProp, Enum, VariantEnum, EnumValue,
    Error, VariantError, ErrorValue, UnsafeUnion, UnionValue, Annot,
    Field, Param, Var, Let, Val, Fin, Test,
    Ctor, Dtor, TypeAlias, Bridge, Oper,
}

data class DecoratorBinding(
    val name: String,
    val trailingTypeArgs: List<TypeRef> = emptyList(),
    /** Empty means the binding is active for every decorator target. */
    val targets: Set<DecoTarget> = emptySet(),
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
 * by the CTCE evaluator before semantic analysis.
 */
sealed class TopLevel {
    /**
     * A top-level function declaration.
     *
     * @property decl the full function declaration
     */
    data class Func(val decl: FuncDecl) : TopLevel()

    /** Runtime top-level mutable binding (`var`). Survives CTCE. */
    data class VarDecl(val name: String, val type: TypeRef?, val initializer: Expr, val line: Int, val column: Int = 0, val annotations: List<Annotation> = emptyList(), val threadlocal: Boolean = false, val visibility: Visibility = Visibility.PUBLIC, val valueMutable: Boolean = true) : TopLevel() {
        /** Convenience: the type name as written in source, or null. */
        val typeName: String? get() = type?.displayName()
    }
    /** Runtime top-level deeply immutable binding (`fin`). Survives CTCE. */
    data class FinDecl(val name: String, val type: TypeRef?, val initializer: Expr, val line: Int, val column: Int = 0, val annotations: List<Annotation> = emptyList(), val threadlocal: Boolean = false, val visibility: Visibility = Visibility.PUBLIC) : TopLevel() {
        /** Convenience: the type name as written in source, or null. */
        val typeName: String? get() = type?.displayName()
    }
    /** Runtime top-level immutable binding (`let`). Survives CTCE. */
    data class LetDecl(val name: String, val type: TypeRef?, val initializer: Expr, val line: Int, val column: Int = 0, val annotations: List<Annotation> = emptyList(), val visibility: Visibility = Visibility.PUBLIC) : TopLevel() {
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
    data class InlineVar(val name: String, val initializer: Expr, val line: Int, val column: Int = 0, val valueMutable: Boolean = true) : TopLevel()

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
     * @property method whether this is one test or a file-level aggregate suite
     */
    data class Test(
        val name: String,
        val body: List<Stmt>,
        val line: Int,
        val column: Int = 0,
        val annotations: List<Annotation> = emptyList(),
        val method: TestMethod = TestMethod.This,
    ) : TopLevel()

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
        val visibility: Visibility = Visibility.PUBLIC,
        /** `shield pack X {}` prevents external extensions from taking `mut ref self`. */
        /** Name of the variadic type param (`T` in `pack Tuple<...T>`), or null for a fixed pack. */
        val variadicParam: String? = null,
        /** Minimum element count from a `where <var>.length >= N` clause, or null if unconstrained. */
        val minVariadicLength: Int? = null,
        /**
         * Type parameters declared as const value params (`name: Int`), e.g. `N` in
         * `pack<T, N: Int> Array`. Supplied as [TypeRef.Const] arguments at
         * instantiation and folded into dependent types.
         */
        val constParams: Set<String> = emptySet(),
    /** Default values for const parameters, e.g. `O: MatrixOrder = .RowMajor`. */
    val constDefaults: Map<String, TypeRef> = emptyMap(),
    /** Const parameter → the enum whose variants it ranges over, when it has one. */
    val constEnums: Map<String, String> = emptyMap(),
        /**
         * The declaration's `where` clause, as an ordinary expression.
         *
         * `where T is Number && N in 2..4` is kept as its parse tree so a constraint
         * can say anything an expression can. [minVariadicLength] is one reading of
         * this tree, not a separate mechanism.
         */
        val whereClause: Expr? = null,
        /** Field generator for a variadic pack body (`inline for Ty in ...T with index { … }`), or null. */
        val fieldTemplate: VariadicFieldTemplate? = null,
        /**
         * `bridge pack X` — a compiler-provided type (primitives, `Reflected<T>`).
         * No struct is emitted; it exists as a reflectable/declared type only.
         */
        val isBridge: Boolean = false,
        /**
         * `union X { … }` — a C-style untagged union.
         *
         * Every field starts at offset 0 and the whole thing is as wide as its
         * widest member, so writing one member and reading another reinterprets
         * the same storage. Nothing records which member is live: that is the
         * difference between this and `variant`, and the reason a union is only
         * as safe as the invariant the author keeps around it.
         */
        val isUnion: Boolean = false,
        /**
         * The module that declares this, or null before the parser tags it.
         *
         * A private member is reachable only from its own declaring module, so
         * the check needs to know where each side was written.
         */
        val declaringModule: String? = null
    ) : TopLevel()

    /** `deco Name [bind Spec] { fields }` — an annotation type and optional derived spec contract. */
    data class Deco(
        val name: String,
        val fields: List<PackField>,
        val line: Int,
        val column: Int = 0,
        val annotations: List<Annotation> = emptyList(),
        /** Empty means this decorator may be applied to every supported target. */
        val targets: Set<DecoTarget> = emptySet(),
        val bindings: List<DecoratorBinding> = emptyList(),
        /** Compiler-recognized contract whose semantics are provided by lowering. */
        val isBridge: Boolean = false,
    ) : TopLevel()

    /** An extern function signature inside a `bridge` block: `func sin(x: Double): Double` (no body). */
    data class BridgeSig(val name: String, val params: List<Param>, val returnType: TypeRef, val line: Int, val column: Int = 0, val typeParams: List<String> = emptyList())

    /** `bridge <target> { func sigs }` — declares extern functions for active FFI targets (C/LLVM, JS/WASM). */
    data class Bridge(val target: String, val funcs: List<BridgeSig>, val line: Int, val column: Int = 0, val annotations: List<Annotation> = emptyList()) : TopLevel()

    /** `solo Name { fields; methods }` — declares a singleton struct with one lazily-created shared instance. */
    data class Solo(val name: String, val fields: List<PackField>, val methods: List<FuncDecl>, val line: Int, val column: Int = 0, val visibility: Visibility = Visibility.PUBLIC, val annotations: List<Annotation> = emptyList()) : TopLevel()

    /** A singleton registration inside a `wrap` block: `solo Type(args) [bind Spec]`. */
    data class WrapReg(val typeName: String, val args: List<Expr>, val bindSpec: String? = null, val line: Int = 0, val column: Int = 0)

    /** `wrap Name { solo Type(args); Concrete bind Spec }` — a DI container that wires singletons. */
    data class Wrap(val name: String, val registrations: List<WrapReg>, val line: Int, val column: Int = 0) : TopLevel()

    /**
     * `import RealmName` or `import RealmName.Item` — imports items from a named realm so they're
     * accessible without the `RealmName::` prefix. [imports] is a list of (realmName, itemName)
     * pairs where itemName is null for "import all".
     *
     * When [exported] is true (written `expose use …`), the import is re-exported:
     * any module that imports this module also transitively imports [imports]. This lets
     * a library forward its dependencies (e.g. `std.char` re-exporting `std.char.core`).
     */
    data class UseImport(
        val imports: List<Pair<String, String?>>,
        val line: Int,
        val column: Int = 0,
        val exported: Boolean = false,
        /** Comptime condition on `export if COND \n import …`; null = unconditional. */
        val condition: Expr? = null,
    ) : TopLevel()

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
        /** Per-variant annotations, parallel to [variants] (e.g. `Red @Deprecated(...)`). */
        val variantAnnotations: List<List<Annotation>> = emptyList(),
    ) : TopLevel()

    /** `fail ErrSet { V1, V2 }` — an error set (a named set of error variants). */
    data class Fail(
        val name: String,
        val variants: List<String>,
        val line: Int,
        val column: Int = 0,
        val annotations: List<Annotation> = emptyList(),
        /** Per-variant annotations, parallel to [variants] (e.g. `NotFound @Deprecated(...)`). */
        val variantAnnotations: List<List<Annotation>> = emptyList(),
        /**
         * Per-variant payload types, parallel to [variants].
         *
         * `fail IndexError { OutOfBounds(index: Int, size: Int) }` carries data with
         * the error rather than only naming it. Empty for a bare variant, which is
         * still the common case.
         */
        val variantPayloads: List<List<TypeRef>> = emptyList(),
    ) : TopLevel()

    /**
     * An `impl Type { methods }` block. Each method gets an implicit `self: Type` receiver;
     * calls desugar to `Type_method(self, ...)`.
     *
     * @property typeName the declaration site being implemented. Decorator-only
     * member targets use `Owner.member`; `Owner.*` selects every pack field.
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
        /** Positional compile-time metadata values on `impl Decorator(...) for Type`. */
        val decoratorArgs: List<Expr> = emptyList(),
        /** Named compile-time metadata values on `impl Decorator(field: value) for Type`. */
        val decoratorNamedArgs: List<Pair<String, Expr>> = emptyList(),
        /** `@Deco` annotations applied to the impl block (e.g. `@UncheckedCast`). */
        val annotations: List<Annotation> = emptyList(),
        /** `bridge impl …` — compiler-provided; no IR emitted, but still registers members. */
        val isBridge: Boolean = false,
        /** Generic parameters declared directly on the implementation. */
        val typeParams: List<String> = emptyList(),
        /** Variadic implementation parameter from `impl<...T>`. */
        val variadicParam: String? = null,
        /** Lexical realm containing this implementation, used for same-realm lookup. */
        val realmPrefix: String? = null,
        /**
         * The module that declares this, or null before the parser tags it.
         *
         * A private member is reachable only from its own declaring module, so
         * the check needs to know where each side was written.
         */
        val declaringModule: String? = null
    ) : TopLevel()

    /** `spec Name { func method(params): Ret; ... }` or compact callback `spec Name<T>: T { ref self } use as "to${T.typeName}"`. */
    data class Spec(
        val name: String,
        val methods: List<FuncDecl>,
        val line: Int,
        val column: Int = 0,
        val callback: SpecCallback? = null,
        val typeParams: List<String> = emptyList(),
        /** Specs this one inherits every member from (`spec Mutable: Read {…}`). */
        val parents: List<TypeRef> = emptyList(),
        /**
         * `bridge spec` — the compiler provides the members.
         *
         * An implementor states the capability with a bodyless `impl` and only
         * writes a member when the default lowering is wrong for its type.
         */
        val isBridge: Boolean = false,
        /**
         * Specs an implementor must *also* implement (`spec Copy requires [Clone]`).
         *
         * Unlike inheritance, this adds nothing to the spec and implies nothing
         * about a conforming type: it is a precondition checked at the `impl`.
         * `impl Copy for T` is rejected unless `T` separately implements
         * `Clone` — the capability is stated, never inferred.
         */
        val requires: List<TypeRef> = emptyList(),
        /**
         * Type parameter → the type it stands for when the argument is omitted.
         *
         * `spec PartialEqual<Rhs = Self>` is what lets `impl PartialEqual for Point`
         * mean `impl PartialEqual<Point> for Point`, so the homogeneous case —
         * nearly all of them — writes no argument at all.
         */
        val typeDefaults: Map<String, TypeRef> = emptyMap(),
    ) : TopLevel()

    /** `typealias Name = Type` — a type alias. */
    data class TypeAlias(val name: String, val type: TypeRef, val line: Int, val column: Int = 0, val annotations: List<Annotation> = emptyList(), val typeParams: List<String> = emptyList()) : TopLevel()

    /** One case of a tagged union: `Name(Type1, Type2)`, or `Name` with no payload. */
    data class SlotVariant(val name: String, val payloadTypes: List<TypeRef>)

    /**
     * `variant enum Name { … }` / `variant error Name { … }` — a tagged union.
     *
     * `variant` is a modifier on the payload-free `enum`/`error` forms: it says
     * the cases may carry data. [isError] records which of the two it modified —
     * an error one can be thrown and named in a `?!` set; otherwise they are the
     * same construct.
     */
    data class Slot(
        val name: String,
        val variants: List<SlotVariant>,
        val line: Int,
        val column: Int = 0,
        val annotations: List<Annotation> = emptyList(),
        val isError: Boolean = false,
    ) : TopLevel()

    /**
     * `meta Name { arm; arm; … }` — a pattern-driven macro declaration.
     *
     * Macros are top-level, bare-name declarations: importing the module that
     * declares one (e.g. `import std.container.*` for `vec!`) makes it invocable
     * as `name!(…)`. [MacroExpander] collects every `Meta`
     * declaration, rewrites all matching [Expr.MetaInvoke] invocations into
     * their arm templates, and removes the `Meta` node itself — so it never
     * reaches semantic analysis or IR generation.
     *
     * @property name the macro name
     * @property arms the ordered pattern arms (first match wins)
     */
    data class Meta(val name: String, val arms: List<MacroArm>, val line: Int, val column: Int = 0) : TopLevel()

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
     * A top-level compile-time trace (`inline trace level { expr }`).
     *
     * @property message the message expression
     * @property level the selected compile-time `LogLevel` expression
     * @property line 1-based source line
     * @property column 1-based source column
     */
    data class InlineTrace(val message: Expr, val line: Int, val column: Int = 0, val level: Expr? = null) : TopLevel()
}

/**
 * The root of an Azora AST, representing a complete source file.
 *
 * @property moduleName the declared module name, or `null` if no `module` declaration is present
 * @property items the list of top-level items (functions and compile-time constructs)
 * @property isExported whether the module was declared `expose mod …`, making its
 *   declarations auto-imported into every unit (as `std.core` is)
 */
data class Program(
    val moduleName: String?,
    val items: List<TopLevel>,
    /**
     * Pack names owned by the source unit before stdlib injection. `impl pack`
     * uses this set to stay limited to the file that declared the pack.
     */
    val localPackNames: Set<String> = emptySet(),
    /**
     * `expose mod …` — the module's declarations are published to every
     * compilation unit that uses this library, with no explicit `import`.
     */
    val isExported: Boolean = false,
    /**
     * Module-level visibility from `[confine] mod …`
     * (default [ModuleVisibility.PUBLIC]). Bounds how far the module — and its
     * `expose` auto-import — reaches.
     */
    val moduleVisibility: ModuleVisibility = ModuleVisibility.PUBLIC,
    /**
     * Comptime condition on `export if COND \n module …` (null = unconditional
     * `expose mod`). Evaluated against config constants + CLI defines; when it
     * folds to `false`, [isExported] is cleared before stdlib indexing.
     */
    val exportCondition: Expr? = null,
    /**
     * Declaration name → the realm it was declared in, for `(reflect X).realm`.
     * Only declarations nested inside a `realm "label" { … }` (or an inline/
     * deepinline realm) appear; a name absent here is global (see [RealmMeta]).
     */
    val realms: Map<String, RealmMeta> = emptyMap(),
    /** Compile-time `type name(...)` declarations owned by this unit. */
    val typeFunctions: List<TypeFunctionDecl> = emptyList(),
    /**
     * `meta type { pattern => template }` rules declared in this unit. Parsed
     * and stored; the rewriting pass is not yet active (see [TypeTypeArm]).
     */
    val typeMacroRules: List<TypeTypeArm> = emptyList(),
    /**
     * Infix operator names declared via `meta .Infix("op")`. A free function
     * named `op` becomes callable as `a op b`. Survives macro expansion (which
     * strips the `meta` declarations themselves).
     */
    val infixOperators: Set<String> = emptySet(),
    /** Value-level infix macros from `meta .Infix("op") { $a $b => expr }`. */
    val infixMacros: List<InfixMacroRule> = emptyList(),
    /**
     * True if this unit contains any `name!…` macro invocation. Lets
     * [MacroExpander] skip the rewrite pass for programs that neither declare
     * nor use macros (the common case), while still catching undefined-macro
     * use sites with a clear error.
     */
    val usesMacros: Boolean = false,
    /**
     * Named type declaration → source-level realm path for declarations inside
     * `realm X`. Declarations in `use realm` remain bare and
     * therefore do not appear here.
     */
    val realmTypeNamespaces: Map<String, String> = emptyMap(),
) {
    /** Convenience — returns only the resolved function declarations. */
    val functions: List<FuncDecl> get() = items.filterIsInstance<TopLevel.Func>().map { it.decl }

    /** Convenience — returns only the test declarations. */
    val tests: List<TopLevel.Test> get() = items.filterIsInstance<TopLevel.Test>()
}

/**
 * The name given to a contextual receiver that a lambda inherits rather than
 * declares — `std::sequence { std::yield(1) }` names no receiver, but the
 * closure still has to carry one.
 *
 * The frontend and the IR generator both synthesize these bindings and must
 * agree on the name; deriving it from the lambda's own source position keeps
 * them in step without threading a counter between passes.
 */
fun lambdaReceiverName(line: Int, column: Int, index: Int): String =
    "__ctx${index}__${line}_$column"
