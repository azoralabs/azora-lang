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

package org.azora.lang.backend

import java.io.File

/** Set by [osSetEnvVar]; the JVM cannot mutate its own environment. */
private val overlaidEnvironment = mutableMapOf<String, String>()

/** Tracks [osChangeDirectory]; `user.dir` is advisory on the JVM, so this is the truth. */
private var workingDirectory: String = System.getProperty("user.dir") ?: "."

internal actual fun osEnvVar(name: String): String? =
    overlaidEnvironment[name] ?: System.getenv(name)

/**
 * Records [name] for processes this one starts.
 *
 * The JVM cannot change its own environment, so rather than pretend, the value
 * is kept here and applied to every command [osRunCommand] launches. A variable
 * set by a program is therefore visible to what that program runs - which is
 * what setting it is almost always for - and `osEnvVar` reads it back.
 */
internal actual fun osSetEnvVar(name: String, value: String): Boolean {
    overlaidEnvironment[name] = value
    return true
}

internal actual fun osCurrentDirectory(): String = workingDirectory

internal actual fun osChangeDirectory(path: String): Boolean {
    val target = File(path).let { if (it.isAbsolute) it else File(workingDirectory, path) }
    if (!target.isDirectory) return false
    workingDirectory = target.canonicalPath
    return true
}

internal actual fun osProcessId(): Int = ProcessHandle.current().pid().toInt()

internal actual fun osRunCommand(command: String): OsCommandResult {
    val shell = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
        listOf("cmd.exe", "/c", command)
    } else {
        listOf("/bin/sh", "-c", command)
    }
    return try {
        val process = ProcessBuilder(shell)
            .directory(File(workingDirectory))
            .redirectErrorStream(true)
            .also { it.environment().putAll(overlaidEnvironment) }
            .start()
        // Read before waiting: a command that fills the pipe blocks until it is
        // drained, so waiting first would deadlock on exactly the verbose output
        // the caller wanted.
        val output = process.inputStream.bufferedReader().readText()
        OsCommandResult(output, process.waitFor(), true)
    } catch (error: Exception) {
        OsCommandResult(error.message ?: "could not start the command", -1, false)
    }
}
