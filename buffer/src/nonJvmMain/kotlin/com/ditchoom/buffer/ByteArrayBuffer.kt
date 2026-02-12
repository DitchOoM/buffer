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
) : PlatformBuffer,
    ManagedMemoryAccess {
    override val backingArray: ByteArray get() = data
    override val arrayOffset: Int get() = 0
    private var positionValue: Int = 0
    private var limitValue: Int = data.size
    override val capacity: Int = data.size

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
        limitValue = data.size
    }

    override fun readByte(): Byte = data[positionValue++]

    override fun get(index: Int): Byte = data[index]

    override fun slice(): ReadBuffer {
        val sliced = data.sliceArray(positionValue until limitValue)
        return ByteArrayBuffer(sliced, byteOrder)
    }

    override fun readByteArray(size: Int): ByteArray {
        val result = data.copyOfRange(positionValue, positionValue + size)
        positionValue += size
        return result
    }

    override fun readShort(): Short {
        val result = getShort(positionValue)
        positionValue += Short.SIZE_BYTES
        return result
    }

    override fun getShort(index: Int): Short {
        val b0 = data[index].toInt() and 0xFF
        val b1 = data[index + 1].toInt() and 0xFF
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
        val b0 = data[index].toInt() and 0xFF
        val b1 = data[index + 1].toInt() and 0xFF
        val b2 = data[index + 2].toInt() and 0xFF
        val b3 = data[index + 3].toInt() and 0xFF
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
        val result = decodeByteArrayToString(data, positionValue, positionValue + length, charset)
        positionValue += length
        return result
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        data[positionValue++] = byte
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        data[index] = byte
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
        if (littleEndian) {
            data[index] = v.toByte()
            data[index + 1] = (v shr 8).toByte()
        } else {
            data[index] = (v shr 8).toByte()
            data[index + 1] = v.toByte()
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
        if (littleEndian) {
            data[index] = int.toByte()
            data[index + 1] = (int shr 8).toByte()
            data[index + 2] = (int shr 16).toByte()
            data[index + 3] = (int shr 24).toByte()
        } else {
            data[index] = (int shr 24).toByte()
            data[index + 1] = (int shr 16).toByte()
            data[index + 2] = (int shr 8).toByte()
            data[index + 3] = int.toByte()
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
        bytes.copyInto(data, positionValue, offset, offset + length)
        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        if (buffer is ByteArrayBuffer) {
            buffer.data.copyInto(data, positionValue, buffer.positionValue, buffer.positionValue + size)
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
            data[pos + i] = (data[pos + i].toInt() xor rb0.toInt()).toByte()
            data[pos + i + 1] = (data[pos + i + 1].toInt() xor rb1.toInt()).toByte()
            data[pos + i + 2] = (data[pos + i + 2].toInt() xor rb2.toInt()).toByte()
            data[pos + i + 3] = (data[pos + i + 3].toInt() xor rb3.toInt()).toByte()
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
            data[pos + i] = (data[pos + i].toInt() xor maskByte.toInt()).toByte()
            i++
        }
    }

    /**
     * Optimized fill using ByteArray.fill().
     */
    override fun fill(value: Byte): WriteBuffer {
        val count = remaining()
        if (count == 0) return this
        data.fill(value, positionValue, positionValue + count)
        positionValue += count
        return this
    }

    /**
     * Optimized contentEquals for ByteArrayBuffer-to-ByteArrayBuffer comparison.
     * Uses a direct element-by-element loop to avoid allocating temporary arrays
     * (copyOfRange would allocate two ByteArrays for the slicing).
     */
    override fun contentEquals(other: ReadBuffer): Boolean {
        if (remaining() != other.remaining()) return false
        val size = remaining()
        if (size == 0) return true

        if (other is ByteArrayBuffer) {
            // Intentionally allocation-free: compare elements directly rather than
            // using copyOfRange().contentEquals() which would allocate two temp arrays.
            for (i in 0 until size) {
                if (data[positionValue + i] != other.data[other.positionValue + i]) {
                    return false
                }
            }
            return true
        }
        return super.contentEquals(other)
    }

    /**
     * Optimized indexOf(Byte) using direct array scan.
     */
    override fun indexOf(byte: Byte): Int {
        val pos = positionValue
        val size = remaining()
        for (i in 0 until size) {
            if (data[pos + i] == byte) {
                return i
            }
        }
        return -1
    }

    override suspend fun close() = Unit

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteArrayBuffer) return false
        if (positionValue != other.positionValue) return false
        if (limitValue != other.limitValue) return false
        if (capacity != other.capacity) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = positionValue.hashCode()
        result = 31 * result + limitValue.hashCode()
        result = 31 * result + capacity.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
