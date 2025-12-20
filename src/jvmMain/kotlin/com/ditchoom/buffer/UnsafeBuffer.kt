package com.ditchoom.buffer

import sun.misc.Unsafe
import java.lang.reflect.Field

class UnsafeBuffer private constructor(
    override val capacity: Int,
    override val byteOrder: ByteOrder,
    private val address: Long,
    private val isSlice: Boolean = false,
) : PlatformBuffer {
    private var pos: Int = 0
    private var lim: Int = capacity
    private var closed: Boolean = false
    private val needsSwap: Boolean = byteOrder != NATIVE_ORDER

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

    override fun readByte(): Byte {
        checkReadBounds(1)
        return unsafe.getByte(address + pos++)
    }

    override fun get(index: Int): Byte {
        checkAbsoluteBounds(index, 1)
        return unsafe.getByte(address + index)
    }

    override fun readByteArray(size: Int): ByteArray {
        checkReadBounds(size)
        val array = ByteArray(size)
        unsafe.copyMemory(null, address + pos, array, BYTE_ARRAY_OFFSET, size.toLong())
        pos += size
        return array
    }

    override fun slice(): ReadBuffer {
        val sliceSize = lim - pos
        val sliceAddress = address + pos
        return UnsafeBuffer(sliceSize, byteOrder, sliceAddress, isSlice = true)
    }

    override fun readShort(): Short {
        checkReadBounds(2)
        val value = unsafe.getShort(address + pos)
        pos += 2
        return if (needsSwap) java.lang.Short.reverseBytes(value) else value
    }

    override fun getShort(index: Int): Short {
        checkAbsoluteBounds(index, 2)
        val value = unsafe.getShort(address + index)
        return if (needsSwap) java.lang.Short.reverseBytes(value) else value
    }

    override fun readInt(): Int {
        checkReadBounds(4)
        val value = unsafe.getInt(address + pos)
        pos += 4
        return if (needsSwap) java.lang.Integer.reverseBytes(value) else value
    }

    override fun getInt(index: Int): Int {
        checkAbsoluteBounds(index, 4)
        val value = unsafe.getInt(address + index)
        return if (needsSwap) java.lang.Integer.reverseBytes(value) else value
    }

    override fun readLong(): Long {
        checkReadBounds(8)
        val value = unsafe.getLong(address + pos)
        pos += 8
        return if (needsSwap) java.lang.Long.reverseBytes(value) else value
    }

    override fun getLong(index: Int): Long {
        checkAbsoluteBounds(index, 8)
        val value = unsafe.getLong(address + index)
        return if (needsSwap) java.lang.Long.reverseBytes(value) else value
    }

    override fun readFloat(): Float = Float.fromBits(readInt())

    override fun getFloat(index: Int): Float = Float.fromBits(getInt(index))

    override fun readDouble(): Double = Double.fromBits(readLong())

    override fun getDouble(index: Int): Double = Double.fromBits(getLong(index))

    override fun readString(length: Int, charset: Charset): String {
        val bytes = readByteArray(length)
        val javaCharset = when (charset) {
            Charset.UTF8 -> Charsets.UTF_8
            Charset.UTF16 -> Charsets.UTF_16
            Charset.UTF16BigEndian -> Charsets.UTF_16BE
            Charset.UTF16LittleEndian -> Charsets.UTF_16LE
            Charset.ASCII -> Charsets.US_ASCII
            Charset.ISOLatin1 -> Charsets.ISO_8859_1
            Charset.UTF32 -> Charsets.UTF_32
            Charset.UTF32LittleEndian -> Charsets.UTF_32LE
            Charset.UTF32BigEndian -> Charsets.UTF_32BE
        }
        return String(bytes, javaCharset)
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        checkWriteBounds(1)
        unsafe.putByte(address + pos++, byte)
        return this
    }

    override fun set(index: Int, byte: Byte): WriteBuffer {
        checkAbsoluteBounds(index, 1)
        unsafe.putByte(address + index, byte)
        return this
    }

    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int): WriteBuffer {
        checkWriteBounds(length)
        unsafe.copyMemory(bytes, BYTE_ARRAY_OFFSET + offset, null, address + pos, length.toLong())
        pos += length
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        checkWriteBounds(2)
        val value = if (needsSwap) java.lang.Short.reverseBytes(short) else short
        unsafe.putShort(address + pos, value)
        pos += 2
        return this
    }

    override fun set(index: Int, short: Short): WriteBuffer {
        checkAbsoluteBounds(index, 2)
        val value = if (needsSwap) java.lang.Short.reverseBytes(short) else short
        unsafe.putShort(address + index, value)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        checkWriteBounds(4)
        val value = if (needsSwap) java.lang.Integer.reverseBytes(int) else int
        unsafe.putInt(address + pos, value)
        pos += 4
        return this
    }

    override fun set(index: Int, int: Int): WriteBuffer {
        checkAbsoluteBounds(index, 4)
        val value = if (needsSwap) java.lang.Integer.reverseBytes(int) else int
        unsafe.putInt(address + index, value)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        checkWriteBounds(8)
        val value = if (needsSwap) java.lang.Long.reverseBytes(long) else long
        unsafe.putLong(address + pos, value)
        pos += 8
        return this
    }

    override fun set(index: Int, long: Long): WriteBuffer {
        checkAbsoluteBounds(index, 8)
        val value = if (needsSwap) java.lang.Long.reverseBytes(long) else long
        unsafe.putLong(address + index, value)
        return this
    }

    override fun writeFloat(float: Float): WriteBuffer = writeInt(float.toRawBits())

    override fun set(index: Int, float: Float): WriteBuffer = set(index, float.toRawBits())

    override fun writeDouble(double: Double): WriteBuffer = writeLong(double.toRawBits())

    override fun set(index: Int, double: Double): WriteBuffer = set(index, double.toRawBits())

    override fun writeString(text: CharSequence, charset: Charset): WriteBuffer {
        val javaCharset = when (charset) {
            Charset.UTF8 -> Charsets.UTF_8
            Charset.UTF16 -> Charsets.UTF_16
            Charset.UTF16BigEndian -> Charsets.UTF_16BE
            Charset.UTF16LittleEndian -> Charsets.UTF_16LE
            Charset.ASCII -> Charsets.US_ASCII
            Charset.ISOLatin1 -> Charsets.ISO_8859_1
            Charset.UTF32 -> Charsets.UTF_32
            Charset.UTF32LittleEndian -> Charsets.UTF_32LE
            Charset.UTF32BigEndian -> Charsets.UTF_32BE
        }
        val bytes = text.toString().toByteArray(javaCharset)
        return writeBytes(bytes)
    }

    override fun write(buffer: ReadBuffer) {
        val bytes = buffer.readByteArray(buffer.remaining())
        writeBytes(bytes)
    }

    override suspend fun close() {
        if (!closed && !isSlice) {
            unsafe.freeMemory(address)
            closed = true
        }
    }

    private fun checkReadBounds(size: Int) {
        if (pos + size > lim) {
            throw IndexOutOfBoundsException("Read of $size bytes at position $pos exceeds limit $lim")
        }
    }

    private fun checkWriteBounds(size: Int) {
        if (pos + size > lim) {
            throw IndexOutOfBoundsException("Write of $size bytes at position $pos exceeds limit $lim")
        }
    }

    private fun checkAbsoluteBounds(index: Int, size: Int) {
        if (index < 0 || index + size > capacity) {
            throw IndexOutOfBoundsException("Access of $size bytes at index $index exceeds capacity $capacity")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is PlatformBuffer) return false
        if (position() != other.position()) return false
        if (limit() != other.limit()) return false
        if (capacity != other.capacity) return false
        return true
    }

    override fun hashCode(): Int {
        var result = capacity
        result = 31 * result + pos
        result = 31 * result + lim
        return result
    }

    override fun toString() = "UnsafeBuffer[pos=$pos lim=$lim cap=$capacity]"

    companion object {
        private val unsafe: Unsafe = run {
            val field: Field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as Unsafe
        }

        private val BYTE_ARRAY_OFFSET: Long = unsafe.arrayBaseOffset(ByteArray::class.java).toLong()

        private val NATIVE_ORDER: ByteOrder =
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN) {
                ByteOrder.BIG_ENDIAN
            } else {
                ByteOrder.LITTLE_ENDIAN
            }

        fun allocate(size: Int, byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN): UnsafeBuffer {
            val address = unsafe.allocateMemory(size.toLong())
            unsafe.setMemory(address, size.toLong(), 0)
            return UnsafeBuffer(size, byteOrder, address)
        }
    }
}
