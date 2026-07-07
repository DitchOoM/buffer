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
 * val buffer = BufferFactory.Default.allocate(100)
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
     * Reads the byte at [index] **without bounds or liveness checks**.
     *
     * This is a building block for the library's own bulk primitives ([hashRange], [regionEquals],
     * [readFixedDecimalTenths], …), which validate the whole accessed range **once** and then loop
     * over these unchecked accessors — the manual equivalent of a JIT hoisting a bounds check out of
     * a loop. On platforms with a JIT (JVM) the default below already gets that elimination for free,
     * so it simply delegates to the checked [get]; non-JIT backends (Native) override it to skip the
     * per-element check. Callers MUST have validated `index` lies within the buffer first.
     */
    fun getUnchecked(index: Int): Byte = get(index)

    /**
     * Reads the 8-byte Long at [index] (per [byteOrder]) **without bounds or liveness checks**.
     * See [getUnchecked] for the contract — the caller must have validated `[index, index+8)`.
     */
    fun getLongUnchecked(index: Int): Long = getLong(index)

    /**
     * **Zero-copy view** of this buffer's remaining content. The slice shares
     * the underlying storage; mutations to either are visible in both.
     *
     * - Slice position: 0
     * - Slice limit: this.remaining()
     * - **Does not change this buffer's position.**
     *
     * **Lifetime contract**: the slice MUST NOT outlive this buffer's scope.
     * If the underlying buffer is pooled and the slice is held past pool
     * release, the slice reads reclaimed memory. For internal-codec staging
     * (read a sub-region, decode within it, discard) this is the right
     * primitive. For consumer-bound bytes that need to survive past decode,
     * see [copyToByteArray] (fresh heap `ByteArray`) or
     * `factory.allocate(n).write(source)` (consumer-owned `PlatformBuffer`).
     *
     * The slice's [byteOrder] defaults to this buffer's [byteOrder]; pass an
     * explicit value to override (useful when handing a sub-region to a
     * codec whose wire byte order differs from the parent's).
     *
     * ```kotlin
     * buffer.position(10)
     * buffer.setLimit(20)
     * val slice = buffer.slice()                            // inherits parent byte order
     * val leSlice = buffer.slice(ByteOrder.LITTLE_ENDIAN)   // override per slice
     * // buffer position is still 10 in both cases
     * ```
     *
     * @param byteOrder Byte order for the returned slice. Defaults to this buffer's order.
     * @return A zero-copy ReadBuffer view of the remaining bytes
     */
    fun slice(byteOrder: ByteOrder = this.byteOrder): ReadBuffer

    /**
     * Reads [size] bytes as a **zero-copy view** and advances position. The
     * returned buffer shares storage with this buffer; the aliasing contract
     * of [slice] applies — must not outlive this buffer's scope.
     *
     * For consumer-bound bytes that need to outlive the decode scope, use
     * [copyToByteArray] or `factory.allocate(size).write(this)`.
     *
     * @param size Number of bytes to read
     * @return A zero-copy ReadBuffer view of the bytes, or [EMPTY_BUFFER] if size < 1
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
     * Reads [size] bytes and advances position. **May return a view sharing
     * storage with this buffer on some platforms** (e.g. JS returns an
     * `Int8Array` over the same underlying `ArrayBuffer`). The returned array
     * must NOT be retained past this buffer's scope when independence matters.
     *
     * For consumer-bound bytes that must outlive the buffer's scope, use
     * [copyToByteArray] — it carries an explicit copy contract on every
     * platform.
     *
     * @param size Number of bytes to read
     * @return A ByteArray containing the bytes (may alias this buffer)
     */
    fun readByteArray(size: Int): ByteArray

    /**
     * Reads [length] bytes from this buffer into [dst] starting at [offset]
     * and advances position by [length]. Bytes are **copied** into [dst];
     * the destination array is independent of this buffer's storage.
     *
     * Use this primitive when the caller wants to reuse a scratch ByteArray
     * across decode calls, or pack the read bytes into an existing array at
     * a specific offset. For the common "give me a fresh ByteArray" case,
     * prefer [copyToByteArray].
     *
     * @param dst Destination array
     * @param offset Starting index in [dst] (default 0)
     * @param length Number of bytes to read (default: fill [dst] from [offset])
     */
    fun readInto(
        dst: ByteArray,
        offset: Int = 0,
        length: Int = dst.size - offset,
    ) {
        for (i in 0 until length) dst[offset + i] = readByte()
    }

    /**
     * Reads [size] bytes into a **new, independently-allocated** ByteArray and
     * advances position. The returned ByteArray does not share storage with
     * this buffer; mutations to the buffer or its underlying pool after this
     * call do not affect the returned ByteArray. Safe to outlive this buffer's
     * scope.
     *
     * Use this primitive at the consumer boundary when the caller wants to
     * hold bytes past the decoding scope. The verb `copy` in the name signals
     * the cost — there is no way to convey raw bytes across the scope without
     * copying, on the platforms that matter.
     *
     * For zero-copy access during the decode call itself (no consumer
     * escape), prefer [slice] / [readBytes] (zero-copy views — see those
     * methods for the aliasing contract) or `toNativeData()` (zero-copy
     * native handle). For scratch-array reuse, see [readInto].
     *
     * @param size Number of bytes to read
     * @return A new independently-allocated ByteArray containing the bytes
     */
    fun copyToByteArray(size: Int): ByteArray {
        val dst = ByteArray(size)
        readInto(dst, 0, size)
        return dst
    }

    /**
     * Reads a single byte as unsigned (0-255) and advances position by 1.
     */
    fun readUnsignedByte(): UByte = readByte().toUByte()

    /** Short-form alias for [readUnsignedByte]. Symmetric with [WriteBuffer.writeUByte]. */
    fun readUByte(): UByte = readUnsignedByte()

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
        val b0 = readByte().toInt() and BufferConstants.BYTE_MASK
        val b1 = readByte().toInt() and BufferConstants.BYTE_MASK
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            ((b0 shl Byte.SIZE_BITS) or b1).toShort()
        } else {
            (b0 or (b1 shl Byte.SIZE_BITS)).toShort()
        }
    }

    /**
     * Gets a 16-bit signed integer at the specified index without changing position.
     *
     * @param index The absolute byte index to read from
     */
    fun getShort(index: Int): Short {
        val b0 = get(index).toInt() and BufferConstants.BYTE_MASK
        val b1 = get(index + 1).toInt() and BufferConstants.BYTE_MASK
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            ((b0 shl Byte.SIZE_BITS) or b1).toShort()
        } else {
            (b0 or (b1 shl Byte.SIZE_BITS)).toShort()
        }
    }

    /** Reads a 16-bit unsigned integer and advances position by 2. */
    fun readUnsignedShort(): UShort = readShort().toUShort()

    /** Short-form alias for [readUnsignedShort]. Symmetric with [WriteBuffer.writeUShort]. */
    fun readUShort(): UShort = readUnsignedShort()

    /** Gets a 16-bit unsigned integer at the specified index without changing position. */
    fun getUnsignedShort(index: Int): UShort = getShort(index).toUShort()

    /**
     * Reads a 32-bit signed integer at the current position and advances position by 4.
     *
     * Byte order is determined by [byteOrder].
     */
    fun readInt(): Int {
        val s0 = readShort().toInt() and BufferConstants.SHORT_MASK
        val s1 = readShort().toInt() and BufferConstants.SHORT_MASK
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (s0 shl Short.SIZE_BITS) or s1
        } else {
            s0 or (s1 shl Short.SIZE_BITS)
        }
    }

    /** Gets a 32-bit signed integer at the specified index without changing position. */
    fun getInt(index: Int): Int {
        val s0 = getShort(index).toInt() and BufferConstants.SHORT_MASK
        val s1 = getShort(index + Short.SIZE_BYTES).toInt() and BufferConstants.SHORT_MASK
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (s0 shl Short.SIZE_BITS) or s1
        } else {
            s0 or (s1 shl Short.SIZE_BITS)
        }
    }

    /** Reads a 32-bit unsigned integer and advances position by 4. */
    fun readUnsignedInt(): UInt = readInt().toUInt()

    /** Short-form alias for [readUnsignedInt]. Symmetric with [WriteBuffer.writeUInt]. */
    fun readUInt(): UInt = readUnsignedInt()

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
        val first = readInt().toLong() and BufferConstants.INT_MASK
        val second = readInt().toLong() and BufferConstants.INT_MASK
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (first shl Int.SIZE_BITS) or second
        } else {
            (second shl Int.SIZE_BITS) or first
        }
    }

    /** Gets a 64-bit signed integer at the specified index without changing position. */
    fun getLong(index: Int): Long {
        val first = getInt(index).toLong() and BufferConstants.INT_MASK
        val second = getInt(index + Int.SIZE_BYTES).toLong() and BufferConstants.INT_MASK
        return if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (first shl Int.SIZE_BITS) or second
        } else {
            (second shl Int.SIZE_BITS) or first
        }
    }

    /** Reads a 64-bit unsigned integer and advances position by 8. */
    fun readUnsignedLong(): ULong = readLong().toULong()

    /** Short-form alias for [readUnsignedLong]. Symmetric with [WriteBuffer.writeULong]. */
    fun readULong(): ULong = readUnsignedLong()

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
     *
     * This implementation uses bulk search operations (indexOf) for better performance
     * on long lines compared to byte-by-byte scanning.
     */
    fun readLine(): CharSequence {
        if (remaining() == 0) return ""

        val startPos = position()

        // Use bulk search to find line endings (much faster than byte-by-byte)
        val crIndex = indexOf(CR) // Relative to current position
        val lfIndex = indexOf(LF) // Relative to current position

        // Determine content length and line ending size
        val (contentLength, lineEndingSize) =
            when {
                crIndex == -1 && lfIndex == -1 -> {
                    // No line ending - read to end of buffer
                    remaining() to 0
                }
                crIndex == -1 -> {
                    // Only \n found
                    lfIndex to 1
                }
                lfIndex == -1 -> {
                    // Only \r found
                    crIndex to 1
                }
                lfIndex < crIndex -> {
                    // \n comes first
                    lfIndex to 1
                }
                else -> {
                    // \r comes first (crIndex <= lfIndex)
                    // Check if followed immediately by \n (CRLF)
                    if (lfIndex == crIndex + 1) {
                        crIndex to 2 // \r\n
                    } else {
                        crIndex to 1 // Just \r
                    }
                }
            }

        val result = readString(contentLength, Charset.UTF8)
        position(startPos + contentLength + lineEndingSize)
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

        // Use managed (ByteArrayBuffer) so freeNativeMemory() is a no-op.
        // BufferFactory.Default creates NativeBuffer on Linux, which would be
        // permanently destroyed when any code calls freeIfNeeded() on this singleton.
        val EMPTY_BUFFER: PlatformBuffer = BufferFactory.managed().allocate(0).also { it.resetForRead() }
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
        check(numberOfBytes in 1..Long.SIZE_BYTES) { "byte size out of range" }
        // Decompose into bulk reads (readLong/readInt/readShort/readByte) instead of N readByte() calls.
        // E.g. 7 bytes → readInt(4) + readShort(2) + readByte(1), 3 calls instead of 7.
        var result = 0L
        var remaining = numberOfBytes
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            if (remaining >= Long.SIZE_BYTES) {
                return readLong()
            }
            if (remaining >= Int.SIZE_BYTES) {
                val intShift = (remaining - Int.SIZE_BYTES) * Byte.SIZE_BITS
                result = readInt().toLong() and BufferConstants.INT_MASK shl intShift
                remaining -= Int.SIZE_BYTES
            }
            if (remaining >= Short.SIZE_BYTES) {
                val shortShift = (remaining - Short.SIZE_BYTES) * Byte.SIZE_BITS
                val shortValue = readShort().toLong() and BufferConstants.SHORT_MASK.toLong()
                result = result or (shortValue shl shortShift)
                remaining -= Short.SIZE_BYTES
            }
            if (remaining >= 1) {
                result = result or (readByte().toLong() and BufferConstants.BYTE_MASK.toLong())
            }
        } else {
            var shift = 0
            if (remaining >= Long.SIZE_BYTES) {
                return readLong()
            }
            if (remaining >= Int.SIZE_BYTES) {
                result = readInt().toLong() and BufferConstants.INT_MASK
                shift = Int.SIZE_BITS
                remaining -= Int.SIZE_BYTES
            }
            if (remaining >= Short.SIZE_BYTES) {
                result = result or (readShort().toLong() and BufferConstants.SHORT_MASK.toLong() shl shift)
                shift += Short.SIZE_BITS
                remaining -= Short.SIZE_BYTES
            }
            if (remaining >= 1) {
                result = result or (readByte().toLong() and BufferConstants.BYTE_MASK.toLong() shl shift)
            }
        }
        return result
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
        check(numberOfBytes in 1..Long.SIZE_BYTES) { "byte size out of range" }
        val savedPos = position()
        position(startIndex)
        val result = readNumberWithByteSize(numberOfBytes)
        position(savedPos)
        return result
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

        // Reads cover [position, position+size) on both buffers (size == remaining), so every access
        // is provably in-bounds — use the unchecked accessors to skip per-element checks on non-JIT
        // backends (see getUnchecked).
        return bulkCompareEquals(
            thisPos = position(),
            otherPos = other.position(),
            length = size,
            getLong = { getLongUnchecked(it) },
            otherGetLong = { other.getLongUnchecked(it) },
            getByte = { getUnchecked(it) },
            otherGetByte = { other.getUnchecked(it) },
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

        // minLength <= remaining on both buffers, so all reads are in-bounds — unchecked accessors
        // skip per-element checks on non-JIT backends (see getUnchecked).
        return bulkMismatch(
            thisPos = position(),
            otherPos = other.position(),
            minLength = minLength,
            thisRemaining = thisRemaining,
            otherRemaining = otherRemaining,
            getLong = { getLongUnchecked(it) },
            otherGetLong = { other.getLongUnchecked(it) },
            getByte = { getUnchecked(it) },
            otherGetByte = { other.getUnchecked(it) },
        )
    }

    /**
     * Fast 64-bit content digest over the absolute byte range `[offset, offset + length)`.
     *
     * FNV-1a-64, mixing whole 8-byte words then the byte tail. Intended for hash-table bucketing where
     * collisions are resolved by an explicit [regionEquals]. The value is consistent across buffers
     * sharing the same [byteOrder]; it does not change [position]. Native backends override this to run
     * the whole digest in C with raw pointer arithmetic (no per-element pointer materialization).
     */
    fun hashRange(
        offset: Int,
        length: Int,
    ): Long {
        requireRange(offset, length)
        return fnv1aHashRange(FNV64_OFFSET_BASIS, offset, length, { getLongUnchecked(it) }, { getUnchecked(it) })
    }

    /**
     * Hex-encodes the absolute source range `[offset, offset + length)` into [dest], writing
     * `2 * length` ASCII hex bytes (high nibble first) at [dest]'s current position and advancing it.
     * Does not change this buffer's [position].
     *
     * "Zero-copy": the bytes flow straight from this buffer into [dest] with no intermediate
     * `String`/`ByteArray`. Native backends override this to run the whole transform in C over raw
     * pointers when [dest] is also native memory (see `buf_hex_encode`).
     *
     * @param dest destination buffer that receives the ASCII hex bytes
     * @param offset absolute index of the first source byte
     * @param length number of source bytes to encode
     * @param upperCase emit 'A'-'F' instead of 'a'-'f'
     */
    fun encodeHexInto(
        dest: WriteBuffer,
        offset: Int,
        length: Int,
        upperCase: Boolean = false,
    ) {
        requireRange(offset, length)
        encodeHexCommon(offset, length, upperCase, dest)
    }

    /**
     * Hex-decodes the absolute source range `[offset, offset + length)` (an even count of ASCII hex
     * bytes) into [dest], writing `length / 2` decoded bytes at [dest]'s current position and advancing
     * it. Does not change this buffer's [position].
     *
     * Native backends override this to run the whole transform in C over raw pointers when [dest] is
     * also native memory (see `buf_hex_decode`).
     *
     * @param dest destination buffer that receives the decoded bytes
     * @param offset absolute index of the first source hex byte
     * @param length number of source hex bytes (must be even)
     * @throws IllegalArgumentException if [length] is odd or a source byte is not a hex digit.
     */
    fun decodeHexInto(
        dest: WriteBuffer,
        offset: Int,
        length: Int,
    ) {
        requireRange(offset, length)
        decodeHexFallback(offset, length, { getUnchecked(it) }, { dest.writeByte(it) })
    }

    /**
     * Base64-encodes the absolute source range `[offset, offset + length)` (RFC 4648) into [dest] at
     * [dest]'s current position, advancing it by the encoded length. Does not change this buffer's
     * [position].
     *
     * Native backends override this to run the whole transform in C over raw pointers when [dest] is
     * also native memory (see `buf_base64_encode`).
     *
     * @param dest destination buffer that receives the ASCII Base64 bytes
     * @param offset absolute index of the first source byte
     * @param length number of source bytes to encode
     * @param urlSafe use the URL-safe alphabet ('-' '_') instead of the standard one ('+' '/')
     * @param padded append '=' padding so the output length is a multiple of 4
     */
    fun encodeBase64Into(
        dest: WriteBuffer,
        offset: Int,
        length: Int,
        urlSafe: Boolean = false,
        padded: Boolean = true,
    ) {
        requireRange(offset, length)
        encodeBase64Common(offset, length, urlSafe, padded, dest)
    }

    /**
     * Base64-decodes the absolute source range `[offset, offset + length)` into [dest] at [dest]'s
     * current position, advancing it by the decoded length. Accepts both the standard and URL-safe
     * alphabets and tolerates missing padding. Does not change this buffer's [position].
     *
     * Native backends override this to run the whole transform in C over raw pointers when [dest] is
     * also native memory (see `buf_base64_decode`).
     *
     * @param dest destination buffer that receives the decoded bytes
     * @param offset absolute index of the first source Base64 byte
     * @param length number of source Base64 bytes
     * @throws IllegalArgumentException if a source byte (before padding) is not a Base64 digit.
     */
    fun decodeBase64Into(
        dest: WriteBuffer,
        offset: Int,
        length: Int,
    ) {
        requireRange(offset, length)
        decodeBase64Fallback(offset, length, { getUnchecked(it) }, { dest.writeByte(it) })
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
     * @param aligned If true, only searches at 2-byte aligned positions from current position.
     *        This enables SIMD auto-vectorization on native platforms. Use when values were
     *        written with writeShort() or when data is known to be 2-byte aligned.
     * @return The relative index where the value starts (relative to current position),
     *         or -1 if not found
     */
    fun indexOf(
        value: Short,
        aligned: Boolean = false,
    ): Int {
        val size = remaining()
        if (size < 2) return -1

        val pos = position()
        val step = if (aligned) 2 else 1
        val searchLimit = size - 1

        for (i in 0 until searchLimit step step) {
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
     * @param aligned If true, only searches at 4-byte aligned positions from current position.
     *        This enables SIMD auto-vectorization on native platforms. Use when values were
     *        written with writeInt() or when data is known to be 4-byte aligned.
     * @return The relative index where the value starts (relative to current position),
     *         or -1 if not found
     */
    fun indexOf(
        value: Int,
        aligned: Boolean = false,
    ): Int {
        val size = remaining()
        if (size < Int.SIZE_BYTES) return -1

        val pos = position()
        val step = if (aligned) Int.SIZE_BYTES else 1
        val searchLimit = size - (Int.SIZE_BYTES - 1)

        for (i in 0 until searchLimit step step) {
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
     * @param aligned If true, only searches at 8-byte aligned positions from current position.
     *        This enables SIMD auto-vectorization on native platforms. Use when values were
     *        written with writeLong() or when data is known to be 8-byte aligned.
     * @return The relative index where the value starts (relative to current position),
     *         or -1 if not found
     */
    fun indexOf(
        value: Long,
        aligned: Boolean = false,
    ): Int {
        val size = remaining()
        if (size < Long.SIZE_BYTES) return -1

        val pos = position()
        val step = if (aligned) Long.SIZE_BYTES else 1
        val searchLimit = size - (Long.SIZE_BYTES - 1)

        for (i in 0 until searchLimit step step) {
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
        val needle = BufferFactory.Default.allocate(text.length * 4) // Max 4 bytes per char in UTF-8
        return try {
            needle.writeString(text, charset)
            needle.resetForRead()
            indexOf(needle)
        } finally {
            // The needle may be an owning native buffer (large Android / Linux Default
            // allocations); a no-op on GC-managed buffers. writeString can throw
            // (unsupported charsets on some platforms), so it must be inside the try.
            needle.freeNativeMemory()
        }
    }

    // ===== Bulk Primitive Read Operations =====
    // These methods read arrays of primitives efficiently.
    // Optimized implementations may use SIMD-like patterns (long-pairs)
    // for ~3x performance improvement on byte-swapping operations.

    /**
     * Reads [count] shorts from the buffer and advances position by `count * 2`.
     *
     * Uses the buffer's byte order for each short value.
     * Optimized implementations may process pairs of shorts as ints for better performance.
     *
     * @param count The number of shorts to read
     * @return A ShortArray containing the read values
     */
    fun readShorts(count: Int): ShortArray {
        val result = ShortArray(count)
        readShorts(result, 0, count)
        return result
    }

    /**
     * Reads shorts into the destination array.
     *
     * @param dest The destination short array
     * @param offset Starting index in the destination array
     * @param length Number of shorts to read
     * @return This buffer for chaining
     */
    fun readShorts(
        dest: ShortArray,
        offset: Int = 0,
        length: Int = dest.size - offset,
    ) {
        for (i in offset until offset + length) {
            dest[i] = readShort()
        }
    }

    /**
     * Reads [count] ints from the buffer and advances position by `count * 4`.
     *
     * Uses the buffer's byte order for each int value.
     * Optimized implementations may process pairs of ints as longs for ~3x better performance
     * when byte swapping is required.
     *
     * @param count The number of ints to read
     * @return An IntArray containing the read values
     */
    fun readInts(count: Int): IntArray {
        val result = IntArray(count)
        readInts(result, 0, count)
        return result
    }

    /**
     * Reads ints into the destination array.
     *
     * @param dest The destination int array
     * @param offset Starting index in the destination array
     * @param length Number of ints to read
     */
    fun readInts(
        dest: IntArray,
        offset: Int = 0,
        length: Int = dest.size - offset,
    ) {
        for (i in offset until offset + length) {
            dest[i] = readInt()
        }
    }

    /**
     * Reads [count] longs from the buffer and advances position by `count * 8`.
     *
     * Uses the buffer's byte order for each long value.
     *
     * @param count The number of longs to read
     * @return A LongArray containing the read values
     */
    fun readLongs(count: Int): LongArray {
        val result = LongArray(count)
        readLongs(result, 0, count)
        return result
    }

    /**
     * Reads longs into the destination array.
     *
     * @param dest The destination long array
     * @param offset Starting index in the destination array
     * @param length Number of longs to read
     */
    fun readLongs(
        dest: LongArray,
        offset: Int = 0,
        length: Int = dest.size - offset,
    ) {
        for (i in offset until offset + length) {
            dest[i] = readLong()
        }
    }

    /**
     * Reads [count] floats from the buffer and advances position by `count * 4`.
     *
     * @param count The number of floats to read
     * @return A FloatArray containing the read values
     */
    fun readFloats(count: Int): FloatArray {
        val result = FloatArray(count)
        readFloats(result, 0, count)
        return result
    }

    /**
     * Reads floats into the destination array.
     *
     * @param dest The destination float array
     * @param offset Starting index in the destination array
     * @param length Number of floats to read
     */
    fun readFloats(
        dest: FloatArray,
        offset: Int = 0,
        length: Int = dest.size - offset,
    ) {
        for (i in offset until offset + length) {
            dest[i] = readFloat()
        }
    }

    /**
     * Reads [count] doubles from the buffer and advances position by `count * 8`.
     *
     * @param count The number of doubles to read
     * @return A DoubleArray containing the read values
     */
    fun readDoubles(count: Int): DoubleArray {
        val result = DoubleArray(count)
        readDoubles(result, 0, count)
        return result
    }

    /**
     * Reads doubles into the destination array.
     *
     * @param dest The destination double array
     * @param offset Starting index in the destination array
     * @param length Number of doubles to read
     */
    fun readDoubles(
        dest: DoubleArray,
        offset: Int = 0,
        length: Int = dest.size - offset,
    ) {
        for (i in offset until offset + length) {
            dest[i] = readDouble()
        }
    }
}

/**
 * Recursively unwraps all wrapper types (PooledBuffer, TrackedSlice) to reach the
 * underlying concrete buffer implementation.
 *
 * Use this instead of `(buffer as? PlatformBuffer)?.unwrap() ?: buffer` to correctly
 * handle TrackedSlice, which implements ReadBuffer but NOT PlatformBuffer.
 */
@Suppress("DEPRECATION")
fun ReadBuffer.unwrapFully(): ReadBuffer {
    if (this is PlatformBuffer) {
        val unwrapped = unwrap()
        return if (unwrapped !== this) unwrapped.unwrapFully() else this
    }
    if (this is com.ditchoom.buffer.pool.TrackedSlice) {
        // Fail fast: resolving a released slice to its raw inner buffer would hand out
        // a reference to storage that may already be back in the pool's freelist. This
        // also gates the nativeMemoryAccess / managedMemoryAccess extensions and the
        // toNativeData/toByteArray interop bridges, which all resolve through here.
        checkNotReleased()
        return inner.unwrapFully()
    }
    return this
}

/**
 * Content-based equality for buffer implementations.
 * Two buffers are equal if they have the same remaining content (position to limit).
 * Accepts any [ReadBuffer], enabling cross-type comparison (e.g. NativeBuffer == NativeBufferSlice).
 */
fun bufferEquals(
    self: ReadBuffer,
    other: Any?,
): Boolean {
    if (self === other) return true
    if (other !is ReadBuffer) return false
    if (self.remaining() != other.remaining()) return false
    return self.contentEquals(other)
}

/** The documented stable multiplier of the rolling content hash (matches String.hashCode's 31). */
private const val HASH_MULTIPLIER = 31

/** Bit shifts extracting each byte (7..0) of a Long word during the unrolled hash fold. */
private const val SHIFT_BYTE7 = 56
private const val SHIFT_BYTE6 = 48
private const val SHIFT_BYTE5 = 40
private const val SHIFT_BYTE4 = 32
private const val SHIFT_BYTE3 = 24
private const val SHIFT_BYTE2 = 16
private const val SHIFT_BYTE1 = 8

/**
 * Content-based hash code for buffer implementations.
 * Hashes the remaining bytes (position to limit) using absolute reads to avoid side effects.
 */
fun bufferHashCode(self: ReadBuffer): Int {
    var h = 1
    val end = self.limit()
    var i = self.position()
    // Bulk path: read 8 bytes per memory access (one getLong instead of eight get()s — 8x fewer
    // accesses / pointer materializations on non-JIT backends), then fold each byte in index order.
    // Bit-identical to the per-byte 31-multiplier loop; the [position, limit) range is in-bounds so
    // the unchecked accessors are safe (see ReadBuffer.getUnchecked).
    if (self.byteOrder == ByteOrder.BIG_ENDIAN) {
        while (i + Long.SIZE_BYTES <= end) {
            val w = self.getLongUnchecked(i)
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE7).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE6).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE5).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE4).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE3).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE2).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE1).toByte().toInt()
            h = HASH_MULTIPLIER * h + w.toByte().toInt()
            i += Long.SIZE_BYTES
        }
    } else {
        while (i + Long.SIZE_BYTES <= end) {
            val w = self.getLongUnchecked(i)
            h = HASH_MULTIPLIER * h + w.toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE1).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE2).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE3).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE4).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE5).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE6).toByte().toInt()
            h = HASH_MULTIPLIER * h + (w shr SHIFT_BYTE7).toByte().toInt()
            i += Long.SIZE_BYTES
        }
    }
    while (i < end) {
        h = HASH_MULTIPLIER * h + self.getUnchecked(i).toInt()
        i++
    }
    return h
}
