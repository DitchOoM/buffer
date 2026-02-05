@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.CFStringGetCStringPtr
import platform.CoreFoundation.CFStringGetLength
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
 */
private class AppleStreamingStringDecoder(
    private val config: StreamingStringDecoderConfig,
) : StreamingStringDecoder {
    init {
        if (config.charset != Charset.UTF8) {
            throw UnsupportedOperationException(
                "Apple StreamingStringDecoder currently only supports UTF-8, got: ${config.charset}"
            )
        }
    }

    // Pending incomplete multi-byte sequence stored as primitives
    private var pending0: Byte = 0
    private var pending1: Byte = 0
    private var pending2: Byte = 0
    private var pendingCount: Int = 0

    override fun decode(buffer: ReadBuffer, destination: Appendable): Int {
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

        // Read data into byte array
        val inputBytes = ByteArray(dataRemaining)
        val startPos = buffer.position() + bytesConsumed
        for (i in 0 until dataRemaining) {
            inputBytes[i] = buffer.get(startPos + i)
        }

        // Find UTF-8 boundary
        val boundary = findUtf8Boundary(inputBytes, dataRemaining)

        // Save incomplete trailing bytes
        if (boundary < dataRemaining) {
            pendingCount = dataRemaining - boundary
            if (pendingCount > 0) pending0 = inputBytes[boundary]
            if (pendingCount > 1) pending1 = inputBytes[boundary + 1]
            if (pendingCount > 2) pending2 = inputBytes[boundary + 2]
        }

        // Convert complete UTF-8 sequences
        if (boundary > 0) {
            val chars = convertUtf8ToAppendable(inputBytes, boundary, destination)
            totalChars += chars
        }

        buffer.position(buffer.position() + remaining)
        return totalChars
    }

    private fun completePendingSequence(buffer: ReadBuffer, destination: Appendable): DecodeResult {
        val leadByte = pending0.toInt() and 0xFF
        val expectedLen = when {
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
                setPendingByte(pendingCount + i, buffer.get(basePos + i))
            }
            pendingCount += available
            return DecodeResult(0, available)
        }

        // Complete the sequence
        val completeSeq = ByteArray(expectedLen)
        for (i in 0 until pendingCount) {
            completeSeq[i] = getPendingByte(i)
        }
        val basePos = buffer.position()
        for (i in 0 until needed) {
            completeSeq[pendingCount + i] = buffer.get(basePos + i)
        }
        pendingCount = 0

        val chars = convertUtf8ToAppendable(completeSeq, expectedLen, destination)
        return DecodeResult(chars, needed)
    }

    private fun convertUtf8ToAppendable(input: ByteArray, length: Int, destination: Appendable): Int {
        if (length == 0) return 0

        val cfString: CFStringRef? = input.usePinned { pinned ->
            CFStringCreateWithBytes(
                kCFAllocatorDefault,
                pinned.addressOf(0).reinterpret(),
                length.convert(),
                kCFStringEncodingUTF8,
                false
            )
        }

        if (cfString == null) {
            return handleMalformedInput(destination).charsWritten
        }

        try {
            // Convert CFString to Kotlin String
            val nsString = CFBridgingRelease(cfString) as platform.Foundation.NSString
            val str = nsString.toString()
            destination.append(str)
            return str.length
        } catch (e: Exception) {
            return handleMalformedInput(destination).charsWritten
        }
    }

    private fun findUtf8Boundary(buffer: ByteArray, length: Int): Int {
        if (length == 0) return 0

        // Fast path: if last byte is ASCII
        if ((buffer[length - 1].toInt() and 0xFF) < 0x80) {
            return length
        }

        // Scan backwards (max 4 bytes for UTF-8)
        val checkStart = if (length > 4) length - 4 else 0
        var i = length - 1
        while (i >= checkStart) {
            val b = buffer[i].toInt() and 0xFF
            // Found a lead byte (not a continuation byte 10xxxxxx)
            if ((b and 0xC0) != 0x80) {
                val seqLen = when {
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

    private fun setPendingByte(index: Int, value: Byte) {
        when (index) {
            0 -> pending0 = value
            1 -> pending1 = value
            2 -> pending2 = value
        }
    }

    private fun getPendingByte(index: Int): Byte = when (index) {
        0 -> pending0
        1 -> pending1
        2 -> pending2
        else -> 0
    }

    override fun finish(destination: Appendable): Int {
        if (pendingCount == 0) return 0
        val result = handleMalformedInput(destination)
        pendingCount = 0
        return result.charsWritten
    }

    override fun reset() {
        pendingCount = 0
        pending0 = 0
        pending1 = 0
        pending2 = 0
    }

    override suspend fun close() {
        // Nothing to close
    }

    private fun handleMalformedInput(destination: Appendable): DecodeResult {
        return when (config.onMalformedInput) {
            DecoderErrorAction.REPORT -> throw CharacterDecodingException("Malformed UTF-8 sequence")
            DecoderErrorAction.REPLACE -> {
                destination.append('\uFFFD')
                DecodeResult(1, 0)
            }
        }
    }

    private data class DecodeResult(val charsWritten: Int, val bytesConsumed: Int)
}

actual fun StreamingStringDecoder(
    config: StreamingStringDecoderConfig,
): StreamingStringDecoder = AppleStreamingStringDecoder(config)
