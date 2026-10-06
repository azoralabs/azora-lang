/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.semantic

import org.azora.lang.diagnostics.*
import org.azora.lang.frontend.*
import org.azora.lang.ir.IrType

data class SemanticOwner(val name: String, val line: Int)

/** These observations come from resolution, never from guessing a name's meaning. */
class SemanticFactRecorder {
    var enabled = false
    private var owner: SemanticOwner? = null
    private val variables = mutableListOf<VariableSymbol>()
    internal val declarations = mutableListOf<ResolvedVariableDeclaration>()
    internal val occurrences = mutableListOf<ResolvedOccurrence>()
    private val expressions = mutableListOf<Pair<Expr, IrType>>()
    private val references = mutableListOf<Pair<Expr, String>>()

    fun reset() {
        owner = null
        variables.clear()
        declarations.clear()
        occurrences.clear()
        expressions.clear()
        references.clear()
    }

    fun enterOwner(name: String, line: Int): SemanticOwner? {
        val previous = owner
        owner = SemanticOwner(name, line)
        return previous
    }
    fun leaveOwner(previous: SemanticOwner?) { owner = previous }
    private fun id(symbol: VariableSymbol): String {
        var index = variables.indexOfFirst { it === symbol }
        if (index < 0) { index = variables.size; variables += symbol }
        return "local:$index"
    }
    fun declaration(symbol: VariableSymbol, line: Int, column: Int = 0, length: Int = 0) {
        if (!enabled) return
        val context = owner ?: return
        declarations += ResolvedVariableDeclaration(context, id(symbol), symbol.name, symbol.type, line, column, length)
    }
    fun reference(expr: Expr, symbol: VariableSymbol) {
        if (enabled) references += expr to id(symbol)
    }
    fun reference(expr: Expr, symbol: FunctionSymbol) {
        if (enabled) references += expr to "function:${symbol.name}"
    }
    fun callable(expr: Expr, symbol: FunctionSymbol, receiverType: IrType? = null) {
        reference(expr, symbol)
        if (receiverType != null && enabled) {
            val target = when (expr) { is Expr.MethodCall -> expr.target; is Expr.Member -> expr.target; else -> null }
            if (target != null) expressions += target to receiverType
        }
    }
    fun expression(expr: Expr, type: IrType?, visible: Map<String, VariableSymbol>) {
        if (!enabled) return
        val context = owner ?: return
        if (type != null) expressions += expr to type
        val target = when (expr) { is Expr.Member -> expr.target; is Expr.MethodCall -> expr.target; else -> null }
        val receiver = target?.let { value -> expressions.lastOrNull { it.first === value }?.second }
        val symbol = references.lastOrNull { it.first === expr }?.second
        val name = when (expr) {
            is Expr.Identifier -> expr.name
            is Expr.Call -> expr.callee
            is Expr.Member -> expr.name
            is Expr.MethodCall -> expr.name
            is Expr.UpperScopeAccess -> expr.name
            else -> null
        }
        occurrences += ResolvedOccurrence(context, expr.line, expr.column, expr.length, name, type, symbol, receiver,
            visible.mapValues { (_, variable) -> ResolvedVisibleVariable(id(variable), variable.name, variable.type) })
    }
    fun checkpoint(line: Int, column: Int, visible: Map<String, VariableSymbol>) {
        if (!enabled) return
        val context = owner ?: return
        occurrences += ResolvedOccurrence(context, line, column, 0, null, null, null, null,
            visible.mapValues { (_, variable) -> ResolvedVisibleVariable(id(variable), variable.name, variable.type) })
    }
}

internal data class ResolvedVariableDeclaration(val owner: SemanticOwner, val id: String, val name: String,
    val type: IrType, val line: Int, val column: Int, val length: Int)
internal data class ResolvedVisibleVariable(val id: String, val name: String, val type: IrType)
internal data class ResolvedOccurrence(val owner: SemanticOwner, val line: Int, val column: Int, val length: Int,
    val name: String?, val type: IrType?, val symbol: String?, val receiver: IrType?,
    val visible: Map<String, ResolvedVisibleVariable>)

data class SemanticSymbol(val id: String, val name: String, val kind: String, val type: String, val declaration: SourceSpan?)
data class SemanticOccurrence(val span: SourceSpan, val type: String?, val symbol: String?,
    val visible: List<String>, val members: List<String> = emptyList())
data class SemanticHover(val span: SourceSpan, val name: String?, val type: String)

/** Immutable, source-qualified facts suitable for an editor or native protocol. */
data class SemanticFacts(
    val symbols: Map<String, SemanticSymbol> = emptyMap(),
    val occurrences: List<SemanticOccurrence> = emptyList(),
) {
    private fun occurrence(source: SourceId, offset: TextOffset): SemanticOccurrence? = occurrences
        .filter { it.span.source == source && offset.value >= it.span.start.value && offset.value < it.span.endExclusive.value }
        .minByOrNull { it.span.endExclusive.value - it.span.start.value }

    fun hover(source: SourceId, offset: TextOffset): SemanticHover? {
        val at = occurrence(source, offset) ?: return null
        val symbol = at.symbol?.let(symbols::get)
        val type = at.type ?: symbol?.type ?: return null
        return SemanticHover(at.span, symbol?.name, type)
    }
    fun definition(source: SourceId, offset: TextOffset): SourceSpan? =
        occurrence(source, offset)?.symbol?.let(symbols::get)?.declaration

    /** Completion is available only at a recorded lexical occurrence. */
    fun complete(source: SourceId, offset: TextOffset, prefix: String = ""): List<SemanticSymbol> {
        val at = occurrence(source, offset) ?: return emptyList()
        val ids = if (at.members.isNotEmpty()) at.members else at.visible
        return ids.mapNotNull(symbols::get).filter { it.name.startsWith(prefix) }.distinctBy { it.name }.sortedBy { it.name }
    }

    companion object {
        fun merge(values: List<SemanticFacts>): SemanticFacts = SemanticFacts(
            values.flatMap { it.symbols.entries }.associate { it.key to it.value },
            values.flatMap { it.occurrences }.distinct(),
        )

        fun from(table: SymbolTable, sources: List<Pair<SourceUnit, Program>>, root: SourceId): SemanticFacts {
            val registry = DeclarationRegistry(sources, root)
            val symbols = linkedMapOf<String, SemanticSymbol>()
            val occurrences = mutableListOf<SemanticOccurrence>()
            fun localId(id: String) = "${root.value}#$id"
            fun function(name: String): String {
                val id = "function:$name"
                if (id !in symbols) table.lookupFunction(name)?.let { signature ->
                    val declaration = registry.function(name)
                    symbols[id] = SemanticSymbol(id, declaration?.shownName ?: surface(name), "function",
                        signature.params.joinToString(", ", "(", ") -> ${display(signature.returnType)}") { "${it.first}: ${display(it.second)}" },
                        declaration?.span)
                }
                return id
            }
            table.allFunctionNames().forEach(::function)
            for (declaration in table.semanticFacts.declarations) {
                val source = registry.owner(declaration.owner) ?: continue
                val span = registry.variable(source, declaration)
                val id = localId(declaration.id)
                symbols[id] = SemanticSymbol(id, declaration.name, "variable", display(declaration.type), span)
                if (span != null) occurrences += SemanticOccurrence(span, display(declaration.type), id, emptyList())
            }
            for (raw in table.semanticFacts.occurrences) {
                val source = registry.owner(raw.owner) ?: continue
                val span = registry.occurrence(source, raw) ?: continue
                val visible = raw.visible.values.map { variable ->
                    val id = localId(variable.id)
                    if (id !in symbols) symbols[id] = SemanticSymbol(id, variable.name, "variable", display(variable.type), null)
                    id
                }
                var symbol = raw.symbol?.let { if (it.startsWith("local:")) localId(it) else it }
                val memberIds = mutableListOf<String>()
                val receiver = raw.receiver.unwrap() as? IrType.Named
                val struct = receiver?.let { table.lookupStruct(it.name) }
                if (struct != null && receiver != null) {
                    for (field in struct.fields.filter { it.visibility == Visibility.PUBLIC && !it.name.startsWith('_') }) {
                        val id = "field:${receiver.name}.${field.name}"
                        val fieldType = receiver.args.getOrNull(field.typeParamIndex) ?: field.type
                        symbols[id] = SemanticSymbol(id, field.name, "field", display(fieldType), registry.field(receiver.name, field.name))
                        memberIds += id
                        if (raw.name == field.name && raw.type != null && symbol == null) symbol = id
                    }
                    for (name in table.allFunctionNames().filter { it.startsWith("${receiver.name}_") }) {
                        val method = table.lookupFunction(name) ?: continue
                        if (method.visibility != Visibility.PUBLIC) continue
                        val id = function(name)
                        val original = symbols[id] ?: continue
                        if (original.declaration != null && !original.name.startsWith('_')) memberIds += id
                    }
                }
                if (symbol?.startsWith("function:") == true) function(symbol.removePrefix("function:"))
                occurrences += SemanticOccurrence(span, raw.type?.let(::display), symbol, visible, memberIds)
            }
            return SemanticFacts(symbols.toMap(), occurrences.distinctBy { listOf(it.span, it.type, it.symbol) })
        }
    }
}

private fun IrType?.unwrap(): IrType? = when (this) {
    is IrType.Pointer -> inner.unwrap()
    is IrType.Nullable -> inner.unwrap()
    else -> this
}
private fun surface(name: String) = if (ModuleQualifiedSymbol.isQualified(name)) ModuleQualifiedSymbol.symbol(name).substringAfterLast("__") else name.substringAfterLast("__")
private fun display(type: IrType): String = when (type) {
    is IrType.Named -> surface(type.name) + if (type.args.isEmpty()) "" else type.args.joinToString(", ", "<", ">", transform = ::display)
    is IrType.Array -> "Array<${display(type.element)}${type.size?.let { ", $it" }.orEmpty()}>"
    is IrType.Pointer -> display(type.inner) + if (type.mutable) "^" else "*"
    is IrType.Nullable -> "${display(type.inner)}?"
    else -> type.toString()
}

private data class WrittenDeclaration(val source: SourceUnit, val name: String, val shownName: String,
    val line: Int, val span: SourceSpan?, val params: List<String> = emptyList())

/** Tokens locate an already resolved declaration; they never select its binding. */
private class DeclarationRegistry(sources: List<Pair<SourceUnit, Program>>, private val root: SourceId) {
    private val functions = mutableListOf<WrittenDeclaration>()
    private val fields = mutableMapOf<Pair<String, String>, SourceSpan>()
    private val tokens = sources.associate { it.first.id to Lexer(it.first.text).tokenize() }
    private val indexes = sources.associate { it.first.id to StringLineIndex(it.first.text) }
    init {
        for ((source, program) in sources) {
            fun names(name: String): List<String> = listOfNotNull(name,
                program.moduleName?.let { "${it.replace(".", "__")}__$name" },
                program.moduleName?.let { ModuleQualifiedSymbol.create(it, name) })
            fun add(func: FuncDecl, owner: String? = null) {
                val canonical = if (owner == null) func.name else "${owner}_${func.name}"
                val shown = func.name.substringAfterLast("__")
                val token = token(source, func.line, func.column, shown)
                for (name in names(canonical)) functions += WrittenDeclaration(source, name, shown, func.line,
                    token?.span(source), func.params.map { it.name })
            }
            for (item in program.items) when (item) {
                is TopLevel.Func -> add(item.decl)
                is TopLevel.Impl -> item.methods.forEach { add(it, item.typeName) }
                is TopLevel.Pack -> {
                    val packTokens = bodyTokens(source, item.line, item.column)
                    for (field in item.fields) {
                        // Require the field-declaration colon, excluding references in defaults.
                        val candidates = packTokens.withIndex().filter { (index, value) ->
                            value.lexeme == field.name && packTokens.getOrNull(index + 1)?.type == TokenType.COLON
                        }
                        val location = candidates.singleOrNull()?.value?.span(source) ?: continue
                        names(item.name).forEach { fields[it to field.name] = location }
                    }
                }
                else -> Unit
            }
        }
    }
    fun function(name: String): WrittenDeclaration? {
        val exact = functions.filter { it.name == name }
        val preferred = exact.filter { it.source.id == root }.ifEmpty { exact }
        return preferred.distinctBy { it.source.id to it.span }.singleOrNull()
    }
    fun owner(owner: SemanticOwner): WrittenDeclaration? = function(owner.name)?.takeIf { it.line == owner.line }
    fun field(owner: String, name: String): SourceSpan? = fields[owner to name]
    fun variable(owner: WrittenDeclaration, declaration: ResolvedVariableDeclaration): SourceSpan? {
        // Parameters have no AST coordinates; locate their name in this function's signature.
        if (declaration.line == owner.line && declaration.name in owner.params) {
            val all = tokens[owner.source.id].orEmpty()
            val start = all.indexOfFirst { it.line >= owner.line && it.type == TokenType.L_PAREN }
            if (start >= 0) {
                var depth = 0
                val signature = all.drop(start).takeWhile { value ->
                    if (value.type == TokenType.L_PAREN) depth++
                    if (value.type == TokenType.R_PAREN) depth--
                    depth > 0 || value.type == TokenType.L_PAREN
                }
                val matching = signature.withIndex().filter { (index, value) ->
                    value.lexeme == declaration.name && signature.getOrNull(index + 1)?.type in setOf(TokenType.COLON, TokenType.AMP, TokenType.BANG)
                }
                return matching.singleOrNull()?.value?.span(owner.source)
            }
        }
        return token(owner.source, declaration.line, declaration.column, declaration.name)?.span(owner.source)
    }
    fun occurrence(owner: WrittenDeclaration, raw: ResolvedOccurrence): SourceSpan? {
        if (raw.line < 1 || raw.column < 1) return null
        val source = owner.source
        val start = indexes[source.id]?.offset(SourcePosition(raw.line - 1, raw.column - 1)) ?: return null
        // Use the actual written name token rather than a transformed internal spelling.
        if (raw.name != null) {
            val candidates = tokens[source.id].orEmpty().filter { it.line == raw.line && it.column >= raw.column }
            val shown = surface(raw.name)
            val matched = if (raw.receiver != null) candidates.firstOrNull { it.lexeme == shown }
                else candidates.firstOrNull { it.column == raw.column && (it.lexeme == shown || raw.name.contains(it.lexeme)) }
            if (matched != null) return matched.span(source)
            // Never attach a generated identifier to an unrelated token at the same line.
            return null
        }
        val end = (start.value + raw.length).coerceAtMost(source.text.length)
        return if (end > start.value) SourceSpan(source.id, start, TextOffset(end)) else null
    }
    private fun token(source: SourceUnit, line: Int, column: Int, name: String): Token? {
        val candidates = tokens[source.id].orEmpty().filter { it.line == line && it.lexeme == name && (column <= 0 || it.column >= column) }
        return candidates.firstOrNull()
    }
    private fun Token.span(source: SourceUnit): SourceSpan? {
        val start = indexes[source.id]?.offset(SourcePosition(line - 1, (column - 1).coerceAtLeast(0))) ?: return null
        return SourceSpan(source.id, start, TextOffset((start.value + lexeme.length).coerceAtMost(source.text.length)))
    }
    private fun bodyTokens(source: SourceUnit, line: Int, column: Int): List<Token> {
        val all = tokens[source.id].orEmpty().dropWhile { it.line < line || it.line == line && it.column < column }
        val start = all.indexOfFirst { it.type == TokenType.L_BRACE }
        if (start < 0) return emptyList()
        var depth = 0
        return all.drop(start).takeWhile { value ->
            if (value.type == TokenType.L_BRACE) depth++
            if (value.type == TokenType.R_BRACE) depth--
            depth > 0 || value.type == TokenType.L_BRACE
        }
    }
}
