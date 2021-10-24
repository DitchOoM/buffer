package com.ditchoom.buffer

@ExperimentalUnsignedTypes
interface WriteBuffer {
    fun resetForWrite()
    fun write(byte: Byte): WriteBuffer
    fun write(bytes: ByteArray): WriteBuffer
    fun write(uByte: UByte): WriteBuffer
    fun write(uShort: UShort): WriteBuffer
    fun write(uInt: UInt): WriteBuffer
    fun write(long: Long): WriteBuffer
    fun writeUtf8(text: CharSequence): WriteBuffer
    fun setLimit(limit: Int)
    fun write(buffer: PlatformBuffer)
}