package com.ditchoom.buffer

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

    override fun readByte(): Byte = data[offset + positionValue++]

    override fun get(index: Int): Byte = data[offset + index]

    override fun slice(byteOrder: ByteOrder): ByteArrayBuffer =
        ByteArrayBuffer(
            data,
            byteOrder,
            offset = offset + positionValue,
            length = limitValue - positionValue,
        )

    override fun readByteArray(size: Int): ByteArray {
        val absStart = offset + positionValue
        val result = data.copyOfRange(absStart, absStart + size)
        positionValue += size
        return result
    }

    override fun readShort(): Short {
        val result = getShort(positionValue)
        positionValue += Short.SIZE_BYTES
        return result
    }

    override fun getShort(index: Int): Short {
        val abs = offset + index
        val b0 = data[abs].toInt() and 0xFF
        val b1 = data[abs + 1].toInt() and 0xFF
        return if (littleEndian) {
            (b0 or (b1 shl 8)).toShort()
        } else {
            ((b0 shl 8) or b1).toShort()
        }
    }

    override fun readInt(): Int {
        val result = getInt(positionValue)
        positionValue += Int.SIZE_BYTES
        return result
    }

    override fun getInt(index: Int): Int {
        val abs = offset + index
        val b0 = data[abs].toInt() and 0xFF
        val b1 = data[abs + 1].toInt() and 0xFF
        val b2 = data[abs + 2].toInt() and 0xFF
        val b3 = data[abs + 3].toInt() and 0xFF
        return if (littleEndian) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    override fun readLong(): Long {
        val result = getLong(positionValue)
        positionValue += Long.SIZE_BYTES
        return result
    }

    override fun getLong(index: Int): Long {
        val first = getInt(index).toLong() and 0xFFFFFFFFL
        val second = getInt(index + 4).toLong() and 0xFFFFFFFFL
        return if (littleEndian) {
            (second shl 32) or first
        } else {
            (first shl 32) or second
        }
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val absStart = offset + positionValue
        val result = decodeByteArrayToString(data, absStart, absStart + length, charset)
        positionValue += length
        return result
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        data[offset + positionValue++] = byte
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        data[offset + index] = byte
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        set(positionValue, short)
        positionValue += Short.SIZE_BYTES
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        val v = short.toInt()
        val abs = offset + index
        if (littleEndian) {
            data[abs] = v.toByte()
            data[abs + 1] = (v shr 8).toByte()
        } else {
            data[abs] = (v shr 8).toByte()
            data[abs + 1] = v.toByte()
        }
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        set(positionValue, int)
        positionValue += Int.SIZE_BYTES
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        val abs = offset + index
        if (littleEndian) {
            data[abs] = int.toByte()
            data[abs + 1] = (int shr 8).toByte()
            data[abs + 2] = (int shr 16).toByte()
            data[abs + 3] = (int shr 24).toByte()
        } else {
            data[abs] = (int shr 24).toByte()
            data[abs + 1] = (int shr 16).toByte()
            data[abs + 2] = (int shr 8).toByte()
            data[abs + 3] = int.toByte()
        }
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        set(positionValue, long)
        positionValue += Long.SIZE_BYTES
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        if (littleEndian) {
            set(index, long.toInt())
            set(index + 4, (long shr 32).toInt())
        } else {
            set(index, (long shr 32).toInt())
            set(index + 4, long.toInt())
        }
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        bytes.copyInto(data, this.offset + positionValue, offset, offset + length)
        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
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
        val shift = (maskOffset and 3) * 8
        val rotated = if (shift == 0) mask else (mask shl shift) or (mask ushr (32 - shift))

        val rb0 = (rotated ushr 24).toByte()
        val rb1 = (rotated ushr 16).toByte()
        val rb2 = (rotated ushr 8).toByte()
        val rb3 = rotated.toByte()

        var i = 0
        // Process 4 bytes at a time with rotated mask
        while (i + 4 <= size) {
            data[basePos + i] = (data[basePos + i].toInt() xor rb0.toInt()).toByte()
            data[basePos + i + 1] = (data[basePos + i + 1].toInt() xor rb1.toInt()).toByte()
            data[basePos + i + 2] = (data[basePos + i + 2].toInt() xor rb2.toInt()).toByte()
            data[basePos + i + 3] = (data[basePos + i + 3].toInt() xor rb3.toInt()).toByte()
            i += 4
        }
        // Handle remaining bytes (at most 3)
        while (i < size) {
            val maskByte =
                when (i and 3) {
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
            val shift = (maskOffset and 3) * 8
            val rotated = if (shift == 0) mask else (mask shl shift) or (mask ushr (32 - shift))

            val rb0 = (rotated ushr 24).toByte()
            val rb1 = (rotated ushr 16).toByte()
            val rb2 = (rotated ushr 8).toByte()
            val rb3 = rotated.toByte()

            var i = 0
            // Process 4 bytes at a time with rotated mask
            while (i + 4 <= size) {
                data[dstAbsPos + i] = (srcData[srcAbsPos + i].toInt() xor rb0.toInt()).toByte()
                data[dstAbsPos + i + 1] = (srcData[srcAbsPos + i + 1].toInt() xor rb1.toInt()).toByte()
                data[dstAbsPos + i + 2] = (srcData[srcAbsPos + i + 2].toInt() xor rb2.toInt()).toByte()
                data[dstAbsPos + i + 3] = (srcData[srcAbsPos + i + 3].toInt() xor rb3.toInt()).toByte()
                i += 4
            }
            // Handle remaining bytes
            while (i < size) {
                val maskByte =
                    when (i and 3) {
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
