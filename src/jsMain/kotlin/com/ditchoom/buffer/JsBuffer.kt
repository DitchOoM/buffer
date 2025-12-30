package com.ditchoom.buffer

import js.buffer.BufferSource
import js.buffer.SharedArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array
import web.encoding.TextDecoder
import web.encoding.TextDecoderOptions

data class JsBuffer(
    val buffer: Int8Array,
    // network endian is big endian
    private val littleEndian: Boolean = false,
    private var position: Int = 0,
    private var limit: Int = 0,
    override val capacity: Int = buffer.byteLength,
    val sharedArrayBuffer: SharedArrayBuffer? = null,
) : PlatformBuffer {
    override val byteOrder = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

    // Cached DataView for the entire buffer - avoids creating new DataView on each operation
    private val dataView = DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength)

    init {
        limit = buffer.length
    }

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

    fun setPosition(position: Int) {
        this.position = position
    }

    override fun readByte(): Byte = dataView.getInt8(position++)

    override fun get(index: Int): Byte = dataView.getInt8(index)

    override fun slice(): ReadBuffer =
        JsBuffer(
            // Zero-copy slice using subarray() which creates a view instead of copying
            buffer.subarray(position, limit),
            littleEndian,
            sharedArrayBuffer = sharedArrayBuffer,
        )

    override fun readByteArray(size: Int): ByteArray {
        val subArray = buffer.subarray(position, position + size)
        val byteArray = Int8Array(subArray.buffer, subArray.byteOffset, size)
        position += size
        return byteArray.unsafeCast<ByteArray>()
    }

    override fun readShort(): Short {
        val value = dataView.getInt16(position, littleEndian)
        position += Short.SIZE_BYTES
        return value.toInt().toShort()
    }

    override fun getShort(index: Int): Short = dataView.getInt16(index, littleEndian).toInt().toShort()

    override fun readInt(): Int {
        val value = dataView.getInt32(position, littleEndian)
        position += Int.SIZE_BYTES
        return value
    }

    override fun getInt(index: Int): Int = dataView.getInt32(index, littleEndian)

    override fun readLong(): Long {
        val long = getLong(position)
        position += Long.SIZE_BYTES
        return long
    }

    override fun getLong(index: Int): Long =
        if (littleEndian) {
            // Little endian: low 32 bits first, then high 32 bits
            val low = dataView.getInt32(index, true).toLong() and 0xFFFFFFFFL
            val high = dataView.getInt32(index + 4, true).toLong()
            (high shl 32) or low
        } else {
            // Big endian: high 32 bits first, then low 32 bits
            val high = dataView.getInt32(index, false).toLong()
            val low = dataView.getInt32(index + 4, false).toLong() and 0xFFFFFFFFL
            (high shl 32) or low
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
        position(position + length)
        return result
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        if (buffer is JsBuffer) {
            // Copy only the remaining portion (from position to limit)
            val sourceSubarray = buffer.buffer.subarray(buffer.position(), buffer.position() + size)
            this.buffer.set(sourceSubarray, position)
        } else {
            this.buffer.set(buffer.readByteArray(size).toTypedArray(), position)
        }
        position += size
        buffer.position(buffer.position() + size)
    }

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
        this.buffer.set(int8Array, position)
        position += int8Array.length
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        dataView.setInt16(position, short, littleEndian)
        position += Short.SIZE_BYTES
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
        position += Int.SIZE_BYTES
        return this
    }

    override fun set(
        index: Int,
        int: Int,
    ): WriteBuffer {
        dataView.setInt32(index, int, littleEndian)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        set(position, long)
        position += Long.SIZE_BYTES
        return this
    }

    override fun set(
        index: Int,
        long: Long,
    ): WriteBuffer {
        if (littleEndian) {
            // Little endian: low 32 bits first, then high 32 bits
            dataView.setInt32(index, long.toInt(), true)
            dataView.setInt32(index + 4, (long shr 32).toInt(), true)
        } else {
            // Big endian: high 32 bits first, then low 32 bits
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
        val size = remaining()
        try {
            if (!readByteArray(size).contentEquals(other.readByteArray(size))) return false
        } finally {
            position -= size
            other.position -= size
        }
        return true
    }

    override fun hashCode(): Int {
        var result = littleEndian.hashCode()
        result = 31 * result + position
        result = 31 * result + limit
        result = 31 * result + capacity.hashCode()
        val size = remaining()
        try {
            result = 31 * result + readByteArray(size).hashCode()
        } finally {
            position -= size
        }
        return result
    }
}
