package com.ditchoom.buffer

interface ReadBuffer : PositionBuffer {
    fun resetForRead()

    fun readByte(): Byte

    operator fun get(index: Int): Byte

    // slice does not change the position
    fun slice(): ReadBuffer

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

    fun readByteArray(size: Int): ByteArray

    fun readUnsignedByte(): UByte = readByte().toUByte()

    fun getUnsignedByte(index: Int): UByte = get(index).toUByte()

    // Optimized Short read - direct byte access instead of loop
    fun readShort(): Short {
        val b0 = readByte().toInt() and 0xFF
        val b1 = readByte().toInt() and 0xFF
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            ((b0 shl 8) or b1).toShort()
        } else {
            (b0 or (b1 shl 8)).toShort()
        }
    }

    fun getShort(index: Int): Short {
        val b0 = get(index).toInt() and 0xFF
        val b1 = get(index + 1).toInt() and 0xFF
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            ((b0 shl 8) or b1).toShort()
        } else {
            (b0 or (b1 shl 8)).toShort()
        }
    }

    fun readUnsignedShort(): UShort = readShort().toUShort()

    fun getUnsignedShort(index: Int): UShort = getShort(index).toUShort()

    // Int read using Short reads - enables optimized ShortArrayBuffer implementations
    fun readInt(): Int {
        val s0 = readShort().toInt() and 0xFFFF
        val s1 = readShort().toInt() and 0xFFFF
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (s0 shl 16) or s1
        } else {
            s0 or (s1 shl 16)
        }
    }

    fun getInt(index: Int): Int {
        val s0 = getShort(index).toInt() and 0xFFFF
        val s1 = getShort(index + 2).toInt() and 0xFFFF
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (s0 shl 16) or s1
        } else {
            s0 or (s1 shl 16)
        }
    }

    fun readUnsignedInt(): UInt = readInt().toUInt()

    fun getUnsignedInt(index: Int): UInt = getInt(index).toUInt()

    fun readFloat(): Float = Float.fromBits(readInt())

    fun getFloat(index: Int): Float = Float.fromBits(getInt(index))

    // Optimized Long read - uses two Int reads for efficiency
    fun readLong(): Long {
        val first = readInt().toLong() and 0xFFFFFFFFL
        val second = readInt().toLong() and 0xFFFFFFFFL
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (first shl 32) or second
        } else {
            (second shl 32) or first
        }
    }

    fun getLong(index: Int): Long {
        val first = getInt(index).toLong() and 0xFFFFFFFFL
        val second = getInt(index + 4).toLong() and 0xFFFFFFFFL
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (first shl 32) or second
        } else {
            (second shl 32) or first
        }
    }

    fun readUnsignedLong(): ULong = readLong().toULong()

    fun getUnsignedLong(index: Int): ULong = getLong(index).toULong()

    fun readDouble(): Double = Double.fromBits(readLong())

    fun getDouble(index: Int): Double = Double.fromBits(getLong(index))

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
     */
    fun readUtf8Line(): CharSequence {
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

    companion object {
        private const val CR: Byte = '\r'.code.toByte()
        private const val LF: Byte = '\n'.code.toByte()
        val newLine = "\r\n".encodeToByteArray()
        val EMPTY_BUFFER = PlatformBuffer.allocate(0)
    }

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
     * @param other The buffer to compare with
     * @return true if the remaining bytes in both buffers are identical
     */
    fun contentEquals(other: ReadBuffer): Boolean {
        if (remaining() != other.remaining()) return false
        val size = remaining()
        for (i in 0 until size) {
            if (get(position() + i) != other.get(other.position() + i)) {
                return false
            }
        }
        return true
    }

    /**
     * Finds the first position where this buffer differs from another buffer.
     *
     * Compares bytes starting from each buffer's current position up to the
     * smaller of the two remaining sizes.
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

        for (i in 0 until minLength) {
            if (get(position() + i) != other.get(other.position() + i)) {
                return i
            }
        }

        // If lengths differ but all compared bytes matched, mismatch is at the end of shorter
        return if (thisRemaining != otherRemaining) minLength else -1
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
