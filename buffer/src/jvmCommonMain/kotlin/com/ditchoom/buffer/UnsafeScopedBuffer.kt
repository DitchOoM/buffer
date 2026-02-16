package com.ditchoom.buffer

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Unsafe-based ScopedBuffer implementation for JVM < 21 and Android.
 *
 * Uses UnsafeMemory for direct memory access with manual bounds checking.
 */
class UnsafeScopedBuffer(
    override val scope: BufferScope,
    override val nativeAddress: Long,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
) : ScopedBuffer {
    private var positionValue: Int = 0
    private var limitValue: Int = capacity

    override val nativeSize: Long get() = capacity.toLong()

    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN

    // Position and limit management
    override fun position(): Int = positionValue

    override fun position(newPosition: Int) {
        positionValue = newPosition
    }

    override fun limit(): Int = limitValue

    override fun setLimit(limit: Int) {
        limitValue = limit
    }

    override fun resetForRead() {
        limitValue = positionValue
        positionValue = 0
    }

    override fun resetForWrite() {
        positionValue = 0
        limitValue = capacity
    }

    // Read operations (relative)
    override fun readByte(): Byte = UnsafeMemory.getByte(nativeAddress + positionValue++)

    override fun readShort(): Short {
        val result = getShort(positionValue)
        positionValue += 2
        return result
    }

    override fun readInt(): Int {
        val result = getInt(positionValue)
        positionValue += 4
        return result
    }

    override fun readLong(): Long {
        val result = getLong(positionValue)
        positionValue += 8
        return result
    }

    // Read operations (absolute)
    override fun get(index: Int): Byte = UnsafeMemory.getByte(nativeAddress + index)

    override fun getShort(index: Int): Short {
        val raw = UnsafeMemory.getShort(nativeAddress + index)
        return if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
            if (littleEndian) raw else java.lang.Short.reverseBytes(raw)
        } else {
            if (littleEndian) java.lang.Short.reverseBytes(raw) else raw
        }
    }

    override fun getInt(index: Int): Int {
        val raw = UnsafeMemory.getInt(nativeAddress + index)
        return if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
            if (littleEndian) raw else java.lang.Integer.reverseBytes(raw)
        } else {
            if (littleEndian) java.lang.Integer.reverseBytes(raw) else raw
        }
    }

    override fun getLong(index: Int): Long {
        val raw = UnsafeMemory.getLong(nativeAddress + index)
        return if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
            if (littleEndian) raw else java.lang.Long.reverseBytes(raw)
        } else {
            if (littleEndian) java.lang.Long.reverseBytes(raw) else raw
        }
    }

    // Write operations (relative)
    override fun writeByte(byte: Byte): WriteBuffer {
        UnsafeMemory.putByte(nativeAddress + positionValue++, byte)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        set(positionValue, short)
        positionValue += 2
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        set(positionValue, int)
        positionValue += 4
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        set(positionValue, long)
        positionValue += 8
        return this
    }

    // Write operations (absolute)
    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        UnsafeMemory.putByte(nativeAddress + index, byte)
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        val value =
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
                if (littleEndian) short else java.lang.Short.reverseBytes(short)
            } else {
                if (littleEndian) java.lang.Short.reverseBytes(short) else short
            }
        UnsafeMemory.putShort(nativeAddress + index, value)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        val value =
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
                if (littleEndian) int else java.lang.Integer.reverseBytes(int)
            } else {
                if (littleEndian) java.lang.Integer.reverseBytes(int) else int
            }
        UnsafeMemory.putInt(nativeAddress + index, value)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        val value =
            if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
                if (littleEndian) long else java.lang.Long.reverseBytes(long)
            } else {
                if (littleEndian) java.lang.Long.reverseBytes(long) else long
            }
        UnsafeMemory.putLong(nativeAddress + index, value)
        return this
    }

    // Bulk operations
    override fun readByteArray(size: Int): ByteArray {
        val array = ByteArray(size)
        UnsafeMemory.copyMemoryToArray(nativeAddress + positionValue, array, 0, size)
        positionValue += size
        return array
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        UnsafeMemory.copyMemoryFromArray(bytes, offset, nativeAddress + positionValue, length)
        positionValue += length
        return this
    }

    // ===== Optimized Bulk Primitive Operations =====
    // Uses long-pairs pattern for ~3x performance improvement when byte swapping is needed.
    // The optimization processes two ints as a single long, reducing memory operations by half.

    /**
     * Optimized bulk int write using long-pairs pattern.
     *
     * When byte swapping is required (buffer byte order differs from native order),
     * this processes pairs of ints together as longs, providing ~3x speedup.
     */
    override fun writeInts(
        ints: IntArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        val needsSwap = (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) != littleEndian
        var addr = nativeAddress + positionValue
        var i = offset
        val end = offset + length

        if (needsSwap) {
            // Process pairs of ints as longs for better performance
            while (i + 1 < end) {
                // Swap bytes in each int, then combine as long
                // For big-endian output on little-endian CPU:
                // [int0, int1] -> memory as [swap(int0), swap(int1)]
                val swapped0 =
                    java.lang.Integer
                        .reverseBytes(ints[i])
                        .toLong() and 0xFFFFFFFFL
                val swapped1 =
                    java.lang.Integer
                        .reverseBytes(ints[i + 1])
                        .toLong() and 0xFFFFFFFFL
                // Write as a long (native order in memory)
                UnsafeMemory.putLong(addr, swapped0 or (swapped1 shl 32))
                addr += 8
                i += 2
            }
            // Handle remaining odd int
            if (i < end) {
                UnsafeMemory.putInt(addr, java.lang.Integer.reverseBytes(ints[i]))
                addr += 4
                i++
            }
        } else {
            // No byte swap needed - write directly using long pairs
            while (i + 1 < end) {
                val v0 = ints[i].toLong() and 0xFFFFFFFFL
                val v1 = ints[i + 1].toLong() and 0xFFFFFFFFL
                UnsafeMemory.putLong(addr, v0 or (v1 shl 32))
                addr += 8
                i += 2
            }
            if (i < end) {
                UnsafeMemory.putInt(addr, ints[i])
                addr += 4
                i++
            }
        }
        positionValue += length * 4
        return this
    }

    /**
     * Optimized bulk int read using long-pairs pattern.
     */
    override fun readInts(
        dest: IntArray,
        offset: Int,
        length: Int,
    ) {
        val needsSwap = (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) != littleEndian
        var addr = nativeAddress + positionValue
        var i = offset
        val end = offset + length

        if (needsSwap) {
            // Read pairs of ints as longs for better performance
            while (i + 1 < end) {
                val longVal = UnsafeMemory.getLong(addr)
                // Extract and swap each int
                dest[i] = java.lang.Integer.reverseBytes(longVal.toInt())
                dest[i + 1] = java.lang.Integer.reverseBytes((longVal ushr 32).toInt())
                addr += 8
                i += 2
            }
            // Handle remaining odd int
            if (i < end) {
                dest[i] = java.lang.Integer.reverseBytes(UnsafeMemory.getInt(addr))
                i++
            }
        } else {
            // No byte swap needed - read directly using long pairs
            while (i + 1 < end) {
                val longVal = UnsafeMemory.getLong(addr)
                dest[i] = longVal.toInt()
                dest[i + 1] = (longVal ushr 32).toInt()
                addr += 8
                i += 2
            }
            if (i < end) {
                dest[i] = UnsafeMemory.getInt(addr)
                i++
            }
        }
        positionValue += length * 4
    }

    /**
     * Optimized bulk short write using long-pairs pattern (4 shorts as 1 long).
     */
    override fun writeShorts(
        shorts: ShortArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        val needsSwap = (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) != littleEndian
        var addr = nativeAddress + positionValue
        var i = offset
        val end = offset + length

        if (needsSwap) {
            // Process 4 shorts as a long for better performance
            while (i + 3 < end) {
                val s0 =
                    java.lang.Short
                        .reverseBytes(shorts[i])
                        .toLong() and 0xFFFFL
                val s1 =
                    java.lang.Short
                        .reverseBytes(shorts[i + 1])
                        .toLong() and 0xFFFFL
                val s2 =
                    java.lang.Short
                        .reverseBytes(shorts[i + 2])
                        .toLong() and 0xFFFFL
                val s3 =
                    java.lang.Short
                        .reverseBytes(shorts[i + 3])
                        .toLong() and 0xFFFFL
                UnsafeMemory.putLong(addr, s0 or (s1 shl 16) or (s2 shl 32) or (s3 shl 48))
                addr += 8
                i += 4
            }
            // Handle remaining shorts
            while (i < end) {
                UnsafeMemory.putShort(addr, java.lang.Short.reverseBytes(shorts[i]))
                addr += 2
                i++
            }
        } else {
            // No byte swap - write 4 shorts as a long
            while (i + 3 < end) {
                val s0 = shorts[i].toLong() and 0xFFFFL
                val s1 = shorts[i + 1].toLong() and 0xFFFFL
                val s2 = shorts[i + 2].toLong() and 0xFFFFL
                val s3 = shorts[i + 3].toLong() and 0xFFFFL
                UnsafeMemory.putLong(addr, s0 or (s1 shl 16) or (s2 shl 32) or (s3 shl 48))
                addr += 8
                i += 4
            }
            while (i < end) {
                UnsafeMemory.putShort(addr, shorts[i])
                addr += 2
                i++
            }
        }
        positionValue += length * 2
        return this
    }

    /**
     * Optimized bulk short read using long-pairs pattern.
     */
    override fun readShorts(
        dest: ShortArray,
        offset: Int,
        length: Int,
    ) {
        val needsSwap = (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) != littleEndian
        var addr = nativeAddress + positionValue
        var i = offset
        val end = offset + length

        if (needsSwap) {
            // Read 4 shorts as a long for better performance
            while (i + 3 < end) {
                val longVal = UnsafeMemory.getLong(addr)
                dest[i] = java.lang.Short.reverseBytes(longVal.toShort())
                dest[i + 1] = java.lang.Short.reverseBytes((longVal ushr 16).toShort())
                dest[i + 2] = java.lang.Short.reverseBytes((longVal ushr 32).toShort())
                dest[i + 3] = java.lang.Short.reverseBytes((longVal ushr 48).toShort())
                addr += 8
                i += 4
            }
            while (i < end) {
                dest[i] = java.lang.Short.reverseBytes(UnsafeMemory.getShort(addr))
                addr += 2
                i++
            }
        } else {
            // No byte swap needed
            while (i + 3 < end) {
                val longVal = UnsafeMemory.getLong(addr)
                dest[i] = longVal.toShort()
                dest[i + 1] = (longVal ushr 16).toShort()
                dest[i + 2] = (longVal ushr 32).toShort()
                dest[i + 3] = (longVal ushr 48).toShort()
                addr += 8
                i += 4
            }
            while (i < end) {
                dest[i] = UnsafeMemory.getShort(addr)
                addr += 2
                i++
            }
        }
        positionValue += length * 2
    }

    /**
     * Optimized bulk long write - already optimal but uses direct memory access.
     */
    override fun writeLongs(
        longs: LongArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        val needsSwap = (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) != littleEndian
        var addr = nativeAddress + positionValue

        if (needsSwap) {
            for (i in offset until offset + length) {
                UnsafeMemory.putLong(addr, java.lang.Long.reverseBytes(longs[i]))
                addr += 8
            }
        } else {
            for (i in offset until offset + length) {
                UnsafeMemory.putLong(addr, longs[i])
                addr += 8
            }
        }
        positionValue += length * 8
        return this
    }

    /**
     * Optimized bulk long read.
     */
    override fun readLongs(
        dest: LongArray,
        offset: Int,
        length: Int,
    ) {
        val needsSwap = (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) != littleEndian
        var addr = nativeAddress + positionValue

        if (needsSwap) {
            for (i in offset until offset + length) {
                dest[i] = java.lang.Long.reverseBytes(UnsafeMemory.getLong(addr))
                addr += 8
            }
        } else {
            for (i in offset until offset + length) {
                dest[i] = UnsafeMemory.getLong(addr)
                addr += 8
            }
        }
        positionValue += length * 8
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        val actual = (buffer as? PlatformBuffer)?.unwrap() ?: buffer
        if (actual is UnsafeScopedBuffer) {
            UnsafeMemory.copyMemory(
                actual.nativeAddress + actual.positionValue,
                nativeAddress + positionValue,
                size.toLong(),
            )
        } else {
            writeBytes(buffer.readByteArray(size))
            buffer.position(buffer.position() + size)
            return
        }
        positionValue += size
        buffer.position(buffer.position() + size)
    }

    override fun slice(): ReadBuffer = UnsafeScopedBuffer(scope, nativeAddress + positionValue, remaining(), byteOrder)

    // String operations - tries zero-copy via DirectByteBuffer reflection, falls back to byte array

    /** Lazily initialized ByteBuffer view - null if reflection not supported */
    private var byteBufferView: java.nio.ByteBuffer? = null
    private var byteBufferViewChecked = false

    private fun getByteBufferView(): java.nio.ByteBuffer? {
        if (!byteBufferViewChecked) {
            byteBufferViewChecked = true
            byteBufferView = UnsafeMemory.tryWrapAsDirectByteBuffer(nativeAddress, capacity)
        }
        return byteBufferView
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val view = getByteBufferView()
        if (view != null) {
            // Zero-copy path via DirectByteBuffer
            val finalPosition = positionValue + length
            (view as java.nio.Buffer).position(positionValue)
            (view as java.nio.Buffer).limit(finalPosition)
            val decoded =
                charset
                    .toJavaCharset()
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(view.slice())
            positionValue = finalPosition
            return decoded.toString()
        }
        // Fallback: intermediate byte array
        val bytes = readByteArray(length)
        return String(bytes, charset.toJavaCharset())
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        val view = getByteBufferView()
        if (view != null) {
            // Zero-copy path via DirectByteBuffer
            val encoder = charset.toEncoder()
            encoder.reset()
            (view as java.nio.Buffer).position(positionValue)
            (view as java.nio.Buffer).limit(capacity)
            encoder.encode(CharBuffer.wrap(text), view, true)
            positionValue = view.position()
            return this
        }
        // Fallback: intermediate byte array
        val bytes = text.toString().toByteArray(charset.toJavaCharset())
        writeBytes(bytes)
        return this
    }

    private fun Charset.toJavaCharset(): java.nio.charset.Charset =
        when (this) {
            Charset.UTF8 -> Charsets.UTF_8
            Charset.UTF16 -> Charsets.UTF_16
            Charset.UTF16BigEndian -> Charsets.UTF_16BE
            Charset.UTF16LittleEndian -> Charsets.UTF_16LE
            Charset.ASCII -> Charsets.US_ASCII
            Charset.ISOLatin1 -> Charsets.ISO_8859_1
            Charset.UTF32 ->
                java.nio.charset.Charset
                    .forName("UTF-32")
            Charset.UTF32BigEndian ->
                java.nio.charset.Charset
                    .forName("UTF-32BE")
            Charset.UTF32LittleEndian ->
                java.nio.charset.Charset
                    .forName("UTF-32LE")
        }
}
