package com.ditchoom.buffer

import js.buffer.BufferSource
import js.buffer.SharedArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array
import web.encoding.TextDecoder
import web.encoding.TextDecoderOptions

/**
 * High-performance JS buffer using Int8Array with cached DataView.
 *
 * Key optimizations:
 * - Cached DataView for all read/write operations (no allocation per operation)
 * - Zero-copy slice() using Int8Array view constructor
 * - Long read/write using two 32-bit operations (no ByteArray allocation)
 */
class JsBuffer(
    val buffer: Int8Array,
    private val littleEndian: Boolean = false,
    private var position: Int = 0,
    private var limit: Int = buffer.length,
    override val capacity: Int = buffer.byteLength,
    val sharedArrayBuffer: SharedArrayBuffer? = null,
) : PlatformBuffer {
    override val byteOrder = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

    // Cached DataView - reused for all operations, avoiding allocation per read/write
    private val dataView = DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength)

    override fun resetForRead() {
        limit = position
        position = 0
    }

    override fun resetForWrite() {
        position = 0
        limit = capacity
    }

    override fun setLimit(limit: Int) {
        this.limit = limit
    }

    // Use cached DataView with offset
    override fun readByte(): Byte = dataView.getInt8(position++)

    override fun get(index: Int): Byte = dataView.getInt8(index)

    // Zero-copy slice: creates a VIEW into the same ArrayBuffer, not a copy
    override fun slice(): ReadBuffer =
        JsBuffer(
            Int8Array(buffer.buffer, buffer.byteOffset + position, limit - position),
            littleEndian,
            sharedArrayBuffer = sharedArrayBuffer,
        )

    override fun readByteArray(size: Int): ByteArray {
        val subArray = buffer.subarray(position, position + size)
        position += size
        return subArray.unsafeCast<ByteArray>()
    }

    // Use cached DataView with position offset
    override fun readShort(): Short {
        val value = dataView.getInt16(position, littleEndian)
        position += 2
        return value.toInt().toShort()
    }

    override fun getShort(index: Int): Short = dataView.getInt16(index, littleEndian).toInt().toShort()

    override fun readInt(): Int {
        val value = dataView.getInt32(position, littleEndian)
        position += 4
        return value
    }

    override fun getInt(index: Int): Int = dataView.getInt32(index, littleEndian)

    // Optimized long read using two 32-bit reads - no ByteArray allocation
    override fun readLong(): Long {
        val first = dataView.getInt32(position, littleEndian).toLong() and 0xFFFFFFFFL
        val second = dataView.getInt32(position + 4, littleEndian).toLong() and 0xFFFFFFFFL
        position += 8
        return if (littleEndian) {
            (second shl 32) or first
        } else {
            (first shl 32) or second
        }
    }

    override fun getLong(index: Int): Long {
        val first = dataView.getInt32(index, littleEndian).toLong() and 0xFFFFFFFFL
        val second = dataView.getInt32(index + 4, littleEndian).toLong() and 0xFFFFFFFFL
        return if (littleEndian) {
            (second shl 32) or first
        } else {
            (first shl 32) or second
        }
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        val encoding =
            when (charset) {
                Charset.UTF8 -> "utf-8"
                Charset.UTF16 -> throw UnsupportedOperationException("Not sure how to implement")
                Charset.UTF16BigEndian -> "utf-16be"
                Charset.UTF16LittleEndian -> "utf-16le"
                Charset.ASCII -> "ascii"
                Charset.ISOLatin1 -> "iso-8859-1"
                Charset.UTF32 -> throw UnsupportedOperationException("Not sure how to implement")
                Charset.UTF32LittleEndian -> throw UnsupportedOperationException("Not sure how to implement")
                Charset.UTF32BigEndian -> throw UnsupportedOperationException("Not sure how to implement")
            }

        @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
        val textDecoder = TextDecoder(encoding, js("{fatal: true}") as TextDecoderOptions)
        val result =
            textDecoder.decode(
                buffer.subarray(position, position + length).unsafeCast<BufferSource>(),
            )
        position += length
        return result
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        if (buffer is JsBuffer) {
            // Zero-copy: set the Int8Array view directly
            this.buffer.set(buffer.buffer.subarray(buffer.position(), buffer.limit()), position)
        } else {
            // Fallback: read bytes (this creates allocation)
            val bytes = buffer.readByteArray(size)
            this.buffer.set(bytes.unsafeCast<Int8Array>(), position)
        }
        position += size
        buffer.position(buffer.position() + size)
    }

    // Use cached DataView with offset
    override fun writeByte(byte: Byte): WriteBuffer {
        dataView.setInt8(position++, byte)
        return this
    }

    override fun set(
        index: Int,
        byte: Byte,
    ): WriteBuffer {
        dataView.setInt8(index, byte)
        return this
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        val int8Array = bytes.unsafeCast<Int8Array>().subarray(offset, offset + length)
        buffer.set(int8Array, position)
        position += length
        return this
    }

    // Use cached DataView
    override fun writeShort(short: Short): WriteBuffer {
        dataView.setInt16(position, short, littleEndian)
        position += 2
        return this
    }

    override fun set(
        index: Int,
        short: Short,
    ): WriteBuffer {
        dataView.setInt16(index, short, littleEndian)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        dataView.setInt32(position, int, littleEndian)
        position += 4
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        dataView.setInt32(index, int, littleEndian)
        return this
    }

    // Optimized long write using two 32-bit writes - no ByteArray allocation
    override fun writeLong(long: Long): WriteBuffer {
        if (littleEndian) {
            dataView.setInt32(position, long.toInt(), true)
            dataView.setInt32(position + 4, (long shr 32).toInt(), true)
        } else {
            dataView.setInt32(position, (long shr 32).toInt(), false)
            dataView.setInt32(position + 4, long.toInt(), false)
        }
        position += 8
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        if (littleEndian) {
            dataView.setInt32(index, long.toInt(), true)
            dataView.setInt32(index + 4, (long shr 32).toInt(), true)
        } else {
            dataView.setInt32(index, (long shr 32).toInt(), false)
            dataView.setInt32(index + 4, long.toInt(), false)
        }
        return this
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        when (charset) {
            Charset.UTF8 -> writeBytes(text.toString().encodeToByteArray())
            else -> throw UnsupportedOperationException("Unable to encode in $charset. Must use Charset.UTF8")
        }
        return this
    }

    override fun limit() = limit

    override fun position() = position

    override fun position(newPosition: Int) {
        position = newPosition
    }

    override suspend fun close() {}

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class.js != other::class.js) return false

        other as JsBuffer

        if (littleEndian != other.littleEndian) return false
        if (position != other.position) return false
        if (limit != other.limit) return false
        if (capacity != other.capacity) return false

        // Compare byte by byte using DataView (no ByteArray allocation)
        val size = remaining()
        for (i in 0 until size) {
            if (dataView.getInt8(position + i) != other.dataView.getInt8(other.position + i)) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var result = littleEndian.hashCode()
        result = 31 * result + position
        result = 31 * result + limit
        result = 31 * result + capacity.hashCode()
        // Hash bytes using DataView (no ByteArray allocation)
        val size = remaining()
        for (i in 0 until size) {
            result = 31 * result + dataView.getInt8(position + i)
        }
        return result
    }
}
