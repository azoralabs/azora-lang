/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.azls

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/** Production AZLS entry point: standard JSON-RPC 2.0/LSP over stdio. */
object AzlsStdio {
    private const val MAX_MESSAGE_BYTES = 32 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isNotEmpty() && "--stdio" !in args) {
            System.err.println("usage: azls --stdio")
            return
        }
        serve(System.`in`, System.out)
    }

    internal fun serve(rawInput: InputStream, output: OutputStream) {
        val input = DataInputStream(rawInput.buffered())
        val writeLock = Any()
        fun send(message: JsonObject) {
            synchronized(writeLock) { writeMessage(output, message.toString()) }
        }
        LspSession(::send).use { session ->
            while (!session.exitRequested) {
                val body = try {
                    readMessage(input) ?: break
                } catch (error: RpcError) {
                    send(errorResponse(JsonNull, error.rpcCode, error.message, error.data))
                    continue
                }
                var id: JsonElement = JsonNull
                val request = try {
                    json.parseToJsonElement(body).jsonObject.also { id = it["id"] ?: JsonNull }
                } catch (error: SerializationException) {
                    send(errorResponse(JsonNull, -32700, "invalid JSON: ${error.message}"))
                    continue
                } catch (error: IllegalArgumentException) {
                    send(errorResponse(JsonNull, -32600, "JSON-RPC message must be an object"))
                    continue
                }
                try {
                    session.handle(request)?.let(::send)
                } catch (error: RpcError) {
                    if (request.containsKey("id")) send(errorResponse(id, error.rpcCode, error.message, error.data))
                } catch (error: Throwable) {
                    val crashId = "rpc-${error.hashCode().toUInt().toString(16)}"
                    System.err.println("azls: internal request failure $crashId\n${error.stackTraceToString()}")
                    if (request.containsKey("id")) {
                        send(errorResponse(id, -32603, "internal AZLS failure ($crashId)"))
                    }
                }
            }
        }
    }

    private fun readMessage(input: DataInputStream): String? {
        var length: Int? = null
        while (true) {
            val line = readHeaderLine(input) ?: return null
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) throw RpcError(-32600, "malformed LSP header")
            val name = line.substring(0, separator).trim()
            if (name.equals("Content-Length", ignoreCase = true)) {
                length = line.substring(separator + 1).trim().toIntOrNull()
                    ?: throw RpcError(-32600, "invalid Content-Length")
            }
        }
        val size = length ?: throw RpcError(-32600, "missing Content-Length")
        if (size !in 0..MAX_MESSAGE_BYTES) throw RpcError(-32600, "Content-Length exceeds AZLS limit")
        val body = ByteArray(size)
        input.readFully(body)
        return body.toString(Charsets.UTF_8)
    }

    private fun readHeaderLine(input: DataInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (line.isEmpty()) null else throw RpcError(-32600, "truncated LSP header")
            if (byte == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= 8_192) throw RpcError(-32600, "LSP header is too large")
            line.append(byte.toChar())
        }
    }

    private fun writeMessage(output: OutputStream, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String, data: JsonElement? = null): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            })
        }
}
