package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the sync persistent zlib stream fix.
 *
 * Prior to this fix, JS Node's sync streaming compressor/decompressor used one-shot
 * zlib calls (deflateRawSync/inflateRawSync), creating a fresh context each time.
 * This broke:
 * - Context takeover (LZ77 sliding window discarded between messages)
 * - Custom windowBits (parameter accepted but silently dropped)
 *
 * These tests validate the fix by exercising:
 * - flush() preserving stream state across calls (persistent stream)
 * - finish() correctly finalizing the stream (one-shot via _processChunk)
 * - Large payloads that exceed the internal chunkSize (16KB), triggering the
 *   multi-iteration writeSync loop that crashed on Node.js v24+
 * - Mixed flush/finish/reset cycles matching real WebSocket patterns
 */
class SyncPersistentStreamTests {
    companion object {
        private const val SYNC_FLUSH_MARKER = 0x0000FFFF
    }

    // =========================================================================
    // Large payload: output exceeds internal chunkSize (16KB)
    // =========================================================================

    @Test
    fun largePayloadContextTakeover() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            // 64KB decompressed = 4x the internal 16KB chunkSize.
            // This was the exact edge case that crashed on Node.js v24 when the inflate
            // output buffer filled exactly, triggering an extra writeSync after Z_STREAM_END.
            val large = "X".repeat(65536)
            val compressed = compressAndStripMarker(large, compressor)
            val result = decompressWithFlush(compressed, decompressor)
            assertEquals(large, result, "64KB payload should decompress correctly")

            // Second message with context takeover (same compressor/decompressor)
            val second = "Y".repeat(65536)
            val compressed2 = compressAndStripMarker(second, compressor)
            val result2 = decompressWithFlush(compressed2, decompressor)
            assertEquals(second, result2, "Second 64KB message with context takeover")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    @Test
    fun largePayloadFinishAndReset() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            // Compress large payload, then decompress with finish() + reset()
            val large = "ABCDEFGHIJ".repeat(6400) // 64KB — highly compressible
            val compressed = compressAndStripMarker(large, compressor)

            // Three cycles: finish + reset
            repeat(3) { cycle ->
                compressed.position(0)
                val result = decompressToStringViaFinish(compressed, decompressor)
                assertEquals(large, result, "Cycle $cycle: 64KB finish+reset")
                decompressor.reset()
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Flush preserves state (persistent stream)
    // =========================================================================

    @Test
    fun flushPreservesCompressionContext() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)

        try {
            // Compress the same message twice without reset.
            // The second compressed output should be SMALLER due to LZ77 back-references.
            val message = "The quick brown fox jumps over the lazy dog. ".repeat(10)
            val compressed1 = compressAndStripMarker(message, compressor)
            val compressed2 = compressAndStripMarker(message, compressor)

            assertTrue(
                compressed2.remaining() < compressed1.remaining(),
                "Second identical message should compress smaller with context takeover " +
                    "(first: ${compressed1.remaining()}, second: ${compressed2.remaining()})",
            )
        } finally {
            compressor.close()
        }
    }

    @Test
    fun flushPreservesDecompressionContext() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            // The decompressor must maintain context to decompress messages
            // that use back-references to previous messages' sliding window.
            val messages =
                listOf(
                    "Hello World",
                    "Hello World", // identical — uses back-ref
                    "Hello World Hello World Hello World", // more back-refs
                    "Goodbye World", // partial back-ref to "World"
                )

            for ((i, msg) in messages.withIndex()) {
                val compressed = compressAndStripMarker(msg, compressor)
                val result = decompressWithFlush(compressed, decompressor)
                assertEquals(msg, result, "Message $i should decompress with context takeover")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Finish destroys stream, reset creates fresh one
    // =========================================================================

    @Test
    fun finishThenResetProducesIdenticalOutput() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        val text = "Finish then reset test data"
        val compressed = compressAndStripMarker(text, compressor)
        compressor.close()

        val results = mutableListOf<String>()
        repeat(5) {
            compressed.position(0)
            results.add(decompressToStringViaFinish(compressed, decompressor))
            decompressor.reset()
        }
        decompressor.close()

        results.forEach { assertEquals(text, it) }
    }

    // =========================================================================
    // Varying payload sizes (exercises chunkSize boundary conditions)
    // =========================================================================

    @Test
    fun varyingPayloadSizesWithContextTakeover() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        // Sizes chosen to exercise boundary conditions around the 16KB chunkSize:
        // below, exactly at, and above the boundary
        val sizes = listOf(1, 100, 1024, 16383, 16384, 16385, 32768, 65536)

        try {
            for (size in sizes) {
                val msg = "Z".repeat(size)
                val compressed = compressAndStripMarker(msg, compressor)
                val result = decompressWithFlush(compressed, decompressor)
                assertEquals(msg.length, result.length, "Size $size should round-trip correctly")
                assertEquals(msg, result)
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    @Test
    fun varyingPayloadSizesWithFinishReset() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        val sizes = listOf(1, 100, 1024, 16383, 16384, 16385, 32768, 65536)

        try {
            for (size in sizes) {
                val msg = "Z".repeat(size)
                val compressed = compressAndStripMarker(msg, compressor)
                compressor.reset()

                compressed.position(0)
                val result = decompressToStringViaFinish(compressed, decompressor)
                assertEquals(msg.length, result.length, "Size $size finish+reset")
                assertEquals(msg, result)
                decompressor.reset()
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // windowBits mismatch: proves decompressor default (15) handles any compressor windowBits
    // =========================================================================

    @Test
    fun customWindowBitsCompressorWithDefaultDecompressor() {
        if (!supportsStatefulFlush) return
        // Compressor uses windowBits=10 (1KB window), decompressor uses default (15 = 32KB).
        // This proves the Autobahn 13.3.x "got length 32, expected 16" error is NOT
        // caused by mismatched windowBits — inflate auto-handles smaller windows.
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, windowBits = 10)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw) // default windowBits

        try {
            val messages =
                listOf(
                    "Hello",
                    "Hello", // back-ref with small window
                    "A".repeat(2048), // exceeds 1KB window — tests window wrapping
                    "B".repeat(16384), // chunkSize boundary
                    "Mixed: Hello World A B C " + "X".repeat(4096),
                )

            for ((i, msg) in messages.withIndex()) {
                val compressed = compressAndStripMarker(msg, compressor)
                val result = decompressWithFlush(compressed, decompressor)
                assertEquals(msg.length, result.length, "windowBits=10 message $i length")
                assertEquals(msg, result, "windowBits=10 message $i content")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    @Test
    fun negativeWindowBitsNormalizedForNodeJs() {
        if (!supportsStatefulFlush) return
        // WebSocket library passes negative windowBits (C zlib convention: -10 = raw deflate, window=10).
        // Node.js createDeflateRaw rejects negative values (valid range: 8-15).
        // The fix normalizes to abs(windowBits) before passing to Node.js.
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, windowBits = -10)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            val messages = listOf("Hello", "Hello", "A".repeat(2048), "Test complete")
            for ((i, msg) in messages.withIndex()) {
                val compressed = compressAndStripMarker(msg, compressor)
                val result = decompressWithFlush(compressed, decompressor)
                assertEquals(msg, result, "Negative windowBits message $i")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    @Test
    fun customWindowBitsRoundTripWithFinishReset() {
        if (!supportsSyncCompression) return
        // Same test with finish+reset cycle — proves the one-shot path also works
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, windowBits = 9)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            val text = "Custom windowBits=9 finish test: " + "Q".repeat(5000)
            val compressed = compressAndStripMarker(text, compressor)

            repeat(3) { cycle ->
                compressed.position(0)
                val result = decompressToStringViaFinish(compressed, decompressor)
                assertEquals(text, result, "windowBits=9 finish+reset cycle $cycle")
                decompressor.reset()
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Gzip and Deflate algorithms (not just Raw)
    // =========================================================================

    @Test
    fun gzipFinishRoundTrip() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Gzip)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Gzip)

        try {
            val text = "Gzip round-trip test with 64KB payload: " + "G".repeat(65000)
            val compressedOutput = BufferFactory.managed().allocate(text.length + 1024)
            val input = textToBuffer(text)
            compressor.compressScoped(input) { compressedOutput.write(this) }
            compressor.finishScoped { compressedOutput.write(this) }
            compressedOutput.resetForRead()

            val decompressedOutput = BufferFactory.managed().allocate(text.length + 1024)
            decompressor.decompressScoped(compressedOutput) { decompressedOutput.write(this) }
            decompressor.finishScoped { decompressedOutput.write(this) }
            decompressedOutput.resetForRead()
            val result = decompressedOutput.readString(decompressedOutput.remaining(), Charset.UTF8)
            assertEquals(text, result, "Gzip 64KB round-trip")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Mixed WebSocket-like pattern
    // =========================================================================

    @Test
    fun websocketPattern_compressFlushDecompressFlush_manyMessages() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            // Simulate WebSocket per-message-deflate: each message is compressed
            // with flush, marker stripped, then decompressed with marker re-added + flush.
            // No reset between messages (context takeover).
            val messages =
                buildList {
                    add("Short")
                    add("A".repeat(100))
                    add("B".repeat(16384)) // exactly chunkSize
                    add("C".repeat(16385)) // chunkSize + 1
                    add("D".repeat(65536)) // 4x chunkSize
                    add("Short again") // back to small after large
                    add("Short again") // identical — back-ref
                    add("E".repeat(32768)) // 2x chunkSize
                }

            for ((i, msg) in messages.withIndex()) {
                val compressed = compressAndStripMarker(msg, compressor)
                val result = decompressWithFlush(compressed, decompressor)
                assertEquals(msg, result, "WebSocket message $i (len=${msg.length})")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun textToBuffer(text: String): ReadBuffer {
        val bytes = text.encodeToByteArray()
        val buf = BufferFactory.Default.allocate(bytes.size)
        buf.writeBytes(bytes)
        buf.resetForRead()
        return buf
    }

    private fun compressAndStripMarker(
        text: String,
        compressor: StreamingCompressor,
    ): ReadBuffer {
        val input = textToBuffer(text)
        // Allocate generously; incompressible data can expand slightly
        val output = BufferFactory.managed().allocate(maxOf(text.length + 1024, 4096))
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
    ): String {
        val estimatedSize = maxOf(buffer.remaining() * 20, 65536 + 1024)
        val output = BufferFactory.managed().allocate(estimatedSize)

        decompressor.decompressScoped(buffer) {
            if (position() != 0) position(0)
            if (remaining() > 0) output.write(this)
        }

        val marker = BufferFactory.Default.allocate(4)
        marker.writeInt(SYNC_FLUSH_MARKER)
        marker.resetForRead()
        decompressor.decompressScoped(marker) {
            if (position() != 0) position(0)
            if (remaining() > 0) output.write(this)
        }

        decompressor.flushScoped {
            if (position() != 0) position(0)
            if (remaining() > 0) output.write(this)
        }

        output.resetForRead()
        if (output.remaining() == 0) return ""
        return output.readString(output.remaining(), Charset.UTF8)
    }

    private fun decompressToStringViaFinish(
        buffer: ReadBuffer,
        decompressor: StreamingDecompressor,
    ): String {
        val estimatedSize = maxOf(buffer.remaining() * 20, 65536 + 1024)
        val output = BufferFactory.managed().allocate(estimatedSize)

        decompressor.decompressScoped(buffer) {
            if (position() != 0) position(0)
            if (remaining() > 0) output.write(this)
        }

        val marker = BufferFactory.Default.allocate(4)
        marker.writeInt(SYNC_FLUSH_MARKER)
        marker.resetForRead()
        decompressor.decompressScoped(marker) {
            if (position() != 0) position(0)
            if (remaining() > 0) output.write(this)
        }

        decompressor.finishScoped {
            if (position() != 0) position(0)
            if (remaining() > 0) output.write(this)
        }

        output.resetForRead()
        if (output.remaining() == 0) return ""
        return output.readString(output.remaining(), Charset.UTF8)
    }

    private fun combineChunks(chunks: List<ReadBuffer>): ReadBuffer {
        if (chunks.isEmpty()) return BufferFactory.Default.allocate(0)
        var totalSize = 0
        for (chunk in chunks) totalSize += chunk.remaining()
        val combined = BufferFactory.Default.allocate(totalSize)
        for (chunk in chunks) {
            chunk.position(0)
            combined.write(chunk)
        }
        combined.resetForRead()
        return combined
    }

    private fun combineAndReadString(chunks: List<ReadBuffer>): String {
        if (chunks.isEmpty()) return ""
        if (chunks.size == 1) {
            val chunk = chunks[0]
            return chunk.readString(chunk.remaining(), Charset.UTF8)
        }
        var totalSize = 0
        for (chunk in chunks) totalSize += chunk.remaining()
        val combined = BufferFactory.managed().allocate(totalSize)
        for (chunk in chunks) {
            chunk.position(0)
            combined.write(chunk)
        }
        combined.resetForRead()
        return combined.readString(totalSize, Charset.UTF8)
    }

    // =========================================================================
    // Regression: output draining when input consumed but output buffer full
    // =========================================================================
    // The processSyncPersistent loop must continue draining zlib output even after
    // all input is consumed (availInAfter == 0) if the output buffer is full
    // (availOutAfter == 0). Before the fix, the loop broke on availInAfter <= 0
    // BEFORE checking availOutAfter, silently truncating output.

    /**
     * High compression ratio: small compressed input decompresses to many times the
     * 16KB chunkSize. All compressed bytes are consumed in the first writeSync call,
     * but decompressed output needs multiple buffer rotations to drain.
     *
     * Before fix: output truncated to first chunkSize worth of data.
     * After fix:  full output returned via drain-with-empty-input iterations.
     */
    @Test
    fun highCompressionRatio_outputExceedsChunkSizeAfterInputConsumed() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Gzip)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Gzip)

        try {
            // Single repeated byte = extreme compression ratio.
            // 128KB decompressed from a few hundred compressed bytes.
            // Decompressor consumes all input in one call, then must drain 8x chunkSize
            // of output across multiple loop iterations with empty input.
            val size = 128 * 1024
            val large = "A".repeat(size)
            val compressedOutput = BufferFactory.managed().allocate(size + 1024)
            val input = textToBuffer(large)
            compressor.compressScoped(input) { compressedOutput.write(this) }
            compressor.finishScoped { compressedOutput.write(this) }
            compressedOutput.resetForRead()

            // Compressed size should be tiny relative to decompressed
            assertTrue(
                compressedOutput.remaining() < size / 10,
                "Expected high compression ratio, got ${compressedOutput.remaining()} bytes from $size",
            )

            val decompressedOutput = BufferFactory.managed().allocate(size + 1024)
            decompressor.decompressScoped(compressedOutput) { decompressedOutput.write(this) }
            decompressor.finishScoped { decompressedOutput.write(this) }
            decompressedOutput.resetForRead()

            assertEquals(
                size,
                decompressedOutput.remaining(),
                "High compression ratio decompression truncated: expected $size bytes, " +
                    "got ${decompressedOutput.remaining()} (likely broke loop on input consumed)",
            )
            assertEquals(large, decompressedOutput.readString(decompressedOutput.remaining(), Charset.UTF8))
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Same high-ratio scenario but via the streaming flush path (context takeover).
     * This exercises processSyncPersistent directly rather than the one-shot path.
     */
    @Test
    fun highCompressionRatio_streamingFlushDrainsAllOutput() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            // 128KB of repeating pattern — compresses to a few hundred bytes.
            // On decompression, input is consumed in one writeSync call but
            // output needs multiple buffer rotations.
            // 64KB of repeating pattern — compresses to a few hundred bytes.
            // On decompression, input is consumed in one writeSync call but
            // output needs multiple buffer rotations.
            val size = 64 * 1024
            val large = "ABCD".repeat(size / 4)
            val compressed = compressAndStripMarker(large, compressor)

            assertTrue(
                compressed.remaining() < size / 10,
                "Expected high compression ratio for flush path test",
            )

            val result = decompressWithFlush(compressed, decompressor)
            assertEquals(
                size,
                result.length,
                "Flush path: expected $size chars, got ${result.length} (output drain incomplete)",
            )
            assertEquals(large, result)
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Decompressed output is an exact multiple of chunkSize (16384 bytes).
     * Tests the boundary where availOutAfter == 0 on the last buffer rotation
     * but there's no more data — the loop must not over-read or hang.
     */
    @Test
    fun exactChunkSizeMultiple_outputDrainStopsCleanly() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            // 3 * 16384 = 49152 bytes — exactly fills 3 output buffers
            val size = 16384 * 3
            val msg = "X".repeat(size)
            val compressed = compressAndStripMarker(msg, compressor)
            val result = decompressWithFlush(compressed, decompressor)
            assertEquals(size, result.length, "Exact 3x chunkSize boundary")
            assertEquals(msg, result)
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Incompressible data: random-ish pattern that barely compresses. The compressed
     * output is approximately the same size as input, so both input and output buffers
     * fill during compression. Tests the input-remaining vs output-full interplay.
     */
    @Test
    fun incompressibleData_roundTripsCorrectly() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            // Build pseudo-random data using all printable ASCII to defeat compression.
            // 48KB = 3x chunkSize ensures multiple buffer rotations on both compress and decompress.
            val size = 48 * 1024
            val sb = StringBuilder(size)
            for (i in 0 until size) {
                sb.append((33 + (i * 7 + i / 256 * 13) % 94).toChar()) // printable ASCII cycle
            }
            val msg = sb.toString()

            val compressed = compressAndStripMarker(msg, compressor)
            val result = decompressWithFlush(compressed, decompressor)
            assertEquals(size, result.length, "Incompressible data: length mismatch")
            assertEquals(msg, result)
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Sequences of alternating high-ratio and incompressible messages with context
     * takeover. This stresses the output drain logic under varying conditions within
     * a single stream lifetime.
     */
    @Test
    fun alternatingCompressionRatios_contextTakeover() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            val messages = listOf(
                "A".repeat(32768),                          // high ratio, 2x chunkSize
                buildString {                                // incompressible, ~20KB
                    for (i in 0 until 20480) append((33 + (i * 7 + i / 256 * 13) % 94).toChar())
                },
                "B".repeat(65536),                          // high ratio, 4x chunkSize
                "Short message",                            // tiny
                buildString {                                // incompressible, ~48KB
                    for (i in 0 until 49152) append((33 + (i * 11 + i / 128 * 17) % 94).toChar())
                },
                "C".repeat(16384),                          // high ratio, exactly chunkSize
            )

            for ((i, msg) in messages.withIndex()) {
                val compressed = compressAndStripMarker(msg, compressor)
                val result = decompressWithFlush(compressed, decompressor)
                assertEquals(
                    msg.length,
                    result.length,
                    "Alternating ratios message $i (len=${msg.length}): got ${result.length}",
                )
                assertEquals(msg, result, "Alternating ratios message $i content")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Error handling: lifecycle transitions must be safe
    // =========================================================================

    @Test
    fun decompressorResetAfterFinishRestoresWorkingState() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        try {
            // First message: compress, decompress via finish
            val msg1 = "first message"
            val compressed1 = compressAndStripMarker(msg1, compressor)
            val output1 = BufferFactory.managed().allocate(1024)
            decompressor.decompressScoped(compressed1) { output1.write(this) }
            decompressor.finishScoped { output1.write(this) }
            output1.resetForRead()
            assertEquals(msg1, output1.readString(output1.remaining(), Charset.UTF8))

            // After finish(), stream is destroyed — reset() must recreate it
            decompressor.reset()
            compressor.reset()

            // Second message must work on the fresh stream
            val msg2 = "second after reset"
            val compressed2 = compressAndStripMarker(msg2, compressor)
            val result2 = decompressWithFlush(compressed2, decompressor)
            assertEquals(msg2, result2)
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    @Test
    fun compressorCloseAfterFlushIsSafe() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)

        // Normal compress + flush cycle
        val msg = "hello"
        val msgBuf = BufferFactory.managed().allocate(msg.length)
        msgBuf.writeString(msg, Charset.UTF8)
        msgBuf.resetForRead()
        compressor.compressScoped(msgBuf) {}
        compressor.flushScoped {}

        // close() after normal use must not throw
        compressor.close()
    }

    @Test
    fun doubleCloseIsSafe() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        compressor.close()
        compressor.close() // must not throw

        decompressor.close()
        decompressor.close() // must not throw
    }
}
