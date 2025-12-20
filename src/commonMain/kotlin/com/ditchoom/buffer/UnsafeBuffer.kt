package com.ditchoom.buffer

/**
 * A high-performance buffer interface using platform-specific unsafe memory operations.
 *
 * This buffer provides:
 * - Fast allocation via direct memory access (no GC pressure on JVM/Native)
 * - Native-speed read/write operations for all primitive types
 * - Zero-copy slicing
 *
 * Note: This buffer implements ReadBuffer, WriteBuffer, and SuspendCloseable but NOT
 * PlatformBuffer, because it cannot support Parcelable on Android (native memory addresses
 * are not valid across process boundaries).
 *
 * Warning: Memory must be explicitly freed by calling [close]. Failure to do so will
 * result in memory leaks on JVM/Android/Native platforms.
 *
 * Recommended usage: Use [withUnsafeBuffer] for scoped allocation with automatic cleanup.
 */
interface UnsafeBuffer :
    ReadBuffer,
    WriteBuffer,
    SuspendCloseable

/**
 * Standard implementation of UnsafeBuffer using UnsafeMemory operations.
 * Used by JVM, Android, JS, and Native platforms.
 */
class DefaultUnsafeBuffer private constructor(
    val capacity: Int,
    override val byteOrder: ByteOrder,
    @PublishedApi internal val address: Long,
    private val isSlice: Boolean = false,
) : UnsafeBuffer {
    private var pos: Int = 0
    private var lim: Int = capacity
    private var closed: Boolean = false
    private val needsSwap: Boolean = byteOrder != UnsafeMemory.nativeByteOrder

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
        return UnsafeMemory.getByte(address, pos++)
    }

    override fun get(index: Int): Byte {
        checkAbsoluteBounds(index, 1)
        return UnsafeMemory.getByte(address, index)
    }

    override fun readByteArray(size: Int): ByteArray {
        checkReadBounds(size)
        val array = ByteArray(size)
        UnsafeMemory.copyToArray(address, pos, array, 0, size)
        pos += size
        return array
    }

    override fun slice(): ReadBuffer {
        val sliceSize = lim - pos
        return DefaultUnsafeBuffer(sliceSize, byteOrder, address + pos, isSlice = true)
    }

    override fun readShort(): Short {
        checkReadBounds(2)
        val value = UnsafeMemory.getShort(address, pos)
        pos += 2
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun getShort(index: Int): Short {
        checkAbsoluteBounds(index, 2)
        val value = UnsafeMemory.getShort(address, index)
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun readInt(): Int {
        checkReadBounds(4)
        val value = UnsafeMemory.getInt(address, pos)
        pos += 4
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun getInt(index: Int): Int {
        checkAbsoluteBounds(index, 4)
        val value = UnsafeMemory.getInt(address, index)
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun readLong(): Long {
        checkReadBounds(8)
        val value = UnsafeMemory.getLong(address, pos)
        pos += 8
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun getLong(index: Int): Long {
        checkAbsoluteBounds(index, 8)
        val value = UnsafeMemory.getLong(address, index)
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun readFloat(): Float {
        checkReadBounds(4)
        val bits = UnsafeMemory.getInt(address, pos)
        pos += 4
        return Float.fromBits(if (needsSwap) bits.reverseBytes() else bits)
    }

    override fun getFloat(index: Int): Float {
        checkAbsoluteBounds(index, 4)
        val bits = UnsafeMemory.getInt(address, index)
        return Float.fromBits(if (needsSwap) bits.reverseBytes() else bits)
    }

    override fun readDouble(): Double {
        checkReadBounds(8)
        val bits = UnsafeMemory.getLong(address, pos)
        pos += 8
        return Double.fromBits(if (needsSwap) bits.reverseBytes() else bits)
    }

    override fun getDouble(index: Int): Double {
        checkAbsoluteBounds(index, 8)
        val bits = UnsafeMemory.getLong(address, index)
        return Double.fromBits(if (needsSwap) bits.reverseBytes() else bits)
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val bytes = readByteArray(length)
        return bytes.decodeToString(charset)
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        checkWriteBounds(1)
        UnsafeMemory.putByte(address, pos++, byte)
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        checkAbsoluteBounds(index, 1)
        UnsafeMemory.putByte(address, index, byte)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkWriteBounds(length)
        UnsafeMemory.copyFromArray(bytes, offset, address, pos, length)
        pos += length
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        checkWriteBounds(2)
        val value = if (needsSwap) short.reverseBytes() else short
        UnsafeMemory.putShort(address, pos, value)
        pos += 2
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        checkAbsoluteBounds(index, 2)
        val value = if (needsSwap) short.reverseBytes() else short
        UnsafeMemory.putShort(address, index, value)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        checkWriteBounds(4)
        val value = if (needsSwap) int.reverseBytes() else int
        UnsafeMemory.putInt(address, pos, value)
        pos += 4
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        checkAbsoluteBounds(index, 4)
        val value = if (needsSwap) int.reverseBytes() else int
        UnsafeMemory.putInt(address, index, value)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        checkWriteBounds(8)
        val value = if (needsSwap) long.reverseBytes() else long
        UnsafeMemory.putLong(address, pos, value)
        pos += 8
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        checkAbsoluteBounds(index, 8)
        val value = if (needsSwap) long.reverseBytes() else long
        UnsafeMemory.putLong(address, index, value)
        return this
    }

    override fun writeFloat(float: Float): WriteBuffer {
        checkWriteBounds(4)
        val bits = float.toRawBits()
        val value = if (needsSwap) bits.reverseBytes() else bits
        UnsafeMemory.putInt(address, pos, value)
        pos += 4
        return this
    }

    override fun set(
        index: Int,
        float: Float,
    ): WriteBuffer {
        checkAbsoluteBounds(index, 4)
        val bits = float.toRawBits()
        val value = if (needsSwap) bits.reverseBytes() else bits
        UnsafeMemory.putInt(address, index, value)
        return this
    }

    override fun writeDouble(double: Double): WriteBuffer {
        checkWriteBounds(8)
        val bits = double.toRawBits()
        val value = if (needsSwap) bits.reverseBytes() else bits
        UnsafeMemory.putLong(address, pos, value)
        pos += 8
        return this
    }

    override fun set(
        index: Int,
        double: Double,
    ): WriteBuffer {
        checkAbsoluteBounds(index, 8)
        val bits = double.toRawBits()
        val value = if (needsSwap) bits.reverseBytes() else bits
        UnsafeMemory.putLong(address, index, value)
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        val bytes = text.toString().encodeToByteArray(charset)
        return writeBytes(bytes)
    }

    override fun write(buffer: ReadBuffer) {
        val bytes = buffer.readByteArray(buffer.remaining())
        writeBytes(bytes)
    }

    override suspend fun close() {
        if (!closed && !isSlice) {
            UnsafeMemory.free(address)
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

    private fun checkAbsoluteBounds(
        index: Int,
        size: Int,
    ) {
        if (index < 0 || index + size > capacity) {
            throw IndexOutOfBoundsException("Access of $size bytes at index $index exceeds capacity $capacity")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DefaultUnsafeBuffer) return false
        if (position() != other.position()) return false
        if (limit() != other.limit()) return false
        if (capacity != other.capacity) return false
        if (address != other.address) return false
        return true
    }

    override fun hashCode(): Int {
        var result = capacity
        result = 31 * result + pos
        result = 31 * result + lim
        return result
    }

    override fun toString() = "DefaultUnsafeBuffer[pos=$pos lim=$lim cap=$capacity]"

    companion object {
        fun allocate(
            size: Int,
            byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
        ): DefaultUnsafeBuffer {
            val address = UnsafeMemory.allocate(size)
            return DefaultUnsafeBuffer(size, byteOrder, address)
        }

        /**
         * Creates an UnsafeBuffer, executes the given block with it, and ensures
         * the buffer is properly closed when the block completes.
         *
         * This is the recommended way to use UnsafeBuffer as it guarantees
         * proper memory cleanup and works well across all platforms including WASM.
         *
         * @param size The size of the buffer to allocate
         * @param byteOrder The byte order for multi-byte operations
         * @param block The code block to execute with the buffer
         * @return The result of the block
         */
        inline fun <R> withBuffer(
            size: Int,
            byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
            block: (UnsafeBuffer) -> R,
        ): R {
            val buffer = allocate(size, byteOrder)
            return try {
                block(buffer)
            } finally {
                // Note: close() is suspend, but we use a non-suspend wrapper here
                // for the common use case. For suspend contexts, use allocate() directly.
                UnsafeMemory.free(buffer.address)
            }
        }
    }
}

private fun ByteArray.decodeToString(charset: Charset): String =
    when (charset) {
        Charset.UTF8 -> decodeToString()
        else -> decodeToString() // TODO: Add proper charset support
    }

private fun String.encodeToByteArray(charset: Charset): ByteArray =
    when (charset) {
        Charset.UTF8 -> encodeToByteArray()
        else -> encodeToByteArray() // TODO: Add proper charset support
    }
