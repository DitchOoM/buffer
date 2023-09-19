package com.ditchoom.buffer

import js.buffer.BufferSource
import js.buffer.SharedArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import web.encoding.TextDecoder
import web.encoding.TextDecoderOptions

data class JsBuffer(
    val buffer: Uint8Array,
    private val littleEndian: Boolean = false, // network endian is big endian
    private var position: Int = 0,
    private var limit: Int = 0,
    override val capacity: Int = buffer.byteLength,
    val sharedArrayBuffer: SharedArrayBuffer? = null
) : PlatformBuffer {
    override val byteOrder = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

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

    override fun readByte(): Byte {
        val dataView = DataView(buffer.buffer, position++, 1)
        return dataView.getInt8(0)
    }

    override fun get(index: Int): Byte {
        val dataView = DataView(buffer.buffer, index, 1)
        return dataView.getInt8(0)
    }

    override fun slice(): ReadBuffer {
        return JsBuffer(
            Uint8Array(buffer.buffer.slice(position, limit)),
            littleEndian,
            sharedArrayBuffer = sharedArrayBuffer
        )
    }

    override fun readByteArray(size: Int): ByteArray {
        val byteArray = Int8Array(buffer.buffer, position, size).unsafeCast<ByteArray>()
        position += size
        return byteArray
    }

    override fun readShort(): Short {
        val dataView = DataView(buffer.buffer, position, Short.SIZE_BYTES)
        position += Short.SIZE_BYTES
        return dataView.getInt16(0, littleEndian).toInt().toShort()
    }

    override fun getShort(index: Int): Short {
        val dataView = DataView(buffer.buffer, index, Short.SIZE_BYTES)
        return dataView.getInt16(0, littleEndian).toInt().toShort()
    }

    override fun readInt(): Int {
        val dataView = DataView(buffer.buffer, position, Int.SIZE_BYTES)
        position += Int.SIZE_BYTES
        return dataView.getInt32(0, littleEndian)
    }

    override fun getInt(index: Int): Int {
        val dataView = DataView(buffer.buffer, index, Int.SIZE_BYTES)
        return dataView.getInt32(0, littleEndian)
    }

    override fun readLong(): Long {
        val bytes = readByteArray(Long.SIZE_BYTES)
        return if (littleEndian) bytes.reversedArray().toLong() else bytes.toLong()
    }

    override fun getLong(index: Int): Long {
        val bytes = Int8Array(buffer.buffer, index, Long.SIZE_BYTES).unsafeCast<ByteArray>()
        return if (littleEndian) bytes.reversedArray().toLong() else bytes.toLong()
    }

    private fun ByteArray.toLong(): Long {
        var result: Long = 0
        (0 until Long.SIZE_BYTES).forEach {
            result = result shl Long.SIZE_BYTES
            result = result or (this[it].toLong() and 0xFFL)
        }
        return result
    }

    override fun readString(length: Int, charset: Charset): String {
        val encoding = when (charset) {
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
        val textDecoder = TextDecoder(encoding, js("{fatal: true}") as TextDecoderOptions)
        val result = textDecoder.decode(
            buffer.subarray(position, position + length).unsafeCast<BufferSource>()
        )
        position(position + length)
        return result
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.limit() - buffer.position()
        if (buffer is JsBuffer) {
            this.buffer.set(buffer.buffer, position)
        } else {
            this.buffer.set(buffer.readByteArray(size).toTypedArray(), position)
        }
        position += size
    }

    override fun writeByte(byte: Byte): WriteBuffer {
        val dataView = DataView(buffer.buffer, position++, Byte.SIZE_BYTES)
        dataView.setInt8(0, byte)
        return this
    }

    override fun set(index: Int, byte: Byte): WriteBuffer {
        val dataView = DataView(buffer.buffer, index, Byte.SIZE_BYTES)
        dataView.setInt8(0, byte)
        return this
    }

    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int): WriteBuffer {
        val uint8Array = bytes.unsafeCast<Uint8Array>().subarray(offset, offset + length)
        this.buffer.set(uint8Array, position)
        position += uint8Array.length
        return this
    }

    override fun writeShort(short: Short): WriteBuffer {
        val dataView = DataView(buffer.buffer, position, Short.SIZE_BYTES)
        position += UShort.SIZE_BYTES
        dataView.setUint16(0, short, littleEndian)
        return this
    }

    override fun set(index: Int, short: Short): WriteBuffer {
        val dataView = DataView(buffer.buffer, index, Short.SIZE_BYTES)
        dataView.setUint16(0, short, littleEndian)
        return this
    }

    override fun writeInt(int: Int): WriteBuffer {
        val dataView = DataView(buffer.buffer, position, UInt.SIZE_BYTES)
        position += UInt.SIZE_BYTES
        dataView.setInt32(0, int, littleEndian)
        return this
    }

    override fun set(index: Int, int: Int): WriteBuffer {
        val dataView = DataView(buffer.buffer, index, UInt.SIZE_BYTES)
        dataView.setUint32(0, int, littleEndian)
        return this
    }

    override fun writeLong(long: Long): WriteBuffer {
        val bytes = if (littleEndian) long.toByteArray().reversedArray() else long.toByteArray()
        writeBytes(bytes)
        return this
    }

    override fun set(index: Int, long: Long): WriteBuffer {
        val bytes = if (littleEndian) long.toByteArray().reversedArray() else long.toByteArray()
        val uint8Array = bytes.unsafeCast<Uint8Array>().subarray(0, Long.SIZE_BYTES)
        this.buffer.set(uint8Array, index)
        return this
    }

    private fun Long.toByteArray(): ByteArray {
        var l = this
        val result = ByteArray(8)
        for (i in 7 downTo 0) {
            result[i] = l.toByte()
            l = l shr 8
        }
        return result
    }

    override fun writeString(text: CharSequence, charset: Charset): WriteBuffer {
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
