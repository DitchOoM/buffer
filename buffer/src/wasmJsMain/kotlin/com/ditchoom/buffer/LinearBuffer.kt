@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

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
            ((raw.toInt() and 0xFF) shl 8 or ((raw.toInt() shr 8) and 0xFF)).toShort()
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
                ((value.toInt() and 0xFF) shl 8 or ((value.toInt() shr 8) and 0xFF)).toShort()
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
        val shift = (maskOffset and 3) * 8
        val rotatedMask =
            if (shift == 0) mask else (mask shl shift) or (mask ushr (32 - shift))

        // WASM is little-endian, so reverse the rotated big-endian mask for memory layout
        val leMask = rotatedMask.reverseBytes()
        val maskLong = (leMask.toLong() and 0xFFFFFFFFL) or (leMask.toLong() shl 32)

        var offset = pos
        // Process 8 bytes at a time
        while (offset + 8 <= lim) {
            val value = ptr(offset).loadLong()
            ptr(offset).storeLong(value xor maskLong)
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
            val b = ptr(offset).loadByte()
            ptr(offset).storeByte((b.toInt() xor maskByte.toInt()).toByte())
            offset++
            i++
        }
    }

    override fun slice(): ReadBuffer {
        // Create a new LinearBuffer view of the remaining portion
        // This is zero-copy - just creates a new view with different base offset
        return LinearBuffer(
            baseOffset + positionValue,
            limitValue - positionValue,
            byteOrder,
        )
    }

    override fun readByteArray(size: Int): ByteArray {
        // Copy from linear memory to Kotlin ByteArray (Wasm GC heap)
        // This requires a copy - unavoidable due to separate memory spaces
        val result = ByteArray(size)
        var srcOffset = positionValue
        var dstOffset = 0

        // Copy 8 bytes at a time using Long operations
        while (dstOffset + 8 <= size) {
            val value = ptr(srcOffset).loadLong()
            // Manually unpack Long to bytes (faster than 8 separate loadByte calls)
            result[dstOffset] = value.toByte()
            result[dstOffset + 1] = (value shr 8).toByte()
            result[dstOffset + 2] = (value shr 16).toByte()
            result[dstOffset + 3] = (value shr 24).toByte()
            result[dstOffset + 4] = (value shr 32).toByte()
            result[dstOffset + 5] = (value shr 40).toByte()
            result[dstOffset + 6] = (value shr 48).toByte()
            result[dstOffset + 7] = (value shr 56).toByte()
            srcOffset += 8
            dstOffset += 8
        }

        // Copy remaining bytes
        while (dstOffset < size) {
            result[dstOffset] = loadByte(srcOffset)
            srcOffset++
            dstOffset++
        }

        positionValue += size
        return result
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): WriteBuffer {
        // Copy from Kotlin ByteArray (Wasm GC heap) to linear memory
        var srcOffset = offset
        var dstOffset = positionValue

        // Copy 8 bytes at a time using Long operations
        while (srcOffset + 8 <= offset + length) {
            // Pack 8 bytes into a Long
            val value =
                (bytes[srcOffset].toLong() and 0xFFL) or
                    ((bytes[srcOffset + 1].toLong() and 0xFFL) shl 8) or
                    ((bytes[srcOffset + 2].toLong() and 0xFFL) shl 16) or
                    ((bytes[srcOffset + 3].toLong() and 0xFFL) shl 24) or
                    ((bytes[srcOffset + 4].toLong() and 0xFFL) shl 32) or
                    ((bytes[srcOffset + 5].toLong() and 0xFFL) shl 40) or
                    ((bytes[srcOffset + 6].toLong() and 0xFFL) shl 48) or
                    ((bytes[srcOffset + 7].toLong() and 0xFFL) shl 56)
            ptr(dstOffset).storeLong(value)
            srcOffset += 8
            dstOffset += 8
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
        val actual = (buffer as? PlatformBuffer)?.unwrap() ?: buffer
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

        val actual = (other as? PlatformBuffer)?.unwrap() ?: other
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

        val actual = (other as? PlatformBuffer)?.unwrap() ?: other
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinearBuffer) return false

        if (byteOrder != other.byteOrder) return false
        if (positionValue != other.positionValue) return false
        if (limitValue != other.limitValue) return false
        if (capacity != other.capacity) return false

        // Compare contents
        val size = remaining()
        for (i in 0 until size) {
            if (loadByte(positionValue + i) != other.loadByte(other.positionValue + i)) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var result = byteOrder.hashCode()
        result = 31 * result + positionValue
        result = 31 * result + limitValue
        result = 31 * result + capacity

        val size = remaining()
        for (i in 0 until size) {
            result = 31 * result + loadByte(positionValue + i).hashCode()
        }
        return result
    }
}
