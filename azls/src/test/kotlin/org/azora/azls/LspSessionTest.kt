/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.azls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LspSessionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun initializeAdvertisesTheStandardProtocolAndNegotiatedEncoding() {
        LspSession({}).use { session ->
            val response = assertNotNull(
                session.handle(
                    request(
                        1,
                        "initialize",
                        buildJsonObject {
                            put("capabilities", buildJsonObject {
                                put("general", buildJsonObject {
                                    put("positionEncodings", buildJsonArray {
                                        add(JsonPrimitive("utf-8"))
                                        add(JsonPrimitive("utf-16"))
                                    })
                                })
                            })
                        },
                    ),
                ),
            )
            val capabilities = response.result().jsonObject["capabilities"]!!.jsonObject
            assertEquals("utf-8", capabilities["positionEncoding"]!!.jsonPrimitive.content)
            assertEquals(2, capabilities["textDocumentSync"]!!.jsonObject["change"]!!.jsonPrimitive.content.toInt())
            assertTrue(capabilities.containsKey("semanticTokensProvider"))
            assertTrue(capabilities.containsKey("codeActionProvider"))
            val roles = capabilities["semanticTokensProvider"]!!.jsonObject["legend"]!!
                .jsonObject["tokenTypes"]!!.jsonArray.map { it.jsonPrimitive.content }
            for (role in listOf(
                "generic", "contextParameter", "property", "enumMember", "errorMember",
                "label", "scope", "modulePath", "doc", "docTag", "docTagValue",
            )) {
                assertTrue(role in roles, "semantic token legend is missing '$role': $roles")
            }
        }
    }

    @Test
    fun everyMissingDerivedSpecHasItsOwnRangeAndImportAction() {
        val notifications = CopyOnWriteArrayList<JsonObject>()
        LspSession(notifications::add).use { session ->
            initialize(session)
            val uri = "file:///workspace/contracts.az"
            val source = "bridge pack Glyph derives (PartialEqual, Equal, Order, Hash)\n"
            session.handle(open(uri, 6, source))
            val diagnostics = awaitPublish(notifications, version = 6)
                .params().jsonObject["diagnostics"]!!.jsonArray
                .filter { item ->
                    item.jsonObject["message"]?.jsonPrimitive?.content.orEmpty()
                        .startsWith("undefined spec or decorator")
                }
            assertEquals(4, diagnostics.size, diagnostics.toString())

            val names = listOf("PartialEqual", "Equal", "Order", "Hash")
            for (name in names) {
                val diagnostic = diagnostics.single { item ->
                    "'$name'" in item.jsonObject["message"]!!.jsonPrimitive.content
                }.jsonObject
                val range = diagnostic["range"]!!.jsonObject
                val start = range["start"]!!.jsonObject["character"]!!.jsonPrimitive.content.toInt()
                val end = range["end"]!!.jsonObject["character"]!!.jsonPrimitive.content.toInt()
                assertEquals(name, source.substring(start, end), "$name range: $range")

                val actions = assertNotNull(session.handle(request(
                    40 + names.indexOf(name),
                    "textDocument/codeAction",
                    buildJsonObject {
                        put("textDocument", buildJsonObject { put("uri", uri) })
                        put("range", range)
                        put("context", buildJsonObject {
                            put("diagnostics", buildJsonArray { add(diagnostic) })
                        })
                    },
                ))).result().jsonArray
                assertTrue(
                    actions.any { it.jsonObject["title"]?.jsonPrimitive?.content == "Import '$name' from 'std.traits'" },
                    "$name actions: $actions",
                )
            }

            // The plugin requests actions for all diagnostics in one batch.
            // Each returned action must retain the exact occurrence it fixes;
            // otherwise every underline receives the alphabetically first
            // action (historically "Import Equal") regardless of its symbol.
            val batchedActions = assertNotNull(session.handle(request(
                90,
                "textDocument/codeAction",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("range", diagnostics.first().jsonObject["range"]!!)
                    put("context", buildJsonObject {
                        put("diagnostics", buildJsonArray { diagnostics.forEach(::add) })
                    })
                },
            ))).result().jsonArray
            assertEquals(4, batchedActions.size, batchedActions.toString())
            for (actionElement in batchedActions) {
                val action = actionElement.jsonObject
                val title = action["title"]!!.jsonPrimitive.content
                val expectedName = names.single { name -> "'$name'" in title }
                val attached = action["diagnostics"]!!.jsonArray.single().jsonObject
                val attachedRange = attached["range"]!!.jsonObject
                val start = attachedRange["start"]!!.jsonObject["character"]!!.jsonPrimitive.content.toInt()
                val end = attachedRange["end"]!!.jsonObject["character"]!!.jsonPrimitive.content.toInt()
                assertEquals(expectedName, source.substring(start, end), title)
                val replacement = action["edit"]!!.jsonObject["documentChanges"]!!.jsonArray
                    .single().jsonObject["edits"]!!.jsonArray.single().jsonObject["newText"]!!
                    .jsonPrimitive.content
                assertTrue("import std.traits::$expectedName" in replacement, "$title edit: $replacement")
            }
        }
    }

    @Test
    fun semanticTokensPreserveTheFullEditorRoleContract() {
        LspSession({}).use { session ->
            initialize(session)
            val uri = "file:///workspace/semantic.az"
            val source = """
                import std.traits::{PartialEqual, Equal, Order, Hash}
                @Supress(.Unused)
                @Since("0.1")
                enum PartialCompare {
                    Less
                    Equal
                    Greater
                    Unordered
                }
                func<T, U, S> (self: T&, other: U&).compare(value: S): PartialCompare {
                    fin left: T = self
                    fin right: U = other
                    return PartialCompare.Less
                }
                bridge pack Glyph derives (PartialEqual, Equal, Order, Hash)
            """.trimIndent()
            session.handle(open(uri, 1, source))
            val response = assertNotNull(session.handle(request(
                70,
                "textDocument/semanticTokens/full",
                buildJsonObject { put("textDocument", buildJsonObject { put("uri", uri) }) },
            )))
            val data = response.result().jsonObject["data"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
            val roleNames = listOf(
                "keyword", "string", "number", "comment", "function", "type", "parameter", "variable",
                "decorator", "macro", "associatedType", "generic", "contextParameter", "property", "enumMember",
                "errorMember", "label", "scope", "modulePath", "doc", "docTag", "docTagValue", "interpolation",
                "field", "typeDeclaration", "specType", "functionDeclaration", "specFunction", "overrideFunction",
                "propertyDeclaration", "specProperty", "overrideProperty", "wildcard", "macroHole", "smartCast",
                "deprecated",
            )
            val lines = source.lines()
            val rolesByText = linkedMapOf<String, MutableList<String>>()
            var line = 0
            var character = 0
            for (index in data.indices step 5) {
                val deltaLine = data[index]
                line += deltaLine
                character = if (deltaLine == 0) character + data[index + 1] else data[index + 1]
                val text = lines[line].substring(character, character + data[index + 2])
                rolesByText.getOrPut(text) { mutableListOf() } += roleNames[data[index + 3]]
            }

            for (decorator in listOf("@Supress", "@Since")) {
                assertEquals(listOf("decorator"), rolesByText[decorator].orEmpty(), rolesByText.toString())
            }
            assertTrue(
                rolesByText["Less"].orEmpty().let { it.isNotEmpty() && it.all { role -> role == "enumMember" } },
                rolesByText.toString(),
            )
            assertTrue(
                rolesByText["Greater"].orEmpty().let { it.isNotEmpty() && it.all { role -> role == "enumMember" } },
                rolesByText.toString(),
            )
            for (generic in listOf("T", "U", "S")) {
                assertTrue(
                    rolesByText[generic].orEmpty().let { it.isNotEmpty() && it.all { role -> role == "generic" } },
                    "$generic: $rolesByText",
                )
            }
            for (receiver in listOf("self", "other")) {
                assertTrue(
                    rolesByText[receiver].orEmpty()
                        .let { it.isNotEmpty() && it.all { role -> role == "contextParameter" } },
                    "$receiver: $rolesByText",
                )
            }
            assertTrue(
                rolesByText["value"].orEmpty().let { it.isNotEmpty() && it.all { role -> role == "parameter" } },
                rolesByText.toString(),
            )
            for (spec in listOf("PartialEqual", "Order", "Hash")) {
                assertTrue(
                    rolesByText[spec].orEmpty().let { it.isNotEmpty() && it.all { role -> role == "specType" } },
                    "$spec: $rolesByText",
                )
            }
        }
    }

    @Test
    fun didOpenPublishesVersionedStructuredDiagnosticsWithExactSymbolRange() {
        val notifications = CopyOnWriteArrayList<JsonObject>()
        LspSession(notifications::add).use { session ->
            initialize(session)
            val source = "func main() {\n    missing()\n}\n"
            session.handle(open("file:///workspace/main.az", 7, source))
            val published = awaitPublish(notifications, version = 7)
            val diagnostic = published.params().jsonObject["diagnostics"]!!.jsonArray.single().jsonObject
            assertEquals("AZ-SYM-0001", diagnostic["code"]!!.jsonPrimitive.content)
            assertEquals("azora", diagnostic["source"]!!.jsonPrimitive.content)
            assertEquals(7, diagnostic["data"]!!.jsonObject["documentVersion"]!!.jsonPrimitive.content.toInt())
            val range = diagnostic["range"]!!.jsonObject
            assertEquals(1, range["start"]!!.jsonObject["line"]!!.jsonPrimitive.content.toInt())
            assertEquals(4, range["start"]!!.jsonObject["character"]!!.jsonPrimitive.content.toInt())
            assertEquals(11, range["end"]!!.jsonObject["character"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun staleDocumentVersionsAreRejectedAndTheNewestSnapshotWins() {
        val notifications = CopyOnWriteArrayList<JsonObject>()
        LspSession(notifications::add).use { session ->
            initialize(session)
            val uri = "file:///workspace/versioned.az"
            session.handle(open(uri, 1, "func main() { missing() }\n"))
            session.handle(change(uri, 2, "func main() {}\n"))
            val stale = assertFailsWith<RpcError> { session.handle(change(uri, 2, "func main() {}\n")) }
            assertEquals(-32602, stale.rpcCode)
            val newest = awaitPublish(notifications, version = 2)
            assertEquals(JsonArray(emptyList()), newest.params().jsonObject["diagnostics"])
            Thread.sleep(200)
            val lastVersion = notifications
                .filter { it["method"]?.jsonPrimitive?.content == "textDocument/publishDiagnostics" }
                .last().params().jsonObject["version"]?.jsonPrimitive?.content?.toInt()
            assertEquals(2, lastVersion)
        }
    }

    @Test
    fun codeActionsCarryVersionCheckedWorkspaceEdits() {
        val notifications = CopyOnWriteArrayList<JsonObject>()
        LspSession(notifications::add).use { session ->
            initialize(session)
            val uri = "file:///workspace/fix.az"
            session.handle(open(uri, 4, "func main() {\n    fin x = 1\n"))
            val published = awaitPublish(notifications, version = 4)
            val diagnostic = published.params().jsonObject["diagnostics"]!!.jsonArray.first().jsonObject
            val response = assertNotNull(
                session.handle(
                    request(
                        9,
                        "textDocument/codeAction",
                        buildJsonObject {
                            put("textDocument", buildJsonObject { put("uri", uri) })
                            put("range", diagnostic["range"]!!)
                            put("context", buildJsonObject {
                                put("diagnostics", buildJsonArray { add(diagnostic) })
                            })
                        },
                    ),
                ),
            )
            val actions = response.result().jsonArray
            assertTrue(actions.isNotEmpty(), response.toString())
            val edit = actions.first().jsonObject["edit"]!!.jsonObject
            val documentChange = edit["documentChanges"]!!.jsonArray.single().jsonObject
            assertEquals(4, documentChange["textDocument"]!!.jsonObject["version"]!!.jsonPrimitive.content.toInt())
            assertEquals("}", documentChange["edits"]!!.jsonArray.single().jsonObject["newText"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun contextualVariantShorthandIsACompilerDiagnosticAndCodeAction() {
        val notifications = CopyOnWriteArrayList<JsonObject>()
        LspSession(notifications::add).use { session ->
            initialize(session)
            val uri = "file:///workspace/file-kind.az"
            val source = """
                enum FileKind {
                    File
                    Directory
                }
                func isFile(kind: FileKind): Bool {
                    return kind == FileKind.File
                }
            """.trimIndent() + "\n"
            session.handle(open(uri, 12, source))
            val diagnostic = awaitPublish(notifications, version = 12)
                .params().jsonObject["diagnostics"]!!.jsonArray
                .single { it.jsonObject["code"]?.jsonPrimitive?.content == "AZ-SEM-0002" }
                .jsonObject

            assertEquals(4, diagnostic["severity"]!!.jsonPrimitive.content.toInt())
            assertEquals(listOf(1), diagnostic["tags"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
            val range = diagnostic["range"]!!.jsonObject
            val line = range["start"]!!.jsonObject["line"]!!.jsonPrimitive.content.toInt()
            val start = range["start"]!!.jsonObject["character"]!!.jsonPrimitive.content.toInt()
            val end = range["end"]!!.jsonObject["character"]!!.jsonPrimitive.content.toInt()
            assertEquals("FileKind.", source.lines()[line].substring(start, end))

            val actions = assertNotNull(session.handle(request(
                91,
                "textDocument/codeAction",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("range", range)
                    put("context", buildJsonObject {
                        put("diagnostics", buildJsonArray { add(diagnostic) })
                    })
                },
            ))).result().jsonArray
            val action = actions.single().jsonObject
            assertEquals("Use inferred variant '.File'", action["title"]!!.jsonPrimitive.content)
            val edit = action["edit"]!!.jsonObject["documentChanges"]!!.jsonArray
                .single().jsonObject["edits"]!!.jsonArray.single().jsonObject
            assertEquals(".", edit["newText"]!!.jsonPrimitive.content)
            assertEquals(12, action["data"]!!.jsonObject["documentVersion"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test
    fun publishingANewerVersionExpiresRetainedFixes() {
        val notifications = CopyOnWriteArrayList<JsonObject>()
        LspSession(notifications::add).use { session ->
            initialize(session)
            val uri = "file:///workspace/expired-fix.az"
            session.handle(open(uri, 4, "func main() {\n    fin x = 1\n"))
            val oldDiagnostic = awaitPublish(notifications, version = 4)
                .params().jsonObject["diagnostics"]!!.jsonArray.first().jsonObject

            session.handle(change(uri, 5, "func main() {}\n"))
            awaitPublish(notifications, version = 5)

            val response = assertNotNull(
                session.handle(
                    request(
                        10,
                        "textDocument/codeAction",
                        buildJsonObject {
                            put("textDocument", buildJsonObject { put("uri", uri) })
                            put("range", oldDiagnostic["range"]!!)
                            put("context", buildJsonObject {
                                put("diagnostics", buildJsonArray { add(oldDiagnostic) })
                            })
                        },
                    ),
                ),
            )
            assertTrue(response.result().jsonArray.isEmpty(), response.toString())
        }
    }

    @Test
    fun workspaceCompletionAndUndefinedNameFixUseTheSameAutoImportEdit() {
        val root = Files.createTempDirectory("azls-auto-import-")
        val library = root.resolve("app/lib.az")
        Files.createDirectories(library.parent)
        Files.writeString(library, "module app.lib\n\nfunc render(): Int { return 1 }\n")
        val notifications = CopyOnWriteArrayList<JsonObject>()
        LspSession(notifications::add).use { session ->
            initialize(session, root.toUri().toString())
            val uri = root.resolve("main.az").toUri().toString()
            val source = "func main() {\n    render()\n}\n"
            session.handle(open(uri, 3, source))
            val published = awaitPublish(notifications, version = 3)
            val diagnostic = published.params().jsonObject["diagnostics"]!!.jsonArray
                .single { it.jsonObject["code"]?.jsonPrimitive?.content == "AZ-SYM-0001" }.jsonObject

            val actions = assertNotNull(session.handle(request(
                20,
                "textDocument/codeAction",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("range", diagnostic["range"]!!)
                    put("context", buildJsonObject { put("diagnostics", buildJsonArray { add(diagnostic) }) })
                },
            ))).result().jsonArray
            assertTrue(actions.any { action ->
                action.jsonObject["title"]?.jsonPrimitive?.content == "Import 'render' from 'app.lib'" &&
                    action.jsonObject["edit"]?.jsonObject?.toString()?.contains("import app.lib::render") == true
            }, actions.toString())

            val completion = assertNotNull(session.handle(request(
                21,
                "textDocument/completion",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("position", buildJsonObject { put("line", 1); put("character", 7) })
                },
            ))).result().jsonObject["items"]!!.jsonArray
                .single { it.jsonObject["label"]?.jsonPrimitive?.content == "render" }.jsonObject
            assertTrue(
                completion["additionalTextEdits"]!!.jsonArray.single().jsonObject["newText"]!!
                    .jsonPrimitive.content.contains("import app.lib::render"),
                completion.toString(),
            )
        }
    }

    @Test
    fun unusedWarningCarriesACompilerOwnedRenameFix() {
        val notifications = CopyOnWriteArrayList<JsonObject>()
        LspSession(notifications::add).use { session ->
            initialize(session)
            val uri = "file:///workspace/unused.az"
            session.handle(open(uri, 8, "func main() {\n    fin forgotten = 1\n}\n"))
            val published = awaitPublish(notifications, version = 8)
            val diagnostic = published.params().jsonObject["diagnostics"]!!.jsonArray
                .single { it.jsonObject["code"]?.jsonPrimitive?.content == "AZ-SEM-0001" }.jsonObject
            assertEquals(2, diagnostic["severity"]!!.jsonPrimitive.content.toInt())
            assertEquals(JsonPrimitive(1), diagnostic["tags"]!!.jsonArray.single())

            val actions = assertNotNull(session.handle(request(
                22,
                "textDocument/codeAction",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("range", diagnostic["range"]!!)
                    put("context", buildJsonObject { put("diagnostics", buildJsonArray { add(diagnostic) }) })
                },
            ))).result().jsonArray
            assertTrue(actions.any { action ->
                val value = action.jsonObject
                val replacement = value["edit"]?.jsonObject?.get("documentChanges")?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("edits")?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("newText")?.jsonPrimitive?.content
                value["title"]?.jsonPrimitive?.content == "Rename unused 'forgotten' to '_'" && replacement == "_"
            }, actions.toString())
        }
    }

    @Test
    fun packageManifestScopesAnalysisAndPrunesGeneratedAndNestedProjects() {
        val root = Files.createTempDirectory("azls-package-scope-")
        Files.writeString(root.resolve("package.azon"), "package: {\n    name: \"std\"\n    version: \"0.1\"\n}\n")
        val target = root.resolve("std/main.az")
        val library = root.resolve("std/container/array.az")
        Files.createDirectories(library.parent)
        Files.writeString(target, "module std.main\nfunc main() {}\n")
        Files.writeString(library, "module std.container.array\nfunc arrayValue(): Int { return 1 }\n")

        val nested = root.resolve("examples/demo/src/App.az")
        Files.createDirectories(nested.parent)
        Files.writeString(root.resolve("examples/demo/azora.azon"), """{"project":{"name":"demo_invalid","src":"src"}}""")
        Files.writeString(nested, "package demo_invalid\nfunc (((")
        val generated = root.resolve("azls/build/wasm/AzoraLanguageServer.az")
        Files.createDirectories(generated.parent)
        Files.writeString(generated, "module wrong.generated\nfunc<Broken> old() {}\n")
        val standalone = root.resolve("experimental_self_hosted/frontend/token.az")
        Files.createDirectories(standalone.parent)
        Files.writeString(standalone, "module azora.lang.frontend.token\nfunc token() {}\n")

        val notifications = CopyOnWriteArrayList<JsonObject>()
        LspSession(notifications::add).use { session ->
            initialize(session, root.toUri().toString())
            session.handle(open(target.toUri().toString(), 1, Files.readString(target)))
            val diagnostics = awaitPublish(notifications, version = 1).params().jsonObject["diagnostics"]!!.jsonArray
            assertTrue(
                diagnostics.none { item ->
                    val message = item.jsonObject["message"]?.jsonPrimitive?.content.orEmpty()
                    "demo_invalid" in message || "AzoraLanguageServer.az" in message ||
                        "does not match module" in message || "Failed to parse library source" in message
                },
                diagnostics.toString(),
            )
        }
    }

    @Test
    fun definitionPrefersTheLocalStdPackageDeclaredByPackageAzon() {
        val root = Files.createTempDirectory("azls-local-std-")
        Files.writeString(root.resolve("package.azon"), "package: { name: \"std\" version: \"0.1\" }\n")
        val math = root.resolve("std/math.az")
        val use = root.resolve("std/use.az")
        Files.createDirectories(math.parent)
        Files.writeString(math, "module std.math\nfunc localAbsolute(value: Int): Int { return value }\n")
        val source = "module std.use\nimport std.math::localAbsolute\nfunc main() { localAbsolute(1) }\n"
        Files.writeString(use, source)

        LspSession({}).use { session ->
            initialize(session, root.toUri().toString())
            session.handle(open(use.toUri().toString(), 1, source))
            val response = assertNotNull(session.handle(request(
                23,
                "textDocument/definition",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", use.toUri().toString()) })
                    put("position", buildJsonObject { put("line", 2); put("character", 16) })
                },
            )))
            assertEquals(math.toUri().toString(), response.result().jsonObject["uri"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun workspaceSymbolsAreDiscoverableButNotResolvableUntilImported() {
        val root = Files.createTempDirectory("azls-import-visibility-")
        Files.writeString(root.resolve("package.azon"), "package: { name: \"std\" version: \"0.1\" }\n")
        val traits = root.resolve("std/traits/traits.az")
        val char = root.resolve("std/char.az")
        Files.createDirectories(traits.parent)
        Files.writeString(
            traits,
            """
                module std.traits
                bridge spec PartialEqual {}
                bridge spec Equal requires PartialEqual {}
                bridge spec Order requires Equal {}
                bridge spec Hash requires Equal {}
            """.trimIndent() + "\n",
        )
        val withoutImport = """
            @Supress(.Unused)
            exposed module std.char
            @Since("0.1")
            bridge pack Char derives (PartialEqual, Equal, Order, Hash)
        """.trimIndent() + "\n"
        Files.writeString(char, withoutImport)

        val notifications = CopyOnWriteArrayList<JsonObject>()
        LspSession(notifications::add).use { session ->
            fun semanticRoleAt(requestId: Int, uri: String, source: String, offset: Int): Int? {
                val data = assertNotNull(session.handle(request(
                    requestId,
                    "textDocument/semanticTokens/full",
                    buildJsonObject { put("textDocument", buildJsonObject { put("uri", uri) }) },
                ))).result().jsonObject["data"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
                val wantedLine = source.take(offset).count { it == '\n' }
                val wantedCharacter = offset - source.lastIndexOf('\n', offset - 1) - 1
                var line = 0
                var character = 0
                for (index in data.indices step 5) {
                    val deltaLine = data[index]
                    line += deltaLine
                    character = if (deltaLine == 0) character + data[index + 1] else data[index + 1]
                    if (line == wantedLine && wantedCharacter in character until character + data[index + 2]) {
                        return data[index + 3]
                    }
                }
                return null
            }

            initialize(session, root.toUri().toString())
            val uri = char.toUri().toString()
            session.handle(open(uri, 1, withoutImport))
            val missing = awaitPublish(notifications, version = 1).params().jsonObject["diagnostics"]!!.jsonArray
                .filter { it.jsonObject["code"]?.jsonPrimitive?.content == "AZ-SYM-0001" }
            assertEquals(4, missing.size, missing.toString())

            val partialEqual = withoutImport.indexOf("PartialEqual")
            val hiddenDefinition = assertNotNull(session.handle(request(
                24,
                "textDocument/definition",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("position", buildJsonObject {
                        put("line", withoutImport.take(partialEqual).count { it == '\n' })
                        put("character", partialEqual - withoutImport.lastIndexOf('\n', partialEqual - 1) - 1)
                    })
                },
            ))).result()
            assertEquals(JsonNull, hiddenDefinition)
            val hiddenHover = assertNotNull(session.handle(request(
                26,
                "textDocument/hover",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("position", buildJsonObject {
                        put("line", withoutImport.take(partialEqual).count { it == '\n' })
                        put("character", partialEqual - withoutImport.lastIndexOf('\n', partialEqual - 1) - 1)
                    })
                },
            ))).result()
            assertEquals(JsonNull, hiddenHover)
            assertTrue(semanticRoleAt(27, uri, withoutImport, partialEqual) != 25)

            val partiallyImported = withoutImport.replace(
                "exposed module std.char\n",
                "exposed module std.char\nimport std.traits::{Order, Hash, Equal}\n",
            )
            session.handle(change(uri, 2, partiallyImported))
            val partialDiagnostics = awaitPublish(notifications, version = 2)
                .params().jsonObject["diagnostics"]!!.jsonArray
                .filter { it.jsonObject["code"]?.jsonPrimitive?.content == "AZ-SYM-0001" }
            assertEquals(1, partialDiagnostics.size, partialDiagnostics.toString())
            assertTrue(
                partialDiagnostics.single().jsonObject["message"]!!.jsonPrimitive.content.contains("PartialEqual"),
                partialDiagnostics.toString(),
            )
            val omittedPartialEqual = partiallyImported.lastIndexOf("PartialEqual")
            val omittedDefinition = assertNotNull(session.handle(request(
                30,
                "textDocument/definition",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("position", buildJsonObject {
                        put("line", partiallyImported.take(omittedPartialEqual).count { it == '\n' })
                        put(
                            "character",
                            omittedPartialEqual - partiallyImported.lastIndexOf('\n', omittedPartialEqual - 1) - 1,
                        )
                    })
                },
            ))).result()
            assertEquals(JsonNull, omittedDefinition)
            val omittedHover = assertNotNull(session.handle(request(
                31,
                "textDocument/hover",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("position", buildJsonObject {
                        put("line", partiallyImported.take(omittedPartialEqual).count { it == '\n' })
                        put(
                            "character",
                            omittedPartialEqual - partiallyImported.lastIndexOf('\n', omittedPartialEqual - 1) - 1,
                        )
                    })
                },
            ))).result()
            assertEquals(JsonNull, omittedHover)
            assertTrue(semanticRoleAt(32, uri, partiallyImported, omittedPartialEqual) != 25)

            val importedEqual = partiallyImported.lastIndexOf("Equal")
            assertEquals(25, semanticRoleAt(33, uri, partiallyImported, importedEqual))

            val imported = withoutImport.replace(
                "exposed module std.char\n",
                "exposed module std.char\nimport std.traits::{PartialEqual, Equal, Order, Hash}\n",
            )
            session.handle(change(uri, 3, imported))
            val importedDiagnostics = awaitPublish(notifications, version = 3)
                .params().jsonObject["diagnostics"]!!.jsonArray
            assertTrue(
                importedDiagnostics.none { it.jsonObject["code"]?.jsonPrimitive?.content == "AZ-SYM-0001" },
                importedDiagnostics.toString(),
            )
            val importedPartialEqual = imported.lastIndexOf("PartialEqual")
            val visibleDefinition = assertNotNull(session.handle(request(
                25,
                "textDocument/definition",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("position", buildJsonObject {
                        put("line", imported.take(importedPartialEqual).count { it == '\n' })
                        put("character", importedPartialEqual - imported.lastIndexOf('\n', importedPartialEqual - 1) - 1)
                    })
                },
            ))).result().jsonObject
            assertEquals(traits.toUri().toString(), visibleDefinition["uri"]!!.jsonPrimitive.content)
            val visibleHover = assertNotNull(session.handle(request(
                28,
                "textDocument/hover",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", uri) })
                    put("position", buildJsonObject {
                        put("line", imported.take(importedPartialEqual).count { it == '\n' })
                        put("character", importedPartialEqual - imported.lastIndexOf('\n', importedPartialEqual - 1) - 1)
                    })
                },
            ))).result()
            assertTrue(visibleHover !is JsonNull)
            assertEquals(25, semanticRoleAt(29, uri, imported, importedPartialEqual))
        }
    }

    @Test
    fun stdioUsesContentLengthJsonRpcAndHonorsShutdownExit() {
        val input = listOf(
            request(1, "initialize", buildJsonObject {}),
            request(2, "shutdown", buildJsonObject {}),
            notification("exit", buildJsonObject {}),
        ).joinToString(separator = "") { frame(it.toString()) }
        val output = ByteArrayOutputStream()

        AzlsStdio.serve(ByteArrayInputStream(input.toByteArray()), output)

        val messages = readFrames(output.toByteArray())
        assertEquals(listOf("1", "2"), messages.map { it["id"]!!.jsonPrimitive.content })
        assertTrue(messages.all { it["jsonrpc"]!!.jsonPrimitive.content == "2.0" })
        assertTrue(messages[0].result().jsonObject.containsKey("capabilities"))
        assertTrue(messages[1].containsKey("result"))
    }

    private fun initialize(session: LspSession, rootUri: String? = null) {
        session.handle(request(1, "initialize", buildJsonObject {
            rootUri?.let { put("rootUri", it) }
        }))
        session.handle(notification("initialized", buildJsonObject {}))
    }

    private fun request(id: Int, method: String, params: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }

    private fun open(uri: String, version: Int, text: String): JsonObject = notification(
        "textDocument/didOpen",
        buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", uri)
                put("languageId", "azora")
                put("version", version)
                put("text", text)
            })
        },
    )

    private fun change(uri: String, version: Int, text: String): JsonObject = notification(
        "textDocument/didChange",
        buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri); put("version", version) })
            put("contentChanges", buildJsonArray { add(buildJsonObject { put("text", text) }) })
        },
    )

    private fun awaitPublish(notifications: List<JsonObject>, version: Int): JsonObject {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            notifications.lastOrNull {
                it["method"]?.jsonPrimitive?.content == "textDocument/publishDiagnostics" &&
                    it.params().jsonObject["version"]?.jsonPrimitive?.content?.toIntOrNull() == version
            }?.let { return it }
            Thread.sleep(10)
        }
        error("timed out waiting for publishDiagnostics version $version; got $notifications")
    }

    private fun JsonObject.result(): JsonElement = this["result"] ?: error("response has no result: $this")
    private fun JsonObject.params(): JsonElement = this["params"] ?: error("message has no params: $this")

    private fun frame(body: String): String {
        val size = body.toByteArray(Charsets.UTF_8).size
        return "Content-Length: $size\r\n\r\n$body"
    }

    private fun readFrames(bytes: ByteArray): List<JsonObject> {
        val text = bytes.toString(Charsets.UTF_8)
        val messages = mutableListOf<JsonObject>()
        var offset = 0
        while (offset < text.length) {
            val headerEnd = text.indexOf("\r\n\r\n", offset)
            assertTrue(headerEnd >= 0, "missing header terminator in ${text.substring(offset)}")
            val header = text.substring(offset, headerEnd)
            val length = header.lineSequence().single { it.startsWith("Content-Length:", true) }
                .substringAfter(':').trim().toInt()
            val bodyStart = headerEnd + 4
            val bodyBytes = text.substring(bodyStart).toByteArray(Charsets.UTF_8)
            val body = bodyBytes.copyOfRange(0, length).toString(Charsets.UTF_8)
            messages += json.parseToJsonElement(body).jsonObject
            offset = bodyStart + body.length
        }
        return messages
    }
}
