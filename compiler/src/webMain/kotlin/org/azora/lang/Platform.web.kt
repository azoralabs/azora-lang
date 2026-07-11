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

/** Web/WASM implementation: always returns `"Web"`. */
internal actual fun detectHostOS(): String = "Web"

/** Web/WASM implementation: no-op — Wasm/JS is single-threaded, so no locking is needed. */
internal actual inline fun <R> azSync(lock: Any, block: () -> R): R = block()

/**
 * Web/WASM implementation: there is no way to block the single-threaded Wasm/JS event loop, so this
 * is a stub. It is never invoked — the playground bridge calls the suspend `IrInterpreter.interpretSuspend`
 * entry point instead.
 */
@Suppress("UNUSED_PARAMETER")
internal actual fun <T> azRunBlocking(
    context: kotlin.coroutines.CoroutineContext,
    block: suspend kotlinx.coroutines.CoroutineScope.() -> T,
): T = error("runBlocking is unavailable on Wasm/JS; use IrInterpreter.interpretSuspend()")
