package com.ditchoom.buffer.compression

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.pool.withPool
import com.ditchoom.buffer.toReadBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for BufferAllocator.FromPool and compression with pool-allocated buffers.
 */
class PoolAllocatorTests {
    // =========================================================================
    // BufferAllocator.FromPool basic tests
    // =========================================================================

    @Test
    fun fromPoolAllocatesFromPool() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 4) { pool ->
            val allocator = BufferAllocator.FromPool(pool)
            val buffer = allocator.allocate(512)
            assertTrue(buffer.capacity >= 512)
            assertEquals(1, pool.stats().totalAllocations)
            pool.release(buffer)
        }

    @Test
    fun fromPoolReusesBuffers() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 4) { pool ->
            val allocator = BufferAllocator.FromPool(pool)
            val buffer1 = allocator.allocate(512)
            pool.release(buffer1)
            val buffer2 = allocator.allocate(512)
            pool.release(buffer2)

            val stats = pool.stats()
            assertEquals(2, stats.totalAllocations)
            assertEquals(1, stats.poolHits)
        }

    // =========================================================================
    // Streaming compression with pool allocator
    // =========================================================================

    @Test
    fun streamingCompressionWithPoolAllocator() {
        if (!supportsSyncCompression) return

        withPool(defaultBufferSize = 32768, maxPoolSize = 8) { pool ->
            val allocator = BufferAllocator.FromPool(pool)
            val text = "Hello from pool-allocated compression!"
            val compressedChunks = mutableListOf<ReadBuffer>()

            StreamingCompressor.create(allocator = allocator).use(
                onOutput = { compressedChunks.add(it) },
            ) { compress ->
                compress(text.toReadBuffer())
            }

            assertTrue(compressedChunks.isNotEmpty(), "Should produce compressed output")

            // Decompress with pool allocator too
            val decompressedChunks = mutableListOf<ReadBuffer>()
            val compressed = combineBuffers(compressedChunks)

            StreamingDecompressor.create(allocator = allocator).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(compressed)
            }

            val decompressed = combineBuffers(decompressedChunks)
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }
    }

    @Test
    fun streamingCompressionMultipleChunksWithPoolAllocator() {
        if (!supportsSyncCompression) return

        withPool(defaultBufferSize = 32768, maxPoolSize = 8) { pool ->
            val allocator = BufferAllocator.FromPool(pool)
            val chunks =
                listOf(
                    "First chunk of data. ",
                    "Second chunk of data. ",
                    "Third chunk of data.",
                )
            val fullText = chunks.joinToString("")

            val compressedChunks = mutableListOf<ReadBuffer>()

            StreamingCompressor.create(allocator = allocator).use(
                onOutput = { compressedChunks.add(it) },
            ) { compress ->
                for (chunk in chunks) {
                    compress(chunk.toReadBuffer())
                }
            }

            val compressed = combineBuffers(compressedChunks)
            val decompressedChunks = mutableListOf<ReadBuffer>()

            StreamingDecompressor.create(allocator = allocator).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(compressed)
            }

            val decompressed = combineBuffers(decompressedChunks)
            assertEquals(fullText, decompressed.readString(decompressed.remaining()))
        }
    }

    @Test
    fun streamingCompressionWithPoolAllocatorGzip() {
        if (!supportsSyncCompression) return

        withPool(defaultBufferSize = 32768, maxPoolSize = 8) { pool ->
            val allocator = BufferAllocator.FromPool(pool)
            val text = "Gzip with pool allocation test data"
            val compressedChunks = mutableListOf<ReadBuffer>()

            StreamingCompressor
                .create(
                    algorithm = CompressionAlgorithm.Gzip,
                    allocator = allocator,
                ).use(
                    onOutput = { compressedChunks.add(it) },
                ) { compress ->
                    compress(text.toReadBuffer())
                }

            val compressed = combineBuffers(compressedChunks)
            // Verify gzip magic bytes
            assertEquals(0x1f.toByte(), compressed.get(0), "Gzip magic byte 1")
            assertEquals(0x8b.toByte(), compressed.get(1), "Gzip magic byte 2")

            val decompressedChunks = mutableListOf<ReadBuffer>()

            StreamingDecompressor
                .create(
                    algorithm = CompressionAlgorithm.Gzip,
                    allocator = allocator,
                ).use(
                    onOutput = { decompressedChunks.add(it) },
                ) { decompress ->
                    decompress(compressed)
                }

            val decompressed = combineBuffers(decompressedChunks)
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }
    }

    // =========================================================================
    // Compression with heap allocator
    // =========================================================================

    @Test
    fun streamingCompressionWithHeapAllocator() {
        if (!supportsSyncCompression) return

        val text = "Hello from heap-allocated compression!"
        val compressedChunks = mutableListOf<ReadBuffer>()

        StreamingCompressor.create(allocator = BufferAllocator.Heap).use(
            onOutput = { compressedChunks.add(it) },
        ) { compress ->
            compress(text.toReadBuffer())
        }

        assertTrue(compressedChunks.isNotEmpty())

        val compressed = combineBuffers(compressedChunks)
        val decompressedChunks = mutableListOf<ReadBuffer>()

        StreamingDecompressor.create(allocator = BufferAllocator.Heap).use(
            onOutput = { decompressedChunks.add(it) },
        ) { decompress ->
            decompress(compressed)
        }

        val decompressed = combineBuffers(decompressedChunks)
        assertEquals(text, decompressed.readString(decompressed.remaining()))
    }

    // =========================================================================
    // Pool allocator with reset (reuse compressor)
    // =========================================================================

    @Test
    fun poolAllocatorWithCompressorReset() {
        if (!supportsSyncCompression) return

        withPool(defaultBufferSize = 32768, maxPoolSize = 8) { pool ->
            val allocator = BufferAllocator.FromPool(pool)
            val compressor = StreamingCompressor.create(allocator = allocator)
            val decompressor = StreamingDecompressor.create(allocator = allocator)

            try {
                for (i in 1..3) {
                    val text = "Iteration $i: data to compress"
                    val compressedChunks = mutableListOf<ReadBuffer>()

                    compressor.compress(text.toReadBuffer()) { compressedChunks.add(it) }
                    compressor.finish { compressedChunks.add(it) }
                    compressor.reset()

                    val compressed = combineBuffers(compressedChunks)
                    val decompressedChunks = mutableListOf<ReadBuffer>()

                    decompressor.decompress(compressed) { decompressedChunks.add(it) }
                    decompressor.finish { decompressedChunks.add(it) }
                    decompressor.reset()

                    val decompressed = combineBuffers(decompressedChunks)
                    assertEquals(
                        text,
                        decompressed.readString(decompressed.remaining()),
                        "Failed on iteration $i",
                    )
                }
            } finally {
                compressor.close()
                decompressor.close()
            }

            // Verify all 3 iterations completed successfully (pool reuse is best-effort)
        }
    }

    // =========================================================================
    // Large data with pool allocator
    // =========================================================================

    @Test
    fun poolAllocatorLargeData() {
        if (!supportsSyncCompression) return

        withPool(defaultBufferSize = 32768, maxPoolSize = 8) { pool ->
            val allocator = BufferAllocator.FromPool(pool)

            // Generate 100KB of compressible data
            val sb = StringBuilder()
            repeat(1000) { i ->
                sb.append("Line $i: The quick brown fox jumps over the lazy dog.\n")
            }
            val text = sb.toString()

            val compressedChunks = mutableListOf<ReadBuffer>()

            StreamingCompressor.create(allocator = allocator).use(
                onOutput = { compressedChunks.add(it) },
            ) { compress ->
                // Send in 4KB chunks like a real network scenario
                val bytes = text.encodeToByteArray()
                var offset = 0
                while (offset < bytes.size) {
                    val chunkSize = minOf(4096, bytes.size - offset)
                    val chunk = PlatformBuffer.allocate(chunkSize)
                    chunk.writeBytes(bytes, offset, chunkSize)
                    chunk.resetForRead()
                    compress(chunk)
                    offset += chunkSize
                }
            }

            val compressed = combineBuffers(compressedChunks)
            assertTrue(
                compressed.remaining() < text.length,
                "Compressed should be smaller than original",
            )

            val decompressedChunks = mutableListOf<ReadBuffer>()

            StreamingDecompressor.create(allocator = allocator).use(
                onOutput = { decompressedChunks.add(it) },
            ) { decompress ->
                decompress(compressed)
            }

            val decompressed = combineBuffers(decompressedChunks)
            assertEquals(text, decompressed.readString(decompressed.remaining()))
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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
