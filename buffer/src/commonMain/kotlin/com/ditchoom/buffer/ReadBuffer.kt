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

    // Optimized Int read - direct byte access instead of loop
    fun readInt(): Int {
        val b0 = readByte().toInt() and 0xFF
        val b1 = readByte().toInt() and 0xFF
        val b2 = readByte().toInt() and 0xFF
        val b3 = readByte().toInt() and 0xFF
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        } else {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
    }

    fun getInt(index: Int): Int {
        val b0 = get(index).toInt() and 0xFF
        val b1 = get(index + 1).toInt() and 0xFF
        val b2 = get(index + 2).toInt() and 0xFF
        val b3 = get(index + 3).toInt() and 0xFF
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        } else {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
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

    fun readUtf8Line(): CharSequence {
        val initialPosition = position()
        var lastByte: Byte = 0
        var currentByte: Byte = 0
        var bytesRead = 0
        while (remaining() > 0) {
            lastByte = currentByte
            currentByte = readByte()
            bytesRead++
            if (currentByte == newLine[1]) {
                break
            }
        }
        val carriageFeedPositionIncrement =
            if (lastByte == newLine[0] && currentByte == newLine[1]) {
                2
            } else if (currentByte == newLine[1]) {
                1
            } else {
                0
            }

        val bytesToRead = bytesRead - carriageFeedPositionIncrement
        position(initialPosition)
        val result = readString(bytesToRead, Charset.UTF8)
        position(position() + carriageFeedPositionIncrement)
        return result
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
     * Searches for [needle] starting from this buffer's current position.
     *
     * @param needle The byte sequence to search for
     * @return The relative index where needle starts (relative to current position),
     *         or -1 if not found
     */
    fun indexOf(needle: ReadBuffer): Int {
        val needleSize = needle.remaining()
        if (needleSize == 0) return 0
        if (needleSize > remaining()) return -1

        val firstByte = needle.get(needle.position())
        val searchLimit = remaining() - needleSize

        outer@ for (i in 0..searchLimit) {
            // Quick check on first byte
            if (get(position() + i) != firstByte) continue

            // Check remaining bytes
            for (j in 1 until needleSize) {
                if (get(position() + i + j) != needle.get(needle.position() + j)) {
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    /**
     * Finds the first occurrence of a single byte within this buffer.
     *
     * @param byte The byte to search for
     * @return The relative index where the byte is found (relative to current position),
     *         or -1 if not found
     */
    fun indexOf(byte: Byte): Int {
        val size = remaining()
        for (i in 0 until size) {
            if (get(position() + i) == byte) {
                return i
            }
        }
        return -1
    }

    companion object {
        val newLine = "\r\n".encodeToByteArray()
        val EMPTY_BUFFER = PlatformBuffer.allocate(0)
    }
}
