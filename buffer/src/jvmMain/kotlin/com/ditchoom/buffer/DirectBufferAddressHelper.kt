package com.ditchoom.buffer

import java.nio.ByteBuffer

// Reflective field lookup; failure is an intentional degrade path (no native address), so the
// caught exception is deliberately not propagated.
@Suppress("SwallowedException")
private val addressField by lazy {
    try {
        java.nio.Buffer::class.java.getDeclaredField("address").apply {
            isAccessible = true
        }
    } catch (e: ReflectiveOperationException) {
        // Buffer.address is inaccessible without --add-opens; degrade to no native address.
        null
    } catch (e: SecurityException) {
        // A SecurityManager forbids the reflective lookup; degrade to no native address.
        null
    }
}

internal actual fun getDirectBufferAddress(buffer: ByteBuffer): Long =
    addressField?.getLong(buffer)
        ?: throw UnsupportedOperationException(
            "Cannot access native address. " +
                "This may require --add-opens java.base/java.nio=ALL-UNNAMED",
        )
