package com.ditchoom.buffer

import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/**
 * Helper for buffer mismatch operations on Android.
 *
 * Uses ByteBuffer.mismatch() on API 34+ (Android 14+), falls back to
 * Long comparisons on older versions.
 */
internal actual object BufferMismatchHelper {
    actual fun mismatch(
        buffer1: ByteBuffer,
        buffer2: ByteBuffer,
        minLength: Int,
        buffer1Remaining: Int,
        buffer2Remaining: Int,
    ): Int =
        if (Build.VERSION.SDK_INT >= 34) {
            mismatchApi34(buffer1, buffer2, minLength, buffer1Remaining, buffer2Remaining)
        } else {
            BufferMismatchImpl.mismatchFallback(buffer1, buffer2, minLength, buffer1Remaining, buffer2Remaining)
        }

    @RequiresApi(34)
    private fun mismatchApi34(
        buffer1: ByteBuffer,
        buffer2: ByteBuffer,
        minLength: Int,
        buffer1Remaining: Int,
        buffer2Remaining: Int,
    ): Int = BufferMismatchImpl.mismatchOptimized(buffer1, buffer2, minLength, buffer1Remaining, buffer2Remaining)
}
