package com.ditchoom.buffer

import java.nio.ByteBuffer

// Cache the Field to avoid repeated reflection lookup
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
 * Gets the native memory address of a direct ByteBuffer using cached reflection.
 *
 * @throws UnsupportedOperationException if the address field is not accessible
 */
internal actual fun getDirectBufferAddress(buffer: ByteBuffer): Long =
    addressField?.getLong(buffer)
        ?: throw UnsupportedOperationException(
            "Cannot access native address. " +
                "This may require --add-opens java.base/java.nio=ALL-UNNAMED",
        )
