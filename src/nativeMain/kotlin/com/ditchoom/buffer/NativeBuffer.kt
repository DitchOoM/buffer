package com.ditchoom.buffer

data class NativeBuffer(
    val data: ByteArray,
    private var position: Int = 0,
    private var limit: Int = data.size,
    override val capacity: Int = data.size,
    override val byteOrder: ByteOrder
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

    override fun slice(): ReadBuffer {
        return NativeBuffer(data.sliceArray(position until limit), byteOrder = byteOrder)
    }

    override fun readByteArray(size: Int): ByteArray {
        val result = data.copyOfRange(position, position + size)
        position += size
        return result
    }

    override fun readShort(): Short {
        val value =
            if (byteOrder == ByteOrder.BIG_ENDIAN)
                (
                    (0xff and data[position + 0].toInt() shl 8)
                        or (0xff and data[position + 1].toInt() shl 0)
                    ).toShort()
            else
                (
                    (0xff and data[position + 1].toInt() shl 8)
                        or (0xff and data[position + 0].toInt() shl 0)
                    ).toShort()
        position += Short.SIZE_BYTES
        return value
    }

    override fun readInt(): Int {
        val value =
            if (byteOrder == ByteOrder.BIG_ENDIAN)
                (
                    0xff and data[position + 0].toInt() shl 24
                        or (0xff and data[position + 1].toInt() shl 16)
                        or (0xff and data[position + 2].toInt() shl 8)
                        or (0xff and data[position + 3].toInt() shl 0)
                    )
            else
                (
                    0xff and data[position + 3].toInt() shl 24
                        or (0xff and data[position + 2].toInt() shl 16)
                        or (0xff and data[position + 1].toInt() shl 8)
                        or (0xff and data[position + 0].toInt() shl 0)
                    )
        position += Int.SIZE_BYTES
        return value
    }

    override fun readLong(): Long {
        val value =
            if (byteOrder == ByteOrder.BIG_ENDIAN)
                (
                    data[position + 0].toLong() shl 56
                        or (data[position + 1].toLong() and 0xff shl 48)
                        or (data[position + 2].toLong() and 0xff shl 40)
                        or (data[position + 3].toLong() and 0xff shl 32)
                        or (data[position + 4].toLong() and 0xff shl 24)
                        or (data[position + 5].toLong() and 0xff shl 16)
                        or (data[position + 6].toLong() and 0xff shl 8)
                        or (data[position + 7].toLong() and 0xff)
                    )
            else
                (
                    data[position + 7].toLong() shl 56
                        or (data[position + 6].toLong() and 0xff shl 48)
                        or (data[position + 5].toLong() and 0xff shl 40)
                        or (data[position + 4].toLong() and 0xff shl 32)
                        or (data[position + 3].toLong() and 0xff shl 24)
                        or (data[position + 2].toLong() and 0xff shl 16)
                        or (data[position + 1].toLong() and 0xff shl 8)
                        or (data[position + 0].toLong() and 0xff)
                    )
        position += Long.SIZE_BYTES
        return value
    }

    override fun readUtf8(bytes: Int): CharSequence {
        val value = data.decodeToString(position, position + bytes)
        position += bytes
        return value
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        data[position++] = byte
        return this
    }

    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int): WriteBuffer {
        bytes.copyInto(data, position, offset, offset + length)
        position += bytes.size
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val start = position()
        if (buffer is NativeBuffer) {
            writeBytes(buffer.data)
        } else {
            writeBytes(buffer.readByteArray(remaining()))
        }
        buffer.position((position() - start))
    }

    override fun writeUtf8(text: CharSequence): WriteBuffer {
        writeBytes(text.toString().encodeToByteArray())
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
        other as NativeBuffer
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
