package com.ditchoom.buffer

import android.os.Build
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.nio.ByteBuffer

/**
 * Android-optimized helper for getting native buffer addresses.
 *
 * On Android 33+ (Tiramisu), uses MethodHandles for faster access (~10x faster than reflection).
 * Falls back to cached reflection on older Android versions.
 */
internal object AndroidBufferHelper {
    private val useMethodHandles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // Cached MethodHandle for address field (Android 33+)
    private val addressHandle: MethodHandle? by lazy {
        if (!useMethodHandles) return@lazy null
        try {
            val lookup =
                MethodHandles.privateLookupIn(
                    java.nio.Buffer::class.java,
                    MethodHandles.lookup(),
                )
            lookup.findGetter(
                java.nio.Buffer::class.java,
                "address",
                Long::class.javaPrimitiveType,
            )
        } catch (e: Exception) {
            null
        }
    }

    // Cached Field for reflection fallback
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
     * Uses MethodHandle on Android 33+ for better performance,
     * falls back to reflection on older versions.
     */
    fun getDirectBufferAddress(buffer: ByteBuffer): Long {
        // Fast path: MethodHandles on Android 33+
        addressHandle?.let { handle ->
            return try {
                handle.invokeExact(buffer as java.nio.Buffer) as Long
            } catch (e: Throwable) {
                getAddressViaReflection(buffer)
            }
        }
        // Fallback: Cached reflection
        return getAddressViaReflection(buffer)
    }

    private fun getAddressViaReflection(buffer: ByteBuffer): Long =
        addressField?.getLong(buffer)
            ?: throw UnsupportedOperationException(
                "Cannot access native address on this Android version.",
            )
}
