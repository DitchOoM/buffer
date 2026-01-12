package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Android helper for getting native buffer addresses.
 *
 * Uses cached reflection for consistent performance across all Android versions.
 *
 * Note: MethodHandle-based optimization for API 33+ is deferred to a future release
 * with proper multi-version library support (minSdk 26+ flavor) to avoid dexing
 * issues with minSdk 19.
 */
internal object AndroidBufferHelper {
    // Cached Field for reflection access
    private val addressField by lazy {
        try {
            java.nio.Buffer::class.java.getDeclaredField("address").apply {
                isAccessible = true
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gets the native memory address of a direct ByteBuffer.
     *
     * Uses cached reflection for field access.
     */
    fun getDirectBufferAddress(buffer: ByteBuffer): Long =
        addressField?.getLong(buffer)
            ?: throw UnsupportedOperationException(
                "Cannot access native address on this Android version.",
            )
}
