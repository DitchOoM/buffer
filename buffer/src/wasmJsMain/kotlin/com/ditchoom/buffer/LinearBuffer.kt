@file:OptIn(UnsafeWasmMemoryApi::class)

package com.ditchoom.buffer

import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi

/**
 * Buffer implementation using WASM linear memory with native Pointer operations.
 *
 * This provides zero-copy interop with JavaScript - both WASM (via Pointer) and
 * JS (via DataView on the same memory) can access the same underlying bytes.
 *
 * All read/write operations compile to native WASM i32.load/i32.store instructions.
 */
class LinearBuffer(
    private val baseOffset: Int,
    capacity: Int,
    byteOrder: ByteOrder,
) : BaseWebBuffer(capacity, byteOrder) {
    /**
     * Get a Pointer to the specified offset within this buffer.
     * Pointer is a value class - no allocation, compiles to raw address arithmetic.
     */
    private fun ptr(offset: Int): Pointer = Pointer((baseOffset + offset).toUInt())

    // Native memory access using Pointer operations
    override fun loadByte(index: Int): Byte = ptr(index).loadByte()

    override fun storeByte(
        index: Int,
        value: Byte,
    ) {
        ptr(index).storeByte(value)
    }

    override fun loadShort(index: Int): Short {
        // Use Pointer for native 16-bit load
        val raw = ptr(index).loadShort()
        return if (littleEndian) {
            raw
        } else {
            // Swap bytes for big endian
            ((raw.toInt() and 0xFF) shl 8 or ((raw.toInt() shr 8) and 0xFF)).toShort()
        }
    }

    override fun storeShort(
        index: Int,
        value: Short,
    ) {
        val toStore =
            if (littleEndian) {
                value
            } else {
                ((value.toInt() and 0xFF) shl 8 or ((value.toInt() shr 8) and 0xFF)).toShort()
            }
        ptr(index).storeShort(toStore)
    }

    override fun loadInt(index: Int): Int {
        val raw = ptr(index).loadInt()
        return if (littleEndian) {
            raw
        } else {
            // Swap bytes for big endian
            reverseInt(raw)
        }
    }

    override fun storeInt(
        index: Int,
        value: Int,
    ) {
        val toStore =
            if (littleEndian) {
                value
            } else {
                reverseInt(value)
            }
        ptr(index).storeInt(toStore)
    }

    override fun loadLong(index: Int): Long {
        val raw = ptr(index).loadLong()
        return if (littleEndian) {
            raw
        } else {
            reverseLong(raw)
        }
    }

    override fun storeLong(
        index: Int,
        value: Long,
    ) {
        val toStore =
            if (littleEndian) {
                value
            } else {
                reverseLong(value)
            }
        ptr(index).storeLong(toStore)
    }

    private fun reverseInt(value: Int): Int =
        ((value and 0xFF) shl 24) or
            ((value and 0xFF00) shl 8) or
            ((value shr 8) and 0xFF00) or
            ((value shr 24) and 0xFF)

    private fun reverseLong(value: Long): Long =
        ((value and 0xFFL) shl 56) or
            ((value and 0xFF00L) shl 40) or
            ((value and 0xFF0000L) shl 24) or
            ((value and 0xFF000000L) shl 8) or
            ((value shr 8) and 0xFF000000L) or
            ((value shr 24) and 0xFF0000L) or
            ((value shr 40) and 0xFF00L) or
            ((value shr 56) and 0xFFL)

    override fun slice(): ReadBuffer {
        // Create a new LinearBuffer view of the remaining portion
        // This is zero-copy - just creates a new view with different base offset
        return LinearBuffer(
            baseOffset + positionValue,
            limitValue - positionValue,
            byteOrder,
        )
    }

    override fun readByteArray(size: Int): ByteArray {
        // Copy from linear memory to Kotlin ByteArray (Wasm GC heap)
        // This requires a copy - unavoidable due to separate memory spaces
        val result = ByteArray(size)
        for (i in 0 until size) {
            result[i] = loadByte(positionValue + i)
        }
        positionValue += size
        return result
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        // Copy from Kotlin ByteArray (Wasm GC heap) to linear memory
        for (i in 0 until length) {
            storeByte(positionValue + i, bytes[offset + i])
        }
        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        when (buffer) {
            is LinearBuffer -> {
                // Both are in linear memory - can use memory copy
                for (i in 0 until size) {
                    storeByte(positionValue + i, buffer.loadByte(buffer.positionValue + i))
                }
            }
            else -> {
                // Fallback for other buffer types
                writeBytes(buffer.readByteArray(size))
                return // readByteArray already advances position
            }
        }
        positionValue += size
        buffer.position(buffer.position() + size)
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String =
        when (charset) {
            Charset.UTF8 -> {
                val bytes = readByteArray(length)
                bytes.decodeToString()
            }
            else -> throw UnsupportedOperationException("LinearBuffer only supports UTF8 charset. Got: $charset")
        }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        when (charset) {
            Charset.UTF8 -> writeBytes(text.toString().encodeToByteArray())
            else -> throw UnsupportedOperationException("LinearBuffer only supports UTF8 charset. Got: $charset")
        }
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinearBuffer) return false

        if (byteOrder != other.byteOrder) return false
        if (positionValue != other.positionValue) return false
        if (limitValue != other.limitValue) return false
        if (capacity != other.capacity) return false

        // Compare contents
        val size = remaining()
        for (i in 0 until size) {
            if (loadByte(positionValue + i) != other.loadByte(other.positionValue + i)) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var result = byteOrder.hashCode()
        result = 31 * result + positionValue
        result = 31 * result + limitValue
        result = 31 * result + capacity

        val size = remaining()
        for (i in 0 until size) {
            result = 31 * result + loadByte(positionValue + i).hashCode()
        }
        return result
    }
}
