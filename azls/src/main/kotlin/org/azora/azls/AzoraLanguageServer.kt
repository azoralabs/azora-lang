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

package org.azora.azls

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.azora.lang.CompilationResult
import org.azora.lang.Compiler
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.Parser
import org.azora.lang.frontend.Program
import org.azora.lang.frontend.Stmt
import org.azora.lang.frontend.TopLevel
import org.azora.lang.frontend.TypeAnnotation
import org.azora.lang.frontend.TypeRef
import org.azora.lang.stdlib.AzStdlib

/**
 * The Azora Language Server — full language intelligence for `.az` sources.
 *
 * Packaged as a self-contained jar (`azls.jar`) that Azora Studio loads via a
 * `URLClassLoader` and calls **reflectively**; every method therefore takes and
 * returns plain [String]s (JSON), so no types cross the classloader boundary.
 *
 * Capabilities:
 * - [highlight] — error-tolerant syntax colorizer spans.
 * - [diagnostics] — full compiler errors/warnings (lex, parse, semantic).
 * - [complete] — keywords, builtins, user symbols, in-scope locals/params,
 *   pack fields and enum variants.
 * - [hover] — signatures for functions / packs / enums under the caret.
 * - [symbols] — document outline of top-level declarations.
 *
 * The optional `prelude` parameter carries the rest of the compilation unit
 * (other project files, installed engine libraries) so cross-file symbols
 * resolve; diagnostics are mapped back to the edited document's line numbers.
 */
class AzoraLanguageServer {

    private val json = Json { encodeDefaults = true }

    /** Standard-library symbols (functions, constants, packs, enums) for completion/hover. */
    private val stdlibIndex: SymbolIndex by lazy {
        SymbolIndex().apply { AzStdlib.loadPrograms().forEach(::addProgram) }
    }

    /** Memoized prelude index — Studio passes the same prelude on every keystroke. */
    private var cachedPreludeKey: Int = 0
    private var cachedPreludeIndex: SymbolIndex? = null

    fun version(): String = "0.1.0"

    // -----------------------------------------------------------------
    // Debugging (single session; polled from Studio)
    // -----------------------------------------------------------------

    @Volatile
    private var debugSession: AzoraDebugSession? = null

    /**
     * Starts a debug run of [source] (with [prelude] as the rest of the
     * compilation unit). [breakpointsJson] is a JSON array of 1-based document
     * lines. Returns `{"ok":true}` or `{"error":"…"}` on compile failure.
     */
    fun debugStart(source: String, prelude: String, breakpointsJson: String): String {
        debugSession?.stop()
        val breakpoints = parseBreakpoints(breakpointsJson)
        val session = AzoraDebugSession(source, prelude, breakpoints)
        session.start()
        debugSession = session
        return if (session.status == "failed") {
            json.encodeToString(DebugStatus.serializer(), DebugStatus("failed", error = session.error ?: "compile failed"))
        } else {
            """{"ok":true}"""
        }
    }

    /** Current session state + output produced since the last poll. */
    fun debugStatus(): String {
        val session = debugSession
            ?: return json.encodeToString(DebugStatus.serializer(), DebugStatus("none"))
        return json.encodeToString(
            DebugStatus.serializer(),
            DebugStatus(
                status = session.status,
                line = session.pausedLine,
                pauseId = session.pauseId,
                locals = session.locals.map { DebugLocal(it.first, it.second) },
                output = session.drainOutput(),
                error = session.error
            )
        )
    }

    fun debugResume(): String { debugSession?.resume(); return """{"ok":true}""" }
    fun debugStep(): String { debugSession?.step(); return """{"ok":true}""" }
    fun debugStop(): String { debugSession?.stop(); return """{"ok":true}""" }
    fun debugSetBreakpoints(breakpointsJson: String): String {
        debugSession?.setBreakpoints(parseBreakpoints(breakpointsJson))
        return """{"ok":true}"""
    }

    private fun parseBreakpoints(breakpointsJson: String): Set<Int> = runCatching {
        json.decodeFromString(ListSerializer(Int.serializer()), breakpointsJson).toSet()
    }.getOrDefault(emptySet())

    // -----------------------------------------------------------------
    // Highlighting
    // -----------------------------------------------------------------

    fun highlight(source: String): String =
        json.encodeToString(ListSerializer(HighlightSpan.serializer()), AzHighlighter.highlight(source))

    // -----------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------

    fun diagnostics(source: String, prelude: String = ""): String {
        val preludeLines = if (prelude.isBlank()) 0 else prelude.lines().size
        val full = if (prelude.isBlank()) source else prelude + "\n" + source
        val docLines = source.lines().size

        val raw: List<Pair<Int, String>> = try {
            when (val result = Compiler().compile(full)) {
                is CompilationResult.Success -> result.warnings.map { lineOf(it) to it }
                is CompilationResult.Failure -> result.errors.map { lineOf(it) to it }
            }
        } catch (e: Exception) {
            // Lexer/Parser throw on syntax errors ("... at line N").
            listOf(lineOf(e.message ?: "") to (e.message ?: "Syntax error"))
        }

        val mapped = raw.mapNotNull { (line, message) ->
            val docLine = (if (line > 0) line - preludeLines else line).coerceAtMost(docLines)
            if (docLine < 1) return@mapNotNull null // error inside the prelude, not this document
            val severity = if (message.startsWith("warning:")) "warning" else "error"
            Diagnostic(docLine, cleanMessage(message), severity)
        }
        return json.encodeToString(ListSerializer(Diagnostic.serializer()), mapped)
    }

    /** Extracts `line N` from compiler messages ("line 3: ..." / "... at line 3"). */
    private fun lineOf(message: String): Int {
        val match = Regex("line (\\d+)").find(message) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    private fun cleanMessage(message: String): String =
        message.removePrefix("warning:").trim()
            .replace(Regex("^line \\d+:\\s*"), "")

    // -----------------------------------------------------------------
    // Completion
    // -----------------------------------------------------------------

    fun complete(source: String, offset: Int, prelude: String = ""): String {
        val safeOffset = offset.coerceIn(0, source.length)
        val (wordStart, prefix) = wordBefore(source, safeOffset)
        val receiver = receiverBefore(source, wordStart)

        val preludeIndex = preludeIndex(prelude)
        val cursorLine = source.take(safeOffset).count { it == '\n' } + 1

        // The cursor's line is the one being typed and usually doesn't parse —
        // blank it before indexing (it can't declare anything its own
        // completion needs), falling back to the raw source when that fails.
        val blanked = source.lines().toMutableList()
            .also { if (cursorLine in 1..it.size) it[cursorLine - 1] = "" }
            .joinToString("\n")
        val program = parseTolerant(blanked) ?: parseTolerant(source)
        val userIndex = SymbolIndex().apply { program?.let(::addProgram) }

        val out = mutableListOf<Completion>()

        if (receiver != null) {
            completeMembers(receiver, userIndex, preludeIndex, cursorLine, out)
        } else {
            // Locals and parameters of the enclosing function.
            userIndex.localsAt(cursorLine).forEach { (name, kind, detail) ->
                out.add(Completion(name, kind, detail, name))
            }
            BUILTIN_FUNCTIONS.forEach { (name, detail) ->
                out.add(Completion(name, "function", detail, "$name("))
            }
            val imports = importsOf(source)
            for (index in listOf(userIndex, preludeIndex, stdlibIndex)) {
                // Symbols from a packaged module (std.*, engine, …) are only
                // offered when the document imports that module via `use`; the
                // document's own symbols (userIndex) are never gated.
                fun visible(name: String): Boolean =
                    index === userIndex || moduleVisible(index.origins[name], imports)
                fun withOrigin(name: String, detail: String): String =
                    index.origins[name]?.let { "$detail — $it" } ?: detail
                index.functions.values.forEach { if (visible(it.name)) out.add(functionCompletion(it).let { c -> c.copy(detail = withOrigin(c.label, c.detail)) }) }
                index.packs.values.forEach { if (visible(it.name)) out.add(Completion(it.name, "pack", withOrigin(it.name, packDetail(it)), it.name)) }
                index.enums.values.forEach { if (visible(it.name)) out.add(Completion(it.name, "enum", withOrigin(it.name, "enum ${it.name}"), it.name)) }
                index.topLevelVars.forEach { (name, detail) -> if (visible(name)) out.add(Completion(name, "variable", withOrigin(name, detail), name)) }
            }
            AzHighlighter.KEYWORDS.forEach { out.add(Completion(it, "keyword", "", it)) }
        }

        val filtered = out
            .filter { prefix.isEmpty() || it.label.startsWith(prefix) }
            .distinctBy { it.label + "/" + it.kind }
            .sortedWith(compareBy({ kindRank(it.kind) }, { it.label }))
            .take(200)
        return json.encodeToString(ListSerializer(Completion.serializer()), filtered)
    }

    private fun completeMembers(
        receiver: String,
        userIndex: SymbolIndex,
        preludeIndex: SymbolIndex,
        cursorLine: Int,
        out: MutableList<Completion>
    ) {
        val indices = listOf(userIndex, preludeIndex, stdlibIndex)

        // Enum values: `Color.` → variants.
        for (index in indices) {
            index.enums[receiver]?.let { enum ->
                enum.variants.forEach { out.add(Completion(it, "enumMember", "${enum.name}.$it", it)) }
                return
            }
        }
        // Pack fields via the receiver variable's declared/constructed type.
        val packName = userIndex.typeOfVariable(receiver, cursorLine)
            ?: preludeIndex.typeOfVariable(receiver, Int.MAX_VALUE)
        if (packName != null) {
            for (index in indices) {
                index.packs[packName]?.let { pack ->
                    pack.fields.forEach { field ->
                        out.add(Completion(field.name, "field", "${field.name}: ${field.type.displayName()}", field.name))
                    }
                    return
                }
            }
        }
        // Unknown receiver — offer the built-in container/string methods.
        BUILTIN_METHODS.forEach { (name, detail) -> out.add(Completion(name, "method", detail, name)) }
    }

    private fun functionCompletion(decl: FuncDecl): Completion {
        val insert = if (decl.params.isEmpty()) "${decl.name}()" else "${decl.name}("
        return Completion(decl.name, "function", signatureOf(decl), insert)
    }

    private fun kindRank(kind: String): Int = when (kind) {
        "param", "variable" -> 0
        "field", "enumMember", "method" -> 1
        "function" -> 2
        "pack", "enum" -> 3
        "keyword" -> 4
        else -> 5
    }

    // -----------------------------------------------------------------
    // Hover
    // -----------------------------------------------------------------

    fun hover(source: String, offset: Int, prelude: String = ""): String {
        val word = wordAt(source, offset) ?: return "null"
        val userIndex = SymbolIndex().apply { parseTolerant(source)?.let(::addProgram) }
        val indices = listOf(userIndex, preludeIndex(prelude), stdlibIndex)
        for (index in indices) {
            index.functions[word]?.let {
                return json.encodeToString(Hover.serializer(), Hover(signatureOf(it)))
            }
            index.packs[word]?.let {
                return json.encodeToString(Hover.serializer(), Hover(packDetail(it)))
            }
            index.enums[word]?.let {
                return json.encodeToString(Hover.serializer(), Hover("enum ${it.name} { ${it.variants.joinToString(", ")} }"))
            }
        }
        return "null"
    }

    // -----------------------------------------------------------------
    // Outline
    // -----------------------------------------------------------------

    fun symbols(source: String): String {
        val program = parseTolerant(source)
            ?: return json.encodeToString(ListSerializer(DocumentSymbol.serializer()), emptyList())
        val out = mutableListOf<DocumentSymbol>()
        for (item in program.items) {
            when (item) {
                is TopLevel.Func -> out.add(DocumentSymbol(item.decl.name, "function", item.decl.line, signatureOf(item.decl)))
                is TopLevel.Pack -> out.add(DocumentSymbol(item.name, "pack", item.line, packDetail(item)))
                is TopLevel.Enum -> out.add(DocumentSymbol(item.name, "enum", item.line, "enum ${item.name}"))
                is TopLevel.Solo -> out.add(DocumentSymbol(item.name, "solo", item.line))
                is TopLevel.Node -> out.add(DocumentSymbol(item.name, "node", item.line))
                is TopLevel.Test -> out.add(DocumentSymbol(item.name, "test", item.line))
                is TopLevel.VarDecl -> out.add(DocumentSymbol(item.name, "variable", item.line))
                is TopLevel.LetDecl -> out.add(DocumentSymbol(item.name, "variable", item.line))
                is TopLevel.FinDecl -> out.add(DocumentSymbol(item.name, "variable", item.line))
                is TopLevel.Impl -> out.add(DocumentSymbol(item.typeName, "impl", item.line))
                is TopLevel.Bridge -> out.add(DocumentSymbol(item.target, "bridge", item.line))
                else -> {}
            }
        }
        return json.encodeToString(ListSerializer(DocumentSymbol.serializer()), out)
    }

    // -----------------------------------------------------------------
    // Parsing / indexing internals
    // -----------------------------------------------------------------

    /**
     * Parses [source], tolerating the in-progress edit: when parsing fails at
     * line N, that line (and, if needed, its neighbours) is blanked and the
     * parse retried, so completions keep working while a line is half-typed.
     */
    private fun parseTolerant(source: String): Program? {
        var lines = source.lines()
        repeat(4) {
            try {
                return Parser(Lexer(lines.joinToString("\n")).tokenize()).parse()
            } catch (e: Exception) {
                val errorLine = lineOf(e.message ?: "")
                if (errorLine !in 1..lines.size) return null
                // Blank the offending line; the reported line is often the one
                // AFTER the half-typed statement, so wipe the previous one too
                // when it is already blank.
                val mutable = lines.toMutableList()
                if (mutable[errorLine - 1].isBlank() && errorLine >= 2) {
                    mutable[errorLine - 2] = ""
                } else {
                    mutable[errorLine - 1] = ""
                }
                if (mutable == lines) return null
                lines = mutable
            }
        }
        return null
    }

    private fun preludeIndex(prelude: String): SymbolIndex {
        if (prelude.isBlank()) return EMPTY_INDEX
        val key = prelude.hashCode()
        cachedPreludeIndex?.let { if (cachedPreludeKey == key) return it }
        // The prelude is split into module sections by `//@azora-module <name>`
        // markers (emitted by Studio for engine/library sources); symbols from a
        // marked section carry that module as their origin and are import-gated
        // in completion. Unmarked sections (project files) are always visible.
        val index = SymbolIndex()
        var module: String? = null
        val section = StringBuilder()
        fun flush() {
            if (section.isNotBlank()) parseTolerant(section.toString())?.let { index.addProgram(it, module) }
            section.setLength(0)
        }
        for (line in prelude.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(MODULE_MARKER)) {
                flush()
                module = trimmed.removePrefix(MODULE_MARKER).trim().ifEmpty { null }
            } else {
                section.append(line).append('\n')
            }
        }
        flush()
        cachedPreludeKey = key
        cachedPreludeIndex = index
        return index
    }

    private fun signatureOf(decl: FuncDecl): String {
        val params = decl.params.joinToString(", ") { "${it.name}: ${it.typeName}" }
        val ret = (decl.returnType as? TypeAnnotation.Explicit)
            ?.ref?.displayName()
            ?.takeIf { it != "Unit" }
            ?.let { ": $it" } ?: ""
        return "func ${decl.name}($params)$ret"
    }

    private fun packDetail(pack: TopLevel.Pack): String =
        "pack ${pack.name}(${pack.fields.joinToString(", ") { "${it.name}: ${it.type.displayName()}" }})"

    /**
     * Module paths imported by the document's `use` lines: `use engine`,
     * `use std.math`, `use scope std`, `use std.math::abs` (the `::` item part
     * imports its whole module for completion purposes).
     */
    private fun importsOf(source: String): Set<String> {
        val out = mutableSetOf<String>()
        for (line in source.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("use ")) continue
            var rest = trimmed.removePrefix("use ").trim()
            if (rest.startsWith("scope ")) rest = rest.removePrefix("scope ").trim()
            for (part in rest.split(",")) {
                val zone = part.trim().substringBefore("::").trim()
                if (zone.isNotEmpty() && zone.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
                    out.add(zone)
                }
            }
        }
        return out
    }

    /** A packaged symbol is visible when its module (or a parent) is imported. */
    private fun moduleVisible(origin: String?, imports: Set<String>): Boolean {
        if (origin == null) return true
        return imports.any { imported -> origin == imported || origin.startsWith("$imported.") }
    }

    // ----- text helpers -----

    /** The identifier fragment immediately before [offset]: (startOffset, text). */
    private fun wordBefore(source: String, offset: Int): Pair<Int, String> {
        var start = offset
        while (start > 0 && source[start - 1].isIdentPart()) start--
        return start to source.substring(start, offset)
    }

    /** The receiver identifier when the word at [wordStart] follows `receiver.`. */
    private fun receiverBefore(source: String, wordStart: Int): String? {
        if (wordStart == 0 || source[wordStart - 1] != '.') return null
        var end = wordStart - 1
        // `..` is a range, not a member access
        if (end > 0 && source[end - 1] == '.') return null
        var start = end
        while (start > 0 && source[start - 1].isIdentPart()) start--
        return source.substring(start, end).takeIf { it.isNotEmpty() }
    }

    /** The identifier containing [offset], or null. */
    private fun wordAt(source: String, offset: Int): String? {
        if (source.isEmpty()) return null
        var start = offset.coerceIn(0, source.length)
        while (start > 0 && source[start - 1].isIdentPart()) start--
        var end = offset.coerceIn(0, source.length)
        while (end < source.length && source[end].isIdentPart()) end++
        return source.substring(start, end).takeIf { it.isNotEmpty() && it[0].isIdentStart() }
    }

    private companion object {
        val EMPTY_INDEX = SymbolIndex()

        /** Prefix marking the module of the prelude section that follows. */
        const val MODULE_MARKER = "//@azora-module"

        /** Functions the compiler registers as builtins (see SymbolCollector). */
        val BUILTIN_FUNCTIONS = listOf(
            "println" to "func println(value)",
            "print" to "func print(value)",
            "channel" to "func channel(): Channel",
        )

        /** Methods available on containers/strings regardless of receiver type. */
        val BUILTIN_METHODS = listOf(
            "length" to "length: Int",
            "isEmpty" to "isEmpty(): Bool",
            "isNotEmpty" to "isNotEmpty(): Bool",
            "add" to "add(value)",
            "removeAt" to "removeAt(index: Int)",
            "contains" to "contains(value): Bool",
            "indexOf" to "indexOf(value): Int",
            "toString" to "toString(): String",
        )
    }
}

private fun Char.isIdentStart(): Boolean = isLetter() || this == '_'
private fun Char.isIdentPart(): Boolean = isLetterOrDigit() || this == '_'

// =====================================================================

/**
 * Symbol tables extracted from one or more parsed [Program]s: top-level
 * functions, packs, enums and bindings, plus enough per-function structure to
 * answer "which locals are in scope at line N" and "what pack type does
 * variable X have".
 */
internal class SymbolIndex {

    val functions = linkedMapOf<String, FuncDecl>()
    val packs = linkedMapOf<String, TopLevel.Pack>()
    val enums = linkedMapOf<String, TopLevel.Enum>()

    /** symbol name → module it came from ("std.math"), when packaged. */
    val origins = linkedMapOf<String, String>()

    /** name → display detail for top-level var/let/fin bindings. */
    val topLevelVars = linkedMapOf<String, String>()

    /** Top-level variable name → pack type name (for member completion). */
    private val topLevelVarTypes = mutableMapOf<String, String>()

    fun addProgram(program: Program, moduleOverride: String? = null) {
        val module = moduleOverride ?: program.packageName
        fun origin(name: String) {
            if (module != null) origins.putIfAbsent(name, module)
        }
        for (item in program.items) {
            when (item) {
                is TopLevel.Func -> { functions[item.decl.name] = item.decl; origin(item.decl.name) }
                is TopLevel.Pack -> { packs[item.name] = item; origin(item.name) }
                is TopLevel.Enum -> { enums[item.name] = item; origin(item.name) }
                is TopLevel.Solo -> item.methods.forEach { functions[it.name] = it }
                is TopLevel.VarDecl -> registerTopVar(item.name, "var", item.type, item.initializer)
                is TopLevel.LetDecl -> registerTopVar(item.name, "let", item.type, item.initializer)
                is TopLevel.FinDecl -> registerTopVar(item.name, "fin", item.type, item.initializer)
                else -> {}
            }
        }
        programs.add(program)
    }

    private val programs = mutableListOf<Program>()

    private fun registerTopVar(name: String, keyword: String, type: TypeRef?, initializer: org.azora.lang.frontend.Expr) {
        topLevelVars[name] = "$keyword $name" + (type?.let { ": ${it.displayName()}" } ?: "")
        packTypeOf(type, initializer)?.let { topLevelVarTypes[name] = it }
    }

    /** Locals + params of the function whose body contains [line]: (name, kind, detail). */
    fun localsAt(line: Int): List<Triple<String, String, String>> {
        val enclosing = enclosingFunction(line) ?: return emptyList()
        val out = mutableListOf<Triple<String, String, String>>()
        for (param in enclosing.params) {
            out.add(Triple(param.name, "param", "${param.name}: ${param.typeName}"))
        }
        collectLocals(enclosing.body, line, out)
        return out
    }

    /** The pack type name of variable [name] visible at [line], if known. */
    fun typeOfVariable(name: String, line: Int): String? {
        topLevelVarTypes[name]?.let { return it }
        val enclosing = enclosingFunction(line) ?: return null
        enclosing.params.find { it.name == name }?.let { param ->
            (param.type as? TypeRef.Named)?.name?.takeIf { it in packs }?.let { return it }
        }
        var found: String? = null
        walkStmts(enclosing.body) { stmt ->
            val (declName, type, init) = when (stmt) {
                is Stmt.VarDecl -> Triple(stmt.name, stmt.type, stmt.initializer)
                is Stmt.LetDecl -> Triple(stmt.name, stmt.type, stmt.initializer)
                is Stmt.FinDecl -> Triple(stmt.name, stmt.type, stmt.initializer)
                else -> return@walkStmts
            }
            if (declName == name && stmt.line <= line) {
                packTypeOf((type as? TypeAnnotation.Explicit)?.ref, init)?.let { found = it }
            }
        }
        return found
    }

    private fun packTypeOf(type: TypeRef?, initializer: org.azora.lang.frontend.Expr): String? {
        (type as? TypeRef.Named)?.name?.takeIf { it in packs }?.let { return it }
        return (initializer as? org.azora.lang.frontend.Expr.Call)?.callee?.takeIf { it in packs }
    }

    private fun enclosingFunction(line: Int): FuncDecl? {
        // Functions sorted by line; the enclosing one is the last starting at or before `line`
        // whose successor starts after it.
        val sorted = functions.values.sortedBy { it.line }
        var candidate: FuncDecl? = null
        for (decl in sorted) {
            if (decl.line <= line) candidate = decl else break
        }
        return candidate
    }

    private fun collectLocals(body: List<Stmt>, line: Int, out: MutableList<Triple<String, String, String>>) {
        walkStmts(body) { stmt ->
            when (stmt) {
                is Stmt.VarDecl -> if (stmt.line <= line) out.add(Triple(stmt.name, "variable", declDetail("var", stmt.name, stmt.type)))
                is Stmt.LetDecl -> if (stmt.line <= line) out.add(Triple(stmt.name, "variable", declDetail("let", stmt.name, stmt.type)))
                is Stmt.FinDecl -> if (stmt.line <= line) out.add(Triple(stmt.name, "variable", declDetail("fin", stmt.name, stmt.type)))
                is Stmt.For -> if (stmt.line <= line) out.add(Triple(stmt.name, "param", "${stmt.name}: Int"))
                else -> {}
            }
        }
    }

    private fun declDetail(keyword: String, name: String, type: TypeAnnotation): String =
        "$keyword $name" + ((type as? TypeAnnotation.Explicit)?.ref?.let { ": ${it.displayName()}" } ?: "")

    /** Depth-first walk over statements including nested blocks. */
    private fun walkStmts(body: List<Stmt>, visit: (Stmt) -> Unit) {
        for (stmt in body) {
            visit(stmt)
            when (stmt) {
                is Stmt.If -> { walkStmts(stmt.thenBranch, visit); stmt.elseBranch?.let { walkStmts(it, visit) } }
                is Stmt.While -> walkStmts(stmt.body, visit)
                is Stmt.For -> walkStmts(stmt.body, visit)
                is Stmt.Loop -> walkStmts(stmt.body, visit)
                is Stmt.When -> { stmt.branches.forEach { walkStmts(it.body, visit) }; stmt.elseBranch?.let { walkStmts(it, visit) } }
                is Stmt.Try -> { walkStmts(stmt.body, visit); stmt.catchBody?.let { walkStmts(it, visit) } }
                is Stmt.Zone -> walkStmts(stmt.body, visit)
                is Stmt.FriendZone -> walkStmts(stmt.body, visit)
                is Stmt.Defer -> walkStmts(stmt.body, visit)
                is Stmt.InlineBlock -> walkStmts(stmt.body, visit)
                is Stmt.DeepInlineBlock -> walkStmts(stmt.body, visit)
                is Stmt.Effect -> walkStmts(stmt.body, visit)
                else -> {}
            }
        }
    }
}
