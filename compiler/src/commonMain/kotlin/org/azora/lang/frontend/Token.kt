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
 * Enumerates every token type recognized by the Azora [Lexer].
 *
 * Variants are grouped into five categories:
 *
 * **Literals** -- values that appear directly in source code:
 * - [INT_LITERAL] -- integer literal (e.g. `42`)
 * - [DOUBLE_LITERAL] -- floating-point literal (e.g. `3.14`)
 * - [STRING_LITERAL] -- double-quoted string literal (e.g. `"hello"`)
 * - [CHAR_LITERAL] -- single-quoted character literal (e.g. `'a'`, `'\n'`)
 * - [TRUE] -- boolean literal `true`
 * - [FALSE] -- boolean literal `false`
 *
 * **Identifier** -- a user-defined name:
 * - [IDENTIFIER] -- variable, function, or type name
 *
 * **Keywords** -- reserved words in the language:
 * - [VAR] -- mutable binding, mutable value
 * - [LET] -- immutable binding, mutable value
 * - [VAL] -- mutable binding, immutable value
 * - [FIN] -- immutable binding, immutable value
 * - [FUNC] -- function declaration
 * - [RETURN] -- return statement
 * - [IF] -- conditional branch
 * - [ELSE] -- alternative conditional branch
 * - [INLINE] -- compile-time evaluation marker
 * - [DEEPINLINE] -- recursive compile-time evaluation marker
 * - [NOINLINE] -- escape from compile-time context back to runtime
 *
 * **Operators** -- arithmetic, comparison, logical, and assignment operators:
 * - [PLUS] -- `+` addition or string concatenation
 * - [MINUS] -- `-` subtraction or unary negation
 * - [STAR] -- `*` multiplication or string repetition
 * - [SLASH] -- `/` division
 * - [PERCENT] -- `%` modulo
 * - [EQUAL] -- `=` assignment
 * - [EQUAL_EQUAL] -- `==` equality comparison
 * - [BANG_EQUAL] -- `!=` inequality comparison
 * - [LESS] -- `<` less-than comparison
 * - [LESS_EQUAL] -- `<=` less-than-or-equal comparison
 * - [GREATER] -- `>` greater-than comparison
 * - [GREATER_EQUAL] -- `>=` greater-than-or-equal comparison
 * - [SPACESHIP] -- `<=>` three-way comparison; yields `Compare`/`PartialCompare`
 * - [AND_AND] -- `&&` logical AND
 * - [OR_OR] -- `||` logical OR
 * - [BANG] -- `!` logical NOT
 *
 * **Delimiters** -- structural punctuation:
 * - [L_PAREN] -- `(` left parenthesis
 * - [R_PAREN] -- `)` right parenthesis
 * - [L_BRACE] -- `{` left brace
 * - [R_BRACE] -- `}` right brace
 * - [COMMA] -- `,` parameter/argument separator
 * - [COLON] -- `:` type annotation separator
 * - [ARROW] -- `->` return type or function type separator
 * - [FAT_ARROW] -- `=>` macro and lowering rule separator
 *
 * **Special** -- synthetic tokens:
 * - [NEWLINE] -- significant newline (statement terminator)
 * - [EOF] -- end of source input
 */
enum class TokenType {
    // Literals
    INT_LITERAL, DOUBLE_LITERAL, STRING_LITERAL, CHAR_LITERAL, TRUE, FALSE,
    INTERPOLATED_STRING,

    // Identifier
    IDENTIFIER,

    // Keywords
    VAR, FIN, LET, VAL, FUNC, RETURN, IF, ELSE, INLINE, DEEPINLINE, NOINLINE, SCOPE,
    TEST, ASSERT, TRACE, PANIC, MACRO,
    FOR, WHILE, LOOP, IN, BREAK, CONTINUE,
    // `pack` structs and `enum` variants. (`union` is contextual - see
    // Parser.isUnionDeclAhead - so that `Set.union(other)` keeps working.)
    PACK, ENUM, WHEN,
    THROW, TRY, CATCH,
    IMPL, SPEC,
    DEFER, TYPEALIAS,
    VARIANT,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQUAL, EQUAL_EQUAL, BANG_EQUAL,
    LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, SPACESHIP,
    AND_AND, OR_OR, BANG,
    DOT, DOT_DOT, DOT_DOT_LESS,
    PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, SLASH_EQUAL, PERCENT_EQUAL,
    AMP_EQUAL, PIPE_EQUAL, CARET_EQUAL, SHIFT_LEFT_EQUAL, SHIFT_RIGHT_EQUAL,
    PLUS_PLUS, MINUS_MINUS,
    AMP, PIPE, CARET, TILDE, SHIFT_LEFT, SHIFT_RIGHT,
    AS, IS,
    QMARK, QMARK_QMARK, QMARK_DOT, QMARK_EQUAL, QMARK_BANG,
    // Null-conditional compound assignment / inc-dec: ?+= ?-= ?*= ?/= ?%= ?++ ?--
    QMARK_PLUS_EQUAL, QMARK_MINUS_EQUAL, QMARK_STAR_EQUAL, QMARK_SLASH_EQUAL, QMARK_PERCENT_EQUAL,
    QMARK_PLUS_PLUS, QMARK_MINUS_MINUS,
    NULL,
    // `import path` - module imports. `use as` gives a spec member its generated
    // call spelling; bridge ABI names are declaration macros, not parser syntax.
    IMPORT, USE,
    // `for x by N in ...` (step) and `reverse for` / `for x in reverse ...`
    BY,
    REVERSE,
    // Decorators and macro declarations/invocations.
    AT,
    // Contextual callable receivers: `with value { ... }` / `with [a, b] { ... }`.
    WITH,
    WITHOUT,
    // `oper[]` / `oper[]=` - index-operator overloading inside impl blocks.
    OPER,
    // `error ErrSet { … }` - error-set declaration; also `error <expr>` throw sugar.
    ERROR,
    // Memory model: `alloc <expr>`, `purge <expr>`, `deref <expr>`, `unsafe { }`.
    ALLOC, PURGE, UNSAFE,
    // Ownership: `take <expr>` transfers ownership. Duplication is the
    // `Clone` spec's `clone()` method, not a keyword.
    TAKE,
    // Concurrency: `flow name(...) { … yield v }` generators, `task { }` / `await`, `launch { }`.
    AWAIT,
    // `delay <ms>` - suspend the current task for a number of milliseconds.
    DELAY,
    // FFI: `bridge <target> { func sigs }` - extern function declarations.
    BRIDGE,
    // DI: `solo pack Name { … }` singleton, `inject Type` resolve, `graph Name { … }` container.
    SOLO, INJECT, GRAPH,
    // `scoped Type(args)` inside a `graph` - one value per active scope. A
    // separate word from `scope`, which is a namespace.
    SCOPED,
    // `direct spec Number { prop rank: Int }` - a spec whose members belong to
    // the type rather than to its values.
    DIRECT,

    // `lazy fin` / `lazy let` - evaluate a binding on its first read.
    LAZY,

    // Provider lifetimes inside a `graph`, and graph composition.
    // `factory Type(args)` - a new owned value per resolution.
    // `graph G includes [A, B]` - compose graphs.
    FACTORY,

    // Reserved ahead of use: nothing parses it yet, but no program may take
    // the name, so introducing the form later breaks nothing.
    DERIVE,
    // Error handling: `rescue { … }` - catch-and-suppress.
    RESCUE,
    // Variadic generics: `...T` type params, `args: ...T` variadic params, `...arr` spread.
    ELLIPSIS,
    // Reactivity enabled by `react`: `remember`, `retain`, `preserve`, and `effect`.
    // `react func f()` - a reactive owner. Reactive state and effects are
    // legal only inside one, and only another may call it.
    REACT,
    REMEMBER, RETAIN, PRESERVE, EFFECT,
    // Object model: `prop name: T { }`, `ctor(params) { }`, `dtor { }`.
    PROP, CTOR, DTOR,
    // `out { … }` postcondition contracts.
    OUT,
    // Visibility: public by default, `confined` narrows to the package. A
    // single leading underscore marks a declaration private where supported.
    // `exposed` marks a `module` or an `import` as auto-imported everywhere.
    EXPOSE, PROTECT, CONFINE,
    // Thread-local storage: `threadlocal var x = 0` / `threadlocal fin y = 42`.
    THREADLOCAL,
    // `annot Name [binds Spec] { fields }` - decorator/annotation declaration.
    ANNOT, BIND,

    // The compiler's own primitives, written `__int`, `__uint` and `__float`.
    // A pack cannot describe them: `Int<N: __uint>` needs a width before there
    // is an `Int` to state one with, and a literal needs a kind before there is
    // a type written as it. Reserved by their `__`, which no user symbol may
    // start with.
    PRIM_INT, PRIM_UINT, PRIM_FLOAT,

    // Delimiters
    L_PAREN, R_PAREN, L_BRACE, R_BRACE,
    L_BRACKET, R_BRACKET,
    COMMA, COLON, DOUBLE_COLON, ARROW, FAT_ARROW, SEMICOLON,

    // Special
    NEWLINE, EOF
}

/**
 * Enumerates the type suffix attached to a numeric literal.
 *
 * No suffix means the default type: [NONE] maps to `Int` for integer
 * literals and `Double` for floating-point literals.
 */
enum class NumericSuffix {
    NONE,
    BYTE,      // b
    UBYTE,     // ub
    SHORT,     // s
    USHORT,    // us
    UINT,      // u
    LONG,      // L
    ULONG,     // uL
    CENT,      // c
    UCENT,     // uc
    FLOAT,     // f
    QUAD    // D
}

/**
 * Pairs a numeric value with its suffix so the parser/IR generator can
 * produce the correct typed literal node.
 */
data class NumericLiteral(
    val value: Any,
    val suffix: NumericSuffix = NumericSuffix.NONE,
    /** The exact digits, when they are wider than [value] can carry (a 128-bit literal). */
    val text: String? = null,
)

/**
 * One segment of an interpolated string literal.
 *
 * - [Literal] is a chunk of literal text (escapes already resolved).
 * - [Expr] is an embedded expression given as raw source text, parsed later
 *   in the surrounding expression context.
 */
sealed class StringPart {
    /** A literal text chunk of an interpolated string. */
    data class Literal(val text: String) : StringPart()
    /** An embedded expression, as raw source text (e.g. `"a + b"` from `"${a + b}"`). */
    data class Expr(val source: String) : StringPart()
}

/**
 * A single lexical token produced by the [Lexer].
 *
 * Tokens carry enough source-location information to produce accurate
 * error messages in later compiler phases.
 *
 * @property type the [TokenType] that classifies this token
 * @property lexeme the raw source text that was matched (e.g. `"42"`, `"func"`)
 * @property line the 1-based line number where the token starts
 * @property column the 1-based column number where the token starts
 * @property literal the parsed literal value for literal tokens (e.g. [Long] for
 *   [TokenType.INT_LITERAL], [Double] for [TokenType.DOUBLE_LITERAL], [String] for
 *   [TokenType.STRING_LITERAL]), or `null` for non-literal tokens
 */
data class Token(
    val type: TokenType,
    val lexeme: String,
    val line: Int,
    val column: Int = 0,
    val literal: Any? = null
)
