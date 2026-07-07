package com.ditchoom.buffer

import java.nio.ByteBuffer

internal object AndroidBufferHelper {
    // Reflective field lookup; failure is an intentional degrade path (no native address), so the
    // caught exception is deliberately not propagated.
    @Suppress("SwallowedException")
    private val addressField by lazy {
        try {
            java.nio.Buffer::class.java.getDeclaredField("address").apply {
                isAccessible = true
            }
        } catch (e: ReflectiveOperationException) {
            // The non-SDK Buffer.address field is unavailable (hidden-API enforcement); degrade.
            null
        } catch (e: SecurityException) {
            // A SecurityManager forbids the reflective lookup; degrade to no native address.
            null
        }
    }

    fun getDirectBufferAddress(buffer: ByteBuffer): Long =
        addressField?.getLong(buffer)
            ?: throw UnsupportedOperationException(
                "Cannot access native address on this Android version.",
            )
}
