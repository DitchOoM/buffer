package com.ditchoom.buffer

interface ReadBuffer : PositionBuffer {
    fun resetForRead()
    fun readByte(): Byte
    fun slice(): ReadBuffer
    fun readByteArray(size: Int): ByteArray
    fun readUnsignedByte(): UByte = readByte().toUByte()
    fun readShort(): Short = readNumberWithByteSize(Short.SIZE_BYTES).toShort()
    fun readUnsignedShort(): UShort = readShort().toUShort()
    fun readInt(): Int = readNumberWithByteSize(Int.SIZE_BYTES).toInt()
    fun readUnsignedInt(): UInt = readInt().toUInt()
    fun readFloat(): Float = Float.fromBits(readInt())
    fun readLong(): Long = readNumberWithByteSize(Long.SIZE_BYTES)
    fun readUnsignedLong(): ULong = readLong().toULong()
    fun readDouble(): Double = Double.fromBits(readLong())
    fun readUtf8(bytes: UInt): CharSequence = readUtf8(bytes.toInt())
    fun readUtf8(bytes: Int): CharSequence
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
            if (lastByte == newLine[0] && currentByte == newLine[1]) 2
            else if (currentByte == newLine[1]) 1
            else 0

        val bytesToRead = bytesRead - carriageFeedPositionIncrement
        position(initialPosition)
        val result = readUtf8(bytesToRead)
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

    companion object {
        val newLine = "\r\n".encodeToByteArray()
    }
}
