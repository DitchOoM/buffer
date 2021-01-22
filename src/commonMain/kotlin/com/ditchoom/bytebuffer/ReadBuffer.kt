package com.ditchoom.bytebuffer

@ExperimentalUnsignedTypes
interface ReadBuffer {

    fun limit(): UInt
    fun position(): UInt
    fun position(newPosition: Int)
    fun remaining() = limit() - position()
    fun hasRemaining() = position() < limit()

    fun resetForRead()
    fun readByte(): Byte
    fun readByteArray(size: UInt): ByteArray
    fun readUnsignedByte(): UByte
    fun readUnsignedShort(): UShort
    fun readUnsignedInt(): UInt
    fun readLong(): Long
    fun readUtf8(bytes: UInt): CharSequence
    fun readUtf8(bytes: Int): CharSequence = readUtf8(bytes.toUInt())
    fun readUtf8Line(): CharSequence {
        val initialPosition = position()
        var lastByte: Byte = 0
        var currentByte: Byte = 0
        var bytesRead = 0u
        while (remaining() > 0u) {
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

        val bytesToRead = bytesRead - carriageFeedPositionIncrement.toUInt()
        position(initialPosition.toInt())
        val result = readUtf8(bytesToRead)
        position(position().toInt() + carriageFeedPositionIncrement)
        return result
    }


    companion object {
        val newLine = "\r\n".encodeToByteArray()
    }
}