package com.ditchoom.buffer

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array

/**
 * High-performance WASM-JS buffer using JavaScript typed arrays.
 *
 * Key optimizations:
 * - Uses Int8Array backed by ArrayBuffer (native JS memory)
 * - Cached DataView for all read/write operations (no allocation per operation)
 * - Zero-copy slice() using Int8Array view constructor
 * - Direct multi-byte access via DataView
 */
class WasmBuffer private constructor(
    private val buffer: Int8Array,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
    private val ownsMemory: Boolean,
) : PlatformBuffer {
    private var pos: Int = 0
    private var lim: Int = capacity
    private val littleEndian: Boolean = byteOrder == ByteOrder.LITTLE_ENDIAN

    // Cached DataView - reused for all operations, avoiding allocation per read/write
    private val dataView = DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength)

    companion object {
        fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): WasmBuffer {
            val arrayBuffer = ArrayBuffer(size)
            val int8Array = Int8Array(arrayBuffer)
            return WasmBuffer(int8Array, size, byteOrder, ownsMemory = true)
        }
    }

    override fun resetForRead() {
        lim = pos
        pos = 0
    }

    override fun resetForWrite() {
        pos = 0
        lim = capacity
    }

    override fun setLimit(limit: Int) {
        lim = limit
    }

    override fun limit(): Int = lim

    override fun position(): Int = pos

    override fun position(newPosition: Int) {
        pos = newPosition
    }

    // Use cached DataView with offset
    override fun readByte(): Byte = dataView.getInt8(pos++)

    override fun get(index: Int): Byte = dataView.getInt8(index)

    // Zero-copy slice: creates a VIEW into the same ArrayBuffer, not a copy
    override fun slice(): ReadBuffer =
        WasmBuffer(
            Int8Array(buffer.buffer, buffer.byteOffset + pos, lim - pos),
            lim - pos,
            byteOrder,
            ownsMemory = false,
        )

    override fun readByteArray(size: Int): ByteArray {
        val result = ByteArray(size)
        for (i in 0 until size) {
            result[i] = dataView.getInt8(pos + i)
        }
        pos += size
        return result
    }

    // Use cached DataView with position offset
    override fun readShort(): Short {
        val value = dataView.getInt16(pos, littleEndian)
        pos += 2
        return value.toInt().toShort()
    }

    override fun getShort(index: Int): Short = dataView.getInt16(index, littleEndian).toInt().toShort()

    override fun readInt(): Int {
        val value = dataView.getInt32(pos, littleEndian)
        pos += 4
        return value
    }

    override fun getInt(index: Int): Int = dataView.getInt32(index, littleEndian)

    // Optimized long read using two 32-bit reads - no ByteArray allocation
    override fun readLong(): Long {
        val first = dataView.getInt32(pos, littleEndian).toLong() and 0xFFFFFFFFL
        val second = dataView.getInt32(pos + 4, littleEndian).toLong() and 0xFFFFFFFFL
        pos += 8
        return if (littleEndian) {
            (second shl 32) or first
        } else {
            (first shl 32) or second
        }
    }

    override fun getLong(index: Int): Long {
        val first = dataView.getInt32(index, littleEndian).toLong() and 0xFFFFFFFFL
        val second = dataView.getInt32(index + 4, littleEndian).toLong() and 0xFFFFFFFFL
        return if (littleEndian) {
            (second shl 32) or first
        } else {
            (first shl 32) or second
        }
    }

    override fun readFloat(): Float {
        val bits = readInt()
        return Float.fromBits(bits)
    }

    override fun getFloat(index: Int): Float {
        val bits = getInt(index)
        return Float.fromBits(bits)
    }

    override fun readDouble(): Double {
        val bits = readLong()
        return Double.fromBits(bits)
    }

    override fun getDouble(index: Int): Double {
        val bits = getLong(index)
        return Double.fromBits(bits)
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val bytes = readByteArray(length)
        return when (charset) {
            Charset.UTF8 -> bytes.decodeToString()
            else -> throw UnsupportedOperationException("Only UTF8 charset is supported on WASM")
        }
    }

    // Use cached DataView with offset
    override fun writeByte(byte: Byte): WriteBuffer {
        dataView.setInt8(pos++, byte)
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        dataView.setInt8(index, byte)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        for (i in 0 until length) {
            dataView.setInt8(pos + i, bytes[offset + i])
        }
        pos += length
        return this
    }

    // Use cached DataView
    override fun writeShort(short: Short): WriteBuffer {
        dataView.setInt16(pos, short, littleEndian)
        pos += 2
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        dataView.setInt16(index, short, littleEndian)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        dataView.setInt32(pos, int, littleEndian)
        pos += 4
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        dataView.setInt32(index, int, littleEndian)
        return this
    }

    // Optimized long write using two 32-bit writes - no ByteArray allocation
    override fun writeLong(long: Long): WriteBuffer {
        if (littleEndian) {
            dataView.setInt32(pos, long.toInt(), true)
            dataView.setInt32(pos + 4, (long shr 32).toInt(), true)
        } else {
            dataView.setInt32(pos, (long shr 32).toInt(), false)
            dataView.setInt32(pos + 4, long.toInt(), false)
        }
        pos += 8
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        if (littleEndian) {
            dataView.setInt32(index, long.toInt(), true)
            dataView.setInt32(index + 4, (long shr 32).toInt(), true)
        } else {
            dataView.setInt32(index, (long shr 32).toInt(), false)
            dataView.setInt32(index + 4, long.toInt(), false)
        }
        return this
    }

    override fun writeFloat(float: Float): WriteBuffer = writeInt(float.toRawBits())

    override fun set(
        index: Int,
        float: Float,
    ): WriteBuffer = set(index, float.toRawBits())

    override fun writeDouble(double: Double): WriteBuffer = writeLong(double.toRawBits())

    override fun set(
        index: Int,
        double: Double,
    ): WriteBuffer = set(index, double.toRawBits())

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        when (charset) {
            Charset.UTF8 -> writeBytes(text.toString().encodeToByteArray())
            else -> throw UnsupportedOperationException("Only UTF8 charset is supported on WASM")
        }
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        if (buffer is WasmBuffer) {
            // Direct copy using DataView
            for (i in 0 until size) {
                dataView.setInt8(pos + i, buffer.dataView.getInt8(buffer.pos + i))
            }
            pos += size
            buffer.pos += size
        } else {
            // Fallback: read bytes
            val bytes = buffer.readByteArray(size)
            writeBytes(bytes)
        }
    }

    override suspend fun close() {
        // ArrayBuffer is garbage collected
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WasmBuffer) return false
        if (pos != other.pos) return false
        if (lim != other.lim) return false
        if (capacity != other.capacity) return false

        // Compare byte by byte using DataView (no ByteArray allocation)
        val size = remaining()
        for (i in 0 until size) {
            if (dataView.getInt8(pos + i) != other.dataView.getInt8(other.pos + i)) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var result = pos.hashCode()
        result = 31 * result + lim.hashCode()
        result = 31 * result + capacity.hashCode()
        // Hash bytes using DataView (no ByteArray allocation)
        val size = remaining()
        for (i in 0 until size) {
            result = 31 * result + dataView.getInt8(pos + i)
        }
        return result
    }
}
