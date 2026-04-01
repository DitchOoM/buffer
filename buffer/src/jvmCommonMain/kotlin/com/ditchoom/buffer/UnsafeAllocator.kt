package com.ditchoom.buffer

import sun.misc.Unsafe

/**
 * JVM-only memory allocator backed by [sun.misc.Unsafe].
 *
 * Provides `allocateMemory` and `freeMemory` for deterministic native memory
 * management on JVM 8+ and Android. Used by [DeterministicUnsafeJvmBuffer].
 */
internal object UnsafeAllocator {
    private val unsafe: Unsafe? =
        try {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as Unsafe
        } catch (_: Exception) {
            null
        }

    val isSupported: Boolean = unsafe != null

    private fun checkSupported() {
        if (unsafe == null) {
            throw UnsupportedOperationException(
                "UnsafeAllocator is not supported: sun.misc.Unsafe is not available.",
            )
        }
    }

    fun allocateMemory(size: Long): Long {
        checkSupported()
        return unsafe!!.allocateMemory(size)
    }

    fun freeMemory(address: Long) {
        checkSupported()
        unsafe!!.freeMemory(address)
    }
}
