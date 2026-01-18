@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import platform.posix.free
import platform.posix.malloc

/**
 * Buffer implementation using native memory (malloc/free) on Linux.
 *
 * Provides true zero-copy access for io_uring and other native I/O operations.
 * Memory is allocated outside Kotlin/Native GC heap - no pinning required.
 *
 * IMPORTANT: Must be explicitly closed to free native memory.
 *
 * Usage with io_uring:
 * ```kotlin
 * val buffer = NativeBuffer.allocate(65536)
 * val ptr = buffer.nativeAddress.toCPointer<ByteVar>()!!
 * io_uring_prep_recv(sqe, sockfd, ptr, buffer.capacity, 0)
 * // ... after read completes ...
 * buffer.position(bytesRead)
 * buffer.resetForRead()
 * return buffer // caller is responsible for closing
 * ```
 */
class NativeBuffer private constructor(
    private val ptr: CPointer<ByteVar>,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
) : PlatformBuffer,
    NativeMemoryAccess {
    private var positionValue: Int = 0
    private var limitValue: Int = capacity
    private var closed: Boolean = false

    override val nativeAddress: Long get() = ptr.toLong()
    override val nativeSize: Long get() = capacity.toLong()

    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN
    private val nativeIsLittleEndian = true

    companion object {
        fun allocate(
            size: Int,
            byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
        ): NativeBuffer {
            val ptr = malloc(size.convert())?.reinterpret<ByteVar>()
                ?: throw OutOfMemoryError("Failed to allocate $size bytes")
            return NativeBuffer(ptr, size, byteOrder)
        }
    }

    private fun checkOpen() = check(!closed) { "Buffer closed" }

    override fun position(): Int = positionValue
    override fun position(newPosition: Int) { positionValue = newPosition }
    override fun limit(): Int = limitValue
    override fun setLimit(limit: Int) { limitValue = limit }

    override fun resetForRead() {
        limitValue = positionValue
        positionValue = 0
    }

    override fun resetForWrite() {
        positionValue = 0
        limitValue = capacity
    }

    // === Read operations ===

    override fun readByte(): Byte {
        checkOpen()
        return ptr[positionValue++]
    }

    override fun get(index: Int): Byte {
        checkOpen()
        return ptr[index]
    }

    override fun readShort(): Short {
        checkOpen()
        val result = getShort(positionValue)
        positionValue += 2
        return result
    }

    override fun getShort(index: Int): Short {
        checkOpen()
        val raw = UnsafeMemory.getShort(nativeAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readInt(): Int {
        checkOpen()
        val result = getInt(positionValue)
        positionValue += 4
        return result
    }

    override fun getInt(index: Int): Int {
        checkOpen()
        val raw = UnsafeMemory.getInt(nativeAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readLong(): Long {
        checkOpen()
        val result = getLong(positionValue)
        positionValue += 8
        return result
    }

    override fun getLong(index: Int): Long {
        checkOpen()
        val raw = UnsafeMemory.getLong(nativeAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readByteArray(size: Int): ByteArray {
        checkOpen()
        val array = ByteArray(size)
        UnsafeMemory.copyMemoryToArray(nativeAddress + positionValue, array, 0, size)
        positionValue += size
        return array
    }

    override fun readString(length: Int, charset: Charset): String {
        checkOpen()
        return readByteArray(length).decodeToString()
    }

    override fun slice(): ReadBuffer {
        checkOpen()
        val sliceAddress = nativeAddress + positionValue
        return NativeBufferSlice(sliceAddress, remaining(), byteOrder, this)
    }

    // === Write operations ===

    override fun writeByte(byte: Byte): WriteBuffer {
        checkOpen()
        ptr[positionValue++] = byte
        return this
    }

    override fun set(index: Int, byte: Byte): WriteBuffer {
        checkOpen()
        ptr[index] = byte
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        checkOpen()
        set(positionValue, short)
        positionValue += 2
        return this
    }

    override fun set(index: Int, short: Short): WriteBuffer {
        checkOpen()
        val value = if (littleEndian == nativeIsLittleEndian) short else short.reverseBytes()
        UnsafeMemory.putShort(nativeAddress + index, value)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        checkOpen()
        set(positionValue, int)
        positionValue += 4
        return this
    }

    override fun set(index: Int, int: Int): WriteBuffer {
        checkOpen()
        val value = if (littleEndian == nativeIsLittleEndian) int else int.reverseBytes()
        UnsafeMemory.putInt(nativeAddress + index, value)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        checkOpen()
        set(positionValue, long)
        positionValue += 8
        return this
    }

    override fun set(index: Int, long: Long): WriteBuffer {
        checkOpen()
        val value = if (littleEndian == nativeIsLittleEndian) long else long.reverseBytes()
        UnsafeMemory.putLong(nativeAddress + index, value)
        return this
    }

    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int): WriteBuffer {
        checkOpen()
        UnsafeMemory.copyMemoryFromArray(bytes, offset, nativeAddress + positionValue, length)
        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        checkOpen()
        val size = buffer.remaining()

        // Zero-copy path: check if source has native memory access
        val srcNative = buffer.nativeMemoryAccess
        if (srcNative != null) {
            UnsafeMemory.copyMemory(
                srcNative.nativeAddress + buffer.position(),
                nativeAddress + positionValue,
                size.toLong(),
            )
            positionValue += size
            buffer.position(buffer.position() + size)
            return
        }

        // Zero-copy path: check if source has managed array access
        val srcManaged = buffer.managedMemoryAccess
        if (srcManaged != null) {
            UnsafeMemory.copyMemoryFromArray(
                srcManaged.backingArray,
                srcManaged.arrayOffset + buffer.position(),
                nativeAddress + positionValue,
                size,
            )
            positionValue += size
            buffer.position(buffer.position() + size)
            return
        }

        // Fallback: copy via temporary array
        writeBytes(buffer.readByteArray(size))
    }

    override fun writeString(text: CharSequence, charset: Charset): WriteBuffer {
        checkOpen()
        writeBytes(text.toString().encodeToByteArray())
        return this
    }

    override suspend fun close() {
        if (!closed) {
            closed = true
            free(ptr)
        }
    }

    fun isClosed(): Boolean = closed
}

/**
 * Read-only slice of a NativeBuffer sharing parent's memory.
 */
private class NativeBufferSlice(
    private val baseAddress: Long,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
    private val parent: NativeBuffer,
) : ReadBuffer,
    NativeMemoryAccess {
    private var positionValue: Int = 0
    private var limitValue: Int = capacity

    override val nativeAddress: Long get() = baseAddress
    override val nativeSize: Long get() = capacity.toLong()

    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN
    private val nativeIsLittleEndian = true

    private fun checkOpen() = check(!parent.isClosed()) { "Parent buffer closed" }

    override fun position(): Int = positionValue
    override fun position(newPosition: Int) { positionValue = newPosition }
    override fun limit(): Int = limitValue
    override fun setLimit(limit: Int) { limitValue = limit }
    override fun resetForRead() {
        limitValue = positionValue
        positionValue = 0
    }

    override fun readByte(): Byte {
        checkOpen()
        return UnsafeMemory.getByte(baseAddress + positionValue++)
    }

    override fun get(index: Int): Byte {
        checkOpen()
        return UnsafeMemory.getByte(baseAddress + index)
    }

    override fun readShort(): Short {
        checkOpen()
        val result = getShort(positionValue)
        positionValue += 2
        return result
    }

    override fun getShort(index: Int): Short {
        checkOpen()
        val raw = UnsafeMemory.getShort(baseAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readInt(): Int {
        checkOpen()
        val result = getInt(positionValue)
        positionValue += 4
        return result
    }

    override fun getInt(index: Int): Int {
        checkOpen()
        val raw = UnsafeMemory.getInt(baseAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readLong(): Long {
        checkOpen()
        val result = getLong(positionValue)
        positionValue += 8
        return result
    }

    override fun getLong(index: Int): Long {
        checkOpen()
        val raw = UnsafeMemory.getLong(baseAddress + index)
        return if (littleEndian == nativeIsLittleEndian) raw else raw.reverseBytes()
    }

    override fun readByteArray(size: Int): ByteArray {
        checkOpen()
        val array = ByteArray(size)
        UnsafeMemory.copyMemoryToArray(baseAddress + positionValue, array, 0, size)
        positionValue += size
        return array
    }

    override fun readString(length: Int, charset: Charset): String {
        checkOpen()
        return readByteArray(length).decodeToString()
    }

    override fun slice(): ReadBuffer {
        checkOpen()
        return NativeBufferSlice(baseAddress + positionValue, remaining(), byteOrder, parent)
    }
}
