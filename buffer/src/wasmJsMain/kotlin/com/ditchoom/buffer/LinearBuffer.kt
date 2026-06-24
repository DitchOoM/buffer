@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

import com.ditchoom.buffer.BufferConstants.BYTE_1_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_2_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_3_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_4_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_5_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_6_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_7_SHIFT
import com.ditchoom.buffer.BufferConstants.BYTE_MASK
import com.ditchoom.buffer.BufferConstants.INT_MASK
import com.ditchoom.buffer.BufferConstants.WORD_BYTE_3
import com.ditchoom.buffer.BufferConstants.WORD_BYTE_4
import com.ditchoom.buffer.BufferConstants.WORD_BYTE_5
import com.ditchoom.buffer.BufferConstants.WORD_BYTE_6
import com.ditchoom.buffer.BufferConstants.WORD_BYTE_7
import com.ditchoom.buffer.BufferConstants.WORD_BYTE_MASK
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi

/**
 * Decode bytes from WASM linear memory to a string using the specified encoding.
 * Uses the browser's TextDecoder API for charset support.
 */
@JsFun(
    """
(offset, length, encoding) => {
    const memory = wasmExports.memory.buffer;
    const bytes = new Uint8Array(memory, offset, length);
    const decoder = new TextDecoder(encoding, { fatal: true });
    return decoder.decode(bytes);
}
""",
)
private external fun decodeString(
    offset: Int,
    length: Int,
    encoding: JsString,
): JsString

/**
 * Efficient memory copy within WASM linear memory using Uint8Array.set().
 * This is much faster than byte-by-byte or even Long-by-Long copying.
 */
@JsFun(
    """
(srcOffset, dstOffset, length) => {
    const memory = wasmExports.memory.buffer;
    const src = new Uint8Array(memory, srcOffset, length);
    const dst = new Uint8Array(memory, dstOffset, length);
    dst.set(src);
}
""",
)
private external fun memcpy(
    srcOffset: Int,
    dstOffset: Int,
    length: Int,
)

/**
 * Copy from JS Int8Array to linear memory. Zero-copy if arrays overlap in same memory.
 */
@JsFun(
    """
(jsArray, dstOffset, srcOffset, length) => {
    const memory = wasmExports.memory.buffer;
    const dst = new Uint8Array(memory, dstOffset, length);
    const src = new Uint8Array(jsArray.buffer, jsArray.byteOffset + srcOffset, length);
    dst.set(src);
}
""",
)
private external fun copyFromJsArray(
    jsArray: JsAny,
    dstOffset: Int,
    srcOffset: Int,
    length: Int,
)

/**
 * Copy from linear memory to JS Int8Array.
 */
@JsFun(
    """
(jsArray, srcOffset, dstOffset, length) => {
    const memory = wasmExports.memory.buffer;
    const src = new Uint8Array(memory, srcOffset, length);
    const dst = new Uint8Array(jsArray.buffer, jsArray.byteOffset + dstOffset, length);
    dst.set(src);
}
""",
)
private external fun copyToJsArray(
    jsArray: JsAny,
    srcOffset: Int,
    dstOffset: Int,
    length: Int,
)

/**
 * Buffer implementation using WASM linear memory with native Pointer operations.
 *
 * This provides zero-copy interop with JavaScript - both WASM (via Pointer) and
 * JS (via DataView on the same memory) can access the same underlying bytes.
 *
 * All read/write operations compile to native WASM i32.load/i32.store instructions.
 */
class LinearBuffer(
    internal val baseOffset: Int,
    capacity: Int,
    byteOrder: ByteOrder,
) : BaseWebBuffer(capacity, byteOrder),
    NativeMemoryAccess {
    /**
     * The offset in WASM linear memory for zero-copy JS interop.
     * Use with `new DataView(wasmExports.memory.buffer, nativeAddress, nativeSize)`.
     */
    override val nativeAddress: Long get() = baseOffset.toLong()

    /**
     * The size of the native memory region in bytes.
     */
    override val nativeSize: Long get() = capacity.toLong()

    /**
     * Get the linear memory offset for the current position.
     * This can be passed to JavaScript for zero-copy access via DataView.
     */
    val linearMemoryOffset: Int get() = baseOffset + positionValue

    /**
     * Write from a JS Int8Array into this buffer.
     * Uses native Uint8Array.set() for efficient copying.
     *
     * @param jsArray The JS Int8Array to copy from
     * @param srcOffset Offset within the JS array to start copying from
     * @param length Number of bytes to copy
     */
    fun writeFromJsArray(
        jsArray: JsAny,
        srcOffset: Int = 0,
        length: Int,
    ): LinearBuffer {
        copyFromJsArray(jsArray, baseOffset + positionValue, srcOffset, length)
        positionValue += length
        return this
    }

    /**
     * Read from this buffer into a JS Int8Array.
     * Uses native Uint8Array.set() for efficient copying.
     *
     * @param jsArray The JS Int8Array to copy to
     * @param dstOffset Offset within the JS array to start copying to
     * @param length Number of bytes to copy
     */
    fun readToJsArray(
        jsArray: JsAny,
        dstOffset: Int = 0,
        length: Int,
    ) {
        copyToJsArray(jsArray, baseOffset + positionValue, dstOffset, length)
        positionValue += length
    }

    /**
     * Get a Pointer to the specified offset within this buffer.
     * Pointer is a value class - no allocation, compiles to raw address arithmetic.
     */
    private fun ptr(offset: Int): Pointer = Pointer((baseOffset + offset).toUInt())

    // Native memory access using Pointer operations
    override fun loadByte(index: Int): Byte = ptr(index).loadByte()

    override fun storeByte(
        index: Int,
        value: Byte,
    ) {
        ptr(index).storeByte(value)
    }

    override fun loadShort(index: Int): Short {
        // Use Pointer for native 16-bit load
        val raw = ptr(index).loadShort()
        return if (littleEndian) {
            raw
        } else {
            // Swap bytes for big endian
            val r = raw.toInt()
            ((r and BYTE_MASK) shl BYTE_1_SHIFT or ((r shr BYTE_1_SHIFT) and BYTE_MASK)).toShort()
        }
    }

    override fun storeShort(
        index: Int,
        value: Short,
    ) {
        val toStore =
            if (littleEndian) {
                value
            } else {
                val v = value.toInt()
                ((v and BYTE_MASK) shl BYTE_1_SHIFT or ((v shr BYTE_1_SHIFT) and BYTE_MASK)).toShort()
            }
        ptr(index).storeShort(toStore)
    }

    override fun loadInt(index: Int): Int {
        val raw = ptr(index).loadInt()
        return if (littleEndian) {
            raw
        } else {
            // Swap bytes for big endian
            raw.reverseBytes()
        }
    }

    override fun storeInt(
        index: Int,
        value: Int,
    ) {
        val toStore =
            if (littleEndian) {
                value
            } else {
                value.reverseBytes()
            }
        ptr(index).storeInt(toStore)
    }

    override fun loadLong(index: Int): Long {
        val raw = ptr(index).loadLong()
        return if (littleEndian) {
            raw
        } else {
            raw.reverseBytes()
        }
    }

    override fun storeLong(
        index: Int,
        value: Long,
    ) {
        val toStore =
            if (littleEndian) {
                value
            } else {
                value.reverseBytes()
            }
        ptr(index).storeLong(toStore)
    }

    /**
     * Optimized XOR mask using Pointer Long operations (8 bytes at a time).
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

        // Rotate the big-endian mask so that byte at (maskOffset % 4) becomes byte 0
        val shift = (maskOffset and WORD_BYTE_MASK) * Byte.SIZE_BITS
        val rotatedMask =
            if (shift == 0) mask else (mask shl shift) or (mask ushr (Int.SIZE_BITS - shift))

        // WASM is little-endian, so reverse the rotated big-endian mask for memory layout
        val leMask = rotatedMask.reverseBytes()
        val maskLong = (leMask.toLong() and INT_MASK) or (leMask.toLong() shl Int.SIZE_BITS)

        var offset = pos
        // Process 8 bytes at a time
        while (offset + Long.SIZE_BYTES <= lim) {
            val value = ptr(offset).loadLong()
            ptr(offset).storeLong(value xor maskLong)
            offset += Long.SIZE_BYTES
        }

        // Handle remaining bytes using the ORIGINAL mask with offset
        val maskByte0 = (mask ushr BYTE_3_SHIFT).toByte()
        val maskByte1 = (mask ushr BYTE_2_SHIFT).toByte()
        val maskByte2 = (mask ushr BYTE_1_SHIFT).toByte()
        val maskByte3 = mask.toByte()
        var i = offset - pos
        while (offset < lim) {
            val maskByte =
                when ((i + maskOffset) and WORD_BYTE_MASK) {
                    0 -> maskByte0
                    1 -> maskByte1
                    2 -> maskByte2
                    else -> maskByte3
                }
            val b = ptr(offset).loadByte()
            ptr(offset).storeByte((b.toInt() xor maskByte.toInt()).toByte())
            offset++
            i++
        }
    }

    /**
     * Optimized XOR mask copy using Pointer Long operations (8 bytes at a time).
     * WASM i64 instructions are native hardware ops on 64-bit CPUs.
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

        // Build mask Long for little-endian WASM memory
        val shift = (maskOffset and WORD_BYTE_MASK) * Byte.SIZE_BITS
        val rotatedMask =
            if (shift == 0) mask else (mask shl shift) or (mask ushr (Int.SIZE_BITS - shift))
        val leMask = rotatedMask.reverseBytes()
        val maskLong = (leMask.toLong() and INT_MASK) or (leMask.toLong() shl Int.SIZE_BITS)

        val actual = source.unwrapFully()
        if (actual is LinearBuffer) {
            // Both in linear memory: use Pointer Long operations
            var srcOffset = actual.baseOffset + actual.positionValue
            var dstOffset = baseOffset + positionValue

            // Process 8 bytes at a time
            while (srcOffset + Long.SIZE_BYTES <= actual.baseOffset + actual.positionValue + size) {
                val srcPtr = Pointer(srcOffset.toUInt())
                val dstPtr = Pointer(dstOffset.toUInt())
                dstPtr.storeLong(srcPtr.loadLong() xor maskLong)
                srcOffset += Long.SIZE_BYTES
                dstOffset += Long.SIZE_BYTES
            }

            // Handle remaining bytes using the ORIGINAL mask with offset
            val maskByte0 = (mask ushr BYTE_3_SHIFT).toByte()
            val maskByte1 = (mask ushr BYTE_2_SHIFT).toByte()
            val maskByte2 = (mask ushr BYTE_1_SHIFT).toByte()
            val maskByte3 = mask.toByte()
            var i = srcOffset - (actual.baseOffset + actual.positionValue)
            while (srcOffset < actual.baseOffset + actual.positionValue + size) {
                val maskByte =
                    when ((i + maskOffset) and WORD_BYTE_MASK) {
                        0 -> maskByte0
                        1 -> maskByte1
                        2 -> maskByte2
                        else -> maskByte3
                    }
                val b = Pointer(srcOffset.toUInt()).loadByte()
                Pointer(dstOffset.toUInt()).storeByte((b.toInt() xor maskByte.toInt()).toByte())
                srcOffset++
                dstOffset++
                i++
            }

            positionValue += size
            source.position(source.position() + size)
        } else {
            // Fallback for non-LinearBuffer sources
            super.xorMaskCopy(source, mask, maskOffset)
        }
    }

    override fun slice(byteOrder: ByteOrder): LinearBuffer {
        // Create a new LinearBuffer view of the remaining portion
        // This is zero-copy - just creates a new view with different base offset
        return LinearBuffer(
            baseOffset + positionValue,
            limitValue - positionValue,
            byteOrder,
        )
    }

    override fun readByteArray(size: Int): ByteArray = copyToByteArray(size)

    override fun readInto(
        dst: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (length == 0) return
        requireReadable(length)
        // Linear memory and Wasm-GC heap are separate address spaces — the
        // payload is always physically copied into dst.
        var srcOffset = positionValue
        var dstOffset = offset
        val end = offset + length

        // Copy 8 bytes at a time using Long operations
        while (dstOffset + Long.SIZE_BYTES <= end) {
            val value = ptr(srcOffset).loadLong()
            dst[dstOffset] = value.toByte()
            dst[dstOffset + 1] = (value shr BYTE_1_SHIFT).toByte()
            dst[dstOffset + 2] = (value shr BYTE_2_SHIFT).toByte()
            dst[dstOffset + WORD_BYTE_3] = (value shr BYTE_3_SHIFT).toByte()
            dst[dstOffset + WORD_BYTE_4] = (value shr BYTE_4_SHIFT).toByte()
            dst[dstOffset + WORD_BYTE_5] = (value shr BYTE_5_SHIFT).toByte()
            dst[dstOffset + WORD_BYTE_6] = (value shr BYTE_6_SHIFT).toByte()
            dst[dstOffset + WORD_BYTE_7] = (value shr BYTE_7_SHIFT).toByte()
            srcOffset += Long.SIZE_BYTES
            dstOffset += Long.SIZE_BYTES
        }

        // Copy remaining bytes
        while (dstOffset < end) {
            dst[dstOffset] = loadByte(srcOffset)
            srcOffset++
            dstOffset++
        }

        positionValue += length
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        checkWriteBounds(length)
        // Copy from Kotlin ByteArray (Wasm GC heap) to linear memory
        var srcOffset = offset
        var dstOffset = positionValue

        // Copy 8 bytes at a time using Long operations
        while (srcOffset + Long.SIZE_BYTES <= offset + length) {
            // Pack 8 bytes into a Long
            val value =
                (bytes[srcOffset].toLong() and BYTE_MASK.toLong()) or
                    ((bytes[srcOffset + 1].toLong() and BYTE_MASK.toLong()) shl BYTE_1_SHIFT) or
                    ((bytes[srcOffset + 2].toLong() and BYTE_MASK.toLong()) shl BYTE_2_SHIFT) or
                    ((bytes[srcOffset + WORD_BYTE_3].toLong() and BYTE_MASK.toLong()) shl BYTE_3_SHIFT) or
                    ((bytes[srcOffset + WORD_BYTE_4].toLong() and BYTE_MASK.toLong()) shl BYTE_4_SHIFT) or
                    ((bytes[srcOffset + WORD_BYTE_5].toLong() and BYTE_MASK.toLong()) shl BYTE_5_SHIFT) or
                    ((bytes[srcOffset + WORD_BYTE_6].toLong() and BYTE_MASK.toLong()) shl BYTE_6_SHIFT) or
                    ((bytes[srcOffset + WORD_BYTE_7].toLong() and BYTE_MASK.toLong()) shl BYTE_7_SHIFT)
            ptr(dstOffset).storeLong(value)
            srcOffset += Long.SIZE_BYTES
            dstOffset += Long.SIZE_BYTES
        }

        // Copy remaining bytes
        while (srcOffset < offset + length) {
            storeByte(dstOffset, bytes[srcOffset])
            srcOffset++
            dstOffset++
        }

        positionValue += length
        return this
    }

    override fun write(buffer: ReadBuffer) {
        val size = buffer.remaining()
        checkWriteBounds(size)
        val actual = buffer.unwrapFully()
        when (actual) {
            is LinearBuffer -> {
                // Both are in linear memory - use native memcpy via Uint8Array.set()
                memcpy(
                    srcOffset = actual.baseOffset + actual.positionValue,
                    dstOffset = baseOffset + positionValue,
                    length = size,
                )
            }
            else -> {
                // Fallback for other buffer types
                writeBytes(buffer.readByteArray(size))
                return // readByteArray already advances position
            }
        }
        positionValue += size
        buffer.position(buffer.position() + size)
    }

    override fun readString(
        length: Int,
        charset: Charset,
    ): String {
        if (length == 0) return ""
        requireReadable(length)
        val encoding =
            when (charset) {
                Charset.UTF8 -> "utf-8"
                Charset.UTF16 -> "utf-16"
                Charset.UTF16BigEndian -> "utf-16be"
                Charset.UTF16LittleEndian -> "utf-16le"
                Charset.ASCII -> "ascii"
                Charset.ISOLatin1 -> "iso-8859-1"
                Charset.UTF32,
                Charset.UTF32LittleEndian,
                Charset.UTF32BigEndian,
                -> throw UnsupportedOperationException("UTF-32 charsets are not supported by TextDecoder")
            }
        val result = decodeString(baseOffset + positionValue, length, encoding.toJsString()).toString()
        positionValue += length
        return result
    }

    override fun writeString(
        text: CharSequence,
        charset: Charset,
    ): WriteBuffer {
        when (charset) {
            Charset.UTF8 -> writeBytes(text.toString().encodeToByteArray())
            else -> throw UnsupportedOperationException("LinearBuffer only supports UTF8 charset. Got: $charset")
        }
        return this
    }

    /**
     * Optimized single byte indexOf using Long comparisons (8 bytes at a time).
     */
    override fun indexOf(byte: Byte): Int =
        bulkIndexOf(
            startPos = positionValue,
            length = remaining(),
            byte = byte,
            getLong = { ptr(it).loadLong() },
            getByte = { loadByte(it) },
        )

    /**
     * Optimized contentEquals using Long comparisons.
     */
    override fun contentEquals(other: ReadBuffer): Boolean {
        if (remaining() != other.remaining()) return false
        val size = remaining()
        if (size == 0) return true

        val actual = other.unwrapFully()
        if (actual is LinearBuffer) {
            return bulkCompareEquals(
                thisPos = positionValue,
                otherPos = actual.positionValue,
                length = size,
                getLong = { ptr(it).loadLong() },
                otherGetLong = { actual.ptr(it).loadLong() },
                getByte = { loadByte(it) },
                otherGetByte = { actual.loadByte(it) },
            )
        }
        return super.contentEquals(other)
    }

    /**
     * Optimized mismatch using Long comparisons.
     */
    override fun mismatch(other: ReadBuffer): Int {
        val thisRemaining = remaining()
        val otherRemaining = other.remaining()
        val minLength = minOf(thisRemaining, otherRemaining)

        val actual = other.unwrapFully()
        if (actual is LinearBuffer) {
            return bulkMismatch(
                thisPos = positionValue,
                otherPos = actual.positionValue,
                minLength = minLength,
                thisRemaining = thisRemaining,
                otherRemaining = otherRemaining,
                getLong = { ptr(it).loadLong() },
                otherGetLong = { actual.ptr(it).loadLong() },
                getByte = { loadByte(it) },
                otherGetByte = { actual.loadByte(it) },
            )
        }
        return super.mismatch(other)
    }

    override fun equals(other: Any?): Boolean = bufferEquals(this, other)

    override fun hashCode(): Int = bufferHashCode(this)
}
