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
 * the browser has no operating system to ask.
 *
 * A page has no environment, no working directory and no shell. Reads answer "unset" and "." - the same answers a real system gives for
 * something absent - while anything that would *act* on the system refuses, so
 * a program cannot believe it started a process that never ran.
 */
internal actual fun osEnvVar(name: String): String? = null

internal actual fun osSetEnvVar(name: String, value: String): Boolean = false

internal actual fun osCurrentDirectory(): String = "."

internal actual fun osChangeDirectory(path: String): Boolean = false

internal actual fun osProcessId(): Int = 0

internal actual fun osRunCommand(command: String): OsCommandResult =
    OsCommandResult("std.os cannot run commands on this target", -1, false)
