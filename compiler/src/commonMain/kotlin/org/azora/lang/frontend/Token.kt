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
 * - [REAL_LITERAL] -- floating-point literal (e.g. `3.14`)
 * - [STRING_LITERAL] -- double-quoted string literal (e.g. `"hello"`)
 * - [CHAR_LITERAL] -- single-quoted character literal (e.g. `'a'`, `'\n'`)
 * - [TRUE] -- boolean literal `true`
 * - [FALSE] -- boolean literal `false`
 *
 * **Identifier** -- a user-defined name:
 * - [IDENTIFIER] -- variable, function, or type name
 *
 * **Keywords** -- reserved words in the language:
 * - [VAR] -- mutable variable declaration
 * - [FIN] -- deeply immutable variable declaration
 * - [LET] -- immutable variable declaration
 * - [FUNC] -- function declaration
 * - [RETURN] -- return statement
 * - [PACKAGE] -- package declaration
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
 *
 * **Special** -- synthetic tokens:
 * - [NEWLINE] -- significant newline (statement terminator)
 * - [EOF] -- end of source input
 */
enum class TokenType {
    // Literals
    INT_LITERAL, REAL_LITERAL, STRING_LITERAL, CHAR_LITERAL, TRUE, FALSE,
    INTERPOLATED_STRING,

    // Identifier
    IDENTIFIER,

    // Keywords
    VAR, FIN, LET, FUNC, RETURN, PACKAGE, IF, ELSE, INLINE, DEEPINLINE, NOINLINE, ZONE, FRIEND,
    TEST, ASSERT, TRACE, MIXIN,
    FOR, WHILE, LOOP, IN, BREAK, CONTINUE,
    PACK, ENUM, WHEN,
    THROW, TRY, CATCH,
    IMPL, SPEC,
    DEFER, TYPEALIAS,
    SLOT,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQUAL, EQUAL_EQUAL, BANG_EQUAL,
    LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
    AND_AND, OR_OR, BANG,
    DOT, DOT_DOT, DOT_DOT_LESS,
    PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, SLASH_EQUAL, PERCENT_EQUAL,
    PLUS_PLUS, MINUS_MINUS,
    AMP, PIPE, CARET, TILDE, SHIFT_LEFT, SHIFT_RIGHT,
    AS, GUARD, IS,
    QMARK, QMARK_QMARK, QMARK_DOT, QMARK_EQUAL,
    // Null-conditional compound assignment / inc-dec: ?+= ?-= ?*= ?/= ?%= ?++ ?--
    QMARK_PLUS_EQUAL, QMARK_MINUS_EQUAL, QMARK_STAR_EQUAL, QMARK_SLASH_EQUAL, QMARK_PERCENT_EQUAL,
    QMARK_PLUS_PLUS, QMARK_MINUS_MINUS,
    NULL,
    USE,
    // `for x by N in ...` (step) and `reverse for` / `for x in reverse ...`
    BY,
    REVERSE,
    // `@label` for labeled loops and `break @label` / `continue @label`.
    AT,
    // `infx Type.method(params)` — extension method usable as an infix call (`a method b`).
    INFX,
    // `oper[]` / `oper[]=` — index-operator overloading inside impl blocks.
    OPER,
    // `fail ErrSet { … }` — error-set declaration; also `fail <expr>` throw sugar.
    FAIL,
    // Memory model: `alloc <expr>`, `drop <expr>`, `deref <expr>`, `unsafe { }`, `isolated(expr)`.
    ALLOC, DROP, DEREF, UNSAFE, ISOLATED,
    // Concurrency: `flow name(...) { … yield v }` generators, `task { }` / `await`, `launch { }`.
    FLOW, YIELD, TASK, AWAIT, LAUNCH,
    // FFI: `bridge <target> { func sigs }` — extern function declarations.
    BRIDGE,
    // DI: `solo Name { … }` singleton, `inject Type` resolve, `wrap Name { … }` container.
    SOLO, INJECT, WRAP,
    // Error handling: `rescue { … }` — catch-and-suppress.
    RESCUE,
    // Inheritance: `node Name(params)`, `leaf Name`, `repl func` (override), `virt` (virtual), `base` (parent/super).
    NODE, LEAF, REPL, VIRT, BASE,
    // Variadic generics: `...T` type params, `args: ...T` variadic params, `...arr` spread.
    ELLIPSIS,
    // Reactivity: `mem` (remember), `rem` (saveable/serializable), `ret` (retain), `effect { }`, `view Name() { }`.
    MEM, REM, RET, EFFECT, VIEW,
    // Object model: `hook name { }`, `prop name: T { }`, `ctor(params) { }`, `dtor { }`.
    // `get` and `set` are accessor markers in specs and future property syntax.
    HOOK, PROP, CTOR, DTOR, GET, SET,
    // Alternating execution: `flip { } flop { }`.
    FLIP, FLOP,
    // Ownership/reference modifiers: `ref T`, `mut ref T`, `shared ref T`, `weak ref T`.
    REF, OUT, MUT, SHARED, WEAK,
    // Visibility: `expose` (public), `confine` (private), `protect` (protected).
    // `shield` — a pack/field modifier: externally read-only, internally mutable.
    EXPOSE, CONFINE, PROTECT, SHIELD,
    // Module: `module Name` (alias for package).
    MODULE,
    // Thread-local storage: `threadlocal var x = 0` / `threadlocal fin y = 42`.
    THREADLOCAL,
    // `deco Name { fields }` — decorator/annotation declaration.
    DECO,

    // Delimiters
    L_PAREN, R_PAREN, L_BRACE, R_BRACE,
    L_BRACKET, R_BRACKET,
    COMMA, COLON, DOUBLE_COLON, ARROW,

    // Special
    NEWLINE, EOF
}

/**
 * Enumerates the type suffix attached to a numeric literal.
 *
 * No suffix means the default type: [NONE] maps to `Int` for integer
 * literals and `Real` for floating-point literals.
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
    DECIMAL    // D
}

/**
 * Pairs a numeric value with its suffix so the parser/IR generator can
 * produce the correct typed literal node.
 */
data class NumericLiteral(val value: Any, val suffix: NumericSuffix = NumericSuffix.NONE)

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
 *   [TokenType.INT_LITERAL], [Double] for [TokenType.REAL_LITERAL], [String] for
 *   [TokenType.STRING_LITERAL]), or `null` for non-literal tokens
 */
data class Token(
    val type: TokenType,
    val lexeme: String,
    val line: Int,
    val column: Int = 0,
    val literal: Any? = null
)
