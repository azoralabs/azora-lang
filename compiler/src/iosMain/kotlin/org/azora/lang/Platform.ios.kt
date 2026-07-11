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

/** iOS implementation: always returns `"iOS"`. */
internal actual fun detectHostOS(): String = "iOS"

/** iOS implementation: no-op — the interpreter's coroutine dispatcher is cooperative here. */
internal actual inline fun <R> azSync(lock: Any, block: () -> R): R = block()

/** iOS implementation: native provides `runBlocking`, so the synchronous interpreter entry works. */
internal actual fun <T> azRunBlocking(
    context: kotlin.coroutines.CoroutineContext,
    block: suspend kotlinx.coroutines.CoroutineScope.() -> T,
): T = kotlinx.coroutines.runBlocking(context, block)
