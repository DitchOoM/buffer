package com.ditchoom.buffer

import java.nio.ByteBuffer

/** Sentinel value indicating the default ReadBuffer.mismatch() should be used. */
internal const val USE_DEFAULT_MISMATCH = Int.MIN_VALUE

/**
 * Helper for buffer mismatch operations.
 *
 * Returns [USE_DEFAULT_MISMATCH] to use the default ReadBuffer.mismatch(),
 * or the actual result from native ByteBuffer.mismatch().
 *
 * Platform-specific implementations:
 * - JVM Java 8: Returns USE_DEFAULT_MISMATCH
 * - JVM Java 11+: Returns ByteBuffer.mismatch() result via multi-release JAR
 * - Android: Returns USE_DEFAULT_MISMATCH
 */
internal expect object BufferMismatchHelper {
    fun mismatch(
        buffer1: ByteBuffer,
        buffer2: ByteBuffer,
    ): Int
}
