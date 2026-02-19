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
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned

/**
 * Native implementation using simdutf for SIMD-accelerated UTF-8 decoding.
 *
 * Used on all native targets (Linux, macOS, iOS, watchOS, tvOS).
 * simdutf uses NEON on ARM64 and SSE4.2/AVX2 on x86_64 for vectorized conversion.
 *
 * ## Thread Safety
 *
 * StreamingStringDecoder is NOT thread-safe. Use one instance per stream/thread.
 * Do not share instances across coroutines running on different threads.
 *
 * ## Implementation Strategy
 *
 * 1. Find UTF-8 boundary using buf_simdutf_utf8_find_boundary (handles incomplete sequences)
 * 2. Convert complete UTF-8 sequences to UTF-16 using buf_simdutf_convert_utf8_to_chararray
 * 3. Save incomplete trailing bytes (max 3) as primitives for next chunk
 * 4. Append UTF-16 chars to destination Appendable
 */
private class NativeStreamingStringDecoder(
    private val config: StreamingStringDecoderConfig,
) : StreamingStringDecoder {
    init {
        if (config.charset != Charset.UTF8) {
            throw UnsupportedOperationException(
                "Native StreamingStringDecoder currently only supports UTF-8, got: ${config.charset}",
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

        // Try native memory access first (zero-copy for NativeBuffer, MutableDataBuffer, slices)
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
                pendingLong = pendingLong or ((buffer.get(basePos + i).toLong() and 0xFF) shl (i * 8))
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
        val leadByte = (pendingLong and 0xFF).toInt()
        val expectedLen =
            when {
                leadByte < 0x80 -> 1
                leadByte and 0xE0 == 0xC0 -> 2
                leadByte and 0xF0 == 0xE0 -> 3
                leadByte and 0xF8 == 0xF0 -> 4
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
                pendingLong = pendingLong or ((b.toLong() and 0xFF) shl ((pendingCount + i) * 8))
            }
            pendingCount += available
            return DecodeResult(0, available)
        }

        // Load remaining bytes from buffer into pendingLong
        val basePos = buffer.position()
        for (i in 0 until needed) {
            val b = buffer.get(basePos + i)
            pendingLong = pendingLong or ((b.toLong() and 0xFF) shl ((pendingCount + i) * 8))
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
        val b0 = (pendingLong and 0xFF).toInt()
        val b1 = if (byteCount > 1) ((pendingLong ushr 8) and 0xFF).toInt() else 0
        val b2 = if (byteCount > 2) ((pendingLong ushr 16) and 0xFF).toInt() else 0
        val b3 = if (byteCount > 3) ((pendingLong ushr 24) and 0xFF).toInt() else 0

        // Validate continuation bytes
        if (byteCount > 1 && (b1 and 0xC0) != 0x80) return handleMalformedInput(destination).charsWritten
        if (byteCount > 2 && (b2 and 0xC0) != 0x80) return handleMalformedInput(destination).charsWritten
        if (byteCount > 3 && (b3 and 0xC0) != 0x80) return handleMalformedInput(destination).charsWritten

        val codePoint =
            when (byteCount) {
                1 -> b0
                2 -> ((b0 and 0x1F) shl 6) or (b1 and 0x3F)
                3 -> ((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)
                4 -> ((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                else -> return handleMalformedInput(destination).charsWritten
            }

        // Reject overlong encodings and surrogate codepoints
        val isValid =
            when (byteCount) {
                2 -> codePoint >= 0x80
                3 -> codePoint >= 0x800 && (codePoint < 0xD800 || codePoint > 0xDFFF)
                4 -> codePoint in 0x10000..0x10FFFF
                else -> true
            }
        if (!isValid) return handleMalformedInput(destination).charsWritten

        return if (codePoint <= 0xFFFF) {
            destination.append(codePoint.toChar())
            1
        } else {
            val adjusted = codePoint - 0x10000
            destination.append((0xD800 + (adjusted shr 10)).toChar())
            destination.append((0xDC00 + (adjusted and 0x3FF)).toChar())
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

    override suspend fun close() {
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

actual fun StreamingStringDecoder(config: StreamingStringDecoderConfig): StreamingStringDecoder = NativeStreamingStringDecoder(config)
