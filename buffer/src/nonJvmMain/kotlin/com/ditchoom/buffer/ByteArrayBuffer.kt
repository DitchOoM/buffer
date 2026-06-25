package com.ditchoom.buffer

import com.ditchoom.buffer.BufferConstants.BYTE_1_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_2_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_3_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_MASK
import com.ditchoom.buffer.BufferConstants.INT_MASK
import com.ditchoom.buffer.BufferConstants.LAST_WORD_BYTE_INDEX
import com.ditchoom.buffer.BufferConstants.WORD_BYTE_MASK

/**
 * Buffer implementation backed by a Kotlin ByteArray.
 *
 * Used for wrap() operations where the buffer must share memory
 * with the original ByteArray. This lives in managed heap memory (WasmGC heap
 * for WASM, Kotlin/Native GC heap for native platforms).
 *
 * For native memory access on Apple platforms, use [MutableDataBuffer] instead.
 * For WASM linear memory, use [LinearBuffer] instead.
 */
class ByteArrayBuffer(
    internal val data: ByteArray,
    override val byteOrder: ByteOrder,
    internal val offset: Int = 0,
    internal val length: Int = data.size,
) : PlatformBuffer,
    ManagedMemoryAccess {
    init {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "ByteArrayBuffer offset/length out of range: offset=$offset length=$length data.size=${data.size}"
        }
    }

    override val backingArray: ByteArray get() = data
    override val arrayOffset: Int get() = offset
    private var positionValue: Int = 0
    private var limitValue: Int = length
    override val capacity: Int = length

    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN

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
        limitValue = length
    }

    private fun requireReadable(needed: Int) {
        if (positionValue + needed > limitValue) {
            throw BufferUnderflowException(
                "read of $needed byte(s) at position $positionValue exceeds limit $limitValue",
            )
        }
    }

    private fun requireIndex(
        index: Int,
        needed: Int,
    ) {
        if (index < 0 || index + needed > limitValue) {
            throw BufferUnderflowException(
                "absolute read of $needed byte(s) at index $index exceeds limit $limitValue",
            )
        }
    }

    override fun readByte(): Byte {
        requireReadable(1)
        return data[offset + positionValue++]
    }

    override fun get(index: Int): Byte {
        requireIndex(index, 1)
        return data[offset + index]
    }

    // Unchecked fast path (see ReadBuffer.getUnchecked): caller has validated the range, so read the
    // backing array directly. Lets bulk primitives skip the per-element requireIndex on non-JIT targets.
    override fun getUnchecked(index: Int): Byte = data[offset + index]

    override fun slice(byteOrder: ByteOrder): ByteArrayBuffer =
        ByteArrayBuffer(
            data,
            byteOrder,
            offset = offset + positionValue,
            length = limitValue - positionValue,
        )

    override fun readByteArray(size: Int): ByteArray {
        if (size < 1) return ByteArray(0)
        requireReadable(size)
        val absStart = offset + positionValue
        val result = data.copyOfRange(absStart, absStart + size)
        positionValue += size
        return result
    }

    override fun readInto(
        dst: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (length == 0) return
        requireReadable(length)
        val absStart = this.offset + positionValue
        data.copyInto(dst, destinationOffset = offset, startIndex = absStart, endIndex = absStart + length)
        positionValue += length
    }

    override fun readShort(): Short {
        requireReadable(Short.SIZE_BYTES)
        val result = getShortUnchecked(positionValue)
        positionValue += Short.SIZE_BYTES
        return result
    }

    override fun getShort(index: Int): Short {
        requireIndex(index, Short.SIZE_BYTES)
        return getShortUnchecked(index)
    }

    private fun getShortUnchecked(index: Int): Short {
        val abs = offset + index
        val b0 = data[abs].toInt() and BYTE_MASK
        val b1 = data[abs + 1].toInt() and BYTE_MASK
        return if (littleEndian) {
            (b0 or (b1 shl BYTE_1_SHIFT)).toShort()
        } else {
            ((b0 shl BYTE_1_SHIFT) or b1).toShort()
        }
    }

    override fun readInt(): Int {
        requireReadable(Int.SIZE_BYTES)
        val result = getIntUnchecked(positionValue)
        positionValue += Int.SIZE_BYTES
        return result
    }

    override fun getInt(index: Int): Int {
        requireIndex(index, Int.SIZE_BYTES)
        return getIntUnchecked(index)
    }

    private fun getIntUnchecked(index: Int): Int {
        val abs = offset + index
        val b0 = data[abs].toInt() and BYTE_MASK
        val b1 = data[abs + 1].toInt() and BYTE_MASK
        val b2 = data[abs + 2].toInt() and BYTE_MASK
        val b3 = data[abs + 3].toInt() and BYTE_MASK
        return if (littleEndian) {
            b0 or (b1 shl BYTE_1_SHIFT) or (b2 shl BYTE_2_SHIFT) or (b3 shl BYTE_3_SHIFT)
        } else {
            (b0 shl BYTE_3_SHIFT) or (b1 shl BYTE_2_SHIFT) or (b2 shl BYTE_1_SHIFT) or b3
        }
    }

    override fun readLong(): Long {
        requireReadable(Long.SIZE_BYTES)
        val result = getLongUnchecked(positionValue)
        positionValue += Long.SIZE_BYTES
        return result
    }

    override fun getLong(index: Int): Long {
        requireIndex(index, Long.SIZE_BYTES)
        return getLongUnchecked(index)
    }

    override fun getLongUnchecked(index: Int): Long {
        val first = getIntUnchecked(index).toLong() and INT_MASK
        val second = getIntUnchecked(index + Int.SIZE_BYTES).toLong() and INT_MASK
        return if (littleEndian) {
            (second shl Int.SIZE_BITS) or first
        } else {
            (first shl Int.SIZE_BITS) or second
        }
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        if (length == 0) return ""
        requireReadable(length)
        val absStart = offset + positionValue
        val result = decodeByteArrayToString(data, absStart, absStart + length, charset)
        positionValue += length
        return result
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        checkWriteBounds(1)
        data[offset + positionValue++] = byte
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        checkIndexBounds(index, 1)
        data[offset + index] = byte
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        checkWriteBounds(Short.SIZE_BYTES)
        setShortUnchecked(positionValue, short)
        positionValue += Short.SIZE_BYTES
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        checkIndexBounds(index, Short.SIZE_BYTES)
        setShortUnchecked(index, short)
        return this
    }

    private fun setShortUnchecked(
        index: Int,
        short: Short,
    ) {
        val v = short.toInt()
        val abs = offset + index
        if (littleEndian) {
            data[abs] = v.toByte()
            data[abs + 1] = (v shr BYTE_1_SHIFT).toByte()
        } else {
            data[abs] = (v shr BYTE_1_SHIFT).toByte()
            data[abs + 1] = v.toByte()
        }
    }

    override fun writeInt(int: Int): WriteBuffer {
        checkWriteBounds(Int.SIZE_BYTES)
        setIntUnchecked(positionValue, int)
        positionValue += Int.SIZE_BYTES
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        checkIndexBounds(index, Int.SIZE_BYTES)
        setIntUnchecked(index, int)
        return this
    }

    private fun setIntUnchecked(
        index: Int,
        int: Int,
    ) {
        val abs = offset + index
        if (littleEndian) {
            data[abs] = int.toByte()
            data[abs + 1] = (int shr BYTE_1_SHIFT).toByte()
            data[abs + 2] = (int shr BYTE_2_SHIFT).toByte()
            data[abs + LAST_WORD_BYTE_INDEX] = (int shr BYTE_3_SHIFT).toByte()
        } else {
            data[abs] = (int shr BYTE_3_SHIFT).toByte()
            data[abs + 1] = (int shr BYTE_2_SHIFT).toByte()
            data[abs + 2] = (int shr BYTE_1_SHIFT).toByte()
            data[abs + LAST_WORD_BYTE_INDEX] = int.toByte()
        }
    }

    override fun writeLong(long: Long): WriteBuffer {
        checkWriteBounds(Long.SIZE_BYTES)
        setLongUnchecked(positionValue, long)
        positionValue += Long.SIZE_BYTES
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        checkIndexBounds(index, Long.SIZE_BYTES)
        setLongUnchecked(index, long)
        return this
    }

    private fun setLongUnchecked(
        index: Int,
        long: Long,
    ) {
        if (littleEndian) {
            setIntUnchecked(index, long.toInt())
            setIntUnchecked(index + Int.SIZE_BYTES, (long shr Int.SIZE_BITS).toInt())
        } else {
            setIntUnchecked(index, (long shr Int.SIZE_BITS).toInt())
            setIntUnchecked(index + Int.SIZE_BYTES, long.toInt())
        }
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkWriteBounds(length)
        bytes.copyInto(data, this.offset + positionValue, offset, offset + length)
        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        if (size == 0) return
        checkWriteBounds(size)
        val actual = buffer.unwrapFully()
        if (actual is ByteArrayBuffer) {
            actual.data.copyInto(
                destination = data,
                destinationOffset = offset + positionValue,
                startIndex = actual.offset + actual.positionValue,
                endIndex = actual.offset + actual.positionValue + size,
            )
        } else {
            // readByteArray() already advances buffer position, don't increment again
            writeBytes(buffer.readByteArray(size))
            return
        }
        positionValue += size
        buffer.position(buffer.position() + size)
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        when (charset) {
            Charset.UTF8 -> writeBytes(text.toString().encodeToByteArray())
            else -> throw UnsupportedOperationException("ByteArrayBuffer only supports UTF8 charset")
        }
        return this
    }

    // === Optimized bulk operations ===

    /**
     * Optimized XOR mask operating directly on the backing byte array.
     */
    override fun xorMask(
        mask: Int,
        maskOffset: Int,
    ) {
        if (mask == 0) return
        val pos = positionValue
        val lim = limitValue
        val size = lim - pos
        if (size == 0) return
        val basePos = offset + pos

        // Rotate mask so byte at (maskOffset % 4) becomes byte 0
        val shift = (maskOffset and WORD_BYTE_MASK) * Byte.SIZE_BITS
        val rotated = if (shift == 0) mask else (mask shl shift) or (mask ushr (Int.SIZE_BITS - shift))

        val rb0 = (rotated ushr BYTE_3_SHIFT).toByte()
        val rb1 = (rotated ushr BYTE_2_SHIFT).toByte()
        val rb2 = (rotated ushr BYTE_1_SHIFT).toByte()
        val rb3 = rotated.toByte()

        var i = 0
        // Process 4 bytes at a time with rotated mask
        while (i + Int.SIZE_BYTES <= size) {
            data[basePos + i] = (data[basePos + i].toInt() xor rb0.toInt()).toByte()
            data[basePos + i + 1] = (data[basePos + i + 1].toInt() xor rb1.toInt()).toByte()
            data[basePos + i + 2] = (data[basePos + i + 2].toInt() xor rb2.toInt()).toByte()
            val l3 = basePos + i + LAST_WORD_BYTE_INDEX
            data[l3] = (data[l3].toInt() xor rb3.toInt()).toByte()
            i += Int.SIZE_BYTES
        }
        // Handle remaining bytes (at most 3)
        while (i < size) {
            val maskByte =
                when (i and WORD_BYTE_MASK) {
                    0 -> rb0
                    1 -> rb1
                    2 -> rb2
                    else -> rb3
                }
            data[basePos + i] = (data[basePos + i].toInt() xor maskByte.toInt()).toByte()
            i++
        }
    }

    /**
     * Optimized XOR mask copy operating directly on backing byte arrays.
     * Uses 4-byte unrolled loop for ByteArrayBuffer-to-ByteArrayBuffer copies.
     */
    override fun xorMaskCopy(
        source: ReadBuffer,
        mask: Int,
        maskOffset: Int,
    ) {
        val size = source.remaining()
        if (size == 0) return
        if (mask == 0) {
            write(source)
            return
        }

        val actual = source.unwrapFully()
        if (actual is ByteArrayBuffer) {
            val srcData = actual.data
            val srcSliceRelativePos = actual.positionValue
            val srcAbsPos = actual.offset + srcSliceRelativePos
            val dstAbsPos = offset + positionValue

            // Rotate mask so byte at (maskOffset % 4) becomes byte 0
            val shift = (maskOffset and WORD_BYTE_MASK) * Byte.SIZE_BITS
            val rotated = if (shift == 0) mask else (mask shl shift) or (mask ushr (Int.SIZE_BITS - shift))

            val rb0 = (rotated ushr BYTE_3_SHIFT).toByte()
            val rb1 = (rotated ushr BYTE_2_SHIFT).toByte()
            val rb2 = (rotated ushr BYTE_1_SHIFT).toByte()
            val rb3 = rotated.toByte()

            var i = 0
            // Process 4 bytes at a time with rotated mask
            while (i + Int.SIZE_BYTES <= size) {
                data[dstAbsPos + i] = (srcData[srcAbsPos + i].toInt() xor rb0.toInt()).toByte()
                data[dstAbsPos + i + 1] = (srcData[srcAbsPos + i + 1].toInt() xor rb1.toInt()).toByte()
                data[dstAbsPos + i + 2] = (srcData[srcAbsPos + i + 2].toInt() xor rb2.toInt()).toByte()
                val l3 = LAST_WORD_BYTE_INDEX
                data[dstAbsPos + i + l3] = (srcData[srcAbsPos + i + l3].toInt() xor rb3.toInt()).toByte()
                i += Int.SIZE_BYTES
            }
            // Handle remaining bytes
            while (i < size) {
                val maskByte =
                    when (i and WORD_BYTE_MASK) {
                        0 -> rb0
                        1 -> rb1
                        2 -> rb2
                        else -> rb3
                    }
                data[dstAbsPos + i] = (srcData[srcAbsPos + i].toInt() xor maskByte.toInt()).toByte()
                i++
            }

            positionValue += size
            source.position(srcSliceRelativePos + size)
        } else {
            // Fallback to default bulk Long-based implementation
            super.xorMaskCopy(source, mask, maskOffset)
        }
    }

    /**
     * Optimized fill using ByteArray.fill().
     */
    override fun fill(value: Byte): WriteBuffer {
        val count = remaining()
        if (count == 0) return this
        val abs = offset + positionValue
        data.fill(value, abs, abs + count)
        positionValue += count
        return this
    }

    // contentEquals() and indexOf() delegate to super which uses bulk Long operations
    // (bulkCompareEquals/bulkIndexOf with 8-byte chunks via getLong()).

    fun close() = Unit

    override fun equals(other: Any?): Boolean = bufferEquals(this, other)

    override fun hashCode(): Int = bufferHashCode(this)
}
