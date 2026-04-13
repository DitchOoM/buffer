package com.ditchoom.buffer

import java.nio.ByteBuffer

internal object AndroidBufferHelper {
    private val addressField by lazy {
        try {
            java.nio.Buffer::class.java.getDeclaredField("address").apply {
                isAccessible = true
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getDirectBufferAddress(buffer: ByteBuffer): Long {
        // Try JNI GetDirectBufferAddress first (officially supported, no blocklist)
        try {
            val addr = NativeBufferHelper.getDirectBufferAddress(buffer)
            if (addr != 0L) return addr
        } catch (_: Throwable) {
            // JNI lib not loaded — fall through to reflection
        }
        // Fall back to reflection
        return addressField?.getLong(buffer)
            ?: throw UnsupportedOperationException(
                "Cannot access native address on this Android version.",
            )
    }
}
