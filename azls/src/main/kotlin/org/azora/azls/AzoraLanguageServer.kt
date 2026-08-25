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
import org.azora.lang.Compiler
import org.azora.lang.diagnostics.AnalysisMode
import org.azora.lang.diagnostics.AnalysisRequest
import org.azora.lang.diagnostics.DiagnosticRenderer
import org.azora.lang.diagnostics.DocumentVersion
import org.azora.lang.diagnostics.ImmutableSourceManager
import org.azora.lang.diagnostics.PositionEncoding
import org.azora.lang.diagnostics.SourceId
import org.azora.lang.diagnostics.SourceKind
import org.azora.lang.diagnostics.SourceUnit
import org.azora.lang.frontend.FuncDecl
import org.azora.lang.frontend.ImportSpec
import org.azora.lang.frontend.Lexer
import org.azora.lang.frontend.ModuleVisibility
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
 * The reusable language-intelligence facade behind the standard LSP process.
 * [AzlsStdio] owns the JSON-RPC boundary; this class contains transitional
 * compiler-backed feature implementations that are also convenient to test.
 *
 * Capabilities:
 * - [highlight] - error-tolerant syntax colorizer spans.
 * - [diagnostics] - full compiler errors/warnings (lex, parse, semantic).
 * - [complete] - keywords, builtins, user symbols, in-scope locals/params,
 *   pack fields and enum variants.
 * - [hover] - signatures for functions / packs / enums under the caret.
 * - [symbols] - document outline of top-level declarations.
 *
 * Project files are distinct immutable source units in the LSP path. The
 * optional strings retained on a few facade methods are compatibility inputs
 * for callers of this Kotlin API only; diagnostics never concatenate them or
 * recover locations by parsing rendered compiler text.
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

    fun highlight(source: String, prelude: String = ""): String {
        val imports = importsOf(source)
        val visibleFunctions = stdlibIndex.functions.keys.filterTo(mutableSetOf()) { name ->
            originVisible(stdlibIndex, stdlibIndex.origins[name], name, imports)
        }
        val visibleTypes = stdlibIndex.qualifiedTypes.filterTo(mutableSetOf()) { name ->
            originVisible(stdlibIndex, stdlibIndex.qualifiedTypeOrigins[name], name, imports)
        }
        val workspace = preludeIndex(prelude)
        visibleFunctions += workspace.functions.keys.filter { name ->
            originVisible(workspace, workspace.origins[name], name, imports)
        }
        visibleTypes += workspace.qualifiedTypes.filter { name ->
            originVisible(workspace, workspace.qualifiedTypeOrigins[name], name, imports)
        }
        val visibleSpecTypes = stdlibIndex.specs.keys.filterTo(mutableSetOf()) { name ->
            originVisible(stdlibIndex, stdlibIndex.origins[name], name, imports)
        }
        visibleSpecTypes += workspace.specs.keys.filter { name ->
            originVisible(workspace, workspace.origins[name], name, imports)
        }
        val visibleProperties = stdlibIndex.properties.filterTo(mutableSetOf()) { name ->
            originVisible(stdlibIndex, stdlibIndex.propertyOrigins[name], name, imports)
        }
        visibleProperties += workspace.properties.filter { name ->
            originVisible(workspace, workspace.propertyOrigins[name], name, imports)
        }
        val visibleEnumCases = stdlibIndex.enumCases.filterTo(mutableSetOf()) { name ->
            originVisible(stdlibIndex, stdlibIndex.enumCaseOrigins[name], name, imports)
        }
        visibleEnumCases += workspace.enumCases.filter { name ->
            originVisible(workspace, workspace.enumCaseOrigins[name], name, imports)
        }
        val visibleErrorCases = stdlibIndex.errorCases.filterTo(mutableSetOf()) { name ->
            originVisible(stdlibIndex, stdlibIndex.errorCaseOrigins[name], name, imports)
        }
        visibleErrorCases += workspace.errorCases.filter { name ->
            originVisible(workspace, workspace.errorCaseOrigins[name], name, imports)
        }
        return json.encodeToString(
            ListSerializer(HighlightSpan.serializer()),
            AzHighlighter.highlight(
                source, visibleFunctions, visibleTypes, visibleSpecTypes,
                visibleProperties, visibleEnumCases, visibleErrorCases,
            ),
        )
    }

    // -----------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------

    fun diagnostics(source: String, workspaceSource: String = ""): String {
        val document = SourceUnit(
            id = SourceId("azora-memory:/document.az"),
            uri = "azora-memory:/document.az",
            displayPath = "<document>",
            text = source,
            version = DocumentVersion(1),
            kind = SourceKind.USER,
        )
        val sources = buildList {
            add(document)
            if (workspaceSource.isNotBlank()) {
                add(
                    SourceUnit(
                        id = SourceId("azora-memory:/workspace.az"),
                        uri = "azora-memory:/workspace.az",
                        displayPath = "azls/workspace.az",
                        // Compatibility callers historically passed an unnamed
                        // same-unit fragment. Publish it as an exposed virtual
                        // module instead of concatenating it into the document.
                        text = "exposed module azls.workspace\n$workspaceSource",
                        kind = SourceKind.WORKSPACE_LIBRARY,
                    ),
                )
            }
        }
        val snapshot = Compiler().analyze(
            AnalysisRequest(
                sources = sources,
                roots = setOf(document.id),
                mode = AnalysisMode.IDE,
            ),
        )
        val manager = ImmutableSourceManager(sources)
        val mapped = snapshot.diagnostics.mapNotNull { diagnostic ->
            val span = manager.resolveToUserSource(diagnostic.primary.span)
            if (span.source != document.id) return@mapNotNull null
            val range = manager.range(span, PositionEncoding.UTF16) ?: return@mapNotNull null
            Diagnostic(
                line = range.start.line + 1,
                message = DiagnosticRenderer.summary(diagnostic),
                severity = diagnostic.severity.name.lowercase(),
                column = range.start.character + 1,
                endLine = range.end.line + 1,
                endColumn = range.end.character + 1,
                code = diagnostic.code.value,
                stage = diagnostic.stage.name.lowercase(),
            )
        }
        return json.encodeToString(ListSerializer(Diagnostic.serializer()), mapped)
    }

    // -----------------------------------------------------------------
    // Completion
    // -----------------------------------------------------------------

    /** Modules that can make an unresolved bare [name] visible in [source]. */
    internal fun importModules(source: String, name: String, prelude: String = ""): List<String> {
        val imports = importsOf(source)
        return buildList {
            for (index in listOf(preludeIndex(prelude), stdlibIndex)) {
                val origin = index.origins[name] ?: index.qualifiedTypeOrigins[name]
                if (origin != null && !originVisible(index, origin, name, imports) &&
                    index.hasSymbol(name, ReferenceRole.ANY)
                ) {
                    add(origin)
                }
            }
        }.distinct().sortedWith(compareBy({ it.count { ch -> ch == '.' } }, { it }))
    }

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
        val imports = importsOf(source)

        // `@` is its own completion namespace. Mixing ordinary values into
        // this list made annotations and macros effectively disappear in a
        // large workspace after the LSP migration.
        if (wordStart > 0 && source[wordStart - 1] == '@') {
            for (index in listOf(userIndex, preludeIndex, stdlibIndex)) {
                fun autoImport(name: String): String? = index.origins[name]
                    ?.takeUnless { index === userIndex || originVisible(index, it, name, imports) }
                index.decorators.values.forEach { decorator ->
                    out += Completion(
                        decorator.name,
                        "annotation",
                        "annotation @${decorator.name}",
                        decorator.name,
                        autoImport(decorator.name),
                    )
                }
                index.macros.values.forEach { macro ->
                    out += Completion(
                        macro.name,
                        "macro",
                        "macro @${macro.name}",
                        macro.name,
                        autoImport(macro.name),
                    )
                }
            }
            val filtered = out.filter { prefix.isEmpty() || it.label.startsWith(prefix) }
                .distinctBy { it.label + "/" + it.kind + "/" + it.importModule.orEmpty() }
                .sortedBy { it.label }
            return json.encodeToString(ListSerializer(Completion.serializer()), filtered)
        }

        // On an `import` line the dotted path names modules and the symbols one
        // declares - not a value and its members, which is what a `.` means
        // everywhere else.
        val line = source.lineAt(safeOffset)
        if (line.trimStart().startsWith("import ")) {
            completeImportPath(source, wordStart, out)
            return json.encodeToString(ListSerializer(Completion.serializer()), out)
        }

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
            for (index in listOf(userIndex, preludeIndex, stdlibIndex)) {
                // Packaged symbols remain completable before they are visible;
                // [importModule] makes acceptance add the precise import. The
                // document's own symbols never need that edit.
                fun visible(name: String): Boolean =
                    index === userIndex || originVisible(index, index.origins[name], name, imports)
                fun withOrigin(name: String, detail: String): String =
                    index.origins[name]?.let { "$detail - $it" } ?: detail
                fun autoImport(name: String): String? =
                    index.origins[name]?.takeUnless { visible(name) }
                index.functions.values.forEach {
                    if ("__" !in it.name) {
                        out.add(functionCompletion(it).let { c ->
                            c.copy(
                                detail = withOrigin(c.label, c.detail),
                                importModule = autoImport(c.label),
                            )
                        })
                    }
                }
                index.packs.values.forEach {
                    if (!index.isScopeType(it.name)) {
                        out.add(Completion(
                            it.name, "pack", withOrigin(it.name, packDetail(it)), it.name, autoImport(it.name),
                        ))
                    }
                }
                index.enums.values.forEach {
                    if (!index.isScopeType(it.name)) {
                        out.add(Completion(
                            it.name, "enum", withOrigin(it.name, "enum ${it.name}"), it.name, autoImport(it.name),
                        ))
                    }
                }
                index.specs.values.forEach {
                    out.add(Completion(
                        it.name, "spec", withOrigin(it.name, "spec ${it.name}"), it.name, autoImport(it.name),
                    ))
                }
                index.aliases.values.forEach {
                    out.add(Completion(
                        it.name, "type", withOrigin(it.name, "typealias ${it.name} = ${it.type.displayName()}"),
                        it.name, autoImport(it.name),
                    ))
                }
                index.slots.values.forEach {
                    out.add(Completion(
                        it.name, if (it.isError) "error" else "enum",
                        withOrigin(it.name, if (it.isError) "variant error ${it.name}" else "variant enum ${it.name}"),
                        it.name, autoImport(it.name),
                    ))
                }
                index.topLevelVars.forEach { (name, detail) ->
                    if ("__" !in name) {
                        out.add(Completion(name, "variable", withOrigin(name, detail), name, autoImport(name)))
                    }
                }
            }
            constructorBefore(source, safeOffset)?.let { typeName ->
                for (index in listOf(userIndex, preludeIndex, stdlibIndex)) {
                    index.packs[typeName]?.fields?.forEach { field ->
                        out += Completion(field.name, "field", "${field.name}: ${field.type.displayName()}", "${field.name}: ")
                    }
                }
            }
            AzHighlighter.KEYWORDS.forEach { out.add(Completion(it, "keyword", "", it)) }
        }

        val filtered = out
            .filter { prefix.isEmpty() || it.label.startsWith(prefix) }
            .distinctBy { it.label + "/" + it.kind + "/" + it.importModule.orEmpty() }
            .sortedWith(compareBy({ kindRank(it.kind) }, { it.label }))
            .take(200)
        return json.encodeToString(ListSerializer(Completion.serializer()), filtered)
    }

    private fun completeMembers(
        receiver: MemberReceiver,
        userIndex: SymbolIndex,
        preludeIndex: SymbolIndex,
        cursorLine: Int,
        imports: ImportVisibility,
        out: MutableList<Completion>,
    ) {
        val indices = listOf(userIndex, preludeIndex, stdlibIndex)
        if (receiver.scopeQualified) {
            completeScopeMembers(receiver.text, indices, imports, out)
            return
        }

        // Enum values: `Color.` → variants.
        for (index in indices) {
            index.enums[receiver.text]?.let { enum ->
                enum.variants.forEach { out.add(Completion(it, "enumMember", "${enum.name}.$it", it)) }
                return
            }
            index.slots[receiver.text]?.let { slot ->
                slot.variants.forEach { out.add(Completion(it.name, "enumMember", "${slot.name}.${it.name}", it.name)) }
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

    /** Members reached through a scope-qualified path such as `ab`. */
    private fun completeScopeMembers(
        scope: String,
        indices: List<SymbolIndex>,
        imports: ImportVisibility,
        out: MutableList<Completion>,
    ) {
        val canonicalScope = scope.replace("::", "__")
        val prefix = "${canonicalScope}__"
        for (index in indices) {
            fun visible(name: String): Boolean = originVisible(index, index.origins[name], name, imports)
            fun directSegment(name: String): String? = name.takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)?.substringBefore("__")?.takeIf { it.isNotEmpty() }
            fun scopeChild(name: String): Boolean = "__" in name.removePrefix(prefix)

            for ((canonical, declaration) in index.functions) {
                val label = directSegment(canonical) ?: continue
                if (!visible(canonical)) continue
                if (scopeChild(canonical)) {
                    out += Completion(label, "scope", "scope $scope::$label", label)
                } else {
                    val completion = functionCompletion(declaration.copy(name = label))
                    val detail = index.origins[canonical]?.let { "${completion.detail} - $it" } ?: completion.detail
                    out += completion.copy(detail = detail)
                }
            }
            for ((canonical, bindingDetail) in index.topLevelVars) {
                val label = directSegment(canonical) ?: continue
                if (!visible(canonical)) continue
                if (scopeChild(canonical)) {
                    out += Completion(label, "scope", "scope $scope::$label", label)
                } else {
                    val plain = bindingDetail.replaceFirst(canonical, label)
                    val detail = index.origins[canonical]?.let { "$plain - $it" } ?: plain
                    out += Completion(label, "variable", detail, label)
                }
            }
            for (canonical in index.qualifiedTypes) {
                val label = directSegment(canonical) ?: continue
                val origin = index.qualifiedTypeOrigins[canonical]
                if (!originVisible(index, origin, canonical, imports)) continue
                if (scopeChild(canonical)) {
                    out += Completion(label, "scope", "scope $scope::$label", label)
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
        "pack", "enum", "error", "spec", "type", "annotation", "macro" -> 3
        "keyword" -> 4
        else -> 5
    }

    /** Nearest still-open simple constructor call (`Point(` / `Point(x, `). */
    private fun constructorBefore(source: String, offset: Int): String? {
        val before = source.substring(0, offset.coerceIn(0, source.length))
        val open = before.lastIndexOf('(')
        if (open < 0 || before.lastIndexOf(')') > open) return null
        return Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*$")
            .find(before.substring(0, open))?.groupValues?.getOrNull(1)
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
        val imports = importsOf(source)
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
                index.slots[name]?.let {
                    val doc = if (fromUser) docCommentAbove(source, it.line)
                    else index.documentation[key].orEmpty().ifEmpty { index.documentation[name].orEmpty() }
                    val kind = if (it.isError) "variant error" else "variant enum"
                    return json.encodeToString(
                        Hover.serializer(),
                        Hover("$kind ${it.name} { ${it.variants.joinToString(", ") { variant -> variant.name }} }", doc = doc),
                    )
                }
                index.specs[name]?.let {
                    val doc = if (fromUser) docCommentAbove(source, it.line)
                    else index.documentation[key].orEmpty().ifEmpty { index.documentation[name].orEmpty() }
                    return json.encodeToString(Hover.serializer(), Hover("spec ${it.name}", doc = doc))
                }
                index.aliases[name]?.let {
                    val doc = if (fromUser) docCommentAbove(source, it.line)
                    else index.documentation[key].orEmpty().ifEmpty { index.documentation[name].orEmpty() }
                    return json.encodeToString(
                        Hover.serializer(), Hover("typealias ${it.name} = ${it.type.displayName()}", doc = doc),
                    )
                }
                return null
            }
            for (key in keys) {
                // Workspace indexing answers where a symbol *could* come from;
                // it does not place that symbol in this file's lexical scope.
                // Only the edited file, an explicit import, or an exposed
                // auto-imported module may contribute hover information.
                if (!fromUser && !symbolVisible(index, key, role, imports)) continue
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
        val imports = importsOf(source)
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
        // locate the source file, but only when that declaration is visible in
        // the compiler import scope. Workspace discoverability alone is what
        // powers auto-import suggestions; it must never make a reference bind.
        val preludeIndex = preludeIndex(prelude)
        val external = keys.firstNotNullOfOrNull { key ->
            when {
                preludeIndex.hasSymbol(key, role) && symbolVisible(preludeIndex, key, role, imports) ->
                    preludeIndex.originOf(key, role)
                stdlibIndex.hasSymbol(key, role) && symbolVisible(stdlibIndex, key, role, imports) ->
                    stdlibIndex.originOf(key, role)
                else -> null
            }
        }
        if (external != null) {
            return json.encodeToString(
                Definition.serializer(),
                Definition(line = 0, name = word, module = external, inCurrentFile = false),
            )
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
                is TopLevel.Slot -> out.add(DocumentSymbol(item.name, if (item.isError) "error" else "enum", item.line))
                is TopLevel.Spec -> out.add(DocumentSymbol(item.name, "spec", item.line))
                is TopLevel.TypeAlias -> out.add(DocumentSymbol(item.name, "type", item.line))
                is TopLevel.Deco -> out.add(DocumentSymbol(item.name, "annotation", item.line))
                is TopLevel.Meta -> out.add(DocumentSymbol(item.name, "macro", item.line))
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

    /** Best-effort indexing parse; locations are never reconstructed from errors. */
    private fun parseTolerant(source: String): Program? =
        runCatching { Parser(Lexer(source).tokenize()).parse() }.getOrNull()

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
    /** The text of the line containing [offset]. */
    private fun String.lineAt(offset: Int): String {
        val start = lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = indexOf('\n', offset).let { if (it < 0) length else it }
        return substring(start, end.coerceAtLeast(start))
    }

    /**
     * Completions for an `import` path: the modules under what has been typed,
     * and the symbols the named module declares.
     *
     * `import std.math.ab` offers `abs`, because `std.math` is a module and
     * `abs` is one of its declarations - a selective import names a symbol.
     */
    private fun completeImportPath(source: String, wordStart: Int, out: MutableList<Completion>) {
        // Everything between `import ` and the word being typed is the path so
        // far; a trailing `.` leaves it as the parent module.
        val typed = source.substring(0, wordStart)
            .substringAfterLast("import ")
            .substringAfterLast('[')
            .substringAfterLast('{')
            .substringAfterLast(',')
            .substringAfterLast('\n')
            .trim()
        // The separator says what is being asked for. After a `::` the module is
        // settled and what follows is a name it declares; after a `.` the path
        // is still walking down the module tree.
        val selecting = typed.endsWith("::") || "::" in typed
        val parent = typed.trimEnd('.').removeSuffix("::").trimEnd(':').ifEmpty { null }

        val modules = stdlibIndex.origins.values.toSortedSet() +
            stdlibIndex.qualifiedTypeOrigins.values.filterNotNull()
        if (!selecting) {
            // A child module one segment below what has been typed.
            for (module in modules) {
                val rest = when {
                    parent == null -> module
                    module.startsWith("$parent.") -> module.removePrefix("$parent.")
                    else -> continue
                }
                val segment = rest.substringBefore('.')
                if (segment.isNotEmpty() && out.none { it.label == segment && it.kind == "module" }) {
                    out.add(Completion(segment, "module", module, segment))
                }
            }
        }
        // A symbol the named module declares - what a `::` selection takes.
        if (parent == null) return
        for ((name, origin) in stdlibIndex.origins) {
            if (origin != parent) continue
            val detail = stdlibIndex.functions[name]?.let { signatureOf(it) } ?: name
            out.add(Completion(name, "function", "$detail - $origin", name))
        }
    }

    /**
     * The compiler-parsed import clauses of one document.
     *
     * This deliberately retains every selector. Collapsing
     * `import std.traits::{Order, Hash}` to just `std.traits` makes unrelated
     * declarations in that module appear imported to highlighting, hover and
     * navigation even though compiler resolution correctly rejects them.
     */
    private data class ImportVisibility(val specs: List<ImportSpec>) {
        fun exposes(origin: String, symbol: String): Boolean = specs.any { spec ->
            when (spec.selector) {
                is ImportSpec.Selector.All -> {
                    val moduleSelected = origin == spec.path || origin.startsWith("${spec.path}.")
                    val shortName = symbol.substringAfterLast("__")
                    moduleSelected && symbol !in spec.without && shortName !in spec.without
                }
                is ImportSpec.Selector.Path -> when {
                    // A plain import of an actual module exposes that module's
                    // declarations, exactly as compiler import injection does.
                    spec.path == origin -> true

                    // A `module::name` selector is stored by the parser as the
                    // fully joined dotted path. Scoped declaration names use
                    // `__` internally, so preserve every remaining segment.
                    spec.path.startsWith("$origin.") ->
                        spec.path.removePrefix("$origin.").replace(".", "__") == symbol

                    else -> false
                }
                is ImportSpec.Selector.Group -> false // importsOf flattens groups to leaves.
            }
        }
    }

    /** Use the language parser as the sole authority for import syntax. */
    private fun importsOf(source: String): ImportVisibility {
        val specs = parseTolerant(source)?.items
            ?.filterIsInstance<TopLevel.UseImport>()
            ?.filterNot { it.exported }
            ?.flatMap { it.importSpecs }
            .orEmpty()
        return ImportVisibility(specs)
    }

    private fun originVisible(
        index: SymbolIndex,
        origin: String?,
        symbol: String,
        imports: ImportVisibility,
    ): Boolean = origin == null || origin in index.implicitModules || imports.exposes(origin, symbol)

    /** Import-aware visibility for a symbol already discovered in an index. */
    private fun symbolVisible(
        index: SymbolIndex,
        name: String,
        role: ReferenceRole,
        imports: ImportVisibility,
    ): Boolean {
        val origin = index.originOf(name, role) ?: return true
        return originVisible(index, origin, name, imports)
    }

    // ----- text helpers -----

    /** The identifier fragment immediately before [offset]: (startOffset, text). */
    private fun wordBefore(source: String, offset: Int): Pair<Int, String> {
        var start = offset
        while (start > 0 && source[start - 1].isIdentPart()) start--
        return start to source.substring(start, offset)
    }

    private data class MemberReceiver(val text: String, val scopeQualified: Boolean)

    /** Receiver before a member (`value.`) or scope path (``) completion. */
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
                ?.let { MemberReceiver(it, scopeQualified = true) }
        }
        if (wordStart == 0 || source[wordStart - 1] != '.') return null
        val end = wordStart - 1
        // `..` is a range, not a member access.
        if (end > 0 && source[end - 1] == '.') return null
        var start = end
        while (start > 0 && source[start - 1].isIdentPart()) start--
        return source.substring(start, end).takeIf { it.isNotEmpty() }
            ?.let { MemberReceiver(it, scopeQualified = false) }
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
            for (groupIndex in listOf(1, 2, 3)) {
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

    /** Canonical compiler key for `scope::name` at [offset] (`scope__name`). */
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
            """(?m)^\s*(?:(?:exposed|protected|confined|inline|deepinline|noinline|unsafe|react|async|bridge|lazy)\s+)*(?:func(?:\s*<[^>{}\n]*>)?\s+(?:\(([^)]*)\)\s*\.\s*[A-Za-z_][A-Za-z0-9_]*|(?:[A-Za-z_][A-Za-z0-9_]*(?:<[^>{}\n]*>)?[&!]?|[&!])\s*\.\s*[A-Za-z_][A-Za-z0-9_]*|[A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)|(?:ctor|dtor)\s*\.?\s*\(([^)]*)\))""",
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
    val slots = linkedMapOf<String, TopLevel.Slot>()
    val specs = linkedMapOf<String, TopLevel.Spec>()
    val aliases = linkedMapOf<String, TopLevel.TypeAlias>()
    val decorators = linkedMapOf<String, TopLevel.Deco>()
    val macros = linkedMapOf<String, TopLevel.Meta>()
    val properties = linkedSetOf<String>()
    val propertyOrigins = linkedMapOf<String, String?>()
    val enumCases = linkedSetOf<String>()
    val enumCaseOrigins = linkedMapOf<String, String?>()
    val errorCases = linkedSetOf<String>()
    val errorCaseOrigins = linkedMapOf<String, String?>()

    /** Modules declared `exposed module`, whose declarations need no import. */
    val implicitModules = linkedSetOf<String>()

    /** Canonical scope-qualified type names (`std__Int`, `shapes__Point`). */
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
        if (module != null && program.isExported && program.moduleVisibility == ModuleVisibility.PUBLIC) {
            implicitModules += module
        }
        fun origin(name: String) {
            if (module != null) origins.putIfAbsent(name, module)
        }
        fun document(name: String, line: Int) {
            val doc = sourceText?.let { documentationAbove(it, line) }.orEmpty()
            if (doc.isNotEmpty()) documentation.putIfAbsent(name, doc)
        }
        fun property(decl: FuncDecl) {
            if (decl.memberCallStyle != org.azora.lang.frontend.MemberCallStyle.PROPERTY &&
                decl.memberCallStyle != org.azora.lang.frontend.MemberCallStyle.STATIC_PROPERTY
            ) return
            properties += decl.name
            propertyOrigins.putIfAbsent(decl.name, module)
        }
        fun enumCase(name: String) {
            enumCases += name
            enumCaseOrigins.putIfAbsent(name, module)
        }
        fun errorCase(name: String) {
            errorCases += name
            errorCaseOrigins.putIfAbsent(name, module)
        }
        fun typeOrigin(name: String): String {
            val namespace = program.scopeTypeNamespaces[name]
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
                    item.variants.forEach(::enumCase)
                }
                is TopLevel.Slot -> {
                    slots[item.name] = item
                    origin(item.name)
                    val canonical = typeOrigin(item.name)
                    document(item.name, item.line)
                    documentation[item.name]?.let { documentation.putIfAbsent(canonical, it) }
                    if (item.isError) item.variants.map { it.name }.forEach(::errorCase)
                    else item.variants.map { it.name }.forEach(::enumCase)
                }
                is TopLevel.Spec -> {
                    specs[item.name] = item
                    origin(item.name)
                    val canonical = typeOrigin(item.name)
                    document(item.name, item.line)
                    documentation[item.name]?.let { documentation.putIfAbsent(canonical, it) }
                    item.methods.forEach(::property)
                }
                is TopLevel.TypeAlias -> {
                    aliases[item.name] = item
                    origin(item.name)
                    val canonical = typeOrigin(item.name)
                    document(item.name, item.line)
                    documentation[item.name]?.let { documentation.putIfAbsent(canonical, it) }
                }
                is TopLevel.Deco -> {
                    decorators[item.name] = item
                    origin(item.name)
                    document(item.name, item.line)
                }
                is TopLevel.Meta -> {
                    macros[item.name] = item
                    origin(item.name)
                    document(item.name, item.line)
                }
                is TopLevel.Solo -> {
                    item.methods.forEach {
                        functions[it.name] = it
                        origin(it.name)
                        property(it)
                        document(it.name, it.line)
                    }
                }
                is TopLevel.Impl -> item.methods.forEach(::property)
                is TopLevel.Fail -> item.variants.forEach(::errorCase)
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

    /** True when [name] is declared inside a named scope and therefore needs `::`. */
    fun isScopeType(name: String): Boolean = qualifiedTypes.any { it != name && it.endsWith("__$name") }

    private fun typeNameForKey(name: String): String? = when {
        name in packs || name in enums || name in slots || name in specs || name in aliases -> name
        name in qualifiedTypes -> name.substringAfterLast("__")
        else -> null
    }

    private fun hasType(name: String): Boolean = typeNameForKey(name)?.let {
        it in packs || it in enums || it in slots || it in specs || it in aliases
    } == true

    private fun typeDeclarationLine(name: String): Int? = typeNameForKey(name)?.let { key ->
        packs[key]?.line ?: enums[key]?.line ?: slots[key]?.line ?: specs[key]?.line ?: aliases[key]?.line
    }

    /** Whether [name] has a declaration matching the use-site [role]. */
    fun hasSymbol(name: String, role: ReferenceRole = ReferenceRole.ANY): Boolean = when (role) {
        ReferenceRole.CALLABLE -> name in functions || hasType(name)
        ReferenceRole.TYPE -> hasType(name)
        ReferenceRole.VALUE -> name in topLevelVars || name in functions || hasType(name)
        ReferenceRole.ANY -> name in functions || hasType(name) || name in topLevelVars ||
            name in decorators || name in macros
    }

    /** The declaring module for [name] in the requested reference namespace. */
    fun originOf(name: String, role: ReferenceRole = ReferenceRole.ANY): String? = when (role) {
        ReferenceRole.TYPE -> qualifiedTypeOrigins[name] ?: origins[name]
        ReferenceRole.CALLABLE, ReferenceRole.VALUE -> origins[name] ?: qualifiedTypeOrigins[name]
        ReferenceRole.ANY -> origins[name] ?: qualifiedTypeOrigins[name]
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
