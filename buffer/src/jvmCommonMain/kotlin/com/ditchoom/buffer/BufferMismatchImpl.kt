package com.ditchoom.buffer

import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Buffer mismatch implementations.
 *
 * Note: jvm11Main has its own inline implementation because it's a separate
 * compilation that can't access jvmCommonMain sources.
 */
internal object BufferMismatchImpl {
    /**
     * Optimized mismatch using ByteBuffer.mismatch() (Android API 34+).
     *
     * Called by Android after verifying Build.VERSION.SDK_INT >= 34.
     * Uses SIMD-optimized vectorized comparison on supported hardware.
     *
     * Note: Only use when both buffers are the same type (both heap or both direct).
     * Mixing buffer types can cause issues on some Android versions.
     */
    @Suppress("NewApi") // Android caller verifies API level
    fun mismatchOptimized(
        buffer1: ByteBuffer,
        buffer2: ByteBuffer,
        minLength: Int,
        buffer1Remaining: Int,
        buffer2Remaining: Int,
    ): Int {
        // Only use native mismatch when both buffers are the same type
        // Mixing heap and direct buffers can cause issues on some Android versions
        if (buffer1.isDirect != buffer2.isDirect) {
            return mismatchFallback(buffer1, buffer2, minLength, buffer1Remaining, buffer2Remaining)
        }

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

    /**
     * Fallback mismatch using Long comparisons (8 bytes at a time).
     *
     * Used when ByteBuffer.mismatch() is not available:
     * - JVM Java 8-10
     * - Android API < 34
     */
    fun mismatchFallback(
        buffer1: ByteBuffer,
        buffer2: ByteBuffer,
        minLength: Int,
        buffer1Remaining: Int,
        buffer2Remaining: Int,
    ): Int {
        val pos1 = (buffer1 as Buffer).position()
        val pos2 = (buffer2 as Buffer).position()
        var i = 0

        // Compare 8 bytes at a time using Long reads
        while (i + 8 <= minLength) {
            if (buffer1.getLong(pos1 + i) != buffer2.getLong(pos2 + i)) {
                // Found mismatch in this Long, find exact position
                for (j in 0 until 8) {
                    if (buffer1.get(pos1 + i + j) != buffer2.get(pos2 + i + j)) {
                        return i + j
                    }
                }
            }
            i += 8
        }

        // Compare remaining bytes
        while (i < minLength) {
            if (buffer1.get(pos1 + i) != buffer2.get(pos2 + i)) {
                return i
            }
            i++
        }

        return if (buffer1Remaining != buffer2Remaining) minLength else -1
    }
}
