package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * JVM buffer backed by a direct ByteBuffer with native memory access support.
 *
 * This class provides access to the native memory address for use with:
 * - Java NIO channels
 * - JNI/FFI native code
 * - Memory-mapped files
 * - Future Java FFM (Foreign Function & Memory) API
 */
class DirectJvmBuffer(
    byteBuffer: ByteBuffer,
) : BaseJvmBuffer(byteBuffer),
    NativeMemoryAccess {
    init {
        require(byteBuffer.isDirect) { "DirectJvmBuffer requires a direct ByteBuffer" }
    }

    /**
     * The native memory address of the direct ByteBuffer.
     * This address can be used for JNI/FFI interop or with Java's FFM API.
     */
    override val nativeAddress: Long by lazy { getDirectBufferAddress(byteBuffer) }

    /**
     * The size of the native memory region in bytes.
     */
    override val nativeSize: Long get() = capacity.toLong()

    /**
     * Frees native memory by invoking the direct ByteBuffer's Cleaner via
     * `sun.misc.Unsafe.invokeCleaner()` (JDK 9+).
     *
     * This is best-effort: silently fails on sliced buffers, already-cleaned
     * buffers, JDK 8, or if reflection is blocked. Idempotent.
     */
    override fun freeNativeMemory() {
        invokeCleaner?.let { cleaner ->
            try {
                cleaner.invoke(unsafeInstance, byteBuffer)
            } catch (_: Exception) {
                // Silently fail: sliced buffer, already cleaned, or unsupported JDK
            }
        }
    }

    override fun slice() = DirectJvmBuffer(byteBuffer.slice())

    companion object {
        // Resolved once per process. null = not available (JDK 8 or restricted access)
        private val unsafeInstance: Any? =
            try {
                val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
                field.isAccessible = true
                field.get(null)
            } catch (_: Exception) {
                null
            }

        private val invokeCleaner: java.lang.reflect.Method? =
            try {
                sun.misc.Unsafe::class.java.getMethod("invokeCleaner", java.nio.ByteBuffer::class.java)
            } catch (_: Exception) {
                null
            }
    }
}
