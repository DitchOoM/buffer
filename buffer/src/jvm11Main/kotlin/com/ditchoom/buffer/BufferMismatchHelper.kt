@file:Suppress("unused") // Loaded at runtime via multi-release JAR, not referenced directly

package com.ditchoom.buffer

import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Helper for buffer mismatch operations.
 *
 * This is the Java 11+ implementation that uses ByteBuffer.mismatch() directly.
 * Loaded via multi-release JAR (META-INF/versions/11/) on JVM 11+.
 *
 * ByteBuffer.mismatch() uses SIMD-optimized vectorized comparison on supported hardware.
 */
internal object BufferMismatchHelper {
    fun mismatch(
        buffer1: ByteBuffer,
        buffer2: ByteBuffer,
        minLength: Int,
        buffer1Remaining: Int,
        buffer2Remaining: Int,
    ): Int {
        val slice1 = buffer1.slice()
        (slice1 as Buffer).limit(minLength)
        val slice2 = buffer2.slice()
        (slice2 as Buffer).limit(minLength)
        val result = slice1.mismatch(slice2)
        return if (result == -1 && buffer1Remaining != buffer2Remaining) {
            minLength
        } else {
            result
        }
    }
}
