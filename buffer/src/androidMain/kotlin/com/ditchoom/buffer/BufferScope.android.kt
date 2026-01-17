package com.ditchoom.buffer

/**
 * Android implementation of [withScope].
 *
 * Uses [UnsafeBufferScope] with sun.misc.Unsafe for memory management.
 */
actual inline fun <T> withScope(block: (BufferScope) -> T): T {
    val scope = UnsafeBufferScope()
    return try {
        block(scope)
    } finally {
        scope.close()
    }
}
