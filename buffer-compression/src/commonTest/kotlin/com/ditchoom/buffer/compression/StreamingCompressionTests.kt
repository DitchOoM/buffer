package com.ditchoom.buffer.compression

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingCompressionTests {
    // =========================================================================
    // Expected compressed bytes (must match one-shot compression output)
    // =========================================================================

    // "Hello" compressed with Gzip, default level
    private val expectedGzipHello =
        intArrayOf(
            0x1f,
            0x8b,
            0x08,
            0x00, // magic, method, flags
            0x00,
            0x00,
            0x00,
            0x00, // mtime (0)
            0x00,
            0xff, // xfl, os (0xff = unknown)
            0xf3,
            0x48,
            0xcd,
            0xc9,
            0xc9,
            0x07,
            0x00, // compressed data
            0x82,
            0x89,
            0xd1,
            0xf7, // CRC32 (little-endian)
            0x05,
            0x00,
            0x00,
            0x00, // ISIZE (little-endian) = 5
        )

    // "Hello" compressed with Deflate (zlib), default level
    private val expectedDeflateHello =
        intArrayOf(
            0x78,
            0x9c, // CMF, FLG (zlib header)
            0xf3,
            0x48,
            0xcd,
            0xc9,
            0xc9,
            0x07,
            0x00, // compressed data
            0x05,
            0x8c,
            0x01,
            0xf5, // Adler32 (big-endian)
        )

    // "Hello" compressed with Raw deflate, default level
    private val expectedRawHello =
        intArrayOf(
            0xf3,
            0x48,
            0xcd,
            0xc9,
            0xc9,
            0x07,
            0x00, // compressed data only
        )

    // =========================================================================
    // Deterministic output tests
    // =========================================================================

    @Test
    fun streamingGzipProducesDeterministicOutput() {
        if (!supportsSyncCompression) return

        val compressedChunks = mutableListOf<ReadBuffer>()

        StreamingCompressor.create(algorithm = CompressionAlgorithm.Gzip).use(
            onOutput = { compressedChunks.add(it) },
        ) { compress ->
            compress("Hello".toReadBuffer())
        }

        val compressed = combineBuffers(compressedChunks)
        val actualBytes = IntArray(compressed.remaining()) { compressed.readByte().toInt() and 0xFF }

        // Validate header (bytes 0-9), skip OS byte (index 9) as it varies
        for (i in 0 until 9) {
            assertEquals(
                expectedGzipHello[i],
                actualBytes[i],
                "Streaming Gzip byte $i mismatch: expected 0x${expectedGzipHello[i].toString(16)}, " +
                    "got 0x${actualBytes[i].toString(16)}",
            )
        }

        // Validate compressed data and trailer (bytes 10+)
        for (i in 10 until expectedGzipHello.size) {
            assertEquals(
                expectedGzipHello[i],
                actualBytes[i],
                "Streaming Gzip byte $i mismatch: expected 0x${expectedGzipHello[i].toString(16)}, " +
                    "got 0x${actualBytes[i].toString(16)}",
            )
        }

        assertEquals(expectedGzipHello.size, actualBytes.size, "Streaming Gzip output size mismatch")
    }

    @Test
    fun streamingDeflateProducesDeterministicOutput() {
        if (!supportsSyncCompression) return

        val compressedChunks = mutableListOf<ReadBuffer>()

        StreamingCompressor.create(algorithm = CompressionAlgorithm.Deflate).use(
            onOutput = { compressedChunks.add(it) },
        ) { compress ->
            compress("Hello".toReadBuffer())
        }

        val compressed = combineBuffers(compressedChunks)
        val actualBytes = IntArray(compressed.remaining()) { compressed.readByte().toInt() and 0xFF }

        // Validate all bytes match expected
        for (i in expectedDeflateHello.indices) {
            assertEquals(
                expectedDeflateHello[i],
                actualBytes[i],
                "Streaming Deflate byte $i mismatch: expected 0x${expectedDeflateHello[i].toString(16)}, " +
                    "got 0x${actualBytes[i].toString(16)}",
            )
        }

        assertEquals(expectedDeflateHello.size, actualBytes.size, "Streaming Deflate output size mismatch")
    }

    @Test
    fun streamingRawDeflateProducesDeterministicOutput() {
        if (!supportsSyncCompression) return

        val compressedChunks = mutableListOf<ReadBuffer>()

        StreamingCompressor.create(algorithm = CompressionAlgorithm.Raw).use(
            onOutput = { compressedChunks.add(it) },
        ) { compress ->
            compress("Hello".toReadBuffer())
        }

        val compressed = combineBuffers(compressedChunks)
        val actualBytes = IntArray(compressed.remaining()) { compressed.readByte().toInt() and 0xFF }

        // Validate all bytes match expected
        for (i in expectedRawHello.indices) {
            assertEquals(
                expectedRawHello[i],
                actualBytes[i],
                "Streaming Raw deflate byte $i mismatch: expected 0x${expectedRawHello[i].toString(16)}, " +
                    "got 0x${actualBytes[i].toString(16)}",
            )
        }

        assertEquals(expectedRawHello.size, actualBytes.size, "Streaming Raw deflate output size mismatch")
    }

    @Test
    fun useExtensionAutoFinishesAndCloses() {
        if (!supportsSyncCompression) return

        val chunks = mutableListOf<ReadBuffer>()

        // Using the new use() extension - no manual finish/close needed
        StreamingCompressor.create(algorithm = CompressionAlgorithm.Gzip).use(
            onOutput = { chunks.add(it) },
        ) { compress ->
            compress("Hello".toReadBuffer())
        }

        // Verify we got output (finish was called)
        assertTrue(chunks.isNotEmpty(), "Should have compressed output")

        // Verify it's valid gzip (can be decompressed via streaming)
        assertEquals("Hello", streamDecompress(chunks, CompressionAlgorithm.Gzip))
    }

    @Test
    fun useExtensionWithMultipleChunks() {
        if (!supportsSyncCompression) return

        val chunks = mutableListOf<ReadBuffer>()

        StreamingCompressor.create(algorithm = CompressionAlgorithm.Gzip).use(
            onOutput = { chunks.add(it) },
        ) { compress ->
            compress("Hello, ".toReadBuffer())
            compress("World!".toReadBuffer())
        }

        assertEquals("Hello, World!", streamDecompress(chunks, CompressionAlgorithm.Gzip))
    }

    @Test
    fun useSuspendingWithSimulatedAsyncIO() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            val chunks = mutableListOf<ReadBuffer>()

            // Simulate async I/O by using useSuspending which allows suspend calls in block
            StreamingCompressor.create(algorithm = CompressionAlgorithm.Gzip).useSuspending(
                onOutput = { chunks.add(it) },
            ) { compress ->
                // In real code, these could be suspending network reads
                val networkData1 = simulateNetworkRead("Hello, ")
                compress(networkData1)

                val networkData2 = simulateNetworkRead("World!")
                compress(networkData2)
            }

            assertEquals("Hello, World!", streamDecompress(chunks, CompressionAlgorithm.Gzip))
        }

    // Simulate an async network read
    private suspend fun simulateNetworkRead(data: String): ReadBuffer {
        // In real code, this would be socket.read() or similar
        return data.toReadBuffer()
    }

    @Test
    fun streamingMatchesOneShotCompression() {
        if (!supportsSyncCompression) return

        val text = "Hello"

        // One-shot compression
        val oneShotResult = compress(text.toReadBuffer(), CompressionAlgorithm.Gzip)
        assertTrue(oneShotResult is CompressionResult.Success)
        val oneShotBytes =
            IntArray(oneShotResult.buffer.remaining()) {
                oneShotResult.buffer.readByte().toInt() and 0xFF
            }

        // Streaming compression
        val streamingChunks = mutableListOf<ReadBuffer>()

        StreamingCompressor.create(algorithm = CompressionAlgorithm.Gzip).use(
            onOutput = { streamingChunks.add(it) },
        ) { compress ->
            compress(text.toReadBuffer())
        }

        val streamingBuffer = combineBuffers(streamingChunks)
        val streamingBytes =
            IntArray(streamingBuffer.remaining()) {
                streamingBuffer.readByte().toInt() and 0xFF
            }

        // Compare sizes
        assertEquals(oneShotBytes.size, streamingBytes.size, "Output sizes should match")

        // Compare all bytes except OS byte (index 9)
        for (i in oneShotBytes.indices) {
            if (i == 9) continue // Skip OS byte
            assertEquals(
                oneShotBytes[i],
                streamingBytes[i],
                "Byte $i mismatch: one-shot=0x${oneShotBytes[i].toString(16)}, " +
                    "streaming=0x${streamingBytes[i].toString(16)}",
            )
        }
    }

    // =========================================================================
    // Functional tests
    // =========================================================================

    @Test
    fun streamingCompressAndDecompressSingleChunk() {
        if (!supportsSyncCompression) return

        val text = "Hello, Streaming World!"
        val compressedChunks = mutableListOf<ReadBuffer>()

        StreamingCompressor.create().use(
            onOutput = { compressedChunks.add(it) },
        ) { compress ->
            compress(text.toReadBuffer())
        }

        assertTrue(compressedChunks.isNotEmpty())

        // Combine compressed chunks into a single buffer
        val compressed = combineBuffers(compressedChunks)

        // Decompress
        val decompressedChunks = mutableListOf<ReadBuffer>()

        StreamingDecompressor.create().use(
            onOutput = { decompressedChunks.add(it) },
        ) { decompress ->
            decompress(compressed)
        }

        // Combine and verify
        val decompressed = combineBuffers(decompressedChunks)
        assertEquals(text, decompressed.readString(decompressed.remaining()))
    }

    @Test
    fun streamingCompressMultipleChunks() {
        if (!supportsSyncCompression) return

        val chunk1 = "First chunk of data. "
        val chunk2 = "Second chunk of data. "
        val chunk3 = "Third chunk of data."
        val fullText = chunk1 + chunk2 + chunk3

        val compressedChunks = mutableListOf<ReadBuffer>()

        StreamingCompressor.create().use(
            onOutput = { compressedChunks.add(it) },
        ) { compress ->
            compress(chunk1.toReadBuffer())
            compress(chunk2.toReadBuffer())
            compress(chunk3.toReadBuffer())
        }

        // Decompress using streaming
        assertEquals(fullText, streamDecompress(compressedChunks))
    }

    @Test
    fun streamingCompressWithReset() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create()
        val text1 = "First compression."
        val text2 = "Second compression."

        try {
            // First compression
            val compressed1 = mutableListOf<ReadBuffer>()
            compressor.compress(text1.toReadBuffer()) { chunk ->
                compressed1.add(chunk)
            }
            compressor.finish { chunk ->
                compressed1.add(chunk)
            }

            // Reset and compress again
            compressor.reset()

            val compressed2 = mutableListOf<ReadBuffer>()
            compressor.compress(text2.toReadBuffer()) { chunk ->
                compressed2.add(chunk)
            }
            compressor.finish { chunk ->
                compressed2.add(chunk)
            }

            // Verify both compressions work
            assertTrue(compressed1.isNotEmpty())
            assertTrue(compressed2.isNotEmpty())

            // Verify decompression of both using streaming
            assertEquals(text1, streamDecompress(compressed1))
            assertEquals(text2, streamDecompress(compressed2))
        } finally {
            compressor.close()
        }
    }

    @Test
    fun streamingWithGzip() {
        if (!supportsSyncCompression) return

        val text = "Testing gzip streaming compression"
        val compressedChunks = mutableListOf<ReadBuffer>()

        StreamingCompressor.create(algorithm = CompressionAlgorithm.Gzip).use(
            onOutput = { compressedChunks.add(it) },
        ) { compress ->
            compress(text.toReadBuffer())
        }

        // Verify gzip magic number (peek at first chunk)
        assertTrue(compressedChunks.isNotEmpty(), "Should have compressed output")
        val firstChunk = compressedChunks[0]
        assertTrue(firstChunk.remaining() >= 2, "First chunk should have at least 2 bytes")
        assertEquals(0x1f.toByte(), firstChunk.readByte(), "First gzip magic byte")
        assertEquals(0x8b.toByte(), firstChunk.readByte(), "Second gzip magic byte")

        // Decompress using streaming (need to reset first chunk position)
        firstChunk.position(0)
        assertEquals(text, streamDecompress(compressedChunks, CompressionAlgorithm.Gzip))
    }

    @Test
    fun streamingWithRaw() {
        if (!supportsSyncCompression) return

        val text = "Testing raw streaming compression"
        val compressedChunks = mutableListOf<ReadBuffer>()

        StreamingCompressor.create(algorithm = CompressionAlgorithm.Raw).use(
            onOutput = { compressedChunks.add(it) },
        ) { compress ->
            compress(text.toReadBuffer())
        }

        // Decompress using streaming
        assertEquals(text, streamDecompress(compressedChunks, CompressionAlgorithm.Raw))
    }

    @Test
    fun streamingLargeData() {
        if (!supportsSyncCompression) return

        // Test with larger data that exceeds typical buffer sizes
        val text = "Large data chunk for streaming test. ".repeat(1000)
        val chunkSize = 4096 // Simulate network chunks
        val compressedChunks = mutableListOf<ReadBuffer>()

        StreamingCompressor.create().use(
            onOutput = { compressedChunks.add(it) },
        ) { compress ->
            // Send data in chunks
            var offset = 0
            while (offset < text.length) {
                val end = minOf(offset + chunkSize, text.length)
                val chunk = text.substring(offset, end)
                compress(chunk.toReadBuffer())
                offset = end
            }
        }

        val originalSize = text.length
        val compressedSize = compressedChunks.sumOf { it.remaining() }

        // Verify compression worked
        assertTrue(
            compressedSize < originalSize,
            "Compression should reduce size for repetitive data. Original: $originalSize, Compressed: $compressedSize",
        )

        // Decompress using streaming
        assertEquals(text, streamDecompress(compressedChunks))
    }

    @Test
    fun streamingDecompressInChunks() {
        if (!supportsSyncCompression) return

        // Compress some data first
        val text = "Data to be decompressed in chunks. ".repeat(100)
        val compressResult = compress(text.toReadBuffer(), CompressionAlgorithm.Gzip)
        assertTrue(compressResult is CompressionResult.Success)
        val compressed = compressResult.buffer

        // Decompress in chunks
        val decompressedChunks = mutableListOf<ReadBuffer>()
        val chunkSize = 32 // Small chunks to test buffering

        StreamingDecompressor.create(algorithm = CompressionAlgorithm.Gzip).use(
            onOutput = { decompressedChunks.add(it) },
        ) { decompress ->
            while (compressed.remaining() > 0) {
                val bytesToRead = minOf(chunkSize, compressed.remaining())
                // Create a chunk buffer and copy bytes into it
                val chunk = PlatformBuffer.allocate(bytesToRead)
                repeat(bytesToRead) {
                    chunk.writeByte(compressed.readByte())
                }
                chunk.resetForRead()
                decompress(chunk)
            }
        }

        val decompressed = combineBuffers(decompressedChunks)
        assertEquals(text, decompressed.readString(decompressed.remaining()))
    }

    /**
     * Stream compressed buffers through decompressor and return the result as a string.
     */
    private fun streamDecompress(
        compressedChunks: List<ReadBuffer>,
        algorithm: CompressionAlgorithm = CompressionAlgorithm.Deflate,
    ): String {
        val decompressedChunks = mutableListOf<ReadBuffer>()

        StreamingDecompressor.create(algorithm = algorithm).use(
            onOutput = { decompressedChunks.add(it) },
        ) { decompress ->
            for (chunk in compressedChunks) {
                decompress(chunk)
            }
        }

        return combineBuffers(decompressedChunks).let { it.readString(it.remaining()) }
    }

    /**
     * Combine multiple buffers into a single buffer.
     */
    private fun combineBuffers(buffers: List<ReadBuffer>): PlatformBuffer {
        if (buffers.isEmpty()) {
            return PlatformBuffer.allocate(0)
        }

        val totalSize = buffers.sumOf { it.remaining() }
        val combined = PlatformBuffer.allocate(totalSize)

        for (buffer in buffers) {
            while (buffer.remaining() > 0) {
                combined.writeByte(buffer.readByte())
            }
        }

        combined.resetForRead()
        return combined
    }

    // =========================================================================
    // Flush tests - Z_SYNC_FLUSH produces independently decompressible blocks
    // =========================================================================

    @Test
    fun flushProducesDecompressibleOutput() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create(algorithm = CompressionAlgorithm.Raw)
        val flushedChunks = mutableListOf<ReadBuffer>()

        try {
            compressor.compress("Hello".toReadBuffer()) {}
            compressor.flush { flushedChunks.add(it) }

            // The flushed output should be immediately decompressible
            val flushed = combineBuffers(flushedChunks)
            assertTrue(flushed.remaining() > 0, "Flush should produce output")

            // Decompress the flushed data
            val decompressedChunks = mutableListOf<ReadBuffer>()
            StreamingDecompressor.create(algorithm = CompressionAlgorithm.Raw).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(flushed)
            }

            val decompressed = combineBuffers(decompressedChunks)
            assertEquals("Hello", decompressed.readString(decompressed.remaining()))
        } finally {
            compressor.close()
        }
    }

    @Test
    fun flushAllowsContinuedCompression() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create(algorithm = CompressionAlgorithm.Raw)
        val allChunks = mutableListOf<ReadBuffer>()

        try {
            // First message
            compressor.compress("Hello".toReadBuffer()) { allChunks.add(it) }
            compressor.flush { allChunks.add(it) }

            // Second message - compressor should still work
            compressor.compress(" World".toReadBuffer()) { allChunks.add(it) }
            compressor.finish { allChunks.add(it) }

            // Decompress all data
            val combined = combineBuffers(allChunks)
            val decompressedChunks = mutableListOf<ReadBuffer>()
            StreamingDecompressor.create(algorithm = CompressionAlgorithm.Raw).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(combined)
            }

            val decompressed = combineBuffers(decompressedChunks)
            assertEquals("Hello World", decompressed.readString(decompressed.remaining()))
        } finally {
            compressor.close()
        }
    }

    @Test
    fun flushRawDeflateEndsWithSyncMarker() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create(algorithm = CompressionAlgorithm.Raw)
        val flushedChunks = mutableListOf<ReadBuffer>()

        try {
            compressor.compress("Test data for sync flush".toReadBuffer()) {}
            compressor.flush { flushedChunks.add(it) }

            val flushed = combineBuffers(flushedChunks)
            val size = flushed.remaining()
            assertTrue(size >= 4, "Flushed output should be at least 4 bytes")

            // Check for sync marker at the end: 00 00 FF FF
            flushed.position(size - 4)
            val b1 = flushed.readByte().toInt() and 0xFF
            val b2 = flushed.readByte().toInt() and 0xFF
            val b3 = flushed.readByte().toInt() and 0xFF
            val b4 = flushed.readByte().toInt() and 0xFF

            assertEquals(0x00, b1, "Sync marker byte 1 should be 0x00")
            assertEquals(0x00, b2, "Sync marker byte 2 should be 0x00")
            assertEquals(0xFF, b3, "Sync marker byte 3 should be 0xFF")
            assertEquals(0xFF, b4, "Sync marker byte 4 should be 0xFF")
        } finally {
            compressor.close()
        }
    }

    @Test
    fun suspendingFlushProducesDecompressibleOutput() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            val compressor = SuspendingStreamingCompressor.create(algorithm = CompressionAlgorithm.Raw)

            try {
                compressor.compress("Hello".toReadBuffer())
                val flushedChunks = compressor.flush()

                assertTrue(flushedChunks.isNotEmpty(), "Flush should produce output")

                // Decompress the flushed data
                val flushed = combineBuffers(flushedChunks)
                val decompressedChunks = mutableListOf<ReadBuffer>()
                StreamingDecompressor.create(algorithm = CompressionAlgorithm.Raw).use(
                    onOutput = { decompressedChunks.add(it) },
                ) { decompress ->
                    decompress(flushed)
                }

                val decompressed = combineBuffers(decompressedChunks)
                assertEquals("Hello", decompressed.readString(decompressed.remaining()))
            } finally {
                compressor.close()
            }
        }

    @Test
    fun suspendingFlushAllowsContinuedCompression() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            val compressor = SuspendingStreamingCompressor.create(algorithm = CompressionAlgorithm.Raw)
            val allChunks = mutableListOf<ReadBuffer>()

            try {
                // First message
                allChunks += compressor.compress("Hello".toReadBuffer())
                allChunks += compressor.flush()

                // Second message - compressor should still work with preserved dictionary
                allChunks += compressor.compress(" World".toReadBuffer())
                allChunks += compressor.finish()

                // Decompress all data
                val combined = combineBuffers(allChunks)
                val decompressedChunks = mutableListOf<ReadBuffer>()
                StreamingDecompressor.create(algorithm = CompressionAlgorithm.Raw).use(
                    onOutput = { decompressedChunks.add(it) },
                ) { decompress ->
                    decompress(combined)
                }

                val decompressed = combineBuffers(decompressedChunks)
                assertEquals("Hello World", decompressed.readString(decompressed.remaining()))
            } finally {
                compressor.close()
            }
        }

    @Test
    fun flushGzipProducesDecompressibleOutput() {
        if (!supportsSyncCompression) return
        // Gzip flush requires stateful compression to produce valid stream after finish
        if (!supportsStatefulFlush) return

        val compressor = StreamingCompressor.create(algorithm = CompressionAlgorithm.Gzip)
        val flushedChunks = mutableListOf<ReadBuffer>()

        try {
            compressor.compress("Hello Gzip".toReadBuffer()) { flushedChunks.add(it) }
            compressor.flush { flushedChunks.add(it) }
            compressor.finish { flushedChunks.add(it) }

            // Decompress the flushed data
            val combined = combineBuffers(flushedChunks)
            assertTrue(combined.remaining() > 0, "Gzip flush should produce output")

            val decompressedChunks = mutableListOf<ReadBuffer>()
            StreamingDecompressor.create(algorithm = CompressionAlgorithm.Gzip).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(combined)
            }

            val decompressed = combineBuffers(decompressedChunks)
            assertEquals("Hello Gzip", decompressed.readString(decompressed.remaining()))
        } finally {
            compressor.close()
        }
    }

    @Test
    fun flushDeflateProducesDecompressibleOutput() {
        if (!supportsSyncCompression) return
        // Deflate flush requires stateful compression to produce valid stream after finish
        if (!supportsStatefulFlush) return

        val compressor = StreamingCompressor.create(algorithm = CompressionAlgorithm.Deflate)
        val flushedChunks = mutableListOf<ReadBuffer>()

        try {
            compressor.compress("Hello Deflate".toReadBuffer()) { flushedChunks.add(it) }
            compressor.flush { flushedChunks.add(it) }
            compressor.finish { flushedChunks.add(it) }

            // Decompress the flushed data
            val combined = combineBuffers(flushedChunks)
            assertTrue(combined.remaining() > 0, "Deflate flush should produce output")

            val decompressedChunks = mutableListOf<ReadBuffer>()
            StreamingDecompressor.create(algorithm = CompressionAlgorithm.Deflate).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(combined)
            }

            val decompressed = combineBuffers(decompressedChunks)
            assertEquals("Hello Deflate", decompressed.readString(decompressed.remaining()))
        } finally {
            compressor.close()
        }
    }

    @Test
    fun flushWithoutPriorCompressProducesOutput() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create(algorithm = CompressionAlgorithm.Raw)
        val flushedChunks = mutableListOf<ReadBuffer>()

        try {
            // Flush without any prior compress calls
            compressor.flush { flushedChunks.add(it) }

            // Should produce at least the sync marker (empty deflate block)
            val flushed = combineBuffers(flushedChunks)
            assertTrue(flushed.remaining() >= 4, "Empty flush should produce at least sync marker")

            // The output should be decompressible (to empty)
            val decompressedChunks = mutableListOf<ReadBuffer>()
            StreamingDecompressor.create(algorithm = CompressionAlgorithm.Raw).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(flushed)
            }

            val decompressed = combineBuffers(decompressedChunks)
            assertEquals(0, decompressed.remaining(), "Empty flush should decompress to empty")
        } finally {
            compressor.close()
        }
    }

    // =========================================================================
    // DeflateFormat utility tests
    // =========================================================================

    @Test
    fun stripSyncFlushMarkerRemovesTrailingMarker() {
        // Create buffer ending with sync marker: 00 00 FF FF
        val data = PlatformBuffer.allocate(10)
        data.writeByte(0x01) // some data
        data.writeByte(0x02)
        data.writeByte(0x03)
        data.writeByte(0x04)
        data.writeByte(0x05)
        data.writeByte(0x06)
        // Sync marker
        data.writeByte(0x00)
        data.writeByte(0x00)
        data.writeByte(0xFF.toByte())
        data.writeByte(0xFF.toByte())
        data.resetForRead()

        assertEquals(10, data.remaining(), "Original size should be 10")

        val stripped = data.stripSyncFlushMarker()
        assertEquals(6, stripped.remaining(), "Stripped size should be 6")

        // Verify the data bytes are still there
        assertEquals(0x01.toByte(), stripped.readByte())
        assertEquals(0x02.toByte(), stripped.readByte())
        assertEquals(0x03.toByte(), stripped.readByte())
        assertEquals(0x04.toByte(), stripped.readByte())
        assertEquals(0x05.toByte(), stripped.readByte())
        assertEquals(0x06.toByte(), stripped.readByte())
    }

    @Test
    fun stripSyncFlushMarkerLeavesDataWithoutMarker() {
        // Create buffer NOT ending with sync marker
        val data = PlatformBuffer.allocate(6)
        data.writeByte(0x01)
        data.writeByte(0x02)
        data.writeByte(0x03)
        data.writeByte(0x04)
        data.writeByte(0x05)
        data.writeByte(0x06)
        data.resetForRead()

        val result = data.stripSyncFlushMarker()
        assertEquals(6, result.remaining(), "Size should remain unchanged")
    }

    @Test
    fun stripSyncFlushMarkerHandlesSmallBuffers() {
        // Buffer too small to contain marker
        val data = PlatformBuffer.allocate(2)
        data.writeByte(0x01)
        data.writeByte(0x02)
        data.resetForRead()

        val result = data.stripSyncFlushMarker()
        assertEquals(2, result.remaining(), "Small buffer should remain unchanged")
    }

    @Test
    fun appendSyncFlushMarkerAddsTrailingMarker() {
        val data = PlatformBuffer.allocate(3)
        data.writeByte(0x01)
        data.writeByte(0x02)
        data.writeByte(0x03)
        data.resetForRead()

        val withMarker = data.appendSyncFlushMarker()
        assertEquals(7, withMarker.remaining(), "Size should be original + 4")

        // Read original data
        assertEquals(0x01.toByte(), withMarker.readByte())
        assertEquals(0x02.toByte(), withMarker.readByte())
        assertEquals(0x03.toByte(), withMarker.readByte())

        // Read sync marker
        assertEquals(0x00.toByte(), withMarker.readByte())
        assertEquals(0x00.toByte(), withMarker.readByte())
        assertEquals(0xFF.toByte(), withMarker.readByte())
        assertEquals(0xFF.toByte(), withMarker.readByte())
    }

    @Test
    fun compressWithSyncFlushProducesStrippedOutput() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            val text = "Hello, compressed world!"
            val compressed = compressWithSyncFlush(text.toReadBuffer())

            // Verify marker is NOT present at the end
            val size = compressed.remaining()
            assertTrue(size >= 4, "Compressed data should be at least 4 bytes")

            compressed.position(size - 4)
            val lastFourBytes = compressed.getInt(size - 4)
            assertTrue(
                lastFourBytes != DeflateFormat.SYNC_FLUSH_MARKER,
                "Sync marker should be stripped",
            )

            // Verify it can be decompressed
            compressed.position(0)
            val decompressed = decompressWithSyncFlush(compressed)
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }

    @Test
    fun compressAndDecompressWithSyncFlushRoundTrip() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            val texts =
                listOf(
                    "Short",
                    "A medium length string for testing compression.",
                    "A longer string with repetition. ".repeat(50),
                )

            for (text in texts) {
                val compressed = compressWithSyncFlush(text.toReadBuffer())
                val decompressed = decompressWithSyncFlush(compressed)
                assertEquals(
                    text,
                    decompressed.readString(decompressed.remaining()),
                    "Round-trip should preserve data",
                )
            }
        }

    @Test
    fun deflateFormatSyncMarkerConstantIsCorrect() {
        // Verify the constant value matches the expected bytes: 00 00 FF FF
        assertEquals(0x0000FFFF, DeflateFormat.SYNC_FLUSH_MARKER)
    }
}

// Note: StreamProcessorIntegrationTests are in a separate file to avoid
// confusion with the streaming compression tests above.
