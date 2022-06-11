package com.ditchoom.buffer

interface ReadBuffer : PositionBuffer {
    fun resetForRead()
    fun readByte(): Byte
    fun slice(): ReadBuffer
    fun readByteArray(size: Int): ByteArray
    fun readUnsignedByte(): UByte
    fun readShort(): Short = readUnsignedShort().toShort()
    fun readUnsignedShort(): UShort
    fun readInt(): Int = readUnsignedInt().toInt()
    fun readUnsignedInt(): UInt
    fun readFloat(): Float = Float.fromBits(readInt())
    fun readLong(): Long
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


    companion object {
        val newLine = "\r\n".encodeToByteArray()
    }
}