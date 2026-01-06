package com.ditchoom.buffer

/**
 * Interface for writing primitive values and byte sequences to a buffer.
 *
 * WriteBuffer provides two types of write operations:
 * - **Relative writes** (`writeByte()`, `writeInt()`, etc.) - Write at the current [position] and advance it
 * - **Absolute writes** (`set(index, value)`) - Write at a specific index without changing position
 *
 * ## Position and Limit
 *
 * The buffer maintains a [position] cursor and a [limit] boundary:
 * - [position]: Current write location (0 to limit-1)
 * - [limit]: Maximum writable position (typically equals capacity)
 * - [remaining]: Bytes available to write (limit - position)
 *
 * ```
 * [0]...[position]...[limit/capacity]
 *       ^cursor      ^end of buffer
 * ```
 *
 * ## Byte Order
 *
 * Multi-byte writes respect the buffer's [byteOrder]:
 * - [ByteOrder.BIG_ENDIAN]: Most significant byte first (network byte order)
 * - [ByteOrder.LITTLE_ENDIAN]: Least significant byte first (x86/ARM native)
 *
 * ```kotlin
 * buffer.writeShort(0x1234.toShort())
 * // BIG_ENDIAN: writes [0x12, 0x34]
 * // LITTLE_ENDIAN: writes [0x34, 0x12]
 * ```
 *
 * ## Thread Safety
 *
 * WriteBuffer is NOT thread-safe. External synchronization is required for concurrent access.
 *
 * ## Example Usage
 *
 * ```kotlin
 * val buffer = PlatformBuffer.allocate(100)
 * buffer.writeInt(42)
 * buffer.writeString("Hello")
 * buffer.resetForRead()  // Switch to read mode
 * ```
 *
 * @see ReadBuffer for read operations
 * @see PlatformBuffer for creating buffers
 */
interface WriteBuffer : PositionBuffer {
    /**
     * Prepares the buffer for writing by setting position to 0 and limit to capacity.
     *
     * Call this to reset a buffer for reuse:
     * ```kotlin
     * buffer.resetForWrite()  // position=0, limit=capacity
     * buffer.writeInt(42)
     * ```
     */
    fun resetForWrite()

    /**
     * Writes a single byte at the current position and advances position by 1.
     *
     * @param byte The byte value to write
     * @return This buffer for chaining
     */
    fun writeByte(byte: Byte): WriteBuffer

    /**
     * Sets a byte at the specified absolute index without changing position.
     *
     * @param index The absolute index to write to (0 to capacity-1)
     * @param byte The byte value to write
     * @return This buffer for chaining
     */
    operator fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer

    @Deprecated(
        "Use writeByte for explicitness. This will be removed in the next release",
        ReplaceWith("writeByte(byte)"),
    )
    fun write(byte: Byte): WriteBuffer = writeByte(byte)

    /**
     * Writes all bytes from the array and advances position by `bytes.size`.
     *
     * @param bytes The byte array to write
     * @return This buffer for chaining
     */
    fun writeBytes(bytes: ByteArray): WriteBuffer = writeBytes(bytes, 0, bytes.size)

    @Deprecated(
        "Use writeBytes for explicitness. This will be removed in the next release",
        ReplaceWith("writeBytes(bytes)"),
    )
    fun write(bytes: ByteArray): WriteBuffer = writeBytes(bytes)

    /**
     * Writes bytes from the array starting at [offset] for [length] bytes.
     *
     * @param bytes The source byte array
     * @param offset Starting position in the source array
     * @param length Number of bytes to write
     * @return This buffer for chaining
     */
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

    /** Writes an unsigned byte and advances position by 1. */
    fun writeUByte(uByte: UByte): WriteBuffer = writeByte(uByte.toByte())

    /** Sets an unsigned byte at the specified index without changing position. */
    operator fun set(
        index: Int,
        uByte: UByte,
    ) = set(index, uByte.toByte())

    @Deprecated(
        "Use writeUByte for explicitness. This will be removed in the next release",
        ReplaceWith("writeUByte(uByte)"),
    )
    fun write(uByte: UByte): WriteBuffer = writeUByte(uByte)

    /**
     * Writes a 16-bit signed integer at the current position and advances position by 2.
     *
     * Byte order is determined by [byteOrder]:
     * - BIG_ENDIAN: `0x1234` → bytes `[0x12, 0x34]`
     * - LITTLE_ENDIAN: `0x1234` → bytes `[0x34, 0x12]`
     */
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

    /** Sets a 16-bit signed integer at the specified index without changing position. */
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

    /** Writes a 16-bit unsigned integer and advances position by 2. */
    fun writeUShort(uShort: UShort): WriteBuffer = writeShort(uShort.toShort())

    /** Sets a 16-bit unsigned integer at the specified index without changing position. */
    operator fun set(
        index: Int,
        uShort: UShort,
    ) = set(index, uShort.toShort())

    @Deprecated(
        "Use writeUShort for explicitness. This will be removed in the next release",
        ReplaceWith("writeUShort(uShort)"),
    )
    fun write(uShort: UShort): WriteBuffer = writeUShort(uShort)

    /**
     * Writes a 32-bit signed integer at the current position and advances position by 4.
     *
     * Byte order is determined by [byteOrder].
     */
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

    /** Sets a 32-bit signed integer at the specified index without changing position. */
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

    /** Writes a 32-bit unsigned integer and advances position by 4. */
    fun writeUInt(uInt: UInt): WriteBuffer = writeInt(uInt.toInt())

    /** Sets a 32-bit unsigned integer at the specified index without changing position. */
    operator fun set(
        index: Int,
        uInt: UInt,
    ) = set(index, uInt.toInt())

    @Deprecated(
        "Use writeUInt for explicitness. This will be removed in the next release",
        ReplaceWith("writeUInt(uInt)"),
    )
    fun write(uInt: UInt): WriteBuffer = writeUInt(uInt)

    /**
     * Writes a 64-bit signed integer at the current position and advances position by 8.
     *
     * Byte order is determined by [byteOrder].
     */
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

    /** Sets a 64-bit signed integer at the specified index without changing position. */
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

    /**
     * Writes a number using a variable byte size (1-8 bytes) and advances position.
     *
     * Useful for protocols with variable-width integers.
     *
     * @param number The value to write
     * @param byteSize Number of bytes to write (1-8)
     * @return This buffer for chaining
     * @throws IllegalStateException if byteSize is not in 1..8
     */
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

    /**
     * Sets a variable-size number at the specified index without changing position.
     *
     * @param index The absolute byte index to write to
     * @param number The value to write
     * @param byteSize Number of bytes to write (1-8)
     * @return This buffer for chaining
     * @throws IllegalStateException if byteSize is not in 1..8
     */
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

    /** Writes a 64-bit unsigned integer and advances position by 8. */
    fun writeULong(uLong: ULong): WriteBuffer = writeLong(uLong.toLong())

    /** Sets a 64-bit unsigned integer at the specified index without changing position. */
    operator fun set(
        index: Int,
        uLong: ULong,
    ) = set(index, uLong.toLong())

    @Deprecated(
        "Use writeULong for explicitness. This will be removed in the next release",
        ReplaceWith("writeULong(uLong)"),
    )
    fun write(uLong: ULong): WriteBuffer = writeULong(uLong)

    /** Writes a 32-bit IEEE 754 floating point and advances position by 4. */
    fun writeFloat(float: Float): WriteBuffer = writeInt(float.toRawBits())

    /** Sets a 32-bit IEEE 754 floating point at the specified index. */
    operator fun set(
        index: Int,
        float: Float,
    ) = set(index, float.toRawBits())

    @Deprecated(
        "Use writeFloat for explicitness. This will be removed in the next release",
        ReplaceWith("writeFloat(float)"),
    )
    fun write(float: Float): WriteBuffer = writeFloat(float)

    /** Writes a 64-bit IEEE 754 double precision floating point and advances position by 8. */
    fun writeDouble(double: Double): WriteBuffer = writeLong(double.toRawBits())

    /** Sets a 64-bit IEEE 754 double precision floating point at the specified index. */
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

    /**
     * Writes a string and advances position by the encoded byte length.
     *
     * See [ReadBuffer.readString] for charset support by platform.
     *
     * @param text The string to write
     * @param charset Character encoding to use (default: UTF-8)
     * @return This buffer for chaining
     * @throws UnsupportedOperationException if the charset is not supported on this platform
     */
    fun writeString(
        text: CharSequence,
        charset: Charset = Charset.UTF8,
    ): WriteBuffer

    /**
     * Writes all remaining bytes from the source buffer and advances both positions.
     *
     * After this operation:
     * - This buffer's position advances by `buffer.remaining()`
     * - The source buffer's position advances to its limit
     *
     * @param buffer The source buffer to read from
     */
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
