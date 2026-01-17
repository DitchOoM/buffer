package com.ditchoom.buffer

import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array

/**
 * JavaScript implementation of [withScope].
 *
 * Uses ArrayBuffer/Int8Array backed by GC-managed memory.
 */
actual inline fun <T> withScope(block: (BufferScope) -> T): T {
    val scope = JsBufferScope()
    return try {
        block(scope)
    } finally {
        scope.close()
    }
}

/**
 * JavaScript BufferScope implementation using GC-managed ArrayBuffers.
 */
class JsBufferScope : BufferScope {
    private val allocations = mutableListOf<JsScopedBuffer>()
    private var open = true

    override val isOpen: Boolean get() = open

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): ScopedBuffer {
        check(open) { "BufferScope is closed" }
        val buffer = Int8Array(size)
        val scopedBuffer = JsScopedBuffer(this, buffer, size, byteOrder)
        allocations.add(scopedBuffer)
        return scopedBuffer
    }

    override fun close() {
        if (open) {
            open = false
            for (buffer in allocations) {
                buffer.invalidate()
            }
            allocations.clear()
        }
    }
}

/**
 * JavaScript ScopedBuffer implementation using Int8Array/DataView.
 */
class JsScopedBuffer(
    override val scope: BufferScope,
    private val buffer: Int8Array,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
) : ScopedBuffer {
    private var positionValue: Int = 0
    private var limitValue: Int = capacity
    private var valid = true
    private val dataView = DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength)

    override val nativeAddress: Long get() = buffer.byteOffset.toLong()
    override val nativeSize: Long get() = capacity.toLong()

    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN

    internal fun invalidate() {
        valid = false
    }

    private fun checkValid() {
        check(valid) { "ScopedBuffer is no longer valid - scope has been closed" }
    }

    // Position and limit management
    override fun position(): Int = positionValue

    override fun position(newPosition: Int) {
        positionValue = newPosition
    }

    override fun limit(): Int = limitValue

    override fun setLimit(limit: Int) {
        limitValue = limit
    }

    override fun resetForRead() {
        limitValue = positionValue
        positionValue = 0
    }

    override fun resetForWrite() {
        positionValue = 0
        limitValue = capacity
    }

    // Read operations (relative)
    override fun readByte(): Byte {
        checkValid()
        return dataView.getInt8(positionValue++)
    }

    override fun readShort(): Short {
        checkValid()
        val value = dataView.getInt16(positionValue, littleEndian).toInt().toShort()
        positionValue += 2
        return value
    }

    override fun readInt(): Int {
        checkValid()
        val value = dataView.getInt32(positionValue, littleEndian)
        positionValue += 4
        return value
    }

    override fun readLong(): Long {
        checkValid()
        val value =
            if (littleEndian) {
                val low = dataView.getInt32(positionValue, true).toLong() and 0xFFFFFFFFL
                val high = dataView.getInt32(positionValue + 4, true).toLong()
                (high shl 32) or low
            } else {
                val high = dataView.getInt32(positionValue, false).toLong()
                val low = dataView.getInt32(positionValue + 4, false).toLong() and 0xFFFFFFFFL
                (high shl 32) or low
            }
        positionValue += 8
        return value
    }

    // Read operations (absolute)
    override fun get(index: Int): Byte {
        checkValid()
        return dataView.getInt8(index)
    }

    override fun getShort(index: Int): Short {
        checkValid()
        return dataView.getInt16(index, littleEndian).toInt().toShort()
    }

    override fun getInt(index: Int): Int {
        checkValid()
        return dataView.getInt32(index, littleEndian)
    }

    override fun getLong(index: Int): Long {
        checkValid()
        return if (littleEndian) {
            val low = dataView.getInt32(index, true).toLong() and 0xFFFFFFFFL
            val high = dataView.getInt32(index + 4, true).toLong()
            (high shl 32) or low
        } else {
            val high = dataView.getInt32(index, false).toLong()
            val low = dataView.getInt32(index + 4, false).toLong() and 0xFFFFFFFFL
            (high shl 32) or low
        }
    }

    // Write operations (relative)
    override fun writeByte(byte: Byte): WriteBuffer {
        checkValid()
        dataView.setInt8(positionValue++, byte)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        checkValid()
        dataView.setInt16(positionValue, short, littleEndian)
        positionValue += 2
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        checkValid()
        dataView.setInt32(positionValue, int, littleEndian)
        positionValue += 4
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        checkValid()
        if (littleEndian) {
            dataView.setInt32(positionValue, long.toInt(), true)
            dataView.setInt32(positionValue + 4, (long shr 32).toInt(), true)
        } else {
            dataView.setInt32(positionValue, (long shr 32).toInt(), false)
            dataView.setInt32(positionValue + 4, long.toInt(), false)
        }
        positionValue += 8
        return this
    }

    // Write operations (absolute)
    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        checkValid()
        dataView.setInt8(index, byte)
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        checkValid()
        dataView.setInt16(index, short, littleEndian)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        checkValid()
        dataView.setInt32(index, int, littleEndian)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        checkValid()
        if (littleEndian) {
            dataView.setInt32(index, long.toInt(), true)
            dataView.setInt32(index + 4, (long shr 32).toInt(), true)
        } else {
            dataView.setInt32(index, (long shr 32).toInt(), false)
            dataView.setInt32(index + 4, long.toInt(), false)
        }
        return this
    }

    // Bulk operations
    override fun readByteArray(size: Int): ByteArray {
        checkValid()
        val subArray = buffer.subarray(positionValue, positionValue + size)
        positionValue += size
        return Int8Array(subArray.buffer, subArray.byteOffset, size).unsafeCast<ByteArray>()
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkValid()
        val int8Array = bytes.unsafeCast<Int8Array>().subarray(offset, offset + length)
        buffer.set(int8Array, positionValue)
        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        if (buffer is JsScopedBuffer) {
            val sourceSubarray = buffer.buffer.subarray(buffer.positionValue, buffer.positionValue + size)
            this.buffer.set(sourceSubarray, positionValue)
        } else {
            writeBytes(buffer.readByteArray(size))
            buffer.position(buffer.position() + size)
            return
        }
        positionValue += size
        buffer.position(buffer.position() + size)
    }

    override fun slice(): ReadBuffer {
        checkValid()
        val slicedArray = buffer.subarray(positionValue, limitValue)
        return JsScopedBuffer(scope, slicedArray, remaining(), byteOrder)
    }

    // String operations
    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val bytes = readByteArray(length)
        return bytes.decodeToString()
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        val bytes = text.toString().encodeToByteArray()
        writeBytes(bytes)
        return this
    }
}
