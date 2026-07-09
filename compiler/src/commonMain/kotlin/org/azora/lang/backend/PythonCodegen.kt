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

package org.azora.lang.backend

import org.azora.lang.ir.IrBinaryOp
import org.azora.lang.ir.IrExpr
import org.azora.lang.ir.IrFunction
import org.azora.lang.ir.IrProgram
import org.azora.lang.ir.IrStmt
import org.azora.lang.ir.IrTopLevel
import org.azora.lang.ir.IrType
import org.azora.lang.ir.IrUnaryOp

/**
 * Backend — lowers [IrProgram] to Python 3 source code.
 *
 * Python is dynamically typed, so no type annotations are emitted. `println`
 * maps to `print`; `fin`/`let`/`var` all become plain assignments.
 *
 * Integer division and modulo route through `_azidiv`/`_azmod` helpers so they
 * truncate toward zero (Python's `//`/`%` floor); booleans print lower-case to
 * match the interpreter. Azora packs become Python classes (reference types).
 * A `main` function is invoked at module end.
 */
class PythonCodegen {

    private val out = StringBuilder()
    private var indent = 0
    private var usesIntDiv = false
    private var usesMod = false
    private var usesFloatMod = false
    private var usesError = false
    private var usesCatchExpr = false
    private var usesLabels = false
    private var usesTasks = false
    private var whenCounter = 0

    private val POINTER_RUNTIME = setOf("__alloc", "__deref", "__derefAssign", "__isolated")

    /**
     * Generates Python 3 source code from the given IR program.
     *
     * @param program the optimized IR program to lower to Python
     * @return the generated Python source code as a string
     */
    fun generate(program: IrProgram): String {
        out.clear()
        indent = 0
        usesIntDiv = false; usesMod = false; usesFloatMod = false
        usesError = false; usesCatchExpr = false; usesLabels = false
        usesTasks = program.functions.any { it.isTask }
        whenCounter = 0

        val structs = program.items.filterIsInstance<IrTopLevel.Struct>()
        val funcs = program.items.filterIsInstance<IrTopLevel.Func>().map { it.function }
        val tests = program.items.filterIsInstance<IrTopLevel.Test>()
        val globals = program.items.filterIsInstance<IrTopLevel.Global>().map { it.stmt }

        // Definitions first (functions may reference each other / later globals).
        val body = capture {
            indent = 0
            for (s in structs) { emitStruct(s); line("") }
            for (f in funcs) { emitFunction(f); line("") }
            for (t in tests) { emitTest(t); line("") }
            for (g in globals) emitStmt(g)
        }

        val sb = StringBuilder()
        if (usesTasks) sb.appendLine("import asyncio")
        if (usesFloatMod) sb.appendLine("import math")
        if (usesTasks || usesFloatMod) sb.appendLine()
        sb.append(preamble())
        sb.append(body)

        // Entry point.
        val main = program.functions.firstOrNull { it.name == "main" }
        if (main != null) {
            if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.appendLine()
            if (main.isTask) sb.appendLine("asyncio.run(main())")
            else sb.appendLine("main()")
        }
        return sb.toString().trimEnd()
    }

    /** Runtime helper definitions, emitted only for the features the program uses. */
    private fun preamble(): String {
        val p = StringBuilder()
        if (usesIntDiv || usesMod) {
            p.appendLine("def _azidiv(a, b):")
            p.appendLine("    q = a // b")
            p.appendLine("    return q + 1 if (q < 0 and q * b != a) else q")
        }
        if (usesMod) {
            p.appendLine("def _azmod(a, b):")
            p.appendLine("    return a - _azidiv(a, b) * b")
        }
        if (usesError) {
            p.appendLine("class _AzError(Exception):")
            p.appendLine("    def __init__(self, value):")
            p.appendLine("        self.value = value")
        }
        if (usesCatchExpr) {
            p.appendLine("def _azcatch(f, fb):")
            p.appendLine("    try:")
            p.appendLine("        return f()")
            p.appendLine("    except Exception:")
            p.appendLine("        return fb()")
        }
        if (usesLabels) {
            p.appendLine("class _AzBreak(Exception):")
            p.appendLine("    def __init__(self, label): self.label = label")
            p.appendLine("class _AzContinue(Exception):")
            p.appendLine("    def __init__(self, label): self.label = label")
        }
        if (usesTasks) {
            p.appendLine("_az_children = []")
            p.appendLine("def _az_spawn(coro):")
            p.appendLine("    t = asyncio.ensure_future(coro)")
            p.appendLine("    _az_children.append(t)")
            p.appendLine("    return t")
            p.appendLine("async def _az_thunk(fn):")
            p.appendLine("    return fn()")
            p.appendLine("async def _az_drain():")
            p.appendLine("    while _az_children:")
            p.appendLine("        batch = _az_children[:]")
            p.appendLine("        _az_children.clear()")
            p.appendLine("        await asyncio.gather(*batch)")
            p.appendLine("def cancel(t):")
            p.appendLine("    t.cancel()")
        }
        if (p.isNotEmpty()) p.appendLine()
        return p.toString()
    }

    private fun emitTest(test: IrTopLevel.Test) {
        val safeName = test.name.replace(" ", "_").replace("\"", "")
        line("def __test_$safeName():")
        indent++
        emitBlockBody(test.body)
        indent--
    }

    private fun emitStruct(struct: IrTopLevel.Struct) {
        line("class ${struct.name}:")
        indent++
        val params = struct.fields.joinToString(", ") { it.name }
        line("def __init__(self${if (params.isEmpty()) "" else ", $params"}):")
        indent++
        if (struct.fields.isEmpty()) line("pass")
        for (f in struct.fields) line("self.${f.name} = ${f.name}")
        indent--
        indent--
    }

    private fun emitFunction(func: IrFunction) {
        val params = func.params.joinToString(", ") { (name, _) -> name }
        val async = if (func.isTask) "async " else ""
        line("${async}def ${func.name}($params):")
        indent++
        val before = out.length
        for (stmt in func.body) emitStmt(stmt)
        // A task main joins any unawaited spawned children before returning.
        if (func.isTask && func.name == "main") line("await _az_drain()")
        if (out.length == before) line("pass")
        indent--
    }

    /** Emits [stmts]; if that produces no output, emits `pass` (Python needs a non-empty suite). */
    private fun emitBlockBody(stmts: List<IrStmt>) {
        val before = out.length
        for (s in stmts) emitStmt(s)
        if (out.length == before) line("pass")
    }

    private fun emitStmt(stmt: IrStmt) {
        when (stmt) {
            is IrStmt.VarDecl -> line("${stmt.name} = ${emitExpr(stmt.initializer)}")
            is IrStmt.FinDecl -> line("${stmt.name} = ${emitExpr(stmt.initializer)}")
            is IrStmt.LetDecl -> line("${stmt.name} = ${emitExpr(stmt.initializer)}")
            is IrStmt.Assignment -> line("${stmt.name} = ${emitExpr(stmt.value)}")
            is IrStmt.IndexAssign -> line("${emitExpr(stmt.target)}[${emitExpr(stmt.index)}] = ${emitExpr(stmt.value)}")
            is IrStmt.MemberAssign -> line("${emitExpr(stmt.target)}.${stmt.name} = ${emitExpr(stmt.value)}")
            is IrStmt.When -> {
                val tmp = "__when${whenCounter++}"
                line("$tmp = ${emitExpr(stmt.scrutinee)}")
                var first = true
                for (b in stmt.branches) {
                    val cond = b.patterns.joinToString(" or ") { "$tmp == ${emitExpr(it)}" }
                    line("${if (first) "if" else "elif"} $cond:")
                    first = false
                    indent++
                    emitBlockBody(b.body)
                    indent--
                }
                if (stmt.elseBranch != null) {
                    line(if (first) "if True:" else "else:")
                    indent++
                    emitBlockBody(stmt.elseBranch)
                    indent--
                }
            }
            is IrStmt.Return -> {
                if (stmt.value != null) line("return ${emitExpr(stmt.value)}")
                else line("return")
            }
            is IrStmt.ExprStmt -> line(emitExpr(stmt.expr))
            is IrStmt.Zone -> {
                // Python has no block scope — emit the body inline.
                for (s in stmt.body) emitStmt(s)
            }
            is IrStmt.Assert -> line("if not (${emitExpr(stmt.condition)}): raise Exception(${emitExpr(stmt.message)})")
            is IrStmt.Trace -> line("print(\"[TRACE] \" + str(${emitExpr(stmt.message)}))")
            is IrStmt.If -> {
                line("if ${emitExpr(stmt.condition)}:")
                indent++
                emitBlockBody(stmt.thenBranch)
                indent--
                var elseB = stmt.elseBranch
                while (elseB != null) {
                    if (elseB.size == 1 && elseB[0] is IrStmt.If) {
                        val ei = elseB[0] as IrStmt.If
                        line("elif ${emitExpr(ei.condition)}:")
                        indent++
                        emitBlockBody(ei.thenBranch)
                        indent--
                        elseB = ei.elseBranch
                    } else {
                        line("else:")
                        indent++
                        emitBlockBody(elseB)
                        indent--
                        elseB = null
                    }
                }
            }
            is IrStmt.While -> emitLoop(stmt.label, "while ${emitExpr(stmt.condition)}:", stmt.body)
            is IrStmt.Loop -> emitLoop(stmt.label, "while True:", stmt.body)
            is IrStmt.For -> {
                val range = when {
                    stmt.reverse -> {
                        val step = stmt.step?.let { emitExpr(it) } ?: "1"
                        "range(${emitExpr(stmt.end)}, ${emitExpr(stmt.start)} - 1, -($step))"
                    }
                    else -> {
                        val end = if (stmt.inclusive) "${emitExpr(stmt.end)} + 1" else emitExpr(stmt.end)
                        val stepPart = stmt.step?.let { ", ${emitExpr(it)}" } ?: ""
                        "range(${emitExpr(stmt.start)}, $end$stepPart)"
                    }
                }
                emitLoop(stmt.label, "for ${stmt.counter} in $range:", stmt.body)
            }
            is IrStmt.ForEach -> {
                val elem = stmt.elem
                val iterable = emitExpr(stmt.iterable)
                emitLoop(null, "for $elem in $iterable:", stmt.body)
            }
            is IrStmt.Throw -> {
                usesError = true
                line("raise _AzError(${emitExpr(stmt.value)})")
            }
            is IrStmt.Try -> {
                usesError = true
                line("try:")
                indent++
                emitBlockBody(stmt.body)
                indent--
                line("except Exception as __e:")
                indent++
                if (stmt.catchName != null) {
                    line("${stmt.catchName} = __e.value if isinstance(__e, _AzError) else str(__e)")
                }
                if (stmt.catchBody != null) emitBlockBody(stmt.catchBody) else line("pass")
                indent--
            }
            is IrStmt.Defer -> {}
            is IrStmt.Yield -> {}
            is IrStmt.Break -> {
                if (stmt.label != null) { usesLabels = true; line("raise _AzBreak(\"${stmt.label}\")") }
                else line("break")
            }
            is IrStmt.Continue -> {
                if (stmt.label != null) { usesLabels = true; line("raise _AzContinue(\"${stmt.label}\")") }
                else line("continue")
            }
        }
    }

    /** Emits a loop; a non-null [label] wraps the body so labeled break/continue can target it. */
    private fun emitLoop(label: String?, header: String, body: List<IrStmt>) {
        line(header)
        indent++
        if (label == null) {
            emitBlockBody(body)
        } else {
            usesLabels = true
            line("try:")
            indent++
            emitBlockBody(body)
            indent--
            line("except _AzBreak as __b:")
            indent++
            line("if __b.label != \"$label\": raise")
            line("break")
            indent--
            line("except _AzContinue as __c:")
            indent++
            line("if __c.label != \"$label\": raise")
            line("continue")
            indent--
        }
        indent--
    }

    private fun emitExpr(expr: IrExpr): String = when (expr) {
        is IrExpr.IntLiteral -> "${expr.value}"
        is IrExpr.RealLiteral -> "${expr.value}"
        is IrExpr.StringLiteral -> "\"${escapeString(expr.value)}\""
        is IrExpr.BoolLiteral -> if (expr.value) "True" else "False"
        is IrExpr.CharLiteral -> "\"${escapeString(expr.value.toString())}\""
        is IrExpr.Var -> expr.name
        is IrExpr.Unary -> when (expr.op) {
            IrUnaryOp.NEG -> "(-${emitExpr(expr.operand)})"
            IrUnaryOp.NOT -> "(not ${emitExpr(expr.operand)})"
            IrUnaryOp.BIT_NOT -> "(~${emitExpr(expr.operand)})"
        }
        is IrExpr.Binary -> {
            val l = emitExpr(expr.left)
            val r = emitExpr(expr.right)
            when (expr.op) {
                IrBinaryOp.DIV -> if (expr.type in IrType.integerTypes) { usesIntDiv = true; "_azidiv($l, $r)" } else "($l / $r)"
                IrBinaryOp.MOD ->
                    if (expr.type in IrType.integerTypes) { usesMod = true; "_azmod($l, $r)" }
                    else { usesFloatMod = true; "math.fmod($l, $r)" }
                IrBinaryOp.ADD -> "($l + $r)"
                IrBinaryOp.SUB -> "($l - $r)"
                IrBinaryOp.MUL -> "($l * $r)"
                IrBinaryOp.EQ -> "($l == $r)"
                IrBinaryOp.NEQ -> "($l != $r)"
                IrBinaryOp.LT -> "($l < $r)"
                IrBinaryOp.LTE -> "($l <= $r)"
                IrBinaryOp.GT -> "($l > $r)"
                IrBinaryOp.GTE -> "($l >= $r)"
                IrBinaryOp.AND -> "($l and $r)"
                IrBinaryOp.OR -> "($l or $r)"
                IrBinaryOp.BIT_AND -> "($l & $r)"
                IrBinaryOp.BIT_OR -> "($l | $r)"
                IrBinaryOp.BIT_XOR -> "($l ^ $r)"
                IrBinaryOp.SHL -> "($l << $r)"
                IrBinaryOp.SHR -> "($l >> $r)"
            }
        }
        is IrExpr.Call -> {
            if (expr.name in POINTER_RUNTIME) { /* pointer runtime funcs, if present, are user-defined */ }
            when {
                expr.name == "async" && expr.args.singleOrNull() is IrExpr.Lambda -> {
                    usesTasks = true
                    "_az_spawn(_az_thunk(${emitLambda(expr.args.single() as IrExpr.Lambda)}))"
                }
                expr.name == "cancel" && expr.args.size == 1 -> "cancel(${emitExpr(expr.args.single())})"
                expr.type is IrType.Task -> {
                    usesTasks = true
                    "_az_spawn(${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }}))"
                }
                expr.name == "println" && expr.args.size == 1 -> "print(${emitPrintArg(expr.args.single())})"
                expr.name == "print" && expr.args.size == 1 -> "print(${emitPrintArg(expr.args.single())}, end=\"\")"
                else -> "${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
            }
        }
        is IrExpr.Await -> "(await ${emitExpr(expr.value)})"
        is IrExpr.Spread -> "*${emitExpr(expr.array)}"
        is IrExpr.ArrayLiteral -> "[${expr.elements.joinToString(", ") { emitExpr(it) }}]"
        is IrExpr.SetLit ->
            if (expr.elements.isEmpty()) "set()"
            else "{${expr.elements.joinToString(", ") { emitExpr(it) }}}"
        is IrExpr.MapLit -> "{${expr.entries.joinToString(", ") { "${emitExpr(it.first)}: ${emitExpr(it.second)}" }}}"
        is IrExpr.Index -> "${emitExpr(expr.target)}[${emitExpr(expr.index)}]"
        is IrExpr.Member -> when (expr.name) {
            "length" -> "len(${emitExpr(expr.target)})"
            "isEmpty" -> "(len(${emitExpr(expr.target)}) == 0)"
            "isNotEmpty" -> "(len(${emitExpr(expr.target)}) != 0)"
            else -> "${emitExpr(expr.target)}.${expr.name}"
        }
        is IrExpr.MethodCall -> when (expr.name) {
            "add" -> "${emitExpr(expr.target)}.append(${expr.args.joinToString(", ") { emitExpr(it) }})"
            "isEmpty" -> "(len(${emitExpr(expr.target)}) == 0)"
            "isNotEmpty" -> "(len(${emitExpr(expr.target)}) != 0)"
            else -> "${emitExpr(expr.target)}.${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        }
        is IrExpr.StructCtor -> "${expr.name}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        is IrExpr.TupleLit -> "(${expr.elements.joinToString(", ") { emitExpr(it) }}${if (expr.elements.size == 1) "," else ""})"
        is IrExpr.TupleAccess -> "${emitExpr(expr.target)}[${expr.index}]"
        is IrExpr.CatchExpr -> {
            usesCatchExpr = true
            "_azcatch(lambda: ${emitExpr(expr.expr)}, lambda: ${emitExpr(expr.fallback)})"
        }
        is IrExpr.IfExpr -> "(${emitExpr(expr.thenExpr)} if ${emitExpr(expr.condition)} else ${emitExpr(expr.elseExpr)})"
        is IrExpr.NumCast -> emitNumCast(expr)
        is IrExpr.SlotPattern -> "" /* handled by when lowering */
        is IrExpr.Lambda -> emitLambda(expr)
        is IrExpr.StringTemplate -> {
            val sb = StringBuilder("f\"")
            for (part in expr.parts) {
                when (part) {
                    is IrExpr.IrTemplatePart.Literal -> sb.append(escapeString(part.text).replace("{", "{{").replace("}", "}}"))
                    is IrExpr.IrTemplatePart.Expr -> sb.append("{").append(emitPrintArg(part.expr)).append("}")
                }
            }
            sb.append("\"").toString()
        }
    }

    /** Renders an argument for printing/interpolation, lower-casing booleans to match the interpreter. */
    private fun emitPrintArg(expr: IrExpr): String =
        if (expr.type == IrType.Bool) "(\"true\" if ${emitExpr(expr)} else \"false\")" else emitExpr(expr)

    /** Emits a lambda. Only single-expression bodies are supported (Python lambdas are expression-only). */
    private fun emitLambda(expr: IrExpr.Lambda): String {
        val ps = expr.params.joinToString(", ") { (n, _) -> n }
        val ret = expr.body.singleOrNull() as? IrStmt.Return
        val value = if (ret?.value != null) emitExpr(ret.value) else "None"
        return "lambda${if (ps.isEmpty()) "" else " $ps"}: $value"
    }

    private fun emitNumCast(expr: IrExpr.NumCast): String {
        val src = expr.value.type
        val sourceNumeric = src in IrType.integerTypes || src in IrType.floatTypes || src == IrType.Char
        if (!sourceNumeric) return emitExpr(expr.value)
        val v = emitExpr(expr.value)
        if (expr.type == IrType.Char) return if (src == IrType.Char) v else "chr(int($v))"
        val base = if (src == IrType.Char) "ord($v)" else v
        return when (expr.type) {
            in IrType.floatTypes -> "float($base)"
            else -> "int($base)"
        }
    }

    private fun escapeString(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            .replace("\t", "\\t").replace("\r", "\\r")

    /** Emits [block] into a scratch region of [out] and returns (and removes) the produced text. */
    private fun capture(block: () -> Unit): String {
        val start = out.length
        block()
        val text = out.substring(start)
        out.setLength(start)
        return text
    }

    private fun line(text: String) {
        if (text.isEmpty()) {
            out.appendLine()
        } else {
            repeat(indent) { out.append("    ") }
            out.appendLine(text)
        }
    }
}
