@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
)

package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease

/**
 * Apple implementation using CoreFoundation for optimized UTF-8 decoding.
 *
 * ## Thread Safety
 *
 * StreamingStringDecoder is NOT thread-safe. Use one instance per stream/thread.
 * Do not share instances across coroutines running on different threads.
 *
 * ## Implementation Strategy
 *
 * Uses CFStringCreateWithBytes which is already SIMD-optimized by Apple.
 * Handles incomplete UTF-8 sequences at chunk boundaries with manual tracking.
 *
 * ## Memory Access Optimization
 *
 * Uses a 3-tier access strategy to avoid unnecessary copies:
 * 1. NativeMemoryAccess - zero-copy pointer for MutableDataBuffer/NSDataBuffer (and slices)
 * 2. ManagedMemoryAccess - zero-copy pin for ByteArrayBuffer
 * 3. Fallback - bulk copy via readByteArray for unknown buffer types
 */
private class AppleStreamingStringDecoder(
    private val config: StreamingStringDecoderConfig,
) : StreamingStringDecoder {
    init {
        if (config.charset != Charset.UTF8) {
            throw UnsupportedOperationException(
                "Apple StreamingStringDecoder currently only supports UTF-8, got: ${config.charset}",
            )
        }
    }

    // Pending incomplete multi-byte sequence packed into a Long (avoids ByteArray allocation)
    private var pendingLong: Long = 0
    private var pendingCount: Int = 0

    override fun decode(
        buffer: ReadBuffer,
        destination: Appendable,
    ): Int {
        val remaining = buffer.remaining()
        if (remaining == 0) return 0

        var totalChars = 0
        var bytesConsumed = 0

        // Handle pending bytes from previous chunk
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

        // Try native memory access first (zero-copy for MutableDataBuffer, NSDataBuffer, slices)
        val nativeAccess = buffer.nativeMemoryAccess
        if (nativeAccess != null) {
            val ptr =
                (nativeAccess.nativeAddress + buffer.position() + bytesConsumed)
                    .toCPointer<ByteVar>()!!
            totalChars += processWithPointer(ptr, dataRemaining, destination)
        } else {
            // Try managed memory access (zero-copy pin for ByteArrayBuffer)
            val managedAccess = buffer.managedMemoryAccess
            if (managedAccess != null) {
                val backingArray = managedAccess.backingArray
                val offset = managedAccess.arrayOffset + buffer.position() + bytesConsumed
                totalChars +=
                    backingArray.usePinned { pinned ->
                        val ptr = pinned.addressOf(offset).reinterpret<ByteVar>()
                        processWithPointer(ptr, dataRemaining, destination)
                    }
            } else {
                // Fallback: bulk copy via readByteArray for unknown buffer types
                totalChars += processWithCopy(buffer, bytesConsumed, dataRemaining, destination)
            }
        }

        buffer.position(buffer.position() + remaining)
        return totalChars
    }

    private fun processWithPointer(
        ptr: CPointer<ByteVar>,
        dataRemaining: Int,
        destination: Appendable,
    ): Int {
        // Find UTF-8 boundary
        val boundary = findUtf8Boundary(ptr, dataRemaining)

        // Save incomplete trailing bytes into Long
        if (boundary < dataRemaining) {
            pendingCount = dataRemaining - boundary
            pendingLong = 0L
            for (i in 0 until pendingCount) {
                pendingLong = pendingLong or ((ptr[boundary + i].toLong() and 0xFF) shl (i * 8))
            }
        }

        // Convert complete UTF-8 sequences directly from pointer
        return if (boundary > 0) {
            convertUtf8PtrToAppendable(ptr, boundary, destination)
        } else {
            0
        }
    }

    private fun processWithCopy(
        buffer: ReadBuffer,
        bytesConsumed: Int,
        dataRemaining: Int,
        destination: Appendable,
    ): Int {
        // Save position, bulk-read, restore (caller sets final position)
        val savedPos = buffer.position()
        buffer.position(savedPos + bytesConsumed)
        val tempBuffer = buffer.readByteArray(dataRemaining)
        buffer.position(savedPos)

        return tempBuffer.usePinned { pinned ->
            val ptr = pinned.addressOf(0).reinterpret<ByteVar>()
            processWithPointer(ptr, dataRemaining, destination)
        }
    }

    private fun completePendingSequence(
        buffer: ReadBuffer,
        destination: Appendable,
    ): DecodeResult {
        val leadByte = (pendingLong and 0xFF).toInt()
        val expectedLen =
            when {
                leadByte < 0x80 -> 1
                leadByte and 0xE0 == 0xC0 -> 2
                leadByte and 0xF0 == 0xE0 -> 3
                leadByte and 0xF8 == 0xF0 -> 4
                else -> {
                    pendingCount = 0
                    return handleMalformedInput(destination)
                }
            }

        val needed = expectedLen - pendingCount
        val available = buffer.remaining()

        if (available < needed) {
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

        val cfString: CFStringRef? =
            CFStringCreateWithBytes(
                kCFAllocatorDefault,
                ptr.reinterpret(),
                length.convert(),
                kCFStringEncodingUTF8,
                false,
            )

        if (cfString == null) {
            return handleMalformedInput(destination).charsWritten
        }

        try {
            val nsString = CFBridgingRelease(cfString) as platform.Foundation.NSString
            val str = nsString.toString()
            destination.append(str)
            return str.length
        } catch (e: Exception) {
            return handleMalformedInput(destination).charsWritten
        }
    }

    private fun findUtf8Boundary(
        ptr: CPointer<ByteVar>,
        length: Int,
    ): Int {
        if (length == 0) return 0

        // Fast path: if last byte is ASCII
        if ((ptr[length - 1].toInt() and 0xFF) < 0x80) {
            return length
        }

        // Scan backwards (max 4 bytes for UTF-8)
        val checkStart = if (length > 4) length - 4 else 0
        var i = length - 1
        while (i >= checkStart) {
            val b = ptr[i].toInt() and 0xFF
            // Found a lead byte (not a continuation byte 10xxxxxx)
            if ((b and 0xC0) != 0x80) {
                val seqLen =
                    when {
                        b < 0x80 -> 1
                        (b and 0xE0) == 0xC0 -> 2
                        (b and 0xF0) == 0xE0 -> 3
                        (b and 0xF8) == 0xF0 -> 4
                        else -> return i
                    }
                val available = length - i
                return if (seqLen <= available) length else i
            }
            i--
        }
        return checkStart
    }

    override fun finish(destination: Appendable): Int {
        if (pendingCount == 0) return 0
        val result = handleMalformedInput(destination)
        pendingCount = 0
        return result.charsWritten
    }

    override fun reset() {
        pendingLong = 0L
        pendingCount = 0
    }

    override suspend fun close() {
        // Nothing to close
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

actual fun StreamingStringDecoder(config: StreamingStringDecoderConfig): StreamingStringDecoder = AppleStreamingStringDecoder(config)
