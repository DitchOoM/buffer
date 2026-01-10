package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Helper for buffer mismatch operations on Android.
 *
 * Uses the Long comparison fallback implementation for reliability.
 * ByteBuffer.mismatch() (API 34+) has been observed to return incorrect
 * results on some Android emulators.
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
