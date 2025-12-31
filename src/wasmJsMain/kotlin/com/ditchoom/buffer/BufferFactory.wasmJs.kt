package com.ditchoom.buffer

actual fun PlatformBuffer.Companion.allocate(
    size: Int,
    zone: AllocationZone,
    byteOrder: ByteOrder,
): PlatformBuffer =
    when (zone) {
        AllocationZone.Heap,
        AllocationZone.SharedMemory,
        AllocationZone.Direct,
        -> KotlinJsBuffer(ByteArray(size), byteOrder = byteOrder)
        is AllocationZone.Custom -> zone.allocator(size)
    }

actual fun PlatformBuffer.Companion.wrap(
    array: ByteArray,
    byteOrder: ByteOrder,
): PlatformBuffer = KotlinJsBuffer(array, byteOrder = byteOrder)

data class KotlinJsBuffer(
    val data: ByteArray,
    private var position: Int = 0,
    private var limit: Int = data.size,
    override val capacity: Int = data.size,
    override val byteOrder: ByteOrder,
) : PlatformBuffer {
    override fun resetForRead() {
        limit = position
        position = 0
    }

    override fun resetForWrite() {
        position = 0
        limit = data.size
    }

    override fun setLimit(limit: Int) {
        this.limit = limit
    }

    override fun readByte() = data[position++]

    override fun get(index: Int): Byte = data[index]

    override fun slice(): ReadBuffer = KotlinJsBuffer(data.sliceArray(position until limit), byteOrder = byteOrder)

    override fun readByteArray(size: Int): ByteArray {
        val result = data.copyOfRange(position, position + size)
        position += size
        return result
    }

    // Optimized Short read - direct byte access instead of loop
    override fun readShort(): Short {
        val value =
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                ((data[position].toInt() and 0xFF) shl 8) or
                    (data[position + 1].toInt() and 0xFF)
            } else {
                (data[position].toInt() and 0xFF) or
                    ((data[position + 1].toInt() and 0xFF) shl 8)
            }
        position += 2
        return value.toShort()
    }

    override fun getShort(index: Int): Short =
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (
                ((data[index].toInt() and 0xFF) shl 8) or
                    (data[index + 1].toInt() and 0xFF)
            ).toShort()
        } else {
            (
                (data[index].toInt() and 0xFF) or
                    ((data[index + 1].toInt() and 0xFF) shl 8)
            ).toShort()
        }

    // Optimized Int read - direct byte access instead of loop
    override fun readInt(): Int {
        val value =
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                ((data[position].toInt() and 0xFF) shl 24) or
                    ((data[position + 1].toInt() and 0xFF) shl 16) or
                    ((data[position + 2].toInt() and 0xFF) shl 8) or
                    (data[position + 3].toInt() and 0xFF)
            } else {
                (data[position].toInt() and 0xFF) or
                    ((data[position + 1].toInt() and 0xFF) shl 8) or
                    ((data[position + 2].toInt() and 0xFF) shl 16) or
                    ((data[position + 3].toInt() and 0xFF) shl 24)
            }
        position += 4
        return value
    }

    override fun getInt(index: Int): Int =
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            ((data[index].toInt() and 0xFF) shl 24) or
                ((data[index + 1].toInt() and 0xFF) shl 16) or
                ((data[index + 2].toInt() and 0xFF) shl 8) or
                (data[index + 3].toInt() and 0xFF)
        } else {
            (data[index].toInt() and 0xFF) or
                ((data[index + 1].toInt() and 0xFF) shl 8) or
                ((data[index + 2].toInt() and 0xFF) shl 16) or
                ((data[index + 3].toInt() and 0xFF) shl 24)
        }

    // Optimized Long read - uses two Int reads for efficiency
    override fun readLong(): Long {
        val first = readInt().toLong() and 0xFFFFFFFFL
        val second = readInt().toLong() and 0xFFFFFFFFL
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (first shl 32) or second
        } else {
            (second shl 32) or first
        }
    }

    override fun getLong(index: Int): Long {
        val first = getInt(index).toLong() and 0xFFFFFFFFL
        val second = getInt(index + 4).toLong() and 0xFFFFFFFFL
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (first shl 32) or second
        } else {
            (second shl 32) or first
        }
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val value =
            when (charset) {
                Charset.UTF8 -> data.decodeToString(position, position + length, throwOnInvalidSequence = true)
                Charset.UTF16 -> throw UnsupportedOperationException("Not sure how to implement.")
                Charset.UTF16BigEndian -> throw UnsupportedOperationException("Not sure how to implement.")
                Charset.UTF16LittleEndian -> throw UnsupportedOperationException("Not sure how to implement.")
                Charset.ASCII -> throw UnsupportedOperationException("Not sure how to implement.")
                Charset.ISOLatin1 -> throw UnsupportedOperationException("Not sure how to implement.")
                Charset.UTF32 -> throw UnsupportedOperationException("Not sure how to implement.")
                Charset.UTF32LittleEndian -> throw UnsupportedOperationException("Not sure how to implement.")
                Charset.UTF32BigEndian -> throw UnsupportedOperationException("Not sure how to implement.")
            }
        position += length
        return value
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        data[position++] = byte
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        data[index] = byte
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        bytes.copyInto(data, position, offset, offset + length)
        position += length
        return this
    }

    // Optimized Short write - direct byte access instead of loop
    override fun writeShort(short: Short): WriteBuffer {
        val value = short.toInt()
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            data[position] = (value shr 8).toByte()
            data[position + 1] = value.toByte()
        } else {
            data[position] = value.toByte()
            data[position + 1] = (value shr 8).toByte()
        }
        position += 2
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        val value = short.toInt()
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            data[index] = (value shr 8).toByte()
            data[index + 1] = value.toByte()
        } else {
            data[index] = value.toByte()
            data[index + 1] = (value shr 8).toByte()
        }
        return this
    }

    // Optimized Int write - direct byte access instead of loop
    override fun writeInt(int: Int): WriteBuffer {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            data[position] = (int shr 24).toByte()
            data[position + 1] = (int shr 16).toByte()
            data[position + 2] = (int shr 8).toByte()
            data[position + 3] = int.toByte()
        } else {
            data[position] = int.toByte()
            data[position + 1] = (int shr 8).toByte()
            data[position + 2] = (int shr 16).toByte()
            data[position + 3] = (int shr 24).toByte()
        }
        position += 4
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            data[index] = (int shr 24).toByte()
            data[index + 1] = (int shr 16).toByte()
            data[index + 2] = (int shr 8).toByte()
            data[index + 3] = int.toByte()
        } else {
            data[index] = int.toByte()
            data[index + 1] = (int shr 8).toByte()
            data[index + 2] = (int shr 16).toByte()
            data[index + 3] = (int shr 24).toByte()
        }
        return this
    }

    // Optimized Long write - uses two Int writes for efficiency
    override fun writeLong(long: Long): WriteBuffer {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            writeInt((long shr 32).toInt())
            writeInt(long.toInt())
        } else {
            writeInt(long.toInt())
            writeInt((long shr 32).toInt())
        }
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            set(index, (long shr 32).toInt())
            set(index + 4, long.toInt())
        } else {
            set(index, long.toInt())
            set(index + 4, (long shr 32).toInt())
        }
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val numBytes = buffer.remaining()
        if (buffer is KotlinJsBuffer) {
            // Copy only the remaining portion (from position to limit)
            buffer.data.copyInto(data, position, buffer.position(), buffer.position() + numBytes)
            position += numBytes
        } else {
            writeBytes(buffer.readByteArray(numBytes))
        }
        buffer.position(buffer.position() + numBytes)
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        when (charset) {
            Charset.UTF8 -> writeBytes(text.toString().encodeToByteArray())
            else -> throw UnsupportedOperationException("Unable to encode in $charset. Must use Charset.UTF8")
        }
        return this
    }

    override suspend fun close() = Unit

    override fun limit() = limit

    override fun position() = position

    override fun position(newPosition: Int) {
        position = newPosition
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        other as KotlinJsBuffer
        if (position != other.position) return false
        if (limit != other.limit) return false
        if (capacity != other.capacity) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = position.hashCode()
        result = 31 * result + limit.hashCode()
        result = 31 * result + capacity.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
