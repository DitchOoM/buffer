package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for two JS-specific bugs documented in:
 * - JS_CONTEXT_TAKEOVER_BUG.md: flush() doesn't drain zlib Transform stream,
 *   causing message N-1 output to leak into message N.
 * - JS_WINDOWBITS_SIGN.md: custom windowBits (e.g., server_max_window_bits=9)
 *   causes decompression to fail silently.
 *
 * These tests are designed to FAIL before the fix and PASS after.
 */
class JsBugRegressionTests {
    companion object {
        private const val SYNC_FLUSH_MARKER = 0x0000FFFF
    }

    // =========================================================================
    // JS_CONTEXT_TAKEOVER_BUG: flush() must drain ALL output per message
    // =========================================================================

    /**
     * Exact reproduction from JS_CONTEXT_TAKEOVER_BUG.md.
     *
     * Before fix: second decompression returns '{"msg":"hello"}{"msg":"world"}' (30 bytes)
     * After fix:  second decompression returns '{"msg":"world"}' (15 bytes)
     */
    @Test
    fun contextTakeover_secondMessageMustNotContainFirstMessage() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            val msg0 = """{"msg":"hello"}"""
            val msg1 = """{"msg":"world"}"""

            // Message 0
            val compressed0 = compressAndStripMarker(msg0, compressor)
            val result0 = decompressWithFlush(compressed0, decompressor)
            assertEquals(msg0, result0, "msg0 content")

            // Message 1 — NO reset (context takeover)
            val compressed1 = compressAndStripMarker(msg1, compressor)
            val result1 = decompressWithFlush(compressed1, decompressor)

            // The exact failure from the bug: result1 == msg0 + msg1 (accumulated)
            assertEquals(msg1.length, result1.length, "msg1 length must be ${msg1.length}, not ${msg0.length + msg1.length}")
            assertFalse(
                result1.startsWith(msg0),
                "msg1 must NOT start with msg0 — flush() leaked previous output into this message",
            )
            assertEquals(msg1, result1, "msg1 content")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Extended accumulation test: verify each message in a sequence decompresses
     * to exactly its own content with no leakage from prior messages.
     */
    @Test
    fun contextTakeover_noAccumulation_acrossManyMessages() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        val messages = (0 until 20).map { i -> """{"seq":$i,"data":"${"X".repeat(50 + i * 10)}"}""" }
        var accumulatedLength = 0

        try {
            for ((i, msg) in messages.withIndex()) {
                accumulatedLength += msg.length
                val compressed = compressAndStripMarker(msg, compressor)
                val result = decompressWithFlush(compressed, decompressor)

                assertEquals(
                    msg.length,
                    result.length,
                    "Message $i: expected length ${msg.length}, got ${result.length}. " +
                        "If got $accumulatedLength, flush() is accumulating all prior messages.",
                )
                assertEquals(msg, result, "Message $i content mismatch")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * The accumulation bug is most visible with small messages where the output
     * fits in a single zlib internal buffer. Verify with tiny 1-byte payloads.
     */
    @Test
    fun contextTakeover_noAccumulation_tinyMessages() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            repeat(50) { i ->
                val msg = ('A' + (i % 26)).toString()
                val compressed = compressAndStripMarker(msg, compressor)
                val result = decompressWithFlush(compressed, decompressor)
                assertEquals(1, result.length, "Message $i: expected 1 byte, got ${result.length} (accumulated)")
                assertEquals(msg, result, "Message $i")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // JS_WINDOWBITS_SIGN: custom windowBits must not cause silent failure
    // =========================================================================

    /**
     * Autobahn case 13.3.x scenario: server compresses with windowBits=9,
     * client decompresses with default windowBits (15).
     *
     * Before fix: decompression fails silently (produces empty/wrong output)
     * After fix:  decompression succeeds, output matches input
     */
    @Test
    fun windowBits9_serverCompressClientDecompress_mustSucceed() {
        if (!supportsStatefulFlush) return
        val serverCompressor = StreamingCompressor.create(CompressionAlgorithm.Raw, windowBits = 9)
        val clientDecompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw) // default=15

        try {
            val msg = "Hello"
            val compressed = compressAndStripMarker(msg, serverCompressor)

            assertTrue(compressed.remaining() > 0, "Compressed output must not be empty")

            val result = decompressWithFlush(compressed, clientDecompressor)
            assertEquals(msg.length, result.length, "Decompressed length must match original")
            assertEquals(msg, result, "Decompressed content must match original")
        } finally {
            serverCompressor.close()
            clientDecompressor.close()
        }
    }

    /**
     * Autobahn case 13.3.x with context takeover: multiple messages compressed
     * with windowBits=9, decompressed with default windowBits.
     */
    @Test
    fun windowBits9_contextTakeover_multipleMessages() {
        if (!supportsStatefulFlush) return
        val serverCompressor = StreamingCompressor.create(CompressionAlgorithm.Raw, windowBits = 9)
        val clientDecompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            // Simulate Autobahn 13.3.x: server sends same message repeatedly
            val messages = listOf("Hello", "Hello", "A".repeat(64), "A".repeat(64))
            for ((i, msg) in messages.withIndex()) {
                val compressed = compressAndStripMarker(msg, serverCompressor)
                val result = decompressWithFlush(compressed, clientDecompressor)
                assertEquals(msg, result, "windowBits=9 context takeover message $i")
            }
        } finally {
            serverCompressor.close()
            clientDecompressor.close()
        }
    }

    /**
     * Negative windowBits (C zlib convention: -9 means raw deflate with window=9).
     * Node.js createDeflateRaw/createInflateRaw rejects negative values.
     *
     * Before fix: Node.js throws "Invalid windowBits" or produces wrong output
     * After fix:  negative windowBits normalized to positive, round-trip succeeds
     */
    @Test
    fun negativeWindowBits9_mustNormalizeAndSucceed() {
        if (!supportsStatefulFlush) return
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, windowBits = -9)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            val msg = "Negative windowBits test"
            val compressed = compressAndStripMarker(msg, compressor)
            val result = decompressWithFlush(compressed, decompressor)
            assertEquals(msg, result, "Negative windowBits=-9 must round-trip correctly")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    /**
     * Both bugs combined: custom windowBits=9 + context takeover across messages.
     * This is the exact Autobahn 13.3.x scenario that triggered both issues.
     */
    @Test
    fun windowBits9_contextTakeover_noAccumulation() {
        if (!supportsStatefulFlush) return
        val serverCompressor = StreamingCompressor.create(CompressionAlgorithm.Raw, windowBits = 9)
        val clientDecompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)

        try {
            val msg0 = "Hello"
            val msg1 = "Hello" // identical — tests back-ref with small window

            val compressed0 = compressAndStripMarker(msg0, serverCompressor)
            val result0 = decompressWithFlush(compressed0, clientDecompressor)
            assertEquals(msg0, result0, "windowBits=9 msg0")

            val compressed1 = compressAndStripMarker(msg1, serverCompressor)
            val result1 = decompressWithFlush(compressed1, clientDecompressor)

            // Combined failure mode: if both bugs present, result1 could be
            // "HelloHello" (10 bytes) instead of "Hello" (5 bytes)
            assertEquals(
                msg1.length,
                result1.length,
                "windowBits=9 msg1: expected ${msg1.length} bytes, got ${result1.length}. " +
                    "If ${msg0.length + msg1.length}, both bugs are present (accumulation + windowBits).",
            )
            assertEquals(msg1, result1, "windowBits=9 msg1 content")
        } finally {
            serverCompressor.close()
            clientDecompressor.close()
        }
    }

    // =========================================================================
    // Helpers — same decompress/compress pattern as ContextTakeoverTests
    // =========================================================================

    private fun compressAndStripMarker(
        text: String,
        compressor: StreamingCompressor,
    ): ReadBuffer {
        val bytes = text.encodeToByteArray()
        val input = BufferFactory.Default.allocate(bytes.size)
        input.writeBytes(bytes)
        input.resetForRead()

        val output = BufferFactory.managed().allocate(text.length + 1024)
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
        val output = BufferFactory.managed().allocate(maxOf(buffer.remaining() * 20, 65536 + 1024))

        decompressor.decompressScoped(buffer) {
            if (position() != 0) position(0)
            if (remaining() > 0) output.write(this)
        }

        // Re-append sync flush marker per RFC 7692
        val marker = BufferFactory.Default.allocate(4)
        marker.writeByte(0x00)
        marker.writeByte(0x00)
        marker.writeByte(0xFF.toByte())
        marker.writeByte(0xFF.toByte())
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
}
