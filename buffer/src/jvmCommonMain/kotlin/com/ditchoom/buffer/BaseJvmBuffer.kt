package com.ditchoom.buffer

import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

abstract class BaseJvmBuffer(
    open val byteBuffer: ByteBuffer,
    val fileRef: RandomAccessFile? = null,
) : PlatformBuffer {
    override val byteOrder =
        when (byteBuffer.order()) {
            java.nio.ByteOrder.BIG_ENDIAN -> ByteOrder.BIG_ENDIAN
            java.nio.ByteOrder.LITTLE_ENDIAN -> ByteOrder.LITTLE_ENDIAN
            else -> ByteOrder.BIG_ENDIAN
        }

    // Use Buffer reference to avoid NoSuchMethodException between JVM. see https://stackoverflow.com/q/61267495
    private val buffer = byteBuffer as Buffer

    override fun resetForRead() {
        buffer.flip()
    }

    override fun resetForWrite() {
        buffer.clear()
    }

    override fun setLimit(limit: Int) {
        buffer.limit(limit)
    }

    override val capacity = buffer.capacity()

    override fun readByte() = byteBuffer.get()

    override fun get(index: Int): Byte = byteBuffer.get(index)

    override fun readByteArray(size: Int) = byteBuffer.toArray(size)

    abstract override fun slice(): PlatformBuffer

    override fun readShort(): Short = byteBuffer.short

    override fun getShort(index: Int): Short = byteBuffer.getShort(index)

    override fun readInt() = byteBuffer.int

    override fun getInt(index: Int): Int = byteBuffer.getInt(index)

    override fun readLong() = byteBuffer.long

    override fun getLong(index: Int): Long = byteBuffer.getLong(index)

    override fun readFloat(): Float = byteBuffer.float

    override fun getFloat(index: Int): Float = byteBuffer.getFloat(index)

    override fun readDouble(): Double = byteBuffer.double

    override fun getDouble(index: Int): Double = byteBuffer.getDouble(index)

    override fun writeFloat(float: Float): WriteBuffer {
        try {
            byteBuffer.putFloat(float)
        } catch (e: java.nio.BufferOverflowException) {
            throw BufferOverflowException(
                "Buffer overflow: cannot write 4 byte(s) at position ${position()} " +
                    "(limit=${limit()}, remaining=${remaining()})",
            )
        }
        return this
    }

    override fun set(
        index: Int,
        float: Float,
    ): WriteBuffer {
        try {
            byteBuffer.putFloat(index, float)
        } catch (e: IndexOutOfBoundsException) {
            throw BufferOverflowException(
                "Index out of bounds: cannot write 4 byte(s) at index $index (limit=${limit()})",
            )
        }
        return this
    }

    override fun writeDouble(double: Double): WriteBuffer {
        try {
            byteBuffer.putDouble(double)
        } catch (e: java.nio.BufferOverflowException) {
            throw BufferOverflowException(
                "Buffer overflow: cannot write 8 byte(s) at position ${position()} " +
                    "(limit=${limit()}, remaining=${remaining()})",
            )
        }
        return this
    }

    override fun set(
        index: Int,
        double: Double,
    ): WriteBuffer {
        try {
            byteBuffer.putDouble(index, double)
        } catch (e: IndexOutOfBoundsException) {
            throw BufferOverflowException(
                "Index out of bounds: cannot write 8 byte(s) at index $index (limit=${limit()})",
            )
        }
        return this
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val finalPosition = buffer.position() + length
        val readBuffer = byteBuffer.asReadOnlyBuffer()
        (readBuffer as Buffer).limit(finalPosition)
        val charsetConverted =
            when (charset) {
                Charset.UTF8 -> Charsets.UTF_8
                Charset.UTF16 -> Charsets.UTF_16
                Charset.UTF16BigEndian -> Charsets.UTF_16BE
                Charset.UTF16LittleEndian -> Charsets.UTF_16LE
                Charset.ASCII -> Charsets.US_ASCII
                Charset.ISOLatin1 -> Charsets.ISO_8859_1
                Charset.UTF32 -> Charsets.UTF_32
                Charset.UTF32LittleEndian -> Charsets.UTF_32LE
                Charset.UTF32BigEndian -> Charsets.UTF_32BE
            }
        val decoded =
            charsetConverted
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(readBuffer)
        buffer.position(finalPosition)
        return decoded.toString()
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        try {
            byteBuffer.put(byte)
        } catch (e: java.nio.BufferOverflowException) {
            throw BufferOverflowException(
                "Buffer overflow: cannot write 1 byte(s) at position ${position()} " +
                    "(limit=${limit()}, remaining=${remaining()})",
            )
        }
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        try {
            byteBuffer.put(index, byte)
        } catch (e: IndexOutOfBoundsException) {
            throw BufferOverflowException(
                "Index out of bounds: cannot write 1 byte(s) at index $index (limit=${limit()})",
            )
        }
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        try {
            byteBuffer.put(bytes, offset, length)
        } catch (e: java.nio.BufferOverflowException) {
            throw BufferOverflowException(
                "Buffer overflow: cannot write $length byte(s) at position ${position()} " +
                    "(limit=${limit()}, remaining=${remaining()})",
            )
        }
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        try {
            byteBuffer.putShort(short)
        } catch (e: java.nio.BufferOverflowException) {
            throw BufferOverflowException(
                "Buffer overflow: cannot write 2 byte(s) at position ${position()} " +
                    "(limit=${limit()}, remaining=${remaining()})",
            )
        }
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        try {
            byteBuffer.putShort(index, short)
        } catch (e: IndexOutOfBoundsException) {
            throw BufferOverflowException(
                "Index out of bounds: cannot write 2 byte(s) at index $index (limit=${limit()})",
            )
        }
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        try {
            byteBuffer.putInt(int)
        } catch (e: java.nio.BufferOverflowException) {
            throw BufferOverflowException(
                "Buffer overflow: cannot write 4 byte(s) at position ${position()} " +
                    "(limit=${limit()}, remaining=${remaining()})",
            )
        }
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        try {
            byteBuffer.putInt(index, int)
        } catch (e: IndexOutOfBoundsException) {
            throw BufferOverflowException(
                "Index out of bounds: cannot write 4 byte(s) at index $index (limit=${limit()})",
            )
        }
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        try {
            byteBuffer.putLong(long)
        } catch (e: java.nio.BufferOverflowException) {
            throw BufferOverflowException(
                "Buffer overflow: cannot write 8 byte(s) at position ${position()} " +
                    "(limit=${limit()}, remaining=${remaining()})",
            )
        }
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        try {
            byteBuffer.putLong(index, long)
        } catch (e: IndexOutOfBoundsException) {
            throw BufferOverflowException(
                "Index out of bounds: cannot write 8 byte(s) at index $index (limit=${limit()})",
            )
        }
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        val encoder = charset.toEncoder()
        encoder.reset()
        encoder.encode(CharBuffer.wrap(text), byteBuffer, true)
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val actual = buffer.unwrapFully()
        try {
            if (actual is BaseJvmBuffer) {
                byteBuffer.put(actual.byteBuffer)
            } else {
                byteBuffer.put(buffer.readByteArray(buffer.remaining()))
            }
        } catch (e: java.nio.BufferOverflowException) {
            throw BufferOverflowException(
                "Buffer overflow: cannot write ${buffer.remaining()} byte(s) at position ${position()} " +
                    "(limit=${limit()}, remaining=${remaining()})",
            )
        }
    }

    /**
     * Optimized XOR mask using ByteBuffer getLong/putLong with forced BIG_ENDIAN.
     */
    override fun xorMask(
        mask: Int,
        maskOffset: Int,
    ) {
        if (mask == 0) return
        val pos = position()
        val lim = limit()
        val size = lim - pos
        if (size == 0) return

        // Rotate the mask so that mask byte at (maskOffset % 4) becomes byte 0
        val shift = (maskOffset and 3) * 8
        val rotatedMask =
            if (shift == 0) mask else (mask shl shift) or (mask ushr (32 - shift))

        // Create 8-byte mask (big-endian: rotatedMask repeated twice)
        val maskLong = (rotatedMask.toLong() shl 32) or (rotatedMask.toLong() and 0xFFFFFFFFL)

        // Use a duplicate with BIG_ENDIAN to avoid byte-order interference
        val dup = byteBuffer.duplicate()
        (dup as java.nio.Buffer).position(pos)
        (dup as java.nio.Buffer).limit(lim)
        dup.order(java.nio.ByteOrder.BIG_ENDIAN)

        var offset = pos
        // Process 8 bytes at a time
        while (offset + 8 <= lim) {
            val value = dup.getLong(offset)
            dup.putLong(offset, value xor maskLong)
            offset += 8
        }

        // Handle remaining bytes using the ORIGINAL mask with offset
        val maskByte0 = (mask ushr 24).toByte()
        val maskByte1 = (mask ushr 16).toByte()
        val maskByte2 = (mask ushr 8).toByte()
        val maskByte3 = mask.toByte()
        var i = offset - pos
        while (offset < lim) {
            val maskByte =
                when ((i + maskOffset) and 3) {
                    0 -> maskByte0
                    1 -> maskByte1
                    2 -> maskByte2
                    else -> maskByte3
                }
            byteBuffer.put(offset, (byteBuffer.get(offset).toInt() xor maskByte.toInt()).toByte())
            offset++
            i++
        }
    }

    /**
     * Optimized fused copy + XOR mask using ByteBuffer getLong/putLong.
     */
    override fun xorMaskCopy(
        source: ReadBuffer,
        mask: Int,
        maskOffset: Int,
    ) {
        val size = source.remaining()
        if (size == 0) return
        if (mask == 0) {
            write(source)
            return
        }

        // Rotate the mask so that mask byte at (maskOffset % 4) becomes byte 0
        val shift = (maskOffset and 3) * 8
        val rotatedMask =
            if (shift == 0) mask else (mask shl shift) or (mask ushr (32 - shift))
        val maskLong = (rotatedMask.toLong() shl 32) or (rotatedMask.toLong() and 0xFFFFFFFFL)

        val actualSource = source.unwrapFully()
        val srcBB =
            if (actualSource is BaseJvmBuffer) {
                val dup = actualSource.byteBuffer.duplicate()
                dup.order(java.nio.ByteOrder.BIG_ENDIAN)
                dup
            } else {
                null
            }

        val dstDup = byteBuffer.duplicate()
        (dstDup as java.nio.Buffer).position(position())
        dstDup.order(java.nio.ByteOrder.BIG_ENDIAN)

        var srcOff = source.position()
        var dstOff = position()
        var processed = 0

        if (srcBB != null) {
            // Fast path: both are JVM ByteBuffers, use getLong/putLong
            while (processed + 8 <= size) {
                val value = srcBB.getLong(srcOff)
                dstDup.putLong(dstOff, value xor maskLong)
                srcOff += 8
                dstOff += 8
                processed += 8
            }
        }

        // Handle remaining bytes (or all bytes if source wasn't a JVM buffer)
        val maskByte0 = (mask ushr 24).toByte()
        val maskByte1 = (mask ushr 16).toByte()
        val maskByte2 = (mask ushr 8).toByte()
        val maskByte3 = mask.toByte()

        while (processed < size) {
            val srcByte =
                if (srcBB != null) {
                    srcBB.get(srcOff)
                } else {
                    source.get(srcOff)
                }
            val maskByte =
                when ((processed + maskOffset) and 3) {
                    0 -> maskByte0
                    1 -> maskByte1
                    2 -> maskByte2
                    else -> maskByte3
                }
            byteBuffer.put(dstOff, (srcByte.toInt() xor maskByte.toInt()).toByte())
            srcOff++
            dstOff++
            processed++
        }

        source.position(srcOff)
        position(dstOff)
    }

    override fun position(newPosition: Int) {
        buffer.position(newPosition)
    }

    override fun equals(other: Any?): Boolean = bufferEquals(this, other)

    override fun hashCode(): Int = bufferHashCode(this)

    override fun toString() = "Buffer[pos=${position()} lim=${limit()} cap=$capacity]"

    open fun close() {
        fileRef?.close()
    }

    override fun limit() = buffer.limit()

    override fun position() = buffer.position()

    /**
     * Optimized content comparison using ByteBuffer.mismatch() on Java 11+ via multi-release JAR.
     * Falls back to default ReadBuffer.contentEquals() on Java 8 and Android.
     */
    override fun contentEquals(other: ReadBuffer): Boolean {
        if (remaining() != other.remaining()) return false
        return mismatch(other) == -1
    }

    /**
     * Optimized mismatch using ByteBuffer.mismatch() on Java 11+ via multi-release JAR.
     * Falls back to default ReadBuffer.mismatch() on Java 8 and Android.
     */
    override fun mismatch(other: ReadBuffer): Int {
        val actualOther = other.unwrapFully()
        if (actualOther is BaseJvmBuffer) {
            val result = BufferMismatchHelper.mismatch(byteBuffer, actualOther.byteBuffer)
            if (result != USE_DEFAULT_MISMATCH) {
                return result
            }
        }
        return super.mismatch(other)
    }

    /**
     * Optimized single byte indexOf using native array operations.
     */
    override fun indexOf(byte: Byte): Int {
        val pos = position()
        val remaining = remaining()

        if (byteBuffer.hasArray()) {
            val array = byteBuffer.array()
            val offset = byteBuffer.arrayOffset() + pos
            for (i in 0 until remaining) {
                if (array[offset + i] == byte) {
                    return i
                }
            }
            return -1
        }

        // Direct buffer - use get()
        for (i in 0 until remaining) {
            if (byteBuffer.get(pos + i) == byte) {
                return i
            }
        }
        return -1
    }

    /**
     * Optimized Short indexOf using ByteBuffer operations.
     */
    override fun indexOf(
        value: Short,
        aligned: Boolean,
    ): Int {
        val pos = position()
        val remaining = remaining()
        if (remaining < 2) return -1

        val step = if (aligned) 2 else 1
        val searchLimit = remaining - 1
        for (i in 0 until searchLimit step step) {
            if (byteBuffer.getShort(pos + i) == value) {
                return i
            }
        }
        return -1
    }

    /**
     * Optimized Int indexOf using ByteBuffer operations.
     */
    override fun indexOf(
        value: Int,
        aligned: Boolean,
    ): Int {
        val pos = position()
        val remaining = remaining()
        if (remaining < 4) return -1

        val step = if (aligned) 4 else 1
        val searchLimit = remaining - 3
        for (i in 0 until searchLimit step step) {
            if (byteBuffer.getInt(pos + i) == value) {
                return i
            }
        }
        return -1
    }

    /**
     * Optimized Long indexOf using ByteBuffer operations.
     */
    override fun indexOf(
        value: Long,
        aligned: Boolean,
    ): Int {
        val pos = position()
        val remaining = remaining()
        if (remaining < 8) return -1

        val step = if (aligned) 8 else 1
        val searchLimit = remaining - 7
        for (i in 0 until searchLimit step step) {
            if (byteBuffer.getLong(pos + i) == value) {
                return i
            }
        }
        return -1
    }

    /**
     * Optimized fill using Arrays.fill for heap buffers.
     */
    override fun fill(value: Byte): WriteBuffer {
        val count = remaining()
        if (count == 0) return this

        if (byteBuffer.hasArray()) {
            val array = byteBuffer.array()
            val offset = byteBuffer.arrayOffset() + position()
            java.util.Arrays.fill(array, offset, offset + count, value)
            buffer.position(buffer.position() + count)
        } else {
            // Direct buffer - write byte by byte
            for (i in 0 until count) {
                byteBuffer.put(value)
            }
        }
        return this
    }
}

fun ByteBuffer.toArray(size: Int = remaining()): ByteArray =
    if (hasArray()) {
        val result = ByteArray(size)
        val buffer = this as Buffer
        System.arraycopy(this.array(), buffer.arrayOffset() + buffer.position(), result, 0, size)
        buffer.position(buffer.position() + size)
        result
    } else {
        val byteArray = ByteArray(size)
        get(byteArray)
        byteArray
    }
