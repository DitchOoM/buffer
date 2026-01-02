package com.ditchoom.buffer.compression

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompressionTests {
    // =========================================================================
    // Checksum computation for validating compressed data
    // =========================================================================

    /**
     * Compute CRC32 checksum (used in Gzip trailer).
     * Uses the standard CRC-32 polynomial 0xEDB88320.
     */
    private fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc =
                    if (crc and 1 != 0) {
                        (crc ushr 1) xor 0xEDB88320.toInt()
                    } else {
                        crc ushr 1
                    }
            }
        }
        return crc xor 0xFFFFFFFF.toInt()
    }

    /**
     * Compute Adler32 checksum (used in Zlib/Deflate trailer).
     * Adler32 = (s2 << 16) | s1, where s1 = 1 + sum of bytes, s2 = sum of s1 values.
     */
    private fun adler32(data: ByteArray): Int {
        var s1 = 1
        var s2 = 0
        val modulo = 65521 // Largest prime smaller than 2^16

        for (byte in data) {
            s1 = (s1 + (byte.toInt() and 0xFF)) % modulo
            s2 = (s2 + s1) % modulo
        }
        return (s2 shl 16) or s1
    }

    /**
     * Read a little-endian Int from buffer (swaps bytes from big-endian read).
     */
    private fun ReadBuffer.readLittleEndianInt(): Int {
        val bigEndian = readInt()
        return ((bigEndian and 0xFF) shl 24) or
            ((bigEndian and 0xFF00) shl 8) or
            ((bigEndian shr 8) and 0xFF00) or
            ((bigEndian shr 24) and 0xFF)
    }

    // =========================================================================
    // Expected compressed bytes (validated across platforms)
    // =========================================================================

    // "Hello" compressed with Gzip, default level
    // Generated once, validated to be identical on JVM, macOS, JS Node
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
            0xff, // xfl, os (0xff = unknown, varies by platform)
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
            0x00, // compressed data (same as raw deflate)
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
            0x00, // compressed data only, no header/trailer
        )

    // =========================================================================
    // Tests
    // =========================================================================
    @Test
    fun gzipProducesDeterministicOutput() {
        if (!supportsSyncCompression) return

        val compressed = compress("Hello".toReadBuffer(), CompressionAlgorithm.Gzip)
        assertIs<CompressionResult.Success>(compressed)

        val buffer = compressed.buffer
        val actualBytes = IntArray(buffer.remaining()) { buffer.readByte().toInt() and 0xFF }

        // Validate header (bytes 0-9), skip OS byte (index 9) as it varies
        for (i in 0 until 9) {
            assertEquals(
                expectedGzipHello[i],
                actualBytes[i],
                "Gzip byte $i mismatch: expected 0x${expectedGzipHello[i].toString(16)}, " +
                    "got 0x${actualBytes[i].toString(16)}",
            )
        }

        // Validate compressed data and trailer (bytes 10+)
        for (i in 10 until expectedGzipHello.size) {
            assertEquals(
                expectedGzipHello[i],
                actualBytes[i],
                "Gzip byte $i mismatch: expected 0x${expectedGzipHello[i].toString(16)}, " +
                    "got 0x${actualBytes[i].toString(16)}",
            )
        }

        assertEquals(expectedGzipHello.size, actualBytes.size, "Gzip output size mismatch")
    }

    @Test
    fun deflateProducesDeterministicOutput() {
        if (!supportsSyncCompression) return

        val compressed = compress("Hello".toReadBuffer(), CompressionAlgorithm.Deflate)
        assertIs<CompressionResult.Success>(compressed)

        val buffer = compressed.buffer
        val actualBytes = IntArray(buffer.remaining()) { buffer.readByte().toInt() and 0xFF }

        // Validate all bytes match expected
        for (i in expectedDeflateHello.indices) {
            assertEquals(
                expectedDeflateHello[i],
                actualBytes[i],
                "Deflate byte $i mismatch: expected 0x${expectedDeflateHello[i].toString(16)}, " +
                    "got 0x${actualBytes[i].toString(16)}",
            )
        }

        assertEquals(expectedDeflateHello.size, actualBytes.size, "Deflate output size mismatch")
    }

    @Test
    fun rawDeflateProducesDeterministicOutput() {
        if (!supportsSyncCompression) return

        val compressed = compress("Hello".toReadBuffer(), CompressionAlgorithm.Raw)
        assertIs<CompressionResult.Success>(compressed)

        val buffer = compressed.buffer
        val actualBytes = IntArray(buffer.remaining()) { buffer.readByte().toInt() and 0xFF }

        // Validate all bytes match expected
        for (i in expectedRawHello.indices) {
            assertEquals(
                expectedRawHello[i],
                actualBytes[i],
                "Raw deflate byte $i mismatch: expected 0x${expectedRawHello[i].toString(16)}, " +
                    "got 0x${actualBytes[i].toString(16)}",
            )
        }

        assertEquals(expectedRawHello.size, actualBytes.size, "Raw deflate output size mismatch")
    }

    @Test
    fun compressAndDecompressEmptyData() {
        if (!supportsSyncCompression) return

        val buffer = ReadBuffer.EMPTY_BUFFER
        val compressed = compress(buffer)
        assertIs<CompressionResult.Success>(compressed)

        val decompressed = decompress(compressed.buffer)
        assertIs<CompressionResult.Success>(decompressed)
        assertEquals(0, decompressed.buffer.remaining())
    }

    @Test
    fun compressAndDecompressSmallData() {
        if (!supportsSyncCompression) return

        val text = "Hello, World!"
        val buffer = text.toReadBuffer()
        val compressed = compress(buffer)
        assertIs<CompressionResult.Success>(compressed)

        val decompressed = decompress(compressed.buffer)
        assertIs<CompressionResult.Success>(decompressed)
        assertEquals(text, decompressed.buffer.readString(decompressed.buffer.remaining()))
    }

    @Test
    fun compressAndDecompressLargerData() {
        if (!supportsSyncCompression) return

        val text = "The quick brown fox jumps over the lazy dog. ".repeat(100)
        val buffer = text.toReadBuffer()
        val compressed = compress(buffer)
        assertIs<CompressionResult.Success>(compressed)

        // Verify compression actually reduced size for repetitive data
        assertTrue(
            compressed.buffer.remaining() < buffer.limit(),
            "Compressed size ${compressed.buffer.remaining()} should be less than original ${buffer.limit()}",
        )

        val decompressed = decompress(compressed.buffer)
        assertIs<CompressionResult.Success>(decompressed)
        assertEquals(text, decompressed.buffer.readString(decompressed.buffer.remaining()))
    }

    @Test
    fun gzipHasCorrectMagicBytes() {
        if (!supportsSyncCompression) return

        val text = "Hello, Gzip World!"
        val originalBytes = text.encodeToByteArray()
        val buffer = text.toReadBuffer()
        val compressed = compress(buffer, CompressionAlgorithm.Gzip)
        assertIs<CompressionResult.Success>(compressed)

        val compressedBuffer = compressed.buffer
        val totalSize = compressedBuffer.remaining()

        // Gzip header (10 bytes minimum):
        // Bytes 0-1: Magic number (0x1f 0x8b)
        assertEquals(0x1f.toByte(), compressedBuffer.readByte(), "First gzip magic byte should be 0x1f")
        assertEquals(0x8b.toByte(), compressedBuffer.readByte(), "Second gzip magic byte should be 0x8b")

        // Byte 2: Compression method (8 = deflate)
        assertEquals(0x08.toByte(), compressedBuffer.readByte(), "Compression method should be 0x08 (deflate)")

        // Byte 3: Flags (typically 0x00 for no extra fields)
        val flags = compressedBuffer.readByte().toInt() and 0xFF
        assertEquals(0, flags and 0xE0, "Reserved flag bits should be 0")

        // Bytes 4-7: Modification time (4 bytes, can be 0)
        compressedBuffer.readInt() // mtime - skip

        // Byte 8: Extra flags (XFL)
        val xfl = compressedBuffer.readByte().toInt() and 0xFF
        assertTrue(xfl == 0 || xfl == 2 || xfl == 4, "XFL should be 0, 2 (best compression), or 4 (fastest)")

        // Byte 9: Operating system
        compressedBuffer.readByte() // OS - skip

        // Gzip trailer (last 8 bytes):
        // CRC32 (4 bytes, little-endian) + ISIZE (4 bytes, little-endian)
        assertTrue(totalSize >= 18, "Gzip must be at least 18 bytes (10 header + 8 trailer)")

        compressedBuffer.position(totalSize - 8)
        // Read CRC32 (little-endian)
        val storedCrc32 = compressedBuffer.readLittleEndianInt()

        // Read ISIZE (little-endian)
        val storedIsize = compressedBuffer.readLittleEndianInt()

        // Compute expected CRC32 and verify it matches stored value
        val expectedCrc32 = crc32(originalBytes)
        assertEquals(expectedCrc32, storedCrc32, "CRC32 in trailer should match computed CRC32 of original data")

        // ISIZE should match original text length
        assertEquals(originalBytes.size, storedIsize, "ISIZE should match original data size")

        // Reset and decompress
        compressedBuffer.position(0)
        val decompressed = decompress(compressedBuffer, CompressionAlgorithm.Gzip)
        assertIs<CompressionResult.Success>(decompressed)
        assertEquals(text, decompressed.buffer.readString(decompressed.buffer.remaining()))
    }

    @Test
    fun deflateHasCorrectHeader() {
        if (!supportsSyncCompression) return

        val text = "Hello, Deflate World!"
        val originalBytes = text.encodeToByteArray()
        val buffer = text.toReadBuffer()
        val compressed = compress(buffer, CompressionAlgorithm.Deflate)
        assertIs<CompressionResult.Success>(compressed)

        val compressedBuffer = compressed.buffer
        val totalSize = compressedBuffer.remaining()

        // Zlib header (2 bytes):
        // Byte 0: CMF (Compression Method and Flags)
        //   - bits 0-3: CM (compression method, 8 = deflate)
        //   - bits 4-7: CINFO (window size, 7 = 32K for deflate)
        val cmf = compressedBuffer.readByte().toInt() and 0xFF
        assertEquals(0x78, cmf, "CMF byte should be 0x78 (deflate with 32K window)")
        assertEquals(8, cmf and 0x0F, "Compression method should be 8 (deflate)")
        assertEquals(7, (cmf shr 4) and 0x0F, "Window size should be 7 (32K)")

        // Byte 1: FLG (Flags)
        //   - bits 0-4: FCHECK (checksum so CMF*256 + FLG is divisible by 31)
        //   - bit 5: FDICT (preset dictionary, should be 0)
        //   - bits 6-7: FLEVEL (compression level)
        val flg = compressedBuffer.readByte().toInt() and 0xFF
        assertEquals(0, (cmf * 256 + flg) % 31, "zlib header checksum should be valid (divisible by 31)")
        assertEquals(0, flg and 0x20, "FDICT should be 0 (no preset dictionary)")

        // Zlib trailer (last 4 bytes): Adler32 checksum (big-endian)
        assertTrue(totalSize >= 6, "Zlib must be at least 6 bytes (2 header + 4 trailer)")
        compressedBuffer.position(totalSize - 4)
        val storedAdler32 = compressedBuffer.readInt() // Big-endian

        // Compute expected Adler32 and verify it matches stored value
        val expectedAdler32 = adler32(originalBytes)
        assertEquals(expectedAdler32, storedAdler32, "Adler32 in trailer should match computed Adler32 of original data")

        // Reset and decompress
        compressedBuffer.position(0)
        val decompressed = decompress(compressedBuffer, CompressionAlgorithm.Deflate)
        assertIs<CompressionResult.Success>(decompressed)
        assertEquals(text, decompressed.buffer.readString(decompressed.buffer.remaining()))
    }

    @Test
    fun rawDeflateHasNoHeader() {
        if (!supportsSyncCompression) return

        val text = "Hello, Raw Deflate!"
        val buffer = text.toReadBuffer()
        val compressed = compress(buffer, CompressionAlgorithm.Raw)
        assertIs<CompressionResult.Success>(compressed)

        val compressedBuffer = compressed.buffer
        val firstByte = compressedBuffer.readByte().toInt() and 0xFF

        // Raw deflate should NOT have gzip magic (0x1f 0x8b) or zlib header (0x78)
        assertTrue(
            firstByte != 0x1f && firstByte != 0x78,
            "Raw deflate should not start with gzip (0x1f) or zlib (0x78) header, got 0x${firstByte.toString(16)}",
        )

        // Raw deflate starts directly with deflate block header:
        // - bit 0: BFINAL (1 if this is the last block)
        // - bits 1-2: BTYPE (00=stored, 01=fixed Huffman, 10=dynamic Huffman)
        val btype = (firstByte shr 1) and 0x03
        assertTrue(btype in 0..2, "BTYPE must be 0, 1, or 2, got $btype")

        // Reset and decompress
        compressedBuffer.position(0)
        val decompressed = decompress(compressedBuffer, CompressionAlgorithm.Raw)
        assertIs<CompressionResult.Success>(decompressed)
        assertEquals(text, decompressed.buffer.readString(decompressed.buffer.remaining()))
    }

    @Test
    fun compressWithDifferentLevels() {
        if (!supportsSyncCompression) return

        val text = "Test data for compression levels. ".repeat(50)

        // Create fresh buffers for each compression
        val noCompression = compress(text.toReadBuffer(), level = CompressionLevel.NoCompression)
        assertIs<CompressionResult.Success>(noCompression)

        val bestSpeed = compress(text.toReadBuffer(), level = CompressionLevel.BestSpeed)
        assertIs<CompressionResult.Success>(bestSpeed)

        val defaultLevel = compress(text.toReadBuffer(), level = CompressionLevel.Default)
        assertIs<CompressionResult.Success>(defaultLevel)

        val bestCompression = compress(text.toReadBuffer(), level = CompressionLevel.BestCompression)
        assertIs<CompressionResult.Success>(bestCompression)

        // Best compression should produce smaller or equal output than best speed
        assertTrue(
            bestCompression.buffer.remaining() <= bestSpeed.buffer.remaining(),
            "BestCompression (${bestCompression.buffer.remaining()}) should be <= BestSpeed (${bestSpeed.buffer.remaining()})",
        )

        // All should decompress to original text
        for (result in listOf(noCompression, bestSpeed, defaultLevel, bestCompression)) {
            val decompressed = decompress(result.buffer)
            assertIs<CompressionResult.Success>(decompressed)
            assertEquals(text, decompressed.buffer.readString(decompressed.buffer.remaining()))
        }
    }

    @Test
    fun getOrThrowReturnsBufferOnSuccess() {
        if (!supportsSyncCompression) return

        val buffer = "Test".toReadBuffer()
        val result = compress(buffer)
        val compressed = result.getOrThrow()
        assertTrue(compressed.remaining() > 0)
    }

    @Test
    fun getOrNullReturnsBufferOnSuccess() {
        if (!supportsSyncCompression) return

        val buffer = "Test".toReadBuffer()
        val result = compress(buffer)
        val compressed = result.getOrNull()
        assertTrue(compressed != null && compressed.remaining() > 0)
    }

    @Test
    fun binaryDataRoundTrip() {
        if (!supportsSyncCompression) return

        // Test with binary data containing all byte values
        val buffer = PlatformBuffer.allocate(256)
        for (i in 0 until 256) {
            buffer.writeByte(i.toByte())
        }
        buffer.resetForRead()

        val compressed = compress(buffer)
        assertIs<CompressionResult.Success>(compressed)

        val decompressed = decompress(compressed.buffer)
        assertIs<CompressionResult.Success>(decompressed)

        // Verify all 256 byte values are preserved
        assertEquals(256, decompressed.buffer.remaining())
        for (i in 0 until 256) {
            assertEquals(i.toByte(), decompressed.buffer.readByte(), "Byte at position $i should be $i")
        }
    }

    @Test
    fun unicodeTextRoundTrip() {
        if (!supportsSyncCompression) return

        val text = "Hello 世界! 🌍 Привет мир! مرحبا بالعالم"
        val buffer = text.toReadBuffer()
        val compressed = compress(buffer, CompressionAlgorithm.Gzip)
        assertIs<CompressionResult.Success>(compressed)

        val decompressed = decompress(compressed.buffer, CompressionAlgorithm.Gzip)
        assertIs<CompressionResult.Success>(decompressed)
        assertEquals(text, decompressed.buffer.readString(decompressed.buffer.remaining()))
    }

    @Test
    fun compressionActuallyReducesSize() {
        if (!supportsSyncCompression) return

        // Highly compressible data (repeated pattern)
        val text = "AAAAAAAAAA".repeat(1000)
        val buffer = text.toReadBuffer()
        val originalSize = buffer.remaining()

        val compressed = compress(buffer, CompressionAlgorithm.Gzip, CompressionLevel.BestCompression)
        assertIs<CompressionResult.Success>(compressed)

        val compressedSize = compressed.buffer.remaining()

        // For highly repetitive data, compression should be significant (at least 90% reduction)
        assertTrue(
            compressedSize < originalSize / 10,
            "Highly compressible data should compress to < 10% of original. " +
                "Original: $originalSize, Compressed: $compressedSize",
        )
    }

    // =========================================================================
    // Async one-shot API tests
    // =========================================================================

    @Test
    fun compressAsyncRoundTrip() =
        runTest {
            val text = "Hello, async compression!"
            val compressed = compressAsync(text.toReadBuffer(), CompressionAlgorithm.Gzip)
            val decompressed = decompressAsync(compressed, CompressionAlgorithm.Gzip)
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }

    @Test
    fun compressAsyncWithDifferentAlgorithms() =
        runTest {
            val text = "Testing different algorithms"

            // Browser JS only supports Gzip via CompressionStream API
            val algorithms =
                if (supportsSyncCompression) {
                    listOf(CompressionAlgorithm.Gzip, CompressionAlgorithm.Deflate, CompressionAlgorithm.Raw)
                } else {
                    listOf(CompressionAlgorithm.Gzip)
                }

            for (algorithm in algorithms) {
                val compressed = compressAsync(text.toReadBuffer(), algorithm)
                val decompressed = decompressAsync(compressed, algorithm)
                assertEquals(text, decompressed.readString(decompressed.remaining()), "Failed for $algorithm")
            }
        }

    @Test
    fun compressAsyncWithDifferentLevels() =
        runTest {
            val text = "Testing compression levels " + "x".repeat(1000)

            val fast = compressAsync(text.toReadBuffer(), level = CompressionLevel.BestSpeed)
            val best = compressAsync(text.toReadBuffer(), level = CompressionLevel.BestCompression)

            // Best compression should produce smaller or equal output
            assertTrue(
                best.remaining() <= fast.remaining(),
                "BestCompression (${best.remaining()}) should be <= BestSpeed (${fast.remaining()})",
            )

            // Both should decompress correctly
            assertEquals(text, decompressAsync(fast).readString(text.length))
            assertEquals(text, decompressAsync(best).readString(text.length))
        }

    @Test
    fun compressAsyncLargeData() =
        runTest {
            val text = "Large data test. ".repeat(10000)
            val compressed = compressAsync(text.toReadBuffer())
            assertTrue(compressed.remaining() < text.length, "Compression should reduce size")

            val decompressed = decompressAsync(compressed)
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }

    // =========================================================================
    // expectedOutputSize tests
    // =========================================================================

    @Test
    fun decompressAsyncWithExactExpectedSize() =
        runTest {
            val text = "Hello, expected size!"
            val compressed = compressAsync(text.toReadBuffer())

            // Decompress with exact expected size
            val decompressed = decompressAsync(
                compressed,
                expectedOutputSize = text.length,
            )
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }

    @Test
    fun decompressAsyncWithUnderestimatedSize() =
        runTest {
            val text = "This is a longer string that will exceed our small estimate. ".repeat(100)
            val compressed = compressAsync(text.toReadBuffer())

            // Decompress with way too small expected size - should grow automatically
            val decompressed = decompressAsync(
                compressed,
                expectedOutputSize = 10, // Way too small!
            )
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }

    @Test
    fun decompressAsyncWithOverestimatedSize() =
        runTest {
            val text = "Short"
            val compressed = compressAsync(text.toReadBuffer())

            // Decompress with larger than needed expected size
            val decompressed = decompressAsync(
                compressed,
                expectedOutputSize = 1000, // Larger than needed
            )
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }

    @Test
    fun inputBufferFullyConsumedAfterCompress() =
        runTest {
            val text = "Test input consumption"
            val input = text.toReadBuffer()
            assertEquals(text.length, input.remaining())

            compressAsync(input)

            // Input buffer should be fully consumed
            assertEquals(0, input.remaining())
        }

    @Test
    fun inputBufferConsumedAfterDecompress() =
        runTest {
            val text = "Test input consumption"
            val compressed = compressAsync(text.toReadBuffer())
            val initialRemaining = compressed.remaining()
            assertTrue(initialRemaining > 0, "Should have compressed data")

            val decompressed = decompressAsync(compressed)

            // Verify decompression worked
            assertEquals(text, decompressed.readString(decompressed.remaining()))

            // Input buffer should be mostly consumed (position advanced)
            // Note: may not be exactly 0 due to platform-specific trailer handling
            assertTrue(
                compressed.remaining() < initialRemaining,
                "Input buffer position should advance during decompression",
            )
        }

    @Test
    fun outputBufferStateAfterCompress() =
        runTest {
            // Use longer text to ensure compression actually reduces size
            val text = "Test output buffer state ".repeat(100)
            val compressed = compressAsync(text.toReadBuffer())

            // Output buffer should be ready for reading: position=0, limit=size
            assertEquals(0, compressed.position())
            assertTrue(compressed.remaining() > 0, "Should have compressed data")
            assertTrue(compressed.remaining() < text.length, "Should be smaller than input for repetitive data")
        }

    @Test
    fun outputBufferStateAfterDecompress() =
        runTest {
            val text = "Test output buffer state"
            val compressed = compressAsync(text.toReadBuffer())
            val decompressed = decompressAsync(compressed)

            // Output buffer should be ready for reading: position=0, limit=size
            assertEquals(0, decompressed.position())
            assertEquals(text.length, decompressed.remaining())
        }

    @Test
    fun outputBufferStateWithExpectedSize() =
        runTest {
            val text = "Test output buffer state with expected size hint"
            val compressed = compressAsync(text.toReadBuffer())
            val decompressed = decompressAsync(compressed, expectedOutputSize = text.length)

            // Output buffer should be ready for reading: position=0, limit=size
            assertEquals(0, decompressed.position())
            assertEquals(text.length, decompressed.remaining())
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }
}
