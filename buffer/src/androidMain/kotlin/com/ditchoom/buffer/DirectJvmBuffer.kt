package com.ditchoom.buffer

import java.lang.reflect.Field
import java.nio.ByteBuffer

/**
 * JVM buffer backed by a direct ByteBuffer with native memory access support.
 *
 * This class provides access to the native memory address for use with:
 * - Java NIO channels
 * - JNI/FFI native code
 * - Memory-mapped files
 */
class DirectJvmBuffer(
    byteBuffer: ByteBuffer,
) : JvmBuffer(byteBuffer),
    NativeMemoryAccess {
    init {
        require(byteBuffer.isDirect) { "DirectJvmBuffer requires a direct ByteBuffer" }
    }

    /**
     * The native memory address of the direct ByteBuffer.
     * This address can be used for JNI/FFI interop.
     */
    override val nativeAddress: Long by lazy { getDirectBufferAddress(byteBuffer) }

    /**
     * The size of the native memory region in bytes.
     */
    override val nativeSize: Long get() = capacity.toLong()

    companion object {
        private val addressField: Field? by lazy {
            try {
                val field = java.nio.Buffer::class.java.getDeclaredField("address")
                field.isAccessible = true
                field
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Gets the native memory address of a direct ByteBuffer using reflection.
         * Direct ByteBuffers store their native address in a protected field.
         */
        private fun getDirectBufferAddress(buffer: ByteBuffer): Long =
            addressField?.getLong(buffer)
                ?: throw UnsupportedOperationException(
                    "Cannot access native address on this Android version.",
                )
    }
}
