/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.gpu

import org.azora.lang.frontend.*
import org.azora.lang.semantic.SlotPatterns

internal data class GpuValue(
    val type: GpuType,
    val code: String,
    val writable: Boolean = false,
    val storage: Boolean = false,
)

/** Checks GPU value semantics while rendering a function in the selected shader dialect. */
internal class GpuBody(
    private val emitter: GpuEmitter,
    private val function: GpuFunction?,
    private val file: String = function!!.file,
) {
    private val program = emitter.program
    private val code = StringBuilder()
    private val scopes = mutableListOf(mutableMapOf<String, GpuValue>())
    private var localId = 0
    private var indentation = 0
    private var loops = 0

    fun emit(): String {
        val fn = checkNotNull(function)
        if (fn.receiver) bind(fn.decl.receiverName, GpuValue(fn.owner!!.type, "az_self", fn.mutableReceiver), fn.site)
        for ((index, parameter) in fn.parameters.withIndex()) {
            val storage = parameter.port as? GpuPort.Storage
            val name = if (storage == null) emitter.parameter(index) else emitter.storage(index)
            bind(parameter.name, GpuValue(parameter.type, name, storage?.writable == true, storage != null), fn.site)
        }
        line("${emitter.signature(fn)} {")
        indentation++
        val alwaysReturns = statements(fn.decl.body)
        fn.site.require(fn.result == gpuUnit || alwaysReturns, "'${fn.displayName}' must return ${fn.result} on every path")
        indentation--
        line("}")
        return code.toString()
    }

    fun constant(expression: Expr, expected: GpuType?): GpuValue {
        val value = expression(expression, expected)
        site(expression).require(value.type is GpuType.Numeric && value.type != gpuUnit, "global fin needs a numeric or Bool constant")
        return value
    }

    private fun site(expression: Expr) = GpuSite(file, expression.line)
    private fun site(statement: Stmt) = GpuSite(file, statement.line)
    private fun line(text: String) {
        code.append("    ".repeat(indentation)).appendLine(text)
    }

    private fun block(header: String, body: () -> Boolean): Boolean {
        line("$header {")
        indentation++
        scopes.add(mutableMapOf())
        val returns = body()
        scopes.removeAt(scopes.lastIndex)
        indentation--
        line("}")
        return returns
    }

    private fun temporary() = "az_l_${localId++}"
    private fun lookup(name: String): GpuValue? =
        scopes.asReversed().firstNotNullOfOrNull { it[name] } ?: emitter.constants[name]

    private fun bind(name: String, value: GpuValue, site: GpuSite) {
        val scope = scopes.last()
        site.require(name !in scope, "duplicate local '$name'")
        scope[name] = value
    }

    private fun declare(name: String, initializer: GpuValue, mutable: Boolean, site: GpuSite): GpuValue {
        site.require(initializer.type != gpuUnit, "'$name' cannot hold Unit")
        val local = GpuValue(initializer.type, temporary(), mutable)
        line("${emitter.type(local.type)} ${local.code} = ${initializer.code};")
        bind(name, local, site)
        return local
    }

    private fun statements(statements: List<Stmt>): Boolean {
        var returns = false
        for (statement in statements) {
            site(statement).require(!returns, "unreachable GPU statement")
            returns = statement(statement)
        }
        return returns
    }

    private fun statement(stmt: Stmt): Boolean {
        val site = site(stmt)
        when (stmt) {
            is Stmt.VarDecl -> {
                site.require(stmt.valueMutable && !stmt.lazy, "only plain var is available on the GPU")
                declare(stmt.name, expression(stmt.initializer, annotation(stmt.type, site)), true, site)
            }
            is Stmt.FinDecl -> {
                site.require(!stmt.lazy && !stmt.synthesized, "only plain fin is available on the GPU")
                declare(stmt.name, expression(stmt.initializer, annotation(stmt.type, site)), false, site)
            }
            is Stmt.Assignment -> assign(Expr.Identifier(stmt.name, stmt.line), stmt.value, stmt.compoundOp, site)
            is Stmt.MemberAssign -> {
                site.require(stmt.nameExpr == null, "computed member names are unavailable on the GPU")
                assign(Expr.Member(stmt.target, stmt.name, stmt.line), stmt.value, stmt.compoundOp, site)
            }
            is Stmt.IndexAssign -> assign(Expr.Index(stmt.target, stmt.index, stmt.line), stmt.value, stmt.compoundOp, site)
            is Stmt.Return -> {
                val result = function!!.result
                if (stmt.value == null) {
                    site.require(result == gpuUnit, "return requires a $result value")
                    line("return;")
                } else {
                    site.require(result != gpuUnit, "Unit functions use return without a value")
                    line("return ${expression(stmt.value, result).code};")
                }
                return true
            }
            is Stmt.ExprStmt -> {
                val value = expression(stmt.expr, effectAllowed = true)
                // A value expression used for its effects still needs valid statement syntax.
                if (value.type == gpuUnit || stmt.expr is Expr.IncDec) line("${value.code};")
                else line("${emitter.type(value.type)} ${temporary()} = ${value.code};")
            }
            is Stmt.If -> {
                val thenReturns = block("if ${condition(stmt.condition)}") { statements(stmt.thenBranch) }
                val elseReturns = stmt.elseBranch?.let { block("else") { statements(it) } } ?: false
                return thenReturns && elseReturns
            }
            is Stmt.When -> return match(stmt)
            is Stmt.While -> {
                site.require(stmt.label == null, "labelled loops are unavailable on the GPU")
                loop("while ${condition(stmt.condition)}", stmt.body)
            }
            is Stmt.For -> forRange(stmt, site)
            is Stmt.Break -> {
                site.require(loops > 0 && stmt.label == null, "break requires an enclosing GPU loop")
                line("break;")
            }
            is Stmt.Continue -> {
                site.require(loops > 0 && stmt.label == null, "continue requires an enclosing GPU loop")
                line("continue;")
            }
            else -> site.fail("${stmt::class.simpleName} is unavailable on the GPU")
        }
        return false
    }

    private fun condition(expr: Expr): String {
        val value = expression(expr, gpuBool).code
        return if (value.startsWith('(')) value else "($value)"
    }

    private fun loop(header: String, body: List<Stmt>) {
        loops++
        block(header) { statements(body) }
        loops--
    }

    private fun forRange(stmt: Stmt.For, site: GpuSite) {
        val range = stmt.iterable as? Expr.Range ?: site.fail("GPU for loops require an integer range")
        site.require(stmt.label == null && stmt.indexName == null && stmt.step == null && !range.descending,
            "GPU for loops use an ascending, unlabelled range with a unit step")
        val start = expression(range.from, stmt.declaredType?.let { program.type(it, site) })
        site.require(start.type == gpuInt || start.type == gpuUInt, "GPU ranges require Int or UInt bounds")
        val end = expression(range.to, start.type)
        block("") {
            val count = temporary()
            val limit = temporary()
            val type = emitter.type(start.type)
            line("$type $count = ${start.code};")
            line("$type $limit = ${end.code};")
            val advance = if (range.inclusive) {
                // Stop at the upper bound even when it is the largest integer of its type.
                val more = temporary()
                line("bool $more = true;")
                "$more && $count <= $limit; $more = ($count != $limit), $count += $type($more)"
            } else {
                "$count < $limit; ++$count"
            }
            loops++
            block("for (; $advance)") {
                bind(stmt.name, GpuValue(start.type, count), site)
                statements(stmt.body)
            }
            loops--
            false
        }
    }

    private fun match(stmt: Stmt.When): Boolean {
        val site = site(stmt)
        val subject = expression(stmt.scrutinee)
        val aggregate = (subject.type as? GpuType.Named)?.let { program.aggregates.getValue(it.name) }
        val enum = aggregate?.takeIf { it.cases.isNotEmpty() }
        site.require(enum != null || subject.type in comparableScalars, "when requires a scalar or enum subject")
        val seen = mutableSetOf<String>()
        val saved = temporary()
        return block("") {
            line("${emitter.type(subject.type)} $saved = ${subject.code};")
            var allReturn = true
            for ((index, branch) in stmt.branches.withIndex()) {
                val branchSite = GpuSite(file, branch.line)
                val bindings = mutableListOf<Pair<String, GpuValue>>()
                val tests = branch.patterns.map { pattern ->
                    if (enum == null) {
                        "$saved == ${expression(pattern, subject.type).code}"
                    } else {
                        val (case, payload) = enumPattern(enum, pattern)
                        branchSite.require(seen.add(case.name), "duplicate when case '${case.name}'")
                        if (payload.isNotEmpty()) {
                            branchSite.require(branch.patterns.size == 1, "payload bindings require their own when branch")
                            payload.forEachIndexed { position, binding ->
                                val name = (binding as? Expr.Identifier)?.name
                                    ?: site(binding).fail("a payload pattern binds a name or _")
                                if (name != "_") bindings += name to GpuValue(case.payload[position], "$saved.${case.field(position)}")
                            }
                        }
                        val tag = if (enum.tagged) "$saved.az_tag" else saved
                        "$tag == ${case.index}u"
                    }
                }
                val test = tests.joinToString(" || ")
                val exhausted = enum != null && seen.size == enum.cases.size
                branchSite.require(!exhausted || index == stmt.branches.lastIndex, "unreachable when branch")
                branchSite.require(!exhausted || stmt.elseBranch == null, "unreachable when else branch")
                val header = when {
                    exhausted && index == 0 -> ""
                    exhausted -> "else"
                    index == 0 -> "if ($test)"
                    else -> "else if ($test)"
                }
                val returns = block(header) {
                    bindings.forEach { (name, value) -> declare(name, value, false, branchSite) }
                    statements(branch.body)
                }
                allReturn = allReturn && returns
            }
            val fallback = stmt.elseBranch
            if (fallback != null) {
                val returns = block(if (stmt.branches.isEmpty()) "" else "else") { statements(fallback) }
                allReturn && returns
            } else allReturn && enum != null && seen.size == enum.cases.size
        }
    }

    private fun enumPattern(aggregate: GpuAggregate, pattern: Expr): Pair<GpuCase, List<Expr>> {
        val site = site(pattern)
        val qualified = when (pattern) {
            is Expr.Identifier -> qualifiedMember(pattern.name)
            is Expr.Call -> qualifiedMember(pattern.callee).takeIf { pattern.typeArgs.isEmpty() }
            else -> null
        }
        val payload = SlotPatterns.parts(pattern, aggregate.name) ?: qualified?.let { (owner, member) ->
            SlotPatterns.Parts(owner.name, member, (pattern as? Expr.Call)?.args.orEmpty())
        }
        val name = when {
            payload != null -> {
                site.require(payload.slot == aggregate.name, "expected a ${aggregate.name} case")
                payload.variant
            }
            pattern is Expr.InferredMember -> pattern.name
            pattern is Expr.Member && (pattern.target as? Expr.Identifier)?.name == aggregate.name -> pattern.name
            else -> site.fail("expected a ${aggregate.name} case")
        }
        val case = findCase(aggregate, name, site)
        val bindings = payload?.bindings.orEmpty()
        if (payload != null && pattern !is Expr.Identifier) {
            site.require(bindings.size == case.payload.size, "'$name' has ${case.payload.size} payload field(s)")
        }
        return case to bindings
    }

    private fun assign(target: Expr, expr: Expr, compound: TokenType?, site: GpuSite) {
        val left = expression(target)
        site.require(left.writable, "assignment requires a writable var, field or storage element")
        val right = if (compound == null) expression(expr, left.type) else {
            // Parser keeps both the original RHS and its read/modify/write form.
            val binary = expr as? Expr.Binary ?: site.fail("unsupported compound assignment")
            expression(binary.right, left.type).also { checkBinary(compound, left.type, it.type, site) }
        }
        val operator = if (compound == null) "=" else "${binaryOperators.getValue(compound)}="
        line("${left.code} $operator ${right.code};")
    }

    private fun annotation(annotation: TypeAnnotation, site: GpuSite): GpuType? =
        (annotation as? TypeAnnotation.Explicit)?.let { program.type(it.ref, site) }

    private fun expression(expr: Expr, expected: GpuType? = null, effectAllowed: Boolean = false): GpuValue {
        val site = site(expr)
        val value = when (expr) {
            is Expr.IntLiteral -> integer(expr, expected, site)
            is Expr.DoubleLiteral -> floating(expr, site)
            is Expr.BoolLiteral -> GpuValue(gpuBool, expr.value.toString())
            is Expr.Identifier -> lookup(expr.name) ?: qualifiedMember(expr.name)?.let { (owner, name) ->
                makeCase(owner, name, emptyList(), site)
            } ?: site.fail("unknown GPU value '${expr.name}'")
            is Expr.Grouping -> expression(expr.expr, expected, effectAllowed).let { it.copy(code = "(${it.code})") }
            is Expr.Binary -> binary(expr, expected, site)
            is Expr.Unary -> unary(expr, expected, site)
            is Expr.IncDec -> {
                site.require(effectAllowed, "increment and decrement are GPU statements")
                increment(expr, site)
            }
            is Expr.Member -> member(expr, site)
            is Expr.InferredMember -> {
                val aggregate = (expected as? GpuType.Named)?.let { program.aggregates.getValue(it.name) }
                    ?: site.fail("implicit case needs a known enum type")
                if (expr.name.isEmpty()) construct(aggregate.type, expr.ctorArgs.orEmpty(), site)
                else makeCase(aggregate, expr.name, expr.ctorArgs.orEmpty(), site)
            }
            is Expr.Index -> index(expr, site)
            is Expr.Call -> {
                site.require(expr.receiver == null && expr.typeArgs.isEmpty(), "GPU calls require a named non-generic function")
                val type = program.findType(expr.callee)
                if (type != null) construct(type, expr.args, site)
                else {
                    val qualified = qualifiedMember(expr.callee)
                    if (qualified != null) {
                        val (owner, name) = qualified
                        staticCall(owner, name, expr.args, site)
                    } else {
                        val callee = program.functions[expr.callee] ?: site.fail("unknown GPU function '${expr.callee}'")
                        call(callee, null, expr.args, site)
                    }
                }
            }
            is Expr.MethodCall -> method(expr, site, effectAllowed)
            is Expr.Cast -> {
                site.require(expr.kind == CastKind.STATIC, "only static numeric casts are available on the GPU")
                val type = program.type(expr.targetType, site)
                construct(type, listOf(expr.expr), site)
            }
            is Expr.IfExpr -> {
                val condition = expression(expr.condition, gpuBool)
                val yes = expression(expr.thenExpr, expected)
                site.require(yes.type is GpuType.Numeric && yes.type != gpuUnit, "conditional values require a scalar or vector")
                val no = expression(expr.elseExpr, yes.type)
                GpuValue(yes.type, "(${condition.code} ? ${yes.code} : ${no.code})")
            }
            else -> site.fail("${expr::class.simpleName} is unavailable in a GPU expression")
        }
        site.require(!value.storage, "a storage buffer must be indexed to read an element")
        site.require(expected == null || expected == value.type, "expected $expected, got ${value.type}")
        return value
    }

    private fun integer(expr: Expr.IntLiteral, expected: GpuType?, site: GpuSite, negated: Boolean = false): GpuValue {
        site.require(expr.magnitude == null, "integer literal is outside the GPU range")
        val number = if (negated) -expr.value else expr.value
        return when (expected) {
            gpuFloat -> GpuValue(gpuFloat, number.toFloat().toString() + "f")
            gpuUInt -> {
                site.require(number in 0..UInt.MAX_VALUE.toLong(), "UInt literal is outside the GPU range")
                GpuValue(gpuUInt, "${number}u")
            }
            else -> {
                site.require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE, "Int literal is outside the GPU range")
                // The positive magnitude of Int.MIN_VALUE does not fit a signed token.
                val spelling = if (number == Int.MIN_VALUE.toLong()) "(-${Int.MAX_VALUE} - 1)" else number.toString()
                GpuValue(gpuInt, spelling)
            }
        }
    }

    private fun floating(expr: Expr.DoubleLiteral, site: GpuSite): GpuValue {
        val value = expr.value.toFloat()
        site.require(value.isFinite() && (expr.value == 0.0 || value != 0.0f), "Float literal is outside the GPU range")
        return GpuValue(gpuFloat, value.toString() + "f")
    }

    private fun unary(expr: Expr.Unary, expected: GpuType?, site: GpuSite): GpuValue {
        if (expr.op == TokenType.MINUS && expr.operand is Expr.IntLiteral) {
            return integer(expr.operand, expected, site, negated = true)
        }
        val operand = expression(expr.operand, if (expr.op == TokenType.BANG) gpuBool else expected)
        val numeric = operand.type as? GpuType.Numeric
        val operator = when (expr.op) {
            TokenType.BANG -> "!"
            TokenType.MINUS -> {
                site.require(numeric?.scalar in setOf(GpuScalar.INT, GpuScalar.FLOAT), "unary - needs Int or Float components")
                "-"
            }
            TokenType.TILDE -> {
                site.require(numeric?.scalar in integerScalars, "~ requires integer components")
                "~"
            }
            else -> site.fail("unsupported GPU unary operator")
        }
        return GpuValue(operand.type, "($operator${operand.code})")
    }

    private fun binary(expr: Expr.Binary, expected: GpuType?, site: GpuSite): GpuValue {
        val op = expr.op
        val arithmeticExpected = expected.takeIf { op in arithmeticOperators }
        // Infer a width from the typed operand when the other side is an unsuffixed literal.
        val left: GpuValue
        val right: GpuValue
        if (expr.left is Expr.IntLiteral && expr.right !is Expr.IntLiteral) {
            right = expression(expr.right, arithmeticExpected)
            left = expression(expr.left, scalarComponent(right.type))
        } else if (expr.left is Expr.InferredMember) {
            right = expression(expr.right, arithmeticExpected)
            left = expression(expr.left, right.type)
        } else {
            left = expression(expr.left, arithmeticExpected.takeIf { expr.left is Expr.IntLiteral })
            right = expression(expr.right, when (expr.right) {
                is Expr.IntLiteral -> scalarComponent(left.type)
                is Expr.InferredMember -> left.type
                else -> null
            })
        }
        val result = checkBinary(op, left.type, right.type, site)
        val operator = binaryOperators.getValue(op)
        return GpuValue(result, "(${left.code} $operator ${right.code})")
    }

    private fun checkBinary(op: TokenType, left: GpuType, right: GpuType, site: GpuSite): GpuType {
        val l = left as? GpuType.Numeric
        val r = right as? GpuType.Numeric
        val sameNumeric = l != null && r != null && l.scalar == r.scalar && l.scalar in arithmeticScalars
        return when (op) {
            TokenType.AND_AND, TokenType.OR_OR -> {
                site.require(left == gpuBool && right == gpuBool, "logical operators require Bool operands")
                gpuBool
            }
            TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL -> {
                val plainEnum = (left as? GpuType.Named)?.let { program.aggregates[it.name]?.plainEnum } == true
                site.require(left == right && (left in comparableScalars || plainEnum), "equality requires matching scalar or plain enum operands")
                gpuBool
            }
            TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL -> {
                site.require(sameNumeric && l!!.width == 1 && r!!.width == 1, "ordering requires matching numeric scalars")
                gpuBool
            }
            in arithmeticOperators -> {
                site.require(sameNumeric, "operator ${binaryOperators[op]} cannot combine $left and $right")
                val scalarAllowed = op in setOf(TokenType.STAR, TokenType.SLASH)
                site.require(l!!.width == r!!.width || scalarAllowed && (l.width == 1 || r.width == 1),
                    "operator ${binaryOperators[op]} requires compatible vector widths")
                if (op in integerOperators) site.require(l.scalar in integerScalars, "operator ${binaryOperators[op]} requires integers")
                GpuType.Numeric(l.scalar, maxOf(l.width, r.width))
            }
            else -> site.fail("unsupported GPU binary operator $op")
        }
    }

    private fun increment(expr: Expr.IncDec, site: GpuSite): GpuValue {
        val operand = expression(expr.target)
        site.require(function != null && operand.writable && operand.type in setOf(gpuInt, gpuUInt, gpuFloat),
            "increment or decrement requires a writable numeric scalar")
        val op = if (expr.op == TokenType.PLUS_PLUS) "++" else "--"
        val spelling = if (expr.prefix) "$op${operand.code}" else "${operand.code}$op"
        return GpuValue(operand.type, "($spelling)")
    }

    private fun member(expr: Expr.Member, site: GpuSite): GpuValue {
        site.require(expr.nameExpr == null, "computed member names are unavailable on the GPU")
        staticAggregate(expr.target)?.let { return makeCase(it, expr.name, emptyList(), site) }
        val target = expression(expr.target)
        val numeric = target.type as? GpuType.Numeric
        if (numeric != null && numeric.width > 1) {
            val mask = expr.name
            val axes = if (mask.all { it in "xyzw" }) "xyzw" else "rgba"
            site.require(mask.length in 1..4 && mask.all { axes.indexOf(it) in 0 until numeric.width }, "invalid vector swizzle '$mask'")
            return GpuValue(GpuType.Numeric(numeric.scalar, mask.length), "${target.code}.$mask",
                target.writable && mask.toSet().size == mask.length)
        }
        val aggregate = (target.type as? GpuType.Named)?.let { program.aggregates.getValue(it.name) }
        val field = aggregate?.fields?.firstOrNull { it.name == expr.name }
            ?: site.fail("${target.type} has no GPU field '${expr.name}'")
        return GpuValue(field.type, "${target.code}.${emitter.field(field.name)}", target.writable && field.mutable)
    }

    private fun index(expr: Expr.Index, site: GpuSite): GpuValue {
        val named = (expr.target as? Expr.Identifier)?.let { lookup(it.name) }
        val target = if (named?.storage == true) named else expression(expr.target)
        val offset = expression(expr.index)
        site.require(offset.type == gpuInt || offset.type == gpuUInt, "index requires Int or UInt")
        val type = if (target.storage) target.type else {
            val vector = target.type as? GpuType.Numeric
            if (vector == null || vector.width == 1) site.fail("only GPU vectors and storage buffers can be indexed")
            GpuType.Numeric(vector.scalar)
        }
        return GpuValue(type, "${target.code}[${offset.code}]", target.writable)
    }

    private fun staticAggregate(target: Expr): GpuAggregate? {
        val name = (target as? Expr.Identifier)?.name ?: return null
        return program.aggregates[name].takeIf { lookup(name) == null }
    }

    private fun method(expr: Expr.MethodCall, site: GpuSite, effectAllowed: Boolean): GpuValue {
        site.require(expr.typeArgs.isEmpty(), "GPU methods cannot take generic arguments")
        staticAggregate(expr.target)?.let { return staticCall(it, expr.name, expr.args, site) }
        val receiver = expression(expr.target)
        val name = "${receiver.type}.${expr.name}"
        val callee = program.functions[name] ?: site.fail("unknown GPU method '$name'")
        site.require(effectAllowed || !callee.mutableReceiver, "a mutable method call must be a GPU statement")
        return call(callee, receiver, expr.args, site)
    }

    // The common parser represents qualified names with its reserved "__" separator.
    private fun qualifiedMember(name: String): Pair<GpuAggregate, String>? {
        val separator = name.indexOf("__")
        if (separator <= 0) return null
        val owner = program.aggregates[name.substring(0, separator)] ?: return null
        return owner to name.substring(separator + 2)
    }

    private fun staticCall(owner: GpuAggregate, name: String, args: List<Expr>, site: GpuSite): GpuValue {
        val callee = program.functions["${owner.name}.$name"]
        return if (callee != null) call(callee, null, args, site) else makeCase(owner, name, args, site)
    }

    private fun call(callee: GpuFunction, receiver: GpuValue?, args: List<Expr>, site: GpuSite): GpuValue {
        val caller = function ?: site.fail("global fin cannot call functions")
        site.require(callee.stage == null, "shader entry points cannot be called")
        site.require(callee.receiver == (receiver != null), "'${callee.displayName}' has a different receiver form")
        site.require(!callee.mutableReceiver || receiver?.writable == true, "mutable method needs a writable receiver")
        emitter.calls.getOrPut(caller) { mutableSetOf() }.add(callee)
        val arguments = listOfNotNull(receiver?.code) + arguments(args, callee.parameters.map { it.type }, site)
        return GpuValue(callee.result, "${callee.symbol}(${arguments.joinToString()})")
    }

    private fun findCase(aggregate: GpuAggregate, name: String, site: GpuSite): GpuCase =
        aggregate.cases.firstOrNull { it.name == name } ?: site.fail("${aggregate.name} has no case '$name'")

    private fun makeCase(aggregate: GpuAggregate, name: String, args: List<Expr>, site: GpuSite): GpuValue {
        val case = findCase(aggregate, name, site)
        site.require(function != null || !aggregate.tagged, "global fin cannot construct a tagged enum")
        return GpuValue(aggregate.type, emitter.caseConstructor(aggregate, case, arguments(args, case.payload, site)))
    }

    private fun construct(type: GpuType, args: List<Expr>, site: GpuSite): GpuValue {
        val arguments = when (type) {
            is GpuType.Named -> {
                val aggregate = program.aggregates.getValue(type.name)
                site.require(function != null && aggregate.cases.isEmpty(), "only packs can be constructed this way")
                arguments(args, aggregate.fields.map { it.type }, site)
            }
            is GpuType.Numeric -> {
                site.require(type != gpuUnit, "cannot construct Unit")
                if (args.size == type.width && type.width > 1) {
                    arguments(args, List(type.width) { GpuType.Numeric(type.scalar) }, site)
                } else {
                    site.require(args.size == 1, "$type requires one value or ${type.width} scalar components")
                    val input = expression(args.single(), GpuType.Numeric(type.scalar).takeIf { args.single() is Expr.IntLiteral })
                    val numeric = input.type as? GpuType.Numeric
                    site.require(numeric != null && (numeric.width == 1 || numeric.width == type.width) &&
                        input.type != gpuUnit && (type.width == 1 || input.type != gpuBool), "cannot construct $type from ${input.type}")
                    if (type.width > 1 && numeric!!.width == 1) {
                        // Metal and GLSL accept vector splats; explicit replication in HLSL
                        // evaluates complex input only once by using its scalar-to-vector cast.
                        if (emitter.target == GpuTarget.DIRECTX) return GpuValue(type, "(${emitter.type(type)})(${input.code})")
                    }
                    listOf(input.code)
                }
            }
        }
        return GpuValue(type, emitter.constructor(type, arguments))
    }

    private fun arguments(args: List<Expr>, types: List<GpuType>, site: GpuSite): List<String> {
        site.require(args.size == types.size, "expected ${types.size} argument(s), got ${args.size}")
        return args.zip(types) { arg, type -> expression(arg, type).code }
    }

    private fun scalarComponent(type: GpuType): GpuType =
        if (type is GpuType.Numeric) GpuType.Numeric(type.scalar) else type

    companion object {
        private val comparableScalars = setOf(gpuInt, gpuUInt, gpuBool, gpuFloat)
        private val integerScalars = setOf(GpuScalar.INT, GpuScalar.UINT)
        private val arithmeticScalars = integerScalars + GpuScalar.FLOAT
        private val integerOperators = setOf(TokenType.PERCENT, TokenType.AMP, TokenType.PIPE, TokenType.CARET,
            TokenType.SHIFT_LEFT, TokenType.SHIFT_RIGHT)
        private val arithmeticOperators = integerOperators + setOf(TokenType.PLUS, TokenType.MINUS, TokenType.STAR, TokenType.SLASH)
        private val binaryOperators = mapOf(
            TokenType.PLUS to "+", TokenType.MINUS to "-", TokenType.STAR to "*", TokenType.SLASH to "/",
            TokenType.PERCENT to "%", TokenType.AMP to "&", TokenType.PIPE to "|", TokenType.CARET to "^",
            TokenType.SHIFT_LEFT to "<<", TokenType.SHIFT_RIGHT to ">>", TokenType.AND_AND to "&&", TokenType.OR_OR to "||",
            TokenType.EQUAL_EQUAL to "==", TokenType.BANG_EQUAL to "!=", TokenType.LESS to "<",
            TokenType.LESS_EQUAL to "<=", TokenType.GREATER to ">", TokenType.GREATER_EQUAL to ">=",
        )
    }
}
