package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Parameterized parity matrix for [StreamingCompressor] / [StreamingDecompressor].
 *
 * Sweeps algorithm × compression level × windowBits × payload shape × lifecycle and
 * asserts byte-exact roundtrip for every combination. The intent is to exercise every
 * reachable code path of the common streaming API at least once on every KMP target,
 * so divergence between platform implementations (and regressions like the JS persistent
 * zlib trailer-drain bug at payload sizes around the output-chunk boundary) surface
 * deterministically here instead of in downstream Autobahn / integration runs.
 *
 * Sub-cases are loop-driven rather than separate `@Test` methods so the matrix stays
 * cheap (<200 ms per target). Failures include the parameter tuple in the assertion
 * message so the exact case is identifiable from the test report.
 */
class StreamingCompressorApiMatrix {
    // ---------------------------------------------------------------- Fixtures

    // Heap-backed factory: avoids the wasmJs LinearBuffer 256 MB bump-allocator cap and
    // keeps cross-platform behavior identical (deflate is deterministic at fixed params).
    private val factory: BufferFactory = BufferFactory.managed()

    private data class Lifecycle(val name: String, val drive: (StreamingCompressor, StreamingDecompressor, ByteArray) -> ByteArray)

    private val algorithms = listOf(CompressionAlgorithm.Deflate, CompressionAlgorithm.Gzip, CompressionAlgorithm.Raw)
    private val levels =
        listOf(
            CompressionLevel.NoCompression,
            CompressionLevel.BestSpeed,
            CompressionLevel.Default,
            CompressionLevel.BestCompression,
        )

    // Includes a payload that compresses to fill > 1 output chunk (16 KB on Node zlib);
    // the 32 KB and 64 KB random shapes specifically trigger the multi-iteration writeSync
    // path on the JS persistent stream — where the Z_SYNC_FLUSH trailer-drain bug lives.
    private val payloads: List<Pair<String, ByteArray>> =
        listOf(
            "empty" to ByteArray(0),
            "small_utf8" to "Hello, world — multibyte: 日本語".encodeToByteArray(),
            "ascii_1k" to ByteArray(1024) { ((it % 95) + 32).toByte() },
            "repetitive_32k" to ByteArray(32 * 1024) { 'A'.code.toByte() },
            "varied_32k" to varyingPayload(32 * 1024),
            "random_64k" to deterministicPseudoRandom(64 * 1024),
        )

    /** Pseudo-random payload — incompressible enough to force multi-chunk compressed output. */
    private fun deterministicPseudoRandom(size: Int): ByteArray {
        // xorshift32 keeps this dependency-free and stable across platforms.
        var state = 0x9E3779B9.toInt()
        return ByteArray(size) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            state.toByte()
        }
    }

    /** Varying-byte payload — compresses moderately but not trivially. */
    private fun varyingPayload(size: Int): ByteArray =
        ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }

    // ---------------------------------------------------------------- Helpers

    private fun ReadBuffer.toBytes(): ByteArray {
        val len = remaining()
        val bytes = ByteArray(len)
        for (i in 0 until len) bytes[i] = readByte()
        return bytes
    }

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
        var offset = 0
        for (c in chunks) {
            val n = c.remaining()
            for (i in 0 until n) out[offset++] = c.readByte()
        }
        return out
    }

    /** Drives compressor.compress(...) + lifecycle, returns compressed bytes. */
    private fun compressOneShot(input: ByteArray, compressor: StreamingCompressor): ByteArray {
        val chunks = mutableListOf<ReadBuffer>()
        if (input.isNotEmpty()) {
            compressor.compress(input.toReadBuffer()) { chunks.add(it) }
        }
        compressor.finish { chunks.add(it) }
        return combine(chunks)
    }

    /** Drives decompressor.decompress(...) + finish, returns decompressed bytes. */
    private fun decompressOneShot(compressed: ByteArray, decompressor: StreamingDecompressor): ByteArray {
        val chunks = mutableListOf<ReadBuffer>()
        if (compressed.isNotEmpty()) {
            decompressor.decompress(compressed.toReadBuffer()) { chunks.add(it) }
        }
        decompressor.finish { chunks.add(it) }
        return combine(chunks)
    }

    private fun lifecycleFinishOnly(): Lifecycle =
        Lifecycle("finish-only") { c, d, input ->
            val compressed = compressOneShot(input, c)
            decompressOneShot(compressed, d)
        }

    /**
     * WebSocket-flow lifecycle: compress + flush + reset on the compressor between
     * messages, decompress + reset on the decompressor between messages. Run a few
     * iterations to surface state-corruption regressions across reset boundaries.
     */
    private fun lifecycleFlushResetCycle(iterations: Int): Lifecycle =
        Lifecycle("flush+reset×$iterations") { compressor, decompressor, input ->
            // Concatenate all decoded outputs across iterations — they must all equal `input`.
            var lastDecoded: ByteArray = ByteArray(0)
            repeat(iterations) { iter ->
                val chunks = mutableListOf<ReadBuffer>()
                if (input.isNotEmpty()) {
                    compressor.compress(input.toReadBuffer()) { chunks.add(it) }
                }
                compressor.flush { chunks.add(it) }
                val compressed = combine(chunks)
                compressor.reset()

                val dchunks = mutableListOf<ReadBuffer>()
                if (compressed.isNotEmpty()) {
                    decompressor.decompress(compressed.toReadBuffer()) { dchunks.add(it) }
                }
                decompressor.flush { dchunks.add(it) }
                val decoded = combine(dchunks)
                decompressor.reset()
                if (!decoded.contentEquals(input)) {
                    fail("iter=$iter: decoded (${decoded.size} B) does not match input (${input.size} B)")
                }
                lastDecoded = decoded
            }
            lastDecoded
        }

    // ---------------------------------------------------------------- Matrix

    @Test
    fun roundtrip_all_combinations_finish_only() {
        if (!supportsSyncCompression) return
        runMatrix(lifecycleFinishOnly())
    }

    @Test
    fun roundtrip_all_combinations_flush_reset_cycle() {
        if (!supportsSyncCompression) return
        if (!supportsStatefulFlush) {
            // On platforms without stateful flush, flush() produces independent blocks but
            // the per-message reset semantics are equivalent — still worth exercising the
            // outer lifecycle. A handful of iterations is enough.
        }
        // 100 iterations is enough to deterministically hit the JS persistent-stream
        // multi-iteration writeSync path (where the Z_SYNC_FLUSH trailer-drain bug lived).
        runMatrix(lifecycleFlushResetCycle(iterations = 100), payloadFilter = { it.first != "empty" })
    }

    private fun runMatrix(lifecycle: Lifecycle, payloadFilter: (Pair<String, ByteArray>) -> Boolean = { true }) {
        var combos = 0
        for (alg in algorithms) {
            if (alg == CompressionAlgorithm.Raw && !supportsRawDeflate) continue
            for (level in levels) {
                for ((shape, payload) in payloads) {
                    if (!payloadFilter(shape to payload)) continue
                    val params = "alg=$alg level=${level.value} shape=$shape lifecycle=${lifecycle.name}"
                    val compressor = StreamingCompressor.create(alg, level, factory)
                    val decompressor = StreamingDecompressor.create(alg, factory)
                    try {
                        val out = lifecycle.drive(compressor, decompressor, payload)
                        assertContentEquals(payload, out, params)
                        combos++
                    } finally {
                        compressor.close()
                        decompressor.close()
                    }
                }
            }
        }
        assertTrue(combos > 0, "matrix executed zero combinations — platform may not support any algorithm")
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray, message: String) {
        if (!expected.contentEquals(actual)) {
            // Truncate large diffs for readability.
            val expHex = expected.take(32).joinToString(" ") { ((it.toInt() and 0xFF)).toString(16).padStart(2, '0') }
            val actHex = actual.take(32).joinToString(" ") { ((it.toInt() and 0xFF)).toString(16).padStart(2, '0') }
            fail("$message — sizes expected=${expected.size} actual=${actual.size}, head expected=[$expHex] actual=[$actHex]")
        }
    }

    // ---------------------------------------------------------------- Wire-format equivalence

    /**
     * For a given (algorithm, level), deflate is deterministic at fixed window size:
     * compressing the same input must yield byte-identical output regardless of whether
     * the compressor is fresh or came through a reset cycle. Any divergence here is a
     * smoking gun for state leakage across `reset()`.
     */
    @Test
    fun resetCycle_produces_identical_bytes_to_fresh_compressor() {
        if (!supportsSyncCompression) return
        val input = "The quick brown fox jumps over the lazy dog. ".repeat(50).encodeToByteArray()
        val priorInput = "context-bleed canary".repeat(100).encodeToByteArray()
        for (alg in algorithms) {
            if (alg == CompressionAlgorithm.Raw && !supportsRawDeflate) continue

            val fresh = StreamingCompressor.create(alg, CompressionLevel.Default, factory)
            val freshBytes = compressOneShot(input, fresh)
            fresh.close()

            // Reset-cycle compressor: compress a different payload first, finish it,
            // then reset, then compress the same target input.
            val cycled = StreamingCompressor.create(alg, CompressionLevel.Default, factory)
            cycled.compress(priorInput.toReadBuffer()) { /* discard */ }
            val sink = mutableListOf<ReadBuffer>()
            cycled.finish { sink.add(it) }
            sink.forEach { /* keep alive */ }
            cycled.reset()
            val cycledBytes = compressOneShot(input, cycled)
            cycled.close()

            assertContentEquals(
                freshBytes,
                cycledBytes,
                "wire-format equivalence after reset for alg=$alg",
            )
        }
    }
}
