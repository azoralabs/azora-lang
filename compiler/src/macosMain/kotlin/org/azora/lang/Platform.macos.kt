@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.azora.lang

import kotlinx.cinterop.*
import platform.posix.*

internal actual fun detectHostOS(): String = "macOS"

// A recursive process-wide lock preserves the monitor contract used by the
// interpreter and stdlib cache, including nested calls on the same thread.
@PublishedApi
internal val nativeCompilerLock = nativeHeap.alloc<pthread_mutex_t>().also { mutex ->
    memScoped {
        val attributes = alloc<pthread_mutexattr_t>()
        check(pthread_mutexattr_init(attributes.ptr) == 0)
        check(pthread_mutexattr_settype(attributes.ptr, PTHREAD_MUTEX_RECURSIVE) == 0)
        check(pthread_mutex_init(mutex.ptr, attributes.ptr) == 0)
        pthread_mutexattr_destroy(attributes.ptr)
    }
}

internal actual inline fun <R> azSync(lock: Any, block: () -> R): R {
    check(pthread_mutex_lock(nativeCompilerLock.ptr) == 0)
    try { return block() } finally { pthread_mutex_unlock(nativeCompilerLock.ptr) }
}

internal actual fun <T> azRunBlocking(
    context: kotlin.coroutines.CoroutineContext,
    block: suspend kotlinx.coroutines.CoroutineScope.() -> T,
): T = kotlinx.coroutines.runBlocking(context, block)
