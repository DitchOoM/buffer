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

    override fun slice() = DirectJvmBuffer(byteBuffer.slice())
}
