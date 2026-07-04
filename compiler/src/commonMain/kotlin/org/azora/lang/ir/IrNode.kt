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

import org.azora.lang.frontend.TypeRef

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/**
 * Represents a resolved type in the intermediate representation.
 *
 * All types in the IR are concrete -- there is no type inference at this level.
 * The type system is shared with the CTFE evaluator.
 *
 * - [Int] -- 32-bit signed integer
 * - [UInt] -- 32-bit unsigned integer
 * - [Real] -- 64-bit double-precision floating point
 * - [String] -- UTF-8 string
 * - [Bool] -- boolean (`true` / `false`)
 * - [Unit] -- no meaningful value (void)
 * - [Char] -- character (stored as Long char code)
 * - [Byte] -- 8-bit signed integer
 * - [UByte] -- 8-bit unsigned integer
 * - [Short] -- 16-bit signed integer
 * - [UShort] -- 16-bit unsigned integer
 * - [Long] -- 64-bit signed integer
 * - [ULong] -- 64-bit unsigned integer
 * - [Cent] -- 128-bit signed integer
 * - [UCent] -- 128-bit unsigned integer
 * - [Float] -- 32-bit single-precision floating point
 * - [Decimal] -- 128-bit decimal floating point
 */
sealed class IrType {
    /** 32-bit signed integer type. */
    object Int : IrType() { override fun toString() = "Int" }
    /** 32-bit unsigned integer type. */
    object UInt : IrType() { override fun toString() = "UInt" }
    /** 64-bit double-precision floating-point type. */
    object Real : IrType() { override fun toString() = "Real" }
    /** UTF-8 string type. */
    object String : IrType() { override fun toString() = "String" }
    /** Boolean type. */
    object Bool : IrType() { override fun toString() = "Bool" }
    /** Unit (void) type -- represents no meaningful value. */
    object Unit : IrType() { override fun toString() = "Unit" }
    /** Character type (stored as Long char code). */
    object Char : IrType() { override fun toString() = "Char" }
    /** 8-bit signed integer type. */
    object Byte : IrType() { override fun toString() = "Byte" }
    /** 8-bit unsigned integer type. */
    object UByte : IrType() { override fun toString() = "UByte" }
    /** 16-bit signed integer type. */
    object Short : IrType() { override fun toString() = "Short" }
    /** 16-bit unsigned integer type. */
    object UShort : IrType() { override fun toString() = "UShort" }
    /** 64-bit signed integer type. */
    object Long : IrType() { override fun toString() = "Long" }
    /** 64-bit unsigned integer type. */
    object ULong : IrType() { override fun toString() = "ULong" }
    /** 128-bit signed integer type. */
    object Cent : IrType() { override fun toString() = "Cent" }
    /** 128-bit unsigned integer type. */
    object UCent : IrType() { override fun toString() = "UCent" }
    /** 32-bit single-precision floating-point type. */
    object Float : IrType() { override fun toString() = "Float" }
    /** 128-bit decimal floating-point type. */
    object Decimal : IrType() { override fun toString() = "Decimal" }

    /** Array type `[T]`. */
    data class Array(val element: IrType) : IrType() { override fun toString() = "[$element]" }

    /** Map type `[K: V]`. */
    data class Map(val key: IrType, val value: IrType) : IrType() { override fun toString() = "[$key: $value]" }

    /** Function type `(A, B) -> R`. */
    data class Function(val params: List<IrType>, val ret: IrType) : IrType() { override fun toString() = "(${params.joinToString(", ")}) -> $ret" }

    /** Tuple type `(A, B)`. */
    data class Tuple(val elements: List<IrType>) : IrType() { override fun toString() = "(${elements.joinToString(", ")})" }

    /** Nullable type `T?`. */
    data class Nullable(val inner: IrType) : IrType() { override fun toString() = "$inner?" }

    /** Pointer type `T*` — a heap reference to [inner]. Runtime representation is a mutable cell. */
    data class Pointer(val inner: IrType) : IrType() { override fun toString() = "$inner*" }

    /** A user-defined or unresolved named type (struct, enum, generic base). */
    data class Named(val name: kotlin.String) : IrType() { override fun toString() = name }

    /** The dynamic / erased type, used when a precise type is unknown. */
    object Any : IrType() { override fun toString() = "Any" }

    companion object {
        /** Type aliases registered by the compiler (cleared per compilation). */
        val aliases = mutableMapOf<kotlin.String, TypeRef>()
        /** The set of all numeric types (integer + floating-point). */
        val numericTypes: kotlin.collections.Set<IrType> = setOf(Int, UInt, Real, Byte, UByte, Short, UShort, Long, ULong, Cent, UCent, Float, Decimal)

        /** The set of all integer numeric types. */
        val integerTypes: kotlin.collections.Set<IrType> = setOf(Int, UInt, Byte, UByte, Short, UShort, Long, ULong, Cent, UCent)

        /** The set of all floating-point numeric types. */
        val floatTypes: kotlin.collections.Set<IrType> = setOf(Real, Float, Decimal)

        /**
         * Resolves a type name string to its corresponding [IrType].
         *
         * @param name the type name (e.g. `"Int"`, `"Bool"`)
         * @return the matching [IrType]
         * @throws IllegalStateException if the name does not match any known type
         */
        fun fromName(name: kotlin.String): IrType = when (name) {
            "Int" -> Int
            "UInt" -> UInt
            "Real" -> Real
            "String" -> String
            "Bool" -> Bool
            "Unit" -> Unit
            "Char" -> Char
            "Byte" -> Byte
            "UByte" -> UByte
            "Short" -> Short
            "UShort" -> UShort
            "Long" -> Long
            "ULong" -> ULong
            "Cent" -> Cent
            "UCent" -> UCent
            "Float" -> Float
            "Decimal" -> Decimal
            "Any" -> Any
            else -> error("Unknown type: $name")
        }

        /**
         * Returns `true` if [name] is a built-in primitive type resolvable via [fromName].
         */
        fun isPrimitiveName(name: kotlin.String): Boolean = try {
            fromName(name); true
        } catch (_: Exception) {
            false
        }

        /**
         * Resolves a structured [TypeRef] (from the parser) into a concrete [IrType].
         *
         * Primitive names resolve to their primitive type; all other named types
         * (structs, enums, generic bases not yet specialized) resolve to [Named].
         * Compound types resolve recursively.
         */
        fun resolve(ref: TypeRef, typeParams: kotlin.collections.Set<kotlin.String> = emptySet()): IrType = when (ref) {
            is TypeRef.Named -> {
                if (ref.name in typeParams) Any
                else if (ref.name in aliases) resolve(aliases[ref.name]!!, typeParams)
                else if (ref.args.isEmpty() && isPrimitiveName(ref.name)) fromName(ref.name)
                else Named(ref.name)
            }
            is TypeRef.Array -> Array(resolve(ref.element, typeParams))
            is TypeRef.Map -> Map(resolve(ref.key, typeParams), resolve(ref.value, typeParams))
            is TypeRef.Function -> Function(ref.params.map { resolve(it, typeParams) }, resolve(ref.ret, typeParams))
            is TypeRef.Tuple -> Tuple(ref.elements.map { resolve(it, typeParams) })
            is TypeRef.Nullable -> Nullable(resolve(ref.inner, typeParams))
            is TypeRef.Pointer -> Pointer(resolve(ref.inner, typeParams))
            // `T!ErrSet` — at runtime a value of T (errors propagate via exceptions),
            // so the IR type is just the inner ok type.
            is TypeRef.Failable -> resolve(ref.ok, typeParams)
        }
    }
}

// ---------------------------------------------------------------------------
// Expressions (typed)
// ---------------------------------------------------------------------------

/**
 * Binary operators in the IR.
 *
 * **Arithmetic:** [ADD], [SUB], [MUL], [DIV], [MOD]
 *
 * **Comparison:** [EQ], [NEQ], [LT], [LTE], [GT], [GTE]
 *
 * **Logical:** [AND], [OR]
 */
enum class IrBinaryOp {
    /** Addition (`+`) or string concatenation. */
    ADD,
    /** Subtraction (`-`). */
    SUB,
    /** Multiplication (`*`) or string repetition. */
    MUL,
    /** Division (`/`). */
    DIV,
    /** Modulo (`%`). */
    MOD,
    /** Equality comparison (`==`). */
    EQ,
    /** Inequality comparison (`!=`). */
    NEQ,
    /** Less-than comparison (`<`). */
    LT,
    /** Less-than-or-equal comparison (`<=`). */
    LTE,
    /** Greater-than comparison (`>`). */
    GT,
    /** Greater-than-or-equal comparison (`>=`). */
    GTE,
    /** Logical AND (`&&`). */
    AND,
    /** Logical OR (`||`). */
    OR,
    /** Bitwise AND (`&`). */
    BIT_AND,
    /** Bitwise OR (`|`). */
    BIT_OR,
    /** Bitwise XOR (`^`). */
    BIT_XOR,
    /** Left shift (`<<`). */
    SHL,
    /** Right shift (`>>`). */
    SHR
}

/**
 * Unary operators in the IR.
 *
 * - [NEG] -- arithmetic negation (`-x`)
 * - [NOT] -- logical negation (`!x`)
 */
enum class IrUnaryOp {
    /** Arithmetic negation (`-`). */
    NEG,
    /** Logical negation (`!`). */
    NOT,
    /** Bitwise NOT (`~`). */
    BIT_NOT
}

/**
 * Base class for all typed IR expression nodes.
 *
 * Every IR expression carries a resolved [type], enabling backends to emit
 * type-correct code without re-running type inference.
 */
sealed class IrExpr {
    /** The resolved type of this expression. */
    abstract val type: IrType

    /**
     * Integer literal (covers Int, UInt, Short, UShort, Long, ULong, Cent, UCent).
     *
     * @property value the 64-bit integer value
     * @property type the resolved integer type (defaults to [IrType.Int])
     */
    data class IntLiteral(val value: Long, override val type: IrType = IrType.Int) : IrExpr()

    /**
     * Floating-point literal (covers Real, Float, Decimal).
     *
     * @property value the double-precision value
     * @property type the resolved float type (defaults to [IrType.Real])
     */
    data class RealLiteral(val value: Double, override val type: IrType = IrType.Real) : IrExpr()

    /**
     * String literal.
     *
     * @property value the string content
     */
    data class StringLiteral(val value: String) : IrExpr() {
        override val type = IrType.String
    }

    /**
     * Boolean literal.
     *
     * @property value the boolean value
     */
    data class BoolLiteral(val value: Boolean) : IrExpr() {
        override val type = IrType.Bool
    }

    /**
     * Character literal.
     *
     * @property value the character value
     */
    data class CharLiteral(val value: Char) : IrExpr() {
        override val type = IrType.Char
    }

    /**
     * Variable reference.
     *
     * @property name the variable name
     * @property type the resolved type of the variable
     */
    data class Var(val name: String, override val type: IrType) : IrExpr()

    /**
     * Binary operator expression.
     *
     * @property left the left-hand operand
     * @property op the binary operator
     * @property right the right-hand operand
     * @property type the resolved result type
     */
    data class Binary(
        val left: IrExpr,
        val op: IrBinaryOp,
        val right: IrExpr,
        override val type: IrType
    ) : IrExpr()

    /**
     * Unary operator expression.
     *
     * @property op the unary operator
     * @property operand the operand expression
     * @property type the resolved result type
     */
    data class Unary(
        val op: IrUnaryOp,
        val operand: IrExpr,
        override val type: IrType
    ) : IrExpr()

    /**
     * Function call expression.
     *
     * @property name the callee function name
     * @property args the list of argument expressions
     * @property type the resolved return type of the called function
     */
    data class Call(
        val name: String,
        val args: List<IrExpr>,
        override val type: IrType
    ) : IrExpr()

    /**
     * Array literal `[a, b, c]`.
     *
     * @property elements the element expressions
     * @property type the resolved array type [IrType.Array]
     */
    data class ArrayLiteral(val elements: List<IrExpr>, override val type: IrType) : IrExpr()

    /** Map literal `["k": v, "k2": v2]`. [type] is an [IrType.Map]. */
    data class MapLit(val entries: List<Pair<IrExpr, IrExpr>>, override val type: IrType) : IrExpr()

    /**
     * Index access `target[index]`.
     *
     * @property target the indexed expression
     * @property index the index expression
     * @property type the element type of the indexed array
     */
    data class Index(val target: IrExpr, val index: IrExpr, override val type: IrType) : IrExpr()

    /**
     * Member access `target.name`.
     *
     * @property target the receiver expression
     * @property name the member name
     * @property type the resolved member type
     */
    data class Member(val target: IrExpr, val name: String, override val type: IrType) : IrExpr()

    /**
     * Method call `target.name(args)`.
     *
     * @property target the receiver expression
     * @property name the method name
     * @property args the argument expressions
     * @property type the resolved return type of the method
     */
    data class MethodCall(val target: IrExpr, val name: String, val args: List<IrExpr>, override val type: IrType) : IrExpr()

    /**
     * Struct construction `Name(args)`, where [name] is a pack.
     *
     * @property name the struct (pack) name
     * @property fieldNames the field names, in positional order (used by the interpreter)
     * @property args the positional field-initializer expressions
     * @property type the resolved struct type [IrType.Named]
     */
    data class StructCtor(val name: String, val fieldNames: List<String>, val args: List<IrExpr>, override val type: IrType) : IrExpr()

    /** One segment of a string-interpolation template. */
    sealed class IrTemplatePart {
        /** A literal text chunk. */
        data class Literal(val text: String) : IrTemplatePart()
        /** An embedded expression. */
        data class Expr(val expr: IrExpr) : IrTemplatePart()
    }

    /**
     * Interpolated string `"hello $name"`. Always has type [IrType.String].
     */
    data class StringTemplate(val parts: List<IrTemplatePart>) : IrExpr() {
        override val type: IrType = IrType.String
    }

    /** Tuple literal `(a, b, c)`. */
    data class TupleLit(val elements: List<IrExpr>, override val type: IrType) : IrExpr()

    /** Tuple positional access `target.index`. */
    data class TupleAccess(val target: IrExpr, val index: Int, override val type: IrType) : IrExpr()

    /** `expr catch fallback` — evaluates [expr]; on throw, evaluates [fallback]. */
    data class CatchExpr(val expr: IrExpr, val fallback: IrExpr, override val type: IrType) : IrExpr()

    /**
     * A lambda/closure `{ params -> body }`.
     *
     * @property params the parameter name/type pairs
     * @property body the lambda body statements
     * @property type the resolved function type [IrType.Function]
     */
    data class Lambda(val params: List<Pair<String, IrType>>, val body: List<IrStmt>, override val type: IrType) : IrExpr()

    /** A slot pattern `SlotName.VariantName(bindings)` in a `when` branch. Never evaluated — consumed by the interpreter. */
    data class SlotPattern(val slotName: String, val variantName: String, val bindings: List<String>) : IrExpr() {
        override val type: IrType = IrType.Bool
    }

    /** `await task` — suspend until the task (a no-arg closure) completes, yielding its result. */
    data class Await(val value: IrExpr, override val type: IrType = IrType.Any) : IrExpr()

    /** Pretty-prints this expression as Azora IR text. */
    fun prettyPrint(): String = when (this) {
        is IntLiteral -> "$value"
        is RealLiteral -> "$value"
        is StringLiteral -> "\"$value\""
        is BoolLiteral -> "$value"
        is CharLiteral -> "'$value'"
        is Var -> name
        is Unary -> {
            val opStr = when (op) { IrUnaryOp.NEG -> "-"; IrUnaryOp.NOT -> "!"; IrUnaryOp.BIT_NOT -> "~" }
            "($opStr${operand.prettyPrint()})"
        }
        is Binary -> {
            val opStr = when (op) {
                IrBinaryOp.ADD -> "+"; IrBinaryOp.SUB -> "-"; IrBinaryOp.MUL -> "*"
                IrBinaryOp.DIV -> "/"; IrBinaryOp.MOD -> "%"; IrBinaryOp.EQ -> "=="
                IrBinaryOp.NEQ -> "!="; IrBinaryOp.LT -> "<"; IrBinaryOp.LTE -> "<="
                IrBinaryOp.GT -> ">"; IrBinaryOp.GTE -> ">="; IrBinaryOp.AND -> "&&"
                IrBinaryOp.BIT_AND -> "&"; IrBinaryOp.BIT_OR -> "|"; IrBinaryOp.BIT_XOR -> "^"
                IrBinaryOp.SHL -> "<<"; IrBinaryOp.SHR -> ">>"
                IrBinaryOp.OR -> "||"
            }
            "(${left.prettyPrint()} $opStr ${right.prettyPrint()})"
        }
        is Call -> "$name(${args.joinToString(", ") { it.prettyPrint() }})"
        is ArrayLiteral -> "[${elements.joinToString(", ") { it.prettyPrint() }}]"
        is MapLit -> "[${entries.joinToString(", ") { "${it.first.prettyPrint()} : ${it.second.prettyPrint()}" }}]"
        is Index -> "${target.prettyPrint()}[${index.prettyPrint()}]"
        is Member -> "${target.prettyPrint()}.$name"
        is MethodCall -> "${target.prettyPrint()}.$name(${args.joinToString(", ") { it.prettyPrint() }})"
        is StructCtor -> "$name(${args.joinToString(", ") { it.prettyPrint() }})"
        is StringTemplate -> parts.joinToString("") {
            when (it) {
                is IrTemplatePart.Literal -> "\"${it.text}\""
                is IrTemplatePart.Expr -> "\${${it.expr.prettyPrint()}}"
            }
        }
        is TupleLit -> "(${elements.joinToString(", ") { it.prettyPrint() }})"
        is TupleAccess -> "${target.prettyPrint()}.$index"
        is CatchExpr -> "(${expr.prettyPrint()} catch ${fallback.prettyPrint()})"
        is Lambda -> "{ ${params.joinToString(", ") { (n, t) -> "$n: $t" }} -> ... }"
        is SlotPattern -> "$slotName.$variantName(${bindings.joinToString(",")})"
        is Await -> "await ${value.prettyPrint()}"
    }
}

// ---------------------------------------------------------------------------
// Statements
// ---------------------------------------------------------------------------

/**
 * Base class for all IR statement nodes.
 *
 * IR statements mirror AST statements but with all types fully resolved
 * and all compile-time constructs already eliminated by CTFE.
 */
sealed class IrStmt {
    /**
     * Mutable binding (`var`).
     *
     * @property name the variable name
     * @property type the resolved type
     * @property initializer the initial value expression
     */
    data class VarDecl(val name: String, val type: IrType, val initializer: IrExpr) : IrStmt()

    /**
     * Deeply immutable binding (`fin`).
     *
     * @property name the variable name
     * @property type the resolved type
     * @property initializer the initial value expression
     */
    data class FinDecl(val name: String, val type: IrType, val initializer: IrExpr) : IrStmt()

    /**
     * Immutable binding (`let`).
     *
     * @property name the variable name
     * @property type the resolved type
     * @property initializer the initial value expression
     */
    data class LetDecl(val name: String, val type: IrType, val initializer: IrExpr) : IrStmt()

    /**
     * Variable reassignment.
     *
     * @property name the name of the variable being reassigned
     * @property value the new value expression
     */
    data class Assignment(val name: String, val value: IrExpr) : IrStmt()

    /**
     * Index assignment `target[index] = value`.
     *
     * @property target the indexed expression
     * @property index the index expression
     * @property value the new value expression
     */
    data class IndexAssign(val target: IrExpr, val index: IrExpr, val value: IrExpr) : IrStmt()

    /**
     * Member assignment `target.name = value`.
     *
     * @property target the receiver expression
     * @property name the member name
     * @property value the new value expression
     */
    data class MemberAssign(val target: IrExpr, val name: String, val value: IrExpr) : IrStmt()

    /**
     * Return statement.
     *
     * @property value the return value expression, or `null` for void returns
     */
    data class Return(val value: IrExpr?) : IrStmt()

    /**
     * Expression used as a statement.
     *
     * @property expr the expression being evaluated for its side effects
     */
    data class ExprStmt(val expr: IrExpr) : IrStmt()

    /**
     * Conditional branch (if/else).
     *
     * @property condition the boolean condition expression
     * @property thenBranch the statements to execute when the condition is true
     * @property elseBranch the statements to execute when the condition is false, or `null`
     */
    data class If(
        val condition: IrExpr,
        val thenBranch: List<IrStmt>,
        val elseBranch: List<IrStmt>?
    ) : IrStmt()

    /**
     * Scoped block (`zone { ... }`). Introduces a new variable scope.
     *
     * @property body the list of statements inside the zone
     */
    data class Zone(val body: List<IrStmt>, val alloc: Boolean = false) : IrStmt()

    /**
     * Runtime assertion (`assert condition { "message" }`).
     *
     * @property condition the boolean condition expression
     * @property message the error message expression
     */
    data class Assert(val condition: IrExpr, val message: IrExpr) : IrStmt()

    /**
     * Runtime trace (`trace { expr }`).
     *
     * @property message the message expression
     */
    data class Trace(val message: IrExpr) : IrStmt()

    /**
     * `while` loop.
     *
     * @property condition the boolean loop condition
     * @property body the statements executed each iteration
     */
    data class While(val condition: IrExpr, val body: List<IrStmt>, val label: String? = null) : IrStmt()

    /**
     * Integer `for` loop lowered from `for name in start..end` / `..<`.
     *
     * @property counter the loop counter variable name
     * @property start the inclusive start bound
     * @property end the end bound
     * @property inclusive whether [end] is included (`..` vs `..<`)
     * @property body the statements executed each iteration
     */
    data class For(
        val counter: String,
        val start: IrExpr,
        val end: IrExpr,
        val inclusive: Boolean,
        val body: List<IrStmt>,
        /** Step for the counter; null means 1. */
        val step: IrExpr? = null,
        /** Iterate downwards from [end] to [start]. */
        val reverse: Boolean = false,
        /** Optional `@label` for labeled `break`/`continue`. */
        val label: String? = null
    ) : IrStmt()

    /**
     * Infinite `loop { body }`. Exits via [Break].
     *
     * @property body the statements executed repeatedly
     */
    data class Loop(val body: List<IrStmt>, val label: String? = null) : IrStmt()

    /** `break` — exits the enclosing loop, or the loop tagged [label] if given. */
    data class Break(val label: String? = null) : IrStmt()

    /** `continue` — next iteration of the enclosing loop, or the loop tagged [label] if given. */
    data class Continue(val label: String? = null) : IrStmt()

    /**
     * `when scrutinee { patterns -> body ... else -> body }`.
     */
    data class When(val scrutinee: IrExpr, val branches: List<IrWhenBranch>, val elseBranch: List<IrStmt>?) : IrStmt()

    /** `throw value`. */
    data class Throw(val value: IrExpr) : IrStmt()

    /** `try { body } catch { name -> handler }`. */
    data class Try(val body: List<IrStmt>, val catchName: String?, val catchBody: List<IrStmt>?) : IrStmt()

    /** `defer { body }` — runs [body] when the enclosing function exits. */
    data class Defer(val body: List<IrStmt>, val onFail: Boolean = false, val suppress: Boolean = false) : IrStmt()

    /** `yield value` — emit a value from a `flow` generator. */
    data class Yield(val value: IrExpr) : IrStmt()

    /** `for x in <iterable>` (non-range) — bind [elem] to each value of [iterable], run [body]. */
    data class ForEach(val elem: String, val iterable: IrExpr, val body: List<IrStmt>) : IrStmt()

    /** Pretty-prints this statement as Azora IR text. */
    fun prettyPrint(sb: StringBuilder, indent: Int) {
        val pad = "    ".repeat(indent)
        when (this) {
            is VarDecl -> sb.appendLine("${pad}var $name: $type = ${initializer.prettyPrint()}")
            is FinDecl -> sb.appendLine("${pad}fin $name: $type = ${initializer.prettyPrint()}")
            is LetDecl -> sb.appendLine("${pad}let $name: $type = ${initializer.prettyPrint()}")
            is Assignment -> sb.appendLine("${pad}$name = ${value.prettyPrint()}")
            is IndexAssign -> sb.appendLine("${pad}${target.prettyPrint()}[${index.prettyPrint()}] = ${value.prettyPrint()}")
            is MemberAssign -> sb.appendLine("${pad}${target.prettyPrint()}.$name = ${value.prettyPrint()}")
            is Return -> if (value != null) sb.appendLine("${pad}return ${value.prettyPrint()}") else sb.appendLine("${pad}return")
            is ExprStmt -> sb.appendLine("${pad}${expr.prettyPrint()}")
            is If -> {
                sb.appendLine("${pad}if ${condition.prettyPrint()} {")
                for (s in thenBranch) s.prettyPrint(sb, indent + 1)
                if (elseBranch != null) {
                    sb.appendLine("${pad}} else {")
                    for (s in elseBranch) s.prettyPrint(sb, indent + 1)
                }
                sb.appendLine("${pad}}")
            }
            is Zone -> {
                sb.appendLine("${pad}zone {")
                for (s in body) s.prettyPrint(sb, indent + 1)
                sb.appendLine("${pad}}")
            }
            is Assert -> sb.appendLine("${pad}assert ${condition.prettyPrint()} { ${message.prettyPrint()} }")
            is Trace -> sb.appendLine("${pad}trace { ${message.prettyPrint()} }")
            is While -> {
                sb.appendLine("${pad}while ${condition.prettyPrint()} {")
                for (s in body) s.prettyPrint(sb, indent + 1)
                sb.appendLine("${pad}}")
            }
            is For -> {
                val op = if (inclusive) ".." else "..<"
                val stepPart = if (step != null) " by ${step.prettyPrint()}" else ""
                val revPart = if (reverse) "reverse " else ""
                sb.appendLine("${pad}for $revPart$counter in ${start.prettyPrint()}$op${end.prettyPrint()}$stepPart {")
                for (s in body) s.prettyPrint(sb, indent + 1)
                sb.appendLine("${pad}}")
            }
            is Loop -> {
                sb.appendLine("${pad}loop {")
                for (s in body) s.prettyPrint(sb, indent + 1)
                sb.appendLine("${pad}}")
            }
            is Break -> {
                val lbl = if (label != null) " @$label" else ""
                sb.appendLine("${pad}break$lbl")
            }
            is Continue -> {
                val lbl = if (label != null) " @$label" else ""
                sb.appendLine("${pad}continue$lbl")
            }
            is When -> {
                sb.appendLine("${pad}when ${scrutinee.prettyPrint()} {")
                for (b in branches) {
                    sb.appendLine("${pad}    ${b.patterns.joinToString(", ") { it.prettyPrint() }} -> {")
                    for (s in b.body) s.prettyPrint(sb, indent + 2)
                    sb.appendLine("${pad}    }")
                }
                if (elseBranch != null) {
                    sb.appendLine("${pad}    else -> {")
                    for (s in elseBranch) s.prettyPrint(sb, indent + 2)
                    sb.appendLine("${pad}    }")
                }
                sb.appendLine("${pad}}")
            }
            is Throw -> sb.appendLine("${pad}throw ${value.prettyPrint()}")
            is Defer -> {
                sb.appendLine("${pad}defer {")
                for (s in body) s.prettyPrint(sb, indent + 1)
                sb.appendLine("${pad}}")
            }
            is Yield -> sb.appendLine("${pad}yield ${value.prettyPrint()}")
            is ForEach -> {
                sb.appendLine("${pad}for $elem in ${iterable.prettyPrint()} {")
                for (s in body) s.prettyPrint(sb, indent + 1)
                sb.appendLine("${pad}}")
            }
            is Try -> {
                sb.appendLine("${pad}try {")
                for (s in body) s.prettyPrint(sb, indent + 1)
                if (catchBody != null) {
                    val bind = if (catchName != null) "$catchName -> " else ""
                    sb.appendLine("${pad}} catch { $bind")
                    for (s in catchBody) s.prettyPrint(sb, indent + 1)
                }
                sb.appendLine("${pad}}")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top-level
// ---------------------------------------------------------------------------

/**
 * A function in the IR, with fully resolved types.
 *
 * @property name the function name
 * @property params the parameter list as name-type pairs
 * @property returnType the resolved return type
 * @property body the list of IR statements forming the function body
 */
data class IrFunction(
    val name: String,
    val params: List<Pair<String, IrType>>,
    val returnType: IrType,
    val body: List<IrStmt>,
    /** `flow` generator: calling it returns a list of `yield`ed values. */
    val isFlow: Boolean = false
) {
    /** Pretty-prints this function as Azora IR text. */
    fun prettyPrint(sb: StringBuilder, indent: Int) {
        val pad = "    ".repeat(indent)
        val params = params.joinToString(", ") { (name, type) -> "$name: $type" }
        sb.appendLine("${pad}func $name($params): $returnType {")
        for (stmt in body) stmt.prettyPrint(sb, indent + 1)
        sb.appendLine("${pad}}")
    }
}

/** A field of an IR struct type. */
data class IrField(val name: String, val type: IrType, val mutable: Boolean)

/** One branch of an IR `when`: any of [patterns] matches → run [body]. */
data class IrWhenBranch(val patterns: List<IrExpr>, val body: List<IrStmt>)

/**
 * A top-level item in the IR program — either a global statement or a function.
 */
sealed class IrTopLevel {
    data class Global(val stmt: IrStmt) : IrTopLevel()
    data class Func(val function: IrFunction) : IrTopLevel()

    /**
     * A test declaration (`test "name" { body }`).
     *
     * @property name the test name string
     * @property body the list of IR statements forming the test body
     */
    data class Test(val name: String, val body: List<IrStmt>) : IrTopLevel()

    /**
     * A `pack` (struct) declaration, emitted so backends can generate the type definition.
     *
     * @property name the struct name
     * @property fields the ordered list of fields
     */
    data class Struct(val name: String, val fields: List<IrField>) : IrTopLevel()

    /**
     * An extern (`bridge`) function declaration — a signature with no body, emitted so
     * backends can declare it (`external fun` / `declare` / LLVM `declare`) for FFI linking.
     */
    data class Extern(val name: String, val params: List<Pair<String, IrType>>, val returnType: IrType) : IrTopLevel()
}

/**
 * The root of an IR program, representing a complete compilation unit.
 *
 * Top-level items are stored in source order to preserve interleaving
 * of globals and functions.
 *
 * @property packageName the declared package name, or `null` if no package declaration
 * @property items the ordered list of top-level items (globals and functions)
 */
data class IrProgram(
    val packageName: String?,
    val items: List<IrTopLevel>
) {
    /** Convenience — returns only the global statements. */
    val globals: List<IrStmt> get() = items.filterIsInstance<IrTopLevel.Global>().map { it.stmt }

    /** Convenience — returns only the functions. */
    val functions: List<IrFunction> get() = items.filterIsInstance<IrTopLevel.Func>().map { it.function }

    /** Convenience — returns only the test declarations. */
    val tests: List<IrTopLevel.Test> get() = items.filterIsInstance<IrTopLevel.Test>()

    /** Pretty-prints this program as Azora IR text. */
    fun prettyPrint(): String {
        val sb = StringBuilder()
        if (packageName != null) {
            sb.appendLine("package $packageName")
            sb.appendLine()
        }
        for ((i, item) in items.withIndex()) {
            val next = items.getOrNull(i + 1)
            when (item) {
                is IrTopLevel.Global -> {
                    item.stmt.prettyPrint(sb, 0)
                    // Blank line after a global if the next item is a function
                    if (next is IrTopLevel.Func) sb.appendLine()
                }
                is IrTopLevel.Func -> {
                    item.function.prettyPrint(sb, 0)
                    if (i < items.lastIndex) sb.appendLine()
                }
                is IrTopLevel.Test -> {
                    sb.appendLine("test \"${item.name}\" {")
                    for (stmt in item.body) stmt.prettyPrint(sb, 1)
                    sb.appendLine("}")
                    if (i < items.lastIndex) sb.appendLine()
                }
                is IrTopLevel.Struct -> {
                    val fields = item.fields.joinToString(", ") { "${it.name}: ${it.type}" }
                    sb.appendLine("pack ${item.name} { $fields }")
                    if (i < items.lastIndex) sb.appendLine()
                }
                is IrTopLevel.Extern -> {
                    val params = item.params.joinToString(", ") { (n, t) -> "$n: $t" }
                    sb.appendLine("extern func ${item.name}($params): ${item.returnType}")
                    if (i < items.lastIndex) sb.appendLine()
                }
            }
        }
        return sb.toString().trimEnd()
    }

    /** Dumps this program as a tree-style AST for debugging. */
    fun dumpTree(): String {
        val sb = StringBuilder()
        sb.appendLine("IrProgram")
        if (packageName != null) {
            sb.appendLine("    package: $packageName")
        }
        for (item in items) {
            when (item) {
                is IrTopLevel.Global -> {
                    sb.appendLine("    global:")
                    dumpIrStmtTree(sb, item.stmt, "        ")
                }
                is IrTopLevel.Func -> {
                    val func = item.function
                    val params = func.params.joinToString(", ") { (name, type) -> "$name: $type" }
                    sb.appendLine("    IrFunction(name=${func.name}, params=($params), returnType=${func.returnType})")
                    sb.appendLine("        body:")
                    for (stmt in func.body) dumpIrStmtTree(sb, stmt, "            ")
                }
                is IrTopLevel.Test -> {
                    sb.appendLine("    IrTest(name=\"${item.name}\")")
                    sb.appendLine("        body:")
                    for (stmt in item.body) dumpIrStmtTree(sb, stmt, "            ")
                }
                is IrTopLevel.Struct -> {
                    val fields = item.fields.joinToString(", ") { "${it.name}: ${it.type}" }
                    sb.appendLine("    IrStruct(name=${item.name}, fields=[$fields])")
                }
                is IrTopLevel.Extern -> {
                    val params = item.params.joinToString(", ") { (n, t) -> "$n: $t" }
                    sb.appendLine("    IrExtern(name=${item.name}, params=($params), ret=${item.returnType})")
                }
            }
        }
        return sb.toString().trimEnd()
    }
}

private fun dumpIrStmtTree(sb: StringBuilder, stmt: IrStmt, indent: String) {
    when (stmt) {
        is IrStmt.VarDecl -> {
            sb.appendLine("${indent}IrVarDecl(name=${stmt.name}, type=${stmt.type})")
            sb.appendLine("$indent    init:")
            dumpIrExprTree(sb, stmt.initializer, "$indent        ")
        }
        is IrStmt.FinDecl -> {
            sb.appendLine("${indent}IrFinDecl(name=${stmt.name}, type=${stmt.type})")
            sb.appendLine("$indent    init:")
            dumpIrExprTree(sb, stmt.initializer, "$indent        ")
        }
        is IrStmt.LetDecl -> {
            sb.appendLine("${indent}IrLetDecl(name=${stmt.name}, type=${stmt.type})")
            sb.appendLine("$indent    init:")
            dumpIrExprTree(sb, stmt.initializer, "$indent        ")
        }
        is IrStmt.Assignment -> {
            sb.appendLine("${indent}IrAssignment(name=${stmt.name})")
            sb.appendLine("$indent    value:")
            dumpIrExprTree(sb, stmt.value, "$indent        ")
        }
        is IrStmt.IndexAssign -> {
            sb.appendLine("${indent}IrIndexAssign")
            sb.appendLine("$indent    target:")
            dumpIrExprTree(sb, stmt.target, "$indent        ")
            sb.appendLine("$indent    index:")
            dumpIrExprTree(sb, stmt.index, "$indent        ")
            sb.appendLine("$indent    value:")
            dumpIrExprTree(sb, stmt.value, "$indent        ")
        }
        is IrStmt.MemberAssign -> {
            sb.appendLine("${indent}IrMemberAssign(name=${stmt.name})")
            sb.appendLine("$indent    target:")
            dumpIrExprTree(sb, stmt.target, "$indent        ")
            sb.appendLine("$indent    value:")
            dumpIrExprTree(sb, stmt.value, "$indent        ")
        }
        is IrStmt.Return -> {
            sb.appendLine("${indent}IrReturn")
            if (stmt.value != null) dumpIrExprTree(sb, stmt.value, "$indent    ")
        }
        is IrStmt.ExprStmt -> {
            sb.appendLine("${indent}IrExprStmt")
            dumpIrExprTree(sb, stmt.expr, "$indent    ")
        }
        is IrStmt.Zone -> {
            sb.appendLine("${indent}IrZone")
            for (s in stmt.body) dumpIrStmtTree(sb, s, "$indent    ")
        }
        is IrStmt.If -> {
            sb.appendLine("${indent}IrIf")
            sb.appendLine("$indent    condition:")
            dumpIrExprTree(sb, stmt.condition, "$indent        ")
            sb.appendLine("$indent    then:")
            for (s in stmt.thenBranch) dumpIrStmtTree(sb, s, "$indent        ")
            if (stmt.elseBranch != null) {
                sb.appendLine("$indent    else:")
                for (s in stmt.elseBranch) dumpIrStmtTree(sb, s, "$indent        ")
            }
        }
        is IrStmt.Assert -> {
            sb.appendLine("${indent}IrAssert")
            sb.appendLine("$indent    condition:")
            dumpIrExprTree(sb, stmt.condition, "$indent        ")
            sb.appendLine("$indent    message:")
            dumpIrExprTree(sb, stmt.message, "$indent        ")
        }
        is IrStmt.Trace -> {
            sb.appendLine("${indent}IrTrace")
            sb.appendLine("$indent    message:")
            dumpIrExprTree(sb, stmt.message, "$indent        ")
        }
        is IrStmt.While -> {
            sb.appendLine("${indent}IrWhile")
            sb.appendLine("$indent    condition:")
            dumpIrExprTree(sb, stmt.condition, "$indent        ")
            sb.appendLine("$indent    body:")
            for (s in stmt.body) dumpIrStmtTree(sb, s, "$indent        ")
        }
        is IrStmt.For -> {
            sb.appendLine("${indent}IrFor(counter=${stmt.counter}, inclusive=${stmt.inclusive}, reverse=${stmt.reverse})")
            sb.appendLine("$indent    start:")
            dumpIrExprTree(sb, stmt.start, "$indent        ")
            sb.appendLine("$indent    end:")
            dumpIrExprTree(sb, stmt.end, "$indent        ")
            if (stmt.step != null) {
                sb.appendLine("$indent    step:")
                dumpIrExprTree(sb, stmt.step, "$indent        ")
            }
            sb.appendLine("$indent    body:")
            for (s in stmt.body) dumpIrStmtTree(sb, s, "$indent        ")
        }
        is IrStmt.Loop -> {
            sb.appendLine("${indent}IrLoop")
            sb.appendLine("$indent    body:")
            for (s in stmt.body) dumpIrStmtTree(sb, s, "$indent        ")
        }
        is IrStmt.Break -> sb.appendLine("${indent}IrBreak(label=${stmt.label})")
        is IrStmt.Continue -> sb.appendLine("${indent}IrContinue(label=${stmt.label})")
        is IrStmt.When -> {
            sb.appendLine("${indent}IrWhen")
            sb.appendLine("$indent    scrutinee:")
            dumpIrExprTree(sb, stmt.scrutinee, "$indent        ")
            for (b in stmt.branches) {
                sb.appendLine("$indent    branch:")
                for (p in b.patterns) dumpIrExprTree(sb, p, "$indent        ")
                for (s in b.body) dumpIrStmtTree(sb, s, "$indent        ")
            }
            if (stmt.elseBranch != null) {
                sb.appendLine("$indent    else:")
                for (s in stmt.elseBranch) dumpIrStmtTree(sb, s, "$indent        ")
            }
        }
        is IrStmt.Throw -> {
            sb.appendLine("${indent}IrThrow")
            dumpIrExprTree(sb, stmt.value, "$indent    ")
        }
        is IrStmt.Try -> {
            sb.appendLine("${indent}IrTry(catchName=${stmt.catchName})")
            sb.appendLine("$indent    body:")
            for (s in stmt.body) dumpIrStmtTree(sb, s, "$indent        ")
            if (stmt.catchBody != null) {
                sb.appendLine("$indent    catch:")
                for (s in stmt.catchBody) dumpIrStmtTree(sb, s, "$indent        ")
            }
        }
        is IrStmt.Defer -> {
            sb.appendLine("${indent}IrDefer")
            for (s in stmt.body) dumpIrStmtTree(sb, s, "$indent    ")
        }
        is IrStmt.Yield -> {
            sb.appendLine("${indent}IrYield")
            dumpIrExprTree(sb, stmt.value, "$indent    ")
        }
        is IrStmt.ForEach -> {
            sb.appendLine("${indent}IrForEach(elem=${stmt.elem})")
            sb.appendLine("$indent    iterable:")
            dumpIrExprTree(sb, stmt.iterable, "$indent        ")
            sb.appendLine("$indent    body:")
            for (s in stmt.body) dumpIrStmtTree(sb, s, "$indent        ")
        }
    }
}

private fun dumpIrExprTree(sb: StringBuilder, expr: IrExpr, indent: String) {
    when (expr) {
        is IrExpr.IntLiteral -> sb.appendLine("${indent}IrIntLiteral(${expr.value}) : ${expr.type}")
        is IrExpr.RealLiteral -> sb.appendLine("${indent}IrRealLiteral(${expr.value}) : ${expr.type}")
        is IrExpr.StringLiteral -> sb.appendLine("${indent}IrStringLiteral(\"${expr.value}\") : String")
        is IrExpr.BoolLiteral -> sb.appendLine("${indent}IrBoolLiteral(${expr.value}) : Bool")
        is IrExpr.CharLiteral -> sb.appendLine("${indent}IrCharLiteral('${expr.value}') : Char")
        is IrExpr.Var -> sb.appendLine("${indent}IrVar(${expr.name}) : ${expr.type}")
        is IrExpr.Binary -> {
            sb.appendLine("${indent}IrBinary(op=${expr.op}) : ${expr.type}")
            dumpIrExprTree(sb, expr.left, "$indent    ")
            dumpIrExprTree(sb, expr.right, "$indent    ")
        }
        is IrExpr.Unary -> {
            sb.appendLine("${indent}IrUnary(op=${expr.op}) : ${expr.type}")
            dumpIrExprTree(sb, expr.operand, "$indent    ")
        }
        is IrExpr.Call -> {
            sb.appendLine("${indent}IrCall(name=${expr.name}) : ${expr.type}")
            for (arg in expr.args) dumpIrExprTree(sb, arg, "$indent    ")
        }
        is IrExpr.ArrayLiteral -> {
            sb.appendLine("${indent}IrArrayLiteral : ${expr.type}")
            for (elem in expr.elements) dumpIrExprTree(sb, elem, "$indent    ")
        }
        is IrExpr.MapLit -> {
            sb.appendLine("${indent}IrMapLiteral : ${expr.type}")
            for ((k, v) in expr.entries) {
                sb.appendLine("$indent    entry:")
                dumpIrExprTree(sb, k, "$indent        ")
                dumpIrExprTree(sb, v, "$indent        ")
            }
        }
        is IrExpr.Index -> {
            sb.appendLine("${indent}IrIndex : ${expr.type}")
            sb.appendLine("$indent    target:")
            dumpIrExprTree(sb, expr.target, "$indent        ")
            sb.appendLine("$indent    index:")
            dumpIrExprTree(sb, expr.index, "$indent        ")
        }
        is IrExpr.Member -> {
            sb.appendLine("${indent}IrMember(name=${expr.name}) : ${expr.type}")
            dumpIrExprTree(sb, expr.target, "$indent    ")
        }
        is IrExpr.MethodCall -> {
            sb.appendLine("${indent}IrMethodCall(name=${expr.name}) : ${expr.type}")
            dumpIrExprTree(sb, expr.target, "$indent    ")
            for (arg in expr.args) dumpIrExprTree(sb, arg, "$indent    ")
        }
        is IrExpr.StructCtor -> {
            sb.appendLine("${indent}IrStructCtor(name=${expr.name}) : ${expr.type}")
            for (arg in expr.args) dumpIrExprTree(sb, arg, "$indent    ")
        }
        is IrExpr.StringTemplate -> {
            sb.appendLine("${indent}IrStringTemplate : String")
            for (part in expr.parts) {
                when (part) {
                    is IrExpr.IrTemplatePart.Literal -> sb.appendLine("$indent    Literal(\"${part.text}\")")
                    is IrExpr.IrTemplatePart.Expr -> {
                        sb.appendLine("$indent    Expr:")
                        dumpIrExprTree(sb, part.expr, "$indent        ")
                    }
                }
            }
        }
        is IrExpr.TupleLit -> {
            sb.appendLine("${indent}IrTupleLit : ${expr.type}")
            for (e in expr.elements) dumpIrExprTree(sb, e, "$indent    ")
        }
        is IrExpr.TupleAccess -> {
            sb.appendLine("${indent}IrTupleAccess(index=${expr.index}) : ${expr.type}")
            dumpIrExprTree(sb, expr.target, "$indent    ")
        }
        is IrExpr.CatchExpr -> {
            sb.appendLine("${indent}IrCatchExpr : ${expr.type}")
            dumpIrExprTree(sb, expr.expr, "$indent    ")
            dumpIrExprTree(sb, expr.fallback, "$indent    ")
        }
        is IrExpr.SlotPattern -> {
            sb.appendLine("${indent}IrSlotPattern(${expr.slotName}.${expr.variantName}, bindings=${expr.bindings})")
        }
        is IrExpr.Await -> {
            sb.appendLine("${indent}IrAwait : ${expr.type}")
            dumpIrExprTree(sb, expr.value, "$indent    ")
        }
        is IrExpr.Lambda -> {
            sb.appendLine("${indent}IrLambda : ${expr.type}")
            for (s in expr.body) dumpIrStmtTree(sb, s, "$indent    ")
        }
    }
}
