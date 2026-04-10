package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that compression/decompression works with both native-memory and managed-memory
 * (ByteArray-backed) buffers. On Linux native, these exercise different branches of
 * `withInputPointer` — the function that triggered the Kotlin/Native AutoboxingTransformer
 * compiler crash (LINUX_NATIVE_COMPILER_BUG.md).
 *
 * These tests run on all platforms and serve as a regression guard.
 */
class ManagedBufferCompressionTests {
    // =========================================================================
    // Compression with managed (ByteArray-backed) input buffers
    // =========================================================================

    @Test
    fun compressWithManagedInputBuffer() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        try {
            val input = BufferFactory.managed().allocate(256)
            repeat(256) { input.writeByte((it % 251).toByte()) }
            input.resetForRead()

            val output = BufferFactory.managed().allocate(512)
            compressor.compressScoped(input) { output.write(this) }
            compressor.flushScoped { output.write(this) }
            output.resetForRead()

            // Compressed output should be non-empty
            assertTrue(output.remaining() > 0, "Compressed output should not be empty")
        } finally {
            compressor.close()
        }
    }

    @Test
    fun decompressWithManagedInputBuffer() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        try {
            // Compress with default (native) buffers
            val original = BufferFactory.Default.allocate(256)
            repeat(256) { original.writeByte((it % 251).toByte()) }
            original.resetForRead()

            val compressed = BufferFactory.managed().allocate(512)
            compressor.compressScoped(original) { compressed.write(this) }
            compressor.flushScoped { compressed.write(this) }
            compressed.resetForRead()

            // Decompress using managed buffer as input
            val decompressed = BufferFactory.managed().allocate(512)
            decompressor.decompressScoped(compressed) { decompressed.write(this) }
            decompressor.flushScoped { decompressed.write(this) }
            decompressed.resetForRead()

            assertEquals(256, decompressed.remaining(), "Decompressed size mismatch")
            for (i in 0 until 256) {
                assertEquals((i % 251).toByte(), decompressed.readByte(), "Byte mismatch at $i")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Round-trip: managed input → compress → managed input → decompress
    // =========================================================================

    @Test
    fun roundTripWithAllManagedBuffers() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        try {
            val input = BufferFactory.managed().allocate(1024)
            repeat(1024) { input.writeByte((it % 251).toByte()) }
            input.resetForRead()

            val compressed = BufferFactory.managed().allocate(2048)
            compressor.compressScoped(input) { compressed.write(this) }
            compressor.flushScoped { compressed.write(this) }
            compressed.resetForRead()

            val decompressed = BufferFactory.managed().allocate(2048)
            decompressor.decompressScoped(compressed) { decompressed.write(this) }
            decompressor.flushScoped { decompressed.write(this) }
            decompressed.resetForRead()

            assertEquals(1024, decompressed.remaining(), "All-managed round-trip size mismatch")
            for (i in 0 until 1024) {
                assertEquals((i % 251).toByte(), decompressed.readByte(), "Byte mismatch at $i")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    @Test
    fun roundTripWithDefaultBuffers() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        try {
            val input = BufferFactory.Default.allocate(1024)
            repeat(1024) { input.writeByte((it % 251).toByte()) }
            input.resetForRead()

            val compressed = BufferFactory.managed().allocate(2048)
            compressor.compressScoped(input) { compressed.write(this) }
            compressor.flushScoped { compressed.write(this) }
            compressed.resetForRead()

            val decompressed = BufferFactory.managed().allocate(2048)
            decompressor.decompressScoped(compressed) { decompressed.write(this) }
            decompressor.flushScoped { decompressed.write(this) }
            decompressed.resetForRead()

            assertEquals(1024, decompressed.remaining(), "Default-buffer round-trip size mismatch")
            for (i in 0 until 1024) {
                assertEquals((i % 251).toByte(), decompressed.readByte(), "Byte mismatch at $i")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Mixed: compress with native input, decompress with managed input
    // =========================================================================

    @Test
    fun crossMemoryTypeRoundTrip() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        try {
            // Compress with native-memory input
            val nativeInput = BufferFactory.Default.allocate(512)
            repeat(512) { nativeInput.writeByte((it % 199).toByte()) }
            nativeInput.resetForRead()

            val compressed = BufferFactory.managed().allocate(1024)
            compressor.compressScoped(nativeInput) { compressed.write(this) }
            compressor.flushScoped { compressed.write(this) }
            compressed.resetForRead()

            // Decompress with managed-memory input (the compressed bytes in a ByteArray buffer)
            val managedCompressed = BufferFactory.managed().allocate(compressed.remaining())
            managedCompressed.write(compressed)
            managedCompressed.resetForRead()

            val decompressed = BufferFactory.managed().allocate(1024)
            decompressor.decompressScoped(managedCompressed) { decompressed.write(this) }
            decompressor.flushScoped { decompressed.write(this) }
            decompressed.resetForRead()

            assertEquals(512, decompressed.remaining(), "Cross-memory round-trip size mismatch")
            for (i in 0 until 512) {
                assertEquals((i % 199).toByte(), decompressed.readByte(), "Byte mismatch at $i")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // finishUnsafe path with managed buffers
    // =========================================================================

    @Test
    fun finishWithManagedBuffers() {
        val compressor = StreamingCompressor.create(CompressionAlgorithm.Raw)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw)
        try {
            val input = BufferFactory.managed().allocate(256)
            repeat(256) { input.writeByte((it % 251).toByte()) }
            input.resetForRead()

            val compressed = BufferFactory.managed().allocate(512)
            compressor.compressScoped(input) { compressed.write(this) }
            compressor.finishScoped { compressed.write(this) }
            compressed.resetForRead()

            val decompressed = BufferFactory.managed().allocate(512)
            decompressor.decompressScoped(compressed) { decompressed.write(this) }
            decompressor.finishScoped { decompressed.write(this) }
            decompressed.resetForRead()

            assertEquals(256, decompressed.remaining(), "Finish with managed buffers size mismatch")
            for (i in 0 until 256) {
                assertEquals((i % 251).toByte(), decompressed.readByte(), "Byte mismatch at $i")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }
}
