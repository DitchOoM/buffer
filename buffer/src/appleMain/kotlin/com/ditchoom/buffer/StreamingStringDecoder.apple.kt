@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
)

package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRange
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.CFStringCreateWithBytesNoCopy
import platform.CoreFoundation.CFStringGetCharacters
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFAllocatorNull
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

    // Reusable CharArray for CFStringGetCharacters output (avoids per-call allocation)
    private var charBuffer = CharArray(config.charBufferSize)

    // Reusable CFRange allocated once on native heap (avoids per-call memScoped/alloc)
    private val cfRange = nativeHeap.alloc<CFRange>()

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
                pendingLong =
                    pendingLong or ((ptr[boundary + i].toLong() and Utf8.BYTE_MASK.toLong()) shl (i * Utf8.BITS_PER_BYTE))
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
        val leadByte = (pendingLong and Utf8.BYTE_MASK.toLong()).toInt()
        val expectedLen =
            when {
                leadByte < Utf8.ASCII_LIMIT -> 1
                leadByte and Utf8.TWO_BYTE_LEAD_MASK == Utf8.TWO_BYTE_LEAD -> 2
                leadByte and Utf8.THREE_BYTE_LEAD_MASK == Utf8.THREE_BYTE_LEAD -> 3
                leadByte and Utf8.FOUR_BYTE_LEAD_MASK == Utf8.FOUR_BYTE_LEAD -> 4
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
                pendingLong =
                    pendingLong or ((b.toLong() and Utf8.BYTE_MASK.toLong()) shl ((pendingCount + i) * Utf8.BITS_PER_BYTE))
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
        if (byteCount > 3 && (b3 and Utf8.CONTINUATION_MASK) != Utf8.CONTINUATION_MARKER) {
            return handleMalformedInput(destination).charsWritten
        }

        val codePoint =
            when (byteCount) {
                1 -> b0
                2 ->
                    ((b0 and Utf8.TWO_BYTE_PAYLOAD_MASK) shl Utf8.CONTINUATION_SHIFT) or
                        (b1 and Utf8.CONTINUATION_PAYLOAD_MASK)
                3 ->
                    ((b0 and Utf8.THREE_BYTE_PAYLOAD_MASK) shl (Utf8.CONTINUATION_SHIFT * 2)) or
                        ((b1 and Utf8.CONTINUATION_PAYLOAD_MASK) shl Utf8.CONTINUATION_SHIFT) or
                        (b2 and Utf8.CONTINUATION_PAYLOAD_MASK)
                4 ->
                    ((b0 and Utf8.FOUR_BYTE_PAYLOAD_MASK) shl (Utf8.CONTINUATION_SHIFT * 3)) or
                        ((b1 and Utf8.CONTINUATION_PAYLOAD_MASK) shl (Utf8.CONTINUATION_SHIFT * 2)) or
                        ((b2 and Utf8.CONTINUATION_PAYLOAD_MASK) shl Utf8.CONTINUATION_SHIFT) or
                        (b3 and Utf8.CONTINUATION_PAYLOAD_MASK)
                else -> return handleMalformedInput(destination).charsWritten
            }

        // Reject overlong encodings and surrogate codepoints
        val isValid =
            when (byteCount) {
                2 -> codePoint >= Utf8.ASCII_LIMIT
                3 ->
                    codePoint >= Utf8.THREE_BYTE_MIN &&
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

        // Small strings: CFStringCreateWithBytes copies + pre-processes into an optimized
        // internal format, making the subsequent toString() fast. The copy cost is negligible
        // for small inputs, and this avoids the overhead of CFStringGetLength + pin + readValue.
        if (length <= SMALL_STRING_BYTE_THRESHOLD) {
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
            val nsString = CFBridgingRelease(cfString) as platform.Foundation.NSString
            val str = nsString.toString()
            destination.append(str)
            return str.length
        }

        // Large strings: zero-copy input + direct UTF-16 extraction avoids intermediate
        // Kotlin String allocation. CFStringCreateWithBytesNoCopy with kCFAllocatorNull
        // references our pointer without copying (valid for the duration of this call).
        val cfString: CFStringRef? =
            CFStringCreateWithBytesNoCopy(
                kCFAllocatorDefault,
                ptr.reinterpret(),
                length.convert(),
                kCFStringEncodingUTF8,
                false,
                kCFAllocatorNull,
            )

        if (cfString == null) {
            return handleMalformedInput(destination).charsWritten
        }

        val charCount = CFStringGetLength(cfString).toInt()
        if (charCount == 0) {
            CFRelease(cfString)
            return 0
        }

        // Grow reusable char buffer if needed
        if (charBuffer.size < charCount) {
            charBuffer = CharArray(charCount)
        }

        // Extract UTF-16 characters directly into pinned CharArray — no intermediate String
        cfRange.location = 0
        cfRange.length = charCount.convert()
        charBuffer.usePinned { pinned ->
            CFStringGetCharacters(
                cfString,
                cfRange.readValue(),
                pinned.addressOf(0).reinterpret<UShortVar>(),
            )
        }
        CFRelease(cfString)

        // Append characters to destination
        if (destination is StringBuilder) {
            destination.appendRange(charBuffer, 0, charCount)
        } else {
            for (i in 0 until charCount) {
                destination.append(charBuffer[i])
            }
        }
        return charCount
    }

    private fun findUtf8Boundary(
        ptr: CPointer<ByteVar>,
        length: Int,
    ): Int {
        if (length == 0) return 0

        // Fast path: if last byte is ASCII
        if ((ptr[length - 1].toInt() and Utf8.BYTE_MASK) < Utf8.ASCII_LIMIT) {
            return length
        }

        // Scan backwards (max 4 bytes for UTF-8)
        val checkStart = if (length > 4) length - 4 else 0
        var i = length - 1
        while (i >= checkStart) {
            val b = ptr[i].toInt() and Utf8.BYTE_MASK
            // Found a lead byte (not a continuation byte 10xxxxxx)
            if ((b and Utf8.CONTINUATION_MASK) != Utf8.CONTINUATION_MARKER) {
                val seqLen =
                    when {
                        b < Utf8.ASCII_LIMIT -> 1
                        (b and Utf8.TWO_BYTE_LEAD_MASK) == Utf8.TWO_BYTE_LEAD -> 2
                        (b and Utf8.THREE_BYTE_LEAD_MASK) == Utf8.THREE_BYTE_LEAD -> 3
                        (b and Utf8.FOUR_BYTE_LEAD_MASK) == Utf8.FOUR_BYTE_LEAD -> 4
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

    override fun close() {
        nativeHeap.free(cfRange.rawPtr)
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

    private companion object {
        // Below this byte count, NSString.toString() is faster than CFStringGetCharacters + pin.
        // At 256B the CF extraction path's per-call overhead (CFStringGetLength, readValue,
        // usePinned) exceeds the cost of a small Kotlin String allocation.
        const val SMALL_STRING_BYTE_THRESHOLD = 512
    }
}

actual fun StreamingStringDecoder(config: StreamingStringDecoderConfig): StreamingStringDecoder = AppleStreamingStringDecoder(config)
