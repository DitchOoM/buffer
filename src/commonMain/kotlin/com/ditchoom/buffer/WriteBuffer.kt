package com.ditchoom.buffer

@ExperimentalUnsignedTypes
interface WriteBuffer : PositionBuffer {
    fun resetForWrite()
    fun write(byte: Byte): WriteBuffer
    fun write(bytes: ByteArray): WriteBuffer
    fun write(uByte: UByte): WriteBuffer
    fun write(short: Short): WriteBuffer = write(short.toUShort())
    fun write(uShort: UShort): WriteBuffer
    fun write(int: Int): WriteBuffer = write(int.toUInt())
    fun write(uInt: UInt): WriteBuffer
    fun write(uLong: ULong): WriteBuffer = write(uLong.toLong())
    fun write(long: Long): WriteBuffer
    fun writeUtf8(text: CharSequence): WriteBuffer
    fun write(buffer: ReadBuffer)
}