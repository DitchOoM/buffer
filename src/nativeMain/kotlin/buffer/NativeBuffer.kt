package com.ditchoom.buffer

/**
 * High-performance native buffer using malloc/free via UnsafeMemory.
 *
 * Key optimizations:
 * - Uses native memory (malloc) instead of ByteArray
 * - Zero-copy slice() by tracking baseOffset
 * - Direct memory access for multi-byte operations
 * - Byte swapping handled via reverseBytes when needed
 */
class NativeBuffer private constructor(
    private val address: Long,
    private val baseOffset: Int,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
    private val ownsMemory: Boolean, // true if this buffer should free memory on close
) : PlatformBuffer {
    private var pos: Int = 0
    private var lim: Int = capacity
    private val needsSwap: Boolean = byteOrder != UnsafeMemory.nativeByteOrder

    companion object {
        fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): NativeBuffer {
            val address = UnsafeMemory.allocate(size)
            return NativeBuffer(address, 0, size, byteOrder, ownsMemory = true)
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

    override fun readByte(): Byte = UnsafeMemory.getByte(address, baseOffset + pos++)

    override fun get(index: Int): Byte = UnsafeMemory.getByte(address, baseOffset + index)

    // Zero-copy slice: creates a view with adjusted offset, shares same native memory
    override fun slice(): ReadBuffer =
        NativeBuffer(
            address,
            baseOffset + pos,
            lim - pos,
            byteOrder,
            ownsMemory = false, // slice doesn't own memory
        )

    override fun readByteArray(size: Int): ByteArray {
        val result = ByteArray(size)
        UnsafeMemory.copyToArray(address, baseOffset + pos, result, 0, size)
        pos += size
        return result
    }

    override fun readShort(): Short {
        val value = UnsafeMemory.getShort(address, baseOffset + pos)
        pos += 2
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun getShort(index: Int): Short {
        val value = UnsafeMemory.getShort(address, baseOffset + index)
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun readInt(): Int {
        val value = UnsafeMemory.getInt(address, baseOffset + pos)
        pos += 4
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun getInt(index: Int): Int {
        val value = UnsafeMemory.getInt(address, baseOffset + index)
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun readLong(): Long {
        val value = UnsafeMemory.getLong(address, baseOffset + pos)
        pos += 8
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun getLong(index: Int): Long {
        val value = UnsafeMemory.getLong(address, baseOffset + index)
        return if (needsSwap) value.reverseBytes() else value
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
        // For string reading, we need to get the bytes first (unavoidable for charset conversion)
        val bytes = readByteArray(length)
        return when (charset) {
            Charset.UTF8 -> bytes.decodeToString()
            else -> throw UnsupportedOperationException("Only UTF8 charset is supported on native")
        }
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        UnsafeMemory.putByte(address, baseOffset + pos++, byte)
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        UnsafeMemory.putByte(address, baseOffset + index, byte)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        UnsafeMemory.copyFromArray(bytes, offset, address, baseOffset + pos, length)
        pos += length
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        val value = if (needsSwap) short.reverseBytes() else short
        UnsafeMemory.putShort(address, baseOffset + pos, value)
        pos += 2
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        val value = if (needsSwap) short.reverseBytes() else short
        UnsafeMemory.putShort(address, baseOffset + index, value)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        val value = if (needsSwap) int.reverseBytes() else int
        UnsafeMemory.putInt(address, baseOffset + pos, value)
        pos += 4
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        val value = if (needsSwap) int.reverseBytes() else int
        UnsafeMemory.putInt(address, baseOffset + index, value)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        val value = if (needsSwap) long.reverseBytes() else long
        UnsafeMemory.putLong(address, baseOffset + pos, value)
        pos += 8
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        val value = if (needsSwap) long.reverseBytes() else long
        UnsafeMemory.putLong(address, baseOffset + index, value)
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
            else -> throw UnsupportedOperationException("Only UTF8 charset is supported on native")
        }
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        if (buffer is NativeBuffer) {
            // Direct memory copy for NativeBuffer to NativeBuffer
            // Use memcpy via the platform
            for (i in 0 until size) {
                UnsafeMemory.putByte(address, baseOffset + pos + i, buffer.readByte())
            }
            pos += size
        } else {
            // Fallback: read bytes (creates allocation)
            val bytes = buffer.readByteArray(size)
            writeBytes(bytes)
        }
        buffer.position(buffer.position())
    }

    override suspend fun close() {
        if (ownsMemory && address != 0L) {
            UnsafeMemory.free(address)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NativeBuffer) return false
        if (pos != other.pos) return false
        if (lim != other.lim) return false
        if (capacity != other.capacity) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pos.hashCode()
        result = 31 * result + lim.hashCode()
        result = 31 * result + capacity.hashCode()
        return result
    }

    // Byte swapping utilities
    private fun Short.reverseBytes(): Short = (((this.toInt() and 0xFF) shl 8) or ((this.toInt() shr 8) and 0xFF)).toShort()

    private fun Int.reverseBytes(): Int =
        ((this and 0xFF) shl 24) or
            ((this and 0xFF00) shl 8) or
            ((this shr 8) and 0xFF00) or
            ((this shr 24) and 0xFF)

    private fun Long.reverseBytes(): Long =
        ((this and 0xFFL) shl 56) or
            ((this and 0xFF00L) shl 40) or
            ((this and 0xFF0000L) shl 24) or
            ((this and 0xFF000000L) shl 8) or
            ((this shr 8) and 0xFF000000L) or
            ((this shr 24) and 0xFF0000L) or
            ((this shr 40) and 0xFF00L) or
            ((this shr 56) and 0xFFL)
}
