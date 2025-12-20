@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package com.ditchoom.buffer

import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * WASM-optimized implementation that uses native linear memory operations.
 * Uses withScopedMemoryAllocator for allocation and Pointer.loadInt()/storeInt()
 * for direct WASM instruction access (i32.load, i32.store, etc.).
 */
actual inline fun <R> withUnsafeBuffer(
    size: Int,
    byteOrder: ByteOrder,
    block: (UnsafeBuffer) -> R,
): R = withScopedMemoryAllocator { allocator ->
    val pointer = allocator.allocate(size)
    // Zero the memory
    for (i in 0 until size) {
        (pointer + i).storeByte(0)
    }
    val buffer = WasmNativeUnsafeBuffer(size, byteOrder, pointer)
    block(buffer)
}

/**
 * WASM-native buffer implementation using Pointer operations.
 * This compiles to native WASM i32.load/i32.store instructions.
 */
@PublishedApi
internal class WasmNativeUnsafeBuffer(
    val capacity: Int,
    override val byteOrder: ByteOrder,
    private val basePointer: Pointer,
) : UnsafeBuffer {
    private var pos: Int = 0
    private var lim: Int = capacity
    private val needsSwap: Boolean = byteOrder != ByteOrder.LITTLE_ENDIAN // WASM is little-endian

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
        return (basePointer + pos++).loadByte()
    }

    override fun get(index: Int): Byte {
        checkAbsoluteBounds(index, 1)
        return (basePointer + index).loadByte()
    }

    override fun readByteArray(size: Int): ByteArray {
        checkReadBounds(size)
        val array = ByteArray(size)
        for (i in 0 until size) {
            array[i] = (basePointer + pos + i).loadByte()
        }
        pos += size
        return array
    }

    override fun slice(): ReadBuffer {
        // For slicing, we create a new buffer pointing to the current position
        val sliceSize = lim - pos
        return WasmNativeUnsafeBuffer(sliceSize, byteOrder, basePointer + pos)
    }

    override fun readShort(): Short {
        checkReadBounds(2)
        val value = (basePointer + pos).loadShort()
        pos += 2
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun getShort(index: Int): Short {
        checkAbsoluteBounds(index, 2)
        val value = (basePointer + index).loadShort()
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun readInt(): Int {
        checkReadBounds(4)
        val value = (basePointer + pos).loadInt()
        pos += 4
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun getInt(index: Int): Int {
        checkAbsoluteBounds(index, 4)
        val value = (basePointer + index).loadInt()
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun readLong(): Long {
        checkReadBounds(8)
        val value = (basePointer + pos).loadLong()
        pos += 8
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun getLong(index: Int): Long {
        checkAbsoluteBounds(index, 8)
        val value = (basePointer + index).loadLong()
        return if (needsSwap) value.reverseBytes() else value
    }

    override fun readFloat(): Float {
        checkReadBounds(4)
        val bits = (basePointer + pos).loadInt()
        pos += 4
        return Float.fromBits(if (needsSwap) bits.reverseBytes() else bits)
    }

    override fun getFloat(index: Int): Float {
        checkAbsoluteBounds(index, 4)
        val bits = (basePointer + index).loadInt()
        return Float.fromBits(if (needsSwap) bits.reverseBytes() else bits)
    }

    override fun readDouble(): Double {
        checkReadBounds(8)
        val bits = (basePointer + pos).loadLong()
        pos += 8
        return Double.fromBits(if (needsSwap) bits.reverseBytes() else bits)
    }

    override fun getDouble(index: Int): Double {
        checkAbsoluteBounds(index, 8)
        val bits = (basePointer + index).loadLong()
        return Double.fromBits(if (needsSwap) bits.reverseBytes() else bits)
    }

    override fun readString(length: Int, charset: Charset): String {
        val bytes = readByteArray(length)
        return bytes.decodeToString()
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        checkWriteBounds(1)
        (basePointer + pos++).storeByte(byte)
        return this
    }

    override fun set(index: Int, byte: Byte): WriteBuffer {
        checkAbsoluteBounds(index, 1)
        (basePointer + index).storeByte(byte)
        return this
    }

    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int): WriteBuffer {
        checkWriteBounds(length)
        for (i in 0 until length) {
            (basePointer + pos + i).storeByte(bytes[offset + i])
        }
        pos += length
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        checkWriteBounds(2)
        val value = if (needsSwap) short.reverseBytes() else short
        (basePointer + pos).storeShort(value)
        pos += 2
        return this
    }

    override fun set(index: Int, short: Short): WriteBuffer {
        checkAbsoluteBounds(index, 2)
        val value = if (needsSwap) short.reverseBytes() else short
        (basePointer + index).storeShort(value)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        checkWriteBounds(4)
        val value = if (needsSwap) int.reverseBytes() else int
        (basePointer + pos).storeInt(value)
        pos += 4
        return this
    }

    override fun set(index: Int, int: Int): WriteBuffer {
        checkAbsoluteBounds(index, 4)
        val value = if (needsSwap) int.reverseBytes() else int
        (basePointer + index).storeInt(value)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        checkWriteBounds(8)
        val value = if (needsSwap) long.reverseBytes() else long
        (basePointer + pos).storeLong(value)
        pos += 8
        return this
    }

    override fun set(index: Int, long: Long): WriteBuffer {
        checkAbsoluteBounds(index, 8)
        val value = if (needsSwap) long.reverseBytes() else long
        (basePointer + index).storeLong(value)
        return this
    }

    override fun writeFloat(float: Float): WriteBuffer {
        checkWriteBounds(4)
        val bits = float.toRawBits()
        val value = if (needsSwap) bits.reverseBytes() else bits
        (basePointer + pos).storeInt(value)
        pos += 4
        return this
    }

    override fun set(index: Int, float: Float): WriteBuffer {
        checkAbsoluteBounds(index, 4)
        val bits = float.toRawBits()
        val value = if (needsSwap) bits.reverseBytes() else bits
        (basePointer + index).storeInt(value)
        return this
    }

    override fun writeDouble(double: Double): WriteBuffer {
        checkWriteBounds(8)
        val bits = double.toRawBits()
        val value = if (needsSwap) bits.reverseBytes() else bits
        (basePointer + pos).storeLong(value)
        pos += 8
        return this
    }

    override fun set(index: Int, double: Double): WriteBuffer {
        checkAbsoluteBounds(index, 8)
        val bits = double.toRawBits()
        val value = if (needsSwap) bits.reverseBytes() else bits
        (basePointer + index).storeLong(value)
        return this
    }

    override fun writeString(text: CharSequence, charset: Charset): WriteBuffer {
        val bytes = text.toString().encodeToByteArray()
        return writeBytes(bytes)
    }

    override fun write(buffer: ReadBuffer) {
        val bytes = buffer.readByteArray(buffer.remaining())
        writeBytes(bytes)
    }

    override suspend fun close() {
        // Memory is automatically freed when withScopedMemoryAllocator scope exits
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
        if (this === other) return true
        if (other !is WasmNativeUnsafeBuffer) return false
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

    override fun toString() = "WasmNativeUnsafeBuffer[pos=$pos lim=$lim cap=$capacity]"
}
