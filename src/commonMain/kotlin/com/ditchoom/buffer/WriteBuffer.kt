package com.ditchoom.buffer

interface WriteBuffer : PositionBuffer {
    fun resetForWrite()

    fun writeByte(byte: Byte): WriteBuffer
    operator fun set(index: Int, byte: Byte): WriteBuffer

    @Deprecated(
        "Use writeByte for explicitness. This will be removed in the next release",
        ReplaceWith("writeByte(byte)")
    )
    fun write(byte: Byte): WriteBuffer = writeByte(byte)

    fun writeBytes(bytes: ByteArray): WriteBuffer = writeBytes(bytes, 0, bytes.size)

    @Deprecated(
        "Use writeBytes for explicitness. This will be removed in the next release",
        ReplaceWith("writeBytes(bytes)")
    )
    fun write(bytes: ByteArray): WriteBuffer = writeBytes(bytes)

    fun writeBytes(bytes: ByteArray, offset: Int, length: Int): WriteBuffer

    @Deprecated(
        "Use writeBytes for explicitness. This will be removed in the next release",
        ReplaceWith("writeBytes(bytes, offset, length)")
    )
    fun write(bytes: ByteArray, offset: Int, length: Int): WriteBuffer =
        writeBytes(bytes, offset, length)

    fun writeUByte(uByte: UByte): WriteBuffer = writeByte(uByte.toByte())
    operator fun set(index: Int, uByte: UByte) = set(index, uByte.toByte())

    @Deprecated(
        "Use writeUByte for explicitness. This will be removed in the next release",
        ReplaceWith("writeUByte(uByte)")
    )
    fun write(uByte: UByte): WriteBuffer = writeUByte(uByte)

    fun writeShort(short: Short): WriteBuffer =
        writeNumberOfByteSize(short.toLong(), Short.SIZE_BYTES)

    operator fun set(index: Int, short: Short) =
        setIndexNumberAndByteSize(index, short.toLong(), Short.SIZE_BYTES)

    @Deprecated(
        "Use writeShort for explicitness. This will be removed in the next release",
        ReplaceWith("writeShort(short)")
    )
    fun write(short: Short): WriteBuffer = writeUShort(short.toUShort())
    fun writeUShort(uShort: UShort): WriteBuffer = writeShort(uShort.toShort())
    operator fun set(index: Int, uShort: UShort) = set(index, uShort.toShort())

    @Deprecated(
        "Use writeUShort for explicitness. This will be removed in the next release",
        ReplaceWith("writeUShort(uShort)")
    )
    fun write(uShort: UShort): WriteBuffer = writeUShort(uShort)

    fun writeInt(int: Int): WriteBuffer = writeNumberOfByteSize(int.toLong(), Int.SIZE_BYTES)
    operator fun set(index: Int, int: Int) =
        setIndexNumberAndByteSize(index, int.toLong(), Int.SIZE_BYTES)

    @Deprecated(
        "Use writeInt for explicitness. This will be removed in the next release",
        ReplaceWith("writeInt(int)")
    )
    fun write(int: Int): WriteBuffer = writeInt(int)

    fun writeUInt(uInt: UInt): WriteBuffer = writeInt(uInt.toInt())
    operator fun set(index: Int, uInt: UInt) = set(index, uInt.toInt())

    @Deprecated(
        "Use writeUInt for explicitness. This will be removed in the next release",
        ReplaceWith("writeUInt(uInt)")
    )
    fun write(uInt: UInt): WriteBuffer = writeUInt(uInt)

    fun writeLong(long: Long): WriteBuffer = writeNumberOfByteSize(long, Long.SIZE_BYTES)
    operator fun set(index: Int, long: Long) =
        setIndexNumberAndByteSize(index, long, Long.SIZE_BYTES)

    fun writeNumberOfByteSize(number: Long, byteSize: Int): WriteBuffer {
        check(byteSize in 1..8) { "byte size out of range" }
        val byteSizeRange = when (byteOrder) {
            ByteOrder.LITTLE_ENDIAN -> 0 until byteSize
            ByteOrder.BIG_ENDIAN -> byteSize - 1 downTo 0
        }
        for (i in byteSizeRange) {
            val bitIndex = i * 8
            val n = (number shr bitIndex and 0xff).toByte()
            println("writing $n at index $i")
            writeByte(n)
        }
        return this
    }

    fun setIndexNumberAndByteSize(index: Int, number: Long, byteSize: Int): WriteBuffer {
        check(byteSize in 1..8) { "byte size out of range" }
        val p = position()
        position(index)
        writeNumberOfByteSize(number, byteSize)
        position(p)
        return this
    }

    @Deprecated(
        "Use writeLong for explicitness. This will be removed in the next release",
        ReplaceWith("writeLong(long)")
    )
    fun write(long: Long): WriteBuffer = writeLong(long)

    fun writeULong(uLong: ULong): WriteBuffer = writeLong(uLong.toLong())
    operator fun set(index: Int, uLong: ULong) = set(index, uLong.toLong())

    @Deprecated(
        "Use writeULong for explicitness. This will be removed in the next release",
        ReplaceWith("writeULong(uLong)")
    )
    fun write(uLong: ULong): WriteBuffer = writeULong(uLong)

    fun writeFloat(float: Float): WriteBuffer = writeInt(float.toRawBits())
    operator fun set(index: Int, float: Float) = set(index, float.toRawBits())

    @Deprecated(
        "Use writeFloat for explicitness. This will be removed in the next release",
        ReplaceWith("writeFloat(float)")
    )
    fun write(float: Float): WriteBuffer = writeFloat(float)

    fun writeDouble(double: Double): WriteBuffer = writeLong(double.toRawBits())
    operator fun set(index: Int, double: Double) = set(index, double.toRawBits())

    @Deprecated(
        "Use writeDouble for explicitness. This will be removed in the next release",
        ReplaceWith("writeDouble(double)")
    )
    fun write(double: Double): WriteBuffer = writeDouble(double)

    @Deprecated(
        "Use writeString(txt, Charset.UTF8) instead",
        ReplaceWith("writeString(text, Charset.UTF8)", "com.ditchoom.buffer.Charset")
    )
    fun writeUtf8(text: CharSequence): WriteBuffer = writeString(text, Charset.UTF8)
    fun writeString(text: CharSequence, charset: Charset = Charset.UTF8): WriteBuffer

    fun write(buffer: ReadBuffer)
}
