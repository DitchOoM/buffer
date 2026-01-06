package com.ditchoom.buffer

/**
 * Interface for reading primitive values and byte sequences from a buffer.
 *
 * ReadBuffer provides two types of read operations:
 * - **Relative reads** (`readByte()`, `readInt()`, etc.) - Read at the current [position] and advance it
 * - **Absolute reads** (`get(index)`, `getInt(index)`, etc.) - Read at a specific index without changing position
 *
 * ## Position and Limit
 *
 * The buffer maintains a [position] cursor and a [limit] boundary:
 * - [position]: Current read location (0 to limit-1)
 * - [limit]: End of readable data (0 to capacity)
 * - [remaining]: Bytes available to read (limit - position)
 *
 * ```
 * [0]...[position]...[limit]...[capacity]
 *       ^cursor      ^end of data
 * ```
 *
 * ## Byte Order
 *
 * Multi-byte reads respect the buffer's [byteOrder]:
 * - [ByteOrder.BIG_ENDIAN]: Most significant byte first (network byte order)
 * - [ByteOrder.LITTLE_ENDIAN]: Least significant byte first (x86/ARM native)
 *
 * ```kotlin
 * // Buffer containing bytes: [0x12, 0x34]
 * buffer.readShort() // BIG_ENDIAN: 0x1234, LITTLE_ENDIAN: 0x3412
 * ```
 *
 * ## Thread Safety
 *
 * ReadBuffer is NOT thread-safe. External synchronization is required for concurrent access.
 *
 * ## Example Usage
 *
 * ```kotlin
 * val buffer = PlatformBuffer.allocate(100)
 * buffer.writeInt(42)
 * buffer.writeString("Hello")
 * buffer.resetForRead()
 *
 * val number = buffer.readInt()     // 42
 * val text = buffer.readString(5)   // "Hello"
 * ```
 *
 * @see WriteBuffer for write operations
 * @see PlatformBuffer for creating buffers
 */
interface ReadBuffer : PositionBuffer {
    /**
     * Prepares the buffer for reading by setting limit to current position and position to 0.
     *
     * Call this after writing to switch to read mode:
     * ```kotlin
     * buffer.writeInt(42)
     * buffer.resetForRead()  // position=0, limit=4
     * val value = buffer.readInt()
     * ```
     */
    fun resetForRead()

    /**
     * Reads a single byte at the current position and advances position by 1.
     *
     * @return The byte value at the current position
     * @throws IndexOutOfBoundsException if position >= limit (platform-dependent behavior)
     */
    fun readByte(): Byte

    /**
     * Gets a byte at the specified absolute index without changing position.
     *
     * @param index The absolute index to read from (0 to capacity-1)
     * @return The byte value at the specified index
     * @throws IndexOutOfBoundsException if index is out of bounds (platform-dependent)
     */
    operator fun get(index: Int): Byte

    /**
     * Creates a new read-only view of this buffer's remaining content.
     *
     * The slice shares the underlying memory but has independent position/limit:
     * - Slice position: 0
     * - Slice limit: this.remaining()
     * - Changes to data are visible in both buffers
     *
     * **Does not change this buffer's position.**
     *
     * ```kotlin
     * buffer.position(10)
     * buffer.setLimit(20)
     * val slice = buffer.slice()  // slice has position=0, limit=10
     * // buffer position is still 10
     * ```
     *
     * @return A new ReadBuffer viewing the remaining bytes
     */
    fun slice(): ReadBuffer

    /**
     * Reads [size] bytes as a new buffer and advances position.
     *
     * @param size Number of bytes to read
     * @return A new ReadBuffer containing the bytes, or [EMPTY_BUFFER] if size < 1
     */
    fun readBytes(size: Int): ReadBuffer {
        if (size < 1) {
            return EMPTY_BUFFER
        }
        val oldLimit = limit()
        val oldPosition = position()
        setLimit(position() + size)
        val bytes = slice()
        position(oldPosition + size)
        setLimit(oldLimit)
        return bytes
    }

    /**
     * Reads [size] bytes into a new ByteArray and advances position.
     *
     * @param size Number of bytes to read
     * @return A new ByteArray containing the bytes
     */
    fun readByteArray(size: Int): ByteArray

    /**
     * Reads a single byte as unsigned (0-255) and advances position by 1.
     */
    fun readUnsignedByte(): UByte = readByte().toUByte()

    /**
     * Gets a byte as unsigned at the specified index without changing position.
     */
    fun getUnsignedByte(index: Int): UByte = get(index).toUByte()

    /**
     * Reads a 16-bit signed integer at the current position and advances position by 2.
     *
     * Byte order is determined by [byteOrder]:
     * - BIG_ENDIAN: bytes `[0x12, 0x34]` → `0x1234`
     * - LITTLE_ENDIAN: bytes `[0x12, 0x34]` → `0x3412`
     */
    fun readShort(): Short {
        val b0 = readByte().toInt() and 0xFF
        val b1 = readByte().toInt() and 0xFF
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            ((b0 shl 8) or b1).toShort()
        } else {
            (b0 or (b1 shl 8)).toShort()
        }
    }

    /**
     * Gets a 16-bit signed integer at the specified index without changing position.
     *
     * @param index The absolute byte index to read from
     */
    fun getShort(index: Int): Short {
        val b0 = get(index).toInt() and 0xFF
        val b1 = get(index + 1).toInt() and 0xFF
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            ((b0 shl 8) or b1).toShort()
        } else {
            (b0 or (b1 shl 8)).toShort()
        }
    }

    /** Reads a 16-bit unsigned integer and advances position by 2. */
    fun readUnsignedShort(): UShort = readShort().toUShort()

    /** Gets a 16-bit unsigned integer at the specified index without changing position. */
    fun getUnsignedShort(index: Int): UShort = getShort(index).toUShort()

    /**
     * Reads a 32-bit signed integer at the current position and advances position by 4.
     *
     * Byte order is determined by [byteOrder].
     */
    fun readInt(): Int {
        val s0 = readShort().toInt() and 0xFFFF
        val s1 = readShort().toInt() and 0xFFFF
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (s0 shl 16) or s1
        } else {
            s0 or (s1 shl 16)
        }
    }

    /** Gets a 32-bit signed integer at the specified index without changing position. */
    fun getInt(index: Int): Int {
        val s0 = getShort(index).toInt() and 0xFFFF
        val s1 = getShort(index + 2).toInt() and 0xFFFF
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (s0 shl 16) or s1
        } else {
            s0 or (s1 shl 16)
        }
    }

    /** Reads a 32-bit unsigned integer and advances position by 4. */
    fun readUnsignedInt(): UInt = readInt().toUInt()

    /** Gets a 32-bit unsigned integer at the specified index without changing position. */
    fun getUnsignedInt(index: Int): UInt = getInt(index).toUInt()

    /** Reads a 32-bit IEEE 754 floating point and advances position by 4. */
    fun readFloat(): Float = Float.fromBits(readInt())

    /** Gets a 32-bit IEEE 754 floating point at the specified index. */
    fun getFloat(index: Int): Float = Float.fromBits(getInt(index))

    /**
     * Reads a 64-bit signed integer at the current position and advances position by 8.
     *
     * Byte order is determined by [byteOrder].
     */
    fun readLong(): Long {
        val first = readInt().toLong() and 0xFFFFFFFFL
        val second = readInt().toLong() and 0xFFFFFFFFL
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (first shl 32) or second
        } else {
            (second shl 32) or first
        }
    }

    /** Gets a 64-bit signed integer at the specified index without changing position. */
    fun getLong(index: Int): Long {
        val first = getInt(index).toLong() and 0xFFFFFFFFL
        val second = getInt(index + 4).toLong() and 0xFFFFFFFFL
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (first shl 32) or second
        } else {
            (second shl 32) or first
        }
    }

    /** Reads a 64-bit unsigned integer and advances position by 8. */
    fun readUnsignedLong(): ULong = readLong().toULong()

    /** Gets a 64-bit unsigned integer at the specified index without changing position. */
    fun getUnsignedLong(index: Int): ULong = getLong(index).toULong()

    /** Reads a 64-bit IEEE 754 double precision floating point and advances position by 8. */
    fun readDouble(): Double = Double.fromBits(readLong())

    /** Gets a 64-bit IEEE 754 double precision floating point at the specified index. */
    fun getDouble(index: Int): Double = Double.fromBits(getLong(index))

    /**
     * Reads a string of the specified byte length and advances position.
     *
     * The [length] parameter specifies the number of **bytes** to read, not characters.
     * For variable-width encodings like UTF-8, the number of characters may differ.
     *
     * ## Charset Support by Platform
     *
     * | Charset | JVM | Android | Apple | JS | WASM |
     * |---------|-----|---------|-------|----|----|
     * | UTF8 | ✓ | ✓ | ✓ | ✓ | ✓ |
     * | UTF16 | ✓ | ✓ | ✓ | ✗ | ✓ |
     * | UTF16BigEndian | ✓ | ✓ | ✓ | ✓ | ✓ |
     * | UTF16LittleEndian | ✓ | ✓ | ✓ | ✓ | ✓ |
     * | ASCII | ✓ | ✓ | ✓ | ✓ | ✓ |
     * | ISOLatin1 | ✓ | ✓ | ✓ | ✓ | ✓ |
     * | UTF32 | ✓ | ✓ | ✗ | ✗ | ✗ |
     * | UTF32BigEndian | ✓ | ✓ | ✗ | ✗ | ✗ |
     * | UTF32LittleEndian | ✓ | ✓ | ✗ | ✗ | ✗ |
     *
     * @param length Number of bytes to read
     * @param charset Character encoding to use (default: UTF-8)
     * @return The decoded string
     * @throws UnsupportedOperationException if the charset is not supported on this platform
     */
    fun readString(
        length: Int,
        charset: Charset = Charset.UTF8,
    ): String

    @Deprecated(
        "Use readString instead",
        ReplaceWith("readString(bytes, Charset.UTF8)", "com.ditchoom.buffer.Charset"),
    )
    fun readUtf8(bytes: UInt): CharSequence = readString(bytes.toInt(), Charset.UTF8)

    @Deprecated(
        "Use readString instead",
        ReplaceWith("readString(bytes, Charset.UTF8)", "com.ditchoom.buffer.Charset"),
    )
    fun readUtf8(bytes: Int): CharSequence = readString(bytes, Charset.UTF8)

    /**
     * Reads a line of text from the buffer, handling all common line endings:
     * - `\n` (Unix/Linux/macOS)
     * - `\r\n` (Windows)
     * - `\r` (Classic Mac)
     *
     * The line ending characters are consumed but not included in the result.
     * The line is decoded as UTF-8.
     */
    fun readLine(): CharSequence {
        val initialPosition = position()
        var currentByte: Byte = 0
        var bytesRead = 0
        var foundCR = false

        while (remaining() > 0) {
            currentByte = readByte()
            bytesRead++

            if (currentByte == CR) {
                foundCR = true
                // Check if next byte is LF (for \r\n)
                if (remaining() > 0) {
                    val nextByte = readByte()
                    if (nextByte == LF) {
                        bytesRead++ // Include the LF in bytes read
                        break
                    } else {
                        // Not \r\n, just \r - put the byte back by adjusting position
                        position(position() - 1)
                        break
                    }
                }
                break
            } else if (currentByte == LF) {
                break
            }
        }

        // Calculate how many bytes are the actual content (excluding line ending)
        val lineEndingSize =
            when {
                foundCR && bytesRead > 1 && get(initialPosition + bytesRead - 1) == LF -> 2 // \r\n
                foundCR -> 1 // \r
                currentByte == LF -> 1 // \n
                else -> 0 // No line ending found (end of buffer)
            }

        val contentLength = bytesRead - lineEndingSize
        position(initialPosition)
        val result = readString(contentLength, Charset.UTF8)
        position(initialPosition + bytesRead)
        return result
    }

    @Deprecated(
        "Use readLine instead",
        ReplaceWith("readLine()"),
    )
    fun readUtf8Line(): CharSequence = readLine()

    companion object {
        private const val CR: Byte = '\r'.code.toByte()
        private const val LF: Byte = '\n'.code.toByte()
        val newLine = "\r\n".encodeToByteArray()
        val EMPTY_BUFFER = PlatformBuffer.allocate(0)
    }

    /**
     * Reads a number of variable byte size (1-8 bytes) and advances position.
     *
     * Useful for protocols with variable-width integers or reading
     * partial values (e.g., 3-byte or 5-byte integers).
     *
     * @param numberOfBytes Number of bytes to read (1-8)
     * @return The value as a Long, with bytes interpreted according to [byteOrder]
     * @throws IllegalStateException if numberOfBytes is not in 1..8
     */
    fun readNumberWithByteSize(numberOfBytes: Int): Long {
        check(numberOfBytes in 1..8) { "byte size out of range" }
        val byteSizeRange =
            when (byteOrder) {
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

    /**
     * Gets a number of variable byte size at the specified index without changing position.
     *
     * @param startIndex The absolute byte index to start reading from
     * @param numberOfBytes Number of bytes to read (1-8)
     * @return The value as a Long, with bytes interpreted according to [byteOrder]
     * @throws IllegalStateException if numberOfBytes is not in 1..8
     */
    fun getNumberWithStartIndexAndByteSize(
        startIndex: Int,
        numberOfBytes: Int,
    ): Long {
        check(numberOfBytes in 1..8) { "byte size out of range" }
        val byteSizeRange =
            when (byteOrder) {
                ByteOrder.LITTLE_ENDIAN -> 0 until numberOfBytes
                ByteOrder.BIG_ENDIAN -> numberOfBytes - 1 downTo 0
            }
        var number = 0L
        var index = startIndex
        for (i in byteSizeRange) {
            val bitIndex = i * 8
            number = get(index++).toLong() and 0xff shl bitIndex or number
        }
        return number
    }

    /**
     * Compares the remaining content of this buffer with another buffer.
     *
     * Uses optimized bulk comparison (8 bytes at a time) when possible.
     *
     * @param other The buffer to compare with
     * @return true if the remaining bytes in both buffers are identical
     */
    fun contentEquals(other: ReadBuffer): Boolean {
        if (remaining() != other.remaining()) return false
        val size = remaining()
        if (size == 0) return true

        return bulkCompareEquals(
            thisPos = position(),
            otherPos = other.position(),
            length = size,
            getLong = { getLong(it) },
            otherGetLong = { other.getLong(it) },
            getByte = { get(it) },
            otherGetByte = { other.get(it) },
        )
    }

    /**
     * Finds the first position where this buffer differs from another buffer.
     *
     * Compares bytes starting from each buffer's current position up to the
     * smaller of the two remaining sizes. Uses bulk Long comparisons (8 bytes
     * at a time) for improved performance.
     *
     * @param other The buffer to compare with
     * @return The relative index of the first mismatch, or -1 if no mismatch is found
     *         within the common length. If buffers have different remaining sizes but
     *         match up to the shorter length, returns the length of the shorter buffer.
     */
    fun mismatch(other: ReadBuffer): Int {
        val thisRemaining = remaining()
        val otherRemaining = other.remaining()
        val minLength = minOf(thisRemaining, otherRemaining)

        if (minLength == 0) {
            return if (thisRemaining != otherRemaining) 0 else -1
        }

        return bulkMismatch(
            thisPos = position(),
            otherPos = other.position(),
            minLength = minLength,
            thisRemaining = thisRemaining,
            otherRemaining = otherRemaining,
            getLong = { getLong(it) },
            otherGetLong = { other.getLong(it) },
            getByte = { get(it) },
            otherGetByte = { other.get(it) },
        )
    }

    /**
     * Finds the first occurrence of a byte sequence within this buffer.
     *
     * Uses optimized bulk search (8 bytes at a time) for the first byte,
     * then bulk Long comparisons for verifying matches.
     *
     * @param needle The byte sequence to search for
     * @return The relative index where needle starts (relative to current position),
     *         or -1 if not found
     */
    fun indexOf(needle: ReadBuffer): Int {
        val needleSize = needle.remaining()
        if (needleSize == 0) return 0
        if (needleSize > remaining()) return -1

        // For single byte, delegate to optimized indexOf(Byte)
        if (needleSize == 1) {
            return indexOf(needle.get(needle.position()))
        }

        val firstByte = needle.get(needle.position())
        val thisPos = position()
        val needlePos = needle.position()
        var searchOffset = 0
        val maxSearchOffset = remaining() - needleSize

        while (searchOffset <= maxSearchOffset) {
            // Use optimized bulk search for first byte
            val sliceRemaining = remaining() - searchOffset
            val firstByteIndex =
                bulkIndexOf(
                    startPos = thisPos + searchOffset,
                    length = sliceRemaining - needleSize + 1,
                    byte = firstByte,
                    getLong = { getLong(it) },
                    getByte = { get(it) },
                )

            if (firstByteIndex == -1) return -1

            val candidatePos = searchOffset + firstByteIndex

            // Verify match using bulk Long comparisons
            val matches =
                bulkCompareEquals(
                    thisPos = thisPos + candidatePos,
                    otherPos = needlePos,
                    length = needleSize,
                    getLong = { getLong(it) },
                    otherGetLong = { needle.getLong(it) },
                    getByte = { get(it) },
                    otherGetByte = { needle.get(it) },
                )

            if (matches) {
                return candidatePos
            }

            // Move past this candidate
            searchOffset = candidatePos + 1
        }
        return -1
    }

    /**
     * Finds the first occurrence of a single byte within this buffer.
     *
     * Uses optimized bulk search (8 bytes at a time with SIMD-like tricks) when possible.
     *
     * @param byte The byte to search for
     * @return The relative index where the byte is found (relative to current position),
     *         or -1 if not found
     */
    fun indexOf(byte: Byte): Int =
        bulkIndexOf(
            startPos = position(),
            length = remaining(),
            byte = byte,
            getLong = { getLong(it) },
            getByte = { get(it) },
        )

    /**
     * Finds the first occurrence of a Short value within this buffer.
     *
     * Searches for the 2-byte representation of [value] using the buffer's byte order.
     *
     * @param value The Short value to search for
     * @return The relative index where the value starts (relative to current position),
     *         or -1 if not found
     */
    fun indexOf(value: Short): Int {
        val size = remaining()
        if (size < 2) return -1

        val pos = position()
        val searchLimit = size - 1

        for (i in 0 until searchLimit) {
            if (getShort(pos + i) == value) {
                return i
            }
        }
        return -1
    }

    /**
     * Finds the first occurrence of an Int value within this buffer.
     *
     * Searches for the 4-byte representation of [value] using the buffer's byte order.
     *
     * @param value The Int value to search for
     * @return The relative index where the value starts (relative to current position),
     *         or -1 if not found
     */
    fun indexOf(value: Int): Int {
        val size = remaining()
        if (size < 4) return -1

        val pos = position()
        val searchLimit = size - 3

        for (i in 0 until searchLimit) {
            if (getInt(pos + i) == value) {
                return i
            }
        }
        return -1
    }

    /**
     * Finds the first occurrence of a Long value within this buffer.
     *
     * Searches for the 8-byte representation of [value] using the buffer's byte order.
     *
     * @param value The Long value to search for
     * @return The relative index where the value starts (relative to current position),
     *         or -1 if not found
     */
    fun indexOf(value: Long): Int {
        val size = remaining()
        if (size < 8) return -1

        val pos = position()
        val searchLimit = size - 7

        for (i in 0 until searchLimit) {
            if (getLong(pos + i) == value) {
                return i
            }
        }
        return -1
    }

    /**
     * Finds the first occurrence of a string within this buffer.
     *
     * Encodes the string using the specified charset and searches for the resulting bytes.
     *
     * @param text The string to search for
     * @param charset The charset to use for encoding (default: UTF-8)
     * @return The relative index where the string starts (relative to current position),
     *         or -1 if not found
     */
    fun indexOf(
        text: CharSequence,
        charset: Charset = Charset.UTF8,
    ): Int {
        if (text.isEmpty()) return 0

        // Encode the string to bytes
        val needle = PlatformBuffer.allocate(text.length * 4) // Max 4 bytes per char in UTF-8
        needle.writeString(text, charset)
        needle.resetForRead()

        return indexOf(needle)
    }
}
