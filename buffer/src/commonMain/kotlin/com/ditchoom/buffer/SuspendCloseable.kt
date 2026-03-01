package com.ditchoom.buffer

interface SuspendCloseable {
    suspend fun close()
}

/**
 * Executes the given [block] on this buffer and then frees native memory in a finally block.
 *
 * This is the recommended pattern for one-off buffer usage:
 * ```kotlin
 * PlatformBuffer.allocate(1024, AllocationZone.Direct).use { buffer ->
 *     buffer.writeInt(42)
 * }
 * ```
 *
 * Calls [PlatformBuffer.freeNativeMemory] (non-suspend) rather than [SuspendCloseable.close],
 * so it can be used in any context without requiring a coroutine scope.
 */
inline fun <T : PlatformBuffer, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        freeNativeMemory()
    }
}
