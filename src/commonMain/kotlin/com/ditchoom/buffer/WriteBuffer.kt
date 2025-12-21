package com.ditchoom.buffer

interface WriteBuffer : PositionBuffer {
    fun resetForWrite()

    fun writeByte(byte: Byte): WriteBuffer

    operator fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer

    fun writeBytes(bytes: ByteArray): WriteBuffer = writeBytes(bytes, 0, bytes.size)

    fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer

    fun writeUByte(uByte: UByte): WriteBuffer = writeByte(uByte.toByte())

    operator fun set(
        index: Int,
        uByte: UByte,
    ) = set(index, uByte.toByte())

    fun writeShort(short: Short): WriteBuffer = writeNumberOfByteSize(short.toLong(), Short.SIZE_BYTES)

    operator fun set(
        index: Int,
        short: Short,
    ) = setIndexNumberAndByteSize(index, short.toLong(), Short.SIZE_BYTES)

    fun writeUShort(uShort: UShort): WriteBuffer = writeShort(uShort.toShort())

    operator fun set(
        index: Int,
        uShort: UShort,
    ) = set(index, uShort.toShort())

    fun writeInt(int: Int): WriteBuffer = writeNumberOfByteSize(int.toLong(), Int.SIZE_BYTES)

    operator fun set(
        index: Int,
        int: Int,
    ) = setIndexNumberAndByteSize(index, int.toLong(), Int.SIZE_BYTES)

    fun writeUInt(uInt: UInt): WriteBuffer = writeInt(uInt.toInt())

    operator fun set(
        index: Int,
        uInt: UInt,
    ) = set(index, uInt.toInt())

    fun writeLong(long: Long): WriteBuffer = writeNumberOfByteSize(long, Long.SIZE_BYTES)

    operator fun set(
        index: Int,
        long: Long,
    ) = setIndexNumberAndByteSize(index, long, Long.SIZE_BYTES)

    fun writeNumberOfByteSize(
        number: Long,
        byteSize: Int,
    ): WriteBuffer {
        check(byteSize in 1..8) { "byte size out of range" }
        val byteSizeRange =
            when (byteOrder) {
                ByteOrder.LITTLE_ENDIAN -> 0 until byteSize
                ByteOrder.BIG_ENDIAN -> byteSize - 1 downTo 0
            }
        for (i in byteSizeRange) {
            val bitIndex = i * 8
            writeByte((number shr bitIndex and 0xff).toByte())
        }
        return this
    }

    fun setIndexNumberAndByteSize(
        index: Int,
        number: Long,
        byteSize: Int,
    ): WriteBuffer {
        check(byteSize in 1..8) { "byte size out of range" }
        val p = position()
        position(index)
        writeNumberOfByteSize(number, byteSize)
        position(p)
        return this
    }

    fun writeULong(uLong: ULong): WriteBuffer = writeLong(uLong.toLong())

    operator fun set(
        index: Int,
        uLong: ULong,
    ) = set(index, uLong.toLong())

    fun writeFloat(float: Float): WriteBuffer = writeInt(float.toRawBits())

    operator fun set(
        index: Int,
        float: Float,
    ) = set(index, float.toRawBits())

    fun writeDouble(double: Double): WriteBuffer = writeLong(double.toRawBits())

    operator fun set(
        index: Int,
        double: Double,
    ) = set(index, double.toRawBits())

    fun writeString(
        text: CharSequence,
        charset: Charset = Charset.UTF8,
    ): WriteBuffer

    fun write(buffer: ReadBuffer)
}
