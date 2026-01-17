@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import platform.posix.free
import platform.posix.malloc
import platform.posix.posix_memalign

/**
 * Native implementation of [withScope].
 *
 * Uses malloc/free for memory management with pointer tracking.
 */
actual inline fun <T> withScope(block: (BufferScope) -> T): T {
    val scope = NativeBufferScope()
    return try {
        block(scope)
    } finally {
        scope.close()
    }
}

/**
 * Native BufferScope implementation using malloc/free.
 */
class NativeBufferScope : BufferScope {
    private val allocations = mutableListOf<CPointer<ByteVar>>()
    private var open = true

    override val isOpen: Boolean get() = open

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): ScopedBuffer {
        check(open) { "BufferScope is closed" }
        val ptr =
            malloc(size.convert())?.reinterpret<ByteVar>()
                ?: throw OutOfMemoryError("Failed to allocate $size bytes")
        allocations.add(ptr)
        return NativeScopedBuffer(this, ptr, size, byteOrder)
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

        val ptr =
            memScoped {
                val ptrHolder = alloc<COpaquePointerVar>()
                val result = posix_memalign(ptrHolder.ptr, alignment.convert(), size.convert())
                if (result != 0) {
                    throw OutOfMemoryError("Failed to allocate $size bytes with alignment $alignment")
                }
                ptrHolder.value?.reinterpret<ByteVar>()
                    ?: throw OutOfMemoryError("posix_memalign returned null")
            }
        allocations.add(ptr)
        return NativeScopedBuffer(this, ptr, size, byteOrder)
    }

    override fun close() {
        if (open) {
            open = false
            for (ptr in allocations) {
                free(ptr)
            }
            allocations.clear()
        }
    }
}

/**
 * Native ScopedBuffer implementation using CPointer.
 */
class NativeScopedBuffer(
    override val scope: BufferScope,
    private val ptr: CPointer<ByteVar>,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
) : ScopedBuffer {
    private var positionValue: Int = 0
    private var limitValue: Int = capacity

    override val nativeAddress: Long get() = ptr.toLong()
    override val nativeSize: Long get() = capacity.toLong()

    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN

    // Native byte order is little-endian on all modern platforms (x86, ARM, etc.)
    private val nativeIsLittleEndian = true

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
    override fun readByte(): Byte = ptr[positionValue++]

    override fun readShort(): Short {
        val result = getShort(positionValue)
        positionValue += 2
        return result
    }

    override fun readInt(): Int {
        val result = getInt(positionValue)
        positionValue += 4
        return result
    }

    override fun readLong(): Long {
        val result = getLong(positionValue)
        positionValue += 8
        return result
    }

    // Read operations (absolute)
    override fun get(index: Int): Byte = ptr[index]

    override fun getShort(index: Int): Short {
        val raw = UnsafeMemory.getShort(nativeAddress + index)
        return if (nativeIsLittleEndian) {
            if (littleEndian) raw else raw.reverseBytes()
        } else {
            if (littleEndian) raw.reverseBytes() else raw
        }
    }

    override fun getInt(index: Int): Int {
        val raw = UnsafeMemory.getInt(nativeAddress + index)
        return if (nativeIsLittleEndian) {
            if (littleEndian) raw else raw.reverseBytes()
        } else {
            if (littleEndian) raw.reverseBytes() else raw
        }
    }

    override fun getLong(index: Int): Long {
        val raw = UnsafeMemory.getLong(nativeAddress + index)
        return if (nativeIsLittleEndian) {
            if (littleEndian) raw else raw.reverseBytes()
        } else {
            if (littleEndian) raw.reverseBytes() else raw
        }
    }

    // Write operations (relative)
    override fun writeByte(byte: Byte): WriteBuffer {
        ptr[positionValue++] = byte
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        set(positionValue, short)
        positionValue += 2
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        set(positionValue, int)
        positionValue += 4
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        set(positionValue, long)
        positionValue += 8
        return this
    }

    // Write operations (absolute)
    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        ptr[index] = byte
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        val value =
            if (nativeIsLittleEndian) {
                if (littleEndian) short else short.reverseBytes()
            } else {
                if (littleEndian) short.reverseBytes() else short
            }
        UnsafeMemory.putShort(nativeAddress + index, value)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        val value =
            if (nativeIsLittleEndian) {
                if (littleEndian) int else int.reverseBytes()
            } else {
                if (littleEndian) int.reverseBytes() else int
            }
        UnsafeMemory.putInt(nativeAddress + index, value)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        val value =
            if (nativeIsLittleEndian) {
                if (littleEndian) long else long.reverseBytes()
            } else {
                if (littleEndian) long.reverseBytes() else long
            }
        UnsafeMemory.putLong(nativeAddress + index, value)
        return this
    }

    // Bulk operations
    override fun readByteArray(size: Int): ByteArray {
        val array = ByteArray(size)
        UnsafeMemory.copyMemoryToArray(nativeAddress + positionValue, array, 0, size)
        positionValue += size
        return array
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        UnsafeMemory.copyMemoryFromArray(bytes, offset, nativeAddress + positionValue, length)
        positionValue += length
        return this
    }

    // ===== Optimized Bulk Primitive Operations =====
    // Uses long-pairs pattern: process 2 ints as 1 long to halve memory operations

    override fun writeShorts(
        shorts: ShortArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        val needsSwap = nativeIsLittleEndian != littleEndian
        var addr = nativeAddress + positionValue
        var i = offset
        val end = offset + length

        if (needsSwap) {
            // Process 4 shorts at a time as 1 long (8 bytes)
            while (i + 3 < end) {
                val s0 = shorts[i].reverseBytes().toLong() and 0xFFFFL
                val s1 = shorts[i + 1].reverseBytes().toLong() and 0xFFFFL
                val s2 = shorts[i + 2].reverseBytes().toLong() and 0xFFFFL
                val s3 = shorts[i + 3].reverseBytes().toLong() and 0xFFFFL
                UnsafeMemory.putLong(addr, s0 or (s1 shl 16) or (s2 shl 32) or (s3 shl 48))
                addr += 8
                i += 4
            }
            // Handle remaining shorts
            while (i < end) {
                UnsafeMemory.putShort(addr, shorts[i].reverseBytes())
                addr += 2
                i++
            }
        } else {
            // No swap needed - process 4 shorts at a time
            while (i + 3 < end) {
                val s0 = shorts[i].toLong() and 0xFFFFL
                val s1 = shorts[i + 1].toLong() and 0xFFFFL
                val s2 = shorts[i + 2].toLong() and 0xFFFFL
                val s3 = shorts[i + 3].toLong() and 0xFFFFL
                UnsafeMemory.putLong(addr, s0 or (s1 shl 16) or (s2 shl 32) or (s3 shl 48))
                addr += 8
                i += 4
            }
            while (i < end) {
                UnsafeMemory.putShort(addr, shorts[i])
                addr += 2
                i++
            }
        }
        positionValue += length * 2
        return this
    }

    override fun readShorts(
        dest: ShortArray,
        offset: Int,
        length: Int,
    ) {
        val needsSwap = nativeIsLittleEndian != littleEndian
        var addr = nativeAddress + positionValue
        var i = offset
        val end = offset + length

        if (needsSwap) {
            while (i + 3 < end) {
                val packed = UnsafeMemory.getLong(addr)
                dest[i] = (packed and 0xFFFFL).toShort().reverseBytes()
                dest[i + 1] = ((packed ushr 16) and 0xFFFFL).toShort().reverseBytes()
                dest[i + 2] = ((packed ushr 32) and 0xFFFFL).toShort().reverseBytes()
                dest[i + 3] = ((packed ushr 48) and 0xFFFFL).toShort().reverseBytes()
                addr += 8
                i += 4
            }
            while (i < end) {
                dest[i] = UnsafeMemory.getShort(addr).reverseBytes()
                addr += 2
                i++
            }
        } else {
            while (i + 3 < end) {
                val packed = UnsafeMemory.getLong(addr)
                dest[i] = (packed and 0xFFFFL).toShort()
                dest[i + 1] = ((packed ushr 16) and 0xFFFFL).toShort()
                dest[i + 2] = ((packed ushr 32) and 0xFFFFL).toShort()
                dest[i + 3] = ((packed ushr 48) and 0xFFFFL).toShort()
                addr += 8
                i += 4
            }
            while (i < end) {
                dest[i] = UnsafeMemory.getShort(addr)
                addr += 2
                i++
            }
        }
        positionValue += length * 2
    }

    override fun writeInts(
        ints: IntArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        val needsSwap = nativeIsLittleEndian != littleEndian
        var addr = nativeAddress + positionValue
        var i = offset
        val end = offset + length

        if (needsSwap) {
            // Process 2 ints at a time as 1 long (8 bytes)
            while (i + 1 < end) {
                val swapped0 = ints[i].reverseBytes().toLong() and 0xFFFFFFFFL
                val swapped1 = ints[i + 1].reverseBytes().toLong() and 0xFFFFFFFFL
                UnsafeMemory.putLong(addr, swapped0 or (swapped1 shl 32))
                addr += 8
                i += 2
            }
            // Handle remaining int if odd count
            if (i < end) {
                UnsafeMemory.putInt(addr, ints[i].reverseBytes())
            }
        } else {
            // No swap needed - process 2 ints at a time
            while (i + 1 < end) {
                val i0 = ints[i].toLong() and 0xFFFFFFFFL
                val i1 = ints[i + 1].toLong() and 0xFFFFFFFFL
                UnsafeMemory.putLong(addr, i0 or (i1 shl 32))
                addr += 8
                i += 2
            }
            if (i < end) {
                UnsafeMemory.putInt(addr, ints[i])
            }
        }
        positionValue += length * 4
        return this
    }

    override fun readInts(
        dest: IntArray,
        offset: Int,
        length: Int,
    ) {
        val needsSwap = nativeIsLittleEndian != littleEndian
        var addr = nativeAddress + positionValue
        var i = offset
        val end = offset + length

        if (needsSwap) {
            while (i + 1 < end) {
                val packed = UnsafeMemory.getLong(addr)
                dest[i] = (packed and 0xFFFFFFFFL).toInt().reverseBytes()
                dest[i + 1] = (packed ushr 32).toInt().reverseBytes()
                addr += 8
                i += 2
            }
            if (i < end) {
                dest[i] = UnsafeMemory.getInt(addr).reverseBytes()
            }
        } else {
            while (i + 1 < end) {
                val packed = UnsafeMemory.getLong(addr)
                dest[i] = (packed and 0xFFFFFFFFL).toInt()
                dest[i + 1] = (packed ushr 32).toInt()
                addr += 8
                i += 2
            }
            if (i < end) {
                dest[i] = UnsafeMemory.getInt(addr)
            }
        }
        positionValue += length * 4
    }

    override fun writeLongs(
        longs: LongArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        val needsSwap = nativeIsLittleEndian != littleEndian
        var addr = nativeAddress + positionValue
        var i = offset
        val end = offset + length

        if (needsSwap) {
            while (i < end) {
                UnsafeMemory.putLong(addr, longs[i].reverseBytes())
                addr += 8
                i++
            }
        } else {
            while (i < end) {
                UnsafeMemory.putLong(addr, longs[i])
                addr += 8
                i++
            }
        }
        positionValue += length * 8
        return this
    }

    override fun readLongs(
        dest: LongArray,
        offset: Int,
        length: Int,
    ) {
        val needsSwap = nativeIsLittleEndian != littleEndian
        var addr = nativeAddress + positionValue
        var i = offset
        val end = offset + length

        if (needsSwap) {
            while (i < end) {
                dest[i] = UnsafeMemory.getLong(addr).reverseBytes()
                addr += 8
                i++
            }
        } else {
            while (i < end) {
                dest[i] = UnsafeMemory.getLong(addr)
                addr += 8
                i++
            }
        }
        positionValue += length * 8
    }

    override fun writeFloats(floats: FloatArray): WriteBuffer {
        val needsSwap = nativeIsLittleEndian != littleEndian
        var addr = nativeAddress + positionValue
        var i = 0
        val end = floats.size

        if (needsSwap) {
            // Process 2 floats at a time as 1 long
            while (i + 1 < end) {
                val f0 = floats[i].toRawBits().reverseBytes().toLong() and 0xFFFFFFFFL
                val f1 = floats[i + 1].toRawBits().reverseBytes().toLong() and 0xFFFFFFFFL
                UnsafeMemory.putLong(addr, f0 or (f1 shl 32))
                addr += 8
                i += 2
            }
            if (i < end) {
                UnsafeMemory.putInt(addr, floats[i].toRawBits().reverseBytes())
            }
        } else {
            while (i + 1 < end) {
                val f0 = floats[i].toRawBits().toLong() and 0xFFFFFFFFL
                val f1 = floats[i + 1].toRawBits().toLong() and 0xFFFFFFFFL
                UnsafeMemory.putLong(addr, f0 or (f1 shl 32))
                addr += 8
                i += 2
            }
            if (i < end) {
                UnsafeMemory.putInt(addr, floats[i].toRawBits())
            }
        }
        positionValue += floats.size * 4
        return this
    }

    override fun readFloats(count: Int): FloatArray {
        val result = FloatArray(count)
        val needsSwap = nativeIsLittleEndian != littleEndian
        var addr = nativeAddress + positionValue
        var i = 0

        if (needsSwap) {
            while (i + 1 < count) {
                val packed = UnsafeMemory.getLong(addr)
                result[i] = Float.fromBits((packed and 0xFFFFFFFFL).toInt().reverseBytes())
                result[i + 1] = Float.fromBits((packed ushr 32).toInt().reverseBytes())
                addr += 8
                i += 2
            }
            if (i < count) {
                result[i] = Float.fromBits(UnsafeMemory.getInt(addr).reverseBytes())
            }
        } else {
            while (i + 1 < count) {
                val packed = UnsafeMemory.getLong(addr)
                result[i] = Float.fromBits((packed and 0xFFFFFFFFL).toInt())
                result[i + 1] = Float.fromBits((packed ushr 32).toInt())
                addr += 8
                i += 2
            }
            if (i < count) {
                result[i] = Float.fromBits(UnsafeMemory.getInt(addr))
            }
        }
        positionValue += count * 4
        return result
    }

    override fun writeDoubles(doubles: DoubleArray): WriteBuffer {
        val needsSwap = nativeIsLittleEndian != littleEndian
        var addr = nativeAddress + positionValue

        if (needsSwap) {
            for (d in doubles) {
                UnsafeMemory.putLong(addr, d.toRawBits().reverseBytes())
                addr += 8
            }
        } else {
            for (d in doubles) {
                UnsafeMemory.putLong(addr, d.toRawBits())
                addr += 8
            }
        }
        positionValue += doubles.size * 8
        return this
    }

    override fun readDoubles(count: Int): DoubleArray {
        val result = DoubleArray(count)
        val needsSwap = nativeIsLittleEndian != littleEndian
        var addr = nativeAddress + positionValue

        if (needsSwap) {
            for (i in 0 until count) {
                result[i] = Double.fromBits(UnsafeMemory.getLong(addr).reverseBytes())
                addr += 8
            }
        } else {
            for (i in 0 until count) {
                result[i] = Double.fromBits(UnsafeMemory.getLong(addr))
                addr += 8
            }
        }
        positionValue += count * 8
        return result
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        if (buffer is NativeScopedBuffer) {
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
        val slicePtr = (nativeAddress + positionValue).toCPointer<ByteVar>()!!
        return NativeScopedBuffer(scope, slicePtr, remaining(), byteOrder)
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
