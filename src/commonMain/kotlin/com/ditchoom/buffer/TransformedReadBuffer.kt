package com.ditchoom.buffer

class TransformedReadBuffer(val origin: ReadBuffer, val transformer: ((Int, Byte) -> Byte)) :
    ReadBuffer {
    override val byteOrder: ByteOrder = origin.byteOrder
    override fun limit() = origin.limit()
    override fun setLimit(limit: Int) {
        origin.setLimit(limit)
    }

    override fun position() = origin.position()

    override fun position(newPosition: Int) = origin.position(newPosition)

    override fun resetForRead() = origin.resetForRead()

    override fun readByte() = transformer(position(), origin.readByte())
    override fun slice(): ReadBuffer {
        val data = readByteArray(remaining())
        val sliced = PlatformBuffer.allocate(data.size)
        sliced.writeBytes(data)
        sliced.resetForRead()
        return sliced
    }

    override fun readByteArray(size: Int): ByteArray {
        val byteArray = origin.readByteArray(size)
        for (position in byteArray.indices) {
            byteArray[position] = transformer(position, byteArray[position])
        }
        return byteArray
    }

    override fun readShort(): Short {
        val buffer = PlatformBuffer.allocate(Short.SIZE_BYTES)
        buffer.writeShort(origin.readShort())
        buffer.resetForRead()
        val byte1 = transformer(position(), buffer.readByte())
        val byte2 = transformer(position(), buffer.readByte())
        buffer.resetForWrite()
        buffer.writeByte(byte1)
        buffer.writeByte(byte2)
        buffer.resetForRead()
        return buffer.readShort()
    }

    override fun readInt(): Int {
        val buffer = PlatformBuffer.allocate(Int.SIZE_BYTES)
        buffer.writeInt(origin.readInt())
        buffer.resetForRead()
        val byte1 = transformer(position(), buffer.readByte())
        val byte2 = transformer(position(), buffer.readByte())
        val byte3 = transformer(position(), buffer.readByte())
        val byte4 = transformer(position(), buffer.readByte())
        buffer.resetForWrite()
        buffer.writeByte(byte1)
        buffer.writeByte(byte2)
        buffer.writeByte(byte3)
        buffer.writeByte(byte4)
        buffer.resetForRead()
        return buffer.readInt()
    }

    override fun readLong(): Long {
        val buffer = PlatformBuffer.allocate(Long.SIZE_BYTES)
        buffer.writeLong(origin.readLong())
        buffer.resetForRead()
        val byte1 = transformer(position(), buffer.readByte())
        val byte2 = transformer(position(), buffer.readByte())
        val byte3 = transformer(position(), buffer.readByte())
        val byte4 = transformer(position(), buffer.readByte())
        val byte5 = transformer(position(), buffer.readByte())
        val byte6 = transformer(position(), buffer.readByte())
        val byte7 = transformer(position(), buffer.readByte())
        val byte8 = transformer(position(), buffer.readByte())
        buffer.resetForWrite()
        buffer.writeByte(byte1)
        buffer.writeByte(byte2)
        buffer.writeByte(byte3)
        buffer.writeByte(byte4)
        buffer.writeByte(byte5)
        buffer.writeByte(byte6)
        buffer.writeByte(byte7)
        buffer.writeByte(byte8)
        buffer.resetForRead()
        return buffer.readLong()
    }

    override fun readString(length: Int, charset: Charset): String {
        return when (charset) {
            Charset.UTF8 -> readByteArray(length).decodeToString()
            else -> throw UnsupportedOperationException("Unsupported charset $charset")
        }
    }
}
