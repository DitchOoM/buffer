package com.ditchoom.buffer

import js.buffer.BufferSource
import js.buffer.SharedArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Int32Array
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import web.encoding.TextDecoder
import web.encoding.TextDecoderOptions
import web.encoding.TextEncoder

class JsBuffer(
    val buffer: Int8Array,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    val sharedArrayBuffer: SharedArrayBuffer? = null,
) : BaseWebBuffer(buffer.byteLength, byteOrder),
    NativeMemoryAccess,
    SharedMemoryAccess {
    /**
     * The byte offset within the underlying ArrayBuffer.
     * Use with `new DataView(buffer.buffer, nativeAddress, nativeSize)`.
     */
    override val nativeAddress: Long get() = buffer.byteOffset.toLong()

    /**
     * The size of the native memory region in bytes.
     */
    override val nativeSize: Long get() = buffer.byteLength.toLong()

    /**
     * Whether this buffer is backed by a SharedArrayBuffer.
     */
    override val isShared: Boolean get() = sharedArrayBuffer != null

    companion object {
        // TextEncoder is stateless (always UTF-8), safe to share across instances
        private val textEncoder = TextEncoder()
    }

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
            // readByteArray already advances buffer position
            this.buffer.set(buffer.readByteArray(size).toTypedArray(), positionValue)
            positionValue += size
            return
        }
        positionValue += size
        buffer.position(buffer.position() + size)
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        when (charset) {
            Charset.UTF8 -> {
                val str = text.toString()
                if (str.isEmpty()) return this
                // Zero-alloc: TextEncoder.encodeInto() writes UTF-8 directly into the buffer
                val target = Uint8Array(buffer.buffer, buffer.byteOffset + positionValue, capacity - positionValue)
                val result = textEncoder.asDynamic().encodeInto(str, target)
                positionValue += (result.written as Int)
            }
            else -> throw UnsupportedOperationException("Unable to encode in $charset. Must use Charset.UTF8")
        }
        return this
    }

    /**
     * Reverses bytes of an Int for little-endian Int32Array compatibility.
     * JS bitwise ops are 32-bit, so this is the widest we can go.
     */
    private fun reverseBytes(value: Int): Int =
        ((value and 0xFF) shl 24) or
            ((value and 0xFF00) shl 8) or
            ((value ushr 8) and 0xFF00) or
            ((value ushr 24) and 0xFF)

    /**
     * XOR remaining 0-3 bytes after bulk Int32 processing.
     */
    private fun xorMaskRemaining(
        startOffset: Int,
        endOffset: Int,
        mask: Int,
        maskOffset: Int,
        bytesProcessed: Int,
    ) {
        val m0 = (mask ushr 24).toByte()
        val m1 = (mask ushr 16).toByte()
        val m2 = (mask ushr 8).toByte()
        val m3 = mask.toByte()
        var offset = startOffset
        var i = bytesProcessed
        while (offset < endOffset) {
            val mb =
                when ((i + maskOffset) and 3) {
                    0 -> m0
                    1 -> m1
                    2 -> m2
                    else -> m3
                }
            dataView.setInt8(offset, (dataView.getInt8(offset).toInt() xor mb.toInt()).toByte())
            offset++
            i++
        }
    }

    /**
     * Optimized XOR mask using Int32Array when 4-byte aligned (V8 inlines typed
     * array access), falling back to DataView Int32 when unaligned.
     */
    override fun xorMask(
        mask: Int,
        maskOffset: Int,
    ) {
        if (mask == 0) return
        val pos = positionValue
        val lim = limitValue
        val size = lim - pos
        if (size == 0) return

        val shift = (maskOffset and 3) * 8
        val rotatedMask =
            if (shift == 0) mask else (mask shl shift) or (mask ushr (32 - shift))

        val startByte = buffer.byteOffset + pos
        val int32Count = size ushr 2
        var bulkEnd = pos

        if (startByte and 3 == 0 && int32Count > 0) {
            // Aligned: Int32Array -- V8 JIT-compiles to direct memory access
            val leMask = reverseBytes(rotatedMask)
            val view = Int32Array(buffer.buffer, startByte, int32Count)
            for (i in 0 until int32Count) {
                view[i] = view[i] xor leMask
            }
            bulkEnd = pos + (int32Count shl 2)
        } else if (int32Count > 0) {
            // Unaligned: DataView (still 4 bytes at a time, no alignment required)
            var offset = pos
            val limit4 = lim - 3
            while (offset < limit4) {
                dataView.setInt32(offset, dataView.getInt32(offset, false) xor rotatedMask, false)
                offset += 4
            }
            bulkEnd = offset
        }

        if (bulkEnd < lim) {
            xorMaskRemaining(bulkEnd, lim, mask, maskOffset, bulkEnd - pos)
        }
    }

    /**
     * Fused copy + XOR mask using Int32Array when aligned, DataView otherwise.
     * The default implementation is byte-by-byte -- this is 4x faster minimum.
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

        if (source !is JsBuffer) {
            super.xorMaskCopy(source, mask, maskOffset)
            return
        }

        val shift = (maskOffset and 3) * 8
        val rotatedMask =
            if (shift == 0) mask else (mask shl shift) or (mask ushr (32 - shift))

        val srcPos = source.position()
        val srcStartByte = source.buffer.byteOffset + srcPos
        val dstStartByte = buffer.byteOffset + positionValue
        val int32Count = size ushr 2
        var srcOff = srcPos
        var dstOff = positionValue

        if (srcStartByte and 3 == 0 && dstStartByte and 3 == 0 && int32Count > 0) {
            // Both aligned: Int32Array -- single-pass read+XOR+write
            val leMask = reverseBytes(rotatedMask)
            val srcView = Int32Array(source.buffer.buffer, srcStartByte, int32Count)
            val dstView = Int32Array(buffer.buffer, dstStartByte, int32Count)
            for (i in 0 until int32Count) {
                dstView[i] = srcView[i] xor leMask
            }
            val bulkBytes = int32Count shl 2
            srcOff += bulkBytes
            dstOff += bulkBytes
        } else if (int32Count > 0) {
            // Unaligned: DataView (still 4 bytes at a time)
            val srcDv = source.dataView
            val end4 = srcPos + size - 3
            while (srcOff < end4) {
                dataView.setInt32(dstOff, srcDv.getInt32(srcOff, false) xor rotatedMask, false)
                srcOff += 4
                dstOff += 4
            }
        }

        // Remaining 0-3 bytes
        val end = srcPos + size
        if (srcOff < end) {
            val srcDv = source.dataView
            val m0 = (mask ushr 24).toByte()
            val m1 = (mask ushr 16).toByte()
            val m2 = (mask ushr 8).toByte()
            val m3 = mask.toByte()
            var i = srcOff - srcPos
            while (srcOff < end) {
                val mb =
                    when ((i + maskOffset) and 3) {
                        0 -> m0
                        1 -> m1
                        2 -> m2
                        else -> m3
                    }
                dataView.setInt8(dstOff, (srcDv.getInt8(srcOff).toInt() xor mb.toInt()).toByte())
                srcOff++
                dstOff++
                i++
            }
        }

        source.position(srcOff)
        positionValue = dstOff
    }

    /**
     * Optimized single byte indexOf using DataView.
     */
    override fun indexOf(byte: Byte): Int =
        bulkIndexOfInt(
            startPos = positionValue,
            length = remaining(),
            byte = byte,
            getInt = { dataView.getInt32(it, true) },
            getByte = { dataView.getInt8(it) },
        )

    /**
     * Optimized contentEquals using Int8Array comparison.
     */
    override fun contentEquals(other: ReadBuffer): Boolean {
        if (remaining() != other.remaining()) return false
        val size = remaining()
        if (size == 0) return true

        if (other is JsBuffer) {
            return bulkCompareEqualsInt(
                thisPos = positionValue,
                otherPos = other.positionValue,
                length = size,
                getInt = { dataView.getInt32(it, true) },
                otherGetInt = { other.dataView.getInt32(it, true) },
                getByte = { dataView.getInt8(it) },
                otherGetByte = { other.dataView.getInt8(it) },
            )
        }
        return super.contentEquals(other)
    }

    /**
     * Optimized mismatch using DataView comparisons.
     */
    override fun mismatch(other: ReadBuffer): Int {
        val thisRemaining = remaining()
        val otherRemaining = other.remaining()
        val minLength = minOf(thisRemaining, otherRemaining)

        if (other is JsBuffer) {
            return bulkMismatchInt(
                thisPos = positionValue,
                otherPos = other.positionValue,
                minLength = minLength,
                thisRemaining = thisRemaining,
                otherRemaining = otherRemaining,
                getInt = { dataView.getInt32(it, true) },
                otherGetInt = { other.dataView.getInt32(it, true) },
                getByte = { dataView.getInt8(it) },
                otherGetByte = { other.dataView.getInt8(it) },
            )
        }
        return super.mismatch(other)
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
