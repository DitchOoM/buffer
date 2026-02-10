package com.ditchoom.buffer.compression

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.freeAll
import com.ditchoom.buffer.freeIfNeeded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that StreamingCompressor/StreamingDecompressor reset() properly restores state
 * for repeated compress/decompress cycles. Validates correctness assumptions used by
 * the websocket benchmark harness and production per-message-deflate.
 *
 * IMPORTANT: To re-read a buffer that is already in read mode, use `position(0)` — NOT
 * `resetForRead()`. `resetForRead()` does `limit = position; position = 0` which is a
 * write→read flip. Calling it on a buffer with position=0 zeroes the limit.
 */
class StreamingCompressionResetTest {
    companion object {
        private const val SYNC_FLUSH_MARKER = 0x0000FFFF
    }

    // --- Buffer re-read semantics tests ---

    @Test
    fun resetForReadIsWriteToReadFlipNotRewind() {
        // resetForRead() sets limit=position, position=0.
        // For a buffer already in read mode (position=0), this zeroes the limit.
        val buf = PlatformBuffer.allocate(16, AllocationZone.Direct)
        buf.writeBytes(ByteArray(10) { it.toByte() })
        buf.resetForRead() // Now: position=0, limit=10
        assertEquals(10, buf.remaining())

        // Calling resetForRead() again on a buffer with position=0 zeroes limit
        buf.resetForRead()
        assertEquals(0, buf.remaining(), "resetForRead on pos=0 buffer should zero the limit")

        buf.freeIfNeeded()
    }

    @Test
    fun positionZeroIsCorrectWayToRewindReadableBuffer() {
        val buf = PlatformBuffer.allocate(16, AllocationZone.Direct)
        buf.writeBytes(ByteArray(10) { it.toByte() })
        buf.resetForRead() // position=0, limit=10

        // Read some bytes to advance position
        buf.readByte()
        buf.readByte()
        assertEquals(8, buf.remaining())

        // position(0) rewinds without touching limit
        buf.position(0)
        assertEquals(10, buf.remaining(), "position(0) should rewind without changing limit")

        buf.freeIfNeeded()
    }

    @Test
    fun resetForReadAfterFullConsumptionPreservesLimit() {
        // After fully reading a buffer, position == limit.
        // resetForRead() does limit = position (== old limit), position = 0 → correct!
        val buf = PlatformBuffer.allocate(16, AllocationZone.Direct)
        buf.writeBytes(ByteArray(10) { it.toByte() })
        buf.resetForRead() // position=0, limit=10

        // Fully consume
        buf.position(buf.limit())
        assertEquals(0, buf.remaining())

        // resetForRead works here because position == limit (the data end)
        buf.resetForRead()
        assertEquals(10, buf.remaining(), "resetForRead after full consumption should restore")

        buf.freeIfNeeded()
    }

    // --- Decompressor reset tests ---

    @Test
    fun decompressorResetProducesIdenticalOutput() {
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)

        val text = "Hello, WebSocket compression reset test!"
        val compressed = compressWithSyncFlushMarkerStrip(text, compressor)
        compressor.close()

        val results = mutableListOf<String>()
        repeat(5) {
            compressed.position(0) // rewind, don't flip
            val result = decompressToString(compressed, decompressor)
            results.add(result)
            decompressor.reset()
        }

        decompressor.close()
        compressed.freeIfNeeded()

        results.forEach { assertEquals(text, it, "Decompress after reset should produce identical output") }
    }

    @Test
    fun decompressorResetWorksWithSmallPayload() {
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)

        val text = "A".repeat(16) // 16 bytes
        val compressed = compressWithSyncFlushMarkerStrip(text, compressor)
        compressor.close()

        repeat(3) { cycle ->
            compressed.position(0)
            val result = decompressToString(compressed, decompressor)
            assertEquals(text, result, "Small payload decompress cycle $cycle")
            decompressor.reset()
        }

        decompressor.close()
        compressed.freeIfNeeded()
    }

    @Test
    fun decompressorResetWorksWithMediumPayload() {
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)

        val text = "ABCDEFGHIJ".repeat(400) // 4KB
        val compressed = compressWithSyncFlushMarkerStrip(text, compressor)
        compressor.close()

        repeat(3) { cycle ->
            compressed.position(0)
            val result = decompressToString(compressed, decompressor)
            assertEquals(text, result, "Medium payload decompress cycle $cycle")
            decompressor.reset()
        }

        decompressor.close()
        compressed.freeIfNeeded()
    }

    @Test
    fun decompressorResetWorksWithLargePayload() {
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)

        val text = "ABCDEFGHIJ".repeat(6400) // 64KB
        val compressed = compressWithSyncFlushMarkerStrip(text, compressor)
        compressor.close()

        repeat(3) { cycle ->
            compressed.position(0)
            val result = decompressToString(compressed, decompressor)
            assertEquals(text, result, "Large payload decompress cycle $cycle")
            decompressor.reset()
        }

        decompressor.close()
        compressed.freeIfNeeded()
    }

    @Test
    fun decompressorResetAfterFinishRestoresState() {
        // finish() sets streamEnded on Linux — verify reset() clears it
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)

        val text = "Testing finish then reset"
        val compressed = compressWithSyncFlushMarkerStrip(text, compressor)
        compressor.close()

        // First cycle: decompress with finish
        compressed.position(0)
        val result1 = decompressToString(compressed, decompressor)
        assertEquals(text, result1)

        // Reset and decompress again
        decompressor.reset()
        compressed.position(0)
        val result2 = decompressToString(compressed, decompressor)
        assertEquals(text, result2, "Decompress after finish+reset should work")

        decompressor.close()
        compressed.freeIfNeeded()
    }

    @Test
    fun decompressorOutputSizeScalesWithInput() {
        // Validates that decompression actually processes the full payload.
        // If reset is broken, all sizes would produce the same (empty/trivial) output.
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)

        val smallText = "A".repeat(16)
        val mediumText = "A".repeat(4096)
        val largeText = "A".repeat(65536)

        val smallCompressed = compressWithSyncFlushMarkerStrip(smallText, compressor)
        compressor.reset()
        val mediumCompressed = compressWithSyncFlushMarkerStrip(mediumText, compressor)
        compressor.reset()
        val largeCompressed = compressWithSyncFlushMarkerStrip(largeText, compressor)
        compressor.close()

        // Cycle 1
        smallCompressed.position(0)
        val r1 = decompressToString(smallCompressed, decompressor)
        assertEquals(16, r1.length, "Small output length")
        decompressor.reset()

        // Cycle 2
        mediumCompressed.position(0)
        val r2 = decompressToString(mediumCompressed, decompressor)
        assertEquals(4096, r2.length, "Medium output length")
        decompressor.reset()

        // Cycle 3
        largeCompressed.position(0)
        val r3 = decompressToString(largeCompressed, decompressor)
        assertEquals(65536, r3.length, "Large output length")
        decompressor.reset()

        // Verify output length scales (not uniform like a broken reset would produce)
        assertTrue(r2.length > r1.length * 10, "Medium should be much larger than small")
        assertTrue(r3.length > r2.length * 10, "Large should be much larger than medium")

        smallCompressed.freeIfNeeded()
        mediumCompressed.freeIfNeeded()
        largeCompressed.freeIfNeeded()
        decompressor.close()
    }

    // --- Compressor reset tests ---

    @Test
    fun compressorResetProducesIdenticalOutput() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val text = "Compressor reset test with some repeated data repeated data repeated data"

        val outputs = mutableListOf<ByteArray>()
        repeat(5) {
            val input = textToBuffer(text)
            val chunks = mutableListOf<ReadBuffer>()
            compressor.compress(input) { chunks.add(it) }
            compressor.flush { chunks.add(it) }
            outputs.add(chunksToByteArray(chunks))
            chunks.freeAll()
            input.freeIfNeeded()
            compressor.reset()
        }

        compressor.close()

        for (i in 1 until outputs.size) {
            assertTrue(
                outputs[0].contentEquals(outputs[i]),
                "Compressed output at iteration $i should match iteration 0",
            )
        }
    }

    @Test
    fun compressorResetClearsState() {
        // Verify that compressor doesn't carry state from previous messages
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        val texts = listOf("First message", "Second different message", "Third unique content")

        for (text in texts) {
            val input = textToBuffer(text)
            val compressed = compressWithSyncFlushMarkerStripFromBuf(input, compressor)
            input.freeIfNeeded()
            compressor.reset()

            compressed.position(0)
            val decompressed = decompressToString(compressed, decompressor)
            assertEquals(text, decompressed, "Round-trip for: $text")
            compressed.freeIfNeeded()
            decompressor.reset()
        }

        compressor.close()
        decompressor.close()
    }

    // --- Helper functions ---

    private fun textToBuffer(text: String): ReadBuffer {
        val bytes = text.encodeToByteArray()
        val buf = PlatformBuffer.allocate(bytes.size, AllocationZone.Direct)
        buf.writeBytes(bytes)
        buf.resetForRead()
        return buf
    }

    private fun compressWithSyncFlushMarkerStrip(
        text: String,
        compressor: StreamingCompressor,
    ): ReadBuffer {
        val input = textToBuffer(text)
        val result = compressWithSyncFlushMarkerStripFromBuf(input, compressor)
        input.freeIfNeeded()
        return result
    }

    private fun compressWithSyncFlushMarkerStripFromBuf(
        input: ReadBuffer,
        compressor: StreamingCompressor,
    ): ReadBuffer {
        val chunks = mutableListOf<ReadBuffer>()
        compressor.compress(input) { chunks.add(it) }
        compressor.flush { chunks.add(it) }

        if (chunks.isEmpty()) return PlatformBuffer.allocate(0, AllocationZone.Direct)

        // Strip sync flush marker from end
        val lastChunk = chunks.last()
        if (lastChunk.remaining() >= 4) {
            val pos = lastChunk.position()
            val endPos = pos + lastChunk.remaining()
            lastChunk.position(endPos - 4)
            val marker = lastChunk.readInt()
            if (marker == SYNC_FLUSH_MARKER) {
                lastChunk.position(pos)
                lastChunk.setLimit(endPos - 4)
                if (lastChunk.remaining() == 0) {
                    chunks.removeLast()
                }
            } else {
                lastChunk.position(pos)
            }
        }

        // Combine into single buffer
        var totalSize = 0
        for (chunk in chunks) totalSize += chunk.remaining()
        val combined = PlatformBuffer.allocate(totalSize, AllocationZone.Direct)
        for (chunk in chunks) {
            chunk.position(0)
            combined.write(chunk)
        }
        chunks.freeAll()
        combined.resetForRead()
        return combined
    }

    /**
     * Reproduces websocket decompression pipeline:
     * decompress input → decompress sync flush marker → finish → readString
     */
    private fun decompressToString(
        buffer: ReadBuffer,
        decompressor: StreamingDecompressor,
    ): String {
        val chunks = mutableListOf<ReadBuffer>()

        decompressor.decompress(buffer) { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) chunks.add(chunk)
        }

        // Append sync marker and decompress
        val marker = PlatformBuffer.allocate(4, AllocationZone.Direct)
        marker.writeInt(SYNC_FLUSH_MARKER)
        marker.resetForRead()
        decompressor.decompress(marker) { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) chunks.add(chunk)
        }
        marker.freeIfNeeded()

        decompressor.finish { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) chunks.add(chunk)
        }

        if (chunks.isEmpty()) return ""

        if (chunks.size == 1) {
            val chunk = chunks[0]
            val result = chunk.readString(chunk.remaining(), Charset.UTF8)
            chunk.freeIfNeeded()
            return result
        }

        var totalSize = 0
        for (chunk in chunks) totalSize += chunk.remaining()
        val combined = PlatformBuffer.allocate(totalSize, AllocationZone.Heap)
        for (chunk in chunks) {
            chunk.position(0)
            combined.write(chunk)
        }
        chunks.freeAll()
        combined.resetForRead()
        val result = combined.readString(totalSize, Charset.UTF8)
        combined.freeIfNeeded()
        return result
    }

    private fun chunksToByteArray(chunks: List<ReadBuffer>): ByteArray {
        var totalSize = 0
        for (chunk in chunks) {
            if (chunk.position() != 0) chunk.position(0)
            totalSize += chunk.remaining()
        }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            chunk.position(0)
            val remaining = chunk.remaining()
            for (i in 0 until remaining) {
                result[offset++] = chunk.readByte()
            }
        }
        return result
    }
}
