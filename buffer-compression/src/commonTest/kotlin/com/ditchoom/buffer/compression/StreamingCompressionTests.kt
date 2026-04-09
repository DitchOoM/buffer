package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed
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
            val compressed1 = BufferFactory.managed().allocate(1024)
            compressor.compressScoped(text1.toReadBuffer()) { compressed1.write(this) }
            compressor.finishScoped { compressed1.write(this) }
            compressed1.resetForRead()

            // Reset and compress again
            compressor.reset()

            val compressed2 = BufferFactory.managed().allocate(1024)
            compressor.compressScoped(text2.toReadBuffer()) { compressed2.write(this) }
            compressor.finishScoped { compressed2.write(this) }
            compressed2.resetForRead()

            // Verify both compressions work
            assertTrue(compressed1.remaining() > 0)
            assertTrue(compressed2.remaining() > 0)

            // Verify decompression of both using streaming
            assertEquals(text1, streamDecompress(listOf(compressed1)))
            assertEquals(text2, streamDecompress(listOf(compressed2)))
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
                val chunk = BufferFactory.Default.allocate(bytesToRead)
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
            return BufferFactory.Default.allocate(0)
        }

        val totalSize = buffers.sumOf { it.remaining() }
        val combined = BufferFactory.Default.allocate(totalSize)

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
        val flushedOutput = BufferFactory.managed().allocate(1024)

        try {
            compressor.compressScoped("Hello".toReadBuffer()) {}
            compressor.flushScoped { flushedOutput.write(this) }
            flushedOutput.resetForRead()

            // The flushed output should be immediately decompressible
            assertTrue(flushedOutput.remaining() > 0, "Flush should produce output")

            // Decompress the flushed data
            val decompressedChunks = mutableListOf<ReadBuffer>()
            StreamingDecompressor.create(algorithm = CompressionAlgorithm.Raw).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(flushedOutput)
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
        val allOutput = BufferFactory.managed().allocate(4096)

        try {
            // First message
            compressor.compressScoped("Hello".toReadBuffer()) { allOutput.write(this) }
            compressor.flushScoped { allOutput.write(this) }

            // Second message - compressor should still work
            compressor.compressScoped(" World".toReadBuffer()) { allOutput.write(this) }
            compressor.finishScoped { allOutput.write(this) }
            allOutput.resetForRead()

            // Decompress all data
            val decompressedChunks = mutableListOf<ReadBuffer>()
            StreamingDecompressor.create(algorithm = CompressionAlgorithm.Raw).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(allOutput)
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
        val flushedOutput = BufferFactory.managed().allocate(4096)

        try {
            compressor.compressScoped("Test data for sync flush".toReadBuffer()) {}
            compressor.flushScoped { flushedOutput.write(this) }
            flushedOutput.resetForRead()

            val size = flushedOutput.remaining()
            assertTrue(size >= 4, "Flushed output should be at least 4 bytes")

            // Check for sync marker at the end: 00 00 FF FF
            flushedOutput.position(size - 4)
            val b1 = flushedOutput.readByte().toInt() and 0xFF
            val b2 = flushedOutput.readByte().toInt() and 0xFF
            val b3 = flushedOutput.readByte().toInt() and 0xFF
            val b4 = flushedOutput.readByte().toInt() and 0xFF

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
                val flushedOutput = BufferFactory.managed().allocate(1024)
                compressor.compressScoped("Hello".toReadBuffer()) {}
                compressor.flushScoped { flushedOutput.write(this) }
                flushedOutput.resetForRead()

                assertTrue(flushedOutput.remaining() > 0, "Flush should produce output")

                // Decompress the flushed data
                val decompressedChunks = mutableListOf<ReadBuffer>()
                StreamingDecompressor.create(algorithm = CompressionAlgorithm.Raw).use(
                    onOutput = { decompressedChunks.add(it) },
                ) { decompress ->
                    decompress(flushedOutput)
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
            val allOutput = BufferFactory.managed().allocate(4096)

            try {
                // First message
                compressor.compressScoped("Hello".toReadBuffer()) { allOutput.write(this) }
                compressor.flushScoped { allOutput.write(this) }

                // Second message - compressor should still work with preserved dictionary
                compressor.compressScoped(" World".toReadBuffer()) { allOutput.write(this) }
                compressor.finishScoped { allOutput.write(this) }
                allOutput.resetForRead()

                // Decompress all data
                val decompressedChunks = mutableListOf<ReadBuffer>()
                StreamingDecompressor.create(algorithm = CompressionAlgorithm.Raw).use(
                    onOutput = { decompressedChunks.add(it) },
                ) { decompress ->
                    decompress(allOutput)
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
        val output = BufferFactory.managed().allocate(4096)

        try {
            compressor.compressScoped("Hello Gzip".toReadBuffer()) { output.write(this) }
            compressor.flushScoped { output.write(this) }
            compressor.finishScoped { output.write(this) }
            output.resetForRead()

            // Decompress the flushed data
            assertTrue(output.remaining() > 0, "Gzip flush should produce output")

            val decompressedChunks = mutableListOf<ReadBuffer>()
            StreamingDecompressor.create(algorithm = CompressionAlgorithm.Gzip).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(output)
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
        val output = BufferFactory.managed().allocate(4096)

        try {
            compressor.compressScoped("Hello Deflate".toReadBuffer()) { output.write(this) }
            compressor.flushScoped { output.write(this) }
            compressor.finishScoped { output.write(this) }
            output.resetForRead()

            // Decompress the flushed data
            assertTrue(output.remaining() > 0, "Deflate flush should produce output")

            val decompressedChunks = mutableListOf<ReadBuffer>()
            StreamingDecompressor.create(algorithm = CompressionAlgorithm.Deflate).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(output)
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
        val flushedOutput = BufferFactory.managed().allocate(1024)

        try {
            // Flush without any prior compress calls
            compressor.flushScoped { flushedOutput.write(this) }
            flushedOutput.resetForRead()

            // Should produce at least the sync marker (empty deflate block)
            assertTrue(flushedOutput.remaining() >= 4, "Empty flush should produce at least sync marker")

            // The output should be decompressible (to empty)
            val decompressedChunks = mutableListOf<ReadBuffer>()
            StreamingDecompressor.create(algorithm = CompressionAlgorithm.Raw).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(flushedOutput)
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
        val data = BufferFactory.Default.allocate(10)
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
        val data = BufferFactory.Default.allocate(6)
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
        val data = BufferFactory.Default.allocate(2)
        data.writeByte(0x01)
        data.writeByte(0x02)
        data.resetForRead()

        val result = data.stripSyncFlushMarker()
        assertEquals(2, result.remaining(), "Small buffer should remain unchanged")
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

    // =========================================================================
    // Buffer position/limit state tests for streaming scoped APIs
    // =========================================================================

    @Test
    fun compressScopedConsumesInputBuffer() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create()
        try {
            val input = "Hello, streaming!".toReadBuffer()
            val initialRemaining = input.remaining()
            assertTrue(initialRemaining > 0)

            compressor.compressScoped(input) {}

            assertEquals(0, input.remaining(), "compressScoped should fully consume input")
        } finally {
            compressor.close()
        }
    }

    @Test
    fun compressScopedOutputChunkReadyForReading() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create()
        try {
            // Use enough data to force output on compress (not just buffered)
            val input = "Repeating data for output. ".repeat(200).toReadBuffer()
            var chunkCount = 0

            compressor.compressScoped(input) {
                chunkCount++
                assertEquals(0, position(), "Output chunk position should be 0")
                assertTrue(remaining() > 0, "Output chunk should have data")
            }

            compressor.finishScoped {
                chunkCount++
                assertEquals(0, position(), "Finish chunk position should be 0")
                assertTrue(remaining() > 0, "Finish chunk should have data")
            }

            assertTrue(chunkCount > 0, "Should have produced at least one output chunk")
        } finally {
            compressor.close()
        }
    }

    @Test
    fun decompressScopedConsumesInputBuffer() {
        if (!supportsSyncCompression) return

        // First compress some data
        val text = "Data for decompression input test"
        val compressed = BufferFactory.managed().allocate(4096)
        val compressor = StreamingCompressor.create()
        try {
            compressor.compressScoped(text.toReadBuffer()) { compressed.write(this) }
            compressor.finishScoped { compressed.write(this) }
        } finally {
            compressor.close()
        }
        compressed.resetForRead()
        val initialRemaining = compressed.remaining()
        assertTrue(initialRemaining > 0)

        // Now decompress and check input consumption
        val decompressor = StreamingDecompressor.create()
        try {
            decompressor.decompressScoped(compressed) {}

            assertEquals(0, compressed.remaining(), "decompressScoped should fully consume input")
        } finally {
            decompressor.close()
        }
    }

    @Test
    fun decompressScopedOutputChunkReadyForReading() {
        if (!supportsSyncCompression) return

        val text = "Output chunk position test for decompression"
        val compressed = BufferFactory.managed().allocate(4096)
        val compressor = StreamingCompressor.create()
        try {
            compressor.compressScoped(text.toReadBuffer()) { compressed.write(this) }
            compressor.finishScoped { compressed.write(this) }
        } finally {
            compressor.close()
        }
        compressed.resetForRead()

        val decompressor = StreamingDecompressor.create()
        try {
            var chunkCount = 0

            decompressor.decompressScoped(compressed) {
                chunkCount++
                assertEquals(0, position(), "Decompressed chunk position should be 0")
                assertTrue(remaining() > 0, "Decompressed chunk should have data")
            }

            decompressor.finishScoped {
                chunkCount++
                assertEquals(0, position(), "Finish chunk position should be 0")
            }

            assertTrue(chunkCount > 0, "Should have produced at least one output chunk")
        } finally {
            decompressor.close()
        }
    }

    @Test
    fun finishScopedOutputChunkReadyForReading() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create()
        try {
            // Small input — compress may buffer everything, finish produces the output
            compressor.compressScoped("Small".toReadBuffer()) {}

            var finishChunkCount = 0
            compressor.finishScoped {
                finishChunkCount++
                assertEquals(0, position(), "Finish output chunk position should be 0")
                assertTrue(remaining() > 0, "Finish output chunk should have data")
            }

            assertTrue(finishChunkCount > 0, "finishScoped should produce output for buffered data")
        } finally {
            compressor.close()
        }
    }

    @Test
    fun flushScopedOutputChunkReadyForReading() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create(algorithm = CompressionAlgorithm.Raw)
        try {
            compressor.compressScoped("Flush test data".toReadBuffer()) {}

            var flushChunkCount = 0
            compressor.flushScoped {
                flushChunkCount++
                assertEquals(0, position(), "Flush output chunk position should be 0")
                assertTrue(remaining() > 0, "Flush output chunk should have data")
            }

            assertTrue(flushChunkCount > 0, "flushScoped should produce output")
        } finally {
            compressor.close()
        }
    }

    @Test
    fun multipleCompressScopedCallsConsumeAllInputs() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create()
        try {
            val input1 = "First chunk".toReadBuffer()
            val input2 = "Second chunk".toReadBuffer()
            val input3 = "Third chunk".toReadBuffer()

            compressor.compressScoped(input1) {}
            assertEquals(0, input1.remaining(), "First input should be fully consumed")

            compressor.compressScoped(input2) {}
            assertEquals(0, input2.remaining(), "Second input should be fully consumed")

            compressor.compressScoped(input3) {}
            assertEquals(0, input3.remaining(), "Third input should be fully consumed")
        } finally {
            compressor.close()
        }
    }

    @Test
    fun streamingRoundTripBufferStateConsistency() {
        if (!supportsSyncCompression) return

        val text = "Full round-trip buffer state verification"
        val compressOutput = BufferFactory.managed().allocate(4096)
        val decompressOutput = BufferFactory.managed().allocate(4096)

        // Compress
        val compressor = StreamingCompressor.create()
        val input = text.toReadBuffer()
        try {
            compressor.compressScoped(input) {
                assertEquals(0, position(), "Compress output chunk: position should be 0")
                compressOutput.write(this)
            }
            compressor.finishScoped {
                assertEquals(0, position(), "Compress finish chunk: position should be 0")
                compressOutput.write(this)
            }
        } finally {
            compressor.close()
        }
        assertEquals(0, input.remaining(), "Input fully consumed after compress")
        compressOutput.resetForRead()
        assertEquals(0, compressOutput.position(), "Compressed buffer ready for reading at position 0")
        assertTrue(compressOutput.remaining() > 0, "Compressed buffer should have data")

        // Decompress
        val decompressor = StreamingDecompressor.create()
        try {
            decompressor.decompressScoped(compressOutput) {
                assertEquals(0, position(), "Decompress output chunk: position should be 0")
                decompressOutput.write(this)
            }
            decompressor.finishScoped {
                if (remaining() > 0) {
                    assertEquals(0, position(), "Decompress finish chunk: position should be 0")
                    decompressOutput.write(this)
                }
            }
        } finally {
            decompressor.close()
        }
        assertEquals(0, compressOutput.remaining(), "Compressed input fully consumed after decompress")
        decompressOutput.resetForRead()
        assertEquals(0, decompressOutput.position(), "Decompressed buffer ready for reading at position 0")
        assertEquals(text.length, decompressOutput.remaining(), "Decompressed size matches original")
        assertEquals(text, decompressOutput.readString(decompressOutput.remaining()))
    }

    // =========================================================================
    // Empty buffer (remaining == 0) consistency tests
    // =========================================================================

    @Test
    fun compressScopedWithEmptyBufferNoCallback() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create()
        try {
            val empty = BufferFactory.Default.allocate(0)
            empty.resetForRead()
            assertEquals(0, empty.remaining())

            var callbackInvoked = false
            compressor.compressScoped(empty) { callbackInvoked = true }

            assertEquals(false, callbackInvoked, "Callback should not be invoked for empty input")
            assertEquals(0, empty.remaining(), "Empty buffer should still have 0 remaining")
        } finally {
            compressor.close()
        }
    }

    @Test
    fun decompressScopedWithEmptyBufferNoCallback() {
        if (!supportsSyncCompression) return

        val decompressor = StreamingDecompressor.create()
        try {
            val empty = BufferFactory.Default.allocate(0)
            empty.resetForRead()
            assertEquals(0, empty.remaining())

            var callbackInvoked = false
            decompressor.decompressScoped(empty) { callbackInvoked = true }

            assertEquals(false, callbackInvoked, "Callback should not be invoked for empty input")
            assertEquals(0, empty.remaining(), "Empty buffer should still have 0 remaining")
        } finally {
            decompressor.close()
        }
    }

    @Test
    fun compressScopedWithConsumedBufferNoCallback() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create()
        try {
            // Create a buffer, write data, read it all back so remaining == 0
            val buf = BufferFactory.Default.allocate(10)
            buf.writeString("hello")
            buf.resetForRead()
            // Consume all bytes
            repeat(5) { buf.readByte() }
            assertEquals(0, buf.remaining())

            var callbackInvoked = false
            compressor.compressScoped(buf) { callbackInvoked = true }

            assertEquals(false, callbackInvoked, "Callback should not be invoked for consumed buffer")
        } finally {
            compressor.close()
        }
    }

    @Test
    fun emptyCompressThenNonEmptyStillWorks() {
        if (!supportsSyncCompression) return

        val compressor = StreamingCompressor.create()
        try {
            val empty = BufferFactory.Default.allocate(0)
            empty.resetForRead()

            // Empty compress — should be a no-op
            compressor.compressScoped(empty) {}

            // Non-empty compress — should still work
            val input = "Hello after empty".toReadBuffer()
            compressor.compressScoped(input) {}
            assertEquals(0, input.remaining(), "Input should be consumed after non-empty compress")

            // Finish and verify we get valid output
            val output = BufferFactory.managed().allocate(4096)
            compressor.finishScoped { output.write(this) }
            output.resetForRead()
            assertTrue(output.remaining() > 0, "Should produce compressed output")

            // Step 1: verify one-shot decompress works (validates the compressed data)
            val oneShotResult = decompress(output)
            assertTrue(oneShotResult is CompressionResult.Success, "One-shot decompress should work")
            assertEquals("Hello after empty", oneShotResult.buffer.readString(oneShotResult.buffer.remaining()))

            // Step 2: verify streaming decompress works
            output.position(0)
            val decompressedChunks = mutableListOf<ReadBuffer>()
            val decompressor = StreamingDecompressor.create()
            try {
                decompressor.decompressScoped(output) { decompressedChunks.add(this) }
                assertEquals(0, output.remaining(), "Compressed input should be fully consumed")
                decompressor.finishScoped {
                    if (remaining() > 0) decompressedChunks.add(this)
                }
            } finally {
                decompressor.close()
            }
            val decompressed = combineBuffers(decompressedChunks)
            assertEquals("Hello after empty", decompressed.readString(decompressed.remaining()))
        } finally {
            compressor.close()
        }
    }

    @Test
    fun emptyBufferWithAllAlgorithmsNoCallback() {
        if (!supportsSyncCompression) return

        val algorithms = listOf(CompressionAlgorithm.Deflate, CompressionAlgorithm.Gzip, CompressionAlgorithm.Raw)

        for (algorithm in algorithms) {
            val compressor = StreamingCompressor.create(algorithm = algorithm)
            try {
                val empty = BufferFactory.Default.allocate(0)
                empty.resetForRead()

                var callbackInvoked = false
                compressor.compressScoped(empty) { callbackInvoked = true }

                assertEquals(
                    false,
                    callbackInvoked,
                    "Callback should not be invoked for empty input with $algorithm",
                )
            } finally {
                compressor.close()
            }

            val decompressor = StreamingDecompressor.create(algorithm = algorithm)
            try {
                val empty = BufferFactory.Default.allocate(0)
                empty.resetForRead()

                var callbackInvoked = false
                decompressor.decompressScoped(empty) { callbackInvoked = true }

                assertEquals(
                    false,
                    callbackInvoked,
                    "Callback should not be invoked for empty decompress input with $algorithm",
                )
            } finally {
                decompressor.close()
            }
        }
    }
}

// Note: StreamProcessorIntegrationTests are in a separate file to avoid
// confusion with the streaming compression tests above.
