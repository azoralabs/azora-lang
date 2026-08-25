/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.azls

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.azora.lang.Compiler
import org.azora.lang.diagnostics.AnalysisMode
import org.azora.lang.diagnostics.AnalysisRequest
import org.azora.lang.diagnostics.AzoraDiagnostic
import org.azora.lang.diagnostics.CancellationToken
import org.azora.lang.diagnostics.DiagnosticFix
import org.azora.lang.diagnostics.DiagnosticRenderer
import org.azora.lang.diagnostics.DiagnosticSeverity
import org.azora.lang.diagnostics.DocumentVersion
import org.azora.lang.diagnostics.FixApplicability
import org.azora.lang.diagnostics.FixId
import org.azora.lang.diagnostics.ImportEditPlanner
import org.azora.lang.diagnostics.ImmutableSourceManager
import org.azora.lang.diagnostics.PositionEncoding
import org.azora.lang.diagnostics.SourceId
import org.azora.lang.diagnostics.SourceEdit
import org.azora.lang.diagnostics.SourceKind
import org.azora.lang.diagnostics.SourcePosition
import org.azora.lang.diagnostics.SourceSpan
import org.azora.lang.diagnostics.SourceUnit
import org.azora.lang.diagnostics.StringLineIndex
import org.azora.lang.diagnostics.TextOffset
import org.azora.lang.diagnostics.UndefinedSymbol
import org.azora.lang.stdlib.AzStdlib
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class RpcError(val rpcCode: Int, override val message: String, val data: JsonElement? = null) : RuntimeException(message)

/** One versioned document overlay owned by the editor. */
internal data class DocumentSnapshot(val uri: String, val version: Long, val text: String) {
    val lineIndex = StringLineIndex(text)
    val contentHash: Int = text.hashCode()
}

/**
 * Standard JSON-RPC/LSP session. It owns document overlays, analysis scheduling,
 * retained fixes and lifecycle state; no compiler class crosses this boundary.
 */
internal class LspSession(private val notify: (JsonObject) -> Unit) : AutoCloseable {
    private val documents = ConcurrentHashMap<String, DocumentSnapshot>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "azls-analysis").apply { isDaemon = true }
    }
    private val compilerLock = Any()
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()
    private val fixes = ConcurrentHashMap<String, RetainedFix>()
    private val publishedUris = ConcurrentHashMap.newKeySet<String>()
    private val workspaceRoots = mutableListOf<Path>()
    private val workspaceCacheLock = Any()
    @Volatile private var diskWorkspaceCache = DiskWorkspaceCache(emptyList(), null, 0L)
    private val legacy = AzoraLanguageServer()
    private var initialized = false
    private var shutdown = false
    @Volatile var exitRequested: Boolean = false
        private set
    private var encoding = PositionEncoding.UTF16

    fun handle(message: JsonObject): JsonObject? {
        val id = message["id"]
        val method = message["method"]?.jsonPrimitive?.contentOrNull
            ?: throw RpcError(-32600, "request has no method")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (message["jsonrpc"]?.jsonPrimitive?.contentOrNull != "2.0") {
            throw RpcError(-32600, "jsonrpc must be '2.0'")
        }
        if (!initialized && method !in setOf("initialize", "exit", "$/cancelRequest")) {
            throw RpcError(-32002, "server is not initialized")
        }
        if (shutdown && method !in setOf("exit", "$/cancelRequest")) {
            throw RpcError(-32600, "server has shut down")
        }
        val result = when (method) {
            "initialize" -> initialize(params)
            "initialized" -> { initialized = true; JsonNull }
            "shutdown" -> { shutdown = true; cancelAll(); JsonNull }
            "exit" -> { exitRequested = true; close(); JsonNull }
            "$/cancelRequest" -> { cancelRequest(params); JsonNull }
            "workspace/didChangeConfiguration" -> { reanalyzeAll(immediate = true); JsonNull }
            "workspace/didChangeWorkspaceFolders" -> { changeWorkspaceFolders(params); JsonNull }
            "textDocument/didOpen" -> { didOpen(params); JsonNull }
            "textDocument/didChange" -> { didChange(params); JsonNull }
            "textDocument/didClose" -> { didClose(params); JsonNull }
            "textDocument/didSave" -> {
                invalidateWorkspaceCache()
                documentUri(params)?.let { schedule(it, immediate = true) }
                JsonNull
            }
            "textDocument/completion" -> completion(params)
            "textDocument/hover" -> hover(params)
            "textDocument/definition" -> definition(params)
            "textDocument/documentSymbol" -> documentSymbols(params)
            "textDocument/semanticTokens/full" -> semanticTokens(params)
            "textDocument/codeAction" -> codeActions(params)
            "codeAction/resolve" -> resolveCodeAction(params)
            else -> throw RpcError(-32601, "method '$method' is not supported")
        }
        return id?.let { response(it, result) }
    }

    private fun initialize(params: JsonObject): JsonObject {
        if (initialized) throw RpcError(-32600, "initialize may be sent only once")
        encoding = negotiateEncoding(params)
        workspaceRoots.clear()
        params["workspaceFolders"]?.jsonArray?.forEach { item ->
            uriToPath(item.jsonObject["uri"]?.jsonPrimitive?.contentOrNull)?.let(workspaceRoots::add)
        }
        uriToPath(params["rootUri"]?.jsonPrimitive?.contentOrNull)?.let {
            if (it !in workspaceRoots) workspaceRoots.add(it)
        }
        initialized = true
        return buildJsonObject {
            put("capabilities", buildJsonObject {
                put("positionEncoding", encoding.lspName)
                put("textDocumentSync", buildJsonObject {
                    put("openClose", true)
                    put("change", 2)
                    put("save", buildJsonObject { put("includeText", false) })
                })
                put("completionProvider", buildJsonObject {
                    put("resolveProvider", false)
                    put("triggerCharacters", buildJsonArray { add(JsonPrimitive(".")); add(JsonPrimitive(":")) })
                })
                put("hoverProvider", true)
                put("definitionProvider", true)
                put("documentSymbolProvider", true)
                put("codeActionProvider", buildJsonObject {
                    put("resolveProvider", true)
                    put("codeActionKinds", buildJsonArray {
                        add(JsonPrimitive("quickfix"))
                        add(JsonPrimitive("refactor.rewrite"))
                        add(JsonPrimitive("source.fixAll.azora"))
                    })
                })
                put("semanticTokensProvider", buildJsonObject {
                    put("legend", buildJsonObject {
                        put("tokenTypes", JsonArray(SEMANTIC_TOKEN_TYPES.map(::JsonPrimitive)))
                        put("tokenModifiers", JsonArray(emptyList()))
                    })
                    put("full", true)
                })
                put("workspace", buildJsonObject {
                    put("workspaceFolders", buildJsonObject {
                        put("supported", true)
                        put("changeNotifications", true)
                    })
                })
                put("experimental", buildJsonObject {
                    put("diagnosticSchemaVersion", 1)
                    put("compilerVersion", AzStdlib.tree().version)
                    put("stdlibVersion", runCatching { AzStdlib.tree().version }.getOrDefault("unavailable"))
                    put("languageEdition", AzStdlib.tree().version)
                })
            })
            put("serverInfo", buildJsonObject {
                put("name", "AZLS")
                put("version", legacy.version())
            })
        }
    }

    private fun didOpen(params: JsonObject) {
        val doc = params["textDocument"]?.jsonObject ?: throw RpcError(-32602, "missing textDocument")
        val uri = doc.requiredText("uri")
        val version = doc["version"]?.jsonPrimitive?.longOrNull ?: 0L
        documents[uri] = DocumentSnapshot(uri, version, doc.requiredText("text"))
        schedule(uri, immediate = true)
    }

    private fun didChange(params: JsonObject) {
        val id = params["textDocument"]?.jsonObject ?: throw RpcError(-32602, "missing textDocument")
        val uri = id.requiredText("uri")
        val version = id["version"]?.jsonPrimitive?.longOrNull ?: throw RpcError(-32602, "missing document version")
        val previous = documents[uri] ?: throw RpcError(-32602, "document '$uri' is not open")
        if (version <= previous.version) throw RpcError(-32602, "document versions must increase")
        var text = previous.text
        for (change in params["contentChanges"]?.jsonArray.orEmpty()) {
            val value = change.jsonObject
            val replacement = value.requiredText("text")
            val range = value["range"] as? JsonObject
            text = if (range == null) replacement else applyRangeEdit(text, range, replacement)
        }
        documents[uri] = DocumentSnapshot(uri, version, text)
        schedule(uri, immediate = false)
    }

    private fun didClose(params: JsonObject) {
        val uri = documentUri(params) ?: throw RpcError(-32602, "missing document uri")
        pending.remove(uri)?.cancel(true)
        cancellations.remove(uri)?.set(true)
        documents.remove(uri)
        publish(uri, null, emptyList())
    }

    private fun applyRangeEdit(text: String, range: JsonObject, replacement: String): String {
        val index = StringLineIndex(text)
        val start = index.offset(range.requiredPosition("start"), encoding)?.value
            ?: throw RpcError(-32602, "invalid change start")
        val end = index.offset(range.requiredPosition("end"), encoding)?.value
            ?: throw RpcError(-32602, "invalid change end")
        if (end < start) throw RpcError(-32602, "change range is reversed")
        return text.replaceRange(start, end, replacement)
    }

    private fun schedule(uri: String, immediate: Boolean) {
        pending.remove(uri)?.cancel(true)
        cancellations.remove(uri)?.set(true)
        val cancellation = AtomicBoolean(false)
        cancellations[uri] = cancellation
        pending[uri] = scheduler.schedule(
            { analyzeAndPublish(uri, cancellation) },
            if (immediate) 0 else 150,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun analyzeAndPublish(uri: String, cancellation: AtomicBoolean) {
        val target = documents[uri] ?: return
        val units = analysisSources(target)
        val result = runCatching {
            synchronized(compilerLock) {
                Compiler().analyze(
                    AnalysisRequest(
                        sources = units,
                        roots = setOf(SourceId(uri)),
                        mode = AnalysisMode.IDE,
                        cancellation = CancellationToken { cancellation.get() || Thread.currentThread().isInterrupted },
                    ),
                )
            }
        }.getOrElse { error ->
            if (cancellation.get() || Thread.currentThread().isInterrupted) return
            log("analysis failed for $uri: ${error.stackTraceToString()}")
            return
        }
        val current = documents[uri]
        if (cancellation.get() || current?.version != target.version) return
        val sourceManager = ImmutableSourceManager(units)
        val enriched = result.diagnostics.map { enrichWorkspaceFixes(it, sourceManager, units) }
        val grouped = enriched.groupBy { it.primary.span.source.value }
        val affected = (publishedUris + grouped.keys + uri).toSet()
        for (diagnosticUri in affected) {
            val version = documents[diagnosticUri]?.version
            val entries = grouped[diagnosticUri].orEmpty()
            publish(diagnosticUri, version, entries, sourceManager)
        }
    }

    private fun publish(
        uri: String,
        version: Long?,
        diagnostics: List<AzoraDiagnostic>,
        sources: ImmutableSourceManager? = null,
    ) {
        // Fixes are snapshot values. Publishing a new result, including an empty
        // one, expires every action retained for the previous version of this URI.
        fixes.entries.removeAll { it.value.uri == uri }
        if (diagnostics.isEmpty()) publishedUris.remove(uri) else publishedUris += uri
        notify(notification("textDocument/publishDiagnostics", buildJsonObject {
            put("uri", uri)
            version?.let { put("version", it) }
            put("diagnostics", buildJsonArray {
                diagnostics.forEach { add(lspDiagnostic(it, version, sources ?: return@forEach)) }
            })
        }))
    }

    private fun lspDiagnostic(
        diagnostic: AzoraDiagnostic,
        version: Long?,
        sources: ImmutableSourceManager,
    ): JsonObject {
        val span = sources.resolveToUserSource(diagnostic.primary.span)
        val range = sources.range(span, encoding) ?: org.azora.lang.diagnostics.SourceRange(
            SourcePosition(0, 0), SourcePosition(0, 0),
        )
        val uri = sources[span.source]?.uri ?: span.source.value
        val occurrence = occurrenceId(diagnostic, version)
        diagnostic.fixes.forEach { fix ->
            fixes["$occurrence:${fix.id.value}"] = RetainedFix(uri, occurrence, version, fix)
        }
        return buildJsonObject {
            put("range", range.toJson())
            put("severity", diagnostic.severity.lspValue)
            put("code", diagnostic.code.value)
            put("codeDescription", buildJsonObject {
                put("href", "https://azora.dev/diagnostics/${diagnostic.code.value.lowercase()}")
            })
            put("source", "azora")
            put("message", DiagnosticRenderer.summary(diagnostic))
            if (diagnostic.tags.isNotEmpty()) put("tags", buildJsonArray {
                if (diagnostic.tags.any { it.name == "UNNECESSARY" }) add(JsonPrimitive(1))
                if (diagnostic.tags.any { it.name == "DEPRECATED" }) add(JsonPrimitive(2))
            })
            if (diagnostic.related.isNotEmpty()) put("relatedInformation", buildJsonArray {
                diagnostic.related.forEach { related ->
                    val relatedSource = sources[related.span.source] ?: return@forEach
                    val relatedRange = sources.range(related.span, encoding) ?: return@forEach
                    add(buildJsonObject {
                        put("location", buildJsonObject {
                            put("uri", relatedSource.uri)
                            put("range", relatedRange.toJson())
                        })
                        put("message", related.message)
                    })
                }
            })
            put("data", buildJsonObject {
                put("occurrence", occurrence)
                put("stage", diagnostic.stage.name.lowercase())
                version?.let { put("documentVersion", it) }
                put("fixes", JsonArray(diagnostic.fixes.map { JsonPrimitive(it.id.value) }))
            })
        }
    }

    private fun completion(params: JsonObject): JsonElement {
        val (document, offset) = documentAndOffset(params)
        val raw = legacy.complete(document.text, offset, workspacePrelude(document.uri))
        val parsed = AZLS_JSON.parseToJsonElement(raw).jsonArray
        return buildJsonObject {
            put("isIncomplete", false)
            put("items", buildJsonArray {
                parsed.forEach { item ->
                    val value = item.jsonObject
                    add(buildJsonObject {
                        put("label", value["label"] ?: JsonPrimitive(""))
                        put("kind", completionKind(value["kind"]?.jsonPrimitive?.contentOrNull))
                        put("detail", value["detail"] ?: JsonPrimitive(""))
                        put("insertText", value["insert"] ?: value["label"] ?: JsonPrimitive(""))
                        val module = value["importModule"]?.jsonPrimitive?.contentOrNull
                        val label = value["label"]?.jsonPrimitive?.contentOrNull
                        if (module != null && label != null) {
                            ImportEditPlanner.plan(document.text, module, label)?.let { edit ->
                                put("additionalTextEdits", buildJsonArray {
                                    add(buildJsonObject {
                                        put("range", buildJsonObject {
                                            put("start", document.lineIndex.position(TextOffset(edit.start), encoding).toJson())
                                            put("end", document.lineIndex.position(TextOffset(edit.endExclusive), encoding).toJson())
                                        })
                                        put("newText", edit.replacement)
                                    })
                                })
                                put("sortText", "1-${module}-${label}")
                            }
                        } else {
                            put("sortText", "0-${label.orEmpty()}")
                        }
                    })
                }
            })
        }
    }

    private fun hover(params: JsonObject): JsonElement {
        val (document, offset) = documentAndOffset(params)
        val raw = legacy.hover(document.text, offset, workspacePrelude(document.uri))
        if (raw == "null") return JsonNull
        val value = AZLS_JSON.parseToJsonElement(raw).jsonObject
        val markdown = buildString {
            append("```azora\n")
            append(value["signature"]?.jsonPrimitive?.contentOrNull.orEmpty())
            append("\n```")
            value["detail"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { append("\n\n$it") }
            value["doc"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { append("\n\n$it") }
        }
        return buildJsonObject {
            put("contents", buildJsonObject { put("kind", "markdown"); put("value", markdown) })
        }
    }

    private fun definition(params: JsonObject): JsonElement {
        val (document, offset) = documentAndOffset(params)
        val raw = legacy.definition(document.text, offset, workspacePrelude(document.uri))
        if (raw == "null") return JsonNull
        val value = AZLS_JSON.parseToJsonElement(raw).jsonObject
        if (value["inCurrentFile"]?.jsonPrimitive?.booleanOrNull != true) {
            val name = value["name"]?.jsonPrimitive?.contentOrNull ?: return JsonNull
            val module = value["module"]?.jsonPrimitive?.contentOrNull
            return externalDefinition(document, name, module) ?: JsonNull
        }
        val line = ((value["line"]?.jsonPrimitive?.intOrNull ?: 1) - 1).coerceAtLeast(0)
        val column = ((value["column"]?.jsonPrimitive?.intOrNull ?: 1) - 1).coerceAtLeast(0)
        return buildJsonObject {
            put("uri", document.uri)
            put("range", zeroRange(line, column))
        }
    }

    private fun documentSymbols(params: JsonObject): JsonElement {
        val uri = documentUri(params) ?: throw RpcError(-32602, "missing document uri")
        val document = documents[uri] ?: throw RpcError(-32602, "document is not open")
        val parsed = AZLS_JSON.parseToJsonElement(legacy.symbols(document.text)).jsonArray
        return buildJsonArray {
            parsed.forEach { item ->
                val value = item.jsonObject
                val line = ((value["line"]?.jsonPrimitive?.intOrNull ?: 1) - 1).coerceAtLeast(0)
                add(buildJsonObject {
                    put("name", value["name"] ?: JsonPrimitive(""))
                    put("detail", value["detail"] ?: JsonPrimitive(""))
                    put("kind", symbolKind(value["kind"]?.jsonPrimitive?.contentOrNull))
                    put("range", zeroRange(line, 0))
                    put("selectionRange", zeroRange(line, 0))
                })
            }
        }
    }

    private fun semanticTokens(params: JsonObject): JsonElement {
        val uri = documentUri(params) ?: throw RpcError(-32602, "missing document uri")
        val document = documents[uri] ?: throw RpcError(-32602, "document is not open")
        val spans = AZLS_JSON.parseToJsonElement(legacy.highlight(document.text, workspacePrelude(document.uri))).jsonArray
            .mapNotNull { item ->
                val value = item.jsonObject
                val start = value["start"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val end = value["end"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val type = value["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val startPosition = document.lineIndex.position(TextOffset(start), encoding)
                val endPosition = document.lineIndex.position(TextOffset(end), encoding)
                if (startPosition.line != endPosition.line || endPosition.character <= startPosition.character) return@mapNotNull null
                SemanticToken(startPosition.line, startPosition.character, endPosition.character - startPosition.character, semanticType(type))
            }.sortedWith(compareBy({ it.line }, { it.start }))
        var priorLine = 0
        var priorStart = 0
        return buildJsonObject {
            put("data", buildJsonArray {
                spans.forEach { token ->
                    val deltaLine = token.line - priorLine
                    val deltaStart = if (deltaLine == 0) token.start - priorStart else token.start
                    add(JsonPrimitive(deltaLine)); add(JsonPrimitive(deltaStart)); add(JsonPrimitive(token.length))
                    add(JsonPrimitive(token.type)); add(JsonPrimitive(0))
                    priorLine = token.line
                    priorStart = token.start
                }
            })
        }
    }

    private fun codeActions(params: JsonObject): JsonElement {
        val uri = documentUri(params) ?: throw RpcError(-32602, "missing document uri")
        val version = documents[uri]?.version ?: return JsonArray(emptyList())
        val diagnosticsByOccurrence = params["context"]?.jsonObject?.get("diagnostics")?.jsonArray.orEmpty()
            .mapNotNull { item ->
                val diagnostic = item.jsonObject
                val occurrence = diagnostic["data"]?.jsonObject
                    ?.get("occurrence")?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                occurrence to diagnostic
            }
            .toMap()
        return buildJsonArray {
            fixes.values
                .filter { it.uri == uri && it.version == version && it.occurrence in diagnosticsByOccurrence }
                .sortedBy { it.fix.title }
                .forEach { retained ->
                    val key = "${retained.occurrence}:${retained.fix.id.value}"
                    add(buildJsonObject {
                        put("title", retained.fix.title)
                        put("kind", when (retained.fix.kind.name) {
                            "REFACTOR_REWRITE" -> "refactor.rewrite"
                            "SOURCE_FIX_ALL" -> "source.fixAll.azora"
                            else -> "quickfix"
                        })
                        put("isPreferred", retained.fix.preferred && retained.fix.applicability == FixApplicability.MACHINE_APPLICABLE)
                        // A quick fix belongs to one concrete diagnostic
                        // occurrence. Carry that association over LSP instead
                        // of making clients infer it from the title, code, or
                        // ordering of otherwise identical undefined-symbol
                        // diagnostics.
                        diagnosticsByOccurrence[retained.occurrence]?.let { diagnostic ->
                            put("diagnostics", buildJsonArray { add(diagnostic) })
                        }
                        put("data", buildJsonObject { put("fixKey", key); put("documentVersion", version) })
                        if (retained.fix.edits.isNotEmpty()) put("edit", workspaceEdit(retained.fix, version))
                    })
                }
        }
    }

    private fun resolveCodeAction(params: JsonObject): JsonElement {
        val data = params["data"]?.jsonObject ?: throw RpcError(-32602, "code action has no data")
        val key = data.requiredText("fixKey")
        val retained = fixes[key] ?: throw RpcError(-32801, "code action snapshot expired")
        val edit = workspaceEdit(retained.fix, retained.version)
        return JsonObject(params + ("edit" to edit))
    }

    private fun workspaceEdit(fix: DiagnosticFix, expectedVersion: Long?): JsonObject = buildJsonObject {
        put("documentChanges", buildJsonArray {
            fix.edits.groupBy { it.source.value }.forEach { (uri, edits) ->
                val current = documents[uri]
                if (expectedVersion != null && current?.version != expectedVersion) throw RpcError(-32801, "document changed")
                val source = current ?: throw RpcError(-32801, "document '$uri' is no longer open")
                add(buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri); put("version", source.version) })
                    put("edits", buildJsonArray {
                        edits.sortedByDescending { it.range.start.value }.forEach { edit ->
                            val requiredVersion = edit.requiredVersion?.value
                            if (requiredVersion != null && requiredVersion != source.version) {
                                throw RpcError(-32801, "fix was produced for another document version")
                            }
                            edit.expectedText?.let { expected ->
                                val actual = source.text.substring(edit.range.start.value, edit.range.endExclusive.value)
                                if (actual != expected) throw RpcError(-32801, "fix no longer matches source")
                            }
                            add(buildJsonObject {
                                put("range", buildJsonObject {
                                    put("start", source.lineIndex.position(edit.range.start, encoding).toJson())
                                    put("end", source.lineIndex.position(edit.range.endExclusive, encoding).toJson())
                                })
                                put("newText", edit.replacement)
                            })
                        }
                    })
                })
            }
        })
    }

    /**
     * Undefined-name ownership belongs to the compiler; choosing a provider is
     * workspace knowledge owned by AZLS.  Enrich the typed compiler diagnostic
     * here so Alt-Enter and completion use the same import planner.
     */
    private fun enrichWorkspaceFixes(
        diagnostic: AzoraDiagnostic,
        sourceManager: ImmutableSourceManager,
        units: List<SourceUnit>,
    ): AzoraDiagnostic {
        if (diagnostic !is UndefinedSymbol || diagnostic.fixes.isNotEmpty()) return diagnostic
        val span = sourceManager.resolveToUserSource(diagnostic.primary.span)
        val source = sourceManager[span.source] ?: return diagnostic
        val modules = legacy.importModules(source.text, diagnostic.symbol, workspacePrelude(source.uri, units))
        val importFixes = modules.mapNotNull { module ->
            val edit = ImportEditPlanner.plan(source.text, module, diagnostic.symbol) ?: return@mapNotNull null
            val range = SourceSpan(source.id, TextOffset(edit.start), TextOffset(edit.endExclusive))
            DiagnosticFix(
                id = FixId("import-$module-${diagnostic.symbol}"),
                title = "Import '${diagnostic.symbol}' from '$module'",
                applicability = FixApplicability.MACHINE_APPLICABLE,
                preferred = modules.size == 1,
                edits = listOf(
                    SourceEdit(
                        source = source.id,
                        range = range,
                        replacement = edit.replacement,
                        expectedText = source.text.substring(edit.start, edit.endExclusive),
                        requiredVersion = source.version,
                    ),
                ),
            )
        }
        return if (importFixes.isEmpty()) diagnostic else diagnostic.copy(
            providerModule = modules.singleOrNull(),
            fixes = importFixes,
        )
    }

    /** Prelude sections retain each file's module origin for visibility/import decisions. */
    private fun workspacePrelude(targetUri: String, units: List<SourceUnit>? = null): String {
        val sources = units ?: documents[targetUri]?.let(::indexSources).orEmpty()
        return sources.asSequence()
            .filter { it.uri != targetUri && it.kind != SourceKind.STANDARD_LIBRARY }
            .joinToString("\n") { source ->
                val module = MODULE_DECLARATION.find(source.text)?.groupValues?.getOrNull(1).orEmpty()
                "//@azora-module $module\n${source.text}\n"
            }
    }

    /** Resolves the external marker returned by the compatibility symbol index to an LSP location. */
    private fun externalDefinition(target: DocumentSnapshot, name: String, module: String?): JsonObject? {
        for (source in indexSources(target)) {
            if (source.uri == target.uri) continue
            if (module != null) {
                val sourceModule = MODULE_DECLARATION.find(source.text)?.groupValues?.getOrNull(1)
                if (sourceModule != module) continue
            }
            val symbols = runCatching {
                AZLS_JSON.parseToJsonElement(legacy.symbols(source.text)).jsonArray
            }.getOrNull() ?: continue
            val symbol = symbols.firstOrNull {
                it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == name
            }?.jsonObject ?: continue
            val line = ((symbol["line"]?.jsonPrimitive?.intOrNull ?: 1) - 1).coerceAtLeast(0)
            val index = StringLineIndex(source.text)
            val lineStart = index.offset(SourcePosition(line, 0), encoding)?.value ?: 0
            val lineEnd = source.text.indexOf('\n', lineStart).let { if (it < 0) source.text.length else it }
            val column = source.text.substring(lineStart, lineEnd).indexOf(name).coerceAtLeast(0)
            return buildJsonObject {
                put("uri", source.uri)
                put("range", buildJsonObject {
                    put("start", buildJsonObject { put("line", line); put("character", column) })
                    put("end", buildJsonObject { put("line", line); put("character", column + name.length) })
                })
            }
        }
        return null
    }

    private fun analysisSources(target: DocumentSnapshot): List<SourceUnit> =
        workspaceSources(target, forIndex = false)

    private fun indexSources(target: DocumentSnapshot): List<SourceUnit> =
        workspaceSources(target, forIndex = true)

    /**
     * Builds a source snapshot from the manifest-backed workspace catalog and
     * overlays every matching open document. Analysis is package-scoped;
     * completion/navigation additionally see the local `std` package.
     */
    private fun workspaceSources(target: DocumentSnapshot, forIndex: Boolean): List<SourceUnit> {
        val catalog = diskWorkspaceCatalog()
        val targetPath = uriToPath(target.uri)?.toAbsolutePath()?.normalize()
        val found = linkedMapOf<String, SourceUnit>()
        val diskSources = if (forIndex) catalog.indexSources(targetPath) else catalog.analysisSources(targetPath)
        diskSources.forEach { found[it.unit.uri] = it.unit }
        documents.values.sortedBy { it.uri }.forEach { document ->
            val path = uriToPath(document.uri)?.toAbsolutePath()?.normalize()
            val belongs = when {
                document.uri == target.uri -> true
                path == null -> false
                forIndex -> catalog.indexContains(targetPath, path)
                else -> catalog.analysisContains(targetPath, path)
            }
            if (!belongs) return@forEach
            found[document.uri] = SourceUnit(
                SourceId(document.uri), document.uri,
                diskSources.firstOrNull { it.unit.uri == document.uri }?.unit?.displayPath
                    ?: path?.toString() ?: document.uri,
                document.text, DocumentVersion(document.version), SourceKind.USER,
            )
        }
        if (target.uri !in found) {
            found[target.uri] = SourceUnit(
                SourceId(target.uri), target.uri, target.uri, target.text, DocumentVersion(target.version), SourceKind.USER,
            )
        }
        return found.values.toList()
    }

    private fun diskWorkspaceCatalog(): WorkspaceCatalog {
        val roots = workspaceRoots.toList()
        val now = System.nanoTime()
        diskWorkspaceCache.takeIf {
            it.roots == roots && now - it.createdAtNanos < WORKSPACE_CACHE_NANOS
        }?.catalog?.let { return it }
        return synchronized(workspaceCacheLock) {
            val current = diskWorkspaceCache
            if (current.roots == roots && now - current.createdAtNanos < WORKSPACE_CACHE_NANOS) {
                current.catalog?.let { return@synchronized it }
            }
            val catalog = WorkspaceCatalog.scan(roots, log = ::log)
            val refreshed = DiskWorkspaceCache(roots, catalog, System.nanoTime())
            diskWorkspaceCache = refreshed
            catalog
        }
    }

    private fun invalidateWorkspaceCache() {
        diskWorkspaceCache = DiskWorkspaceCache(emptyList(), null, 0L)
    }

    private fun documentAndOffset(params: JsonObject): Pair<DocumentSnapshot, Int> {
        val uri = documentUri(params) ?: throw RpcError(-32602, "missing document uri")
        val document = documents[uri] ?: throw RpcError(-32602, "document is not open")
        val position = params.requiredPosition("position")
        val offset = document.lineIndex.offset(position, encoding)?.value ?: throw RpcError(-32602, "invalid position")
        return document to offset
    }

    private fun changeWorkspaceFolders(params: JsonObject) {
        val event = params["event"]?.jsonObject ?: return
        event["removed"]?.jsonArray?.forEach { item ->
            uriToPath(item.jsonObject["uri"]?.jsonPrimitive?.contentOrNull)?.let(workspaceRoots::remove)
        }
        event["added"]?.jsonArray?.forEach { item ->
            uriToPath(item.jsonObject["uri"]?.jsonPrimitive?.contentOrNull)?.let {
                if (it !in workspaceRoots) workspaceRoots.add(it)
            }
        }
        invalidateWorkspaceCache()
        reanalyzeAll(immediate = true)
    }

    private fun cancelRequest(params: JsonObject) {
        val id = params["id"]?.toString() ?: return
        cancellations[id]?.set(true)
    }

    private fun reanalyzeAll(immediate: Boolean) = documents.keys.forEach { schedule(it, immediate) }

    private fun cancelAll() {
        cancellations.values.forEach { it.set(true) }
        pending.values.forEach { it.cancel(true) }
        pending.clear()
    }

    override fun close() {
        cancelAll()
        scheduler.shutdownNow()
    }

    private fun negotiateEncoding(params: JsonObject): PositionEncoding {
        val offered = params["capabilities"]?.jsonObject
            ?.get("general")?.jsonObject
            ?.get("positionEncodings")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        return when {
            "utf-8" in offered -> PositionEncoding.UTF8
            "utf-32" in offered -> PositionEncoding.UTF32
            else -> PositionEncoding.UTF16
        }
    }

    private fun occurrenceId(diagnostic: AzoraDiagnostic, version: Long?): String =
        "d-${version ?: -1}-${listOf(diagnostic.code.value, diagnostic.primary.span.source.value, diagnostic.primary.span.start.value, diagnostic.primary.span.endExclusive.value).hashCode().toUInt().toString(16)}"

    private fun uriToPath(uri: String?): Path? = uri?.let {
        runCatching { URI(it) }.getOrNull()?.takeIf { parsed -> parsed.scheme == "file" }?.let(Path::of)
    }

    private fun documentUri(params: JsonObject): String? =
        params["textDocument"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull

    private fun completionKind(kind: String?): Int = when (kind) {
        "function", "method" -> 3
        "field", "variable", "param" -> 6
        "pack", "enum", "error", "spec", "type" -> 7
        "enumMember" -> 20
        "annotation" -> 22
        "macro" -> 1
        "keyword" -> 14
        else -> 1
    }

    private fun symbolKind(kind: String?): Int = when (kind) {
        "function" -> 12
        "pack" -> 23
        "enum" -> 10
        "variable" -> 13
        else -> 13
    }

    private fun semanticType(type: String): Int = when (type) {
        "keyword" -> 0
        "string", "char" -> 1
        "number" -> 2
        "comment" -> 3
        "function", "method" -> 4
        "type" -> 5
        "parameter" -> 6
        "variable" -> 7
        "annotation" -> 8
        "macro" -> 9
        "associatedType" -> 10
        "generic" -> 11
        "contextParameter" -> 12
        "property" -> 13
        "enumMember" -> 14
        "errorMember" -> 15
        "label" -> 16
        "scope" -> 17
        "modulePath" -> 18
        "doc" -> 19
        "docTag" -> 20
        "docTagValue" -> 21
        "interpolation-punctuation" -> 22
        "field" -> 23
        "typeDeclaration" -> 24
        "specType" -> 25
        "functionDeclaration" -> 26
        "specFunction" -> 27
        "overrideFunction" -> 28
        "propertyDeclaration" -> 29
        "specProperty" -> 30
        "overrideProperty" -> 31
        "wildcard" -> 32
        "macroHole" -> 33
        "smartCast" -> 34
        "deprecated" -> 35
        else -> 7
    }

    private fun zeroRange(line: Int, column: Int): JsonObject = buildJsonObject {
        put("start", buildJsonObject { put("line", line); put("character", column) })
        put("end", buildJsonObject { put("line", line); put("character", column) })
    }

    private fun log(message: String) = System.err.println("azls: $message")

    private data class RetainedFix(val uri: String, val occurrence: String, val version: Long?, val fix: DiagnosticFix)
    private data class SemanticToken(val line: Int, val start: Int, val length: Int, val type: Int)
    private data class DiskWorkspaceCache(
        val roots: List<Path>,
        val catalog: WorkspaceCatalog?,
        val createdAtNanos: Long,
    )

    companion object {
        private val AZLS_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val SEMANTIC_TOKEN_TYPES = listOf(
            "keyword", "string", "number", "comment", "function", "type", "parameter", "variable", "decorator", "macro",
            "associatedType", "generic", "contextParameter", "property", "enumMember", "errorMember", "label", "scope",
            "modulePath", "doc", "docTag", "docTagValue", "interpolation", "field", "typeDeclaration", "specType",
            "functionDeclaration", "specFunction", "overrideFunction", "propertyDeclaration", "specProperty",
            "overrideProperty", "wildcard", "macroHole", "smartCast", "deprecated",
        )
        private val MODULE_DECLARATION = Regex("(?m)^[ \\t]*(?:(?:export|exposed)[ \\t]+)?module[ \\t]+([A-Za-z_][A-Za-z0-9_.]*)")
        private const val WORKSPACE_CACHE_NANOS = 2_000_000_000L
    }
}

private fun JsonObject.requiredText(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: throw RpcError(-32602, "missing '$name'")

private fun JsonObject.requiredPosition(name: String): SourcePosition {
    val position = this[name]?.jsonObject ?: throw RpcError(-32602, "missing '$name'")
    return SourcePosition(
        position["line"]?.jsonPrimitive?.intOrNull ?: throw RpcError(-32602, "missing '$name.line'"),
        position["character"]?.jsonPrimitive?.intOrNull ?: throw RpcError(-32602, "missing '$name.character'"),
    )
}

private val PositionEncoding.lspName: String get() = when (this) {
    PositionEncoding.UTF8 -> "utf-8"
    PositionEncoding.UTF16 -> "utf-16"
    PositionEncoding.UTF32 -> "utf-32"
}

private val DiagnosticSeverity.lspValue: Int get() = when (this) {
    DiagnosticSeverity.ERROR -> 1
    DiagnosticSeverity.WARNING -> 2
    DiagnosticSeverity.INFORMATION -> 3
    DiagnosticSeverity.HINT -> 4
}

private fun SourcePosition.toJson(): JsonObject = buildJsonObject {
    put("line", line)
    put("character", character)
}

private fun org.azora.lang.diagnostics.SourceRange.toJson(): JsonObject = buildJsonObject {
    put("start", start.toJson())
    put("end", end.toJson())
}

private fun response(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}

internal fun notification(method: String, params: JsonElement): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("method", method)
    put("params", params)
}
