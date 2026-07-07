package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.toReadBuffer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the preset-dictionary seam: [compress]/[decompress], the streaming
 * `create()` factories, and the "small dictionary is a free lunch" result from
 * docs/investigations/compression-dictionaries/README.md.
 */
class DictionaryTests {
    // A small dictionary tuned to a fictional MQTT-telemetry corpus's common byte
    // sequences, mirroring the investigation's HYBRID strategy (structural + value
    // patterns), just small enough to keep tests fast.
    private val topicDictionary = "topic=sensors/livingroom/temperature;unit=celsius;value="

    private fun sampleMessage(i: Int) = "$topicDictionary${20 + (i % 10)}.${i % 10}C;seq=$i"

    // =========================================================================
    // CompressionAlgorithm.supportsDictionary() semantics
    // =========================================================================

    @Test
    fun gzipNeverSupportsDictionary() {
        // Format-level restriction (RFC 1952 has no preset-dictionary mechanism),
        // independent of the platform flag.
        assertTrue(!CompressionAlgorithm.Gzip.supportsDictionary())
    }

    @Test
    fun rawAndDeflateFollowPlatformFlag() {
        assertIs<Boolean>(CompressionAlgorithm.Raw.supportsDictionary())
        assertTrue(CompressionAlgorithm.Raw.supportsDictionary() == supportsPresetDictionary)
        assertTrue(CompressionAlgorithm.Deflate.supportsDictionary() == supportsPresetDictionary)
    }

    @Test
    fun gzipWithDictionaryFailsOneShot() {
        if (!supportsSyncCompression) return

        val result =
            compress(
                "Hello".toReadBuffer(),
                CompressionAlgorithm.Gzip,
                dictionary = topicDictionary.toReadBuffer(),
            )
        assertIs<CompressionResult.Failure>(result)
    }

    @Test
    fun gzipWithDictionaryFailsStreamingCreate() {
        if (!supportsSyncCompression) return

        assertFailsWith<CompressionException> {
            StreamingCompressor.create(CompressionAlgorithm.Gzip, dictionary = topicDictionary.toReadBuffer())
        }
        assertFailsWith<CompressionException> {
            StreamingDecompressor.create(CompressionAlgorithm.Gzip, dictionary = topicDictionary.toReadBuffer())
        }
    }

    // =========================================================================
    // One-shot round trip with a dictionary
    // =========================================================================

    @Test
    fun oneShotRoundTripWithDictionaryRaw() {
        if (!supportsSyncCompression || !CompressionAlgorithm.Raw.supportsDictionary()) return

        val message = sampleMessage(3)
        val compressed =
            compress(message.toReadBuffer(), CompressionAlgorithm.Raw, dictionary = topicDictionary.toReadBuffer())
        assertIs<CompressionResult.Success>(compressed)

        val decompressed =
            decompress(compressed.buffer, CompressionAlgorithm.Raw, dictionary = topicDictionary.toReadBuffer())
        assertIs<CompressionResult.Success>(decompressed)
        assertEqualsText(message, decompressed.buffer)
    }

    @Test
    fun oneShotRoundTripWithDictionaryDeflate() {
        if (!supportsSyncCompression || !CompressionAlgorithm.Deflate.supportsDictionary()) return

        val message = sampleMessage(7)
        val compressed =
            compress(
                message.toReadBuffer(),
                CompressionAlgorithm.Deflate,
                dictionary = topicDictionary.toReadBuffer(),
            )
        assertIs<CompressionResult.Success>(compressed)

        val decompressed =
            decompress(compressed.buffer, CompressionAlgorithm.Deflate, dictionary = topicDictionary.toReadBuffer())
        assertIs<CompressionResult.Success>(decompressed)
        assertEqualsText(message, decompressed.buffer)
    }

    @Test
    fun wrongDictionaryFailsToDecompressDeflate() {
        if (!supportsSyncCompression || !CompressionAlgorithm.Deflate.supportsDictionary()) return

        val message = sampleMessage(1)
        val compressed =
            compress(
                message.toReadBuffer(),
                CompressionAlgorithm.Deflate,
                dictionary = topicDictionary.toReadBuffer(),
            )
        assertIs<CompressionResult.Success>(compressed)

        // Decompressing zlib-wrapped data without supplying any dictionary must fail
        // loudly (Z_NEED_DICT / needsDictionary with nothing to apply) rather than
        // silently produce garbage.
        val decompressed = decompress(compressed.buffer, CompressionAlgorithm.Deflate)
        assertIs<CompressionResult.Failure>(decompressed)
    }

    // =========================================================================
    // Streaming reuse: dictionary supplied once at create(), reapplied by reset()
    // =========================================================================

    @Test
    fun streamingCompressorReappliesDictionaryAcrossReset() {
        if (!supportsSyncCompression || !CompressionAlgorithm.Raw.supportsDictionary()) return

        val compressor =
            StreamingCompressor.create(CompressionAlgorithm.Raw, dictionary = topicDictionary.toReadBuffer())
        val decompressor =
            StreamingDecompressor.create(CompressionAlgorithm.Raw, dictionary = topicDictionary.toReadBuffer())
        try {
            repeat(20) { i ->
                val message = sampleMessage(i)
                val compressedChunks = mutableListOf<ReadBuffer>()
                compressor.compress(message.toReadBuffer()) { compressedChunks.add(it) }
                compressor.finish { compressedChunks.add(it) }
                compressor.reset()

                val decompressedChunks = mutableListOf<ReadBuffer>()
                for (chunk in compressedChunks) {
                    decompressor.decompress(chunk) { decompressedChunks.add(it) }
                }
                decompressor.finish { decompressedChunks.add(it) }
                decompressor.reset()

                val combined = combineBuffers(decompressedChunks)
                assertEqualsText(message, combined, "message index $i")
            }
        } finally {
            compressor.close()
            decompressor.close()
        }
    }

    // =========================================================================
    // Dictionary sourced from wrapper buffer types
    // =========================================================================

    @Test
    fun dictionaryFromPooledBufferRoundTrips() {
        if (!supportsSyncCompression || !CompressionAlgorithm.Raw.supportsDictionary()) return

        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)
        try {
            val dictBuffer = pool.allocate(topicDictionary.length)
            dictBuffer.writeString(topicDictionary)
            dictBuffer.resetForRead()

            val message = sampleMessage(5)
            val compressed = compress(message.toReadBuffer(), CompressionAlgorithm.Raw, dictionary = dictBuffer)
            assertIs<CompressionResult.Success>(compressed)

            val dictBuffer2 = pool.allocate(topicDictionary.length)
            dictBuffer2.writeString(topicDictionary)
            dictBuffer2.resetForRead()
            val decompressed =
                decompress(compressed.buffer, CompressionAlgorithm.Raw, dictionary = dictBuffer2)
            assertIs<CompressionResult.Success>(decompressed)
            assertEqualsText(message, decompressed.buffer)
        } finally {
            pool.clear()
        }
    }

    // =========================================================================
    // "Small dictionary is a free lunch": smaller output, compression still succeeds
    // fast across many messages (docs/investigations/compression-dictionaries/README.md)
    // =========================================================================

    @Test
    fun dictionaryReducesCompressedSizeForSmallSimilarMessages() {
        if (!supportsSyncCompression || !CompressionAlgorithm.Raw.supportsDictionary()) return

        val messages = (0 until 50).map { sampleMessage(it) }

        var withoutDictTotal = 0
        var withDictTotal = 0

        for (message in messages) {
            val plain = compress(message.toReadBuffer(), CompressionAlgorithm.Raw)
            assertIs<CompressionResult.Success>(plain)
            withoutDictTotal += plain.buffer.remaining()

            val withDict =
                compress(message.toReadBuffer(), CompressionAlgorithm.Raw, dictionary = topicDictionary.toReadBuffer())
            assertIs<CompressionResult.Success>(withDict)
            withDictTotal += withDict.buffer.remaining()

            // Round-trip check alongside the size measurement.
            val decompressed =
                decompress(withDict.buffer, CompressionAlgorithm.Raw, dictionary = topicDictionary.toReadBuffer())
            assertIs<CompressionResult.Success>(decompressed)
            assertEqualsText(message, decompressed.buffer)
        }

        assertTrue(
            withDictTotal < withoutDictTotal,
            "Dictionary-assisted compression ($withDictTotal bytes) should beat no dictionary " +
                "($withoutDictTotal bytes) for small, structurally-similar messages",
        )
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun assertEqualsText(
        expected: String,
        actual: ReadBuffer,
        message: String? = null,
    ) {
        val text = actual.readString(actual.remaining())
        assertTrue(expected == text, "${message ?: ""}: expected '$expected' but got '$text'".trim())
    }

    private fun combineBuffers(buffers: List<ReadBuffer>): PlatformBuffer {
        if (buffers.isEmpty()) return BufferFactory.Default.allocate(0)
        val totalSize = buffers.sumOf { it.remaining() }
        val combined = BufferFactory.Default.allocate(totalSize)
        for (buffer in buffers) {
            combined.write(buffer)
        }
        combined.resetForRead()
        return combined
    }
}
