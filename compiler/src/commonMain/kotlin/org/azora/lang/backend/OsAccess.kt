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

/**
 * The operating-system services `std.os` is built on.
 *
 * These are `expect`s rather than C bridges because `azora run` executes
 * through [IrInterpreter], which has no FFI: a `bridge .C` declaration compiles
 * but cannot be called there. A standard-library API that only works after a
 * native build is not a standard-library API.
 *
 * Targets without an operating system underneath them (the browser) implement
 * these by refusing clearly. A program that asks the web for its process id has
 * made a mistake that a wrong answer would hide.
 */

/** The value of environment variable [name], or null when it is unset. */
internal expect fun osEnvVar(name: String): String?

/** Sets [name] to [value] for child processes; false when the platform refuses. */
internal expect fun osSetEnvVar(name: String, value: String): Boolean

/** The process's working directory. */
internal expect fun osCurrentDirectory(): String

/** Changes the working directory, reporting whether it moved. */
internal expect fun osChangeDirectory(path: String): Boolean

/** This process's identifier. */
internal expect fun osProcessId(): Int

/**
 * Runs [command] through the platform shell and waits for it.
 *
 * Returns the exit code and everything the command wrote. stderr is folded into
 * stdout: the two are interleaved in the order they were produced, which is the
 * order a reader needs them in, and splitting them loses that ordering for good.
 *
 * A command that could not be started is reported with [OsCommandResult.started]
 * false rather than as an empty successful run - "printed nothing" and "never
 * ran" are different answers.
 */
internal expect fun osRunCommand(command: String): OsCommandResult

/** What [osRunCommand] observed. */
internal data class OsCommandResult(
    val output: String,
    val exitCode: Int,
    val started: Boolean,
)
