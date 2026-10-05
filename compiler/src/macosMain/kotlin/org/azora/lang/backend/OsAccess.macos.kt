@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.azora.lang.backend

import kotlinx.cinterop.*
import platform.posix.*

internal actual fun osEnvVar(name: String): String? = getenv(name)?.toKString()
internal actual fun osSetEnvVar(name: String, value: String): Boolean = setenv(name, value, 1) == 0
internal actual fun osCurrentDirectory(): String {
    val path = getcwd(null, 0u) ?: error("cannot read working directory")
    return try { path.toKString() } finally { free(path) }
}
internal actual fun osChangeDirectory(path: String): Boolean = chdir(path) == 0
internal actual fun osProcessId(): Int = getpid()
internal actual fun osRunCommand(command: String): OsCommandResult {
    val stream = popen("$command 2>&1", "r") ?: return OsCommandResult("", -1, false)
    val output = memScoped {
        val buffer = allocArray<ByteVar>(4096)
        buildString { while (fgets(buffer, 4096, stream) != null) append(buffer.toKString()) }
    }
    val status = pclose(stream)
    return OsCommandResult(output, if (status < 0) -1 else if ((status and 127) == 0) status shr 8 else 128 + (status and 127), true)
}
