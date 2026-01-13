@file:Suppress("unused") // Loaded at runtime via multi-release JAR

package com.ditchoom.buffer

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * FFM-based BufferScope implementation for Java 21+.
 *
 * Uses Arena.ofConfined() for deterministic memory management.
 * All allocations are freed when the arena is closed.
 */
class FfmBufferScope : BufferScope {
    private val arena: Arena = Arena.ofConfined()

    override val isOpen: Boolean get() = arena.scope().isAlive

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): ScopedBuffer {
        check(isOpen) { "BufferScope is closed" }
        val segment = arena.allocate(size.toLong())
        return FfmScopedBuffer(this, segment, size, byteOrder)
    }

    override fun allocateAligned(
        size: Int,
        alignment: Int,
        byteOrder: ByteOrder,
    ): ScopedBuffer {
        check(isOpen) { "BufferScope is closed" }
        require(alignment > 0 && (alignment and (alignment - 1)) == 0) {
            "Alignment must be a positive power of 2, got: $alignment"
        }
        val segment = arena.allocate(size.toLong(), alignment.toLong())
        return FfmScopedBuffer(this, segment, size, byteOrder)
    }

    override fun close() {
        if (isOpen) {
            arena.close()
        }
    }
}

/**
 * FFM-based ScopedBuffer implementation for Java 21+.
 *
 * Uses MemorySegment for direct memory access with bounds checking.
 */
class FfmScopedBuffer(
    override val scope: BufferScope,
    private val segment: MemorySegment,
    override val capacity: Int,
    override val byteOrder: ByteOrder,
) : ScopedBuffer {
    private var positionValue: Int = 0
    private var limitValue: Int = capacity

    override val nativeAddress: Long get() = segment.address()
    override val nativeSize: Long get() = capacity.toLong()

    private val littleEndian = byteOrder == ByteOrder.LITTLE_ENDIAN

    private fun javaByteOrder(): java.nio.ByteOrder =
        if (littleEndian) {
            java.nio.ByteOrder.LITTLE_ENDIAN
        } else {
            java.nio.ByteOrder.BIG_ENDIAN
        }

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
    override fun readByte(): Byte = segment.get(ValueLayout.JAVA_BYTE, positionValue++.toLong())

    override fun readShort(): Short {
        val layout = ValueLayout.JAVA_SHORT.withOrder(javaByteOrder())
        val value = segment.get(layout, positionValue.toLong())
        positionValue += 2
        return value
    }

    override fun readInt(): Int {
        val layout = ValueLayout.JAVA_INT.withOrder(javaByteOrder())
        val value = segment.get(layout, positionValue.toLong())
        positionValue += 4
        return value
    }

    override fun readLong(): Long {
        val layout = ValueLayout.JAVA_LONG.withOrder(javaByteOrder())
        val value = segment.get(layout, positionValue.toLong())
        positionValue += 8
        return value
    }

    // Read operations (absolute)
    override fun get(index: Int): Byte = segment.get(ValueLayout.JAVA_BYTE, index.toLong())

    override fun getShort(index: Int): Short {
        val layout = ValueLayout.JAVA_SHORT.withOrder(javaByteOrder())
        return segment.get(layout, index.toLong())
    }

    override fun getInt(index: Int): Int {
        val layout = ValueLayout.JAVA_INT.withOrder(javaByteOrder())
        return segment.get(layout, index.toLong())
    }

    override fun getLong(index: Int): Long {
        val layout = ValueLayout.JAVA_LONG.withOrder(javaByteOrder())
        return segment.get(layout, index.toLong())
    }

    // Write operations (relative)
    override fun writeByte(byte: Byte): WriteBuffer {
        segment.set(ValueLayout.JAVA_BYTE, positionValue++.toLong(), byte)
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        val layout = ValueLayout.JAVA_SHORT.withOrder(javaByteOrder())
        segment.set(layout, positionValue.toLong(), short)
        positionValue += 2
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        val layout = ValueLayout.JAVA_INT.withOrder(javaByteOrder())
        segment.set(layout, positionValue.toLong(), int)
        positionValue += 4
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        val layout = ValueLayout.JAVA_LONG.withOrder(javaByteOrder())
        segment.set(layout, positionValue.toLong(), long)
        positionValue += 8
        return this
    }

    // Write operations (absolute)
    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        segment.set(ValueLayout.JAVA_BYTE, index.toLong(), byte)
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        val layout = ValueLayout.JAVA_SHORT.withOrder(javaByteOrder())
        segment.set(layout, index.toLong(), short)
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        val layout = ValueLayout.JAVA_INT.withOrder(javaByteOrder())
        segment.set(layout, index.toLong(), int)
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        val layout = ValueLayout.JAVA_LONG.withOrder(javaByteOrder())
        segment.set(layout, index.toLong(), long)
        return this
    }

    // Bulk operations
    override fun readByteArray(size: Int): ByteArray {
        val array = ByteArray(size)
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, positionValue.toLong(), array, 0, size)
        positionValue += size
        return array
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        MemorySegment.copy(bytes, offset, segment, ValueLayout.JAVA_BYTE, positionValue.toLong(), length)
        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        if (buffer is FfmScopedBuffer) {
            MemorySegment.copy(
                buffer.segment,
                buffer.positionValue.toLong(),
                segment,
                positionValue.toLong(),
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

    override fun slice(): ReadBuffer {
        val slicedSegment = segment.asSlice(positionValue.toLong(), remaining().toLong())
        return FfmScopedBuffer(scope, slicedSegment, remaining(), byteOrder)
    }

    // String operations
    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val bytes = readByteArray(length)
        return when (charset) {
            Charset.UTF8 -> bytes.decodeToString()
            Charset.UTF16 -> String(bytes, Charsets.UTF_16)
            Charset.UTF16BigEndian -> String(bytes, Charsets.UTF_16BE)
            Charset.UTF16LittleEndian -> String(bytes, Charsets.UTF_16LE)
            Charset.ASCII -> String(bytes, Charsets.US_ASCII)
            Charset.ISOLatin1 -> String(bytes, Charsets.ISO_8859_1)
            Charset.UTF32 ->
                String(
                    bytes,
                    java.nio.charset.Charset
                        .forName("UTF-32"),
                )
            Charset.UTF32BigEndian ->
                String(
                    bytes,
                    java.nio.charset.Charset
                        .forName("UTF-32BE"),
                )
            Charset.UTF32LittleEndian ->
                String(
                    bytes,
                    java.nio.charset.Charset
                        .forName("UTF-32LE"),
                )
        }
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        val bytes =
            when (charset) {
                Charset.UTF8 -> text.toString().encodeToByteArray()
                Charset.UTF16 -> text.toString().toByteArray(Charsets.UTF_16)
                Charset.UTF16BigEndian -> text.toString().toByteArray(Charsets.UTF_16BE)
                Charset.UTF16LittleEndian -> text.toString().toByteArray(Charsets.UTF_16LE)
                Charset.ASCII -> text.toString().toByteArray(Charsets.US_ASCII)
                Charset.ISOLatin1 -> text.toString().toByteArray(Charsets.ISO_8859_1)
                Charset.UTF32 ->
                    text.toString().toByteArray(
                        java.nio.charset.Charset
                            .forName("UTF-32"),
                    )
                Charset.UTF32BigEndian ->
                    text.toString().toByteArray(
                        java.nio.charset.Charset
                            .forName("UTF-32BE"),
                    )
                Charset.UTF32LittleEndian ->
                    text.toString().toByteArray(
                        java.nio.charset.Charset
                            .forName("UTF-32LE"),
                    )
            }
        writeBytes(bytes)
        return this
    }
}
