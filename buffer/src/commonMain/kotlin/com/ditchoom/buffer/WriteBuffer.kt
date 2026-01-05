package com.ditchoom.buffer

interface WriteBuffer : PositionBuffer {
    fun resetForWrite()

    fun writeByte(byte: Byte): WriteBuffer

    operator fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer

    @Deprecated(
        "Use writeByte for explicitness. This will be removed in the next release",
        ReplaceWith("writeByte(byte)"),
    )
    fun write(byte: Byte): WriteBuffer = writeByte(byte)

    fun writeBytes(bytes: ByteArray): WriteBuffer = writeBytes(bytes, 0, bytes.size)

    @Deprecated(
        "Use writeBytes for explicitness. This will be removed in the next release",
        ReplaceWith("writeBytes(bytes)"),
    )
    fun write(bytes: ByteArray): WriteBuffer = writeBytes(bytes)

    fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer

    @Deprecated(
        "Use writeBytes for explicitness. This will be removed in the next release",
        ReplaceWith("writeBytes(bytes, offset, length)"),
    )
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer = writeBytes(bytes, offset, length)

    fun writeUByte(uByte: UByte): WriteBuffer = writeByte(uByte.toByte())

    operator fun set(
        index: Int,
        uByte: UByte,
    ) = set(index, uByte.toByte())

    @Deprecated(
        "Use writeUByte for explicitness. This will be removed in the next release",
        ReplaceWith("writeUByte(uByte)"),
    )
    fun write(uByte: UByte): WriteBuffer = writeUByte(uByte)

    // Optimized Short write - direct byte access instead of loop
    fun writeShort(short: Short): WriteBuffer {
        val value = short.toInt()
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            writeByte((value shr 8).toByte())
            writeByte(value.toByte())
        } else {
            writeByte(value.toByte())
            writeByte((value shr 8).toByte())
        }
        return this
    }

    operator fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        val value = short.toInt()
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            set(index, (value shr 8).toByte())
            set(index + 1, value.toByte())
        } else {
            set(index, value.toByte())
            set(index + 1, (value shr 8).toByte())
        }
        return this
    }

    @Deprecated(
        "Use writeShort for explicitness. This will be removed in the next release",
        ReplaceWith("writeShort(short)"),
    )
    fun write(short: Short): WriteBuffer = writeUShort(short.toUShort())

    fun writeUShort(uShort: UShort): WriteBuffer = writeShort(uShort.toShort())

    operator fun set(
        index: Int,
        uShort: UShort,
    ) = set(index, uShort.toShort())

    @Deprecated(
        "Use writeUShort for explicitness. This will be removed in the next release",
        ReplaceWith("writeUShort(uShort)"),
    )
    fun write(uShort: UShort): WriteBuffer = writeUShort(uShort)

    // Int write using Short writes - enables optimized ShortArrayBuffer implementations
    fun writeInt(int: Int): WriteBuffer {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            writeShort((int shr 16).toShort())
            writeShort(int.toShort())
        } else {
            writeShort(int.toShort())
            writeShort((int shr 16).toShort())
        }
        return this
    }

    operator fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            set(index, (int shr 16).toShort())
            set(index + 2, int.toShort())
        } else {
            set(index, int.toShort())
            set(index + 2, (int shr 16).toShort())
        }
        return this
    }

    @Deprecated(
        "Use writeInt for explicitness. This will be removed in the next release",
        ReplaceWith("writeInt(int)"),
    )
    fun write(int: Int): WriteBuffer = writeInt(int)

    fun writeUInt(uInt: UInt): WriteBuffer = writeInt(uInt.toInt())

    operator fun set(
        index: Int,
        uInt: UInt,
    ) = set(index, uInt.toInt())

    @Deprecated(
        "Use writeUInt for explicitness. This will be removed in the next release",
        ReplaceWith("writeUInt(uInt)"),
    )
    fun write(uInt: UInt): WriteBuffer = writeUInt(uInt)

    // Optimized Long write - uses two Int writes for efficiency
    fun writeLong(long: Long): WriteBuffer {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            writeInt((long shr 32).toInt())
            writeInt(long.toInt())
        } else {
            writeInt(long.toInt())
            writeInt((long shr 32).toInt())
        }
        return this
    }

    operator fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            set(index, (long shr 32).toInt())
            set(index + 4, long.toInt())
        } else {
            set(index, long.toInt())
            set(index + 4, (long shr 32).toInt())
        }
        return this
    }

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

    @Deprecated(
        "Use writeLong for explicitness. This will be removed in the next release",
        ReplaceWith("writeLong(long)"),
    )
    fun write(long: Long): WriteBuffer = writeLong(long)

    fun writeULong(uLong: ULong): WriteBuffer = writeLong(uLong.toLong())

    operator fun set(
        index: Int,
        uLong: ULong,
    ) = set(index, uLong.toLong())

    @Deprecated(
        "Use writeULong for explicitness. This will be removed in the next release",
        ReplaceWith("writeULong(uLong)"),
    )
    fun write(uLong: ULong): WriteBuffer = writeULong(uLong)

    fun writeFloat(float: Float): WriteBuffer = writeInt(float.toRawBits())

    operator fun set(
        index: Int,
        float: Float,
    ) = set(index, float.toRawBits())

    @Deprecated(
        "Use writeFloat for explicitness. This will be removed in the next release",
        ReplaceWith("writeFloat(float)"),
    )
    fun write(float: Float): WriteBuffer = writeFloat(float)

    fun writeDouble(double: Double): WriteBuffer = writeLong(double.toRawBits())

    operator fun set(
        index: Int,
        double: Double,
    ) = set(index, double.toRawBits())

    @Deprecated(
        "Use writeDouble for explicitness. This will be removed in the next release",
        ReplaceWith("writeDouble(double)"),
    )
    fun write(double: Double): WriteBuffer = writeDouble(double)

    @Deprecated(
        "Use writeString(txt, Charset.UTF8) instead",
        ReplaceWith("writeString(text, Charset.UTF8)", "com.ditchoom.buffer.Charset"),
    )
    fun writeUtf8(text: CharSequence): WriteBuffer = writeString(text, Charset.UTF8)

    fun writeString(
        text: CharSequence,
        charset: Charset = Charset.UTF8,
    ): WriteBuffer

    fun write(buffer: ReadBuffer)

    /**
     * Fills the remaining space in this buffer with the specified byte value.
     *
     * Uses optimized bulk writes (8 bytes at a time) when possible.
     * After this operation, position will equal limit.
     *
     * @param value The byte value to fill with
     * @return This buffer for chaining
     */
    fun fill(value: Byte): WriteBuffer {
        val count = remaining()
        if (count == 0) return this

        // Create a Long with the byte repeated 8 times for bulk writing
        val b = value.toLong() and 0xFFL
        val longValue =
            b or (b shl 8) or (b shl 16) or (b shl 24) or
                (b shl 32) or (b shl 40) or (b shl 48) or (b shl 56)

        // Write 8 bytes at a time
        var remaining = count
        while (remaining >= 8) {
            writeLong(longValue)
            remaining -= 8
        }

        // Write remaining bytes
        while (remaining > 0) {
            writeByte(value)
            remaining--
        }
        return this
    }

    /**
     * Fills the remaining space in this buffer with the specified Short value.
     *
     * Uses optimized bulk writes (8 bytes at a time) when possible.
     * The remaining space must be a multiple of 2 bytes, otherwise an exception is thrown.
     *
     * @param value The Short value to fill with
     * @return This buffer for chaining
     * @throws IllegalArgumentException if remaining space is not a multiple of 2
     */
    fun fill(value: Short): WriteBuffer {
        val count = remaining()
        require(count % 2 == 0) { "Remaining space ($count) must be a multiple of 2 for Short fill" }
        if (count == 0) return this

        // Create a Long with the Short repeated 4 times for bulk writing
        val s = value.toLong() and 0xFFFFL
        val longValue = s or (s shl 16) or (s shl 32) or (s shl 48)

        // Write 8 bytes (4 shorts) at a time
        var remaining = count
        while (remaining >= 8) {
            writeLong(longValue)
            remaining -= 8
        }

        // Write remaining shorts
        while (remaining >= 2) {
            writeShort(value)
            remaining -= 2
        }
        return this
    }

    /**
     * Fills the remaining space in this buffer with the specified Int value.
     *
     * Uses optimized bulk writes (8 bytes at a time) when possible.
     * The remaining space must be a multiple of 4 bytes, otherwise an exception is thrown.
     *
     * @param value The Int value to fill with
     * @return This buffer for chaining
     * @throws IllegalArgumentException if remaining space is not a multiple of 4
     */
    fun fill(value: Int): WriteBuffer {
        val count = remaining()
        require(count % 4 == 0) { "Remaining space ($count) must be a multiple of 4 for Int fill" }
        if (count == 0) return this

        // Create a Long with the Int repeated twice for bulk writing
        val i = value.toLong() and 0xFFFFFFFFL
        val longValue = i or (i shl 32)

        // Write 8 bytes (2 ints) at a time
        var remaining = count
        while (remaining >= 8) {
            writeLong(longValue)
            remaining -= 8
        }

        // Write remaining int if any
        if (remaining >= 4) {
            writeInt(value)
        }
        return this
    }

    /**
     * Fills the remaining space in this buffer with the specified Long value.
     *
     * Writes [value] repeatedly using the buffer's byte order. The remaining space
     * must be a multiple of 8 bytes, otherwise an exception is thrown.
     *
     * @param value The Long value to fill with
     * @return This buffer for chaining
     * @throws IllegalArgumentException if remaining space is not a multiple of 8
     */
    fun fill(value: Long): WriteBuffer {
        val count = remaining()
        require(count % 8 == 0) { "Remaining space ($count) must be a multiple of 8 for Long fill" }
        val iterations = count / 8
        for (i in 0 until iterations) {
            writeLong(value)
        }
        return this
    }
}
