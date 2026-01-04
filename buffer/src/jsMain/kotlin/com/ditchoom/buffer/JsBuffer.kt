package com.ditchoom.buffer

import js.buffer.BufferSource
import js.buffer.SharedArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array
import web.encoding.TextDecoder
import web.encoding.TextDecoderOptions

class JsBuffer(
    val buffer: Int8Array,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    val sharedArrayBuffer: SharedArrayBuffer? = null,
) : BaseWebBuffer(buffer.byteLength, byteOrder),
    NativeMemoryAccess {
    /**
     * The byte offset within the underlying ArrayBuffer.
     * Use with `new DataView(buffer.buffer, nativeAddress, nativeSize)`.
     */
    override val nativeAddress: Long get() = buffer.byteOffset.toLong()

    /**
     * The size of the native memory region in bytes.
     */
    override val nativeSize: Int get() = buffer.byteLength

    // Cached DataView for the entire buffer - avoids creating new DataView on each operation
    private val dataView = DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength)

    // Platform-specific memory access using DataView
    override fun loadByte(index: Int): Byte = dataView.getInt8(index)

    override fun storeByte(
        index: Int,
        value: Byte,
    ) {
        dataView.setInt8(index, value)
    }

    // Optimized multi-byte operations using DataView
    override fun loadShort(index: Int): Short = dataView.getInt16(index, littleEndian).toInt().toShort()

    override fun storeShort(
        index: Int,
        value: Short,
    ) {
        dataView.setInt16(index, value, littleEndian)
    }

    override fun loadInt(index: Int): Int = dataView.getInt32(index, littleEndian)

    override fun storeInt(
        index: Int,
        value: Int,
    ) {
        dataView.setInt32(index, value, littleEndian)
    }

    override fun loadLong(index: Int): Long =
        if (littleEndian) {
            val low = dataView.getInt32(index, true).toLong() and 0xFFFFFFFFL
            val high = dataView.getInt32(index + 4, true).toLong()
            (high shl 32) or low
        } else {
            val high = dataView.getInt32(index, false).toLong()
            val low = dataView.getInt32(index + 4, false).toLong() and 0xFFFFFFFFL
            (high shl 32) or low
        }

    override fun storeLong(
        index: Int,
        value: Long,
    ) {
        if (littleEndian) {
            dataView.setInt32(index, value.toInt(), true)
            dataView.setInt32(index + 4, (value shr 32).toInt(), true)
        } else {
            dataView.setInt32(index, (value shr 32).toInt(), false)
            dataView.setInt32(index + 4, value.toInt(), false)
        }
    }

    // Zero-copy slice using subarray
    override fun slice(): ReadBuffer =
        JsBuffer(
            buffer.subarray(positionValue, limitValue),
            byteOrder,
            sharedArrayBuffer = sharedArrayBuffer,
        )

    override fun readByteArray(size: Int): ByteArray {
        val subArray = buffer.subarray(positionValue, positionValue + size)
        val byteArray = Int8Array(subArray.buffer, subArray.byteOffset, size)
        positionValue += size
        return byteArray.unsafeCast<ByteArray>()
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
                buffer.subarray(positionValue, positionValue + length).unsafeCast<BufferSource>(),
            )
        position(positionValue + length)
        return result
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        val int8Array = bytes.unsafeCast<Int8Array>().subarray(offset, offset + length)
        this.buffer.set(int8Array, positionValue)
        positionValue += int8Array.length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        if (buffer is JsBuffer) {
            // Zero-copy: copy only the remaining portion using subarray
            val sourceSubarray = buffer.buffer.subarray(buffer.position(), buffer.position() + size)
            this.buffer.set(sourceSubarray, positionValue)
        } else {
            this.buffer.set(buffer.readByteArray(size).toTypedArray(), positionValue)
        }
        positionValue += size
        buffer.position(buffer.position() + size)
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class.js != other::class.js) return false

        other as JsBuffer

        if (byteOrder != other.byteOrder) return false
        if (positionValue != other.positionValue) return false
        if (limitValue != other.limitValue) return false
        if (capacity != other.capacity) return false
        val size = remaining()
        try {
            if (!readByteArray(size).contentEquals(other.readByteArray(size))) return false
        } finally {
            positionValue -= size
            other.positionValue -= size
        }
        return true
    }

    override fun hashCode(): Int {
        var result = byteOrder.hashCode()
        result = 31 * result + positionValue
        result = 31 * result + limitValue
        result = 31 * result + capacity.hashCode()
        val size = remaining()
        try {
            result = 31 * result + readByteArray(size).hashCode()
        } finally {
            positionValue -= size
        }
        return result
    }
}
