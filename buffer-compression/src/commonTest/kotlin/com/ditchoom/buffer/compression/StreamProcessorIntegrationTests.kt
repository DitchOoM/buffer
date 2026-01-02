package com.ditchoom.buffer.compression

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.pool.withPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for StreamProcessor integration with compression.
 * These tests verify that compression can be used via the StreamProcessor builder API.
 */
class StreamProcessorIntegrationTests {
    @Test
    fun decompressWithStreamProcessorBuilder() {
        if (!supportsSyncCompression) return

        val text = "Hello, StreamProcessor!"

        // Compress and validate
        val compressedResult = compress(text.toReadBuffer(), CompressionAlgorithm.Gzip)
        assertTrue(compressedResult is CompressionResult.Success)
        val compressed = compressedResult.buffer
        validateGzipHeader(compressed)
        compressed.position(0)

        // Decompress using StreamProcessor (auto-finishes on read)
        withPool { pool ->
            val processor =
                StreamProcessor
                    .builder(pool)
                    .decompress(CompressionAlgorithm.Gzip)
                    .build()

            try {
                processor.append(compressed)
                processor.finish() // Signal no more data
                val decompressed = processor.readBuffer(processor.available())
                assertEquals(text, decompressed.readString(decompressed.remaining()))
            } finally {
                processor.release()
            }
        }
    }

    @Test
    fun decompressMultipleChunks() {
        if (!supportsSyncCompression) return

        val text = "Hello, World! ".repeat(100)

        val compressedResult = compress(text.toReadBuffer(), CompressionAlgorithm.Gzip)
        assertTrue(compressedResult is CompressionResult.Success)
        val compressed = compressedResult.buffer
        validateGzipHeader(compressed)
        compressed.position(0)

        val chunks = splitIntoChunks(compressed, chunkSize = 32)

        withPool { pool ->
            val processor =
                StreamProcessor
                    .builder(pool)
                    .decompress(CompressionAlgorithm.Gzip)
                    .build()

            try {
                for (chunk in chunks) {
                    processor.append(chunk)
                }
                processor.finish() // Signal no more data
                val decompressed = processor.readBuffer(processor.available())
                assertEquals(text, decompressed.readString(decompressed.remaining()))
            } finally {
                processor.release()
            }
        }
    }

    @Test
    fun decompressSuspendingStreamProcessor() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            val text = "Hello, Suspending StreamProcessor!"

            val compressedResult = compress(text.toReadBuffer(), CompressionAlgorithm.Gzip)
            assertTrue(compressedResult is CompressionResult.Success)
            val compressed = compressedResult.buffer
            validateGzipHeader(compressed)
            compressed.position(0)

            withPool { pool ->
                val processor =
                    StreamProcessor
                        .builder(pool)
                        .decompress(CompressionAlgorithm.Gzip)
                        .buildSuspending()

                try {
                    processor.append(compressed)
                    processor.finish() // Signal no more data
                    val decompressed = processor.readBuffer(processor.available())
                    assertEquals(text, decompressed.readString(decompressed.remaining()))
                } finally {
                    processor.release()
                }
            }
        }

    @Test
    fun decompressWithDeflateAlgorithm() {
        if (!supportsSyncCompression) return

        val text = "Testing Deflate with StreamProcessor"

        val compressedResult = compress(text.toReadBuffer(), CompressionAlgorithm.Deflate)
        assertTrue(compressedResult is CompressionResult.Success)
        val compressed = compressedResult.buffer
        validateDeflateHeader(compressed)
        compressed.position(0)

        withPool { pool ->
            val processor =
                StreamProcessor
                    .builder(pool)
                    .decompress(CompressionAlgorithm.Deflate)
                    .build()

            try {
                processor.append(compressed)
                processor.finish() // Signal no more data
                val decompressed = processor.readBuffer(processor.available())
                assertEquals(text, decompressed.readString(decompressed.remaining()))
            } finally {
                processor.release()
            }
        }
    }

    @Test
    fun decompressWithRawAlgorithm() {
        if (!supportsSyncCompression) return

        val text = "Testing Raw with StreamProcessor"

        val compressedResult = compress(text.toReadBuffer(), CompressionAlgorithm.Raw)
        assertTrue(compressedResult is CompressionResult.Success)
        val compressed = compressedResult.buffer

        withPool { pool ->
            val processor =
                StreamProcessor
                    .builder(pool)
                    .decompress(CompressionAlgorithm.Raw)
                    .build()

            try {
                processor.append(compressed)
                processor.finish() // Signal no more data
                val decompressed = processor.readBuffer(processor.available())
                assertEquals(text, decompressed.readString(decompressed.remaining()))
            } finally {
                processor.release()
            }
        }
    }

    @Test
    fun decompressLargeData() {
        if (!supportsSyncCompression) return

        val text = "Large data chunk for StreamProcessor test. ".repeat(1000)

        val compressedResult = compress(text.toReadBuffer(), CompressionAlgorithm.Gzip)
        assertTrue(compressedResult is CompressionResult.Success)
        val compressed = compressedResult.buffer
        assertTrue(compressed.remaining() < text.length, "Compression should reduce size")
        validateGzipHeader(compressed)
        compressed.position(0)

        withPool { pool ->
            val processor =
                StreamProcessor
                    .builder(pool)
                    .decompress(CompressionAlgorithm.Gzip)
                    .build()

            try {
                processor.append(compressed)
                processor.finish() // Signal no more data
                val decompressed = processor.readBuffer(processor.available())
                assertEquals(text, decompressed.readString(decompressed.remaining()))
            } finally {
                processor.release()
            }
        }
    }

    @Test
    fun peekDecompressedData() {
        if (!supportsSyncCompression) return

        val buffer = PlatformBuffer.allocate(12)
        buffer.writeInt(0x12345678)
        buffer.writeInt(0xDEADBEEF.toInt())
        buffer.writeInt(0xCAFEBABE.toInt())
        buffer.resetForRead()

        val compressedResult = compress(buffer, CompressionAlgorithm.Gzip)
        assertTrue(compressedResult is CompressionResult.Success)
        val compressed = compressedResult.buffer
        validateGzipHeader(compressed)
        compressed.position(0)

        withPool { pool ->
            val processor =
                StreamProcessor
                    .builder(pool)
                    .decompress(CompressionAlgorithm.Gzip)
                    .build()

            try {
                processor.append(compressed)
                processor.finish() // Signal no more data

                assertEquals(0x12345678, processor.peekInt())
                assertEquals(0x12345678, processor.readInt())

                assertEquals(0xDEADBEEF.toInt(), processor.peekInt())
                assertEquals(0xDEADBEEF.toInt(), processor.readInt())

                assertEquals(0xCAFEBABE.toInt(), processor.readInt())
                assertEquals(0, processor.available())
            } finally {
                processor.release()
            }
        }
    }

    @Test
    fun validateCompressedDataMatchesExpected() {
        if (!supportsSyncCompression) return

        val text = "Hello"

        // Expected gzip bytes for "Hello" (deterministic output)
        val expectedGzip =
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

        val compressedResult = compress(text.toReadBuffer(), CompressionAlgorithm.Gzip)
        assertTrue(compressedResult is CompressionResult.Success)
        val compressed = compressedResult.buffer

        // Validate byte-by-byte
        validateBufferMatchesExpected(compressed, expectedGzip)
        compressed.position(0)

        // Verify round-trip
        withPool { pool ->
            val processor =
                StreamProcessor
                    .builder(pool)
                    .decompress(CompressionAlgorithm.Gzip)
                    .build()

            try {
                processor.append(compressed)
                processor.finish() // Signal no more data
                val decompressed = processor.readBuffer(processor.available())
                assertEquals(text, decompressed.readString(decompressed.remaining()))
            } finally {
                processor.release()
            }
        }
    }

    // =========================================================================
    // Documentation example tests - validate docs compile and work
    // =========================================================================

    /**
     * Tests the pattern from docs: sync StreamProcessor with suspending I/O.
     * The suspend happens on read(), then append() is synchronous.
     */
    @Test
    fun syncProcessorWithSuspendingIO() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            val text = "Data from suspending socket read"

            val compressedResult = compress(text.toReadBuffer(), CompressionAlgorithm.Gzip)
            assertTrue(compressedResult is CompressionResult.Success)
            val compressed = compressedResult.buffer
            compressed.position(0)

            // Simulate a socket that returns data in chunks via suspend calls
            val socket = MockSuspendingSocket(splitIntoChunks(compressed, chunkSize = 16))

            withPool { pool ->
                val processor =
                    StreamProcessor
                        .builder(pool)
                        .decompress(CompressionAlgorithm.Gzip)
                        .build()

                try {
                    // Pattern from docs: suspend on socket.read(), then sync append
                    while (socket.hasData()) {
                        val chunk = socket.read() // suspend call
                        processor.append(chunk) // sync call
                    }
                    processor.finish()

                    val decompressed = processor.readBuffer(processor.available())
                    assertEquals(text, decompressed.readString(decompressed.remaining()))
                } finally {
                    processor.release()
                }
            }
        }

    /**
     * Tests the SuspendingStreamProcessor pattern from docs.
     * Both read() and append() are suspend functions.
     */
    @Test
    fun suspendingProcessorWithSuspendingIO() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            val text = "Data for suspending processor with suspending IO"

            val compressedResult = compress(text.toReadBuffer(), CompressionAlgorithm.Gzip)
            assertTrue(compressedResult is CompressionResult.Success)
            val compressed = compressedResult.buffer
            compressed.position(0)

            val socket = MockSuspendingSocket(splitIntoChunks(compressed, chunkSize = 16))

            withPool { pool ->
                val processor =
                    StreamProcessor
                        .builder(pool)
                        .decompress(CompressionAlgorithm.Gzip)
                        .buildSuspending() // Returns SuspendingStreamProcessor

                try {
                    while (socket.hasData()) {
                        val chunk = socket.read() // suspend
                        processor.append(chunk) // also suspend
                    }
                    processor.finish()

                    val decompressed = processor.readBuffer(processor.available())
                    assertEquals(text, decompressed.readString(decompressed.remaining()))
                } finally {
                    processor.release()
                }
            }
        }

    /**
     * Tests processing multiple messages in a stream.
     * Common pattern: length-prefixed protocol over compressed transport.
     */
    @Test
    fun parseMultipleMessagesFromCompressedStream() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            // Create a buffer with 3 length-prefixed messages
            val messages = listOf("First message", "Second message", "Third!")
            val uncompressedBuffer = PlatformBuffer.allocate(1024)
            for (msg in messages) {
                val msgBytes = msg.encodeToByteArray()
                uncompressedBuffer.writeInt(msgBytes.size)
                uncompressedBuffer.writeBytes(msgBytes)
            }
            uncompressedBuffer.resetForRead()

            // Compress the entire buffer
            val compressedResult = compress(uncompressedBuffer, CompressionAlgorithm.Gzip)
            assertTrue(compressedResult is CompressionResult.Success)
            val compressed = compressedResult.buffer
            compressed.position(0)

            // Simulate receiving in chunks
            val socket = MockSuspendingSocket(splitIntoChunks(compressed, chunkSize = 20))

            withPool { pool ->
                val processor =
                    StreamProcessor
                        .builder(pool)
                        .decompress(CompressionAlgorithm.Gzip)
                        .build()

                try {
                    // Read all data from socket
                    while (socket.hasData()) {
                        processor.append(socket.read())
                    }
                    processor.finish()

                    // Parse length-prefixed messages (pattern from docs)
                    val parsed = mutableListOf<String>()
                    while (processor.available() >= 4) {
                        val length = processor.peekInt()
                        if (processor.available() >= 4 + length) {
                            processor.skip(4) // Skip length header
                            val payload = processor.readBuffer(length)
                            parsed.add(payload.readString(payload.remaining()))
                        } else {
                            break
                        }
                    }

                    assertEquals(messages, parsed)
                } finally {
                    processor.release()
                }
            }
        }

    /**
     * Tests interleaved reading and appending with SuspendingStreamProcessor.
     * This pattern reads messages as they become available while still receiving data,
     * which is common in real-time streaming scenarios.
     */
    @Test
    fun interleavedReadAndAppendWithSuspendingProcessor() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            // Create multiple length-prefixed messages
            val messages =
                listOf(
                    "Message one",
                    "Message two is a bit longer",
                    "Three",
                    "Fourth message here",
                    "Fifth and final message",
                )
            val uncompressedBuffer = PlatformBuffer.allocate(2048)
            for (msg in messages) {
                val msgBytes = msg.encodeToByteArray()
                uncompressedBuffer.writeInt(msgBytes.size)
                uncompressedBuffer.writeBytes(msgBytes)
            }
            uncompressedBuffer.resetForRead()

            // Compress the entire buffer
            val compressedResult = compress(uncompressedBuffer, CompressionAlgorithm.Gzip)
            assertTrue(compressedResult is CompressionResult.Success)
            val compressed = compressedResult.buffer
            compressed.position(0)

            // Use small chunks to simulate slow network - messages span multiple chunks
            val socket = MockSuspendingSocket(splitIntoChunks(compressed, chunkSize = 8))

            withPool { pool ->
                val processor =
                    StreamProcessor
                        .builder(pool)
                        .decompress(CompressionAlgorithm.Gzip)
                        .buildSuspending()

                try {
                    val parsed = mutableListOf<String>()

                    // Interleaved pattern: read messages as they become available
                    // while still receiving data from the socket
                    while (socket.hasData() || processor.available() >= 4) {
                        // Try to read complete messages from what we have
                        while (processor.available() >= 4) {
                            val length = processor.peekInt()
                            if (processor.available() >= 4 + length) {
                                processor.skip(4)
                                val payload = processor.readBuffer(length) // suspend
                                parsed.add(payload.readString(payload.remaining()))
                            } else {
                                break // Need more data for this message
                            }
                        }

                        // Get more data if available
                        if (socket.hasData()) {
                            processor.append(socket.read()) // suspend
                        }
                    }
                    processor.finish()

                    // Read any remaining messages after finish
                    while (processor.available() >= 4) {
                        val length = processor.peekInt()
                        if (processor.available() >= 4 + length) {
                            processor.skip(4)
                            val payload = processor.readBuffer(length)
                            parsed.add(payload.readString(payload.remaining()))
                        } else {
                            break
                        }
                    }

                    assertEquals(messages, parsed)
                } finally {
                    processor.release()
                }
            }
        }

    // =========================================================================
    // Helper functions
    // =========================================================================

    /**
     * Mock socket that simulates suspending reads returning chunks of data.
     */
    private class MockSuspendingSocket(
        chunks: List<ReadBuffer>,
    ) {
        private val chunks = chunks.toMutableList()

        fun hasData(): Boolean = chunks.isNotEmpty()

        suspend fun read(): ReadBuffer {
            // Simulate suspend (in real code this would be actual I/O)
            kotlinx.coroutines.yield()
            return chunks.removeAt(0)
        }
    }

    private fun validateBufferMatchesExpected(
        buffer: ReadBuffer,
        expected: IntArray,
    ) {
        val savedPosition = buffer.position()
        assertEquals(expected.size, buffer.remaining(), "Buffer size mismatch")

        for (i in expected.indices) {
            val actual = buffer.readByte().toInt() and 0xFF
            val expectedByte = expected[i]
            // Skip OS byte at index 9 as it may vary
            if (i != 9) {
                assertEquals(
                    expectedByte,
                    actual,
                    "Byte $i mismatch: expected 0x${expectedByte.toString(16)}, got 0x${actual.toString(16)}",
                )
            }
        }

        buffer.position(savedPosition)
    }

    private fun validateGzipHeader(buffer: ReadBuffer) {
        val savedPosition = buffer.position()
        assertEquals(0x1f, buffer.readByte().toInt() and 0xFF, "Gzip magic byte 1")
        assertEquals(0x8b, buffer.readByte().toInt() and 0xFF, "Gzip magic byte 2")
        assertEquals(0x08, buffer.readByte().toInt() and 0xFF, "Gzip compression method")
        buffer.position(savedPosition)
    }

    private fun validateDeflateHeader(buffer: ReadBuffer) {
        val savedPosition = buffer.position()
        val cmf = buffer.readByte().toInt() and 0xFF
        assertEquals(0x78, cmf, "Deflate CMF byte (default compression)")
        buffer.position(savedPosition)
    }

    private fun splitIntoChunks(
        buffer: ReadBuffer,
        chunkSize: Int,
    ): List<PlatformBuffer> {
        val chunks = mutableListOf<PlatformBuffer>()
        while (buffer.remaining() > 0) {
            val size = minOf(chunkSize, buffer.remaining())
            val chunk = PlatformBuffer.allocate(size)
            repeat(size) { chunk.writeByte(buffer.readByte()) }
            chunk.resetForRead()
            chunks.add(chunk)
        }
        return chunks
    }
}
