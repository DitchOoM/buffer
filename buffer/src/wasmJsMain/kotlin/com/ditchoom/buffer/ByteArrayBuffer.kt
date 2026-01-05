package com.ditchoom.buffer

/**
 * Buffer implementation backed by a Kotlin ByteArray.
 *
 * Used for wrap() operations where the buffer must share memory
 * with the original ByteArray. This lives in WasmGC heap, not linear memory.
 */
class ByteArrayBuffer(
    private val data: ByteArray,
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
        val result =
            when (charset) {
                Charset.UTF8 ->
                    data.decodeToString(
                        positionValue,
                        positionValue + length,
                        throwOnInvalidSequence = true,
                    )
                else -> throw UnsupportedOperationException("ByteArrayBuffer only supports UTF8 charset")
            }
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
            writeBytes(buffer.readByteArray(size))
            buffer.position(buffer.position() + size)
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
