package com.ditchoom.buffer

interface ReadBuffer : PositionBuffer {
    fun resetForRead()
    fun readByte(): Byte
    operator fun get(index: Int): Byte

    // slice does not change the position
    fun slice(): ReadBuffer

    fun readBytes(size: Int): ReadBuffer {
        if (size < 1) {
            return EMPTY_BUFFER
        }
        val oldLimit = limit()
        val oldPosition = position()
        setLimit(position() + size)
        val bytes = slice()
        position(oldPosition + size)
        setLimit(oldLimit)
        return bytes
    }

    fun readByteArray(size: Int): ByteArray

    fun readUnsignedByte(): UByte = readByte().toUByte()
    fun getUnsignedByte(index: Int): UByte = get(index).toUByte()

    fun readShort(): Short = readNumberWithByteSize(Short.SIZE_BYTES).toShort()
    fun getShort(index: Int): Short = getNumberWithStartIndexAndByteSize(index, Short.SIZE_BYTES).toShort()
    fun readUnsignedShort(): UShort = readShort().toUShort()
    fun getUnsignedShort(index: Int): UShort = getShort(index).toUShort()
    fun readInt(): Int = readNumberWithByteSize(Int.SIZE_BYTES).toInt()
    fun getInt(index: Int): Int = getNumberWithStartIndexAndByteSize(index, Int.SIZE_BYTES).toInt()
    fun readUnsignedInt(): UInt = readInt().toUInt()
    fun getUnsignedInt(index: Int): UInt = getInt(index).toUInt()
    fun readFloat(): Float = Float.fromBits(readInt())
    fun getFloat(index: Int): Float = Float.fromBits(getInt(index))
    fun readLong(): Long = readNumberWithByteSize(Long.SIZE_BYTES)
    fun getLong(index: Int): Long = getNumberWithStartIndexAndByteSize(index, Long.SIZE_BYTES)
    fun readUnsignedLong(): ULong = readLong().toULong()
    fun getUnsignedLong(index: Int): ULong = getLong(index).toULong()
    fun readDouble(): Double = Double.fromBits(readLong())
    fun getDouble(index: Int): Double = Double.fromBits(getLong(index))
    fun readString(length: Int, charset: Charset = Charset.UTF8): String

    @Deprecated(
        "Use readString instead",
        ReplaceWith("readString(bytes, Charset.UTF8)", "com.ditchoom.buffer.Charset")
    )
    fun readUtf8(bytes: UInt): CharSequence = readString(bytes.toInt(), Charset.UTF8)

    @Deprecated(
        "Use readString instead",
        ReplaceWith("readString(bytes, Charset.UTF8)", "com.ditchoom.buffer.Charset")
    )
    fun readUtf8(bytes: Int): CharSequence = readString(bytes, Charset.UTF8)
    fun readUtf8Line(): CharSequence {
        val initialPosition = position()
        var lastByte: Byte = 0
        var currentByte: Byte = 0
        var bytesRead = 0
        while (remaining() > 0) {
            lastByte = currentByte
            currentByte = readByte()
            bytesRead++
            if (currentByte == newLine[1]) {
                break
            }
        }
        val carriageFeedPositionIncrement =
            if (lastByte == newLine[0] && currentByte == newLine[1]) {
                2
            } else if (currentByte == newLine[1]) {
                1
            } else {
                0
            }

        val bytesToRead = bytesRead - carriageFeedPositionIncrement
        position(initialPosition)
        val result = readString(bytesToRead, Charset.UTF8)
        position(position() + carriageFeedPositionIncrement)
        return result
    }

    fun readNumberWithByteSize(numberOfBytes: Int): Long {
        check(numberOfBytes in 1..8) { "byte size out of range" }
        val byteSizeRange = when (byteOrder) {
            ByteOrder.LITTLE_ENDIAN -> 0 until numberOfBytes
            ByteOrder.BIG_ENDIAN -> numberOfBytes - 1 downTo 0
        }
        var number = 0L
        for (i in byteSizeRange) {
            val bitIndex = i * 8
            number = readByte().toLong() and 0xff shl bitIndex or number
        }
        return number
    }

    fun getNumberWithStartIndexAndByteSize(startIndex: Int, numberOfBytes: Int): Long {
        check(numberOfBytes in 1..8) { "byte size out of range" }
        val byteSizeRange = when (byteOrder) {
            ByteOrder.LITTLE_ENDIAN -> 0 until numberOfBytes
            ByteOrder.BIG_ENDIAN -> numberOfBytes - 1 downTo 0
        }
        var number = 0L
        var index = startIndex
        for (i in byteSizeRange) {
            val bitIndex = i * 8
            number = get(index++).toLong() and 0xff shl bitIndex or number
            println("reading index $bitIndex of ${toString()} -> $number")
        }
        return number
    }

    companion object {
        val newLine = "\r\n".encodeToByteArray()
        val EMPTY_BUFFER = PlatformBuffer.allocate(0)
    }
}
