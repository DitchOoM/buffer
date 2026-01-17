package com.ditchoom.buffer

import java.nio.ByteBuffer

private val addressField by lazy {
    try {
        java.nio.Buffer::class.java.getDeclaredField("address").apply {
            isAccessible = true
        }
    } catch (e: Exception) {
        null
    }
}

internal actual fun getDirectBufferAddress(buffer: ByteBuffer): Long =
    addressField?.getLong(buffer)
        ?: throw UnsupportedOperationException(
            "Cannot access native address. " +
                "This may require --add-opens java.base/java.nio=ALL-UNNAMED",
        )
