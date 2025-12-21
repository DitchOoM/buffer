package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.DefaultUnsafeBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.UnsafeMemory
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.withLock

/**
 * High-performance buffer pool using UnsafeBuffer for direct memory access.
 *
 * WARNING: Buffers MUST be released back to the pool to avoid memory leaks.
 * Unlike SafeBufferPool, unreleased buffers will leak native memory.
 */
internal class UnsafeBufferPoolImpl(
    initialPoolSize: Int,
    private val maxPoolSize: Int,
    private val defaultBufferSize: Int,
    private val byteOrder: ByteOrder,
) : BufferPool {
    private val pool = ArrayDeque<UnsafePooledBufferImpl>(initialPoolSize)

    private var totalAllocations = 0L
    private var poolHits = 0L
    private var poolMisses = 0L
    private var peakPoolSize = 0

    override fun acquire(minSize: Int): PooledBuffer {
        totalAllocations++
        val size = maxOf(minSize, defaultBufferSize)

        // Try to get from pool
        val buffer =
            withLock(pool) {
                pool.removeLastOrNull()
            }

        return if (buffer != null && buffer.capacity >= size) {
            poolHits++
            buffer.resetForWrite()
            buffer.markAcquired()
            buffer
        } else {
            poolMisses++
            // Free the smaller buffer if we got one
            buffer?.freeMemory()
            // Allocate new buffer
            UnsafePooledBufferImpl(size, byteOrder, this)
        }
    }

    override fun release(buffer: PooledBuffer) {
        if (buffer !is UnsafePooledBufferImpl) return
        if (!buffer.isAcquired) return

        withLock(pool) {
            if (pool.size < maxPoolSize) {
                buffer.resetForWrite()
                buffer.markReleased()
                pool.addLast(buffer)
                if (pool.size > peakPoolSize) {
                    peakPoolSize = pool.size
                }
            } else {
                // Pool is full, free the memory
                buffer.freeMemory()
            }
        }
    }

    override fun stats(): PoolStats =
        PoolStats(
            totalAllocations = totalAllocations,
            poolHits = poolHits,
            poolMisses = poolMisses,
            currentPoolSize = pool.size,
            peakPoolSize = peakPoolSize,
        )

    override fun clear() {
        withLock(pool) {
            pool.forEach { it.freeMemory() }
            pool.clear()
        }
    }
}

private class UnsafePooledBufferImpl(
    override val capacity: Int,
    override val byteOrder: ByteOrder,
    private val pool: UnsafeBufferPoolImpl,
) : PooledBuffer {
    private val address: Long = UnsafeMemory.allocate(capacity)
    private var pos: Int = 0
    private var lim: Int = capacity
    private val needsSwap: Boolean = byteOrder != UnsafeMemory.nativeByteOrder

    var isAcquired: Boolean = true
        private set

    fun markAcquired() {
        isAcquired = true
    }

    fun markReleased() {
        isAcquired = false
    }

    fun freeMemory() {
        UnsafeMemory.free(address)
    }

    override fun release() = pool.release(this)

    // Position/limit management
    override fun resetForRead() {
        lim = pos
        pos = 0
    }

    override fun resetForWrite() {
        pos = 0
        lim = capacity
    }

    override fun limit(): Int = lim

    override fun position(): Int = pos

    override fun position(newPosition: Int) {
        pos = newPosition
    }

    override fun setLimit(limit: Int) {
        lim = limit
    }

    // Read operations
    override fun readByte(): Byte {
        checkReadBounds(1)
        return UnsafeMemory.getByte(address, pos++)
    }

    override fun get(index: Int): Byte = UnsafeMemory.getByte(address, index)

    override fun readByteArray(size: Int): ByteArray {
        checkReadBounds(size)
        val array = ByteArray(size)
        UnsafeMemory.copyToArray(address, pos, array, 0, size)
        pos += size
        return array
    }

    override fun readShort(): Short {
        checkReadBounds(2)
        val value = UnsafeMemory.getShort(address, pos)
        pos += 2
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun getShort(index: Int): Short {
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
        val bits = UnsafeMemory.getLong(address, index)
        return Double.fromBits(if (needsSwap) bits.reverseBytes() else bits)
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val bytes = readByteArray(length)
        return bytes.decodeToString()
    }

    override fun slice(): ReadBuffer {
        // Create a view - note: this doesn't participate in pooling
        val sliceBuffer = DefaultUnsafeBuffer.allocate(lim - pos, byteOrder)
        val bytes = readByteArray(lim - pos)
        position(pos - bytes.size) // Reset position
        sliceBuffer.writeBytes(bytes)
        sliceBuffer.resetForRead()
        return sliceBuffer
    }

    // Write operations
    override fun writeByte(byte: Byte): WriteBuffer {
        checkWriteBounds(1)
        UnsafeMemory.putByte(address, pos++, byte)
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
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
        val bits = double.toRawBits()
        val value = if (needsSwap) bits.reverseBytes() else bits
        UnsafeMemory.putLong(address, index, value)
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        val bytes = text.toString().encodeToByteArray()
        return writeBytes(bytes)
    }

    override fun write(buffer: ReadBuffer) {
        val bytes = buffer.readByteArray(buffer.remaining())
        writeBytes(bytes)
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
