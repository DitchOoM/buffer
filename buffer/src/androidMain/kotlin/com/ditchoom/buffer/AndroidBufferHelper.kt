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

    fun getDirectBufferAddress(buffer: ByteBuffer): Long =
        addressField?.getLong(buffer)
            ?: throw UnsupportedOperationException(
                "Cannot access native address on this Android version.",
            )
}
