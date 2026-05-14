package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * State-machine and contract assertions for [StreamingCompressor] / [StreamingDecompressor].
 *
 * Each KMP target (JVM / Apple / Linux / JS / wasmJs) brings its own implementation; this
 * suite pins down the common-API behavior so divergence is caught here rather than at the
 * downstream call site. Tests are deliberately tiny so they don't add measurable runtime.
 */
class CompressionLifecycleContractTests {
    // Heap-backed factory: avoids the wasmJs LinearBuffer 256 MB bump-allocator cap.
    private val factory: BufferFactory = BufferFactory.managed()

    private fun ByteArray.toReadBuffer(): ReadBuffer {
        val buf = factory.allocate(size)
        if (isNotEmpty()) buf.writeBytes(this)
        buf.resetForRead()
        return buf
    }

    private fun combine(chunks: List<ReadBuffer>): ByteArray {
        val total = chunks.sumOf { it.remaining() }
        if (total == 0) return ByteArray(0)
        val out = ByteArray(total)
        var off = 0
        for (c in chunks) {
            val n = c.remaining()
            for (i in 0 until n) out[off++] = c.readByte()
        }
        return out
    }

    private fun roundTrip(input: ByteArray, alg: CompressionAlgorithm = CompressionAlgorithm.Deflate): ByteArray {
        val compressor = StreamingCompressor.create(alg, CompressionLevel.Default, factory)
        val cChunks = mutableListOf<ReadBuffer>()
        if (input.isNotEmpty()) {
            compressor.compress(input.toReadBuffer()) { cChunks.add(it) }
        }
        compressor.finish { cChunks.add(it) }
        compressor.close()
        val compressed = combine(cChunks)

        val decompressor = StreamingDecompressor.create(alg, factory)
        val dChunks = mutableListOf<ReadBuffer>()
        if (compressed.isNotEmpty()) {
            decompressor.decompress(compressed.toReadBuffer()) { dChunks.add(it) }
        }
        decompressor.finish { dChunks.add(it) }
        decompressor.close()
        return combine(dChunks)
    }

    // -------------------------------------------------------------- Close semantics

    @Test
    fun close_then_compress_throws() {
        if (!supportsSyncCompression) return
        val c = StreamingCompressor.create(CompressionAlgorithm.Deflate, CompressionLevel.Default, factory)
        c.close()
        assertFails("compress after close must throw") {
            c.compress("data".encodeToByteArray().toReadBuffer()) { /* drain */ }
        }
    }

    @Test
    fun close_then_flush_throws() {
        if (!supportsSyncCompression) return
        val c = StreamingCompressor.create(CompressionAlgorithm.Deflate, CompressionLevel.Default, factory)
        c.close()
        assertFails("flush after close must throw") {
            c.flush { /* drain */ }
        }
    }

    @Test
    fun close_then_finish_throws() {
        if (!supportsSyncCompression) return
        val c = StreamingCompressor.create(CompressionAlgorithm.Deflate, CompressionLevel.Default, factory)
        c.close()
        assertFails("finish after close must throw") {
            c.finish { /* drain */ }
        }
    }

    @Test
    fun close_is_idempotent() {
        if (!supportsSyncCompression) return
        val c = StreamingCompressor.create(CompressionAlgorithm.Deflate, CompressionLevel.Default, factory)
        c.close()
        c.close() // must not throw
    }

    @Test
    fun decompressor_close_then_decompress_throws() {
        if (!supportsSyncCompression) return
        val d = StreamingDecompressor.create(CompressionAlgorithm.Deflate, factory)
        d.close()
        assertFails {
            d.decompress("garbage".encodeToByteArray().toReadBuffer()) { /* drain */ }
        }
    }

    // -------------------------------------------------------------- Empty / boundary input

    @Test
    fun finish_with_no_input_emits_valid_empty_message() {
        if (!supportsSyncCompression) return
        // For each algorithm, an empty input must roundtrip to an empty output.
        val algorithms = listOf(CompressionAlgorithm.Deflate, CompressionAlgorithm.Gzip, CompressionAlgorithm.Raw)
        for (alg in algorithms) {
            if (alg == CompressionAlgorithm.Raw && !supportsRawDeflate) continue
            val out = roundTrip(ByteArray(0), alg)
            assertEquals(0, out.size, "$alg: empty input must roundtrip to empty output")
        }
    }

    @Test
    fun flush_with_no_input_does_not_throw() {
        if (!supportsSyncCompression) return
        val c = StreamingCompressor.create(CompressionAlgorithm.Deflate, CompressionLevel.Default, factory)
        try {
            c.flush { /* may emit empty or sync-marker block; both are valid */ }
        } finally {
            c.close()
        }
    }

    // -------------------------------------------------------------- Reset recovery

    @Test
    fun reset_after_finish_allows_new_message() {
        if (!supportsSyncCompression) return
        val msg1 = "first message".encodeToByteArray()
        val msg2 = "second message after reset".encodeToByteArray()

        val compressor = StreamingCompressor.create(CompressionAlgorithm.Deflate, CompressionLevel.Default, factory)
        val decompressor = StreamingDecompressor.create(CompressionAlgorithm.Deflate, factory)

        try {
            val c1 = mutableListOf<ReadBuffer>()
            compressor.compress(msg1.toReadBuffer()) { c1.add(it) }
            compressor.finish { c1.add(it) }
            val compressed1 = combine(c1)

            // After finish(), the stream is terminal. reset() must recover it.
            compressor.reset()

            val c2 = mutableListOf<ReadBuffer>()
            compressor.compress(msg2.toReadBuffer()) { c2.add(it) }
            compressor.finish { c2.add(it) }
            val compressed2 = combine(c2)

            // Independent decompression of each compressed message.
            val d1 = mutableListOf<ReadBuffer>()
            decompressor.decompress(compressed1.toReadBuffer()) { d1.add(it) }
            decompressor.finish { d1.add(it) }
            assertTrue(msg1.contentEquals(combine(d1)), "message 1 must roundtrip")
            decompressor.reset()

            val d2 = mutableListOf<ReadBuffer>()
            decompressor.decompress(compressed2.toReadBuffer()) { d2.add(it) }
            decompressor.finish { d2.add(it) }
            assertTrue(msg2.contentEquals(combine(d2)), "message 2 (after reset) must roundtrip")
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // -------------------------------------------------------------- Output buffer boundary

    /**
     * Compresses N messages where N×payload_size guarantees the compressed output
     * crosses a 16 KB boundary at least once (matching Node's default `_chunkSize`).
     * Caught today's Z_SYNC_FLUSH trailer-drain bug: when the output buffer fills
     * exactly at input exhaustion, the persistent stream's writeSync loop must keep
     * spinning until a non-full output buffer signals the trailer has been emitted.
     */
    @Test
    fun large_payload_repeated_compress_flush_roundtrips() {
        if (!supportsSyncCompression) return
        val payloadSize = 32 * 1024 // forces multi-iteration writeSync on a 16 KB outBuffer
        val iterations = 16          // small but plenty to hit any boundary alignment
        val payload = ByteArray(payloadSize) { (it and 0xFF).toByte() }

        for (alg in listOf(CompressionAlgorithm.Deflate, CompressionAlgorithm.Raw)) {
            if (alg == CompressionAlgorithm.Raw && !supportsRawDeflate) continue
            val compressor = StreamingCompressor.create(alg, CompressionLevel.Default, factory)
            val decompressor = StreamingDecompressor.create(alg, factory)
            try {
                repeat(iterations) { iter ->
                    val cChunks = mutableListOf<ReadBuffer>()
                    compressor.compress(payload.toReadBuffer()) { cChunks.add(it) }
                    compressor.flush { cChunks.add(it) }
                    val compressed = combine(cChunks)
                    if (compressed.isEmpty()) fail("iter=$iter alg=$alg: flush produced no output")
                    compressor.reset()

                    val dChunks = mutableListOf<ReadBuffer>()
                    decompressor.decompress(compressed.toReadBuffer()) { dChunks.add(it) }
                    decompressor.flush { dChunks.add(it) }
                    val decoded = combine(dChunks)
                    decompressor.reset()
                    if (!decoded.contentEquals(payload)) {
                        fail("iter=$iter alg=$alg: decoded ${decoded.size}B does not match payload ${payload.size}B")
                    }
                }
            } finally {
                compressor.close()
                decompressor.close()
            }
        }
    }
}
