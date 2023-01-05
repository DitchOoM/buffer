package com.ditchoom.buffer

interface WriteBuffer : PositionBuffer {
    fun resetForWrite()

    fun writeByte(byte: Byte): WriteBuffer
    @Deprecated("Use writeByte for explicitness. This will be removed in the next release", ReplaceWith("writeByte(byte)"))
    fun write(byte: Byte): WriteBuffer = writeByte(byte)

    fun writeBytes(bytes: ByteArray): WriteBuffer = writeBytes(bytes, 0, bytes.size)
    @Deprecated("Use writeBytes for explicitness. This will be removed in the next release", ReplaceWith("writeBytes(bytes)"))
    fun write(bytes: ByteArray): WriteBuffer = writeBytes(bytes)

    fun writeBytes(bytes: ByteArray, offset: Int, length: Int): WriteBuffer
    @Deprecated("Use writeBytes for explicitness. This will be removed in the next release", ReplaceWith("writeBytes(bytes, offset, length)"))
    fun write(bytes: ByteArray, offset: Int, length: Int): WriteBuffer = writeBytes(bytes, offset, length)

    fun writeUByte(uByte: UByte): WriteBuffer = writeByte(uByte.toByte())
    @Deprecated("Use writeUByte for explicitness. This will be removed in the next release", ReplaceWith("writeUByte(uByte)"))
    fun write(uByte: UByte): WriteBuffer = writeUByte(uByte)

    fun writeShort(short: Short): WriteBuffer {
        val value = short.toInt()
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            writeByte((value shr 8 and 0xff).toByte())
            writeByte((value shr 0 and 0xff).toByte())
        } else {
            writeByte((value shr 0 and 0xff).toByte())
            writeByte((value shr 8 and 0xff).toByte())
        }
        return this
    }
    @Deprecated("Use writeShort for explicitness. This will be removed in the next release", ReplaceWith("writeShort(short)"))
    fun write(short: Short): WriteBuffer = writeUShort(short.toUShort())

    fun writeUShort(uShort: UShort): WriteBuffer = writeShort(uShort.toShort())
    @Deprecated("Use writeUShort for explicitness. This will be removed in the next release", ReplaceWith("writeUShort(uShort)"))
    fun write(uShort: UShort): WriteBuffer = writeUShort(uShort)

    fun writeInt(int: Int): WriteBuffer {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            writeByte((int shr 24 and 0xff).toByte())
            writeByte((int shr 16 and 0xff).toByte())
            writeByte((int shr 8 and 0xff).toByte())
            writeByte((int shr 0 and 0xff).toByte())
        } else {
            writeByte((int shr 0 and 0xff).toByte())
            writeByte((int shr 8 and 0xff).toByte())
            writeByte((int shr 16 and 0xff).toByte())
            writeByte((int shr 24 and 0xff).toByte())
        }
        return this
    }
    @Deprecated("Use writeInt for explicitness. This will be removed in the next release", ReplaceWith("writeInt(int)"))
    fun write(int: Int): WriteBuffer = writeInt(int)

    fun writeUInt(uInt: UInt): WriteBuffer = writeInt(uInt.toInt())
    @Deprecated("Use writeUInt for explicitness. This will be removed in the next release", ReplaceWith("writeUInt(uInt)"))
    fun write(uInt: UInt): WriteBuffer = writeUInt(uInt)

    fun writeLong(long: Long): WriteBuffer {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            writeByte((long shr 56 and 0xff).toByte())
            writeByte((long shr 48 and 0xff).toByte())
            writeByte((long shr 40 and 0xff).toByte())
            writeByte((long shr 32 and 0xff).toByte())
            writeByte((long shr 24 and 0xff).toByte())
            writeByte((long shr 16 and 0xff).toByte())
            writeByte((long shr 8 and 0xff).toByte())
            writeByte((long shr 0 and 0xff).toByte())
        } else {
            writeByte((long shr 0 and 0xff).toByte())
            writeByte((long shr 8 and 0xff).toByte())
            writeByte((long shr 16 and 0xff).toByte())
            writeByte((long shr 24 and 0xff).toByte())
            writeByte((long shr 32 and 0xff).toByte())
            writeByte((long shr 40 and 0xff).toByte())
            writeByte((long shr 48 and 0xff).toByte())
            writeByte((long shr 56 and 0xff).toByte())
        }
        return this
    }

    fun writeBits(bitSize: Byte, number: Long) {
        check(bitSize >= 1) { "bit size out of range" }
        val byteSizeRange = when (byteOrder) {
            ByteOrder.BIG_ENDIAN -> bitSize - 1..0
            ByteOrder.LITTLE_ENDIAN -> 0 until bitSize
        }
        for (byteIndex in byteSizeRange) {
            val bitIndex = byteIndex * 8
            writeByte((number shr bitIndex and 0xff).toByte())
        }
    }

    @Deprecated("Use writeLong for explicitness. This will be removed in the next release", ReplaceWith("writeLong(long)"))
    fun write(long: Long): WriteBuffer = writeLong(long)

    fun writeULong(uLong: ULong): WriteBuffer = writeLong(uLong.toLong())
    @Deprecated("Use writeULong for explicitness. This will be removed in the next release", ReplaceWith("writeULong(uLong)"))
    fun write(uLong: ULong): WriteBuffer = writeULong(uLong)

    fun writeFloat(float: Float): WriteBuffer = writeInt(float.toRawBits())
    @Deprecated("Use writeFloat for explicitness. This will be removed in the next release", ReplaceWith("writeFloat(float)"))
    fun write(float: Float): WriteBuffer = writeFloat(float)

    fun writeDouble(double: Double): WriteBuffer = writeLong(double.toRawBits())
    @Deprecated("Use writeDouble for explicitness. This will be removed in the next release", ReplaceWith("writeDouble(double)"))
    fun write(double: Double): WriteBuffer = writeDouble(double)

    fun writeUtf8(text: CharSequence): WriteBuffer
    fun write(buffer: ReadBuffer)
}
