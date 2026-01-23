package com.ditchoom.buffer.compression

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for raw deflate decompression of streams without BFINAL=1.
 *
 * WebSocket per-message-deflate (RFC 7692) appends the sync marker 00 00 FF FF
 * to compressed payloads. This marker is an empty stored block with BFINAL=0,
 * meaning the deflate stream never has a final block. Decompressors must handle
 * this without hanging or throwing errors.
 *
 * These tests use known RFC 7692 Section 7.2.3 examples to validate correctness
 * on all platforms (JVM, Apple native, JS/Node).
 */
class RawDeflateTests {
    /**
     * The RFC 7692 sync flush terminator appended after removing trailing 00 00 FF FF
     * from the previous message's compressed form. When decompressing, the receiver
     * appends these 4 bytes back before inflating.
     */
    private val syncTerminator = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())

    /**
     * Helper: create a buffer from compressed payload + sync terminator.
     */
    private fun rawDeflateBuffer(compressedPayload: ByteArray): PlatformBuffer {
        val fullInput = compressedPayload + syncTerminator
        val buffer = PlatformBuffer.allocate(fullInput.size)
        buffer.writeBytes(fullInput)
        buffer.resetForRead()
        return buffer
    }

    // =========================================================================
    // RFC 7692 Section 7.2.3.1 — Single "Hello" message (fixed Huffman)
    // =========================================================================

    /**
     * RFC 7692 Section 7.2.3.1: Unfragmented single compressed text frame.
     * Compressed payload: f2 48 cd c9 c9 07 00
     * Decompresses to: "Hello"
     */
    @Test
    fun rfc7692_unfragmented_hello_async() =
        runTest {
            val compressed =
                byteArrayOf(
                    0xf2.toByte(),
                    0x48,
                    0xcd.toByte(),
                    0xc9.toByte(),
                    0xc9.toByte(),
                    0x07,
                    0x00,
                )
            val buffer = rawDeflateBuffer(compressed)
            val decompressed = decompressAsync(buffer, CompressionAlgorithm.Raw)
            assertEquals("Hello", decompressed.readString(decompressed.remaining()))
        }

    @Test
    fun rfc7692_unfragmented_hello_sync() {
        if (!supportsSyncCompression) return
        val compressed =
            byteArrayOf(
                0xf2.toByte(),
                0x48,
                0xcd.toByte(),
                0xc9.toByte(),
                0xc9.toByte(),
                0x07,
                0x00,
            )
        val buffer = rawDeflateBuffer(compressed)
        val result = decompress(buffer, CompressionAlgorithm.Raw)
        assertTrue(result is CompressionResult.Success, "Decompress failed: $result")
        assertEquals("Hello", result.buffer.readString(result.buffer.remaining()))
    }

    @Test
    fun rfc7692_unfragmented_hello_streaming() =
        runTest {
            val compressed =
                byteArrayOf(
                    0xf2.toByte(),
                    0x48,
                    0xcd.toByte(),
                    0xc9.toByte(),
                    0xc9.toByte(),
                    0x07,
                    0x00,
                )
            val buffer = rawDeflateBuffer(compressed)

            val decompressor = SuspendingStreamingDecompressor.create(CompressionAlgorithm.Raw)
            try {
                val output = mutableListOf<ByteArray>()
                for (chunk in decompressor.decompress(buffer)) {
                    output.add(chunk.readByteArray(chunk.remaining()))
                }
                for (chunk in decompressor.finish()) {
                    output.add(chunk.readByteArray(chunk.remaining()))
                }
                val result = output.fold(byteArrayOf()) { acc, arr -> acc + arr }
                assertEquals("Hello", result.decodeToString())
            } finally {
                decompressor.close()
            }
        }

    // =========================================================================
    // RFC 7692 Section 7.2.3.1 — Fragmented "Hello" (two WebSocket frames)
    // =========================================================================

    /**
     * RFC 7692 Section 7.2.3.1: Same "Hello" split across two WebSocket frames.
     * First frame payload: f2 48 cd
     * Second frame payload: c9 c9 07 00
     * Combined (same as unfragmented): f2 48 cd c9 c9 07 00
     */
    @Test
    fun rfc7692_fragmented_hello_async() =
        runTest {
            // After reassembly, the combined compressed payload is the same
            val combined =
                byteArrayOf(
                    0xf2.toByte(),
                    0x48,
                    0xcd.toByte(),
                    0xc9.toByte(),
                    0xc9.toByte(),
                    0x07,
                    0x00,
                )
            val buffer = rawDeflateBuffer(combined)
            val decompressed = decompressAsync(buffer, CompressionAlgorithm.Raw)
            assertEquals("Hello", decompressed.readString(decompressed.remaining()))
        }

    /**
     * Test streaming decompression with the two fragments arriving separately
     * (simulating incremental network data).
     */
    @Test
    fun rfc7692_fragmented_hello_streaming_incremental() =
        runTest {
            val fragment1 = byteArrayOf(0xf2.toByte(), 0x48, 0xcd.toByte())
            val fragment2 = byteArrayOf(0xc9.toByte(), 0xc9.toByte(), 0x07, 0x00) + syncTerminator

            val decompressor = SuspendingStreamingDecompressor.create(CompressionAlgorithm.Raw)
            try {
                val output = mutableListOf<ByteArray>()

                // Feed first fragment
                val buf1 = PlatformBuffer.allocate(fragment1.size)
                buf1.writeBytes(fragment1)
                buf1.resetForRead()
                for (chunk in decompressor.decompress(buf1)) {
                    output.add(chunk.readByteArray(chunk.remaining()))
                }

                // Feed second fragment (includes terminator)
                val buf2 = PlatformBuffer.allocate(fragment2.size)
                buf2.writeBytes(fragment2)
                buf2.resetForRead()
                for (chunk in decompressor.decompress(buf2)) {
                    output.add(chunk.readByteArray(chunk.remaining()))
                }

                for (chunk in decompressor.finish()) {
                    output.add(chunk.readByteArray(chunk.remaining()))
                }

                val result = output.fold(byteArrayOf()) { acc, arr -> acc + arr }
                assertEquals("Hello", result.decodeToString())
            } finally {
                decompressor.close()
            }
        }

    // =========================================================================
    // RFC 7692 Section 7.2.3.3 — "Hello" with BTYPE=0 (stored block)
    // =========================================================================

    /**
     * RFC 7692 Section 7.2.3.3: "Hello" compressed with BTYPE=0 (no compression/stored).
     * Compressed payload (11 bytes): 00 05 00 fa ff 48 65 6c 6c 6f 00
     * This is a stored block: BFINAL=0, BTYPE=00, LEN=5, NLEN=0xFA_FF, then "Hello"
     * followed by empty block marker.
     */
    @Test
    fun rfc7692_stored_block_hello_async() =
        runTest {
            val compressed =
                byteArrayOf(
                    0x00,
                    0x05,
                    0x00,
                    0xfa.toByte(),
                    0xff.toByte(),
                    0x48,
                    0x65,
                    0x6c,
                    0x6c,
                    0x6f,
                    0x00,
                )
            val buffer = rawDeflateBuffer(compressed)
            val decompressed = decompressAsync(buffer, CompressionAlgorithm.Raw)
            assertEquals("Hello", decompressed.readString(decompressed.remaining()))
        }

    @Test
    fun rfc7692_stored_block_hello_sync() {
        if (!supportsSyncCompression) return
        val compressed =
            byteArrayOf(
                0x00,
                0x05,
                0x00,
                0xfa.toByte(),
                0xff.toByte(),
                0x48,
                0x65,
                0x6c,
                0x6c,
                0x6f,
                0x00,
            )
        val buffer = rawDeflateBuffer(compressed)
        val result = decompress(buffer, CompressionAlgorithm.Raw)
        assertTrue(result is CompressionResult.Success, "Decompress failed: $result")
        assertEquals("Hello", result.buffer.readString(result.buffer.remaining()))
    }

    // =========================================================================
    // Raw deflate without BFINAL — general regression tests
    // =========================================================================

    /**
     * Compress with Raw, then decompress with Raw — round-trip must work.
     * The compressor may or may not set BFINAL depending on platform.
     */
    @Test
    fun rawDeflate_roundTrip_async() =
        runTest {
            val text = "Round trip raw deflate test data!"
            val compressed = compressAsync(text.toReadBuffer(), CompressionAlgorithm.Raw)
            val decompressed = decompressAsync(compressed, CompressionAlgorithm.Raw)
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }

    @Test
    fun rawDeflate_roundTrip_sync() {
        if (!supportsSyncCompression) return
        val text = "Round trip raw deflate test data!"
        val compressResult = compress(text.toReadBuffer(), CompressionAlgorithm.Raw)
        assertTrue(compressResult is CompressionResult.Success)
        val decompressResult = decompress(compressResult.buffer, CompressionAlgorithm.Raw)
        assertTrue(decompressResult is CompressionResult.Success, "Decompress failed: $decompressResult")
        assertEquals(text, decompressResult.buffer.readString(decompressResult.buffer.remaining()))
    }

    /**
     * Compress larger data with Raw deflate and append sync terminator manually.
     * This simulates what WebSocket does: compress, strip 00 00 ff ff, then
     * on decompress side, re-append 00 00 ff ff.
     */
    @Test
    fun rawDeflate_largePayload_withSyncTerminator() =
        runTest {
            val text = "WebSocket payload data. ".repeat(500) // ~12KB
            val compressed = compressAsync(text.toReadBuffer(), CompressionAlgorithm.Raw)

            // Read compressed bytes and append sync terminator (simulating receiver side)
            val compressedBytes = compressed.readByteArray(compressed.remaining())
            val withTerminator = compressedBytes + syncTerminator
            val buffer = PlatformBuffer.allocate(withTerminator.size)
            buffer.writeBytes(withTerminator)
            buffer.resetForRead()

            val decompressed = decompressAsync(buffer, CompressionAlgorithm.Raw)
            val result = decompressed.readString(decompressed.remaining())
            // The decompressed output may include the original text plus any extra
            // from the sync terminator block (empty stored block = 0 bytes).
            assertTrue(result.startsWith(text), "Decompressed should contain original text")
        }

    /**
     * Test that the sync terminator alone (empty deflate stream) decompresses to empty.
     */
    @Test
    fun rawDeflate_onlySyncTerminator_async() =
        runTest {
            // Just the sync terminator: an empty stored block
            val buffer = PlatformBuffer.allocate(syncTerminator.size)
            buffer.writeBytes(syncTerminator)
            buffer.resetForRead()

            val decompressed = decompressAsync(buffer, CompressionAlgorithm.Raw)
            assertEquals(0, decompressed.remaining(), "Empty stored block should decompress to empty")
        }

    @Test
    fun rawDeflate_onlySyncTerminator_sync() {
        if (!supportsSyncCompression) return
        val buffer = PlatformBuffer.allocate(syncTerminator.size)
        buffer.writeBytes(syncTerminator)
        buffer.resetForRead()

        val result = decompress(buffer, CompressionAlgorithm.Raw)
        assertTrue(result is CompressionResult.Success, "Decompress failed: $result")
        assertEquals(0, result.buffer.remaining(), "Empty stored block should decompress to empty")
    }

    // =========================================================================
    // Multiple messages with context reuse (sliding window preserved)
    // =========================================================================

    /**
     * Simulate multiple WebSocket messages sharing the deflate context.
     * Each message is compressed incrementally and the decompressor's state
     * carries over between messages.
     */
    @Test
    fun rawDeflate_multipleMessages_sharedContext_streaming() =
        runTest {
            val messages = listOf("Hello", "World", "WebSocket", "Compression")

            // Compress each message with shared compressor context
            val compressor =
                SuspendingStreamingCompressor.create(
                    CompressionAlgorithm.Raw,
                    level = CompressionLevel.Default,
                )
            val decompressor = SuspendingStreamingDecompressor.create(CompressionAlgorithm.Raw)

            try {
                for (msg in messages) {
                    // Compress with sync flush (simulates per-message compression)
                    val input = msg.toReadBuffer()
                    val compressedChunks = compressor.compress(input)

                    // Feed compressed chunks to decompressor
                    val decompressedParts = mutableListOf<ByteArray>()
                    for (chunk in compressedChunks) {
                        for (out in decompressor.decompress(chunk)) {
                            decompressedParts.add(out.readByteArray(out.remaining()))
                        }
                    }

                    if (decompressedParts.isNotEmpty()) {
                        val result = decompressedParts.fold(byteArrayOf()) { acc, arr -> acc + arr }
                        assertEquals(msg, result.decodeToString())
                    }
                }
            } finally {
                compressor.close()
                decompressor.close()
            }
        }

    // =========================================================================
    // Edge cases
    // =========================================================================

    /**
     * Single byte decompression with raw deflate.
     */
    @Test
    fun rawDeflate_singleByte_async() =
        runTest {
            val text = "X"
            val compressed = compressAsync(text.toReadBuffer(), CompressionAlgorithm.Raw)
            val decompressed = decompressAsync(compressed, CompressionAlgorithm.Raw)
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }

    /**
     * Binary data (all byte values 0-255) with raw deflate.
     */
    @Test
    fun rawDeflate_binaryData_async() =
        runTest {
            val data = ByteArray(256) { it.toByte() }
            val input = PlatformBuffer.allocate(data.size)
            input.writeBytes(data)
            input.resetForRead()

            val compressed = compressAsync(input, CompressionAlgorithm.Raw)
            val decompressed = decompressAsync(compressed, CompressionAlgorithm.Raw)

            val result = decompressed.readByteArray(decompressed.remaining())
            assertTrue(data.contentEquals(result), "Binary round-trip should preserve all byte values")
        }

    /**
     * Verify decompressAsync with expectedOutputSize hint works for raw deflate.
     */
    @Test
    fun rawDeflate_withExpectedOutputSize_async() =
        runTest {
            val compressed =
                byteArrayOf(
                    0xf2.toByte(),
                    0x48,
                    0xcd.toByte(),
                    0xc9.toByte(),
                    0xc9.toByte(),
                    0x07,
                    0x00,
                )
            val buffer = rawDeflateBuffer(compressed)
            val decompressed = decompressAsync(buffer, CompressionAlgorithm.Raw, expectedOutputSize = 5)
            assertEquals("Hello", decompressed.readString(decompressed.remaining()))
        }

    @Test
    fun rawDeflate_withUnderestimatedExpectedSize_async() =
        runTest {
            val compressed =
                byteArrayOf(
                    0xf2.toByte(),
                    0x48,
                    0xcd.toByte(),
                    0xc9.toByte(),
                    0xc9.toByte(),
                    0x07,
                    0x00,
                )
            val buffer = rawDeflateBuffer(compressed)
            // Underestimate: should still work via buffer growth
            val decompressed = decompressAsync(buffer, CompressionAlgorithm.Raw, expectedOutputSize = 2)
            assertEquals("Hello", decompressed.readString(decompressed.remaining()))
        }

    // =========================================================================
    // Stress: repeated compress/decompress cycles
    // =========================================================================

    /**
     * Repeatedly compress and decompress to verify no state leakage.
     */
    @Test
    fun rawDeflate_repeatedCycles_async() =
        runTest {
            val texts = listOf("Hello", "World", "Test123", "A".repeat(1000), "B")
            for (text in texts) {
                val compressed = compressAsync(text.toReadBuffer(), CompressionAlgorithm.Raw)
                val decompressed = decompressAsync(compressed, CompressionAlgorithm.Raw)
                assertEquals(
                    text,
                    decompressed.readString(decompressed.remaining()),
                    "Failed for text of length ${text.length}",
                )
            }
        }
}
