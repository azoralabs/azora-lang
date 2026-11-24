/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.gpu

import org.azora.lang.frontend.*
import org.azora.lang.semantic.SlotPatterns

/**
 * A value while a body is rendered: its type, its spelling in the target, whether it may be
 * assigned, and - for buffers, uniform blocks, textures, samplers and workgroup arrays - which
 * resource it is ([type] is then the element, block, texture or array type).
 */
internal data class GpuValue(
    val type: GpuType,
    val code: String,
    val writable: Boolean = false,
    val resource: GpuResource? = null,
)

/**
 * Checks GPU value semantics while rendering a function in the selected shader dialect.
 * [resources] (SPIR-V and DXIL only) are the globals this instance's resource parameters are
 * bound to: those targets cannot pass a buffer or a texture, so a function is rendered once per
 * set of resources it is called with.
 */
internal class GpuBody(
    private val emitter: GpuEmitter,
    private val function: GpuFunction?,
    private val file: String = function!!.file,
    private val resources: List<GpuValue?> = emptyList(),
    private val symbol: String = function?.symbol.orEmpty(),
) {
    private val program = emitter.program
    private val target = emitter.target
    private val code = StringBuilder()
    private val scopes = mutableListOf(mutableMapOf<String, GpuValue>())
    private var localId = 0
    private var indentation = 0
    private var loops = 0

    fun emit(): String {
        val fn = checkNotNull(function)
        if (fn.receiver) bind(fn.decl.receiverName, GpuValue(fn.owner!!.type, "az_self", fn.mutableReceiver), fn.site)
        for ((index, parameter) in fn.parameters.withIndex()) {
            val resource = parameter.resource
            val value = when {
                resource == null -> GpuValue(parameter.type, emitter.parameter(index))
                target == GpuTarget.METAL -> GpuValue(parameter.type, emitter.parameter(index),
                    resource == GpuResource.STORAGE || resource == GpuResource.WORKGROUP, resource)
                else -> resources.getOrNull(index) ?: fn.site.fail("internal: unbound GPU resource '${parameter.name}'")
            }
            bind(parameter.name, value, fn.site)
        }
        line("${emitter.signature(fn, symbol)} {")
        indentation++
        val alwaysReturns = statements(fn.decl.body)
        fn.site.require(fn.result == gpuUnit || alwaysReturns, "'${fn.displayName}' must return ${fn.result} on every path")
        indentation--
        line("}")
        return code.toString()
    }

    /** A global constant's value: a number, vector or matrix, or an array of them written as `[a, b, c]`. */
    fun constant(expression: Expr, expected: GpuType?): GpuValue {
        val site = site(expression)
        if (expression is Expr.ArrayLiteral) {
            val array = expected as? GpuType.FixedArray
            val element = array?.element
            site.require(expression.elements.isNotEmpty(), "a constant array lists its values")
            val values = expression.elements.map { expression(it, element ?: inferredLiteral(it)) }
            val type = GpuType.FixedArray(values.first().type, values.size)
            site.require(array == null || array == type, "expected $array, got $type")
            site.require(values.all { it.type == type.element } && constantElement(type.element),
                "a constant array holds numbers, vectors or matrices of one type")
            return GpuValue(type, emitter.arrayLiteral(type, values.map { it.code }))
        }
        val value = expression(expression, expected)
        site.require(constantElement(value.type), "global fin needs a numeric, Bool or matrix constant")
        return value
    }

    private fun constantElement(type: GpuType) = type is GpuType.Matrix || type is GpuType.Numeric && type != gpuUnit

    private fun inferredLiteral(expr: Expr): GpuType? = when (expr) {
        is Expr.DoubleLiteral -> gpuFloat
        is Expr.Unary -> if (expr.operand is Expr.DoubleLiteral) gpuFloat else null
        else -> null
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
        site.require(initializer.resource == null || initializer.resource == GpuResource.UNIFORM,
            "'$name' cannot hold a ${initializer.type} resource: use it where it is")
        site.require(program.valueType(initializer.type), "'$name' cannot hold ${initializer.type}")
        val local = GpuValue(initializer.type, temporary(), mutable)
        line("${emitter.declaration(local.type, local.code)} = ${initializer.code};")
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

    private fun initializer(expr: Expr, type: GpuType?, site: GpuSite): GpuValue {
        val array = type as? GpuType.FixedArray
        if (expr is Expr.ArrayLiteral) {
            site.require(array != null, "an array literal needs a declared Array<Element, Size> type")
            val elements = if (expr.elements.isEmpty()) {
                site.require(array!!.size <= 256, "[] fills up to 256 values with zero; larger arrays belong in workgroup or storage memory")
                List(array.size) { emitter.zero(array.element) }
            } else {
                site.require(expr.elements.size == array!!.size, "expected ${array.size} values, got ${expr.elements.size}")
                expr.elements.map { expression(it, array.element).code }
            }
            return GpuValue(array!!, emitter.arrayLiteral(array, elements))
        }
        return expression(expr, type)
    }

    private fun statement(stmt: Stmt): Boolean {
        val site = site(stmt)
        when (stmt) {
            is Stmt.VarDecl -> {
                site.require(stmt.valueMutable && !stmt.lazy, "only plain var is available on the GPU")
                declare(stmt.name, initializer(stmt.initializer, annotation(stmt.type, site), site), true, site)
            }
            is Stmt.FinDecl -> {
                site.require(!stmt.lazy && !stmt.synthesized, "only plain fin is available on the GPU")
                declare(stmt.name, initializer(stmt.initializer, annotation(stmt.type, site), site), false, site)
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
                else line("${emitter.declaration(value.type, temporary())} = ${value.code};")
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
        return if (value.startsWith('(') && value.endsWith(')')) value else "($value)"
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
        val declared = stmt.declaredType?.let { program.type(it, site) }
        val start = expression(range.from, declared ?: if (range.from is Expr.IntLiteral && range.to !is Expr.IntLiteral) null else null)
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
        site.require(left.type !is GpuType.FixedArray && left.type !is GpuType.Atomic,
            "arrays are assigned element by element; atomics through atomicStore")
        val right = if (compound == null) expression(expr, left.type) else {
            // Parser keeps both the original RHS and its read/modify/write form.
            val binary = expr as? Expr.Binary ?: site.fail("unsupported compound assignment")
            val value = expression(binary.right, if (binary.right is Expr.IntLiteral || binary.right is Expr.DoubleLiteral) scalarComponent(left.type) else null)
            checkBinary(compound, left.type, value.type, site).also {
                site.require(it == left.type, "operator ${binaryOperators[compound]}= would change ${left.type} into $it")
            }
            if (left.type is GpuType.Matrix && compound == TokenType.STAR && value.type is GpuType.Matrix) {
                line("${left.code} = ${multiply(left, value)};")
                return
            }
            value
        }
        val operator = if (compound == null) "=" else "${binaryOperators.getValue(compound)}="
        line("${left.code} $operator ${right.code};")
    }

    private fun annotation(annotation: TypeAnnotation, site: GpuSite): GpuType? =
        (annotation as? TypeAnnotation.Explicit)?.let { program.type(it.ref, site) }

    private fun expression(expr: Expr, expected: GpuType? = null, effectAllowed: Boolean = false, resource: Boolean = false): GpuValue {
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
                    ?: (if (expr.name.isEmpty() && expected != null) return construct(expected, expr.ctorArgs.orEmpty(), site) else null)
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
                        val callee = program.functions[expr.callee]
                        when {
                            callee != null -> call(callee, null, expr.args, site)
                            expr.callee in gpuIntrinsicNames -> intrinsic(expr.callee, expr.args, expected, effectAllowed, site)
                            else -> site.fail("unknown GPU function '${expr.callee}'")
                        }
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
                site.require(yes.type is GpuType.Numeric && yes.type != gpuUnit || yes.type is GpuType.Matrix,
                    "conditional values require a scalar, vector or matrix")
                val no = expression(expr.elseExpr, yes.type)
                GpuValue(yes.type, "(${condition.code} ? ${yes.code} : ${no.code})")
            }
            else -> site.fail("${expr::class.simpleName} is unavailable in a GPU expression")
        }
        if (!resource) {
            site.require(value.resource == null || value.resource == GpuResource.UNIFORM,
                when (value.resource) {
                    GpuResource.STORAGE, GpuResource.READ_STORAGE, GpuResource.CONSTANT, GpuResource.WORKGROUP -> "a buffer or workgroup array must be indexed to read an element"
                    else -> "a ${value.type} is used through its methods or passed on"
                })
        }
        site.require(expected == null || expected == value.type, "expected $expected, got ${value.type}")
        return value
    }

    private fun integer(expr: Expr.IntLiteral, expected: GpuType?, site: GpuSite, negated: Boolean = false): GpuValue {
        site.require(expr.magnitude == null, "integer literal is outside the GPU range")
        val number = if (negated) -expr.value else expr.value
        return when (expected) {
            gpuFloat -> GpuValue(gpuFloat, floatSpelling(number.toFloat()))
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
        return GpuValue(gpuFloat, floatSpelling(value))
    }

    // Kotlin/Native and the JVM spell some floats in exponent form ("1.0E-6"): every target reads that with an 'f'.
    private fun floatSpelling(value: Float): String {
        val text = value.toString()
        return (if ('.' in text || 'E' in text || 'e' in text) text else "$text.0") + "f"
    }

    private fun unary(expr: Expr.Unary, expected: GpuType?, site: GpuSite): GpuValue {
        if (expr.op == TokenType.MINUS && expr.operand is Expr.IntLiteral) {
            return integer(expr.operand, expected, site, negated = true)
        }
        val operand = expression(expr.operand, if (expr.op == TokenType.BANG && expected == null) null else expected.takeIf { expr.op != TokenType.BANG })
        val numeric = operand.type as? GpuType.Numeric
        return when (expr.op) {
            TokenType.BANG -> {
                site.require(numeric?.scalar == GpuScalar.BOOL, "! requires Bool")
                if (numeric!!.width > 1 && target == GpuTarget.VULKAN) GpuValue(operand.type, "not(${operand.code})")
                else GpuValue(operand.type, "(!${operand.code})")
            }
            TokenType.MINUS -> {
                site.require(numeric?.scalar in setOf(GpuScalar.INT, GpuScalar.FLOAT) || operand.type is GpuType.Matrix,
                    "unary - needs Int or Float components")
                GpuValue(operand.type, "(-${operand.code})")
            }
            TokenType.TILDE -> {
                site.require(numeric?.scalar in integerScalars, "~ requires integer components")
                GpuValue(operand.type, "(~${operand.code})")
            }
            else -> site.fail("unsupported GPU unary operator")
        }
    }

    private fun binary(expr: Expr.Binary, expected: GpuType?, site: GpuSite): GpuValue {
        val op = expr.op
        val arithmeticExpected = expected.takeIf { op in arithmeticOperators && it !is GpuType.Matrix }
        // Infer a width from the typed operand when the other side is an unsuffixed literal.
        val left: GpuValue
        val right: GpuValue
        if (literal(expr.left) && !literal(expr.right)) {
            right = expression(expr.right, arithmeticExpected.takeIf { it?.let(::scalarComponent) == it })
            left = expression(expr.left, scalarComponent(right.type))
        } else if (expr.left is Expr.InferredMember) {
            right = expression(expr.right, arithmeticExpected)
            left = expression(expr.left, right.type)
        } else {
            left = expression(expr.left, arithmeticExpected.takeIf { literal(expr.left) }?.let(::scalarComponent))
            right = expression(expr.right, when {
                literal(expr.right) -> scalarComponent(left.type)
                expr.right is Expr.InferredMember -> left.type
                else -> null
            })
        }
        val result = checkBinary(op, left.type, right.type, site)
        return GpuValue(result, render(op, left, right, result))
    }

    private fun literal(expr: Expr): Boolean = expr is Expr.IntLiteral ||
        expr is Expr.Unary && expr.op == TokenType.MINUS && expr.operand is Expr.IntLiteral

    private fun render(op: TokenType, left: GpuValue, right: GpuValue, result: GpuType): String {
        val operator = binaryOperators.getValue(op)
        val l = left.type as? GpuType.Numeric
        val r = right.type as? GpuType.Numeric
        if (op == TokenType.STAR && (left.type is GpuType.Matrix || right.type is GpuType.Matrix) &&
            !(l != null && l.width == 1) && !(r != null && r.width == 1)) return multiply(left, right)
        val vectorResult = result is GpuType.Numeric && result.scalar == GpuScalar.BOOL && result.width > 1
        if (vectorResult && target == GpuTarget.VULKAN) {
            // GLSL compares vectors through functions, both sides the same width.
            val width = (result as GpuType.Numeric).width
            val function = when (op) {
                TokenType.LESS -> "lessThan"
                TokenType.LESS_EQUAL -> "lessThanEqual"
                TokenType.GREATER -> "greaterThan"
                TokenType.GREATER_EQUAL -> "greaterThanEqual"
                TokenType.EQUAL_EQUAL -> "equal"
                else -> "notEqual"
            }
            return "$function(${spread(left, width)}, ${spread(right, width)})"
        }
        return "(${left.code} $operator ${right.code})"
    }

    /** Matrix products: column-major everywhere; DXIL stores each column as a row, so it multiplies on the other side. */
    private fun multiply(left: GpuValue, right: GpuValue): String =
        if (target == GpuTarget.DIRECTX) "mul(${right.code}, ${left.code})" else "(${left.code} * ${right.code})"

    private fun spread(value: GpuValue, width: Int): String {
        val numeric = value.type as GpuType.Numeric
        if (numeric.width == width) return value.code
        val vector = GpuType.Numeric(numeric.scalar, width)
        return if (target == GpuTarget.DIRECTX) "((${emitter.type(vector)})(${value.code}))" else "${emitter.type(vector)}(${value.code})"
    }

    private fun checkBinary(op: TokenType, left: GpuType, right: GpuType, site: GpuSite): GpuType {
        val l = left as? GpuType.Numeric
        val r = right as? GpuType.Numeric
        val sameScalar = l != null && r != null && l.scalar == r.scalar
        val widths = l != null && r != null && (l.width == r.width || l.width == 1 || r.width == 1)
        val width = maxOf(l?.width ?: 1, r?.width ?: 1)
        if (left is GpuType.Matrix || right is GpuType.Matrix) {
            val m = (left as? GpuType.Matrix) ?: (right as GpuType.Matrix)
            return when {
                op == TokenType.STAR && left == right -> m
                op == TokenType.STAR && left is GpuType.Matrix && right == m.column -> m.column
                op == TokenType.STAR && right is GpuType.Matrix && left == m.column -> m.column
                op in setOf(TokenType.STAR, TokenType.SLASH) && right == gpuFloat && left is GpuType.Matrix -> m
                op == TokenType.STAR && left == gpuFloat -> m
                op in setOf(TokenType.PLUS, TokenType.MINUS) && left == right -> m
                else -> site.fail("operator ${binaryOperators[op]} cannot combine $left and $right")
            }
        }
        return when (op) {
            TokenType.AND_AND, TokenType.OR_OR -> {
                site.require(left == gpuBool && right == gpuBool, "logical operators require Bool operands")
                gpuBool
            }
            TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL -> {
                val plainEnum = (left as? GpuType.Named)?.let { program.aggregates[it.name]?.plainEnum } == true
                if (l != null && r != null && (l.width > 1 || r.width > 1)) {
                    site.require(sameScalar && widths, "equality requires matching vectors")
                    GpuType.Numeric(GpuScalar.BOOL, width)
                } else {
                    site.require(left == right && (left in comparableScalars || plainEnum), "equality requires matching scalar or plain enum operands")
                    gpuBool
                }
            }
            TokenType.LESS, TokenType.LESS_EQUAL, TokenType.GREATER, TokenType.GREATER_EQUAL -> {
                site.require(sameScalar && widths && l!!.scalar in arithmeticScalars, "ordering requires matching numeric operands")
                if (width == 1) gpuBool else GpuType.Numeric(GpuScalar.BOOL, width)
            }
            in arithmeticOperators -> {
                site.require(sameScalar && l!!.scalar in arithmeticScalars, "operator ${binaryOperators[op]} cannot combine $left and $right")
                site.require(widths, "operator ${binaryOperators[op]} requires compatible vector widths")
                if (op in integerOperators) site.require(l!!.scalar in integerScalars, "operator ${binaryOperators[op]} requires integers (use fmod for Float)")
                GpuType.Numeric(l!!.scalar, width)
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
        val target = expression(expr.target, resource = true)
        val offset = expression(expr.index, if (expr.index is Expr.IntLiteral) gpuInt else null)
        site.require(offset.type == gpuInt || offset.type == gpuUInt, "index requires Int or UInt")
        val (type, writable) = when (target.resource) {
            GpuResource.STORAGE -> target.type to true
            GpuResource.READ_STORAGE, GpuResource.CONSTANT -> target.type to false
            GpuResource.WORKGROUP -> (target.type as GpuType.FixedArray).element to true
            null, GpuResource.UNIFORM -> when (val type = target.type) {
                is GpuType.FixedArray -> type.element to target.writable
                is GpuType.Matrix -> type.column to target.writable
                is GpuType.Numeric -> {
                    site.require(type.width > 1, "only GPU vectors, matrices, arrays and buffers can be indexed")
                    GpuType.Numeric(type.scalar) to target.writable
                }
                else -> site.fail("${target.type} cannot be indexed")
            }
            else -> site.fail("a ${target.type} is read through its methods")
        }
        return GpuValue(type, "${target.code}[${offset.code}]", writable)
    }

    private fun staticAggregate(target: Expr): GpuAggregate? {
        val name = (target as? Expr.Identifier)?.name ?: return null
        return program.aggregates[name].takeIf { lookup(name) == null }
    }

    private fun method(expr: Expr.MethodCall, site: GpuSite, effectAllowed: Boolean): GpuValue {
        site.require(expr.typeArgs.isEmpty(), "GPU methods cannot take generic arguments")
        staticAggregate(expr.target)?.let { return staticCall(it, expr.name, expr.args, site) }
        val receiver = expression(expr.target, resource = true)
        if (receiver.type is GpuType.Texture) return textureMethod(receiver, expr.name, expr.args, effectAllowed, site)
        site.require(receiver.resource == null || receiver.resource == GpuResource.UNIFORM, "a ${receiver.type} has no methods")
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
        site.require(function != null, "global fin cannot call functions")
        site.require(callee.stage == null, "shader entry points cannot be called")
        site.require(callee.receiver == (receiver != null), "'${callee.displayName}' has a different receiver form")
        site.require(!callee.mutableReceiver || receiver?.writable == true, "mutable method needs a writable receiver")
        site.require(args.size == callee.parameters.size, "expected ${callee.parameters.size} argument(s), got ${args.size}")
        val values = mutableListOf<String>()
        val bound = mutableListOf<GpuValue?>()
        receiver?.let { values += it.code }
        for ((arg, parameter) in args.zip(callee.parameters)) {
            val wanted = parameter.resource
            if (wanted == null) {
                values += expression(arg, parameter.type).code
                bound += null
                continue
            }
            val value = expression(arg, resource = true)
            val fits = when (wanted) {
                GpuResource.READ_STORAGE -> value.resource == GpuResource.STORAGE || value.resource == GpuResource.READ_STORAGE ||
                    // SPIR-V and DXIL hold every read-only buffer alike; Metal's constant space is its own.
                    target != GpuTarget.METAL && value.resource == GpuResource.CONSTANT
                else -> value.resource == wanted
            }
            site.require(fits && value.type == parameter.type,
                "'${parameter.name}' takes ${describe(wanted)} of ${parameter.type}, got ${value.resource?.let(::describe) ?: "a value"} of ${value.type}")
            if (target == GpuTarget.METAL) values += value.code
            bound += value
        }
        val symbol = emitter.instance(callee, bound)
        return GpuValue(callee.result, "$symbol(${values.joinToString()})")
    }

    private fun describe(resource: GpuResource) = when (resource) {
        GpuResource.STORAGE -> "a writable buffer"
        GpuResource.READ_STORAGE -> "a buffer"
        GpuResource.CONSTANT -> "a @Constant buffer"
        GpuResource.UNIFORM -> "a uniform block"
        GpuResource.TEXTURE -> "a texture"
        GpuResource.SAMPLER -> "a sampler"
        GpuResource.WORKGROUP -> "a workgroup array"
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
            is GpuType.Matrix -> {
                if (args.size == 1) {
                    val diagonal = expression(args.single(), gpuFloat)
                    // A scalar makes a diagonal matrix (DXIL would fill every element).
                    if (target == GpuTarget.DIRECTX) {
                        val identity = (0 until type.size).joinToString { c -> (0 until type.size).joinToString { r -> if (r == c) "1.0f" else "0.0f" } }
                        return GpuValue(type, "((${diagonal.code}) * ${emitter.type(type)}($identity))")
                    }
                    listOf(diagonal.code)
                } else arguments(args, List(type.size) { type.column }, site)
            }
            is GpuType.Numeric -> {
                site.require(type != gpuUnit, "cannot construct Unit")
                val scalar = GpuType.Numeric(type.scalar)
                if (args.size == 1) {
                    val input = expression(args.single(), scalar.takeIf { literal(args.single()) || args.single() is Expr.DoubleLiteral && type.scalar == GpuScalar.FLOAT })
                    val numeric = input.type as? GpuType.Numeric
                    site.require(numeric != null && (numeric.width == 1 || numeric.width == type.width) &&
                        input.type != gpuUnit, "cannot construct $type from ${input.type}")
                    if (type.width > 1 && numeric!!.width == 1) {
                        // Metal and GLSL accept vector splats; DXIL spreads a scalar by casting it.
                        if (target == GpuTarget.DIRECTX) {
                            val converted = if (numeric.scalar == type.scalar) input.code else "${type.scalar.shaderName}(${input.code})"
                            return GpuValue(type, "((${emitter.type(type)})(${converted}))")
                        }
                    }
                    listOf(input.code)
                } else {
                    // Components and smaller vectors joined into a vector: Float4(position, 1.0).
                    var count = 0
                    val parts = args.map { arg ->
                        val value = expression(arg, scalar.takeIf { literal(arg) || arg is Expr.DoubleLiteral })
                        val numeric = value.type as? GpuType.Numeric
                        site.require(numeric != null && numeric != gpuUnit && (numeric.scalar == type.scalar ||
                            numeric.scalar in arithmeticScalars && type.scalar in arithmeticScalars),
                            "$type is made of ${type.scalar.sourceName} components, not ${value.type}")
                        count += numeric!!.width
                        // Each component converted explicitly: Int2(x, y) from Floats reads alike everywhere.
                        if (numeric.scalar == type.scalar) value.code else emitter.constructor(GpuType.Numeric(type.scalar, numeric.width), listOf(value.code))
                    }
                    site.require(count == type.width, "$type needs ${type.width} components, got $count")
                    parts
                }
            }
            else -> site.fail("cannot construct $type")
        }
        return GpuValue(type, emitter.constructor(type, arguments))
    }

    private fun arguments(args: List<Expr>, types: List<GpuType>, site: GpuSite): List<String> {
        site.require(args.size == types.size, "expected ${types.size} argument(s), got ${args.size}")
        return args.zip(types) { arg, type -> expression(arg, type).code }
    }

    private fun scalarComponent(type: GpuType): GpuType = when (type) {
        is GpuType.Numeric -> GpuType.Numeric(type.scalar)
        is GpuType.Matrix -> gpuFloat
        else -> type
    }

    // ── Built-in functions ──

    /** The arguments of a component-wise built-in, agreed on one type: literals take the others' scalar. */
    private fun generic(args: List<Expr>, integers: Boolean, site: GpuSite, name: String): Pair<GpuType.Numeric, List<String>> {
        val typed = arrayOfNulls<GpuValue>(args.size)
        for ((index, arg) in args.withIndex()) if (!literal(arg)) typed[index] = expression(arg)
        val known = typed.filterNotNull().map { it.type as? GpuType.Numeric ?: site.fail("$name takes numbers, not ${it.type}") }
        val scalar = known.firstOrNull()?.scalar ?: if (integers) GpuScalar.INT else GpuScalar.FLOAT
        site.require(known.all { it.scalar == scalar }, "$name needs arguments of one component type")
        site.require(scalar == GpuScalar.FLOAT || integers && scalar in integerScalars, "$name takes ${if (integers) "Int, UInt or " else ""}Float components")
        val width = known.maxOfOrNull { it.width } ?: 1
        site.require(known.all { it.width == 1 || it.width == width }, "$name needs arguments of one width")
        for ((index, arg) in args.withIndex()) if (typed[index] == null) typed[index] = expression(arg, GpuType.Numeric(scalar))
        val type = GpuType.Numeric(scalar, width)
        return type to typed.map { spread(it!!, width) }
    }

    private fun intrinsic(name: String, args: List<Expr>, expected: GpuType?, effectAllowed: Boolean, site: GpuSite): GpuValue {
        site.require(function != null, "global fin cannot call $name")
        gpuIntrinsics[name]?.let { builtin ->
            site.require(args.size == builtin.arity, "$name takes ${builtin.arity} argument(s)")
            val (type, codes) = generic(args, builtin.integers, site, name)
            val result = when (builtin.result) {
                GpuResultShape.SAME -> type
                GpuResultShape.SCALAR -> GpuType.Numeric(type.scalar)
                GpuResultShape.BOOL -> GpuType.Numeric(GpuScalar.BOOL, type.width)
            }
            val spelling = when (target) {
                GpuTarget.METAL -> builtin.metal
                GpuTarget.VULKAN -> builtin.glsl
                GpuTarget.DIRECTX -> builtin.hlsl
            }
            if (spelling.isNotEmpty()) return GpuValue(result, "$spelling(${codes.joinToString()})")
            return GpuValue(result, when (name) {
                "saturate" -> "clamp(${codes[0]}, ${emitter.constructor(type, listOf("0.0"))}, ${emitter.constructor(type, listOf("1.0"))})"
                "fmod" -> "${emitter.helper("fmod", type)}(${codes.joinToString()})"
                else -> site.fail("internal: no spelling for $name")
            })
        }
        fun value(index: Int, type: GpuType? = null) = expression(args[index], type)
        fun arity(count: Int) = site.require(args.size == count, "$name takes $count argument(s)")
        return when (name) {
            "cross" -> {
                arity(2)
                val float3 = GpuType.Numeric(GpuScalar.FLOAT, 3)
                GpuValue(float3, "cross(${value(0, float3).code}, ${value(1, float3).code})")
            }
            "refract" -> {
                arity(3)
                val incident = value(0)
                site.require(incident.type is GpuType.Numeric && (incident.type as GpuType.Numeric).scalar == GpuScalar.FLOAT, "refract takes Float vectors")
                GpuValue(incident.type, "refract(${incident.code}, ${value(1, incident.type).code}, ${value(2, gpuFloat).code})")
            }
            "select" -> {
                // select(whenFalse, whenTrue, condition), component by component when the condition is a vector.
                arity(3)
                val (type, codes) = generic(args.take(2), integers = true, site = site, name = name)
                val condition = value(2)
                val bool = condition.type as? GpuType.Numeric
                site.require(bool?.scalar == GpuScalar.BOOL && (bool.width == 1 || bool.width == type.width),
                    "select's condition is a Bool or a Bool vector as wide as its values")
                val vector = GpuType.Numeric(type.scalar, type.width)
                val no = codes[0]
                val yes = codes[1]
                GpuValue(vector, when (target) {
                    GpuTarget.METAL -> "select($no, $yes, ${condition.code})"
                    GpuTarget.VULKAN -> if (bool!!.width == 1) "(${condition.code} ? $yes : $no)" else "mix($no, $yes, ${condition.code})"
                    GpuTarget.DIRECTX -> "select(${condition.code}, $yes, $no)"
                })
            }
            "any", "all" -> {
                arity(1)
                val vector = value(0)
                site.require((vector.type as? GpuType.Numeric)?.scalar == GpuScalar.BOOL, "$name takes a Bool vector")
                GpuValue(gpuBool, if ((vector.type as GpuType.Numeric).width == 1) vector.code else "$name(${vector.code})")
            }
            "transpose" -> {
                arity(1)
                val matrix = value(0)
                site.require(matrix.type is GpuType.Matrix, "transpose takes a matrix")
                GpuValue(matrix.type, "transpose(${matrix.code})")
            }
            "determinant" -> {
                arity(1)
                val matrix = value(0)
                site.require(matrix.type is GpuType.Matrix, "determinant takes a matrix")
                GpuValue(gpuFloat, "determinant(${matrix.code})")
            }
            "asFloat", "asInt", "asUInt" -> {
                arity(1)
                val input = value(0)
                val numeric = input.type as? GpuType.Numeric
                site.require(numeric != null && numeric.scalar in arithmeticScalars, "$name takes a 32-bit number")
                val scalar = when (name) {
                    "asFloat" -> GpuScalar.FLOAT
                    "asInt" -> GpuScalar.INT
                    else -> GpuScalar.UINT
                }
                site.require(numeric!!.scalar != scalar, "$name of a ${numeric.scalar.sourceName} changes nothing")
                val result = GpuType.Numeric(scalar, numeric.width)
                GpuValue(result, when (target) {
                    GpuTarget.METAL -> "as_type<${emitter.type(result)}>(${input.code})"
                    GpuTarget.DIRECTX -> "${name.lowercase()}(${input.code})"
                    GpuTarget.VULKAN -> when {
                        scalar == GpuScalar.FLOAT -> "${if (numeric.scalar == GpuScalar.INT) "int" else "uint"}BitsToFloat(${input.code})"
                        numeric.scalar == GpuScalar.FLOAT -> "floatBitsTo${if (scalar == GpuScalar.INT) "Int" else "Uint"}(${input.code})"
                        else -> "${emitter.type(result)}(${input.code})"
                    }
                })
            }
            "countBits", "reverseBits" -> {
                arity(1)
                val input = value(0)
                site.require((input.type as? GpuType.Numeric)?.scalar == GpuScalar.UINT, "$name takes UInt components")
                val spelling = when (target) {
                    GpuTarget.METAL -> if (name == "countBits") "popcount(${input.code})" else "reverse_bits(${input.code})"
                    GpuTarget.VULKAN -> if (name == "countBits") "${emitter.type(input.type)}(bitCount(${input.code}))" else "bitfieldReverse(${input.code})"
                    GpuTarget.DIRECTX -> if (name == "countBits") "countbits(${input.code})" else "reversebits(${input.code})"
                }
                GpuValue(input.type, spelling)
            }
            "discardFragment", "workgroupBarrier", "storageBarrier" -> {
                arity(0)
                site.require(effectAllowed, "$name() is a statement")
                GpuValue(gpuUnit, when (name) {
                    "discardFragment" -> if (target == GpuTarget.METAL) "discard_fragment()" else "discard"
                    "workgroupBarrier" -> when (target) {
                        GpuTarget.METAL -> "threadgroup_barrier(mem_flags::mem_threadgroup)"
                        GpuTarget.VULKAN -> "barrier()"
                        GpuTarget.DIRECTX -> "GroupMemoryBarrierWithGroupSync()"
                    }
                    else -> when (target) {
                        GpuTarget.METAL -> "threadgroup_barrier(mem_flags::mem_device)"
                        GpuTarget.VULKAN -> "memoryBarrierBuffer(), barrier()"
                        GpuTarget.DIRECTX -> "DeviceMemoryBarrierWithGroupSync()"
                    }
                })
            }
            else -> atomic(name, args, effectAllowed, site)
        }
    }

    /** `atomicAdd(counts[i], 1)` and its kin on an AtomicUInt / AtomicInt element: each answers the value before. */
    private fun atomic(name: String, args: List<Expr>, effectAllowed: Boolean, site: GpuSite): GpuValue {
        val store = name == "atomicStore"
        val load = name == "atomicLoad"
        site.require(args.size == if (load) 1 else 2, "$name takes ${if (load) "an atomic element" else "an atomic element and a value"}")
        val element = expression(args[0])
        val atomicType = element.type as? GpuType.Atomic ?: site.fail("$name works on an AtomicUInt or AtomicInt element")
        val operand = if (load) null else expression(args[1], atomicType.value)
        val result = if (store) gpuUnit else atomicType.value
        site.require(!store || effectAllowed, "atomicStore is a statement")
        val operation = name.removePrefix("atomic")
        return GpuValue(result, when (target) {
            GpuTarget.METAL -> when {
                load -> "atomic_load_explicit(&${element.code}, memory_order_relaxed)"
                store -> "atomic_store_explicit(&${element.code}, ${operand!!.code}, memory_order_relaxed)"
                operation == "Exchange" -> "atomic_exchange_explicit(&${element.code}, ${operand!!.code}, memory_order_relaxed)"
                else -> "atomic_fetch_${operation.lowercase()}_explicit(&${element.code}, ${operand!!.code}, memory_order_relaxed)"
            }
            GpuTarget.VULKAN -> when {
                load -> "atomicAdd(${element.code}, ${emitter.zero(atomicType.value)})"
                store -> "atomicExchange(${element.code}, ${operand!!.code})"
                else -> "atomic$operation(${element.code}, ${operand!!.code})"
            }
            GpuTarget.DIRECTX -> {
                // DXIL's interlocked operations write the previous value to an out parameter.
                val before = temporary()
                line("${emitter.type(atomicType.value)} $before;")
                when {
                    load -> line("InterlockedOr(${element.code}, ${emitter.zero(atomicType.value)}, $before);")
                    store -> line("InterlockedExchange(${element.code}, ${operand!!.code}, $before);")
                    else -> line("Interlocked$operation(${element.code}, ${operand!!.code}, $before);")
                }
                if (store) "(void)0" else before
            }
        })
    }

    // ── Textures ──

    private fun textureMethod(texture: GpuValue, name: String, args: List<Expr>, effectAllowed: Boolean, site: GpuSite): GpuValue {
        val type = texture.type as GpuType.Texture
        site.require(name in gpuTextureMethods, "${type.name} has no method '$name'")
        val shape = type.shape
        val coordinate = GpuType.Numeric(GpuScalar.FLOAT, shape.coordinates)
        val texel = if (type.depth) gpuFloat else gpuFloat4
        fun sampler(index: Int, comparison: Boolean): GpuValue {
            val value = expression(args.getOrNull(index) ?: site.fail("$name needs a sampler"), resource = true)
            site.require(value.resource == GpuResource.SAMPLER && (value.type as GpuType.Sampler).comparison == comparison,
                "$name takes a ${if (comparison) "CompareSampler" else "Sampler"}")
            return value
        }
        fun arity(vararg counts: Int) = site.require(args.size in counts, "${type.name}.$name takes ${counts.joinToString(" or ")} argument(s)")
        fun sampled() = site.require(!type.writable, "${type.name} is written, not sampled")
        val layered = shape == GpuTextureShape.LAYERS
        // Sampling: (sampler, uv[, layer], extra...)
        fun sampleCall(extra: List<GpuValue>, kind: String, comparison: Boolean = false): GpuValue {
            val smp = sampler(0, comparison)
            val uv = expression(args[1], coordinate)
            val layer = if (layered) expression(args[2], gpuUInt) else null
            val combined = "sampler${shape.glsl}${if (comparison) "Shadow" else ""}(${texture.code}, ${smp.code})"
            val glslCoordinate = when {
                layered && comparison -> "vec4(${uv.code}, float(${layer!!.code}), ${extra[0].code})"
                layered -> "vec3(${uv.code}, float(${layer!!.code}))"
                comparison -> "vec3(${uv.code}, ${extra[0].code})"
                else -> uv.code
            }
            val hlslCoordinate = if (layered) "float3(${uv.code}, float(${layer!!.code}))" else uv.code
            val metalLayer = layer?.let { ", ${it.code}" } ?: ""
            val depthPick = if (type.depth && target == GpuTarget.VULKAN && !comparison) ".r" else ""
            val result = if (comparison) gpuFloat else texel
            return GpuValue(result, when (target) {
                GpuTarget.METAL -> when (kind) {
                    "sample" -> "${texture.code}.sample(${smp.code}, ${uv.code}$metalLayer)"
                    "level" -> "${texture.code}.sample(${smp.code}, ${uv.code}$metalLayer, level(${extra[0].code}))"
                    "bias" -> "${texture.code}.sample(${smp.code}, ${uv.code}$metalLayer, bias(${extra[0].code}))"
                    "grad" -> "${texture.code}.sample(${smp.code}, ${uv.code}$metalLayer, gradient2d(${extra[0].code}, ${extra[1].code}))"
                    else -> "${texture.code}.sample_compare(${smp.code}, ${uv.code}$metalLayer, ${extra[0].code}, level(0))"
                }
                GpuTarget.VULKAN -> when (kind) {
                    "sample" -> "texture($combined, $glslCoordinate)$depthPick"
                    "level" -> "textureLod($combined, $glslCoordinate, ${extra[0].code})$depthPick"
                    "bias" -> "texture($combined, $glslCoordinate, ${extra[0].code})$depthPick"
                    "grad" -> "textureGrad($combined, $glslCoordinate, ${extra[0].code}, ${extra[1].code})$depthPick"
                    else -> if (layered) "texture($combined, $glslCoordinate)" else "textureLod($combined, $glslCoordinate, 0.0)"
                }
                GpuTarget.DIRECTX -> when (kind) {
                    "sample" -> "${texture.code}.Sample(${smp.code}, $hlslCoordinate)"
                    "level" -> "${texture.code}.SampleLevel(${smp.code}, $hlslCoordinate, ${extra[0].code})"
                    "bias" -> "${texture.code}.SampleBias(${smp.code}, $hlslCoordinate, ${extra[0].code})"
                    "grad" -> "${texture.code}.SampleGrad(${smp.code}, $hlslCoordinate, ${extra[0].code}, ${extra[1].code})"
                    else -> "${texture.code}.SampleCmpLevelZero(${smp.code}, $hlslCoordinate, ${extra[0].code})"
                }
            })
        }
        val base = if (layered) 3 else 2
        return when (name) {
            "sample" -> { sampled(); arity(base); sampleCall(emptyList(), "sample") }
            "sampleLevel" -> { sampled(); arity(base + 1); sampleCall(listOf(expression(args[base], gpuFloat)), "level") }
            "sampleBias" -> { sampled(); arity(base + 1); sampleCall(listOf(expression(args[base], gpuFloat)), "bias") }
            "sampleGrad" -> {
                sampled(); arity(base + 2)
                site.require(shape == GpuTextureShape.PLANE, "sampleGrad takes a 2D texture")
                sampleCall(listOf(expression(args[base], coordinate), expression(args[base + 1], coordinate)), "grad")
            }
            "sampleCompare" -> {
                site.require(type.depth, "sampleCompare reads a depth texture")
                arity(base + 1)
                sampleCall(listOf(expression(args[base], gpuFloat)), "compare", comparison = true)
            }
            "gather" -> {
                sampled(); arity(2)
                site.require(shape == GpuTextureShape.PLANE, "gather takes a 2D texture")
                val smp = sampler(0, false)
                val uv = expression(args[1], coordinate)
                GpuValue(gpuFloat4, when (target) {
                    GpuTarget.METAL -> "${texture.code}.gather(${smp.code}, ${uv.code})"
                    GpuTarget.VULKAN -> "textureGather(sampler2D(${texture.code}, ${smp.code}), ${uv.code})"
                    GpuTarget.DIRECTX -> "${texture.code}.Gather(${smp.code}, ${uv.code})"
                })
            }
            "read" -> {
                site.require(type.access != GpuTextureAccess.WRITE && shape != GpuTextureShape.CUBE, "${type.name} cannot be read by texel")
                val texelCoordinate = GpuType.Numeric(GpuScalar.UINT, if (shape == GpuTextureShape.VOLUME) 3 else 2)
                val position = expression(args.getOrNull(0) ?: site.fail("read takes a texel coordinate"))
                val positionType = position.type as? GpuType.Numeric
                site.require(positionType != null && positionType.width == texelCoordinate.width && positionType.scalar in integerScalars,
                    "read takes a $texelCoordinate (or Int) texel coordinate")
                val layer = if (layered) expression(args.getOrNull(1) ?: site.fail("read takes the layer after the coordinate"), gpuUInt) else null
                val lodIndex = if (layered) 2 else 1
                site.require(args.size <= lodIndex + 1, "read takes a coordinate${if (layered) ", a layer" else ""} and an optional mip level")
                site.require(!type.writable || args.size == lodIndex, "a written texture is read at its only level")
                val lod = args.getOrNull(lodIndex)?.let { expression(it, gpuUInt) }
                val lodCode = lod?.code ?: "0"
                val result = texel
                val coords = texelCoordinate.width
                GpuValue(result, when (target) {
                    GpuTarget.METAL -> {
                        val c = "${emitter.type(texelCoordinate)}(${position.code})"
                        val mid = layer?.let { ", ${it.code}" } ?: ""
                        if (type.writable) "${texture.code}.read($c)" else "${texture.code}.read($c$mid, ${lod?.code ?: "0u"})"
                    }
                    GpuTarget.VULKAN -> {
                        val ivec = "ivec$coords(${position.code})"
                        when {
                            type.writable -> "imageLoad(${texture.code}, $ivec)"
                            layered -> "texelFetch(${texture.code}, ivec3($ivec, int(${layer!!.code})), int($lodCode))${if (type.depth) ".r" else ""}"
                            else -> "texelFetch(${texture.code}, $ivec, int($lodCode))${if (type.depth) ".r" else ""}"
                        }
                    }
                    GpuTarget.DIRECTX -> {
                        val ints = "int$coords(${position.code})"
                        when {
                            type.writable -> "${texture.code}[uint$coords(${position.code})]"
                            layered -> "${texture.code}.Load(int4($ints, int(${layer!!.code}), int($lodCode)))"
                            else -> "${texture.code}.Load(int${coords + 1}($ints, int($lodCode)))"
                        }
                    }
                })
            }
            "write" -> {
                site.require(type.writable, "${type.name} cannot be written (use WriteTexture2D or ReadWriteTexture2D)")
                site.require(effectAllowed, "write is a statement")
                arity(2)
                val value = expression(args[0], gpuFloat4)
                val texelCoordinate = GpuType.Numeric(GpuScalar.UINT, if (shape == GpuTextureShape.VOLUME) 3 else 2)
                val position = expression(args[1])
                val positionType = position.type as? GpuType.Numeric
                site.require(positionType != null && positionType.width == texelCoordinate.width && positionType.scalar in integerScalars,
                    "write takes a value and a $texelCoordinate texel coordinate")
                val coords = texelCoordinate.width
                GpuValue(gpuUnit, when (target) {
                    GpuTarget.METAL -> "${texture.code}.write(${value.code}, ${emitter.type(texelCoordinate)}(${position.code}))"
                    GpuTarget.VULKAN -> "imageStore(${texture.code}, ivec$coords(${position.code}), ${value.code})"
                    GpuTarget.DIRECTX -> "${texture.code}[uint$coords(${position.code})] = ${value.code}"
                })
            }
            else -> {
                // size(), width(), height(), depth()
                arity(0)
                val volume = shape == GpuTextureShape.VOLUME
                site.require(name != "depth" || volume, "only 3D textures have a depth")
                val sizeType = GpuType.Numeric(GpuScalar.UINT, if (volume) 3 else 2)
                val size = when (target) {
                    GpuTarget.METAL -> if (volume) "uint3(${texture.code}.get_width(), ${texture.code}.get_height(), ${texture.code}.get_depth())"
                        else "uint2(${texture.code}.get_width(), ${texture.code}.get_height())"
                    GpuTarget.VULKAN -> {
                        val raw = if (type.writable) "imageSize(${texture.code})" else "textureSize(${texture.code}, 0)"
                        if (layered) "uvec2($raw.xy)" else "${emitter.type(sizeType)}($raw)"
                    }
                    GpuTarget.DIRECTX -> "${emitter.sizeHelper(type)}(${texture.code})"
                }
                when (name) {
                    "size" -> GpuValue(sizeType, size)
                    "width" -> GpuValue(gpuUInt, "$size.x")
                    "height" -> GpuValue(gpuUInt, "$size.y")
                    else -> GpuValue(gpuUInt, "$size.z")
                }
            }
        }
    }

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
