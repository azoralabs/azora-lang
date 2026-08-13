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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream

/**
 * Drives [AzoraLanguageServer] over stdin/stdout.
 *
 * Azora Studio loads the server in-process and calls it reflectively. Anything
 * else - an editor, a CI check, the Azora IDE - has no JVM to load it into, so
 * without this the server is reachable only from one host. A process boundary
 * costs a pipe and makes the same language intelligence available to any
 * program that can start one.
 *
 * ## Wire format
 *
 * `Content-Length: <n>\r\n\r\n<n bytes of UTF-8 JSON>`, in both directions -
 * the LSP framing, because it is unambiguous about where a message ends and
 * every editor toolkit already implements it. Length-prefixing (rather than
 * newline-delimiting) matters here because the payloads carry source text,
 * which contains newlines.
 *
 * A request is `{"id": 1, "method": "diagnostics", "params": {…}}`; the reply is
 * `{"id": 1, "result": <whatever the method returned>}` or
 * `{"id": 1, "error": "…"}`. The methods and their JSON are the server's own -
 * this adds a transport, not a protocol.
 */
object AzlsStdio {

    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun main(args: Array<String>) {
        val server = AzoraLanguageServer()
        val input = DataInputStream(System.`in`.buffered())
        val output = System.out

        while (true) {
            val message = readMessage(input) ?: break
            val reply = try {
                val request = json.parseToJsonElement(message).jsonObject
                val id = request["id"]?.jsonPrimitive?.intOrNull ?: 0
                val method = request["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())
                """{"id":$id,"result":${dispatch(server, method, params)}}"""
            } catch (error: Exception) {
                // A malformed request must not take the server down: an editor
                // that sends nonsense should get an error back and keep its
                // session, exactly as it would from a bad method call.
                """{"id":0,"error":${quote(error.message ?: error.toString())}}"""
            }
            writeMessage(output, reply)
        }
    }

    /** Runs one request. Every method already returns JSON, so results pass through. */
    private fun dispatch(server: AzoraLanguageServer, method: String, params: JsonObject): String {
        fun text(name: String, fallback: String = ""): String =
            (params[name] as? JsonPrimitive)?.contentOrNull ?: fallback
        fun number(name: String): Int = (params[name] as? JsonPrimitive)?.intOrNull ?: 0

        return when (method) {
            "version" -> quote(server.version())
            "diagnostics" -> server.diagnostics(text("source"), text("prelude"))
            "highlight" -> server.highlight(text("source"))
            "symbols" -> server.symbols(text("source"))
            "complete" -> server.complete(text("source"), number("offset"), text("prelude"))
            "hover" -> server.hover(text("source"), number("offset"), text("prelude"))
            "definition" -> server.definition(text("source"), number("offset"), text("prelude"))
            else -> throw IllegalArgumentException("unknown method '$method'")
        }
    }

    /**
     * Reads one framed message, or null at end of stream.
     *
     * Only `Content-Length` is acted on; other headers are read and ignored so a
     * client that sends `Content-Type` is not rejected over a header nobody
     * needs.
     */
    private fun readMessage(input: DataInputStream): String? {
        var length = -1
        while (true) {
            val line = readHeaderLine(input) ?: return null
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0 && line.substring(0, separator).trim().equals("Content-Length", true)) {
                length = line.substring(separator + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (length < 0) return null
        val body = ByteArray(length)
        input.readFully(body)
        return body.toString(Charsets.UTF_8)
    }

    /** One CRLF-terminated header line, or null at end of stream. */
    private fun readHeaderLine(input: DataInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(byte.toChar())
        }
    }

    private fun writeMessage(output: java.io.PrintStream, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.print("Content-Length: ${bytes.size}\r\n\r\n")
        output.write(bytes)
        output.flush()
    }

    private fun quote(value: String): String {
        val escaped = StringBuilder(value.length + 2)
        escaped.append('"')
        for (c in value) {
            when (c) {
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                else -> if (c < ' ') escaped.append("\\u%04x".format(c.code)) else escaped.append(c)
            }
        }
        return escaped.append('"').toString()
    }
}
