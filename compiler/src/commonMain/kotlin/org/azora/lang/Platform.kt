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

package org.azora.lang

/** Returns the name of the host operating system (e.g. "macOS", "Linux", "Windows", "iOS", "Android", "Web"). */
internal expect fun detectHostOS(): String

/**
 * Platform-portable mutual-exclusion primitive.
 *
 * On the JVM this is a real monitor lock (`kotlin.synchronized`) — required because the interpreter
 * runs coroutines on a multi-threaded dispatcher (`Dispatchers.Default`) and shared mutable state
 * (`output`, `launchedTasks`, singletons) must be guarded. On single-threaded targets (Wasm/JS,
 * native iOS) it is a no-op that just runs [block], which is correct since there is no true
 * parallelism to guard against.
 */
internal expect inline fun <R> azSync(lock: Any, block: () -> R): R

/**
 * Platform-portable `runBlocking`.
 *
 * On JVM and native this is the real `kotlinx.coroutines.runBlocking` — it blocks the calling thread
 * until [block] completes, which is how the synchronous [IrInterpreter.interpret] entry point bridges
 * the suspend-based evaluator. On Wasm/JS there is no way to block the single-threaded event loop, so
 * `runBlocking` does not exist there; this actual is a stub that throws (it is never called — Wasm/JS
 * callers use the suspend [IrInterpreter.interpretSuspend] entry point instead).
 */
internal expect fun <T> azRunBlocking(
    context: kotlin.coroutines.CoroutineContext,
    block: suspend kotlinx.coroutines.CoroutineScope.() -> T,
): T
