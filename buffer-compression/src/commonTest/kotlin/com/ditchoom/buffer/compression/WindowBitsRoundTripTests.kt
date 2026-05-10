package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toReadBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cross-platform round-trip coverage for the [WindowBits] parameter on
 * [StreamingCompressor.create].
 *
 * Direct regression target: [LinuxStreamingCompression] used to pass a positive
 * `windowBits: Int` straight to `deflateInit2`, which produces *zlib*-format
 * output for [CompressionAlgorithm.Raw]. The raw decompressor then failed with
 * `inflate code -3` (Z_DATA_ERROR). JVM masked the bug because java.util.zip
 * ignores `windowBits`; Apple and JS/Wasm currently also ignore it.
 *
 * The contract — captured in [resolveWindowBits] — is:
 *  - [WindowBits.Default] selects the algorithm's default (15-bit / 32 KB window).
 *  - [WindowBits] of `9..15` carries the size; the algorithm determines the format.
 *  - 8 is excluded entirely because zlib's `deflateInit2` rejects it
 *    (`Z_STREAM_ERROR`).
 *  - Negative / offset / out-of-range values are unrepresentable.
 *
 * These tests verify cross-algorithm round-trips for every supported size.
 * Platforms that ignore `windowBits` still pass because they fall back to the
 * algorithm default for both compress and decompress.
 */
class WindowBitsRoundTripTests {
    private val sample = "windowBits round-trip — repeated repeated repeated content for compressibility " * 8

    // =========================================================================
    // Round-trip matrix: every supported size × every algorithm
    // =========================================================================

    @Test
    fun rawRoundTrip_default() {
        if (!supportsSyncCompression) return
        roundTrip(CompressionAlgorithm.Raw, WindowBits.Default)
    }

    @Test
    fun rawRoundTrip_min() {
        if (!supportsSyncCompression) return
        roundTrip(CompressionAlgorithm.Raw, WindowBits.Min)
    }

    @Test
    fun rawRoundTrip_max() {
        if (!supportsSyncCompression) return
        roundTrip(CompressionAlgorithm.Raw, WindowBits.Max)
    }

    @Test
    fun rawRoundTrip_allSizes() {
        if (!supportsSyncCompression) return
        for (size in 9..15) {
            roundTrip(CompressionAlgorithm.Raw, WindowBits(size))
        }
    }

    @Test
    fun deflateRoundTrip_default() {
        if (!supportsSyncCompression) return
        roundTrip(CompressionAlgorithm.Deflate, WindowBits.Default)
    }

    @Test
    fun deflateRoundTrip_allSizes() {
        if (!supportsSyncCompression) return
        for (size in 9..15) {
            roundTrip(CompressionAlgorithm.Deflate, WindowBits(size))
        }
    }

    @Test
    fun gzipRoundTrip_default() {
        if (!supportsSyncCompression) return
        roundTrip(CompressionAlgorithm.Gzip, WindowBits.Default)
    }

    @Test
    fun gzipRoundTrip_allSizes() {
        if (!supportsSyncCompression) return
        for (size in 9..15) {
            roundTrip(CompressionAlgorithm.Gzip, WindowBits(size))
        }
    }

    // =========================================================================
    // Format-byte assertions — catches algorithm-confusion bugs
    // =========================================================================

    @Test
    fun gzipOutputStartsWithMagicBytes() {
        if (!supportsSyncCompression) return
        for (size in 9..15) {
            val compressed = compressAll(CompressionAlgorithm.Gzip, WindowBits(size))
            assertEquals(0x1f.toByte(), compressed.get(0), "gzip magic byte 1 (size=$size)")
            assertEquals(0x8b.toByte(), compressed.get(1), "gzip magic byte 2 (size=$size)")
        }
    }

    @Test
    fun zlibOutputStartsWithCMF() {
        if (!supportsSyncCompression) return
        for (size in 9..15) {
            val compressed = compressAll(CompressionAlgorithm.Deflate, WindowBits(size))
            // zlib CMF byte: low nibble is compression method (8 = deflate).
            val cmf = compressed.get(0).toInt() and 0xFF
            assertEquals(8, cmf and 0x0F, "zlib CMF method nibble (size=$size)")
            // High nibble of CMF is windowBits-8 (so 9..15 → 1..7). Only meaningful on
            // platforms that honor windowBits; others always emit the default (7).
            val windowNibble = (cmf shr 4) and 0x0F
            if (supportsCustomWindowBits) {
                assertEquals(size - 8, windowNibble, "zlib CMF window nibble (size=$size)")
            } else {
                assertEquals(7, windowNibble, "platform ignores windowBits; expected default (15→7)")
            }
        }
    }

    // Raw deflate has no header — assert the inverse: first byte must NOT be
    // a gzip magic or a zlib CMF (would indicate algorithm-confusion).
    @Test
    fun rawOutputHasNoZlibOrGzipHeader() {
        if (!supportsSyncCompression) return
        for (size in 9..15) {
            val compressed = compressAll(CompressionAlgorithm.Raw, WindowBits(size))
            val first = compressed.get(0).toInt() and 0xFF
            assertTrue(
                first != 0x1f && (first and 0x0F) != 0x08,
                "raw deflate must not emit zlib/gzip header (size=$size, first=0x${first.toString(16)})",
            )
        }
    }

    // =========================================================================
    // resolveWindowBits unit tests — guards the helper directly
    // =========================================================================

    @Test
    fun resolveWindowBits_defaultPerAlgorithm() {
        assertEquals(15, resolveWindowBits(CompressionAlgorithm.Deflate, WindowBits.Default))
        assertEquals(-15, resolveWindowBits(CompressionAlgorithm.Raw, WindowBits.Default))
        assertEquals(31, resolveWindowBits(CompressionAlgorithm.Gzip, WindowBits.Default))
    }

    @Test
    fun resolveWindowBits_perSizeAndAlgorithm() {
        for (size in 9..15) {
            val w = WindowBits(size)
            assertEquals(size, resolveWindowBits(CompressionAlgorithm.Deflate, w))
            assertEquals(-size, resolveWindowBits(CompressionAlgorithm.Raw, w))
            assertEquals(size + 16, resolveWindowBits(CompressionAlgorithm.Gzip, w))
        }
    }

    // =========================================================================
    // WindowBits construction validation — impossible states are unrepresentable
    // =========================================================================

    @Test
    fun windowBits_rejectsZero() {
        assertFailsWith<IllegalArgumentException> { WindowBits(0) }
    }

    @Test
    fun windowBits_rejectsEight() {
        // zlib's deflateInit2 returns Z_STREAM_ERROR for windowBits == ±8.
        assertFailsWith<IllegalArgumentException> { WindowBits(8) }
    }

    @Test
    fun windowBits_rejectsNegative() {
        assertFailsWith<IllegalArgumentException> { WindowBits(-9) }
    }

    @Test
    fun windowBits_rejectsAboveFifteen() {
        assertFailsWith<IllegalArgumentException> { WindowBits(16) }
        assertFailsWith<IllegalArgumentException> { WindowBits(31) }
    }

    @Test
    fun windowBits_minMaxAreCanonical() {
        assertEquals(WindowBits(9), WindowBits.Min)
        assertEquals(WindowBits(15), WindowBits.Max)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun roundTrip(
        algorithm: CompressionAlgorithm,
        windowBits: WindowBits,
    ) {
        val combined = compressAll(algorithm, windowBits)
        val decompressed = mutableListOf<ReadBuffer>()
        StreamingDecompressor
            .create(algorithm = algorithm, bufferFactory = BufferFactory.Default)
            .use(onOutput = { decompressed.add(it) }) { decompress ->
                decompress(combined)
            }
        val out = combineBuffers(decompressed)
        assertEquals(
            sample,
            out.readString(out.remaining()),
            "round-trip mismatch (algorithm=$algorithm windowBits=$windowBits)",
        )
    }

    private fun compressAll(
        algorithm: CompressionAlgorithm,
        windowBits: WindowBits,
    ): PlatformBuffer {
        val compressed = mutableListOf<ReadBuffer>()
        StreamingCompressor
            .create(
                algorithm = algorithm,
                level = CompressionLevel.Default,
                bufferFactory = BufferFactory.Default,
                windowBits = windowBits,
            ).use(onOutput = { compressed.add(it) }) { compress ->
                compress(sample.toReadBuffer())
            }
        assertTrue(compressed.isNotEmpty(), "compress produced no output (algorithm=$algorithm windowBits=$windowBits)")
        return combineBuffers(compressed)
    }

    private fun combineBuffers(buffers: List<ReadBuffer>): PlatformBuffer {
        if (buffers.isEmpty()) return BufferFactory.Default.allocate(0)
        val total = buffers.sumOf { it.remaining() }
        val combined = BufferFactory.Default.allocate(total)
        for (b in buffers) combined.write(b)
        combined.resetForRead()
        return combined
    }

    private operator fun String.times(n: Int): String = buildString { repeat(n) { append(this@times) } }
}
