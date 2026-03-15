package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for WebSocket permessage-deflate context takeover (sliding window preserved
 * across messages). These tests exactly replicate the websocket decompression pipeline:
 *
 * For each message:
 *   1. Compress: compress(input) + flush() → strip 00 00 FF FF from end
 *   2. Decompress: decompress(payload) + decompress(SYNC_MARKER) + flush()
 *
 * The compressor and decompressor are NOT reset between messages (context takeover).
 * The 2nd+ message's compressed bytes use LZ77 back-references to previous messages.
 *
 * Bug under investigation: Autobahn case 13.3 shows the 2nd decompressed message
 * produces corrupted UTF-8 when context takeover is enabled.
 */
class ContextTakeoverTests {
    companion object {
        private const val SYNC_FLUSH_MARKER = 0x0000FFFF
    }

    /**
     * Exact replica of websocket's decompressToStringSync using flush() (not finish()).
     * This is critical — flush() behaves differently from finish() regarding currentOutput.
     */
    private fun decompressWithFlush(
        buffer: ReadBuffer,
        decompressor: StreamingDecompressor,
    ): String {
        val chunks = mutableListOf<ReadBuffer>()

        // Step 1: decompress the payload
        decompressor.decompress(buffer) { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) chunks.add(chunk)
        }

        // Step 2: decompress the sync flush marker (re-appended per RFC 7692)
        val marker = BufferFactory.Default.allocate(4)
        marker.writeInt(SYNC_FLUSH_MARKER)
        marker.resetForRead()
        decompressor.decompress(marker) { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) chunks.add(chunk)
        }

        // Step 3: flush (NOT finish!) — this is what the websocket code does
        decompressor.flush { chunk ->
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) chunks.add(chunk)
        }

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

    /**
     * Compress a string using the websocket pattern: compress + flush, strip sync marker.
     */
    private fun compressAndStripMarker(
        text: String,
        compressor: StreamingCompressor,
    ): ReadBuffer {
        val bytes = text.encodeToByteArray()
        val input = BufferFactory.Default.allocate(bytes.size)
        input.writeBytes(bytes)
        input.resetForRead()

        val chunks = mutableListOf<ReadBuffer>()
        compressor.compress(input) { chunks.add(it) }
        compressor.flush { chunks.add(it) }

        if (chunks.isEmpty()) return BufferFactory.Default.allocate(0)

        // Strip sync flush marker from end (same logic as websocket's compressSync)
        val lastChunk = chunks.last()
        if (lastChunk.remaining() >= 4) {
            val pos = lastChunk.position()
            val endPos = pos + lastChunk.remaining()
            lastChunk.position(endPos - 4)
            val m = lastChunk.readInt()
            if (m == SYNC_FLUSH_MARKER) {
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
        val combined = BufferFactory.Default.allocate(totalSize)
        for (chunk in chunks) {
            chunk.position(0)
            combined.write(chunk)
        }
        combined.resetForRead()
        return combined
    }

    // =========================================================================
    // Context takeover with flush() — the exact websocket path
    // =========================================================================

    @Test
    fun contextTakeover_twoMessages_withFlush() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            // Message 1
            val compressed1 = compressAndStripMarker("Hello", compressor)
            val result1 = decompressWithFlush(compressed1, decompressor)
            assertEquals("Hello", result1, "Message 1 should decompress correctly")

            // Message 2 — NO reset (context takeover)
            val compressed2 = compressAndStripMarker("World", compressor)
            val result2 = decompressWithFlush(compressed2, decompressor)
            assertEquals("World", result2, "Message 2 with context takeover should decompress correctly")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    @Test
    fun contextTakeover_multipleMessages_withFlush() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        val messages =
            listOf(
                "Hello",
                "Hello", // Same message should also work with back-references
                "World",
                "Hello World", // Contains references to previous messages
                "WebSocket compression test with context takeover",
                "A".repeat(1000),
                "B".repeat(1000),
                "Mixed content: Hello World WebSocket test 12345",
            )

        try {
            for ((i, msg) in messages.withIndex()) {
                val compressed = compressAndStripMarker(msg, compressor)
                val result = decompressWithFlush(compressed, decompressor)
                assertEquals(msg, result, "Message $i ('${msg.take(30)}...') should decompress correctly")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    @Test
    fun contextTakeover_manyMessages_withFlush() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            repeat(100) { i ->
                val msg = "Message #$i: " + "data".repeat(10 + i % 50)
                val compressed = compressAndStripMarker(msg, compressor)
                val result = decompressWithFlush(compressed, decompressor)
                assertEquals(msg, result, "Message $i should decompress correctly")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Verify flush() vs finish() difference doesn't corrupt context
    // =========================================================================

    @Test
    fun contextTakeover_flushDoesNotCorruptState() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            val compressedMessages = mutableListOf<ReadBuffer>()
            val originalMessages = mutableListOf<String>()

            for (i in 0 until 10) {
                val msg = "Context takeover message $i with some padding data: ${"X".repeat(50 + i * 10)}"
                originalMessages.add(msg)
                compressedMessages.add(compressAndStripMarker(msg, compressor))
            }

            for (i in 0 until 10) {
                val result = decompressWithFlush(compressedMessages[i], decompressor)
                assertEquals(
                    originalMessages[i],
                    result,
                    "Message $i should decompress correctly with flush()-based context takeover",
                )
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Shared marker buffer + freeIfNeeded (exact websocket pattern)
    // =========================================================================

    /**
     * Shared, pre-allocated sync flush marker buffer — exactly like the websocket's
     * SYNC_FLUSH_MARKER_BUFFER. Reused across all decompress calls.
     */
    private val sharedMarkerBuffer: ReadBuffer by lazy {
        val buffer = PlatformBuffer.allocate(4)
        // Write individual bytes to avoid byte order issues.
        // PlatformBuffer.allocate() defaults to ByteOrder.NATIVE (LITTLE_ENDIAN on x86).
        // writeInt(0x0000FFFF) on little-endian produces FF FF 00 00 — wrong marker.
        buffer.writeByte(0x00)
        buffer.writeByte(0x00)
        buffer.writeByte(0xFF.toByte())
        buffer.writeByte(0xFF.toByte())
        buffer.resetForRead()
        buffer
    }

    /**
     * Exact replica of websocket's decompressToStringSync with:
     * - Shared/reused SYNC_FLUSH_MARKER_BUFFER
     * - Collects all chunks then combines before decoding (avoids UTF-8 boundary issues)
     */
    private fun decompressWithSharedMarker(
        buffer: ReadBuffer,
        decompressor: StreamingDecompressor,
    ): String {
        val chunks = mutableListOf<ReadBuffer>()

        fun collectChunk(chunk: ReadBuffer) {
            if (chunk.position() != 0) chunk.position(0)
            if (chunk.remaining() > 0) chunks.add(chunk)
        }

        decompressor.decompress(buffer) { collectChunk(it) }

        sharedMarkerBuffer.position(0)
        decompressor.decompress(sharedMarkerBuffer) { collectChunk(it) }

        decompressor.flush { collectChunk(it) }

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

    @Test
    fun contextTakeover_sharedMarkerBuffer_twoMessages() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            val compressed1 = compressAndStripMarker("Hello", compressor)
            val result1 = decompressWithSharedMarker(compressed1, decompressor)
            assertEquals("Hello", result1, "Message 1")

            val compressed2 = compressAndStripMarker("World", compressor)
            val result2 = decompressWithSharedMarker(compressed2, decompressor)
            assertEquals("World", result2, "Message 2 with shared marker + context takeover")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Diagnostic test: same as shared marker test but with fresh marker each time.
     * This isolates whether the shared buffer reuse is the cause.
     */
    @Test
    fun contextTakeover_freshMarkerBuffer_twoMessages() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        fun decompressWithFreshMarker(buffer: ReadBuffer): String {
            val chunks = mutableListOf<ReadBuffer>()

            fun collectChunk(chunk: ReadBuffer) {
                if (chunk.position() != 0) chunk.position(0)
                if (chunk.remaining() > 0) chunks.add(chunk)
            }

            decompressor.decompress(buffer) { collectChunk(it) }

            // Fresh marker buffer each time — write bytes to avoid byte order issues
            val marker = PlatformBuffer.allocate(4)
            marker.writeByte(0x00)
            marker.writeByte(0x00)
            marker.writeByte(0xFF.toByte())
            marker.writeByte(0xFF.toByte())
            marker.resetForRead()
            decompressor.decompress(marker) { collectChunk(it) }

            decompressor.flush { collectChunk(it) }

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

        try {
            val compressed1 = compressAndStripMarker("Hello", compressor)
            val result1 = decompressWithFreshMarker(compressed1)
            assertEquals("Hello", result1, "Message 1")

            val compressed2 = compressAndStripMarker("World", compressor)
            val result2 = decompressWithFreshMarker(compressed2)
            assertEquals("World", result2, "Message 2 with fresh marker + context takeover")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    @Test
    fun contextTakeover_sharedMarkerBuffer_manyMessages() {
        if (!supportsSyncCompression) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            repeat(50) { i ->
                val msg = "Message #$i: " + "data".repeat(10 + i % 50)
                val compressed = compressAndStripMarker(msg, compressor)
                val result = decompressWithSharedMarker(compressed, decompressor)
                assertEquals(msg, result, "Message $i with shared marker")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Simulate Autobahn: server compresses, client decompresses + echoes
    // =========================================================================

    @Test
    fun contextTakeover_serverCompressesClientDecompresses() {
        if (!supportsSyncCompression) return
        // Server compressor (shared context)
        val serverCompressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        // Client decompressor (shared context)
        val clientDecompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            // Autobahn case 13.3.1: server sends same text message twice
            val messages = listOf("Hello", "Hello")

            for ((i, msg) in messages.withIndex()) {
                val compressed = compressAndStripMarker(msg, serverCompressor)
                val decompressed = decompressWithSharedMarker(compressed, clientDecompressor)
                assertEquals(msg, decompressed, "Server message $i should decompress correctly")
            }
        } finally {
            serverCompressor.close()
            clientDecompressor.close()
        }
    }

    @Test
    fun contextTakeover_serverCompresses_variousPayloads() {
        if (!supportsSyncCompression) return
        val serverCompressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val clientDecompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            // Various payloads similar to Autobahn test cases
            val messages =
                listOf(
                    "Hello",
                    "Hello",
                    "A".repeat(64),
                    "A".repeat(64),
                    "B".repeat(256),
                    "*".repeat(1000),
                    "*".repeat(1000),
                    "Mixed ${"ABC".repeat(100)} content",
                )

            for ((i, msg) in messages.withIndex()) {
                val compressed = compressAndStripMarker(msg, serverCompressor)
                val decompressed = decompressWithSharedMarker(compressed, clientDecompressor)
                assertEquals(msg, decompressed, "Message $i (len=${msg.length})")
            }
        } finally {
            serverCompressor.close()
            clientDecompressor.close()
        }
    }
}
