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
 * The Azora Language Server - full language intelligence for `.az` sources.
 *
 * Packaged as a self-contained jar (`azls.jar`) that Azora Studio loads via a
 * `URLClassLoader` and calls **reflectively**; every method therefore takes and
 * returns plain [String]s (JSON), so no types cross the classloader boundary.
 *
 * Capabilities:
 * - [highlight] - error-tolerant syntax colorizer spans.
 * - [diagnostics] - full compiler errors/warnings (lex, parse, semantic).
 * - [complete] - keywords, builtins, user symbols, in-scope locals/params,
 *   pack fields and enum variants.
 * - [hover] - signatures for functions / packs / enums under the caret.
 * - [symbols] - document outline of top-level declarations.
 *
 * The optional `prelude` parameter carries the rest of the compilation unit
 * (other project files, installed engine libraries) so cross-file symbols
 * resolve; diagnostics are mapped back to the edited document's line numbers.
 */
class AzoraLanguageServer {

    private val json = Json { encodeDefaults = true }

    /** Standard-library symbols (functions, constants, packs, enums) for completion/hover. */
    private val stdlibIndex: SymbolIndex by lazy {
        val programs = AzStdlib.loadPrograms()
        val files = AzStdlib.tree().files
        SymbolIndex().apply {
            programs.forEachIndexed { index, program ->
                addProgram(program, sourceText = files.getOrNull(index)?.source)
            }
        }
    }

    /** Memoized prelude index - Studio passes the same prelude on every keystroke. */
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

    fun highlight(source: String): String {
        val imports = importsOf(source)
        val visibleFunctions = stdlibIndex.functions.keys.filterTo(mutableSetOf()) { name ->
            moduleVisible(stdlibIndex.origins[name], imports)
        }
        val visibleTypes = stdlibIndex.qualifiedTypes.filterTo(mutableSetOf()) { name ->
            moduleVisible(stdlibIndex.qualifiedTypeOrigins[name], imports)
        }
        return json.encodeToString(
            ListSerializer(HighlightSpan.serializer()),
            AzHighlighter.highlight(source, visibleFunctions, visibleTypes),
        )
    }

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

        // The cursor's line is the one being typed and usually doesn't parse -
        // blank it before indexing (it can't declare anything its own
        // completion needs), falling back to the raw source when that fails.
        val blanked = source.lines().toMutableList()
            .also { if (cursorLine in 1..it.size) it[cursorLine - 1] = "" }
            .joinToString("\n")
        val program = parseTolerant(blanked) ?: parseTolerant(source)
        val userIndex = SymbolIndex().apply { program?.let(::addProgram) }

        val out = mutableListOf<Completion>()

        if (receiver != null) {
            completeMembers(receiver, userIndex, preludeIndex, cursorLine, importsOf(source), out)
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
                    index.origins[name]?.let { "$detail - $it" } ?: detail
                index.functions.values.forEach {
                    if ("__" !in it.name && visible(it.name)) {
                        out.add(functionCompletion(it).let { c -> c.copy(detail = withOrigin(c.label, c.detail)) })
                    }
                }
                index.packs.values.forEach {
                    if (!index.isRealmType(it.name) && visible(it.name)) {
                        out.add(Completion(it.name, "pack", withOrigin(it.name, packDetail(it)), it.name))
                    }
                }
                index.enums.values.forEach {
                    if (!index.isRealmType(it.name) && visible(it.name)) {
                        out.add(Completion(it.name, "enum", withOrigin(it.name, "enum ${it.name}"), it.name))
                    }
                }
                index.topLevelVars.forEach { (name, detail) ->
                    if ("__" !in name && visible(name)) {
                        out.add(Completion(name, "variable", withOrigin(name, detail), name))
                    }
                }
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
        receiver: MemberReceiver,
        userIndex: SymbolIndex,
        preludeIndex: SymbolIndex,
        cursorLine: Int,
        imports: Set<String>,
        out: MutableList<Completion>,
    ) {
        val indices = listOf(userIndex, preludeIndex, stdlibIndex)
        if (receiver.realmQualified) {
            completeRealmMembers(receiver.text, indices, imports, out)
            return
        }

        // Enum values: `Color.` → variants.
        for (index in indices) {
            index.enums[receiver.text]?.let { enum ->
                enum.variants.forEach { out.add(Completion(it, "enumMember", "${enum.name}.$it", it)) }
                return
            }
        }
        // Pack fields via the receiver variable's declared/constructed type.
        val packName = userIndex.typeOfVariable(receiver.text, cursorLine)
            ?: preludeIndex.typeOfVariable(receiver.text, Int.MAX_VALUE)
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
        // Unknown receiver - offer the built-in container/string methods.
        BUILTIN_METHODS.forEach { (name, detail) -> out.add(Completion(name, "method", detail, name)) }
    }

    /** Members reached through a realm-qualified path such as `ab`. */
    private fun completeRealmMembers(
        realm: String,
        indices: List<SymbolIndex>,
        imports: Set<String>,
        out: MutableList<Completion>,
    ) {
        val canonicalRealm = realm.replace("::", "__")
        val prefix = "${canonicalRealm}__"
        for (index in indices) {
            fun visible(name: String): Boolean = moduleVisible(index.origins[name], imports)
            fun directSegment(name: String): String? = name.takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)?.substringBefore("__")?.takeIf { it.isNotEmpty() }
            fun realmChild(name: String): Boolean = "__" in name.removePrefix(prefix)

            for ((canonical, declaration) in index.functions) {
                val label = directSegment(canonical) ?: continue
                if (!visible(canonical)) continue
                if (realmChild(canonical)) {
                    out += Completion(label, "realm", "realm $realm::$label", label)
                } else {
                    val completion = functionCompletion(declaration.copy(name = label))
                    val detail = index.origins[canonical]?.let { "${completion.detail} - $it" } ?: completion.detail
                    out += completion.copy(detail = detail)
                }
            }
            for ((canonical, bindingDetail) in index.topLevelVars) {
                val label = directSegment(canonical) ?: continue
                if (!visible(canonical)) continue
                if (realmChild(canonical)) {
                    out += Completion(label, "realm", "realm $realm::$label", label)
                } else {
                    val plain = bindingDetail.replaceFirst(canonical, label)
                    val detail = index.origins[canonical]?.let { "$plain - $it" } ?: plain
                    out += Completion(label, "variable", detail, label)
                }
            }
            for (canonical in index.qualifiedTypes) {
                val label = directSegment(canonical) ?: continue
                val origin = index.qualifiedTypeOrigins[canonical]
                if (!moduleVisible(origin, imports)) continue
                if (realmChild(canonical)) {
                    out += Completion(label, "realm", "realm $realm::$label", label)
                    continue
                }
                index.packs[label]?.let { pack ->
                    out += Completion(label, "pack", packDetail(pack) + (origin?.let { " - $it" } ?: ""), label)
                }
                index.enums[label]?.let {
                    out += Completion(label, "enum", "enum $label" + (origin?.let { " - $it" } ?: ""), label)
                }
            }
        }
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
        localDeclaration(source, word, offset)?.let { local ->
            return json.encodeToString(Hover.serializer(), Hover(local.detail))
        }
        val role = referenceRole(source, offset)
        val keys = listOf(qualifiedNameAt(source, word, offset), word).distinct()
        val userIndex = SymbolIndex().apply { parseTolerant(source)?.let(::addProgram) }
        // Doc comments are only extracted for the edited document: prelude
        // sections are parsed individually so their declaration lines don't map
        // back to the concatenated prelude text.
        val indices = listOf(userIndex, preludeIndex(prelude), stdlibIndex)
        for (index in indices) {
            val fromUser = index === userIndex
            fun callable(key: String): String? = index.functions[key]?.let {
                val doc = if (fromUser) docCommentAbove(source, it.line) else index.documentation[key].orEmpty()
                json.encodeToString(Hover.serializer(), Hover(signatureOf(it.copy(name = word)), doc = doc))
            }
            fun type(key: String): String? {
                val name = if ("__" in key) {
                    key.takeIf { it in index.qualifiedTypes }?.substringAfterLast("__") ?: return null
                } else key
                index.packs[name]?.let {
                    val doc = if (fromUser) docCommentAbove(source, it.line)
                    else index.documentation[key].orEmpty().ifEmpty { index.documentation[name].orEmpty() }
                    return json.encodeToString(Hover.serializer(), Hover(packDetail(it), doc = doc))
                }
                index.enums[name]?.let {
                    val doc = if (fromUser) docCommentAbove(source, it.line)
                    else index.documentation[key].orEmpty().ifEmpty { index.documentation[name].orEmpty() }
                    return json.encodeToString(Hover.serializer(), Hover("enum ${it.name} { ${it.variants.joinToString(", ")} }", doc = doc))
                }
                return null
            }
            for (key in keys) {
                val resolved = when (role) {
                    ReferenceRole.CALLABLE -> callable(key) ?: type(key)
                    ReferenceRole.TYPE -> type(key)
                    ReferenceRole.VALUE -> index.topLevelVars[key]?.let {
                        val doc = if (fromUser) index.declarationLine(key, ReferenceRole.VALUE)
                            ?.let { line -> docCommentAbove(source, line) }.orEmpty()
                        else index.documentation[key].orEmpty()
                        json.encodeToString(
                            Hover.serializer(),
                            Hover(it.replaceFirst(key, word), doc = doc),
                        )
                    } ?: callable(key) ?: type(key)
                    ReferenceRole.ANY -> callable(key) ?: type(key) ?: index.topLevelVars[key]?.let {
                        val doc = if (fromUser) index.declarationLine(key, ReferenceRole.VALUE)
                            ?.let { line -> docCommentAbove(source, line) }.orEmpty()
                        else index.documentation[key].orEmpty()
                        json.encodeToString(
                            Hover.serializer(),
                            Hover(it.replaceFirst(key, word), doc = doc),
                        )
                    }
                }
                if (resolved != null) return resolved
            }
        }
        return "null"
    }

    // -----------------------------------------------------------------
    // Go to definition
    // -----------------------------------------------------------------

    /**
     * Resolves the declaration of the symbol at [offset].
     *
     * Locals/params and top-level declarations in [source] resolve to a line in
     * the edited document; symbols that live in the [prelude] (other project
     * files / installed libraries) or the stdlib resolve to a name the client
     * can search for. Returns `"null"` when the word isn't a known symbol.
     */
    fun definition(source: String, offset: Int, prelude: String = ""): String {
        val word = wordAt(source, offset) ?: return "null"
        val safeOffset = offset.coerceIn(0, source.length)
        val cursorLine = source.take(safeOffset).count { it == '\n' } + 1
        val userIndex = SymbolIndex().apply { parseTolerant(source)?.let(::addProgram) }
        val role = referenceRole(source, safeOffset)
        val keys = listOf(qualifiedNameAt(source, word, safeOffset), word).distinct()

        // Resolve an actual lexical declaration, not merely the nearest same-
        // spelled name on an earlier line. This respects nested/sibling blocks,
        // local shadowing, contextual receiver parameters, and loop bindings.
        localDeclaration(source, word, safeOffset)?.let { local ->
            return json.encodeToString(
                Definition.serializer(),
                Definition(line = local.line, column = local.column, name = word, inCurrentFile = true),
            )
        }
        for (key in keys) {
            userIndex.declarationLine(key, role)?.let { line ->
                return json.encodeToString(Definition.serializer(), Definition(line = line, name = word, inCurrentFile = true))
            }
        }
        // Known elsewhere (prelude/stdlib): report the name so the client can
        // locate the source file.
        val preludeIndex = preludeIndex(prelude)
        val known = keys.any { key -> preludeIndex.hasSymbol(key, role) || stdlibIndex.hasSymbol(key, role) }
        if (known) {
            return json.encodeToString(Definition.serializer(), Definition(line = 0, name = word, inCurrentFile = false))
        }
        return "null"
    }

    /**
     * The contiguous `//` / `///` comment block immediately above [declLine]
     * (1-based) in [sourceText], as plain text, or `""` when there is none.
     * Blank lines and `@annotation` lines between the comment and the
     * declaration are tolerated.
     */
    private fun docCommentAbove(sourceText: String, declLine: Int): String {
        return extractDocumentationAbove(sourceText, declLine)
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
            if (section.isNotBlank()) {
                val sourceText = section.toString()
                parseTolerant(sourceText)?.let { index.addProgram(it, module, sourceText) }
            }
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
     * Module paths imported by the document's `import` lines. The reader is
     * syntax-only: it records paths and lets completion visibility decide what
     * those paths mean for packaged symbols.
     */
    private fun importsOf(source: String): Set<String> {
        val out = mutableSetOf<String>()
        for (line in source.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("import ")) continue
            val rest = trimmed.removePrefix("import ").trim()
            for (part in splitUseParts(rest)) addImportPath(part.trim(), out)
        }
        return out
    }

    private fun splitUseParts(text: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in text) {
            when {
                ch == '{' -> {
                    depth++
                    current.append(ch)
                }
                ch == '}' -> {
                    if (depth > 0) depth--
                    current.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        parts.add(current.toString())
        return parts
    }

    private fun addImportPath(rawPart: String, out: MutableSet<String>) {
        val part = rawPart.substringBefore("//").trim()
        if (part.isEmpty() || "::" in part) return

        if (part.endsWith(".*")) {
            addValidImport(part.removeSuffix(".*"), out)
            return
        }

        val groupStart = part.indexOf(".{")
        if (groupStart >= 0 && part.endsWith("}")) {
            val base = part.substring(0, groupStart)
            val inner = part.substring(groupStart + 2, part.length - 1)
            if (!isValidImportPath(base)) return
            splitUseParts(inner).map { it.trim() }.filter(::isValidImportName).forEach { out.add("$base.$it") }
            return
        }

        addValidImport(part, out)
    }

    private fun addValidImport(path: String, out: MutableSet<String>) {
        if (isValidImportPath(path)) out.add(path)
    }

    private fun isValidImportPath(path: String): Boolean =
        path.isNotEmpty() && path.split('.').all(::isValidImportName)

    private fun isValidImportName(name: String): Boolean =
        name.isNotEmpty() && name[0].isIdentStart() && name.all { it.isIdentPart() }

    /** A packaged symbol is visible when its module (or a parent) is imported. */
    private fun moduleVisible(origin: String?, imports: Set<String>): Boolean {
        if (origin == null) return true
        return imports.any { imported ->
            origin == imported || origin.startsWith("$imported.") || imported.startsWith("$origin.")
        }
    }

    // ----- text helpers -----

    /** The identifier fragment immediately before [offset]: (startOffset, text). */
    private fun wordBefore(source: String, offset: Int): Pair<Int, String> {
        var start = offset
        while (start > 0 && source[start - 1].isIdentPart()) start--
        return start to source.substring(start, offset)
    }

    private data class MemberReceiver(val text: String, val realmQualified: Boolean)

    /** Receiver before a member (`value.`) or realm path (``) completion. */
    private fun receiverBefore(source: String, wordStart: Int): MemberReceiver? {
        if (wordStart >= 2 && source[wordStart - 2] == ':' && source[wordStart - 1] == ':') {
            val end = wordStart - 2
            var start = end
            while (start > 0) {
                when {
                    source[start - 1].isIdentPart() -> start--
                    start >= 2 && source[start - 2] == ':' && source[start - 1] == ':' -> start -= 2
                    else -> break
                }
            }
            return source.substring(start, end).takeIf { it.isNotEmpty() }
                ?.let { MemberReceiver(it, realmQualified = true) }
        }
        if (wordStart == 0 || source[wordStart - 1] != '.') return null
        val end = wordStart - 1
        // `..` is a range, not a member access.
        if (end > 0 && source[end - 1] == '.') return null
        var start = end
        while (start > 0 && source[start - 1].isIdentPart()) start--
        return source.substring(start, end).takeIf { it.isNotEmpty() }
            ?.let { MemberReceiver(it, realmQualified = false) }
    }

    private data class LocalDeclaration(
        val line: Int,
        val column: Int,
        val offset: Int,
        val detail: String,
        val depth: Int,
    )

    private data class TextBlock(val open: Int, val close: Int, val depth: Int)

    /** Lexically resolves a local/parameter declaration at one concrete use. */
    private fun localDeclaration(source: String, name: String, useOffset: Int): LocalDeclaration? {
        val code = codeOnly(source)
        val blocks = textBlocks(code)
        val candidates = mutableListOf<LocalDeclaration>()

        fun add(offset: Int, detail: String, scope: TextBlock) {
            if (offset >= useOffset || useOffset !in (scope.open + 1)..scope.close) return
            candidates += LocalDeclaration(
                line = source.take(offset).count { it == '\n' } + 1,
                column = offset - source.lastIndexOf('\n', offset - 1),
                offset = offset,
                detail = detail,
                depth = scope.depth,
            )
        }

        LOCAL_BINDING.findAll(code).forEach { binding ->
            val declared = binding.groups[2] ?: return@forEach
            if (declared.value != name) return@forEach
            val scope = containingTextBlock(blocks, declared.range.first) ?: return@forEach
            val type = binding.groups[3]?.value?.trim().orEmpty()
            val detail = "${binding.groupValues[1]} $name" + if (type.isEmpty()) "" else ": $type"
            add(declared.range.first, detail, scope)
        }

        FOR_BINDING.findAll(code).forEach { binding ->
            val declared = binding.groups[1] ?: return@forEach
            if (declared.value != name) return@forEach
            val bodyOpen = code.indexOf('{', binding.range.last + 1)
            val scope = blocks.firstOrNull { it.open == bodyOpen } ?: return@forEach
            add(declared.range.first, "for $name", scope)
        }

        CALLABLE_SIGNATURE.findAll(code).forEach { callable ->
            val bodyOpen = code.indexOf('{', callable.range.last + 1)
            val scope = blocks.firstOrNull { it.open == bodyOpen } ?: return@forEach
            if (useOffset !in (scope.open + 1)..scope.close) return@forEach
            for (groupIndex in listOf(1, 2)) {
                val parameters = callable.groups[groupIndex] ?: continue
                PARAMETER_DECLARATION.findAll(parameters.value).forEach { parameter ->
                    val declared = parameter.groups[1] ?: return@forEach
                    if (declared.value != name) return@forEach
                    val absolute = parameters.range.first + declared.range.first
                    val type = parameter.groups[2]?.value?.trim().orEmpty()
                    add(absolute, "$name: $type", scope)
                }
            }
        }

        return candidates.maxWithOrNull(compareBy<LocalDeclaration> { it.depth }.thenBy { it.offset })
    }

    /** Source with comments/literals blanked while every original offset stays stable. */
    private fun codeOnly(source: String): String {
        val result = source.toCharArray()
        var index = 0
        var blockCommentDepth = 0
        fun blank(at: Int) {
            if (result[at] != '\n' && result[at] != '\r') result[at] = ' '
        }
        while (index < source.length) {
            when {
                blockCommentDepth > 0 -> when {
                    source.startsWith("/*", index) -> {
                        blank(index); blank(index + 1); index += 2; blockCommentDepth++
                    }
                    source.startsWith("*/", index) -> {
                        blank(index); blank(index + 1); index += 2; blockCommentDepth--
                    }
                    else -> blank(index++)
                }
                source.startsWith("//", index) ->
                    while (index < source.length && source[index] != '\n') blank(index++)
                source.startsWith("/*", index) -> {
                    blank(index); blank(index + 1); index += 2; blockCommentDepth = 1
                }
                source.startsWith("\"\"\"", index) -> {
                    repeat(3) { blank(index++) }
                    while (index < source.length && !source.startsWith("\"\"\"", index)) blank(index++)
                    repeat(minOf(3, source.length - index)) { blank(index++) }
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index]
                    blank(index++)
                    while (index < source.length) {
                        val character = source[index]
                        blank(index++)
                        if (character == '\\' && index < source.length) blank(index++)
                        else if (character == quote) break
                    }
                }
                else -> index++
            }
        }
        return result.concatToString()
    }

    private fun textBlocks(code: String): List<TextBlock> {
        val stack = ArrayDeque<Pair<Int, Int>>()
        val blocks = mutableListOf<TextBlock>()
        for (offset in code.indices) {
            when (code[offset]) {
                '{' -> stack.addLast(offset to stack.size)
                '}' -> stack.removeLastOrNull()?.let { (open, depth) -> blocks += TextBlock(open, offset, depth) }
            }
        }
        while (stack.isNotEmpty()) {
            val (open, depth) = stack.removeLast()
            blocks += TextBlock(open, code.length, depth)
        }
        return blocks
    }

    private fun containingTextBlock(blocks: List<TextBlock>, offset: Int): TextBlock? =
        blocks.filter { offset in (it.open + 1)..it.close }
            .maxWithOrNull(compareBy<TextBlock> { it.depth }.thenBy { it.open })

    /** The identifier containing [offset], or null. */
    private fun wordAt(source: String, offset: Int): String? {
        if (source.isEmpty()) return null
        var start = offset.coerceIn(0, source.length)
        while (start > 0 && source[start - 1].isIdentPart()) start--
        var end = offset.coerceIn(0, source.length)
        while (end < source.length && source[end].isIdentPart()) end++
        return source.substring(start, end).takeIf { it.isNotEmpty() && it[0].isIdentStart() }
    }

    /** Canonical compiler key for `realm::name` at [offset] (`realm__name`). */
    private fun qualifiedNameAt(source: String, name: String, offset: Int): String {
        var wordStart = offset.coerceIn(0, source.length)
        while (wordStart > 0 && source[wordStart - 1].isIdentPart()) wordStart--
        val parts = mutableListOf(name)
        var cursor = wordStart
        while (cursor >= 2 && source[cursor - 2] == ':' && source[cursor - 1] == ':') {
            val end = cursor - 2
            var start = end
            while (start > 0 && source[start - 1].isIdentPart()) start--
            if (start == end) break
            parts.add(0, source.substring(start, end))
            cursor = start
        }
        return parts.joinToString("__")
    }

    private fun referenceRole(source: String, offset: Int): ReferenceRole {
        if (source.isEmpty()) return ReferenceRole.ANY
        var start = offset.coerceIn(0, source.length)
        while (start > 0 && source[start - 1].isIdentPart()) start--
        var end = offset.coerceIn(0, source.length)
        while (end < source.length && source[end].isIdentPart()) end++

        var next = end
        while (next < source.length && source[next].isWhitespace()) next++
        if (source.getOrNull(next) == '(') return ReferenceRole.CALLABLE

        var previous = start - 1
        while (previous >= 0 && source[previous].isWhitespace()) previous--
        if (source.getOrNull(previous) == ':' && source.getOrNull(previous - 1) != ':') return ReferenceRole.TYPE

        val before = source.substring(0, start)
        val previousWord = Regex("""[A-Za-z_][A-Za-z0-9_]*\s*$""").find(before)?.value?.trim()
        if (previousWord in TYPE_CONTEXT_WORDS) return ReferenceRole.TYPE
        if (previousWord == "func") return ReferenceRole.CALLABLE
        if (previousWord in VALUE_DECLARATION_WORDS) return ReferenceRole.VALUE

        if (source.getOrNull(next) == '<') {
            return if (previousWord in TYPE_CONTEXT_WORDS) ReferenceRole.TYPE else ReferenceRole.CALLABLE
        }
        // A bare identifier is a value use. Functions and constructors still
        // remain valid first-class values through the value-role fallback, but
        // a same-spelled binding wins over them at this use site.
        return ReferenceRole.VALUE
    }

    private companion object {
        val EMPTY_INDEX = SymbolIndex()

        /** Prefix marking the module of the prelude section that follows. */
        const val MODULE_MARKER = "//@azora-module"

        /** Functions the compiler registers as builtins (see SymbolCollector). */
        val BUILTIN_FUNCTIONS = listOf(
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

        val LOCAL_BINDING = Regex(
            """\b(var|val|fin|let)\s+([A-Za-z_][A-Za-z0-9_]*)(?:[ \t]*:[ \t]*([^=\n]+?))?(?:[ \t]*=|\n|$)""",
        )
        val FOR_BINDING = Regex("""\bfor\s+([A-Za-z_][A-Za-z0-9_]*)\s+in\b""")
        val CALLABLE_SIGNATURE = Regex(
            """(?m)^\s*(?:(?:exposed|protected|confined|inline|deepinline|noinline|unsafe|react|async|bridge|lazy)\s+)*(?:func\s+[A-Za-z_][A-Za-z0-9_]*(?:\s*<[^>{}\n]*>)?|ctor|dtor|oper[^\s\[(]*)\s*(?:\[([^]\n]*)])?\s*\(([^)]*)\)""",
        )
        val PARAMETER_DECLARATION = Regex(
            """(?:\.\.\.)?([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^,\n]+)""",
        )
        val TYPE_CONTEXT_WORDS = setOf(
            "as", "is", "for", "impl", "pack", "enum", "error", "spec",
            "annot", "typealias", "derives", "requires", "assoc",
        )
        val VALUE_DECLARATION_WORDS = setOf("var", "val", "fin", "let")
    }
}

internal enum class ReferenceRole { CALLABLE, TYPE, VALUE, ANY }

private fun Char.isIdentStart(): Boolean = isLetter() || this == '_'
private fun Char.isIdentPart(): Boolean = isLetterOrDigit() || this == '_'

/** Line or block documentation immediately above a declaration. */
private fun extractDocumentationAbove(source: String, declarationLine: Int): String {
    val lines = source.lines()
    if (declarationLine !in 1..lines.size) return ""
    var index = declarationLine - 2
    while (index >= 0 && lines[index].trim().startsWith("@")) index--

    if (index >= 0 && lines[index].trim().endsWith("*/")) {
        val end = index
        while (index >= 0 && !lines[index].trim().startsWith("/*")) index--
        if (index >= 0) {
            return lines.subList(index, end + 1).joinToString("\n") { line ->
                line.trim()
                    .removePrefix("/**")
                    .removePrefix("/*")
                    .removeSuffix("*/")
                    .trim()
                    .removePrefix("*")
                    .trimStart()
            }.trim()
        }
    }

    val collected = ArrayDeque<String>()
    while (index >= 0) {
        val trimmed = lines[index].trim()
        when {
            trimmed.startsWith("///") -> collected.addFirst(trimmed.removePrefix("///").trim())
            trimmed.startsWith("//") -> collected.addFirst(trimmed.removePrefix("//").trim())
            else -> break
        }
        index--
    }
    return collected.joinToString("\n").trim()
}

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

    /** Canonical realm-qualified type names (`std__Int`, `shapes__Point`). */
    val qualifiedTypes = linkedSetOf<String>()
    val qualifiedTypeOrigins = linkedMapOf<String, String?>()

    /** symbol name → module it came from ("std.math"), when packaged. */
    val origins = linkedMapOf<String, String>()

    /** Canonical symbol key → source documentation immediately above it. */
    val documentation = linkedMapOf<String, String>()

    /** name → display detail for top-level var/let/fin bindings. */
    val topLevelVars = linkedMapOf<String, String>()

    /** Top-level variable name → pack type name (for member completion). */
    private val topLevelVarTypes = mutableMapOf<String, String>()

    /** Top-level binding name → its declaration line (for go-to-definition). */
    private val topLevelVarLines = mutableMapOf<String, Int>()

    fun addProgram(program: Program, moduleOverride: String? = null, sourceText: String? = null) {
        val module = moduleOverride ?: program.moduleName
        fun origin(name: String) {
            if (module != null) origins.putIfAbsent(name, module)
        }
        fun document(name: String, line: Int) {
            val doc = sourceText?.let { documentationAbove(it, line) }.orEmpty()
            if (doc.isNotEmpty()) documentation.putIfAbsent(name, doc)
        }
        fun typeOrigin(name: String): String {
            val namespace = program.realmTypeNamespaces[name]
            val qualified = if (namespace == null) name else "${namespace.replace("::", "__")}__$name"
            qualifiedTypes += qualified
            qualifiedTypeOrigins.putIfAbsent(qualified, module)
            return qualified
        }
        for (item in program.items) {
            when (item) {
                is TopLevel.Func -> {
                    functions[item.decl.name] = item.decl
                    origin(item.decl.name)
                    document(item.decl.name, item.decl.line)
                }
                is TopLevel.Bridge -> item.funcs.forEach { decl ->
                    functions[decl.name] = FuncDecl(
                        name = decl.name,
                        params = decl.params,
                        returnType = TypeAnnotation.Explicit(decl.returnType),
                        body = emptyList(),
                        typeParams = decl.typeParams,
                        line = decl.line,
                        column = decl.column,
                    )
                    origin(decl.name)
                    document(decl.name, decl.line)
                }
                is TopLevel.Pack -> {
                    packs[item.name] = item
                    origin(item.name)
                    val canonical = typeOrigin(item.name)
                    document(item.name, item.line)
                    documentation[item.name]?.let { documentation.putIfAbsent(canonical, it) }
                }
                is TopLevel.Enum -> {
                    enums[item.name] = item
                    origin(item.name)
                    val canonical = typeOrigin(item.name)
                    document(item.name, item.line)
                    documentation[item.name]?.let { documentation.putIfAbsent(canonical, it) }
                }
                is TopLevel.Solo -> item.methods.forEach {
                    functions[it.name] = it
                    origin(it.name)
                    document(it.name, it.line)
                }
                is TopLevel.VarDecl -> {
                    registerTopVar(item.name, "var", item.type, item.initializer, item.line)
                    origin(item.name)
                    document(item.name, item.line)
                }
                is TopLevel.LetDecl -> {
                    registerTopVar(item.name, "let", item.type, item.initializer, item.line)
                    origin(item.name)
                    document(item.name, item.line)
                }
                is TopLevel.FinDecl -> {
                    registerTopVar(item.name, "fin", item.type, item.initializer, item.line)
                    origin(item.name)
                    document(item.name, item.line)
                }
                else -> {}
            }
        }
        programs.add(program)
    }

    private fun documentationAbove(source: String, declarationLine: Int): String {
        return extractDocumentationAbove(source, declarationLine)
    }

    private val programs = mutableListOf<Program>()

    private fun registerTopVar(name: String, keyword: String, type: TypeRef?, initializer: org.azora.lang.frontend.Expr, line: Int) {
        topLevelVars[name] = "$keyword $name" + (type?.let { ": ${it.displayName()}" } ?: "")
        packTypeOf(type, initializer)?.let { topLevelVarTypes[name] = it }
        topLevelVarLines[name] = line
    }

    /** True when [name] is declared inside a named realm and therefore needs `::`. */
    fun isRealmType(name: String): Boolean = qualifiedTypes.any { it != name && it.endsWith("__$name") }

    private fun typeNameForKey(name: String): String? = when {
        name in packs || name in enums -> name
        name in qualifiedTypes -> name.substringAfterLast("__")
        else -> null
    }

    private fun hasType(name: String): Boolean = typeNameForKey(name)?.let { it in packs || it in enums } == true

    private fun typeDeclarationLine(name: String): Int? = typeNameForKey(name)?.let { key ->
        packs[key]?.line ?: enums[key]?.line
    }

    /** Whether [name] has a declaration matching the use-site [role]. */
    fun hasSymbol(name: String, role: ReferenceRole = ReferenceRole.ANY): Boolean = when (role) {
        ReferenceRole.CALLABLE -> name in functions || hasType(name)
        ReferenceRole.TYPE -> hasType(name)
        ReferenceRole.VALUE -> name in topLevelVars || name in functions || hasType(name)
        ReferenceRole.ANY -> name in functions || hasType(name) || name in topLevelVars
    }

    /** Declaration line selected by symbol role rather than spelling alone. */
    fun declarationLine(name: String, role: ReferenceRole = ReferenceRole.ANY): Int? = when (role) {
        ReferenceRole.CALLABLE -> functions[name]?.line ?: typeDeclarationLine(name)
        ReferenceRole.TYPE -> typeDeclarationLine(name)
        ReferenceRole.VALUE -> topLevelVarLines[name] ?: functions[name]?.line ?: typeDeclarationLine(name)
        ReferenceRole.ANY -> functions[name]?.line ?: typeDeclarationLine(name) ?: topLevelVarLines[name]
    }

    /**
     * Declaration line of a local/parameter named [name] visible at [atLine]:
     * the nearest matching `var`/`let`/`fin`/`for` binding at or above the
     * cursor, else a parameter (reported at the function's own line).
     */
    fun localDeclarationLine(name: String, atLine: Int): Int? {
        val enclosing = enclosingFunction(atLine) ?: return null
        var best: Int? = null
        walkStmts(enclosing.body) { stmt ->
            val (declName, line) = when (stmt) {
                is Stmt.VarDecl -> stmt.name to stmt.line
                is Stmt.LetDecl -> stmt.name to stmt.line
                is Stmt.FinDecl -> stmt.name to stmt.line
                is Stmt.For -> stmt.name to stmt.line
                else -> return@walkStmts
            }
            if (declName == name && line <= atLine && (best == null || line > best!!)) best = line
        }
        if (best != null) return best
        if (enclosing.params.any { it.name == name }) return enclosing.line
        return null
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
                is Stmt.Scope -> walkStmts(stmt.body, visit)
                is Stmt.Defer -> walkStmts(stmt.body, visit)
                is Stmt.InlineBlock -> walkStmts(stmt.body, visit)
                is Stmt.DeepInlineBlock -> walkStmts(stmt.body, visit)
                is Stmt.Effect -> walkStmts(stmt.body, visit)
                else -> {}
            }
        }
    }
}
