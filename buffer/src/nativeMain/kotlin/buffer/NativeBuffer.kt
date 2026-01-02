package com.ditchoom.buffer

data class NativeBuffer(
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

    override fun slice(): ReadBuffer = NativeBuffer(data.sliceArray(position until limit), byteOrder = byteOrder)

    override fun readByteArray(size: Int): ByteArray {
        val result = data.copyOfRange(position, position + size)
        position += size
        return result
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

    override fun write(buffer: ReadBuffer) {
        val start = position()
        val byteSize =
            if (buffer is NativeBuffer) {
                writeBytes(buffer.data)
                buffer.data.size
            } else {
                val numBytes = buffer.remaining()
                writeBytes(buffer.readByteArray(numBytes))
                numBytes
            }
        buffer.position(start + byteSize)
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
