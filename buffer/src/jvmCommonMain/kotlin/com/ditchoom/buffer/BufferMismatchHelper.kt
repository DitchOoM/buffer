package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Helper for buffer mismatch operations.
 *
 * Platform-specific implementations:
 * - JVM: Delegates to fallback, replaced by ByteBuffer.mismatch() via multi-release JAR on Java 11+
 * - Android: Runtime check for API 34+, uses ByteBuffer.mismatch() when available
 */
internal expect object BufferMismatchHelper {
    fun mismatch(
        buffer1: ByteBuffer,
        buffer2: ByteBuffer,
        minLength: Int,
        buffer1Remaining: Int,
        buffer2Remaining: Int,
    ): Int
}
