@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.buffer

import com.ditchoom.buffer.cinterop.simdutf.buf_simdutf_convert_utf8_to_chararray
import com.ditchoom.buffer.cinterop.simdutf.buf_simdutf_utf16_length_from_utf8
import com.ditchoom.buffer.cinterop.simdutf.buf_simdutf_utf8_find_boundary
import com.ditchoom.buffer.cinterop.simdutf.buf_simdutf_validate_utf8
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned

/** A `byteCount` above this index means the UTF-8 sequence carries a 4th (b3) byte. */
private const val FOURTH_BYTE_PRESENT_THRESHOLD = 3

/**
 * Linux implementation using simdutf for SIMD-accelerated UTF-8 decoding.
 *
 * ## Thread Safety
 *
 * StreamingStringDecoder is NOT thread-safe. Use one instance per stream/thread.
 * Do not share instances across coroutines running on different threads.
 *
 * ## Implementation Strategy
 *
 * 1. Find UTF-8 boundary using buf_simdutf_utf8_find_boundary (handles incomplete sequences)
 * 2. Convert complete UTF-8 sequences to UTF-16 using buf_simdutf_convert_utf8_to_utf16le
 * 3. Save incomplete trailing bytes (max 3) as primitives for next chunk
 * 4. Append UTF-16 chars to destination Appendable
 */
private class LinuxStreamingStringDecoder(
    private val config: StreamingStringDecoderConfig,
) : StreamingStringDecoder {
    init {
        if (config.charset != Charset.UTF8) {
            throw UnsupportedOperationException(
                "Linux StreamingStringDecoder currently only supports UTF-8, got: ${config.charset}",
            )
        }
    }

    // Pending incomplete multi-byte sequence packed into a Long (avoids ByteArray allocation)
    private var pendingLong: Long = 0
    private var pendingCount: Int = 0

    // Reusable CharArray buffer for direct simdutf output (avoids intermediate UTF-16 buffer)
    private var charArrayBuffer: CharArray? = null
    private var charArrayCapacity: Int = 0

    // Reusable ByteArray buffer for managed memory fallback
    private var byteArrayBuffer: ByteArray? = null
    private var byteArrayCapacity: Int = 0

    override fun decode(
        buffer: ReadBuffer,
        destination: Appendable,
    ): Int {
        val remaining = buffer.remaining()
        if (remaining == 0) return 0

        var totalChars = 0
        var bytesConsumed = 0

        // First, handle any pending bytes from previous chunk
        if (pendingCount > 0) {
            val result = completePendingSequence(buffer, destination)
            totalChars += result.charsWritten
            bytesConsumed = result.bytesConsumed
            if (bytesConsumed >= remaining) {
                buffer.position(buffer.position() + remaining)
                return totalChars
            }
        }

        // Process remaining data
        val dataRemaining = remaining - bytesConsumed
        if (dataRemaining == 0) {
            buffer.position(buffer.position() + bytesConsumed)
            return totalChars
        }

        // Try native memory access first (zero-copy for NativeBuffer)
        val nativeAccess = buffer.nativeMemoryAccess
        if (nativeAccess != null) {
            val ptr =
                (nativeAccess.nativeAddress + buffer.position() + bytesConsumed)
                    .toCPointer<ByteVar>()!!

            totalChars += processWithPointer(ptr, dataRemaining, buffer, bytesConsumed, destination)
        } else {
            // Fallback for managed memory (ByteArrayBuffer): pin and process
            totalChars += processWithManagedMemory(buffer, bytesConsumed, dataRemaining, destination)
        }

        buffer.position(buffer.position() + remaining)
        return totalChars
    }

    private fun processWithPointer(
        ptr: CPointer<ByteVar>,
        dataRemaining: Int,
        buffer: ReadBuffer,
        bytesConsumed: Int,
        destination: Appendable,
    ): Int {
        val boundary = buf_simdutf_utf8_find_boundary(ptr, dataRemaining.convert()).toInt()

        if (boundary < dataRemaining) {
            pendingCount = dataRemaining - boundary
            pendingLong = 0L
            val basePos = buffer.position() + bytesConsumed + boundary
            for (i in 0 until pendingCount) {
                val maskedByte = buffer.get(basePos + i).toLong() and Utf8.BYTE_MASK.toLong()
                pendingLong = pendingLong or (maskedByte shl (i * Utf8.BITS_PER_BYTE))
            }
        }

        return if (boundary > 0) {
            convertUtf8PtrToAppendable(ptr, boundary, destination)
        } else {
            0
        }
    }

    private fun processWithManagedMemory(
        buffer: ReadBuffer,
        bytesConsumed: Int,
        dataRemaining: Int,
        destination: Appendable,
    ): Int {
        // Check for direct ByteArray access via ManagedMemoryAccess
        val managedAccess = buffer.managedMemoryAccess
        if (managedAccess != null) {
            val backingArray = managedAccess.backingArray
            val offset = managedAccess.arrayOffset + buffer.position() + bytesConsumed
            return backingArray.usePinned { pinned ->
                val ptr = pinned.addressOf(offset).reinterpret<ByteVar>()
                processWithPointer(ptr, dataRemaining, buffer, bytesConsumed, destination)
            }
        }

        // Final fallback: copy to temporary buffer
        val tempBuffer = ensureByteArrayCapacity(dataRemaining)
        val startPos = buffer.position() + bytesConsumed
        for (i in 0 until dataRemaining) {
            tempBuffer[i] = buffer.get(startPos + i)
        }

        return tempBuffer.usePinned { pinned ->
            val ptr = pinned.addressOf(0).reinterpret<ByteVar>()
            processWithPointer(ptr, dataRemaining, buffer, bytesConsumed, destination)
        }
    }

    private fun ensureByteArrayCapacity(needed: Int): ByteArray {
        if (byteArrayCapacity >= needed) return byteArrayBuffer!!
        val newCapacity = maxOf(needed, 8192)
        byteArrayBuffer = ByteArray(newCapacity)
        byteArrayCapacity = newCapacity
        return byteArrayBuffer!!
    }

    private fun completePendingSequence(
        buffer: ReadBuffer,
        destination: Appendable,
    ): DecodeResult {
        // Determine expected sequence length from lead byte
        val leadByte = (pendingLong and Utf8.BYTE_MASK.toLong()).toInt()
        val expectedLen =
            when {
                leadByte < Utf8.ASCII_LIMIT -> 1
                leadByte and Utf8.TWO_BYTE_LEAD_MASK == Utf8.TWO_BYTE_LEAD -> 2
                leadByte and Utf8.THREE_BYTE_LEAD_MASK == Utf8.THREE_BYTE_LEAD -> 3
                leadByte and Utf8.FOUR_BYTE_LEAD_MASK == Utf8.FOUR_BYTE_LEAD -> 4
                else -> {
                    // Invalid lead byte
                    pendingCount = 0
                    return handleMalformedInput(destination)
                }
            }

        val needed = expectedLen - pendingCount
        val available = buffer.remaining()

        if (available < needed) {
            // Still not enough - buffer all available
            val basePos = buffer.position()
            for (i in 0 until available) {
                val b = buffer.get(basePos + i)
                val maskedByte = b.toLong() and Utf8.BYTE_MASK.toLong()
                pendingLong = pendingLong or (maskedByte shl ((pendingCount + i) * Utf8.BITS_PER_BYTE))
            }
            pendingCount += available
            return DecodeResult(0, available)
        }

        // Load remaining bytes from buffer into pendingLong
        val basePos = buffer.position()
        for (i in 0 until needed) {
            val b = buffer.get(basePos + i)
            pendingLong =
                pendingLong or ((b.toLong() and Utf8.BYTE_MASK.toLong()) shl ((pendingCount + i) * Utf8.BITS_PER_BYTE))
        }
        pendingCount = 0

        // Decode UTF-8 codepoint directly from Long — zero allocation
        val chars = decodeUtf8CodePointToAppendable(expectedLen, destination)
        return DecodeResult(chars, needed)
    }

    private fun decodeUtf8CodePointToAppendable(
        byteCount: Int,
        destination: Appendable,
    ): Int {
        val b0 = (pendingLong and Utf8.BYTE_MASK.toLong()).toInt()
        val b1 = if (byteCount > 1) ((pendingLong ushr 8) and Utf8.BYTE_MASK.toLong()).toInt() else 0
        val b2 = if (byteCount > 2) ((pendingLong ushr 16) and Utf8.BYTE_MASK.toLong()).toInt() else 0
        val b3 = if (byteCount > 3) ((pendingLong ushr 24) and Utf8.BYTE_MASK.toLong()).toInt() else 0

        // Validate continuation bytes
        if (byteCount > 1 && (b1 and Utf8.CONTINUATION_MASK) != Utf8.CONTINUATION_MARKER) {
            return handleMalformedInput(destination).charsWritten
        }
        if (byteCount > 2 && (b2 and Utf8.CONTINUATION_MASK) != Utf8.CONTINUATION_MARKER) {
            return handleMalformedInput(destination).charsWritten
        }
        if (byteCount > FOURTH_BYTE_PRESENT_THRESHOLD && (b3 and Utf8.CONTINUATION_MASK) != Utf8.CONTINUATION_MARKER) {
            return handleMalformedInput(destination).charsWritten
        }

        val codePoint =
            when (byteCount) {
                1 -> b0
                2 -> ((b0 and Utf8.TWO_BYTE_PAYLOAD_MASK) shl Utf8.CONTINUATION_SHIFT) or
                    (b1 and Utf8.CONTINUATION_PAYLOAD_MASK)
                3 -> ((b0 and Utf8.THREE_BYTE_PAYLOAD_MASK) shl (Utf8.CONTINUATION_SHIFT * 2)) or
                    ((b1 and Utf8.CONTINUATION_PAYLOAD_MASK) shl Utf8.CONTINUATION_SHIFT) or
                    (b2 and Utf8.CONTINUATION_PAYLOAD_MASK)
                4 -> ((b0 and Utf8.FOUR_BYTE_PAYLOAD_MASK) shl (Utf8.CONTINUATION_SHIFT * 3)) or
                    ((b1 and Utf8.CONTINUATION_PAYLOAD_MASK) shl (Utf8.CONTINUATION_SHIFT * 2)) or
                    ((b2 and Utf8.CONTINUATION_PAYLOAD_MASK) shl Utf8.CONTINUATION_SHIFT) or
                    (b3 and Utf8.CONTINUATION_PAYLOAD_MASK)
                else -> return handleMalformedInput(destination).charsWritten
            }

        // Reject overlong encodings and surrogate codepoints
        val isValid =
            when (byteCount) {
                2 -> codePoint >= Utf8.ASCII_LIMIT
                3 -> codePoint >= Utf8.THREE_BYTE_MIN &&
                    (codePoint < Utf8.HIGH_SURROGATE_START || codePoint > Utf8.LOW_SURROGATE_END)
                4 -> codePoint in Utf8.FOUR_BYTE_MIN..Utf8.MAX_CODE_POINT
                else -> true
            }
        if (!isValid) return handleMalformedInput(destination).charsWritten

        return if (codePoint <= Utf8.BMP_MAX) {
            destination.append(codePoint.toChar())
            1
        } else {
            val adjusted = codePoint - Utf8.FOUR_BYTE_MIN
            destination.append((Utf8.HIGH_SURROGATE_START + (adjusted shr Utf8.SURROGATE_SHIFT)).toChar())
            destination.append((Utf8.LOW_SURROGATE_START + (adjusted and Utf8.LOW_SURROGATE_MASK)).toChar())
            2
        }
    }

    private fun convertUtf8PtrToAppendable(
        ptr: CPointer<ByteVar>,
        length: Int,
        destination: Appendable,
    ): Int {
        if (length == 0) return 0

        // Validate UTF-8 before conversion — simdutf silently replaces invalid sequences
        if (buf_simdutf_validate_utf8(ptr, length.convert()) == 0) {
            return handleMalformedInput(destination).charsWritten
        }

        // Calculate required UTF-16 buffer size
        val utf16Len = buf_simdutf_utf16_length_from_utf8(ptr, length.convert()).toInt()
        if (utf16Len == 0) return 0

        // Ensure CharArray is large enough
        val charArray = ensureCharArrayCapacity(utf16Len)

        // Convert UTF-8 directly to pinned CharArray
        val written =
            charArray.usePinned { outputPinned ->
                buf_simdutf_convert_utf8_to_chararray(
                    ptr,
                    length.convert(),
                    outputPinned.addressOf(0).reinterpret<ShortVar>(),
                ).toInt()
            }

        if (written == 0) {
            return handleMalformedInput(destination).charsWritten
        }

        // Bulk append
        if (destination is StringBuilder) {
            destination.appendRange(charArray, 0, written)
        } else {
            destination.append(charArray.concatToString(0, written))
        }

        return written
    }

    private fun ensureCharArrayCapacity(needed: Int): CharArray {
        if (charArrayCapacity >= needed) return charArrayBuffer!!
        val newCapacity = maxOf(needed, 4096)
        charArrayBuffer = CharArray(newCapacity)
        charArrayCapacity = newCapacity
        return charArrayBuffer!!
    }

    override fun finish(destination: Appendable): Int {
        if (pendingCount == 0) return 0

        // Handle incomplete trailing sequence
        val result = handleMalformedInput(destination)
        pendingCount = 0
        return result.charsWritten
    }

    override fun reset() {
        pendingLong = 0L
        pendingCount = 0
    }

    override fun close() {
        charArrayBuffer = null
        charArrayCapacity = 0
        byteArrayBuffer = null
        byteArrayCapacity = 0
    }

    private fun handleMalformedInput(destination: Appendable): DecodeResult =
        when (config.onMalformedInput) {
            DecoderErrorAction.REPORT -> throw CharacterDecodingException("Malformed UTF-8 sequence")
            DecoderErrorAction.REPLACE -> {
                destination.append('\uFFFD')
                DecodeResult(1, 0)
            }
        }

    private data class DecodeResult(
        val charsWritten: Int,
        val bytesConsumed: Int,
    )
}

actual fun StreamingStringDecoder(
    config: StreamingStringDecoderConfig,
): StreamingStringDecoder = LinuxStreamingStringDecoder(config)
