package com.ditchoom.buffer.compression

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Exhaustive stress tests for compression that validate:
 * - Large data handling across buffer boundaries
 * - Chunked compression/decompression with various chunk sizes
 * - Byte-by-byte correctness of compressed output
 * - Round-trip integrity for edge cases
 *
 * All tests use ReadBuffer/WriteBuffer directly without intermediate ByteArrays.
 */
class CompressionStressTests {
    // =========================================================================
    // Optimized comparison utilities
    // =========================================================================

    /**
     * Compares two buffers using optimized Long-by-Long comparison.
     * Returns the index of the first mismatch, or -1 if buffers are identical.
     * Much faster than byte-by-byte comparison for large buffers.
     */
    private fun findMismatchOptimized(
        expected: ReadBuffer,
        actual: ReadBuffer,
    ): Int {
        val expectedSize = expected.remaining()
        val actualSize = actual.remaining()

        if (expectedSize != actualSize) {
            return minOf(expectedSize, actualSize)
        }

        val size = expectedSize
        val expectedStart = expected.position()
        val actualStart = actual.position()

        // Compare 8 bytes at a time using Long reads
        var i = 0
        while (i + 8 <= size) {
            val expectedLong = expected.getLong(expectedStart + i)
            val actualLong = actual.getLong(actualStart + i)
            if (expectedLong != actualLong) {
                // Find exact byte within the Long
                for (j in 0 until 8) {
                    if (expected[expectedStart + i + j] != actual[actualStart + i + j]) {
                        return i + j
                    }
                }
            }
            i += 8
        }

        // Compare remaining bytes
        while (i < size) {
            if (expected[expectedStart + i] != actual[actualStart + i]) {
                return i
            }
            i++
        }

        return -1 // No mismatch
    }

    /**
     * Asserts that two buffers are byte-by-byte identical.
     * Uses optimized Long comparison for speed.
     */
    private fun assertBuffersEqual(
        expected: ReadBuffer,
        actual: ReadBuffer,
        message: String = "Buffers differ",
    ) {
        val mismatchIndex = findMismatchOptimized(expected, actual)
        if (mismatchIndex >= 0) {
            val expectedSize = expected.remaining()
            val actualSize = actual.remaining()
            val expectedStart = expected.position()
            val actualStart = actual.position()

            val context =
                if (mismatchIndex < expectedSize && mismatchIndex < actualSize) {
                    val expectedByte = expected[expectedStart + mismatchIndex]
                    val actualByte = actual[actualStart + mismatchIndex]
                    "at index $mismatchIndex: expected 0x${expectedByte.toUByte().toString(16)} " +
                        "but was 0x${actualByte.toUByte().toString(16)}"
                } else {
                    "size mismatch: expected $expectedSize bytes but was $actualSize bytes"
                }
            fail("$message: $context")
        }
    }

    // =========================================================================
    // Test data generators (return ReadBuffer directly, no ByteArray)
    // =========================================================================

    /**
     * Generates highly compressible data (repeating pattern).
     * Returns a buffer ready for reading (position=0, limit=size).
     */
    private fun generateCompressibleBuffer(size: Int): PlatformBuffer {
        val pattern = "ABCDEFGHIJKLMNOP" // 16 chars
        val buffer = PlatformBuffer.allocate(size)
        for (i in 0 until size) {
            buffer.writeByte(pattern[i % pattern.length].code.toByte())
        }
        buffer.resetForRead()
        return buffer
    }

    /**
     * Generates random data (less compressible).
     * Returns a buffer ready for reading.
     */
    private fun generateRandomBuffer(
        size: Int,
        seed: Long = 42L,
    ): PlatformBuffer {
        val random = Random(seed)
        val buffer = PlatformBuffer.allocate(size)
        // Write in Long chunks for efficiency, then remaining bytes
        var i = 0
        while (i + 8 <= size) {
            buffer.writeLong(random.nextLong())
            i += 8
        }
        while (i < size) {
            buffer.writeByte(random.nextInt().toByte())
            i++
        }
        buffer.resetForRead()
        return buffer
    }

    /**
     * Generates mixed data with varying compressibility.
     * Returns a buffer ready for reading.
     */
    private fun generateMixedBuffer(size: Int): PlatformBuffer {
        val buffer = PlatformBuffer.allocate(size)
        val random = Random(123)
        var i = 0
        while (i < size) {
            // Alternate between compressible and random sections
            val sectionSize = minOf(1024, size - i)
            if ((i / 1024) % 2 == 0) {
                // Compressible section - repeated byte
                val pattern = ((i / 1024) % 256).toByte()
                for (j in 0 until sectionSize) {
                    buffer.writeByte(pattern)
                }
            } else {
                // Random section
                var j = 0
                while (j + 8 <= sectionSize) {
                    buffer.writeLong(random.nextLong())
                    j += 8
                }
                while (j < sectionSize) {
                    buffer.writeByte(random.nextInt().toByte())
                    j++
                }
            }
            i += sectionSize
        }
        buffer.resetForRead()
        return buffer
    }

    /**
     * Creates a copy of a buffer for later comparison.
     * The original buffer's position is reset after copying.
     */
    private fun copyBuffer(source: ReadBuffer): PlatformBuffer {
        val size = source.remaining()
        val copy = PlatformBuffer.allocate(size)
        copy.write(source)
        copy.resetForRead()
        // Reset source position for reuse
        source.position(source.position() - size)
        return copy
    }

    /**
     * Splits a buffer into chunks of specified size.
     * Returns list of independent buffers (copies, not slices).
     */
    private fun splitIntoChunks(
        source: ReadBuffer,
        chunkSize: Int,
    ): List<PlatformBuffer> {
        val chunks = mutableListOf<PlatformBuffer>()
        val originalPosition = source.position()
        while (source.remaining() > 0) {
            val thisChunkSize = minOf(chunkSize, source.remaining())
            val chunk = PlatformBuffer.allocate(thisChunkSize)
            for (j in 0 until thisChunkSize) {
                chunk.writeByte(source.readByte())
            }
            chunk.resetForRead()
            chunks.add(chunk)
        }
        // Reset source position
        source.position(originalPosition)
        return chunks
    }

    /**
     * Splits a buffer into variable-sized chunks.
     */
    private fun splitIntoVariableChunks(
        source: ReadBuffer,
        seed: Long = 999L,
    ): List<PlatformBuffer> {
        val random = Random(seed)
        val chunks = mutableListOf<PlatformBuffer>()
        val originalPosition = source.position()
        while (source.remaining() > 0) {
            // Random chunk size between 1 and 4KB
            val chunkSize = minOf(random.nextInt(1, 4096), source.remaining())
            val chunk = PlatformBuffer.allocate(chunkSize)
            for (j in 0 until chunkSize) {
                chunk.writeByte(source.readByte())
            }
            chunk.resetForRead()
            chunks.add(chunk)
        }
        // Reset source position
        source.position(originalPosition)
        return chunks
    }

    // =========================================================================
    // Edge case sizes
    // =========================================================================

    private val edgeCaseSizes =
        listOf(
            0, // Empty
            1, // Single byte
            7, // Less than 8 (Long size)
            8, // Exactly Long size
            9, // Just over Long size
            15, // Common alignment boundary - 1
            16, // Common alignment boundary
            17, // Common alignment boundary + 1
            127, // Just under typical buffer
            128, // Typical small buffer
            129, // Just over typical buffer
            255, // Max unsigned byte value
            256, // 2^8
            257, // 2^8 + 1
            1023, // Just under 1KB
            1024, // 1KB
            1025, // Just over 1KB
            4095, // Just under 4KB (page size)
            4096, // 4KB (typical page size)
            4097, // Just over page size
            8191, // Just under 8KB
            8192, // 8KB
            8193, // Just over 8KB
            32767, // Just under 32KB (typical streaming buffer)
            32768, // 32KB (typical output buffer size in compression)
            32769, // Just over 32KB
            65535, // Max unsigned short
            65536, // 64KB
            65537, // 64KB + 1
        )

    // =========================================================================
    // Stress tests - Round trip with various data sizes
    // =========================================================================

    @Test
    fun stressTestCompressibleDataVariousSizes() =
        runTest {
            for (size in edgeCaseSizes) {
                if (size == 0) continue // Skip empty for this test

                val original = generateCompressibleBuffer(size)
                val originalCopy = copyBuffer(original)
                val compressed = compressAsync(original)
                val decompressed = decompressAsync(compressed)

                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Round-trip failed for compressible data of size $size",
                )
            }
        }

    @Test
    fun stressTestRandomDataVariousSizes() =
        runTest {
            for (size in edgeCaseSizes) {
                if (size == 0) continue // Skip empty for this test

                val original = generateRandomBuffer(size)
                val originalCopy = copyBuffer(original)
                val compressed = compressAsync(original)
                val decompressed = decompressAsync(compressed)

                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Round-trip failed for random data of size $size",
                )
            }
        }

    @Test
    fun stressTestMixedDataVariousSizes() =
        runTest {
            for (size in edgeCaseSizes) {
                if (size == 0) continue

                val original = generateMixedBuffer(size)
                val originalCopy = copyBuffer(original)
                val compressed = compressAsync(original)
                val decompressed = decompressAsync(compressed)

                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Round-trip failed for mixed data of size $size",
                )
            }
        }

    // =========================================================================
    // Stress tests - Large data
    // =========================================================================

    @Test
    fun stressTestLargeCompressibleData() =
        runTest {
            val sizes = listOf(100_000, 500_000, 1_000_000) // 100KB, 500KB, 1MB

            for (size in sizes) {
                val original = generateCompressibleBuffer(size)
                val originalCopy = copyBuffer(original)
                val compressed = compressAsync(original)

                // Verify compression actually reduced size for compressible data
                assertTrue(
                    compressed.remaining() < size / 2,
                    "Expected significant compression for $size bytes of compressible data",
                )

                val decompressed = decompressAsync(compressed)

                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Round-trip failed for large compressible data of size $size",
                )
            }
        }

    @Test
    fun stressTestLargeRandomData() =
        runTest {
            val sizes = listOf(100_000, 500_000, 1_000_000)

            for (size in sizes) {
                val original = generateRandomBuffer(size)
                val originalCopy = copyBuffer(original)
                val compressed = compressAsync(original)
                val decompressed = decompressAsync(compressed)

                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Round-trip failed for large random data of size $size",
                )
            }
        }

    // =========================================================================
    // Stress tests - Chunked streaming compression
    // =========================================================================

    @Test
    fun stressTestChunkedCompressionVariousChunkSizes() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            val dataSize = 100_000
            val original = generateMixedBuffer(dataSize)
            val originalCopy = copyBuffer(original)
            val chunkSizes = listOf(1, 7, 8, 9, 15, 16, 17, 64, 128, 256, 512, 1024, 4096, 8192, 32768)

            for (chunkSize in chunkSizes) {
                // Reset original for each iteration
                original.position(0)
                val chunks = splitIntoChunks(original, chunkSize)
                val compressedChunks = mutableListOf<ReadBuffer>()

                // Compress using streaming API with chunks
                val compressor = StreamingCompressor.create(CompressionAlgorithm.Gzip)
                try {
                    for (chunk in chunks) {
                        compressor.compress(chunk) { compressedChunks.add(it) }
                    }
                    compressor.finish { compressedChunks.add(it) }
                } finally {
                    compressor.close()
                }

                // Combine compressed chunks
                val totalCompressedSize = compressedChunks.sumOf { it.remaining() }
                val compressed = PlatformBuffer.allocate(totalCompressedSize)
                for (chunk in compressedChunks) {
                    compressed.write(chunk)
                }
                compressed.resetForRead()

                // Decompress and verify
                val decompressed = decompressAsync(compressed)
                originalCopy.position(0)

                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Chunked compression failed with chunk size $chunkSize",
                )
            }
        }

    @Test
    fun stressTestChunkedDecompressionVariousChunkSizes() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            val dataSize = 100_000
            val original = generateMixedBuffer(dataSize)
            val originalCopy = copyBuffer(original)

            // Compress all at once
            val compressed = compressAsync(original)
            val compressedCopy = copyBuffer(compressed)

            val chunkSizes = listOf(1, 7, 8, 9, 15, 16, 17, 64, 128, 256, 512, 1024, 4096, 8192, 32768)

            for (chunkSize in chunkSizes) {
                // Reset compressed for each iteration
                compressedCopy.position(0)
                val chunks = splitIntoChunks(compressedCopy, chunkSize)
                val decompressedChunks = mutableListOf<ReadBuffer>()

                // Decompress using streaming API with chunks
                val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Gzip)
                try {
                    for (chunk in chunks) {
                        decompressor.decompress(chunk) { decompressedChunks.add(it) }
                    }
                    decompressor.finish { decompressedChunks.add(it) }
                } finally {
                    decompressor.close()
                }

                // Combine decompressed chunks
                val totalDecompressedSize = decompressedChunks.sumOf { it.remaining() }
                val decompressed = PlatformBuffer.allocate(totalDecompressedSize)
                for (chunk in decompressedChunks) {
                    decompressed.write(chunk)
                }
                decompressed.resetForRead()

                originalCopy.position(0)
                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Chunked decompression failed with chunk size $chunkSize",
                )
            }
        }

    @Test
    fun stressTestVariableChunkSizes() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            val dataSize = 100_000
            val original = generateMixedBuffer(dataSize)
            val originalCopy = copyBuffer(original)

            // Compress with variable chunk sizes
            val compressChunks = splitIntoVariableChunks(original, seed = 111L)
            val compressedOutput = mutableListOf<ReadBuffer>()

            val compressor = StreamingCompressor.create(CompressionAlgorithm.Gzip)
            try {
                for (chunk in compressChunks) {
                    compressor.compress(chunk) { compressedOutput.add(it) }
                }
                compressor.finish { compressedOutput.add(it) }
            } finally {
                compressor.close()
            }

            // Combine compressed data
            val totalCompressedSize = compressedOutput.sumOf { it.remaining() }
            val compressed = PlatformBuffer.allocate(totalCompressedSize)
            for (chunk in compressedOutput) {
                compressed.write(chunk)
            }
            compressed.resetForRead()

            // Decompress with different variable chunk sizes
            val decompressChunks = splitIntoVariableChunks(compressed, seed = 222L)
            val decompressedOutput = mutableListOf<ReadBuffer>()

            val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Gzip)
            try {
                for (chunk in decompressChunks) {
                    decompressor.decompress(chunk) { decompressedOutput.add(it) }
                }
                decompressor.finish { decompressedOutput.add(it) }
            } finally {
                decompressor.close()
            }

            // Combine and verify
            val totalDecompressedSize = decompressedOutput.sumOf { it.remaining() }
            val decompressed = PlatformBuffer.allocate(totalDecompressedSize)
            for (chunk in decompressedOutput) {
                decompressed.write(chunk)
            }
            decompressed.resetForRead()

            assertBuffersEqual(originalCopy, decompressed, "Variable chunk size round-trip failed")
        }

    // =========================================================================
    // Stress tests - Compression determinism
    // =========================================================================

    @Test
    fun stressTestCompressionDeterminism() =
        runTest {
            val sizes = listOf(1000, 10_000, 100_000)

            for (size in sizes) {
                val original = generateMixedBuffer(size)

                // Compress multiple times (reset position between compressions)
                original.position(0)
                val compressed1 = compressAsync(copyBuffer(original))
                original.position(0)
                val compressed2 = compressAsync(copyBuffer(original))
                original.position(0)
                val compressed3 = compressAsync(copyBuffer(original))

                // Verify all compressions produce identical output
                assertBuffersEqual(compressed1, compressed2, "Compression not deterministic for size $size (1 vs 2)")
                compressed1.position(0)
                assertBuffersEqual(compressed1, compressed3, "Compression not deterministic for size $size (1 vs 3)")
            }
        }

    // =========================================================================
    // Stress tests - expectedOutputSize parameter
    // =========================================================================

    @Test
    fun stressTestExpectedOutputSizeExact() =
        runTest {
            for (size in edgeCaseSizes) {
                if (size == 0) continue

                val original = generateCompressibleBuffer(size)
                val originalCopy = copyBuffer(original)
                val compressed = compressAsync(original)

                // Decompress with exact expected size
                val decompressed =
                    decompressAsync(
                        compressed,
                        expectedOutputSize = size,
                    )

                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Exact expectedOutputSize failed for size $size",
                )
            }
        }

    @Test
    fun stressTestExpectedOutputSizeUnderestimate() =
        runTest {
            val testCases =
                listOf(
                    // (dataSize, underestimate)
                    Pair(1000, 1),
                    Pair(1000, 10),
                    Pair(1000, 100),
                    Pair(10_000, 1),
                    Pair(10_000, 100),
                    Pair(10_000, 1000),
                    Pair(100_000, 1),
                    Pair(100_000, 1000),
                    Pair(100_000, 10_000),
                )

            for ((dataSize, underestimate) in testCases) {
                val original = generateCompressibleBuffer(dataSize)
                val originalCopy = copyBuffer(original)
                val compressed = compressAsync(original)

                // Decompress with underestimated size - should grow automatically
                val decompressed =
                    decompressAsync(
                        compressed,
                        expectedOutputSize = underestimate,
                    )

                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Underestimated expectedOutputSize ($underestimate for $dataSize) failed",
                )
            }
        }

    @Test
    fun stressTestExpectedOutputSizeOverestimate() =
        runTest {
            val testCases =
                listOf(
                    // (dataSize, overestimate)
                    Pair(100, 1000),
                    Pair(100, 10_000),
                    Pair(1000, 10_000),
                    Pair(1000, 100_000),
                    Pair(10_000, 100_000),
                    Pair(10_000, 1_000_000),
                )

            for ((dataSize, overestimate) in testCases) {
                val original = generateCompressibleBuffer(dataSize)
                val originalCopy = copyBuffer(original)
                val compressed = compressAsync(original)

                // Decompress with overestimated size - should return right-sized buffer
                val decompressed =
                    decompressAsync(
                        compressed,
                        expectedOutputSize = overestimate,
                    )

                // Verify buffer is right-sized, not oversized
                assertEquals(
                    dataSize,
                    decompressed.remaining(),
                    "Buffer should be right-sized for $dataSize (not $overestimate)",
                )

                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Overestimated expectedOutputSize ($overestimate for $dataSize) failed",
                )
            }
        }

    // =========================================================================
    // Stress tests - All algorithms
    // =========================================================================

    @Test
    fun stressTestAllAlgorithmsWithLargeData() =
        runTest {
            // Browser JS only supports Gzip
            val algorithms =
                if (supportsSyncCompression) {
                    listOf(
                        CompressionAlgorithm.Gzip,
                        CompressionAlgorithm.Deflate,
                        CompressionAlgorithm.Raw,
                    )
                } else {
                    listOf(CompressionAlgorithm.Gzip)
                }

            val sizes = listOf(1000, 10_000, 100_000)

            for (algorithm in algorithms) {
                for (size in sizes) {
                    val original = generateMixedBuffer(size)
                    val originalCopy = copyBuffer(original)
                    val compressed = compressAsync(original, algorithm)
                    val decompressed = decompressAsync(compressed, algorithm)

                    assertBuffersEqual(
                        originalCopy,
                        decompressed,
                        "Round-trip failed for $algorithm with size $size",
                    )
                }
            }
        }

    // =========================================================================
    // Stress tests - Compression levels
    // =========================================================================

    @Test
    fun stressTestCompressionLevelsWithLargeData() =
        runTest {
            val levels =
                listOf(
                    CompressionLevel.NoCompression,
                    CompressionLevel.BestSpeed,
                    CompressionLevel.Default,
                    CompressionLevel.BestCompression,
                    CompressionLevel.Custom(3),
                    CompressionLevel.Custom(7),
                )

            val sizes = listOf(1000, 10_000, 100_000)

            for (level in levels) {
                for (size in sizes) {
                    val original = generateCompressibleBuffer(size)
                    val originalCopy = copyBuffer(original)
                    val compressed = compressAsync(original, level = level)
                    val decompressed = decompressAsync(compressed)

                    assertBuffersEqual(
                        originalCopy,
                        decompressed,
                        "Round-trip failed for level $level with size $size",
                    )
                }
            }
        }

    @Test
    fun stressTestCompressionLevelOrdering() =
        runTest {
            // For compressible data, higher compression levels should produce smaller output
            val size = 100_000
            val original = generateCompressibleBuffer(size)
            val originalCopy = copyBuffer(original)

            // Compress with different levels
            original.position(0)
            val noCompression = compressAsync(copyBuffer(original), level = CompressionLevel.NoCompression)
            original.position(0)
            val bestSpeed = compressAsync(copyBuffer(original), level = CompressionLevel.BestSpeed)
            original.position(0)
            val default = compressAsync(copyBuffer(original), level = CompressionLevel.Default)
            original.position(0)
            val bestCompression = compressAsync(copyBuffer(original), level = CompressionLevel.BestCompression)

            // BestCompression should be <= Default <= BestSpeed
            assertTrue(
                bestCompression.remaining() <= default.remaining(),
                "BestCompression (${bestCompression.remaining()}) should be <= Default (${default.remaining()})",
            )
            assertTrue(
                default.remaining() <= bestSpeed.remaining(),
                "Default (${default.remaining()}) should be <= BestSpeed (${bestSpeed.remaining()})",
            )
            assertTrue(
                bestSpeed.remaining() <= noCompression.remaining(),
                "BestSpeed (${bestSpeed.remaining()}) should be <= NoCompression (${noCompression.remaining()})",
            )

            // All should decompress correctly
            for ((name, compressed) in listOf(
                "NoCompression" to noCompression,
                "BestSpeed" to bestSpeed,
                "Default" to default,
                "BestCompression" to bestCompression,
            )) {
                val decompressed = decompressAsync(compressed)
                originalCopy.position(0)
                assertBuffersEqual(originalCopy, decompressed, "Round-trip failed for $name")
            }
        }

    // =========================================================================
    // Stress tests - Allocation zones
    // =========================================================================

    @Test
    fun stressTestAllocationZones() =
        runTest {
            val zones = listOf(AllocationZone.Heap, AllocationZone.Direct)
            val size = 50_000
            val original = generateMixedBuffer(size)
            val originalCopy = copyBuffer(original)

            for (zone in zones) {
                original.position(0)
                val compressed = compressAsync(copyBuffer(original), zone = zone)
                val decompressed = decompressAsync(compressed, zone = zone)

                originalCopy.position(0)
                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Round-trip failed for zone $zone",
                )
            }
        }

    @Test
    fun stressTestAllocationZonesWithExpectedSize() =
        runTest {
            val zones = listOf(AllocationZone.Heap, AllocationZone.Direct)
            val size = 50_000
            val original = generateMixedBuffer(size)
            val originalCopy = copyBuffer(original)

            for (zone in zones) {
                original.position(0)
                val compressed = compressAsync(copyBuffer(original), zone = zone)
                val decompressed =
                    decompressAsync(
                        compressed,
                        zone = zone,
                        expectedOutputSize = size,
                    )

                originalCopy.position(0)
                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Round-trip with expectedSize failed for zone $zone",
                )
            }
        }

    // =========================================================================
    // Edge case tests
    // =========================================================================

    @Test
    fun stressTestSingleByteData() =
        runTest {
            // Test all possible single byte values
            for (byteValue in 0..255) {
                val original = PlatformBuffer.allocate(1)
                original.writeByte(byteValue.toByte())
                original.resetForRead()
                val originalCopy = copyBuffer(original)

                val compressed = compressAsync(original)
                val decompressed = decompressAsync(compressed)

                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Single byte round-trip failed for value $byteValue",
                )
            }
        }

    @Test
    fun stressTestRepeatedSingleByte() =
        runTest {
            // Test various sizes of repeated single byte (highly compressible)
            for (byteValue in listOf(0x00, 0x55, 0xAA, 0xFF)) {
                for (size in listOf(100, 1000, 10_000, 100_000)) {
                    val original = PlatformBuffer.allocate(size)
                    repeat(size) {
                        original.writeByte(byteValue.toByte())
                    }
                    original.resetForRead()
                    val originalCopy = copyBuffer(original)

                    val compressed = compressAsync(original)
                    val decompressed = decompressAsync(compressed)

                    assertBuffersEqual(
                        originalCopy,
                        decompressed,
                        "Repeated byte 0x${byteValue.toString(16)} of size $size failed",
                    )
                }
            }
        }

    @Test
    fun stressTestBufferBoundaryAlignedData() =
        runTest {
            // Test sizes that align with common buffer boundaries
            val boundarySizes =
                listOf(
                    32768 - 1,
                    32768,
                    32768 + 1, // 32KB (typical output buffer)
                    32768 * 2 - 1,
                    32768 * 2,
                    32768 * 2 + 1,
                    32768 * 3,
                    32768 * 4, // 128KB
                )

            for (size in boundarySizes) {
                val original = generateMixedBuffer(size)
                val originalCopy = copyBuffer(original)
                val compressed = compressAsync(original)
                val decompressed = decompressAsync(compressed)

                assertBuffersEqual(
                    originalCopy,
                    decompressed,
                    "Buffer boundary aligned size $size failed",
                )
            }
        }

    // =========================================================================
    // Stress tests - Many reset cycles (validates inflateReset/deflateReset)
    // =========================================================================

    @Test
    fun stressTestManyCompressorResetCycles() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            // Test many reset cycles to ensure no memory corruption
            // This validates that deflateReset() works correctly
            val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
            val resetCycles = 500 // Enough to trigger memory issues if reset is broken

            try {
                for (cycle in 0 until resetCycles) {
                    val text = "Reset cycle $cycle - data to compress"
                    val chunks = mutableListOf<ReadBuffer>()

                    compressor.compress(text.toReadBuffer()) { chunks.add(it) }
                    compressor.finish { chunks.add(it) }

                    // Verify compression worked
                    assertTrue(chunks.isNotEmpty(), "Cycle $cycle: Should have output")

                    // Decompress and verify
                    val combined = combineBuffers(chunks)
                    val decompressedChunks = mutableListOf<ReadBuffer>()
                    StreamingDecompressor.create(CompressionAlgorithm.Raw).use(
                        onOutput = { decompressedChunks.add(it) },
                    ) { decompress ->
                        decompress(combined)
                    }
                    val decompressed = combineBuffers(decompressedChunks)
                    assertEquals(
                        text,
                        decompressed.readString(decompressed.remaining()),
                        "Cycle $cycle: Round-trip failed",
                    )

                    // Reset for next cycle
                    compressor.reset()
                }
            } finally {
                compressor.close()
            }
        }

    @Test
    fun stressTestManyDecompressorResetCycles() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            // Test many reset cycles to ensure no memory corruption
            // This validates that inflateReset() works correctly
            val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
            val resetCycles = 500 // Enough to trigger memory issues if reset is broken

            try {
                for (cycle in 0 until resetCycles) {
                    val text = "Reset cycle $cycle - data to decompress"

                    // Compress the data first
                    val compressedChunks = mutableListOf<ReadBuffer>()
                    StreamingCompressor.create(CompressionAlgorithm.Raw).use(
                        onOutput = { compressedChunks.add(it) },
                    ) { compress ->
                        compress(text.toReadBuffer())
                    }
                    val compressed = combineBuffers(compressedChunks)

                    // Decompress using the reused decompressor
                    val decompressedChunks = mutableListOf<ReadBuffer>()
                    decompressor.decompress(compressed) { decompressedChunks.add(it) }
                    decompressor.finish { decompressedChunks.add(it) }

                    // Verify decompression worked
                    val decompressed = combineBuffers(decompressedChunks)
                    assertEquals(
                        text,
                        decompressed.readString(decompressed.remaining()),
                        "Cycle $cycle: Round-trip failed",
                    )

                    // Reset for next cycle
                    decompressor.reset()
                }
            } finally {
                decompressor.close()
            }
        }

    @Test
    fun stressTestManyResetCyclesWithLargerData() =
        runTest {
            if (!supportsSyncCompression) return@runTest

            // Test reset cycles with larger data to stress native heap more
            val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
            val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
            val resetCycles = 100
            val dataSize = 10_000

            try {
                for (cycle in 0 until resetCycles) {
                    val original = generateMixedBuffer(dataSize)
                    val originalCopy = copyBuffer(original)

                    // Compress
                    val compressedChunks = mutableListOf<ReadBuffer>()
                    compressor.compress(original) { compressedChunks.add(it) }
                    compressor.finish { compressedChunks.add(it) }
                    val compressed = combineBuffers(compressedChunks)

                    // Decompress
                    val decompressedChunks = mutableListOf<ReadBuffer>()
                    decompressor.decompress(compressed) { decompressedChunks.add(it) }
                    decompressor.finish { decompressedChunks.add(it) }
                    val decompressed = combineBuffers(decompressedChunks)

                    // Verify
                    assertBuffersEqual(
                        originalCopy,
                        decompressed,
                        "Cycle $cycle: Round-trip with larger data failed",
                    )

                    // Reset both for next cycle
                    compressor.reset()
                    decompressor.reset()
                }
            } finally {
                compressor.close()
                decompressor.close()
            }
        }

    /**
     * Helper to combine buffers for stress tests.
     */
    private fun combineBuffers(buffers: List<ReadBuffer>): PlatformBuffer {
        if (buffers.isEmpty()) return PlatformBuffer.allocate(0)
        val totalSize = buffers.sumOf { it.remaining() }
        val combined = PlatformBuffer.allocate(totalSize)
        for (buffer in buffers) {
            combined.write(buffer)
        }
        combined.resetForRead()
        return combined
    }
}
