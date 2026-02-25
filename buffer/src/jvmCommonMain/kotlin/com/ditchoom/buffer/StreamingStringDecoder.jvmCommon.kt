package com.ditchoom.buffer

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction

/**
 * JVM implementation using java.nio.charset.CharsetDecoder with reusable CharBuffer.
 *
 * Key optimizations:
 * - CharsetDecoder is created once and reused via reset()
 * - CharBuffer is allocated once and reused for all decode() calls
 * - For heap buffers, uses zero-copy view via ByteBuffer.wrap()
 * - For direct buffers, uses the buffer directly
 * - Pending incomplete bytes are saved between decode() calls
 */
private class JvmStreamingStringDecoder(
    private val config: StreamingStringDecoderConfig,
) : StreamingStringDecoder {
    companion object {
        private val EMPTY_BYTE_BUFFER: ByteBuffer = ByteBuffer.allocate(0).asReadOnlyBuffer()
    }

    private val javaCharset =
        when (config.charset) {
            Charset.UTF8 -> Charsets.UTF_8
            Charset.UTF16 -> Charsets.UTF_16
            Charset.UTF16BigEndian -> Charsets.UTF_16BE
            Charset.UTF16LittleEndian -> Charsets.UTF_16LE
            Charset.ASCII -> Charsets.US_ASCII
            Charset.ISOLatin1 -> Charsets.ISO_8859_1
            Charset.UTF32 -> Charsets.UTF_32
            Charset.UTF32LittleEndian -> Charsets.UTF_32LE
            Charset.UTF32BigEndian -> Charsets.UTF_32BE
        }

    private val decoder: CharsetDecoder =
        javaCharset.newDecoder().apply {
            onMalformedInput(config.onMalformedInput.toCodingErrorAction())
            onUnmappableCharacter(config.onUnmappableCharacter.toCodingErrorAction())
        }

    // Reusable CharBuffer for decoding output
    private val charBuffer: CharBuffer = CharBuffer.allocate(config.charBufferSize)

    // Pending incomplete bytes stored as a Long (max 7 bytes: UTF-32 can have up to 3 pending)
    private var pendingLong: Long = 0
    private var pendingCount = 0

    // Combined buffer for pending + new input
    private var combinedBuffer: ByteBuffer? = null

    override fun decode(
        buffer: ReadBuffer,
        destination: Appendable,
    ): Int {
        val remaining = buffer.remaining()
        if (remaining == 0 && pendingCount == 0) return 0

        var totalChars = 0

        // Create input ByteBuffer, prepending any pending bytes
        val byteBuffer =
            if (pendingCount > 0) {
                getCombinedByteBuffer(buffer, remaining)
            } else {
                getByteBuffer(buffer, remaining)
            }

        val startPosition = byteBuffer.position()

        while (byteBuffer.hasRemaining()) {
            charBuffer.clear()
            val result = decoder.decode(byteBuffer, charBuffer, false)

            // Append decoded characters
            charBuffer.flip()
            if (charBuffer.hasRemaining()) {
                val count = charBuffer.remaining()
                destination.append(charBuffer)
                totalChars += count
            }

            when {
                result.isUnderflow -> {
                    // Save any remaining bytes as pending for next call
                    val unconsumed = byteBuffer.remaining()
                    if (unconsumed > 0) {
                        pendingCount = unconsumed
                        pendingLong = 0L
                        for (i in 0 until unconsumed) {
                            pendingLong = pendingLong or ((byteBuffer.get().toLong() and 0xFF) shl (i * 8))
                        }
                    } else {
                        pendingCount = 0
                    }
                    break
                }
                result.isOverflow -> {
                    // CharBuffer full, continue loop to process more
                    continue
                }
                result.isMalformed || result.isUnmappable -> {
                    pendingCount = 0
                    handleCoderResult(result, buffer.position())
                }
            }
        }

        // Update source buffer position - consume all bytes from the original buffer
        buffer.position(buffer.position() + remaining)

        return totalChars
    }

    private fun getByteBuffer(
        buffer: ReadBuffer,
        remaining: Int,
    ): ByteBuffer {
        // Try to get zero-copy access (unwrap PooledBuffer if needed)
        val actual = buffer.unwrapFully()
        if (actual is BaseJvmBuffer) {
            val bb = actual.byteBuffer.asReadOnlyBuffer()
            (bb as java.nio.Buffer).position(buffer.position())
            (bb as java.nio.Buffer).limit(buffer.position() + remaining)
            return bb
        }

        // Check for managed array access
        val managedAccess = buffer.managedMemoryAccess
        if (managedAccess != null) {
            return ByteBuffer.wrap(
                managedAccess.backingArray,
                managedAccess.arrayOffset + buffer.position(),
                remaining,
            )
        }

        // Fallback: copy to temporary buffer
        var cb = combinedBuffer
        if (cb == null || cb.capacity() < remaining) {
            cb = ByteBuffer.allocate(maxOf(remaining, 8192))
            combinedBuffer = cb
        }
        (cb as java.nio.Buffer).clear()

        // Copy data
        val startPos = buffer.position()
        for (i in 0 until remaining) {
            cb.put(buffer.get(startPos + i))
        }
        (cb as java.nio.Buffer).flip()
        return cb
    }

    private fun getCombinedByteBuffer(
        buffer: ReadBuffer,
        remaining: Int,
    ): ByteBuffer {
        val totalSize = pendingCount + remaining

        // Ensure combined buffer is large enough
        var cb = combinedBuffer
        if (cb == null || cb.capacity() < totalSize) {
            cb = ByteBuffer.allocate(maxOf(totalSize, 8192))
            combinedBuffer = cb
        }
        (cb as java.nio.Buffer).clear()

        // Add pending bytes first (unpack from Long)
        for (i in 0 until pendingCount) {
            cb.put(((pendingLong ushr (i * 8)) and 0xFF).toByte())
        }
        pendingCount = 0

        // Add new bytes
        val startPos = buffer.position()
        for (i in 0 until remaining) {
            cb.put(buffer.get(startPos + i))
        }

        (cb as java.nio.Buffer).flip()
        return cb
    }

    override fun finish(destination: Appendable): Int {
        var totalChars = 0

        // If there are pending bytes, try to decode them with endOfInput=true
        if (pendingCount > 0) {
            // Unpack pending Long into a temporary ByteBuffer
            var cb = combinedBuffer
            if (cb == null || cb.capacity() < pendingCount) {
                cb = ByteBuffer.allocate(maxOf(pendingCount, 8192))
                combinedBuffer = cb
            }
            (cb as java.nio.Buffer).clear()
            for (i in 0 until pendingCount) {
                cb.put(((pendingLong ushr (i * 8)) and 0xFF).toByte())
            }
            (cb as java.nio.Buffer).flip()
            val byteBuffer = cb
            pendingCount = 0

            charBuffer.clear()
            val result = decoder.decode(byteBuffer, charBuffer, true)

            charBuffer.flip()
            if (charBuffer.hasRemaining()) {
                val count = charBuffer.remaining()
                destination.append(charBuffer)
                totalChars += count
            }

            if (result.isMalformed || result.isUnmappable) {
                handleCoderResult(result, -1)
            }
        } else {
            // Signal end of input with empty buffer
            charBuffer.clear()
            decoder.decode(EMPTY_BYTE_BUFFER.duplicate(), charBuffer, true)

            charBuffer.flip()
            if (charBuffer.hasRemaining()) {
                val count = charBuffer.remaining()
                destination.append(charBuffer)
                totalChars += count
            }
        }

        // Flush any remaining output
        charBuffer.clear()
        val flushResult = decoder.flush(charBuffer)
        charBuffer.flip()
        if (charBuffer.hasRemaining()) {
            val count = charBuffer.remaining()
            destination.append(charBuffer)
            totalChars += count
        }

        if (flushResult.isMalformed || flushResult.isUnmappable) {
            handleCoderResult(flushResult, -1)
        }

        return totalChars
    }

    override fun reset() {
        decoder.reset()
        charBuffer.clear()
        pendingLong = 0L
        pendingCount = 0
    }

    override suspend fun close() {
        // Nothing to close - decoder and charBuffer are managed by GC
    }

    private fun handleCoderResult(
        result: CoderResult,
        position: Int,
    ) {
        if (result.isMalformed) {
            throw CharacterDecodingException(
                "Malformed input of length ${result.length()}",
                position,
            )
        }
        if (result.isUnmappable) {
            throw CharacterDecodingException(
                "Unmappable character of length ${result.length()}",
                position,
            )
        }
    }
}

private fun DecoderErrorAction.toCodingErrorAction(): CodingErrorAction =
    when (this) {
        DecoderErrorAction.REPORT -> CodingErrorAction.REPORT
        DecoderErrorAction.REPLACE -> CodingErrorAction.REPLACE
    }

actual fun StreamingStringDecoder(config: StreamingStringDecoderConfig): StreamingStringDecoder = JvmStreamingStringDecoder(config)
