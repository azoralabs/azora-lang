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

    companion object {
        /** The set of all numeric types (integer + floating-point). */
        val numericTypes: Set<IrType> = setOf(Int, UInt, Real, Byte, UByte, Short, UShort, Long, ULong, Cent, UCent, Float, Decimal)

        /** The set of all integer numeric types. */
        val integerTypes: Set<IrType> = setOf(Int, UInt, Byte, UByte, Short, UShort, Long, ULong, Cent, UCent)

        /** The set of all floating-point numeric types. */
        val floatTypes: Set<IrType> = setOf(Real, Float, Decimal)

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
            else -> error("Unknown type: $name")
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
    OR
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
    NOT
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

    /** Pretty-prints this expression as Azora IR text. */
    fun prettyPrint(): String = when (this) {
        is IntLiteral -> "$value"
        is RealLiteral -> "$value"
        is StringLiteral -> "\"$value\""
        is BoolLiteral -> "$value"
        is CharLiteral -> "'$value'"
        is Var -> name
        is Unary -> {
            val opStr = when (op) { IrUnaryOp.NEG -> "-"; IrUnaryOp.NOT -> "!" }
            "($opStr${operand.prettyPrint()})"
        }
        is Binary -> {
            val opStr = when (op) {
                IrBinaryOp.ADD -> "+"; IrBinaryOp.SUB -> "-"; IrBinaryOp.MUL -> "*"
                IrBinaryOp.DIV -> "/"; IrBinaryOp.MOD -> "%"; IrBinaryOp.EQ -> "=="
                IrBinaryOp.NEQ -> "!="; IrBinaryOp.LT -> "<"; IrBinaryOp.LTE -> "<="
                IrBinaryOp.GT -> ">"; IrBinaryOp.GTE -> ">="; IrBinaryOp.AND -> "&&"
                IrBinaryOp.OR -> "||"
            }
            "(${left.prettyPrint()} $opStr ${right.prettyPrint()})"
        }
        is Call -> "$name(${args.joinToString(", ") { it.prettyPrint() }})"
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
    data class Zone(val body: List<IrStmt>) : IrStmt()

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

    /** Pretty-prints this statement as Azora IR text. */
    fun prettyPrint(sb: StringBuilder, indent: Int) {
        val pad = "    ".repeat(indent)
        when (this) {
            is VarDecl -> sb.appendLine("${pad}var $name: $type = ${initializer.prettyPrint()}")
            is FinDecl -> sb.appendLine("${pad}fin $name: $type = ${initializer.prettyPrint()}")
            is LetDecl -> sb.appendLine("${pad}let $name: $type = ${initializer.prettyPrint()}")
            is Assignment -> sb.appendLine("${pad}$name = ${value.prettyPrint()}")
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
    val body: List<IrStmt>
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
    }
}
