package com.ditchoom.buffer

/**
 * JVM implementation of [withScope].
 *
 * On JVM < 21: Uses [UnsafeBufferScope] with sun.misc.Unsafe
 * On JVM 21+: Uses FfmBufferScope with FFM Arena (via multi-release JAR)
 */
actual inline fun <T> withScope(block: (BufferScope) -> T): T {
    val scope = createBufferScope()
    return try {
        block(scope)
    } finally {
        scope.close()
    }
}
