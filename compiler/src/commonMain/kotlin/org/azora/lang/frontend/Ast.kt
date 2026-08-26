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
 * Every one of them moves or borrows; none duplicates, so none asks a
 * capability of its operand - every value can be given away or lent. An
 * independent copy is `v.clone()`, which goes through `Clone` like any other
 * method.
 *
 * @property spelling how it is written in source, for diagnostics.
 */
enum class OwnershipOp(val spelling: String) {
    /**
     * `take v` - ownership transfer. Duplicates nothing, and leaves the operand
     * unusable, which is what separates it from the other two.
     *
     * It asks nothing of the operand: every value can be given away, so a
     * capability for it would say nothing.
     */
    TAKE("take"),

    /**
     * `lend v` - ownership transfer the callee gives back.
     *
     * The parameter it feeds is marked `return`, so the value comes home when
     * the call ends and the operand stays usable. It differs from a borrow in
     * that the callee genuinely owns the value while it runs, and from `take`
     * in that the caller gets it back.
     */
    LEND("lend"),

    /** `v.&` / `v&` - a shared, read-only borrow. Owns nothing. */
    SHARE("&"),

    /** `v.!` / `v!` - an exclusive, mutable borrow. Owns nothing. */
    BORROW("!"),
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
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     */
    /**
     * Integer literal.
     *
     * [text] is the exact digits, and is set only when they do not fit
     * [value]: a `Cent` or `UCent` literal is up to 128 bits wide, and the
     * value it stands for cannot be carried in a `Long`. The backends that
     * have a 128-bit integer write [text] out; the ones that do not say so
     * rather than truncating.
     */
    data class IntLiteral(val value: Long, override val line: Int, override val column: Int = 0, override val length: Int = 0, val text: String? = null) : Expr()

    /**
     * Floating-point literal expression (e.g. `3.14`, `3.14f`, `3.14D`).
     *
     * @property value the parsed double-precision value
     * @property line 1-based source line
     * @property column 1-based source column
     * @property length source text length
     * @property text the digits as written, which a `Double` cannot always hold
     */
    data class DoubleLiteral(val value: Double, override val line: Int, override val column: Int = 0, override val length: Int = 0, val text: String? = null) : Expr()

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

    /** C++-style increment/decrement. Prefix returns the new value; postfix the old value. */
    data class IncDec(
        val target: Expr,
        val op: TokenType,
        val prefix: Boolean,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
    ) : Expr()

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
     * `1: "one"` written as an argument of a macro invocation.
     *
     * Only a macro whose arm is a [MacroPattern.MapEntryCapture] can receive one:
     * `@map[1: "one"]` binds the keys and the values as two lists the
     * template splices together. The expander consumes these before any later
     * pass runs, so an entry reaching one is a macro that did not match.
     */
    data class MapEntryArg(
        val key: Expr,
        val value: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
    ) : Expr()

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
     * [nameExpr] carries a `${ … }` written in name position - `self.${f.name}`.
     * Compile-time expansion folds it into [name]; every later stage sees an
     * ordinary member access, so nothing downstream needs to know it was spliced.
     */
    /**
     * `.Name` with the type left to context - `@Log(level: .Warn)`.
     *
     * The type is already written where the value is going: a parameter's type,
     * a field's type, an annotation's field. Naming it again in the value is
     * repetition, so the leading dot says "the one that is expected here" and
     * the resolver supplies it. Unresolvable without an expected type, which is
     * an error rather than a guess.
     */
    data class InferredMember(
        val name: String,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        /**
         * Constructor arguments for `.(…)`, or null for `.Name`.
         *
         * `modifier: .().height(64.0)` - the parameter already says `Modifier`,
         * so the value need not say it again. Same rule as `.Name`, applied to
         * the type's constructor instead of one of its members.
         */
        val ctorArgs: List<Expr>? = null,
    ) : Expr()

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

    /**
     * Tuple literal `(a, b, c)` or a grouped receiver `{a, b, c}`.
     *
     * Grouped receivers keep [grouped] set while postfix operations are read;
     * the statement parser can then broadcast the complete operation pairwise
     * instead of treating the values as an ordinary tuple.
     */
    data class TupleLit(
        val elements: List<Expr>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        val grouped: Boolean = false,
        /** True for `receiver.{call(), call()}` sequencing groups. */
        val sequence: Boolean = false,
        /** The receiver expression written before a sequencing group, if any. */
        val sequenceReceiver: Expr? = null,
    ) : Expr()

    /** Variant literal `var(a, b, c)` - constructs a `Var<...>` holding exactly one of the given
     *  candidate values (the first, by default). At least two candidates are required. */
    data class VariantLit(val elements: List<Expr>, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** Tuple positional access `target.index` (e.g. `pair.0`). */
    data class TupleAccess(val target: Expr, val index: Int, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `expr catch fallback` - evaluates [expr]; if it throws, evaluates [fallback]. */
    data class CatchExpr(val expr: Expr, val fallback: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `try expr` - evaluates [expr] and propagates any failure to the caller. */
    data class TryPropagate(val expr: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * If-expression `if cond { a } else { b }` - both branches are single
     * expressions and one of them becomes the value of the whole expression.
     */
    data class IfExpr(val condition: Expr, val thenExpr: Expr, val elseExpr: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * `seal value` - a decorator field default that the application may not
     * override.
     *
     * Written on the branch of a `when` that computes the default, so the seal
     * belongs to the case rather than to the field:
     *
     *     annot @Log for .Func {
     *         fin level: LogLevel = .Info
     *         fin prefix: String = when level {
     *             .Error -> seal "!! "
     *             else -> ""
     *         }
     *     }
     *
     * One case fixes the value and the others leave the field free, which a
     * seal on the field itself could not express. The value is [value] either
     * way - the seal governs who may write the field, not what it holds.
     */
    data class Seal(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * A first-class callable value.
     *
     * [params] are ordinary call parameters. [receivers] are contextual
     * parameters which may be supplied explicitly after the ordinary
     * arguments or resolved from an enclosing `using` block.
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
        /**
         * False when the lambda wrote no parameter list at all (`{ body }`).
         *
         * A bare lambda takes the parameters of the expected callable type when
         * there is one, so the resolver has to tell "declared none" apart from
         * "declared nothing" - only the second may be given parameters.
         */
        val paramsWritten: Boolean = true,
        /** Explicit entries written in the lambda's single ownership header. */
        val captures: List<Capture> = emptyList(),
        /** `[=]` / `[&]` / `[!]` / `[take]` for otherwise unnamed free variables. */
        val captureDefault: CaptureMode? = null,
        /** Hard capture fences written as `without name` in the ownership header. */
        val captureExclusions: List<CaptureExclusion> = emptyList(),
        /** Generic parameters declared by `<T, U>` before the receiver/capture list. */
        val typeParams: List<String> = emptyList(),
        /** The variadic generic pack from `<...T>`, when present. */
        val variadicTypeParam: String? = null,
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

    /** `a ?? b` - returns `a` if non-null, else `b`. */
    data class NullCoalesce(val left: Expr, val right: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `a?.field` - safe member access; returns null if `a` is null. */
    data class SafeMember(val target: Expr, val name: String, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * A type cast. Surface sugar: `x as T` ([CastKind.STATIC], = `cast<T>(x)`),
     * `x as? T` ([CastKind.DYNAMIC], = `dyncast<T>(x)`, result `T?`), and
     * `x as* T` ([CastKind.REINTERPRET], = `bitcast<T>(x)`).
     */
    data class Cast(val expr: Expr, val targetType: TypeRef, val kind: CastKind = CastKind.STATIC, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `expr is Type` - runtime type check, returns Bool. */
    data class IsCheck(val expr: Expr, val typeName: String, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * `value in collection` - membership.
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

    /** `alloc <expr>` - heap-allocate a value and return a pointer to it. */
    /** `alloc* value` → `T*` (read-only), `alloc^ value` → `T^` (mutable). */
    data class Alloc(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0, val mutable: Boolean = false) : Expr()


    /** `*ptr` - dereference a pointer. */
    data class Deref(val target: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /**
     * An ownership operation on [value]: `take v`, `lend v`, or a borrow sigil.
     *
     * They are one node because they differ only in what they require of the
     * operand and what they do to the caller's claim on it - the shape is
     * identical, and every pass that merely walks the tree should treat them
     * alike.
     *
     * Duplication is not among them: an independent copy is `v.clone()`, an
     * ordinary method call on `Clone`.
     */
    data class Isolated(
        val value: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        val op: OwnershipOp,
    ) : Expr()

    /** `await task` - suspend until the task completes and yield its result. */
    data class Await(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Expr()

    /** `inject Type` - resolve an instance of [typeName] from the active DI graph. */
    data class Inject(
        val typeName: String,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
    ) : Expr()

    /** `arr...` - spread an array's elements as individual call arguments. */
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
/**
 * `.(args) * count` - the construction and the count it is repeated by, or null.
 *
 * Only the *shape* is read here. Whether the left side really constructs
 * something - rather than being an ordinary call whose result is multiplied - is
 * a question for a pass that knows what names mean, so each asks its own tables
 * before treating the answer as a repetition.
 */
fun asRepeatedConstruction(expr: Expr.Binary): Pair<Expr, Expr>? {
    if (expr.op != TokenType.STAR) return null
    val left = expr.left
    val constructs = left is Expr.Call || left is Expr.InferredMember ||
        (left is Expr.Alloc && (left.value is Expr.Call || left.value is Expr.InferredMember))
    return if (constructs) left to expr.right else null
}

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
     * The four binding keywords vary two independent axes - whether the *name*
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
     * @property valueMutable false for `val` - the value cannot be mutated or
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
        val valueMutable: Boolean = true,
        /** `lazy` delays initialization until the binding's first read. */
        val lazy: Boolean = false,
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
        override val length: Int = 0,
        /** `lazy` delays initialization until the binding's first read. */
        val lazy: Boolean = false,
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
        override val length: Int = 0,
        /** `lazy` delays initialization until the binding's first read. */
        val lazy: Boolean = false,
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
         * type declared an in-place `oper+=` and call it instead - otherwise a
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
        /** Explicit opt-in boundary for operations whose contracts cannot be proven safe. */
        val unsafe: Boolean = false,
        /**
         * True for a scope the source actually wrote.
         *
         * Sibling written scopes share one scope, so a binding made in the first
         * is visible in the second. The compiler also builds scopes of its own -
         * to scope an inlined body, an `unsafe { }` block, a desugared for-else
         * - and those must stay independent, or two inlined calls would collide
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
        /** Optional source label for `break:label`/`continue:label`. */
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
        /** Optional source label for `break:label`/`continue:label`. */
        val label: String? = null,
        /**
         * `for i: Int in 0..mid` - the type the row is declared to have.
         *
         * A loop variable is a binding like any other, and a binding may say
         * what it holds instead of leaving a reader to work it out from the
         * thing being walked. Null is the unannotated form, which infers.
         */
        val declaredType: TypeRef? = null,
        /** Optional ordinal binding from `with index` (`0, 1, 2, …`). */
        val indexName: String? = null,
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
        /** Optional source label for `break:label`/`continue:label`. */
        val label: String? = null,
        /** Optional iterable for `loop iterable { }` - when present, desugars to
         *  `iterable.reset(); while iterable.hasNext() { body }`. */
        val iterable: Expr? = null,
        /**
         * `loop xs by 5.seconds { … }` - seconds to wait between passes.
         *
         * The loop never ends: it walks the iterable, waits, and walks it again,
         * so what it sees is re-read each pass rather than captured once. Only
         * an async function may write one, because waiting means suspending -
         * in a plain function it would block the caller instead.
         */
        val everySeconds: Expr? = null
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
     * `break` statement. Exits the enclosing loop. With a label (`break:lbl`)
     * it exits the loop tagged with that label, skipping any inner loops.
     *
     * A value (`break result`) is only legal in a `for` expression. The
     * parser keeps that value on the AST long enough for [Parser.parseForExpr]
     * to lower it to an assignment followed by an ordinary break; no backend
     * needs a second kind of jump.
     *
     * @property label the target label, or `null` for the innermost loop
     * @property value the value produced by a `for` expression, or `null`
     */
    data class Break(
        val label: String? = null,
        override val line: Int = 0,
        override val column: Int = 0,
        override val length: Int = 0,
        val value: Expr? = null,
    ) : Stmt()

    /**
     * `continue` statement. Skips to the next iteration of the enclosing loop.
     * With a label (`continue:lbl`) it targets the loop tagged with that label.
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
         * type declared an in-place `oper+=` and call it instead - otherwise a
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

    /** `throw value` - raises [value] as a throwable. */
    data class Throw(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /**
     * `panic "msg"` - unrecoverable runtime abort with [message].
     * `inline panic "msg"` ([inlinePanic]) - if reached during compile-time evaluation,
     * aborts the compiler with [message].
     */
    data class Panic(val message: Expr, val inlinePanic: Boolean, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /** `*ptr = value` - store through a pointer. */
    data class DerefAssign(val target: Expr, val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /** `yield value` - emit a value from a `flow` generator. */
    data class Yield(val value: Expr, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /**
     * `try { body } catch { name -> handler }`.
     *
     * @property body the protected statements
     * @property catchName the binding name for the caught value, or `null` if none
     * @property catchBody the handler statements, or `null` if the try has no catch
     */
    data class Try(val body: List<Stmt>, val catchName: String?, val catchBody: List<Stmt>?, override val line: Int, override val column: Int = 0, override val length: Int = 0) : Stmt()

    /** `defer { body }` - runs [body] when the enclosing function exits. */
    data class Defer(val body: List<Stmt>, override val line: Int, override val column: Int = 0, override val length: Int = 0, val onFail: Boolean = false, val suppress: Boolean = false) : Stmt()

    /** `remember|retain|preserve <var|val|let|fin> x: T = init` - reactive state. */
    data class RemDecl(
        val name: String,
        val type: TypeAnnotation,
        val initializer: Expr,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        val kind: ReactiveKind = ReactiveKind.REMEMBER,
        /** The binding kind written after the lifetime: `var`, `val`, `let` or `fin`. */
        val binding: BindingKind = BindingKind.VAR,
    ) : Stmt()

    /** `effect { body }` - reactive side-effect; re-runs when tracked `rem` variables change. */
    data class Effect(
        val body: List<Stmt>,
        override val line: Int,
        override val column: Int = 0,
        override val length: Int = 0,
        /** Null means automatic dependency tracking; an empty list is explicit. */
        val dependencies: List<Expr>? = null,
        /** `effect defer { ... }` runs at reactive-owner disposal. */
        val deferred: Boolean = false,
        /**
         * `effect done == true { … }` - a rising-edge condition.
         *
         * Distinct from a dependency: a dependency says *when to reconsider*,
         * while a condition also says *whether to act*. The body runs on the
         * transition to true and not again until the condition has gone false,
         * so `effect closing == true { exit() }` fires once rather than on every
         * evaluation while closing.
         */
        val condition: Expr? = null,
    ) : Stmt()

    /** `using value { ... }` / `using (a, b) { ... }` contextual receiver scope. */
    data class UsingContext(
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

    companion object {
        /**
         * `add<Short, _, Long>(…)` - the argument the caller left to inference.
         *
         * A hole says about one position what an unwritten list says about
         * every position: work it out from the argument. It is a [Named] rather
         * than a shape of its own because nothing may ever *resolve* it - every
         * reader of a type-argument list drops it first, and a hole that
         * reached a backend would be a bug with a name.
         */
        const val HOLE = "_"
    }

    /** Whether this is the hole `_`, which stands for an argument not written. */
    val isHole: Boolean get() = this is Named && name == HOLE

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
     * [qualifier] preserves the source-level scope path (`std` in
     * `Tuple<Int, String>`). Semantic passes still use [name] as the
     * canonical declaration name, while visibility checks can distinguish a
     * qualified type reference from a bare one.
     */
    data class Named(
        val name: String,
        val args: List<TypeRef> = emptyList(),
        val variadic: Boolean = false,
        val qualifier: String? = null,
        /**
         * True when the compiler produced this reference rather than an author.
         *
         * An untyped lambda parameter (`{ x, y -> … }`) is given `Any` as a
         * placeholder until the expected callable type supplies its real type.
         * Nobody wrote it, so source-level rules - scope qualification above all
         * - must not be applied to it.
         */
        val synthesized: Boolean = false,
        /**
         * Value arguments of a compile-time type call - `T<U>(flag, other)`.
         *
         * A `deepinline prop` may take values as well as types, because what it
         * computes can depend on an answer that is not itself a type: whether a
         * parameter has a default is a `Bool`, and the type of that default
         * depends on it. Empty for every ordinary type reference.
         */
        val valueArgs: List<Expr> = emptyList(),
        /** Exact written name token, absent for compiler-synthesized references. */
        val line: Int = 0,
        val column: Int = 0,
        val length: Int = 0,
    ) : TypeRef() {
        override fun toString() = when {
            TypeFunctionCall.isCall(this) -> "${TypeFunctionCall.name(this)}<${args.joinToString(", ")}>"
            args.isEmpty() -> qualifiedName()
            else -> "${qualifiedName()}<${args.joinToString(", ")}>"
        }

        private fun qualifiedName(): String = qualifier?.let { "$it::$name" } ?: name

        /*
         * A qualifier is source-level access metadata, not part of type
         * identity. `Tuple<Int, Int>` must resolve to the same semantic
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
        /**
         * `escaping (Event) -> Unit` - the callable may outlive the call it is
         * passed to, so whatever it captures must be owned.
         *
         * It belongs to the type rather than to the parameter, which is what lets
         * it describe a pack field or a collection element as well as an argument
         * (LAMBDA_CONTEXT_CAPTURE_DIP.MD §4.7).
         */
        val isEscaping: Boolean = false,
        /**
         * `inline (Item) -> Unit` - the callable is substituted at the call it
         * is passed to rather than called through.
         *
         * A block written at the call site costs what writing it there directly
         * would: `rows.forEach { … }` is the loop it stands for, not a call per
         * row. It belongs to the type because that is what the caller has to
         * satisfy - a callable that is not a literal block cannot be inlined.
         */
        val isInline: Boolean = false,
    ) : TypeRef() {
        override fun toString(): String {
            val prefix = if (kind == CallableKind.FUNC) "" else "${kind.surfaceName} "
            val context = when (receivers.size) {
                0 -> ""
                1 -> "${receivers.single()}."
                else -> receivers.joinToString(", ", "(", ").")
            }
            val arguments = params.joinToString(", ", "(", ")")
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

    /** Pointer type `T*` - a reference to a heap value of [inner]. */
    /**
     * Pointer type `T*` (read-only) or `T^` (mutable).
     *
     * The sigil is the whole difference and it is carried at every site - the
     * type, the allocation that produced it, and the dereference that reads it -
     * so a mutation through a pointer is visible without consulting the
     * declaration.
     */
    data class Pointer(val inner: TypeRef, val mutable: Boolean = false) : TypeRef() {
        override fun toString() = "$inner*"
    }

    /**
     * A compile-time integer used as a **const-generic type argument** - the `3` in
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
     * [origins] names the parameters a returned borrow comes from - the `|a, b|`
     * in `func choose(a: String&, b: String&): String&|a, b|`. Azora infers most
     * borrow relationships, so this is written only where a public signature has
     * to state one; empty means "inferred".
     */
    data class Reference(
        val kind: RefKind,
        val inner: TypeRef,
        val origins: List<String> = emptyList(),
    ) : TypeRef() {
        /**
         * The borrow as the language spells it: postfix `&`, or `!` when
         * exclusive.
         *
         * This is the spelling an author writes, so it is the spelling a
         * rendered type carries - into diagnostics, and into anything that
         * reads a type's text back.
         */
        private val sigil: String get() = if (kind == RefKind.MUTABLE) "!" else "&"

        override fun toString() =
            if (origins.isEmpty()) "$inner$sigil"
            else "$inner$sigil|${origins.joinToString(", ")}|"
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
 * A compile-time type property (`deepinline prop<...T> Name: Type { … }`).
 *
 * A type property receives types and returns a [TypeRef]. It is erased before
 * IR generation and can therefore never be called at runtime; a use site spells
 * it exactly like a generic type (`promote<T, U>`).
 */
data class TypeFunctionDecl(
    val name: String,
    val params: List<TypeFunctionParam>,
    /**
     * Value parameters - `prop<T> Name(hasDefault: Bool): Type`.
     *
     * A type can depend on an answer that is not itself a type: whether a
     * parameter has a default is a `Bool`, and what type its default has depends
     * on that. Empty for a type property that takes only types.
     */
    val valueParams: List<Param> = emptyList(),
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
    /** `if COND { … } [else { … }]` - a branch over a type comparison. */
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
    /** `T?` - the nullable form of another type expression. */
    data class Nullable(val inner: TypeFunctionExpr) : TypeFunctionExpr()
    data class PackElement(val packName: String, val index: Int) : TypeFunctionExpr()
    data class Call(val name: String, val args: List<TypeFunctionExpr>) : TypeFunctionExpr()
    data class Conditional(
        val condition: TypeFunctionCondition,
        val thenValue: TypeFunctionExpr,
        val elseValue: TypeFunctionExpr,
    ) : TypeFunctionExpr()
}

/**
 * A test used by a [TypeFunctionExpr.Conditional].
 *
 * Either a comparison between two type expressions, or - when [valueFlags] is
 * non-empty - a conjunction of the type function's own `Bool` parameters, which
 * is what lets a computed type depend on an answer that is not a type:
 * `hasDefault && isNullable => T?`.
 */
data class TypeFunctionCondition(
    val left: TypeFunctionExpr,
    val operator: TokenType,
    val right: TypeFunctionExpr,
    val compareRank: Boolean,
    /** `Bool` value parameters that must all hold; empty for a type comparison. */
    val valueFlags: List<String> = emptyList(),
    /** Whether each flag is required true or false (`!flag`), positionally. */
    val flagsExpected: List<Boolean> = emptyList(),
)

/** Internal encoding for a deferred `Name<...>` type-property call inside a [TypeRef]. */
object TypeFunctionCall {
    private const val PREFIX = "__azora_type_function__"

    fun create(name: String, args: List<TypeRef>, valueArgs: List<Expr> = emptyList()): TypeRef.Named =
        TypeRef.Named(PREFIX + name, args, valueArgs = valueArgs)
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

/** Encodes a source path containing both an owning module and a scope-qualified symbol. */
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
 * unless something says otherwise. [CONFINED] narrows it to the declaring
 * package. A single leading underscore marks declarations private where that
 * declaration kind supports private visibility.
 */
/**
 * How far a declaration reaches, and whether it arrives without being asked for.
 *
 * These are two independent axes, which is why they combine:
 *
 * ```azora
 * exposed func api() { … }           // importable everywhere - the default reach
 * protected func internal() { … }     // importable within the declaring folder
 * confined func helper() { … }       // private to this compilation unit
 * exposed protected func shared() { }  // auto-imported, bounded to the folder
 * exposed confined func local() { }   // auto-available, never leaves this unit
 * ```
 *
 * [reach] bounds how far the declaration can be seen at all. [isExposed] says it
 * is published into that scope with no explicit `import`. `exposed` alone selects
 * the default reach, which is why writing it changes nothing on its own.
 */
data class Visibility(
    val reach: Reach = Reach.PUBLIC,
    val isExposed: Boolean = false,
) {
    /** How far a declaration can be seen. */
    enum class Reach { PUBLIC, PROTECTED, CONFINED }

    companion object {
        val PUBLIC = Visibility(Reach.PUBLIC)
        val PROTECTED = Visibility(Reach.PROTECTED)
        val CONFINED = Visibility(Reach.CONFINED)
    }
}

/**
 * Visibility of a whole module (`[exposed] [confined] mod x`).
 *
 * - [EXPOSED] (default): importable everywhere, including downstream libraries.
 * - [PROTECTED]: importable only within the declaring folder.
 * - [CONFINED]: private - not importable anywhere (e.g. a test file or an app's
 *   `main` module).
 *
 * Orthogonal to `export` (see [Program.isExported]): `exposed` auto-imports the
 * module into every unit within its visibility scope. `exposed confined` is
 * contradictory and rejected at parse time.
 */
enum class ModuleVisibility { PUBLIC, CONFINED }

/**
 * Compile-time metadata for a declaration's enclosing scope, surfaced by
 * `(reflect X).scope`. The global (top-level) scope is a scope with [label]
 * `"global"`, [isInline] `false`, and no [parent].
 *
 * @property label the scope's string label (`scope "my scope" { … }`), or null for
 *   an unlabeled `scope { … }`; `"global"` for the top-level scope.
 * @property isInline whether the scope is `inline`/`deepinline`, or is nested in
 *   one (inline-ness is inherited by nested scopes).
 * @property parent the enclosing scope, or null at the global scope.
 */
data class ScopeMeta(val label: String?, val isInline: Boolean, val parent: ScopeMeta? = null)

/**
 * Kind of a type cast ([Expr.Cast]).
 *
 * - [STATIC]: converting cast (`static_cast`) - numeric conversions, stringify to
 *   `String`, unchecked up/down casts. Spelled `x as T` / `cast<T>(x)`.
 * - [DYNAMIC]: runtime-checked downcast (`dynamic_cast`) yielding `T?` (null on a
 *   type mismatch). Spelled `x as? T` / `dyncast<T>(x)`.
 * - [REINTERPRET]: bit reinterpretation (`reinterpret_cast`), representation-
 *   preserving. Spelled `x as* T` / `bitcast<T>(x)`.
 */
enum class CastKind { STATIC, DYNAMIC, REINTERPRET }

/** Which binding form follows a reactive lifetime: `var`, `val`, `let`, `fin`. */
enum class BindingKind(val spelling: String, val nameRebindable: Boolean, val valueMutable: Boolean) {
    VAR("var", nameRebindable = true, valueMutable = true),
    VAL("val", nameRebindable = true, valueMutable = false),
    LET("let", nameRebindable = false, valueMutable = true),
    FIN("fin", nameRebindable = false, valueMutable = false),
}

/**
 * How long a reactive binding's value outlives the thing that declared it.
 *
 * A ladder, weakest first. Each rung survives everything the one below it does.
 */
enum class ReactiveKind(val spelling: String) {
    /** Survives reactive reruns. */
    REMEMBER("remember"),

    /** Survives owner recreation in the same process. */
    RETAIN("retain"),

    /** Serialized and restored across process or application restarts. */
    PRESERVE("preserve"),
}

/** Surface callable families supported by first-class lambda values. */
/**
 * How a lambda captures a binding from the scope around it.
 *
 * See LAMBDA_CONTEXT_CAPTURE_DIP.MD §4.2. Each spelling is the one the operation
 * already has elsewhere: `take` is a prefix keyword, `clone` a method, and `&`
 * / `!` the call-site borrow sigils.
 */
enum class CaptureMode(val spelling: String) {
    /** `[value]` / `[=]` - an independent copy, taken when the closure is created. */
    COPY("="),

    /** `[value.&]` / `[&]` - a shared reference to the original binding. */
    SHARED("&"),

    /** `[value.!]` / `[!]` - a mutable reference to the original binding. */
    MUTABLE("!"),

    /** `[value.clone()]` - an independent value, cloned when the closure is created. */
    CLONE("clone()"),

    /** `[take value]` / `[take]` - ownership moves into the closure. */
    MOVE("take"),
}

/**
 * One capture entry in a lambda's bracket list.
 *
 * @property name the name the body uses - the alias where one was written
 *   (`[ownedMessage = message.clone()]`), otherwise [source]
 * @property source the outer binding being captured
 */
data class Capture(
    val name: String,
    val source: String,
    val mode: CaptureMode,
    val line: Int,
    val column: Int = 0,
)

/** One source binding barred from a lambda's usage-driven default capture. */
data class CaptureExclusion(
    val source: String,
    val line: Int,
    val column: Int = 0,
)

enum class CallableKind(val surfaceName: String) {
    FUNC(""),
    TASK("async"),

    /**
     * `react (A) -> R` - a callable that may use reactive state.
     *
     * A builder that runs a block written by its caller cannot know whether that
     * block reads `remember` or declares an `effect`, so the *type* says it may.
     * A plain callable satisfies a reactive one: a block that uses no reactive
     * feature is a valid reactive block, and requiring `react` at every call
     * site would make the annotation noise rather than information.
     */
    REACT("react"),

    /** `react async (A) -> R` - reactive and suspending at once. */
    REACT_TASK("react async"),
    ;

    /** Whether a callable of [other] can be passed where this kind is wanted. */
    fun accepts(other: CallableKind): Boolean = when (this) {
        REACT -> other == FUNC || other == REACT
        REACT_TASK -> other == TASK || other == REACT_TASK || other == FUNC || other == REACT
        else -> this == other
    }
}

/** Test execution mode mirrored by the compiler-predefined `TestMethod` enum. */
enum class TestMethod { This, All }

/**
 * How a parameter or receiver borrows its argument.
 *
 * Written as a postfix sigil on the name - `x&` borrows for reading, `x!`
 * borrows exclusively - so none of these has a keyword behind it. [NONE] is a
 * plain by-value parameter.
 */
enum class ParamModifier {
    NONE,
    /** `x&` - shared, read-only. */
    SHARED,
    /** `x!` - exclusive; the callee may write through it. */
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
     * `name: return T` - ownership of this parameter goes back to the caller.
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
    /**
     * `unsafe fin data: T*` - the field is readable only inside an unsafe scope.
     *
     * A raw pointer beside a safe API is the case this exists for: `Array` can
     * expose its storage without every reader inheriting the obligation that
     * comes with it.
     */
    val isUnsafe: Boolean = false,
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
    /** `repl func` - overrides a parent node's method. */
    val isOverride: Boolean = false,
    /** `virt func` - virtual method (dynamic dispatch). */
    val isVirtual: Boolean = false,
    /** `async func name(…)` / `async prop name` - a structured asynchronous declaration. */
    val isTask: Boolean = false,
    /** `react func` - a reactive owner (see REACTIVE_DIP.MD). */
    val isReactive: Boolean = false,
    /** Declaration requires an explicit unsafe calling context. */
    val isUnsafe: Boolean = false,
    /** Visibility exported to import/member access rules. */
    val visibility: Visibility = Visibility.PUBLIC,
    /**
     * False when the declaration wrote no return type at all.
     *
     * An omitted return type means `Unit`, so [returnType] is already correct -
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
     * Whether the declaration *wrote* a receiver (`func &.f()`).
     *
     * [receiverModifier] and [receiverName] always hold a value, so they cannot
     * answer this - and inside an `impl` the answer is what separates a member of
     * a *value* from a member of the *type*: `func make(): Arena` is
     * `Arena.make()`, `func &.size(): Int` is `arena.size()`.
     */
    val declaresReceiver: Boolean = true,
    /**
     * Extension receiver: `func<T> T&.m(): R`. When present, the
     * function is an extension method on the receiver's type (callable as
     * `value.m()` or inside `using value { m() }`). The param's type carries the
     * borrow (`Type&` → `ref`, `Type!` → `mut ref`).
     */
    val extensionReceiver: Param? = null,
    /** Name of the variadic type param (`T` in `func<...T> name`), or null for a fixed function. */
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
    /**
     * How many leading [params] the call site does not write.
     *
     * A callable may name contextual receivers beyond its own - `func (Self&, Scope&).render()`
     * - and those arrive as leading parameters filled from the `using` block or
     * receiver lambda the call sits in. Zero for everything else.
     */
    val contextualParams: Int = 0,
    /**
     * `ctor .(…) * count` - the ctor a repeated construction selects.
     *
     * The repetition arrives as a trailing `count` parameter, so without this a
     * repeated ctor is indistinguishable from an ordinary one of the same arity
     * and the two collide on one symbol.
     */
    val isRepeated: Boolean = false,
    /** How this declaration may be invoked when registered as an impl member. */
    val memberCallStyle: MemberCallStyle = MemberCallStyle.NORMAL,
    /**
     * A spec member's call-site alias template - `use as "to${T.typeName}"`.
     *
     * The member keeps its own canonical name (`into`), and an implementation is
     * additionally reachable under the expanded template (`.toString`). Written
     * on the member rather than the spec because it is the member that gets the
     * second name.
     */
    val useAsTemplate: String? = null,
    /**
     * A free function reachable as an infix call, registered by a bodyless
     * infix macro (`macro $a @to $b`):
     * callable as an infix method on any receiver type (`a to b` → `to(a, b)`).
     * The first param is the receiver (`self`).
     */
    val isUniversalInfix: Boolean = false,
    /**
     * `deepinline` rather than `inline` - evaluated through, not just at, the
     * call site. Only a `deepinline` member may hand back a `Type`.
     */
    val isDeepInline: Boolean = false
)

enum class MemberCallStyle {
    NORMAL,
    PROPERTY,
    METHOD,

    /**
     * A property reached through the type rather than a value - `Type::name`.
     *
     * A spec requirement written without a receiver (`prop rank: Int`) asks for one
     * of these, and a receiver-free member in `impl Spec for Type { … }` supplies it.
     */
    STATIC_PROPERTY,

    /**
     * A method reached through the type rather than a value - `Type::name(args)`.
     *
     * A spec requirement written without a receiver (`func from(value: T): Self`)
     * asks for one of these, and `impl Spec for Type { … }` supplies it. It is
     * the only shape a *constructing* conversion can have: `From` builds a value,
     * so there is no `self` to build it from.
     */
    STATIC_METHOD,
}

/**
 * The delimiter a macro arm was *written* with: `()` ([PAREN]), `[]`
 * ([BRACKET]), or `{}` ([BRACE]). Stored for diagnostics only - macro arms
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
    /** `[]` / `()` / `{}` - matches an invocation that passed no arguments. */
    object Empty : MacroPattern()
    /** `(...$name)` / `[...$name]` / `{...$name}` - matches ≥1 arg, binding the full list to [name]. */
    data class SeqCapture(val name: String) : MacroPattern()
    /** `($name: String)` - matches one argument of [type], binding it to [name]. */
    data class TypedCapture(val name: String, val type: TypeRef) : MacroPattern()
    /**
     * `[...${key: value}]` - matches ≥1 `k: v` argument pair (each passed as an
     * [Expr.MapEntryArg]), binding the key exprs to [keyName] and the value exprs
     * to [valueName] so a template can splice them (e.g. `...mapEntry($key, $value)`).
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
data class MacroArm(
    val delimiter: MacroDelimiter,
    val pattern: MacroPattern,
    val template: Expr,
    /** Additional adjacent declaration fragments produced by this arm. */
    val templateTail: List<Expr> = emptyList(),
)

/**
 * A value-level infix macro arm from `meta .Infix("op") { $a $b => template }`.
 * A use `x op y` rewrites to [template] with `$a`→x and `$b`→y.
 */
data class InfixMacroRule(val op: String, val left: String, val right: String, val template: Expr)

/**
 * Which built-in type-sugar form a [TypeTypeArm] pattern matches. Used by the
 * (staged) type-macro rewriting pass to map a source form to its template.
 */
enum class TypeFormKind { ARRAY, ARRAY_SIZED, SET, MAP, TUPLE, PREFIX, PREFIX_LIST, INFIX }

/**
 * One arm of a type-macro declaration: when a type
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
    /**
     * Whether the last hole takes the remaining arguments as a list.
     *
     * `[...$ITEMS] with [...$WITH]` binds `$ITEMS` to the fetch list and
     * `$WITH` to every type in the second operand, however many were written.
     */
    val listTail: Boolean = false,
    /**
     * The words joining the arm's operands - `with`, `without` - in order.
     *
     * They belong to the macro's own grammar, not to the language: an arm says
     * which words it accepts and in what order, so a library defines the syntax
     * it reads with rather than the parser blessing any of it. Empty for an arm
     * that takes a single operand.
     */
    val keywords: List<String> = emptyList(),
    /**
     * Whether each operand is a bracketed list, positionally.
     *
     * What separates `with $T` from `with [...$ITEMS]`: the use site says which
     * by writing brackets or not, and the arm that said the same thing is the
     * one meant.
     */
    val holeIsList: List<Boolean> = emptyList(),
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
    /** Source scope path (`std` in `@Serializable`), if explicitly qualified. */
    val qualifier: String? = null,
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
    Field, Param, Var, Let, Val, Fin, Test, Module,
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
// Imports
// ---------------------------------------------------------------------------

/**
 * One clause of an `import` statement.
 *
 * ```
 * import std.io.*                             All
 * import std.math.abs                         Path
 * import std.container::{list::*, map::*}     Group
 * import std.x::{*, without (Y, Z)}            All + without
 * import std.container::{list::*, map::*} as std alias
 * ```
 *
 * A [Selector.Group] member carries its own fully-joined [path], so a group nests
 * to any depth without the reader having to track a base path down the tree.
 *
 * @property path the dotted module path this clause selects from
 * @property selector what is taken from [path]
 * @property without symbols removed from a wildcard; only meaningful with
 *   [Selector.All], which the validator enforces
 * @property alias the `as Name` view this clause contributes to, or null. An alias
 *   names an import view rather than a module: several clauses may share one, and
 *   their symbols merge into it.
 */
data class ImportSpec(
    val path: String,
    val selector: Selector,
    val without: List<String> = emptyList(),
    val alias: String? = null,
    val line: Int = 0,
    val column: Int = 0,
) {
    /** What an [ImportSpec] takes from its [ImportSpec.path]. */
    sealed class Selector {
        /** `path.*` - every symbol below the path. */
        object All : Selector()

        /**
         * `path` - a dotted path naming either a module or a single symbol
         * inside one; which it is only the module graph knows, so the choice
         * belongs to the semantic passes rather than the parser.
         */
        object Path : Selector()

        /** `path.[a, b.*]` - a group; each member carries its own full path. */
        data class Group(val members: List<ImportSpec>) : Selector()
    }

    /**
     * Compatibility `(path, selector)` pairs, where the second element is `"*"`
     * for a wildcard and null for a dotted path. This view intentionally cannot
     * carry [without]; semantic import resolution uses [leaves] instead.
     */
    fun flatten(): List<Pair<String, String?>> = when (selector) {
        is Selector.All -> listOf(path to "*")
        is Selector.Path -> listOf(path to null)
        is Selector.Group -> selector.members.flatMap { it.flatten() }
    }

    /**
     * Leaf clauses with metadata intact. Unlike [flatten], this preserves
     * wildcard exclusions for import resolution and transitive re-exports.
     */
    fun leaves(): List<ImportSpec> = when (selector) {
        is Selector.All, is Selector.Path -> listOf(this)
        is Selector.Group -> selector.members.flatMap { it.leaves() }
    }

    companion object {
        /** Rebuilds a spec from one flattened pair. */
        fun of(path: String, selector: String?): ImportSpec =
            ImportSpec(path, if (selector == "*") Selector.All else Selector.Path)
    }
}

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
         * `bridge pack X` - a compiler-provided type (primitives, `Reflected<T>`).
         * No struct is emitted; it exists as a reflectable/declared type only.
         */
        val isBridge: Boolean = false,
        /**
         * `bridge pack Byte(IntLiteral)` - the literal this type is written as.
         *
         * A primitive is not built, it is *written*: `4` is already an
         * `IntLiteral`, and `Byte`, `Int` and `Long` are the widths that
         * literal can be read at. Naming one - `Byte(4)` - says which, and
         * means exactly what the bare literal means. Only a `bridge pack` may
         * say this: it is how the compiler's own types are declared, and
         * nothing a library writes is a literal.
         */
        val literalKind: String? = null,
        /**
         * `union X { … }` - a C-style untagged union.
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
        val declaringModule: String? = null,
        /** Exact backend type name when it differs from [name]. */
        val foreignName: String? = null,
        /** Deferred declaration-name macro for a bridge pack. */
        val nameMacro: Expr.MetaInvoke? = null,
        /** Scope enclosing a deferred local name. */
        val localScope: String? = null,
    ) : TopLevel()

    /** `annot @Name [binds Spec] { fields }` - an annotation type and optional derived spec contract. */
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
    data class BridgeSig(
        /** Backend symbol until [nameMacro] is expanded. */
        val name: String,
        val params: List<Param>,
        val returnType: TypeRef,
        val line: Int,
        val column: Int = 0,
        val typeParams: List<String> = emptyList(),
        /** Local Azora wrapper name when the backend symbol differs. */
        val localName: String? = null,
        /** Declaration-position macro whose string result supplies [name]. */
        val nameMacro: Expr.MetaInvoke? = null,
        /** Scope enclosing a deferred local name. */
        val localScope: String? = null,
    )

    /** An exported bridge global (`fin`, `let`, or `var`). */
    data class BridgeValue(
        val name: String,
        val type: TypeRef,
        val initializer: Expr,
        val mutable: Boolean,
        val isLet: Boolean,
        val line: Int,
        val column: Int = 0,
        val foreignName: String? = null,
        val nameMacro: Expr.MetaInvoke? = null,
        val localScope: String? = null,
    )

    /** `bridge <target> { func sigs }` - declares extern functions for active FFI targets (C/LLVM, Wasm). */
    data class Bridge(
        val target: String,
        val funcs: List<BridgeSig>,
        val line: Int,
        val column: Int = 0,
        val annotations: List<Annotation> = emptyList(),
        val values: List<BridgeValue> = emptyList(),
    ) : TopLevel()

    /** `solo pack Name { fields; methods }` - declares a singleton struct with one lazily-created shared instance. */
    data class Solo(val name: String, val fields: List<PackField>, val methods: List<FuncDecl>, val line: Int, val column: Int = 0, val visibility: Visibility = Visibility.PUBLIC, val annotations: List<Annotation> = emptyList()) : TopLevel()

    /** A singleton registration inside a `graph` block: `solo Type(args) [binds Spec]`. */
    /**
     * How long a provider's value lives.
     *
     * The lifetime is the first word of a `graph` entry, and it is the whole
     * difference between the forms: a graph owns one [SOLO], hands out a fresh
     * [FACTORY] value each time, and keeps one [SCOPE] value per active scope.
     */
    enum class ProviderLifetime(val spelling: String) {
        SOLO("solo"),
        FACTORY("factory"),

        /**
         * One value per active scope.
         *
         * Spelled `scoped`, not `scope`: a `scope` is a namespace, and a lifetime
         * that borrowed the same word would put two unrelated meanings on it.
         */
        SCOPED("scoped"),
    }

    /**
     * One entry in a `graph`: `<lifetime> Type(args) [binds Spec]`.
     *
     * @property bindSpecs the specs this definition also answers injections of.
     */
    data class GraphReg(
        val typeName: String,
        val args: List<Expr>,
        val bindSpecs: List<String> = emptyList(),
        val lifetime: ProviderLifetime = ProviderLifetime.SOLO,
        val line: Int = 0,
        val column: Int = 0,
    )

    /**
     * `graph Name [includes [A, B]] { <lifetime> Type(args) [binds Spec] … }` - a
     * dependency graph.
     *
     * @property included graphs whose definitions this one also contains.
     */
    data class Graph(
        val name: String,
        val registrations: List<GraphReg>,
        val line: Int,
        val column: Int = 0,
        val included: List<String> = emptyList(),
    ) : TopLevel()

    /**
     * `import path` - one import statement, holding one [ImportSpec] per
     * comma-separated clause.
     *
     * [imports] is the legacy flattened `(path, selector)` compatibility view;
     * [specs] is the structure semantic passes must use so wildcard exclusions
     * and future clause metadata are not discarded.
     *
     * When [exported] is true (written `exposed use …`), the import is re-exported:
     * any module that imports this module also transitively imports [imports]. This lets
     * a library forward its dependencies (e.g. `std.char` re-exporting `std.char.core`).
     */
    data class UseImport(
        val specs: List<ImportSpec>,
        val line: Int,
        val column: Int = 0,
        val exported: Boolean = false,
        /** Comptime condition on `export if COND \n import …`; null = unconditional. */
        val condition: Expr? = null,
    ) : TopLevel() {
        /**
         * Legacy flat `(path, selector)` pairs. Wildcard exclusions are available
         * only through [importSpecs].
         */
        val imports: List<Pair<String, String?>> get() = specs.flatMap { it.flatten() }

        /** Flat leaf clauses with wildcard exclusions and future metadata intact. */
        val importSpecs: List<ImportSpec> get() = specs.flatMap { it.leaves() }

        companion object {
            /** Builds an import statement from already-flattened pairs. */
            fun of(
                imports: List<Pair<String, String?>>,
                line: Int,
                column: Int = 0,
                exported: Boolean = false,
                condition: Expr? = null,
            ): UseImport = UseImport(imports.map { ImportSpec.of(it.first, it.second) }, line, column, exported, condition)
        }
    }

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
        /** `enum Name<T>` - the parameters the declaration is generic over. */
        val typeParams: List<String> = emptyList(),
    ) : TopLevel()

    /** `fail ErrSet { V1, V2 }` - an error set (a named set of error variants). */
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
        /** `error Name<T>` - the parameters the declaration is generic over. */
        val typeParams: List<String> = emptyList(),
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
        /** `bridge impl …` - compiler-provided; no IR emitted, but still registers members. */
        val isBridge: Boolean = false,
        /** Generic parameters declared directly on the implementation. */
        val typeParams: List<String> = emptyList(),
        /** Variadic implementation parameter from `impl<...T>`. */
        val variadicParam: String? = null,
        /** Lexical scope containing this implementation, used for same-scope lookup. */
        val scopePrefix: String? = null,
        /**
         * The module that declares this, or null before the parser tags it.
         *
         * A private member is reachable only from its own declaring module, so
         * the check needs to know where each side was written.
         */
        val declaringModule: String? = null,
        /** Source scope path of the implemented spec/decorator, when qualified. */
        val traitQualifier: String? = null,
        /** Exact written location of [traitName], independent of the `impl`/`pack` declaration head. */
        val traitLine: Int = line,
        val traitColumn: Int = column,
        val traitLength: Int = traitName?.length ?: 0,
        /** Generated-conformance request written with `derive` or a declaration's `derives` clause. */
        val isDerived: Boolean = false,
        /** Whether the source contained an implementation body, including an explicitly empty `{}`. */
        val hasBody: Boolean = true,
        /**
         * What this implementation's associated types are -
         * `impl Iterator for Rows assoc Item = Int`.
         */
        val assocBindings: Map<String, TypeRef> = emptyMap()
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
         * `bridge spec` - the compiler provides the members.
         *
         * A type requests the compiler-provided capability with `derive` and
         * writes a manual `impl` body only when the default lowering is wrong.
         */
        val isBridge: Boolean = false,
        /**
         * `direct spec` - the members belong to the type, not to its values.
         *
         * `direct spec Number { prop rank: Int }` asks each implementor for a
         * `rank` that is a property *of the type*: `Int::rank` answers for every
         * `Int` at once, so there is nothing for an instance to carry and nothing
         * to dispatch on at run time. The answer is reached directly, without a
         * value to reach it through.
         */
        val isDirect: Boolean = false,
        /**
         * Specs an implementor must *also* implement (`spec Copy requires [Clone]`).
         *
         * Unlike inheritance, this adds nothing to the spec and implies nothing
         * about a conforming type: it is a precondition checked at the `impl`.
         * `impl Copy for T` is rejected unless `T` separately implements
         * `Clone` - the capability is stated, never inferred.
         */
        val requires: List<TypeRef> = emptyList(),
        /**
         * Type parameter → the type it stands for when the argument is omitted.
         *
         * `spec PartialEqual<Rhs = Self>` is what lets `impl PartialEqual for Point`
         * mean `impl PartialEqual<Point> for Point`, so the homogeneous case -
         * nearly all of them - writes no argument at all.
         */
        val typeDefaults: Map<String, TypeRef> = emptyMap(),
        /**
         * Associated type names - `spec Iterator assoc Item`.
         *
         * A name the spec's members may use as a type, which each implementation
         * says the meaning of. Unlike a type parameter it is chosen by the
         * implementation rather than by whoever names the spec, so `Iterator` is
         * one spec however many kinds of row exist.
         */
        val assocNames: List<String> = emptyList()
    ) : TopLevel()

    /** `typealias Name = Type` - a type alias. */
    data class TypeAlias(val name: String, val type: TypeRef, val line: Int, val column: Int = 0, val annotations: List<Annotation> = emptyList(), val typeParams: List<String> = emptyList()) : TopLevel()

    /** One case of a tagged union: `Name(Type1, Type2)`, or `Name` with no payload. */
    data class SlotVariant(val name: String, val payloadTypes: List<TypeRef>)

    /**
     * `variant enum Name { … }` / `variant error Name { … }` - a tagged union.
     *
     * `variant` is a modifier on the payload-free `enum`/`error` forms: it says
     * the cases may carry data. [isError] records which of the two it modified -
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
        /** `variant enum Name<T>` - the parameters the declaration is generic over. */
        val typeParams: List<String> = emptyList(),
    ) : TopLevel()

    /**
     * `meta Name { arm; arm; … }` - a pattern-driven macro declaration.
     *
     * Macros are top-level declarations. A local macro is called as `@name(…)`;
     * an imported scope macro keeps its scope before the sigil, such as
     * `@vec(…)`. [MacroExpander] collects every `Meta`
     * declaration, rewrites all matching [Expr.MetaInvoke] invocations into
     * their arm templates, and removes the `Meta` node itself - so it never
     * reaches semantic analysis or IR generation.
     *
     * @property name the macro name
     * @property arms the ordered pattern arms (first match wins)
     */
    data class Meta(
        val name: String,
        val arms: List<MacroArm>,
        val line: Int,
        val column: Int = 0,
        /** Parameter supplied by the compiler when the macro decorates a declaration. */
        val parameter: String? = null,
    ) : TopLevel()

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
 * @property isExported whether the module was declared `exposed mod …`, making its
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
     * `exposed mod …` - the module's declarations are published to every
     * compilation unit that uses this library, with no explicit `import`.
     */
    val isExported: Boolean = false,
    /**
     * Module-level visibility from `[confined] mod …`
     * (default [ModuleVisibility.PUBLIC]). Bounds how far the module - and its
     * `exposed` auto-import - reaches.
     */
    val moduleVisibility: ModuleVisibility = ModuleVisibility.PUBLIC,
    /**
     * Comptime condition on `export if COND \n module …` (null = unconditional
     * `exposed mod`). Evaluated against config constants + CLI defines; when it
     * folds to `false`, [isExported] is cleared before stdlib indexing.
     */
    val exportCondition: Expr? = null,
    /**
     * Declaration name → the scope it was declared in, for `(reflect X).scope`.
     * Only declarations nested inside a `scope "label" { … }` (or an inline/
     * deepinline scope) appear; a name absent here is global (see [ScopeMeta]).
     */
    val scopes: Map<String, ScopeMeta> = emptyMap(),
    /** Compile-time `type name(...)` declarations owned by this unit. */
    val typeFunctions: List<TypeFunctionDecl> = emptyList(),
    /**
     * Type-macro rules declared in this unit. Parsed
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
     * Named type declaration → source-level scope path for declarations inside
     * `scope X`. Scope declarations are always qualified outside their scope;
     * this map preserves the source path while declarations remain flat in the AST.
     */
    val scopeTypeNamespaces: Map<String, String> = emptyMap(),
    /**
     * Declarations written inside a `test scope`, mapped to how far each reaches.
     *
     * A `scope test` is a visibility rule rather than a namespace, so its members
     * sit in [items] like any other declaration; this is what records that only
     * a test may refer to them.
     */
    val testScopeMembers: Map<String, Visibility> = emptyMap(),
    /**
     * Decorators written above the `module` header - the `.Module` target.
     *
     * A module is a declaration like any other, and this is what lets one say
     * something about the whole unit: `@Supress(kind: .Unused)` above
     * `module std.core` answers for every declaration the module makes.
     */
    val moduleAnnotations: List<Annotation> = emptyList(),
) {
    /** Convenience - returns only the resolved function declarations. */
    val functions: List<FuncDecl> get() = items.filterIsInstance<TopLevel.Func>().map { it.decl }

    /** Convenience - returns only the test declarations. */
    val tests: List<TopLevel.Test> get() = items.filterIsInstance<TopLevel.Test>()
}

/**
 * The name given to a contextual receiver that a lambda inherits rather than
 * declares - `sequence { yield(1) }` names no receiver, but the
 * closure still has to carry one.
 *
 * The frontend and the IR generator both synthesize these bindings and must
 * agree on the name; deriving it from the lambda's own source position keeps
 * them in step without threading a counter between passes.
 *
 * The separators are single underscores after the leading `__`. A `__` in the
 * middle of a name is the frontend's *joining* marker for a qualified symbol,
 * and canonicalization collapses it on the way to IR - so a name carrying one
 * arrives spelled differently from the parameter it declared, and the receiver
 * resolves to a global that nothing defines.
 */
fun lambdaReceiverName(line: Int, column: Int, index: Int): String =
    "__ctx${index}_${line}_$column"
