package com.ditchoom.buffer

import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * JVM buffer backed by a heap ByteBuffer with managed memory access support.
 *
 * This class provides access to the underlying ByteArray for:
 * - Serialization libraries that work directly with ByteArray
 * - Zero-copy interop with APIs expecting ByteArray input
 */
class HeapJvmBuffer(
    byteBuffer: ByteBuffer,
    fileRef: RandomAccessFile? = null,
) : BaseJvmBuffer(byteBuffer, fileRef),
    ManagedMemoryAccess {
    init {
        require(byteBuffer.hasArray()) { "HeapJvmBuffer requires a heap ByteBuffer with backing array" }
    }

    override val backingArray: ByteArray get() = byteBuffer.array()

    override val arrayOffset: Int get() = (byteBuffer as Buffer).arrayOffset()

    override fun slice() = HeapJvmBuffer(byteBuffer.slice())
}
