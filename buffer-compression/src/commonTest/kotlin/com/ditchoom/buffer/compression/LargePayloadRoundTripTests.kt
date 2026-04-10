package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip compression tests at large payload sizes (32KB+) that reproduce
 * JS-specific decompression truncation (JS_COMPRESSION_ISSUES.md).
 *
 * These tests run on ALL platforms. They are expected to:
 * - PASS on JVM and Linux (reference implementations)
 * - FAIL on JS before the fix (decompression truncation at 32KB+)
 * - PASS on JS after the fix
 */
class LargePayloadRoundTripTests {
    companion object {
        private const val SYNC_FLUSH_MARKER = 0x0000FFFF
    }

    // =========================================================================
    // Size sweep: find the truncation boundary
    // =========================================================================

    @Test
    fun roundTrip1KB() = assertRoundTrip(1024)

    @Test
    fun roundTrip4KB() = assertRoundTrip(4096)

    @Test
    fun roundTrip8KB() = assertRoundTrip(8192)

    @Test
    fun roundTrip16KB() = assertRoundTrip(16384)

    @Test
    fun roundTrip32KB() = assertRoundTrip(32768)

    @Test
    fun roundTrip64KB() = assertRoundTrip(65536)

    @Test
    fun roundTrip128KB() = assertRoundTrip(131072)

    // =========================================================================
    // WebSocket-style round-trip at large sizes
    // =========================================================================

    @Test
    fun websocketRoundTrip32KB() = assertWebSocketRoundTrip(32768)

    @Test
    fun websocketRoundTrip64KB() = assertWebSocketRoundTrip(65536)

    @Test
    fun websocketRoundTrip128KB() = assertWebSocketRoundTrip(131072)

    // =========================================================================
    // Sequential messages (simulates Autobahn 1000-message echo)
    // =========================================================================

    @Test
    fun sequential100Messages32KB() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        val messageSize = 32768

        try {
            repeat(100) { i ->
                val compressed = compressAndStripMarker(createPayload(messageSize, seed = i), compressor)
                val result = decompressWithFlush(compressed, decompressor)
                assertEquals(
                    messageSize,
                    result.remaining(),
                    "Message $i: decompressed ${result.remaining()} bytes, expected $messageSize",
                )
                verifyPayload(result, messageSize, seed = i, label = "Message $i")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // finishUnsafe path (one-shot decompression, no context takeover)
    // =========================================================================

    @Test
    fun finishRoundTrip32KB() = assertFinishRoundTrip(32768)

    @Test
    fun finishRoundTrip64KB() = assertFinishRoundTrip(65536)

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * WebSocket-style round-trip: compress+strip marker, decompress+re-append marker+flush.
     * This is the exact code path used for permessage-deflate with context takeover.
     */
    private fun assertWebSocketRoundTrip(size: Int) {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            val original = createPayload(size)
            val compressed = compressAndStripMarker(original, compressor)
            val result = decompressWithFlush(compressed, decompressor)

            assertEquals(size, result.remaining(), "WebSocket round-trip at $size bytes: size mismatch")
            verifyPayload(result, size, label = "WebSocket $size")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Simple round-trip using compressUnsafe + finishUnsafe / decompressUnsafe + finishUnsafe.
     * No context takeover, no sync marker stripping.
     */
    private fun assertFinishRoundTrip(size: Int) {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            val original = createPayload(size)
            original.resetForRead()

            // Compress
            val compressedOutput = BufferFactory.managed().allocate(size + 1024)
            compressor.compressScoped(original) { compressedOutput.write(this) }
            compressor.finishScoped { compressedOutput.write(this) }
            compressedOutput.resetForRead()

            // Decompress
            val decompressedOutput = BufferFactory.managed().allocate(size + 1024)
            decompressor.decompressScoped(compressedOutput) { decompressedOutput.write(this) }
            decompressor.finishScoped { decompressedOutput.write(this) }
            decompressedOutput.resetForRead()

            assertEquals(size, decompressedOutput.remaining(), "Finish round-trip at $size bytes: size mismatch")
            verifyPayload(decompressedOutput, size, label = "Finish $size")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Simple round-trip using flushUnsafe (not finishUnsafe), without WebSocket marker manipulation.
     * Tests that flush produces complete output at each size.
     */
    private fun assertRoundTrip(size: Int) {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            val original = createPayload(size)
            original.resetForRead()

            // Compress with flush
            val compressedOutput = BufferFactory.managed().allocate(size + 1024)
            compressor.compressScoped(original) { compressedOutput.write(this) }
            compressor.flushScoped { compressedOutput.write(this) }
            compressedOutput.resetForRead()

            // Decompress with flush
            val decompressedOutput = BufferFactory.managed().allocate(size + 1024)
            decompressor.decompressScoped(compressedOutput) { decompressedOutput.write(this) }
            decompressor.flushScoped { decompressedOutput.write(this) }
            decompressedOutput.resetForRead()

            assertEquals(size, decompressedOutput.remaining(), "Round-trip at $size bytes: size mismatch")
            verifyPayload(decompressedOutput, size, label = "RoundTrip $size")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    private fun createPayload(
        size: Int,
        seed: Int = 0,
    ): ReadBuffer {
        val buffer = BufferFactory.Default.allocate(size)
        // Non-trivial repeating pattern (mod 251, a prime, avoids alignment artifacts)
        repeat(size) { buffer.writeByte(((it + seed) % 251).toByte()) }
        return buffer
    }

    private fun verifyPayload(
        buffer: ReadBuffer,
        size: Int,
        seed: Int = 0,
        label: String = "",
    ) {
        for (i in 0 until size) {
            val expected = ((i + seed) % 251).toByte()
            val actual = buffer.readByte()
            if (expected != actual) {
                throw AssertionError("$label: byte mismatch at offset $i: expected $expected, got $actual")
            }
        }
    }

    private fun compressAndStripMarker(
        input: ReadBuffer,
        compressor: StreamingCompressor,
    ): ReadBuffer {
        input.resetForRead()
        val inputSize = input.remaining()
        val output = BufferFactory.managed().allocate(inputSize + 1024)
        compressor.compressScoped(input) { output.write(this) }
        compressor.flushScoped { output.write(this) }
        output.resetForRead()

        if (output.remaining() == 0) return BufferFactory.Default.allocate(0)

        // Strip sync flush marker from end if present
        val size = output.remaining()
        if (size >= 4) {
            val pos = output.position()
            output.position(pos + size - 4)
            val m = output.readInt()
            output.position(pos)
            if (m == SYNC_FLUSH_MARKER) {
                output.setLimit(output.limit() - 4)
            }
        }

        return output
    }

    private fun decompressWithFlush(
        buffer: ReadBuffer,
        decompressor: StreamingDecompressor,
    ): ReadBuffer {
        // Decompressed output can be much larger than compressed input
        val outputSize = maxOf(buffer.remaining() * 100, 256 * 1024)
        val output = BufferFactory.managed().allocate(outputSize)

        // Step 1: decompress the payload
        decompressor.decompressScoped(buffer) {
            if (position() != 0) position(0)
            if (remaining() > 0) output.write(this)
        }

        // Step 2: decompress the sync flush marker (re-appended per RFC 7692)
        val marker = BufferFactory.Default.allocate(4)
        marker.writeInt(SYNC_FLUSH_MARKER)
        marker.resetForRead()
        decompressor.decompressScoped(marker) {
            if (position() != 0) position(0)
            if (remaining() > 0) output.write(this)
        }

        // Step 3: flush (NOT finish!) — preserves context for next message
        decompressor.flushScoped {
            if (position() != 0) position(0)
            if (remaining() > 0) output.write(this)
        }

        output.resetForRead()
        return output
    }
}
