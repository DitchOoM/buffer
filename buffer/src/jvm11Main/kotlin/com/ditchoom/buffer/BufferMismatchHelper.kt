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
        // Only use native mismatch when both buffers are the same type
        // This matches the behavior in BufferMismatchImpl for consistency
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
     */
    private fun mismatchFallback(
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
