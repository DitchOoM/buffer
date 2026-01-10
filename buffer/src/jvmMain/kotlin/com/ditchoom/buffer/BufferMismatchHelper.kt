package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Helper for buffer mismatch operations on JVM.
 *
 * This delegates to the fallback implementation (Long comparisons).
 * On Java 11+, this class is replaced by the optimized version via multi-release JAR
 * (META-INF/versions/11/) which uses ByteBuffer.mismatch() with SIMD optimizations.
 */
internal actual object BufferMismatchHelper {
    actual fun mismatch(
        buffer1: ByteBuffer,
        buffer2: ByteBuffer,
        minLength: Int,
        buffer1Remaining: Int,
        buffer2Remaining: Int,
    ): Int = BufferMismatchImpl.mismatchFallback(buffer1, buffer2, minLength, buffer1Remaining, buffer2Remaining)
}
