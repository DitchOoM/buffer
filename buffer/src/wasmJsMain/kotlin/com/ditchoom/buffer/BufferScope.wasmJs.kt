@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi

/**
 * Tracks scope nesting depth to know when to reset the allocator.
 * When depth returns to 0, all scopes have closed and we can reclaim memory.
 */
@PublishedApi
internal object WasmScopeTracker {
    var depth = 0
}

/**
 * WASM implementation of [withScope].
 *
 * Uses LinearMemoryAllocator for memory management with Pointer operations.
 * Memory is reclaimed when the outermost scope closes.
 */
actual inline fun <T> withScope(block: (BufferScope) -> T): T {
    WasmScopeTracker.depth++
    val scope = WasmBufferScope()
    return try {
        block(scope)
    } finally {
        scope.close()
        WasmScopeTracker.depth--
        // Reset allocator when all scopes have closed
        if (WasmScopeTracker.depth == 0) {
            LinearMemoryAllocator.reset()
        }
    }
}

/**
 * WASM BufferScope implementation using LinearMemoryAllocator.
 */
class WasmBufferScope : BufferScope {
    private val allocations = mutableListOf<WasmScopedBuffer>()
    private var open = true

    override val isOpen: Boolean get() = open

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): ScopedBuffer {
        check(open) { "BufferScope is closed" }
        val (offset, _) = LinearMemoryAllocator.allocate(size)
        val buffer = WasmScopedBuffer(this, offset, size, byteOrder)
        allocations.add(buffer)
        return buffer
    }

    override fun allocateAligned(
        size: Int,
        alignment: Int,
        byteOrder: ByteOrder,
    ): ScopedBuffer {
        check(open) { "BufferScope is closed" }
        require(alignment > 0 && (alignment and (alignment - 1)) == 0) {
            "Alignment must be a positive power of 2, got: $alignment"
        }
        val extraForAlignment = if (alignment > 8) alignment - 1 else 0
        val (offset, _) = LinearMemoryAllocator.allocate(size + extraForAlignment)
        val alignedOffset =
            if (extraForAlignment > 0) {
                (offset + alignment - 1) and (alignment - 1).inv()
            } else {
                offset
            }
        val buffer = WasmScopedBuffer(this, alignedOffset, size, byteOrder)
        allocations.add(buffer)
        return buffer
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
 * WASM ScopedBuffer implementation using Pointer operations.
 */
class WasmScopedBuffer(
    override val scope: BufferScope,
    private val baseOffset: Int,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
) : ScopedBuffer {
    private var positionValue: Int = 0
    private var limitValue: Int = capacity
    private var valid = true

    override val nativeAddress: Long get() = baseOffset.toLong()
    override val nativeSize: Long get() = capacity.toLong()

    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN

    internal fun invalidate() {
        valid = false
    }

    private fun checkValid() {
        check(valid) { "ScopedBuffer is no longer valid - scope has been closed" }
    }

    private fun ptr(offset: Int): Pointer = Pointer((baseOffset + offset).toUInt())

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
        return ptr(positionValue++).loadByte()
    }

    override fun readShort(): Short {
        checkValid()
        val raw = ptr(positionValue).loadShort()
        positionValue += 2
        return if (littleEndian) raw else raw.reverseBytes()
    }

    override fun readInt(): Int {
        checkValid()
        val raw = ptr(positionValue).loadInt()
        positionValue += 4
        return if (littleEndian) raw else raw.reverseBytes()
    }

    override fun readLong(): Long {
        checkValid()
        val raw = ptr(positionValue).loadLong()
        positionValue += 8
        return if (littleEndian) raw else raw.reverseBytes()
    }

    // Read operations (absolute)
    override fun get(index: Int): Byte {
        checkValid()
        return ptr(index).loadByte()
    }

    override fun getShort(index: Int): Short {
        checkValid()
        val raw = ptr(index).loadShort()
        return if (littleEndian) raw else raw.reverseBytes()
    }

    override fun getInt(index: Int): Int {
        checkValid()
        val raw = ptr(index).loadInt()
        return if (littleEndian) raw else raw.reverseBytes()
    }

    override fun getLong(index: Int): Long {
        checkValid()
        val raw = ptr(index).loadLong()
        return if (littleEndian) raw else raw.reverseBytes()
    }

    // Write operations (relative)
    override fun writeByte(byte: Byte): WriteBuffer {
        checkValid()
        ptr(positionValue++).storeByte(byte)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        checkValid()
        val value = if (littleEndian) short else short.reverseBytes()
        ptr(positionValue).storeShort(value)
        positionValue += 2
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        checkValid()
        val value = if (littleEndian) int else int.reverseBytes()
        ptr(positionValue).storeInt(value)
        positionValue += 4
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        checkValid()
        val value = if (littleEndian) long else long.reverseBytes()
        ptr(positionValue).storeLong(value)
        positionValue += 8
        return this
    }

    // Write operations (absolute)
    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        checkValid()
        ptr(index).storeByte(byte)
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        checkValid()
        val value = if (littleEndian) short else short.reverseBytes()
        ptr(index).storeShort(value)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        checkValid()
        val value = if (littleEndian) int else int.reverseBytes()
        ptr(index).storeInt(value)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        checkValid()
        val value = if (littleEndian) long else long.reverseBytes()
        ptr(index).storeLong(value)
        return this
    }

    // Bulk operations
    override fun readByteArray(size: Int): ByteArray {
        checkValid()
        val array = ByteArray(size)
        for (i in 0 until size) {
            array[i] = ptr(positionValue + i).loadByte()
        }
        positionValue += size
        return array
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkValid()
        for (i in 0 until length) {
            ptr(positionValue + i).storeByte(bytes[offset + i])
        }
        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        if (buffer is WasmScopedBuffer) {
            UnsafeMemory.copyMemory(
                buffer.nativeAddress + buffer.positionValue,
                nativeAddress + positionValue,
                size.toLong(),
            )
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
        return WasmScopedBuffer(scope, baseOffset + positionValue, remaining(), byteOrder)
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

// Helper functions for byte swapping
private fun Short.reverseBytes(): Short = (((this.toInt() and 0xFF) shl 8) or ((this.toInt() ushr 8) and 0xFF)).toShort()

private fun Int.reverseBytes(): Int =
    (this ushr 24) or
        ((this ushr 8) and 0xFF00) or
        ((this shl 8) and 0xFF0000) or
        (this shl 24)

private fun Long.reverseBytes(): Long =
    (this ushr 56) or
        ((this ushr 40) and 0xFF00L) or
        ((this ushr 24) and 0xFF0000L) or
        ((this ushr 8) and 0xFF000000L) or
        ((this shl 8) and 0xFF00000000L) or
        ((this shl 24) and 0xFF0000000000L) or
        ((this shl 40) and 0xFF000000000000L) or
        (this shl 56)
